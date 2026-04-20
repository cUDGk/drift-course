from __future__ import annotations

from fastapi import APIRouter, Request

router = APIRouter(tags=["health"])


@router.get("/health")
async def health(request: Request) -> dict[str, object]:
    llama = request.app.state.llama
    return {
        "ok": llama.is_running(),
        "current_model": llama.current_model,
        "draft_model": llama.current_draft,
    }
