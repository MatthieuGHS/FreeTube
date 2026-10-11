"""App assembly: lifespan, routers, error mapping, logging.

`app` is a module-level object (Dockerfile CMD and the OpenAPI export import it). Settings,
storage and migrations happen in the lifespan, so importing `app` needs no environment.
"""

import logging
import sqlite3
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from botocore.exceptions import BotoCoreError, ClientError
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse, Response
from fastapi.staticfiles import StaticFiles
from starlette.exceptions import HTTPException as StarletteHTTPException

from freetube import auth, routes
from freetube.config import Settings
from freetube.db import migrate
from freetube.library import NotFound, RevConflict, UploadIncomplete
from freetube.logs import configure_logging
from freetube.storage import Storage

log = logging.getLogger("freetube")


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    configure_logging()
    settings = Settings.from_env()
    migrate(settings.db_path)
    app.state.settings = settings
    app.state.storage = Storage(settings.bucket, settings.region)
    app.state.login_limiter = auth.LoginLimiter()
    app.state.web = StaticFiles(directory=settings.web_dist) if settings.web_dist.is_dir() else None
    yield


app = FastAPI(
    title="FreeTube",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,  # the contract is server/openapi.json, not a live endpoint
    lifespan=lifespan,
    generate_unique_id_function=lambda route: route.name,  # clean names for openapi-typescript
)
app.include_router(auth.router)
app.include_router(routes.router)


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


# Registered after every API route, so it only sees paths no API route matched.
@app.get("/{path:path}", include_in_schema=False)
async def web(path: str, request: Request) -> Response:
    """The SPA: a built file, else index.html so client-side routes survive a reload.
    StaticFiles resolves the path and refuses anything outside web_dist."""
    files: StaticFiles | None = request.app.state.web
    if files is None or path == "api" or path.startswith("api/"):
        raise HTTPException(404, "Not Found")
    try:
        response = await files.get_response(path, request.scope)
    except StarletteHTTPException as exc:
        if exc.status_code != 404:
            raise
        path = "index.html"
        response = await files.get_response(path, request.scope)
    # Vite fingerprints everything under assets/; index.html must be revalidated to pick them up.
    immutable = path.startswith("assets/")
    response.headers["Cache-Control"] = (
        "public, max-age=31536000, immutable" if immutable else "no-cache"
    )
    return response


# One mapping from domain errors to HTTP: routes never build error responses by hand.
@app.exception_handler(NotFound)
def _not_found(request: Request, exc: NotFound) -> JSONResponse:
    return JSONResponse({"detail": "not_found"}, status_code=404)


@app.exception_handler(RevConflict)
def _conflict(request: Request, exc: RevConflict) -> JSONResponse:
    return JSONResponse({"detail": "rev_conflict", "current_rev": exc.current_rev}, 409)


@app.exception_handler(UploadIncomplete)
def _incomplete(request: Request, exc: UploadIncomplete) -> JSONResponse:
    return JSONResponse({"detail": "upload_incomplete", "missing": exc.missing}, 409)


@app.exception_handler(ClientError)
@app.exception_handler(BotoCoreError)
def _storage(request: Request, exc: Exception) -> JSONResponse:
    code = exc.response["Error"]["Code"] if isinstance(exc, ClientError) else type(exc).__name__
    log.error("storage error", extra={"code": code, "path": request.url.path})
    return JSONResponse({"detail": "storage_unavailable"}, status_code=502)


@app.exception_handler(sqlite3.OperationalError)
def _db_busy(request: Request, exc: sqlite3.OperationalError) -> JSONResponse:
    if "locked" not in str(exc):
        raise exc
    return JSONResponse({"detail": "busy"}, status_code=503, headers={"Retry-After": "1"})
