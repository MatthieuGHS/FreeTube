"""The ONLY module that writes tracks, playlists, revs and tombstones.

Web routes, upload routes and sync ops all call these functions. The caller states who wins
with a guard:

    IfRev(rev)  web write: optimistic lock. Stale rev -> RevConflict (409). Never resurrects:
                a target the web cannot see (missing, tombstoned, still uploading) -> NotFound.
    Create()    web POST /playlists: the id must be new, else RevConflict.
    PHONE_WINS  sync op: always applied, resurrects tombstones, never raises for valid input.

So web writes and sync ops cannot diverge: the policy is a value, not a second code path.

Invariants owned here:
- every committed change to a track/playlist row gets a fresh rev from db.bump_rev in the
  same transaction (deletions included);
- a no-op (same values, deleting a tombstone, op on an unknown track) does NOT bump rev;
- playlist items are replaced as a block together with the playlist's rev bump;
- tracks still being uploaded are invisible to every reader (pull and web lists).

Functions take a connection already inside db.write_tx / db.read_tx.
"""

import logging
import sqlite3
from dataclasses import dataclass
from typing import Literal, overload
from uuid import UUID

from freetube.db import bump_rev, now_iso
from freetube.schemas import (
    DeletePlaylistOp,
    DeleteTrackOp,
    OpsIn,
    Playlist,
    SyncPull,
    Track,
    UpdateTrackOp,
    UpsertPlaylistOp,
)

log = logging.getLogger("freetube.library")

# A reader can see a track once it is ready; a tombstone stays visible so deletions propagate.
VISIBLE_TRACK = "(status = 'ready' OR deleted_at IS NOT NULL)"


@dataclass(frozen=True)
class IfRev:
    rev: int


@dataclass(frozen=True)
class Create:
    pass


@dataclass(frozen=True)
class PhoneWins:
    pass


PHONE_WINS = PhoneWins()


class NotFound(Exception):
    pass


class RevConflict(Exception):
    def __init__(self, current_rev: int) -> None:
        self.current_rev = current_rev


class UploadIncomplete(Exception):
    def __init__(self, missing: list[str]) -> None:
        self.missing = missing


# --- Reads -----------------------------------------------------------------------------------


def _track_row(conn: sqlite3.Connection, video_id: str) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM tracks WHERE video_id = ?", (video_id,)).fetchone()


def _playlist_row(conn: sqlite3.Connection, playlist_id: UUID) -> sqlite3.Row | None:
    return conn.execute("SELECT * FROM playlists WHERE id = ?", (str(playlist_id),)).fetchone()


def _must(row: sqlite3.Row | None) -> sqlite3.Row:
    if row is None:
        raise NotFound
    return row


def _load_track(conn: sqlite3.Connection, video_id: str) -> Track:
    return Track(**dict(_must(_track_row(conn, video_id))))


def _load_playlist(conn: sqlite3.Connection, playlist_id: UUID) -> Playlist:
    return _playlists(conn, [_must(_playlist_row(conn, playlist_id))])[0]


def _playlists(conn: sqlite3.Connection, rows: list[sqlite3.Row]) -> list[Playlist]:
    items: dict[str, list[str]] = {row["id"]: [] for row in rows}
    if items:
        marks = ",".join("?" * len(items))
        for item in conn.execute(
            f"SELECT playlist_id, video_id FROM playlist_items WHERE playlist_id IN ({marks}) "  # noqa: S608
            "ORDER BY playlist_id, position",
            list(items),
        ):
            items[item["playlist_id"]].append(item["video_id"])
    return [Playlist(**dict(row), items=items[row["id"]]) for row in rows]


def get_track(conn: sqlite3.Connection, video_id: str) -> Track | None:
    """Any state, tombstones included (upload/complete/stream routes decide)."""
    row = _track_row(conn, video_id)
    return Track(**dict(row)) if row else None


def list_tracks(conn: sqlite3.Connection) -> list[Track]:
    rows = conn.execute(
        "SELECT * FROM tracks WHERE status = 'ready' AND deleted_at IS NULL ORDER BY created_at"
    )
    return [Track(**dict(row)) for row in rows]


def list_playlists(conn: sqlite3.Connection) -> list[Playlist]:
    rows = conn.execute(
        "SELECT * FROM playlists WHERE deleted_at IS NULL ORDER BY created_at"
    ).fetchall()
    return _playlists(conn, rows)


def changes_since(conn: sqlite3.Connection, since: int) -> SyncPull:
    """Call inside read_tx: the cursor and the rows come from one snapshot."""
    cursor = conn.execute("SELECT value FROM meta WHERE key = 'server_rev'").fetchone()[0]
    tracks = conn.execute(
        f"SELECT * FROM tracks WHERE rev > ? AND {VISIBLE_TRACK} ORDER BY rev",  # noqa: S608
        (since,),
    )
    playlists = conn.execute(
        "SELECT * FROM playlists WHERE rev > ? ORDER BY rev", (since,)
    ).fetchall()
    return SyncPull(
        tracks=[Track(**dict(row)) for row in tracks],
        playlists=_playlists(conn, playlists),
        cursor=cursor,
    )


# --- Guards ----------------------------------------------------------------------------------


def _web_view(row: sqlite3.Row | None) -> sqlite3.Row | None:
    """What the web can see of a track: nothing while it is still being uploaded."""
    if row is None or (row["status"] == "pending_upload" and row["deleted_at"] is None):
        return None
    return row


def _check_web(row: sqlite3.Row | None, guard: IfRev) -> None:
    """Rev is checked before the tombstone: a phone deletion reads as "modified elsewhere"."""
    row = _must(row)
    if row["rev"] != guard.rev:
        raise RevConflict(row["rev"])
    if row["deleted_at"] is not None:
        raise NotFound


# --- Track writes ----------------------------------------------------------------------------


@overload
def update_track(
    conn: sqlite3.Connection, video_id: str, title: str, artist: str, guard: IfRev
) -> Track: ...
@overload
def update_track(
    conn: sqlite3.Connection, video_id: str, title: str, artist: str, guard: PhoneWins
) -> Track | None: ...
def update_track(
    conn: sqlite3.Connection, video_id: str, title: str, artist: str, guard: IfRev | PhoneWins
) -> Track | None:
    """Rename. Returns None when the phone renames a track the server never received: the
    later upload call carries the phone's current title and artist, so nothing is lost."""
    row = _track_row(conn, video_id)
    if isinstance(guard, IfRev):
        _check_web(_web_view(row), guard)
    elif row is None:
        log.info("update_track on unknown track ignored", extra={"video_id": video_id})
        return None
    if (row["title"], row["artist"], row["deleted_at"]) == (title, artist, None):
        return Track(**dict(row))
    conn.execute(
        "UPDATE tracks SET title = ?, artist = ?, deleted_at = NULL, rev = ? WHERE video_id = ?",
        (title, artist, bump_rev(conn), video_id),
    )
    return _load_track(conn, video_id)


def delete_track(conn: sqlite3.Connection, video_id: str, guard: IfRev | PhoneWins) -> None:
    """Soft delete. S3 objects stay until purge (30-day resurrection window)."""
    row = _track_row(conn, video_id)
    if isinstance(guard, IfRev):
        _check_web(_web_view(row), guard)
    elif row is None or row["deleted_at"] is not None:
        return
    conn.execute(
        "UPDATE tracks SET deleted_at = ?, rev = ? WHERE video_id = ?",
        (now_iso(), bump_rev(conn), video_id),
    )


def register_upload(
    conn: sqlite3.Connection,
    video_id: str,
    title: str,
    artist: str,
    duration_s: int,
    audio_ext: str,
    audio_mime: str,
) -> Track:
    """POST /tracks/{id}/upload, idempotent. Phone-only, so the phone wins here too.

    A tombstone is resurrected as pending_upload, not ready: the phone re-sends the files and
    complete re-checks them, which covers objects lost to a partially failed purge.
    """
    row = _track_row(conn, video_id)
    meta = (title, artist, duration_s, audio_ext, audio_mime)
    if row is None:
        conn.execute(
            "INSERT INTO tracks (video_id, title, artist, duration_s, audio_ext, audio_mime, "
            "status, rev) VALUES (?, ?, ?, ?, ?, ?, 'pending_upload', ?)",
            (video_id, *meta, bump_rev(conn)),
        )
    elif row["deleted_at"] is not None or (
        row["status"] == "pending_upload"
        and tuple(row[k] for k in ("title", "artist", "duration_s", "audio_ext", "audio_mime"))
        != meta
    ):
        conn.execute(
            "UPDATE tracks SET title = ?, artist = ?, duration_s = ?, audio_ext = ?, "
            "audio_mime = ?, status = 'pending_upload', deleted_at = NULL, rev = ? "
            "WHERE video_id = ?",
            (*meta, bump_rev(conn), video_id),
        )
    return _load_track(conn, video_id)


def mark_ready(conn: sqlite3.Connection, video_id: str) -> Track:
    """POST /tracks/{id}/complete, after the route checked both objects outside the tx."""
    row = _track_row(conn, video_id)
    if row is None or row["deleted_at"] is not None:
        raise NotFound
    if row["status"] == "pending_upload":
        conn.execute(
            "UPDATE tracks SET status = 'ready', rev = ? WHERE video_id = ?",
            (bump_rev(conn), video_id),
        )
    return _load_track(conn, video_id)


# --- Playlist writes -------------------------------------------------------------------------


def put_playlist(
    conn: sqlite3.Connection,
    playlist_id: UUID,
    name: str,
    source_youtube_id: str | None,
    items: list[str],
    guard: IfRev | Create | PhoneWins,
) -> Playlist:
    """Full-state write: web POST (Create) / PUT (IfRev), sync upsert_playlist (PHONE_WINS).
    Items are stored as given (order, duplicates); no check that the tracks exist."""
    row = _playlist_row(conn, playlist_id)
    if isinstance(guard, Create) and row is not None:
        raise RevConflict(row["rev"])
    if isinstance(guard, IfRev):
        _check_web(row, guard)
    if row is not None and row["deleted_at"] is None:
        current = _playlists(conn, [row])[0]
        if (current.name, current.source_youtube_id, current.items) == (
            name,
            source_youtube_id,
            items,
        ):
            return current
    conn.execute(
        "INSERT INTO playlists (id, name, source_youtube_id, rev) VALUES (?, ?, ?, ?) "
        "ON CONFLICT (id) DO UPDATE SET name = excluded.name, "
        "source_youtube_id = excluded.source_youtube_id, rev = excluded.rev, deleted_at = NULL",
        (str(playlist_id), name, source_youtube_id, bump_rev(conn)),
    )
    conn.execute("DELETE FROM playlist_items WHERE playlist_id = ?", (str(playlist_id),))
    conn.executemany(
        "INSERT INTO playlist_items (playlist_id, position, video_id) VALUES (?, ?, ?)",
        [(str(playlist_id), position, video_id) for position, video_id in enumerate(items)],
    )
    return _load_playlist(conn, playlist_id)


def delete_playlist(conn: sqlite3.Connection, playlist_id: UUID, guard: IfRev | PhoneWins) -> None:
    """Soft delete. Items are kept: a resurrecting upsert replaces them anyway."""
    row = _playlist_row(conn, playlist_id)
    if isinstance(guard, IfRev):
        _check_web(row, guard)
    elif row is None or row["deleted_at"] is not None:
        return
    conn.execute(
        "UPDATE playlists SET deleted_at = ?, rev = ? WHERE id = ?",
        (now_iso(), bump_rev(conn), str(playlist_id)),
    )


# --- Sync push -------------------------------------------------------------------------------


def apply_ops(conn: sqlite3.Connection, batch: OpsIn, device_id: int) -> int:
    """Apply a batch in order, inside ONE write_tx owned by the caller (all or nothing,
    processed_ops included: a retry after a lost response is all skips)."""
    touched: set[tuple[str, str]] = set()
    for op in batch.ops:
        inserted = conn.execute(
            "INSERT OR IGNORE INTO processed_ops (op_id) VALUES (?)", (str(op.op_id),)
        ).rowcount
        if not inserted:
            continue
        match op:
            case UpsertPlaylistOp(payload=p):
                _note_conflict(conn, batch.cursor, touched, device_id, "playlist", str(p.id))
                put_playlist(conn, p.id, p.name, p.source_youtube_id, p.items, PHONE_WINS)
            case DeletePlaylistOp(payload=p):
                _note_conflict(conn, batch.cursor, touched, device_id, "playlist", str(p.id))
                delete_playlist(conn, p.id, PHONE_WINS)
            case UpdateTrackOp(payload=p):
                _note_conflict(conn, batch.cursor, touched, device_id, "track", p.video_id)
                update_track(conn, p.video_id, p.title, p.artist, PHONE_WINS)
            case DeleteTrackOp(payload=p):
                _note_conflict(conn, batch.cursor, touched, device_id, "track", p.video_id)
                delete_track(conn, p.video_id, PHONE_WINS)
    return len(batch.ops)


def _note_conflict(
    conn: sqlite3.Connection,
    cursor: int | None,
    touched: set[tuple[str, str]],
    device_id: int,
    entity: Literal["track", "playlist"],
    entity_id: str,
) -> None:
    """Log (never block) when the phone overwrites a change it had not pulled. An entity
    already written earlier in this batch has rev > cursor because of the batch itself."""
    key = (entity, entity_id)
    if cursor is not None and key not in touched:
        row = (
            _track_row(conn, entity_id)
            if entity == "track"
            else _playlist_row(conn, UUID(entity_id))
        )
        if row is not None and row["rev"] > cursor:
            log.warning(
                "sync conflict, phone wins",
                extra={
                    "entity": entity,
                    "id": entity_id,
                    "server_rev": row["rev"],
                    "client_cursor": cursor,
                    "device_id": device_id,
                },
            )
    touched.add(key)
