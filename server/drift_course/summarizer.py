from __future__ import annotations

import asyncio
import logging
import time
from typing import Any

import httpx

from .config import Settings
from .db import Database
from .llama_proc import LlamaServer

log = logging.getLogger(__name__)

# llama-server に流す要約指示。mid は "rewrite" 方式 (旧要約 + 新ターン → 新要約)。
SUMMARIZE_INSTRUCTION = (
    "これは会話ログの要約です。新しいターンを取り込み、時系列で簡潔に箇条書きに"
    "まとめ直してください。冗長さより情報密度を優先。日本語で。"
)


class Summarizer:
    """
    低優先の背景タスクで mid レイヤ要約を維持する。
    ポリシー:
      - llama-server が止まっているときは黙ってスキップ (tick ごと判定)。
      - DB ロックを持ったまま HTTP を叩かない。短いトランザクションで区切る。
      - 1 会話の失敗が他を止めないよう、per-conversation try/except。
    """

    def __init__(
        self,
        settings: Settings,
        db: Database,
        http: httpx.AsyncClient,
        llama: LlamaServer,
    ) -> None:
        self.settings = settings
        self.db = db
        self.http = http
        self.llama = llama
        self._task: asyncio.Task[None] | None = None
        self._stopping = asyncio.Event()

    async def start(self) -> None:
        if self._task is not None:
            return
        self._stopping.clear()
        self._task = asyncio.create_task(self._loop(), name="drift-summarizer")

    async def stop(self) -> None:
        if self._task is None:
            return
        self._stopping.set()
        self._task.cancel()
        try:
            await self._task
        except (asyncio.CancelledError, Exception):
            # 停止経路の例外はログのみ。上位シャットダウンを止めない。
            pass
        self._task = None

    async def _loop(self) -> None:
        interval = max(1.0, float(self.settings.summarize_interval_s))
        while not self._stopping.is_set():
            try:
                await asyncio.sleep(interval)
            except asyncio.CancelledError:
                return
            try:
                await self._tick()
            except asyncio.CancelledError:
                return
            except Exception:
                # tick 全体が落ちても次ループは回す。
                log.exception("summarizer tick failed")

    async def _tick(self) -> None:
        if not self.llama.is_running():
            return
        # list_conversations(None) は短いクエリ。ここでスナップショットを取って以降はロック外。
        convs = self.db.list_conversations(None)
        for conv in convs:
            convid = conv["id"]
            try:
                await self._summarize(convid)
            except Exception:
                log.exception("summarize failed for conversation %s", convid)

    async def _summarize(self, convid: str) -> dict[str, Any]:
        """
        1 会話を要約。戻り値はデバッグルートから観測するための小さな dict。
        skipped の場合は理由を返す。
        """
        if not self.llama.is_running():
            return {"skipped": "llama-not-running"}

        unsummarized = self.db.list_unsummarized_messages(convid)
        if len(unsummarized) < 6:
            return {"skipped": "too-few-messages"}

        total_tokens = self.db.count_unsummarized_tokens(convid)
        if total_tokens <= self.settings.summarize_trigger_tokens:
            return {"skipped": "below-trigger"}

        picked = self._pick_batch(unsummarized)
        if len(picked) < 2:
            return {"skipped": "no-batch"}

        prev_mid = self.db.get_memory(convid).get("mid", "")
        system_content = (prev_mid + "\n\n" + SUMMARIZE_INSTRUCTION).strip() if prev_mid else SUMMARIZE_INSTRUCTION
        user_content = "\n".join(f"[{m['role']}] {m['content']}" for m in picked)

        payload = {
            "messages": [
                {"role": "system", "content": system_content},
                {"role": "user", "content": user_content},
            ],
            "temperature": 0.2,
            "max_tokens": 512,
            "stream": False,
            "cache_prompt": True,
        }
        url = f"{self.llama.base_url}/v1/chat/completions"

        try:
            r = await self.http.post(url, json=payload, timeout=120.0)
        except (httpx.HTTPError, asyncio.TimeoutError) as e:
            log.warning("summarize POST failed for %s: %s", convid, e)
            return {"skipped": "http-error"}

        if r.status_code != 200:
            log.warning("summarize non-200 for %s: %s %s", convid, r.status_code, r.text[:200])
            return {"skipped": f"http-{r.status_code}"}

        try:
            obj = r.json()
            new_mid = obj["choices"][0]["message"]["content"]
        except (KeyError, IndexError, ValueError) as e:
            log.warning("summarize parse failed for %s: %s", convid, e)
            return {"skipped": "parse-error"}

        new_mid = (new_mid or "").strip()
        if not new_mid:
            return {"skipped": "empty-summary"}

        now = int(time.time())
        picked_ids = [m["id"] for m in picked]
        # DB 書き込みはここでまとめる。HTTP 完了後なのでロック保持は短い。
        self.db.set_memory(convid, "mid", new_mid)
        self.db.mark_summarized(picked_ids, now)
        log.info(
            "summarized convid=%s messages=%d new_mid_chars=%d",
            convid, len(picked_ids), len(new_mid),
        )
        return {"summarized_message_ids": picked_ids, "new_mid_chars": len(new_mid)}

    def _pick_batch(self, unsummarized: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """
        古い側から summarize_batch_tokens ぶん掴む。ただし新しい側に
        summarize_keep_tokens ぶんは raw として残す。
        token は LENGTH(content)/4 の荒見積もりと同じ規則。
        """
        batch_cap = self.settings.summarize_batch_tokens
        keep_cap = self.settings.summarize_keep_tokens

        # 末尾から keep_cap ぶん残すカット境界を求める。
        keep_tokens = 0
        keep_from_idx = len(unsummarized)
        for i in range(len(unsummarized) - 1, -1, -1):
            keep_tokens += len(unsummarized[i]["content"]) // 4
            if keep_tokens >= keep_cap:
                keep_from_idx = i
                break
        candidates = unsummarized[:keep_from_idx]

        picked: list[dict[str, Any]] = []
        acc = 0
        for m in candidates:
            t = len(m["content"]) // 4
            if picked and acc + t > batch_cap:
                break
            picked.append(m)
            acc += t
        return picked
