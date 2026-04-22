from __future__ import annotations

import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles

from .config import Settings, ensure_token
from .db import Database
from .llama_proc import LlamaServer
from .routes import characters, chat, conversations, health, models
from .summarizer import Summarizer

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = Settings()
    token = ensure_token(settings)
    db = Database(settings.db_path)
    llama = LlamaServer(settings)
    http = httpx.AsyncClient()

    app.state.settings = settings
    app.state.token = token
    app.state.db = db
    app.state.llama = llama
    app.state.http = http

    log.warning("DriftCourse token: %s", token)
    await llama.start()

    summarizer: Summarizer | None = None
    if settings.summarize_enabled:
        summarizer = Summarizer(settings, db, http, llama)
        await summarizer.start()
    app.state.summarizer = summarizer

    try:
        yield
    finally:
        if summarizer is not None:
            await summarizer.stop()
        await llama.stop()
        await http.aclose()
        db.close()


def create_app() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    app = FastAPI(title="DriftCourse", version="0.1.0", lifespan=lifespan)
    # LAN 内/tailnet から file:// や http://localhost で動く Web クライアントも叩けるように。
    # Bearer 認証 (Authorization header) のみ使っていて Cookie は使わないので allow_credentials=False で OK。
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_credentials=False,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.include_router(health.router)
    app.include_router(models.router)
    app.include_router(characters.router)
    app.include_router(conversations.router)
    app.include_router(chat.router)
    # 外部の静的サイトを同一オリジンで配信したい場合は
    # DRIFT_STATIC_MOUNTS="mountpath=dir,mountpath2=dir2" で指定する (任意・空なら何もしない)。
    import os
    for entry in os.environ.get("DRIFT_STATIC_MOUNTS", "").split(","):
        entry = entry.strip()
        if not entry or "=" not in entry:
            continue
        mount_path, src_dir = entry.split("=", 1)
        mount_path, src_dir = mount_path.strip(), src_dir.strip()
        if os.path.isdir(src_dir):
            app.mount(mount_path, StaticFiles(directory=src_dir, html=True), name=mount_path.strip("/"))
            log.warning("mounted static: %s -> %s", mount_path, src_dir)
    return app


app = create_app()


def run() -> None:
    import uvicorn

    settings = Settings()
    uvicorn.run(
        "drift_course.main:app",
        host=settings.host,
        port=settings.port,
        log_level="info",
    )


if __name__ == "__main__":
    run()
