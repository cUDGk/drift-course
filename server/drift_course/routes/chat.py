from __future__ import annotations

import json
from collections.abc import AsyncIterator
from typing import Any

import httpx
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from ..auth import BearerDep

router = APIRouter(tags=["chat"], dependencies=[BearerDep])


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messages: list[ChatMessage]
    temperature: float = 0.7
    top_p: float = 0.9
    max_tokens: int = 1024
    stream: bool = True
    # llama-server 固有: 多ターンで効く (llm-exp-chatcache 参照)
    cache_prompt: bool = True


async def _stream_llama(client: httpx.AsyncClient, url: str, payload: dict[str, Any]) -> AsyncIterator[bytes]:
    async with client.stream("POST", url, json=payload, timeout=None) as r:
        if r.status_code != 200:
            body = await r.aread()
            raise HTTPException(status_code=r.status_code, detail=body.decode("utf-8", "replace"))
        async for chunk in r.aiter_raw():
            if chunk:
                yield chunk


@router.post("/v1/chat")
async def chat(body: ChatRequest, request: Request) -> StreamingResponse:
    llama = request.app.state.llama
    if not llama.is_running():
        raise HTTPException(status_code=503, detail="llama-server not running")
    payload: dict[str, Any] = {
        "messages": [m.model_dump() for m in body.messages],
        "temperature": body.temperature,
        "top_p": body.top_p,
        "max_tokens": body.max_tokens,
        "stream": body.stream,
        "cache_prompt": body.cache_prompt,
    }
    url = f"{llama.base_url}/v1/chat/completions"
    client: httpx.AsyncClient = request.app.state.http

    if not body.stream:
        r = await client.post(url, json=payload, timeout=None)
        if r.status_code != 200:
            raise HTTPException(status_code=r.status_code, detail=r.text)
        return StreamingResponse(
            iter([json.dumps(r.json()).encode()]),
            media_type="application/json",
        )

    return StreamingResponse(
        _stream_llama(client, url, payload),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
