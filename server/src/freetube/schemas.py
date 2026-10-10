"""Every type that crosses the HTTP boundary. These models ARE the OpenAPI contract (brief §7).

External input is validated here, once; library.py trusts what it receives. Track and
Playlist double as the records library.py returns: the wire shape and the record are the same
thing in this app, a second set of classes would only add a conversion.
"""

from datetime import datetime
from typing import Annotated, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, StringConstraints

VideoId = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{11}$")]
AudioExt = Literal["m4a", "webm", "opus"]  # goes into an S3 key: whitelist only
AudioMime = Annotated[str, StringConstraints(pattern=r"^audio/[a-z0-9.+-]{1,40}$")]
Title = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=300)]
Name = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
YoutubePlaylistId = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{2,64}$")]
Items = Annotated[list[VideoId], Field(max_length=5000)]


class Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")  # a typo in a client field is a 422, not a no-op


# --- Records ---------------------------------------------------------------------------------


class Track(BaseModel):
    video_id: str
    title: str
    artist: str
    duration_s: int
    audio_ext: str
    audio_mime: str
    status: Literal["pending_upload", "ready"]
    rev: int
    deleted_at: datetime | None  # tombstone when set
    created_at: datetime


class Playlist(BaseModel):
    id: UUID
    name: str
    source_youtube_id: str | None
    items: list[str]  # in order; may reference tracks the server cannot play: clients hide them
    rev: int
    deleted_at: datetime | None
    created_at: datetime


class SyncPull(BaseModel):
    """Changes with rev > since, tombstones included. Tracks still being uploaded are never
    listed: a track appears when it becomes ready (that transition bumps its rev)."""

    tracks: list[Track]
    playlists: list[Playlist]
    cursor: int


# --- Auth & devices --------------------------------------------------------------------------


class LoginIn(Strict):
    password: Annotated[str, StringConstraints(min_length=1, max_length=1024)]


class Me(BaseModel):
    kind: Literal["session", "device"]


class DeviceIn(Strict):
    name: Name


class Device(BaseModel):
    id: int
    name: str
    created_at: datetime
    last_used_at: datetime | None
    revoked_at: datetime | None


class DeviceCreated(Device):
    token: str  # shown once; only its SHA-256 is stored


# --- Upload ----------------------------------------------------------------------------------


class UploadIn(Strict):
    title: Title
    artist: Title
    duration_s: Annotated[int, Field(ge=0, le=86_400)]
    audio_ext: AudioExt
    audio_mime: AudioMime


class UploadPending(BaseModel):
    status: Literal["pending_upload"]
    audio_put_url: str  # the PUT must send Content-Type: <audio_mime> (it is signed)
    thumb_put_url: str  # the PUT must send Content-Type: image/jpeg


class UploadReady(BaseModel):
    status: Literal["ready"]


UploadOut = Annotated[UploadPending | UploadReady, Field(discriminator="status")]


# --- Web writes (the expected rev travels in If-Match) ---------------------------------------


class TrackPatch(Strict):
    title: Title
    artist: Title


class PlaylistIn(Strict):
    name: Name
    source_youtube_id: YoutubePlaylistId | None = None
    items: Items


class PlaylistCreate(PlaylistIn):
    id: UUID


# --- Sync push -------------------------------------------------------------------------------


class UpsertPlaylistPayload(PlaylistCreate):
    pass


class DeletePlaylistPayload(Strict):
    id: UUID


class UpdateTrackPayload(Strict):
    video_id: VideoId
    title: Title
    artist: Title


class DeleteTrackPayload(Strict):
    video_id: VideoId


class UpsertPlaylistOp(Strict):
    op_id: UUID
    type: Literal["upsert_playlist"]
    payload: UpsertPlaylistPayload


class DeletePlaylistOp(Strict):
    op_id: UUID
    type: Literal["delete_playlist"]
    payload: DeletePlaylistPayload


class UpdateTrackOp(Strict):
    op_id: UUID
    type: Literal["update_track"]
    payload: UpdateTrackPayload


class DeleteTrackOp(Strict):
    op_id: UUID
    type: Literal["delete_track"]
    payload: DeleteTrackPayload


Op = Annotated[
    UpsertPlaylistOp | DeletePlaylistOp | UpdateTrackOp | DeleteTrackOp,
    Field(discriminator="type"),
]


class OpsIn(Strict):
    ops: Annotated[list[Op], Field(max_length=500)]
    # Extension to brief §7 (accepted 2026-10-10): the client's pull cursor at push time.
    # Only used to log conflicts; absent -> no conflict logs.
    cursor: Annotated[int, Field(ge=0)] | None = None


class OpsOut(BaseModel):
    """200 means the whole batch is committed: the client deletes every op it sent."""

    acked: int
