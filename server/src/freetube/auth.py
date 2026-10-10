"""Who is calling: web sessions (cookie), devices (bearer token), and the login rate limit.

Only SHA-256 digests of session ids and tokens are stored. Raw values live in memory for one
request and are never logged.

Sliding renewal without a write per request: a session lives 30 days; when it was last
extended more than a day ago, it is pushed to now + 30 days and the cookie is re-sent. Same
idea for device_tokens.last_used_at, at hour granularity.
"""

import hashlib
import secrets
import sqlite3
import threading
import time
from collections import deque
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal

from argon2 import PasswordHasher
from argon2.exceptions import VerificationError
from fastapi import APIRouter, Depends, HTTPException, Request, Response

from freetube.db import ISO, get_db, now_iso, write_tx
from freetube.schemas import Device, DeviceCreated, DeviceIn, LoginIn, Me

COOKIE = "freetube_session"
SESSION_TTL = timedelta(days=30)
SESSION_RENEW_AFTER = timedelta(days=1)
DEVICE_SEEN_GRANULARITY = timedelta(hours=1)
LOGIN_LIMIT = 5
LOGIN_WINDOW_S = 60.0

router = APIRouter(prefix="/api")
Db = Annotated[sqlite3.Connection, Depends(get_db)]
UNAUTHORIZED = HTTPException(401, "unauthorized")


class LoginLimiter:
    """In-memory sliding window per IP: valid because there is one process (D4). Counts every
    attempt, successful ones included."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._hits: dict[str, deque[float]] = {}

    def allow(self, ip: str, now: float) -> bool:
        with self._lock:
            for key in [k for k, hits in self._hits.items() if hits[-1] <= now - LOGIN_WINDOW_S]:
                del self._hits[key]  # keeps the dict small
            hits = self._hits.setdefault(ip, deque())
            while hits and hits[0] <= now - LOGIN_WINDOW_S:
                hits.popleft()
            if len(hits) >= LOGIN_LIMIT:
                return False
            hits.append(now)
            return True


def sha256_hex(secret: str) -> str:
    return hashlib.sha256(secret.encode()).hexdigest()


def _parse(iso: str) -> datetime:
    return datetime.strptime(iso, ISO).replace(tzinfo=UTC)


def _set_cookie(response: Response, raw: str) -> None:
    response.set_cookie(
        COOKIE,
        raw,
        max_age=int(SESSION_TTL.total_seconds()),
        path="/",
        secure=True,
        httponly=True,
        samesite="strict",
    )


# --- Dependencies (brief §7) -----------------------------------------------------------------


def require_session(request: Request, response: Response, db: Db) -> None:
    raw = request.cookies.get(COOKIE)
    if not raw:
        raise UNAUTHORIZED
    digest = sha256_hex(raw)
    row = db.execute("SELECT expires_at FROM sessions WHERE id_sha256 = ?", (digest,)).fetchone()
    now = datetime.now(UTC)
    if row is None or _parse(row["expires_at"]) <= now:
        raise UNAUTHORIZED
    if _parse(row["expires_at"]) < now + SESSION_TTL - SESSION_RENEW_AFTER:
        with write_tx(db):
            db.execute(
                "UPDATE sessions SET expires_at = ? WHERE id_sha256 = ?",
                (now_iso(SESSION_TTL), digest),
            )
        _set_cookie(response, raw)


def require_device(request: Request, db: Db) -> int:
    """Returns the device id."""
    scheme, _, token = request.headers.get("Authorization", "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise UNAUTHORIZED
    row = db.execute(
        "SELECT id, last_used_at FROM device_tokens WHERE token_sha256 = ? AND revoked_at IS NULL",
        (sha256_hex(token),),
    ).fetchone()
    if row is None:
        raise UNAUTHORIZED
    last = row["last_used_at"]
    if last is None or _parse(last) < datetime.now(UTC) - DEVICE_SEEN_GRANULARITY:
        with write_tx(db):
            db.execute(
                "UPDATE device_tokens SET last_used_at = ? WHERE id = ?", (now_iso(), row["id"])
            )
    return row["id"]


def require_any(request: Request, response: Response, db: Db) -> Literal["session", "device"]:
    """A present Authorization header means device auth: a bad bearer is a 401, never a silent
    fallback to the cookie."""
    if "Authorization" in request.headers:
        require_device(request, db)
        return "device"
    require_session(request, response, db)
    return "session"


Session = Annotated[None, Depends(require_session)]
DeviceAuth = Annotated[int, Depends(require_device)]
AnyAuth = Annotated[Literal["session", "device"], Depends(require_any)]


# --- Routes ----------------------------------------------------------------------------------


@router.post("/auth/login", status_code=204)
def login(body: LoginIn, request: Request, response: Response, db: Db) -> None:
    ip = request.client.host if request.client else "unknown"
    # Before hashing: argon2 is the expensive part.
    if not request.app.state.login_limiter.allow(ip, time.monotonic()):
        raise HTTPException(429, "too_many_attempts")
    try:
        PasswordHasher().verify(request.app.state.settings.password_hash, body.password)
    except VerificationError:
        raise UNAUTHORIZED from None
    raw = secrets.token_urlsafe(32)
    with write_tx(db):
        db.execute(
            "INSERT INTO sessions (id_sha256, expires_at) VALUES (?, ?)",
            (sha256_hex(raw), now_iso(SESSION_TTL)),
        )
    _set_cookie(response, raw)


@router.post("/auth/logout", status_code=204)
def logout(request: Request, response: Response, db: Db, _: Session) -> None:
    with write_tx(db):
        db.execute(
            "DELETE FROM sessions WHERE id_sha256 = ?", (sha256_hex(request.cookies[COOKIE]),)
        )
    response.delete_cookie(COOKIE, path="/", secure=True, httponly=True, samesite="strict")


@router.get("/auth/me")
def me(kind: AnyAuth) -> Me:
    return Me(kind=kind)


@router.get("/devices")
def list_devices(db: Db, _: Session) -> list[Device]:
    rows = db.execute(
        "SELECT id, name, created_at, last_used_at, revoked_at FROM device_tokens ORDER BY id"
    )
    return [Device(**dict(row)) for row in rows]


@router.post("/devices", status_code=201)
def create_device(body: DeviceIn, db: Db, _: Session) -> DeviceCreated:
    token = secrets.token_urlsafe(32)
    with write_tx(db):
        row = db.execute(
            "INSERT INTO device_tokens (name, token_sha256) VALUES (?, ?) "
            "RETURNING id, name, created_at, last_used_at, revoked_at",
            (body.name, sha256_hex(token)),
        ).fetchone()
    return DeviceCreated(**dict(row), token=token)


@router.delete("/devices/{device_id}", status_code=204)
def revoke_device(device_id: int, db: Db, _: Session) -> None:
    """Idempotent. The row is kept so the web can show when a token was revoked."""
    with write_tx(db):
        row = db.execute("SELECT 1 FROM device_tokens WHERE id = ?", (device_id,)).fetchone()
        if row is None:
            raise HTTPException(404, "not_found")
        db.execute(
            "UPDATE device_tokens SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL",
            (now_iso(), device_id),
        )
