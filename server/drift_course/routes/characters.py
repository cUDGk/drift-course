from __future__ import annotations

from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from ..auth import BearerDep

router = APIRouter(prefix="/characters", tags=["characters"], dependencies=[BearerDep])


class CharacterIn(BaseModel):
    name: str
    system_prompt: str = ""
    card: dict[str, Any] = {}


class CharacterPatch(BaseModel):
    name: str | None = None
    system_prompt: str | None = None
    card: dict[str, Any] | None = None


class CharacterCopyRequest(BaseModel):
    name: str | None = None


@router.get("")
async def list_characters(request: Request) -> list[dict[str, Any]]:
    return request.app.state.db.list_characters()


@router.post("")
async def create_character(body: CharacterIn, request: Request) -> dict[str, Any]:
    return request.app.state.db.create_character(body.name, body.system_prompt, body.card)


@router.get("/{cid}")
async def get_character(cid: str, request: Request) -> dict[str, Any]:
    row = request.app.state.db.get_character(cid)
    if row is None:
        raise HTTPException(status_code=404, detail="character not found")
    return row


@router.patch("/{cid}")
async def patch_character(cid: str, body: CharacterPatch, request: Request) -> dict[str, Any]:
    row = request.app.state.db.update_character(cid, body.name, body.system_prompt, body.card)
    if row is None:
        raise HTTPException(status_code=404, detail="character not found")
    return row


@router.delete("/{cid}")
async def delete_character(cid: str, request: Request) -> dict[str, str]:
    if not request.app.state.db.delete_character(cid):
        raise HTTPException(status_code=404, detail="character not found")
    return {"status": "deleted"}


@router.post("/{cid}/copy")
async def copy_character(
    cid: str,
    request: Request,
    body: CharacterCopyRequest | None = None,
) -> dict[str, Any]:
    name = body.name if body is not None else None
    row = request.app.state.db.copy_character(cid, name)
    if row is None:
        raise HTTPException(status_code=404, detail="character not found")
    return row
