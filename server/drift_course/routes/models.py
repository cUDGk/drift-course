from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from ..auth import BearerDep

router = APIRouter(prefix="/models", tags=["models"], dependencies=[BearerDep])


class ModelInfo(BaseModel):
    name: str
    size_bytes: int
    is_current: bool
    is_draft: bool


class ModelsResponse(BaseModel):
    current: str
    draft: str
    available: list[ModelInfo]


class LoadRequest(BaseModel):
    model: str
    draft: str | None = None


@router.get("", response_model=ModelsResponse)
async def list_models(request: Request) -> ModelsResponse:
    llama = request.app.state.llama
    models_dir = request.app.state.settings.models_dir
    files = sorted(p for p in models_dir.glob("*.gguf") if p.is_file())
    infos = [
        ModelInfo(
            name=p.name,
            size_bytes=p.stat().st_size,
            is_current=(p.name == llama.current_model),
            is_draft=(p.name == llama.current_draft),
        )
        for p in files
    ]
    return ModelsResponse(current=llama.current_model, draft=llama.current_draft, available=infos)


@router.post("/load")
async def load_model(body: LoadRequest, request: Request) -> dict[str, str]:
    llama = request.app.state.llama
    models_dir = request.app.state.settings.models_dir
    if not (models_dir / body.model).exists():
        raise HTTPException(status_code=404, detail=f"model not found: {body.model}")
    if body.draft and not (models_dir / body.draft).exists():
        raise HTTPException(status_code=404, detail=f"draft model not found: {body.draft}")
    await llama.switch(model=body.model, draft=body.draft)
    return {"current": llama.current_model, "draft": llama.current_draft}
