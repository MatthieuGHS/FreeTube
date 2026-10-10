"""`python -m freetube.backup` and `python -m freetube.backup --restore <key>`.

Runs inside the api container (accepted deviation from brief §10, which assumed the AWS CLI on
the host): the same two steps, with tools the container already has.
- sqlite3.Connection.backup() is the online-backup API behind `sqlite3 .backup`: consistent
  while the app writes;
- boto3 uploads to backups/freetube-YYYYMMDD.db with the instance role.
"""

import argparse
import logging
import os
import sqlite3
from contextlib import closing
from datetime import UTC, datetime
from pathlib import Path

from freetube.config import Settings
from freetube.logs import configure_logging
from freetube.storage import Storage

log = logging.getLogger("freetube.backup")


def _check_integrity(path: Path) -> None:
    with closing(sqlite3.connect(path)) as conn:
        result = conn.execute("PRAGMA integrity_check").fetchone()[0]
    if result != "ok":
        raise RuntimeError(f"integrity check failed on {path.name}: {result}")


def backup(db_path: Path, storage: Storage, day: str) -> str:
    """Returns the S3 key. Same day twice overwrites that key (versioning keeps the previous)."""
    tmp = db_path.with_name("backup-tmp.db")  # on /data: same disk, not the image layer
    try:
        with closing(sqlite3.connect(db_path)) as src, closing(sqlite3.connect(tmp)) as dst:
            src.backup(dst)
        _check_integrity(tmp)  # a corrupt backup is worse than none
        key = f"backups/freetube-{day}.db"
        storage.upload_file(tmp, key)
        return key
    finally:
        tmp.unlink(missing_ok=True)


def restore(db_path: Path, storage: Storage, key: str) -> None:
    """Run with the api STOPPED (see docs/deploy.md). Never deletes the current database: it is
    kept as freetube.db.before-restore. If the current database is lost too, clients must reset
    their sync cursor (documented in docs/deploy.md)."""
    incoming = db_path.with_name("restore-tmp.db")
    storage.download_file(key, incoming)
    _check_integrity(incoming)
    if db_path.exists():
        # server_rev must never go back: clients hold cursors from the current database, and a
        # rev handed out twice would be skipped by their next pull.
        with closing(sqlite3.connect(db_path)) as old, closing(sqlite3.connect(incoming)) as new:
            (current,) = old.execute("SELECT value FROM meta WHERE key = 'server_rev'").fetchone()
            with new:
                new.execute(
                    "UPDATE meta SET value = max(value, ?) WHERE key = 'server_rev'", (current,)
                )
        os.replace(db_path, db_path.with_name(db_path.name + ".before-restore"))
    for suffix in ("-wal", "-shm"):  # stale journal of the old database must not be replayed
        db_path.with_name(db_path.name + suffix).unlink(missing_ok=True)
    os.replace(incoming, db_path)


def main() -> None:
    parser = argparse.ArgumentParser(prog="python -m freetube.backup")
    parser.add_argument("--restore", metavar="KEY", help="e.g. backups/freetube-20261010.db")
    args = parser.parse_args()
    configure_logging()
    settings = Settings.from_env()
    storage = Storage(settings.bucket, settings.region)
    if args.restore:
        restore(settings.db_path, storage, args.restore)
        log.info("restore done", extra={"key": args.restore})
    else:
        key = backup(settings.db_path, storage, datetime.now(UTC).strftime("%Y%m%d"))
        log.info("backup done", extra={"key": key})


if __name__ == "__main__":
    main()
