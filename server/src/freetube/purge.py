"""`python -m freetube.purge`: daily, via `docker compose exec -T api python -m freetube.purge`.

Hard-deletes tracks tombstoned for more than 30 days and their S3 objects, plus expired
sessions and old processed_ops. Idempotent: a rerun after a crash converges.
"""

import logging
import sqlite3
from datetime import timedelta

from freetube.config import Settings
from freetube.db import connect, now_iso, write_tx
from freetube.logs import configure_logging
from freetube.storage import Storage

RETENTION = timedelta(days=30)
log = logging.getLogger("freetube.purge")


def purge(conn: sqlite3.Connection, storage: Storage) -> int:
    """Returns the number of tracks purged.

    Each track: re-check inside the write tx, delete S3 objects, delete the row. Deleting S3
    *inside* the transaction holds the write lock ~100 ms per track, once a day, and closes the
    race "the phone resurrects the track between the S3 delete and the row delete" (which
    would leave a ready track without audio). If S3 fails, the row survives for tomorrow.
    Playlist items are not touched: a dangling video_id is a normal state, and rewriting a
    playlist would change its content behind the phone's back.
    """
    cutoff = now_iso(-RETENTION)
    candidates = [
        row["video_id"]
        for row in conn.execute("SELECT video_id FROM tracks WHERE deleted_at < ?", (cutoff,))
    ]
    purged = 0
    for video_id in candidates:
        with write_tx(conn):
            row = conn.execute(
                "SELECT audio_ext FROM tracks WHERE video_id = ? AND deleted_at < ?",
                (video_id, cutoff),
            ).fetchone()
            if row is None:  # resurrected since the selection
                continue
            storage.delete_track_objects(video_id, row["audio_ext"])
            conn.execute("DELETE FROM tracks WHERE video_id = ?", (video_id,))
            purged += 1
    with write_tx(conn):
        conn.execute("DELETE FROM sessions WHERE expires_at < ?", (now_iso(),))
        # An outbox retry happens minutes after a lost response, never 30 days later.
        conn.execute("DELETE FROM processed_ops WHERE applied_at < ?", (cutoff,))
    return purged


def main() -> None:
    configure_logging()
    settings = Settings.from_env()
    conn = connect(settings.db_path)
    try:
        count = purge(conn, Storage(settings.bucket, settings.region))
    finally:
        conn.close()
    log.info("purge done", extra={"tracks_purged": count})


if __name__ == "__main__":
    main()
