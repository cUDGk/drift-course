from __future__ import annotations

import hmac

from fastapi import Depends, HTTPException, Request, status


def require_bearer(request: Request) -> None:
    expected: str | None = getattr(request.app.state, "token", None)
    if not expected:
        raise HTTPException(status_code=500, detail="server token not configured")
    header = request.headers.get("authorization", "")
    scheme, _, presented = header.partition(" ")
    if scheme.lower() != "bearer" or not presented:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="bearer token required",
            headers={"WWW-Authenticate": "Bearer"},
        )
    if not hmac.compare_digest(presented, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid token")


BearerDep = Depends(require_bearer)
