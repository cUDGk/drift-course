from __future__ import annotations

import asyncio
import logging
import subprocess
from pathlib import Path

import httpx

from .config import Settings

log = logging.getLogger(__name__)


class LlamaServer:
    """
    llama-server を subprocess として起動・監視する。
    モデル切替は terminate → 新しい引数で再起動。llama-cpp-python ではなく
    subprocess を挟む理由は llm-exp-chatcache で検証済みの --cache-prompt /
    --draft-max 等をそのまま渡せるのと、推論側クラッシュを Python から隔離できる点。
    """

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.proc: subprocess.Popen[bytes] | None = None
        self._log_fh = None
        self.current_model: str = settings.default_model
        self.current_draft: str = settings.default_draft_model

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.settings.llama_port}"

    def _build_args(self, model: str, draft: str) -> list[str]:
        s = self.settings
        model_path = s.models_dir / model
        args: list[str] = [
            str(s.llama_server_bin),
            "-m", str(model_path),
            "--host", "127.0.0.1",
            "--port", str(s.llama_port),
            "-c", str(s.ctx_size),
            "-ngl", str(s.n_gpu_layers),
            "--cache-type-k", s.cache_type_k,
            "--cache-type-v", s.cache_type_v,
            "--cache-reuse", "256",
        ]
        # 新しめの llama.cpp は -fa が on|off|auto を引数に取るので値を明示する。
        args.extend(["-fa", "on" if s.flash_attn else "off"])
        if draft:
            draft_path = s.models_dir / draft
            args += [
                "-md", str(draft_path),
                "--draft-max", str(s.draft_max),
                "--draft-min", "2",
            ]
        return args

    async def start(self, model: str | None = None, draft: str | None = None) -> None:
        if self.proc and self.proc.poll() is None:
            raise RuntimeError("llama-server is already running")
        model = model or self.current_model
        draft = draft if draft is not None else self.current_draft
        args = self._build_args(model, draft)
        log.info("starting llama-server: %s", " ".join(args))
        # llama-server の起動失敗時に原因を追えるよう、stderr は別ファイルに残す。
        log_path = Path(self.settings.db_path).parent / "llama-server.log"
        self._log_fh = open(log_path, "wb")  # noqa: SIM115 — 親プロセス終了まで保持
        self.proc = subprocess.Popen(
            args,
            stdout=self._log_fh,
            stderr=subprocess.STDOUT,
            cwd=str(Path(self.settings.llama_server_bin).parent),
        )
        self.current_model = model
        self.current_draft = draft
        await self._wait_healthy()

    async def _wait_healthy(self, timeout_s: float = 120.0) -> None:
        # llama-server はモデルロードに最大で数十秒かかる (30B MoE で 20–40s)。
        # /health が "ok" を返すまでポーリング。
        url = f"{self.base_url}/health"
        deadline = asyncio.get_event_loop().time() + timeout_s
        async with httpx.AsyncClient(timeout=2.0) as client:
            while asyncio.get_event_loop().time() < deadline:
                if self.proc is None or self.proc.poll() is not None:
                    raise RuntimeError("llama-server exited during startup")
                try:
                    r = await client.get(url)
                    if r.status_code == 200:
                        return
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(0.5)
        raise TimeoutError(f"llama-server health check timed out after {timeout_s}s")

    async def stop(self) -> None:
        if self.proc is None:
            return
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                await asyncio.to_thread(self.proc.wait, 10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                await asyncio.to_thread(self.proc.wait)
        self.proc = None
        if self._log_fh is not None:
            self._log_fh.close()
            self._log_fh = None

    async def switch(self, model: str, draft: str | None = None) -> None:
        await self.stop()
        await self.start(model=model, draft=draft if draft is not None else self.current_draft)

    def is_running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None
