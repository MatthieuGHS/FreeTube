"""Initial schema."""

# Raw SQL: the app talks to SQLite through stdlib sqlite3, so this DDL is the single place the
# schema is written down. STRICT tables + CHECKs reject bad rows even if a code path forgets to
# validate.

from alembic import op

revision = "0001"
down_revision = None

NOW = "(strftime('%Y-%m-%dT%H:%M:%SZ', 'now'))"

DDL = [
    "CREATE TABLE meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL) STRICT",
    "INSERT INTO meta (key, value) VALUES ('server_rev', 0)",
    f"""
    CREATE TABLE tracks (
        video_id   TEXT PRIMARY KEY
                   CHECK (length(video_id) = 11 AND video_id NOT GLOB '*[^A-Za-z0-9_-]*'),
        title      TEXT NOT NULL,
        artist     TEXT NOT NULL,
        duration_s INTEGER NOT NULL CHECK (duration_s >= 0),
        audio_ext  TEXT NOT NULL,
        audio_mime TEXT NOT NULL,
        status     TEXT NOT NULL CHECK (status IN ('pending_upload', 'ready')),
        rev        INTEGER NOT NULL,
        deleted_at TEXT,
        created_at TEXT NOT NULL DEFAULT {NOW}
    ) STRICT
    """,
    "CREATE INDEX tracks_rev ON tracks (rev)",
    f"""
    CREATE TABLE playlists (
        id                TEXT PRIMARY KEY,
        name              TEXT NOT NULL,
        source_youtube_id TEXT,
        rev               INTEGER NOT NULL,
        deleted_at        TEXT,
        created_at        TEXT NOT NULL DEFAULT {NOW}
    ) STRICT
    """,
    "CREATE INDEX playlists_rev ON playlists (rev)",
    # No foreign key on video_id: the phone pushes playlists before their tracks are uploaded,
    # and a track may be tombstoned or purged. Clients hide ids they cannot play.
    """
    CREATE TABLE playlist_items (
        playlist_id TEXT NOT NULL REFERENCES playlists (id) ON DELETE CASCADE,
        position    INTEGER NOT NULL,
        video_id    TEXT NOT NULL,
        PRIMARY KEY (playlist_id, position)
    ) STRICT
    """,
    f"""
    CREATE TABLE device_tokens (
        id           INTEGER PRIMARY KEY,
        name         TEXT NOT NULL,
        token_sha256 TEXT NOT NULL UNIQUE,
        created_at   TEXT NOT NULL DEFAULT {NOW},
        last_used_at TEXT,
        revoked_at   TEXT
    ) STRICT
    """,
    f"""
    CREATE TABLE sessions (
        id_sha256  TEXT PRIMARY KEY,
        created_at TEXT NOT NULL DEFAULT {NOW},
        expires_at TEXT NOT NULL
    ) STRICT
    """,
    f"""
    CREATE TABLE processed_ops (
        op_id      TEXT PRIMARY KEY,
        applied_at TEXT NOT NULL DEFAULT {NOW}
    ) STRICT
    """,
]


def upgrade() -> None:
    for statement in DDL:
        op.execute(statement)


def downgrade() -> None:
    raise NotImplementedError("no downgrades: restore a backup instead (docs/deploy.md)")
