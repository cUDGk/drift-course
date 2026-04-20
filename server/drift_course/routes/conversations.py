from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from ..auth import BearerDep

router = APIRouter(prefix="/conversations", tags=["conversations"], dependencies=[BearerDep])


class NewConversation(BaseModel):
    character_id: str
    title: str = ""


class PostMessage(BaseModel):
    content: str
    temperature: float = 0.7
    top_p: float = 0.9
    max_tokens: int = 4096


class MemoryPatch(BaseModel):
    content: str


class ForkRequest(BaseModel):
    from_message_id: str
    inclusive: bool = False
    title: str = ""


class ConversationPatch(BaseModel):
    title: str


class MessagePatch(BaseModel):
    content: str


@router.get("")
async def list_conversations(request: Request, character_id: str | None = None) -> list[dict[str, Any]]:
    return request.app.state.db.list_conversations(character_id)


@router.post("")
async def new_conversation(body: NewConversation, request: Request) -> dict[str, Any]:
    db = request.app.state.db
    if db.get_character(body.character_id) is None:
        raise HTTPException(status_code=404, detail="character not found")
    return db.create_conversation(body.character_id, body.title)


@router.get("/{convid}")
async def get_conversation(convid: str, request: Request) -> dict[str, Any]:
    conv = request.app.state.db.get_conversation(convid)
    if conv is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    return conv


@router.delete("/{convid}")
async def delete_conversation(convid: str, request: Request) -> dict[str, str]:
    if not request.app.state.db.delete_conversation(convid):
        raise HTTPException(status_code=404, detail="conversation not found")
    return {"status": "deleted"}


@router.get("/{convid}/messages")
async def get_messages(convid: str, request: Request) -> list[dict[str, Any]]:
    db = request.app.state.db
    if db.get_conversation(convid) is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    return db.list_messages(convid)


@router.get("/{convid}/memory")
async def get_memory(convid: str, request: Request) -> dict[str, str]:
    db = request.app.state.db
    if db.get_conversation(convid) is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    return db.get_memory(convid)


@router.patch("/{convid}/memory/{layer}")
async def patch_memory(convid: str, layer: str, body: MemoryPatch, request: Request) -> dict[str, str]:
    db = request.app.state.db
    if db.get_conversation(convid) is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    try:
        db.set_memory(convid, layer, body.content)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e)) from e
    return {"status": "ok"}


CARD_LABELS = [
    ("gender", "性別"),
    ("user_address", "ユーザの呼び方"),
    ("description", "人物"),
    ("personality", "性格"),
    ("scenario", "状況"),
    ("first_mes", "初回挨拶"),
    ("mes_example", "例会話"),
    ("memory", "記憶"),
    ("response_style", "応答のスタイル・分量"),
]

# LLM に投げてはいけない内部用キー。`icon` は data URL なので system prompt に流すとトークンを大量に食い潰すし、
# `bare` は「素モデルです」という UI 向けマーカーで、LLM には無意味。どちらも [その他] にも載せない。
INTERNAL_CARD_KEYS = {"icon", "bare"}


def _compose_system(character: dict[str, Any], memory: dict[str, str]) -> str:
    # DRIFT の 4 層メモリ + カード項目をラベル付き日本語セクションとして結合する。
    # サイズ管理は将来の要約ジョブ (priority=low) で行う前提で今は単純結合。
    parts: list[str] = []
    if character.get("system_prompt"):
        parts.append(character["system_prompt"].strip())
    card = character.get("card") or {}
    for key, label in CARD_LABELS:
        v = card.get(key)
        if isinstance(v, str) and v.strip():
            parts.append(f"[{label}]\n{v.strip()}")
    known = {kk for kk, _ in CARD_LABELS}
    extras = {
        k: v for k, v in card.items()
        if k not in known and k not in INTERNAL_CARD_KEYS
    }
    if extras:
        parts.append(f"[その他]\n{json.dumps(extras, ensure_ascii=False, indent=2)}")
    for layer_name, label in (("long", "長期記憶"), ("mid", "中期要約"), ("recent", "直近")):
        if memory.get(layer_name):
            parts.append(f"[{label}]\n{memory[layer_name]}")
    return "\n\n".join(parts).strip()


@router.post("/{convid}/messages")
async def post_message(convid: str, body: PostMessage, request: Request) -> StreamingResponse:
    db = request.app.state.db
    llama = request.app.state.llama
    conv = db.get_conversation(convid)
    if conv is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    character = db.get_character(conv["character_id"])
    if character is None:
        raise HTTPException(status_code=500, detail="orphan conversation (character missing)")
    if not llama.is_running():
        raise HTTPException(status_code=503, detail="llama-server not running")

    db.append_message(convid, "user", body.content)

    # 要約済みメッセージは mid レイヤに入っているので raw では再送しない。
    history = db.list_unsummarized_messages(convid)
    system = _compose_system(character, db.get_memory(convid))
    messages: list[dict[str, str]] = []
    if system:
        messages.append({"role": "system", "content": system})
    for m in history:
        messages.append({"role": m["role"], "content": m["content"]})

    payload = {
        "messages": messages,
        "temperature": body.temperature,
        "top_p": body.top_p,
        "max_tokens": body.max_tokens,
        "stream": True,
        "cache_prompt": True,
    }
    url = f"{llama.base_url}/v1/chat/completions"
    client: httpx.AsyncClient = request.app.state.http

    # httpx の stream() は context manager なので、StreamingResponse の
    # 非同期ジェネレータ内で開始・終了させる必要がある。
    async def gen() -> AsyncIterator[bytes]:
        async with client.stream("POST", url, json=payload, timeout=None) as r:
            if r.status_code != 200:
                err = await r.aread()
                raise HTTPException(status_code=r.status_code, detail=err.decode("utf-8", "replace"))
            accumulated: list[str] = []
            try:
                async for line in r.aiter_lines():
                    if not line:
                        yield b"\n"
                        continue
                    yield (line + "\n").encode("utf-8")
                    if line.startswith("data: "):
                        payload_s = line[6:].strip()
                        if payload_s == "[DONE]":
                            continue
                        try:
                            obj = json.loads(payload_s)
                            delta = obj.get("choices", [{}])[0].get("delta", {}).get("content") or ""
                            if delta:
                                accumulated.append(delta)
                        except json.JSONDecodeError:
                            pass
            finally:
                reply = "".join(accumulated)
                if reply:
                    db.append_message(convid, "assistant", reply)

    return StreamingResponse(
        gen(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


@router.patch("/{convid}")
async def patch_conversation(convid: str, body: ConversationPatch, request: Request) -> dict[str, Any]:
    db = request.app.state.db
    row = db.update_conversation_title(convid, body.title)
    if row is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    return row


@router.patch("/{convid}/messages/{mid}")
async def patch_message(convid: str, mid: str, body: MessagePatch, request: Request) -> dict[str, Any]:
    db = request.app.state.db
    if db.get_conversation(convid) is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    row = db.update_message_content(convid, mid, body.content)
    if row is None:
        raise HTTPException(status_code=404, detail="message not found")
    return row


@router.post("/{convid}/fork")
async def fork_conversation(convid: str, body: ForkRequest, request: Request) -> dict[str, Any]:
    db = request.app.state.db
    src = db.get_conversation(convid)
    if src is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    title = body.title.strip() if body.title else ""
    if not title:
        src_title = src.get("title") or ""
        title = f"{src_title} (分岐)" if src_title else "(分岐)"
    new_conv = db.fork_conversation(
        src_convid=convid,
        stop_message_id=body.from_message_id,
        inclusive=body.inclusive,
        title=title,
    )
    if new_conv is None:
        raise HTTPException(status_code=404, detail="from_message_id not found")
    return new_conv


@router.post("/{convid}/summarize")
async def trigger_summarize(convid: str, request: Request) -> dict[str, Any]:
    db = request.app.state.db
    if db.get_conversation(convid) is None:
        raise HTTPException(status_code=404, detail="conversation not found")
    summarizer = getattr(request.app.state, "summarizer", None)
    if summarizer is None:
        raise HTTPException(status_code=503, detail="summarizer not enabled")
    return await summarizer._summarize(convid)
