"""Library HTTP surface: upload, playback, web reads/writes, sync.

Thin by rule: parse, pick the guard, open the transaction, call library, shape the response.
No SQL and no S3 key here. Handlers are plain `def` (sqlite3 and boto3 block): threadpool.

Web optimistic lock: `If-Match: <rev>` on PATCH/PUT/DELETE (one header for the three verbs;
DELETE has no body). Missing -> 428, stale -> 409.
"""

import sqlite3
from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends, Header, HTTPException, Path, Query, Request
from fastapi.responses import RedirectResponse

from freetube import library
from freetube.auth import AnyAuth, DeviceAuth, Session
from freetube.db import get_db, read_tx, write_tx
from freetube.library import Create, IfRev, NotFound, UploadIncomplete
from freetube.schemas import (
    OpsIn,
    OpsOut,
    Playlist,
    PlaylistCreate,
    PlaylistIn,
    SyncPull,
    Track,
    TrackPatch,
    UploadIn,
    UploadOut,
    UploadPending,
    UploadReady,
    VideoId,
)
from freetube.storage import Kind, Storage

router = APIRouter(prefix="/api")
Db = Annotated[sqlite3.Connection, Depends(get_db)]
VideoIdPath = Annotated[VideoId, Path()]
THUMB_CACHE = "private, max-age=3000"  # < GET_TTL_S: a cached redirect never outlives its URL


def get_storage(request: Request) -> Storage:
    return request.app.state.storage


S3 = Annotated[Storage, Depends(get_storage)]


def expected_rev(if_match: Annotated[str | None, Header()] = None) -> int:
    if if_match is None:
        raise HTTPException(428, "if_match_required")
    try:
        return int(if_match.strip('"'))
    except ValueError:
        raise HTTPException(422, "if_match_not_a_rev") from None


ExpectedRev = Annotated[int, Depends(expected_rev)]


# --- Upload (device) -------------------------------------------------------------------------


@router.post("/tracks/{video_id}/upload")
def upload(video_id: VideoIdPath, body: UploadIn, db: Db, s3: S3, _: DeviceAuth) -> UploadOut:
    with write_tx(db):
        track = library.register_upload(
            db, video_id, body.title, body.artist, body.duration_s, body.audio_ext, body.audio_mime
        )
    if track.status == "ready":
        return UploadReady(status="ready")
    # Presigning is a local HMAC, no network call.
    return UploadPending(
        status="pending_upload",
        audio_put_url=s3.presign_put("audio", video_id, track.audio_ext, track.audio_mime),
        thumb_put_url=s3.presign_put("thumb", video_id, track.audio_ext, track.audio_mime),
    )


@router.post("/tracks/{video_id}/complete")
def complete(video_id: VideoIdPath, db: Db, s3: S3, _: DeviceAuth) -> Track:
    track = library.get_track(db, video_id)
    if track is None or track.deleted_at is not None:
        raise NotFound
    if track.status == "ready":
        return track
    # HeadObject outside the transaction; mark_ready re-checks the row inside it.
    if missing := s3.missing_objects(video_id, track.audio_ext):
        raise UploadIncomplete(list(missing))
    with write_tx(db):
        return library.mark_ready(db, video_id)


# --- Playback (session or device) ------------------------------------------------------------


def _redirect(db: sqlite3.Connection, s3: Storage, video_id: str, kind: Kind, cache: str):
    track = library.get_track(db, video_id)
    if track is None or track.status != "ready" or track.deleted_at is not None:
        raise NotFound
    url = s3.presign_get(kind, video_id, track.audio_ext)
    return RedirectResponse(url, status_code=302, headers={"Cache-Control": cache})


@router.get("/tracks/{video_id}/stream", status_code=302, response_class=RedirectResponse)
def stream(video_id: VideoIdPath, db: Db, s3: S3, _: AnyAuth) -> RedirectResponse:
    # no-store: a browser must not replay an expired pre-signed URL from its cache.
    return _redirect(db, s3, video_id, "audio", "no-store")


@router.get("/tracks/{video_id}/thumb", status_code=302, response_class=RedirectResponse)
def thumb(video_id: VideoIdPath, db: Db, s3: S3, _: AnyAuth) -> RedirectResponse:
    return _redirect(db, s3, video_id, "thumb", THUMB_CACHE)


# --- Web reads and writes (session) ----------------------------------------------------------


@router.get("/tracks")
def list_tracks(db: Db, _: Session) -> list[Track]:
    return library.list_tracks(db)


@router.patch("/tracks/{video_id}")
def patch_track(
    video_id: VideoIdPath, body: TrackPatch, rev: ExpectedRev, db: Db, _: Session
) -> Track:
    with write_tx(db):
        return library.update_track(db, video_id, body.title, body.artist, IfRev(rev))


@router.delete("/tracks/{video_id}", status_code=204)
def delete_track(video_id: VideoIdPath, rev: ExpectedRev, db: Db, _: Session) -> None:
    with write_tx(db):
        library.delete_track(db, video_id, IfRev(rev))


@router.get("/playlists")
def list_playlists(db: Db, _: Session) -> list[Playlist]:
    with read_tx(db):
        return library.list_playlists(db)


@router.post("/playlists", status_code=201)
def create_playlist(body: PlaylistCreate, db: Db, _: Session) -> Playlist:
    with write_tx(db):
        return library.put_playlist(
            db, body.id, body.name, body.source_youtube_id, body.items, Create()
        )


@router.put("/playlists/{playlist_id}")
def put_playlist(
    playlist_id: UUID, body: PlaylistIn, rev: ExpectedRev, db: Db, _: Session
) -> Playlist:
    with write_tx(db):
        return library.put_playlist(
            db, playlist_id, body.name, body.source_youtube_id, body.items, IfRev(rev)
        )


@router.delete("/playlists/{playlist_id}", status_code=204)
def delete_playlist(playlist_id: UUID, rev: ExpectedRev, db: Db, _: Session) -> None:
    with write_tx(db):
        library.delete_playlist(db, playlist_id, IfRev(rev))


# --- Sync ------------------------------------------------------------------------------------


@router.get("/sync")
def pull(db: Db, _: AnyAuth, since: Annotated[int, Query(ge=0)] = 0) -> SyncPull:
    with read_tx(db):
        return library.changes_since(db, since)


@router.post("/sync/ops")
def push(body: OpsIn, db: Db, device_id: DeviceAuth) -> OpsOut:
    with write_tx(db):
        return OpsOut(acked=library.apply_ops(db, body, device_id))
