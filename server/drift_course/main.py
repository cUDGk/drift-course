from __future__ import annotations

import logging
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI

from .config import Settings, ensure_token
from .llama_proc import LlamaServer
from .routes import chat, health, models

log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = Settings()
    token = ensure_token(settings)
    llama = LlamaServer(settings)
    http = httpx.AsyncClient()

    app.state.settings = settings
    app.state.token = token
    app.state.llama = llama
    app.state.http = http

    log.warning("DriftCourse token: %s", token)
    await llama.start()
    try:
        yield
    finally:
        await llama.stop()
        await http.aclose()


def create_app() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    app = FastAPI(title="DriftCourse", version="0.1.0", lifespan=lifespan)
    app.include_router(health.router)
    app.include_router(models.router)
    app.include_router(chat.router)
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
