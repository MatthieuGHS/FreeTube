"""Connections, the two transaction kinds, the rev counter and migrations.

Locking model (one uvicorn process, sync endpoints in a threadpool, WAL):
- one sqlite3 connection per request; SQLite's file lock is the only lock;
- writes run in `write_tx` (BEGIN IMMEDIATE): the write lock is taken up front, so concurrent
  writers queue on busy_timeout instead of failing with SQLITE_BUSY on lock upgrade;
- multi-statement reads run in `read_tx`: one WAL snapshot.

Why the pull cursor never skips a change: `bump_rev` runs inside the write transaction and
SQLite has one writer at a time, so revs are handed out in commit order. A reader that sees
rev N sees every committed rev < N. Pull returns `server_rev` read in the same snapshot.
"""

import sqlite3
from collections.abc import Iterator
from contextlib import closing, contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path

from alembic import command
from alembic.config import Config
from fastapi import Request

BUSY_TIMEOUT_MS = 5000
ISO = "%Y-%m-%dT%H:%M:%SZ"  # fixed width: string order is time order


def now_iso(delta: timedelta = timedelta()) -> str:
    return (datetime.now(UTC) + delta).strftime(ISO)


def connect(db_path: Path) -> sqlite3.Connection:
    # FastAPI may run a yield dependency and its endpoint on different pool threads;
    # a connection is still never shared between requests.
    conn = sqlite3.connect(db_path, autocommit=True, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute(f"PRAGMA busy_timeout = {BUSY_TIMEOUT_MS}")
    return conn


def get_db(request: Request) -> Iterator[sqlite3.Connection]:
    conn = connect(request.app.state.settings.db_path)
    try:
        yield conn
    finally:
        conn.close()


@contextmanager
def write_tx(conn: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    conn.execute("BEGIN IMMEDIATE")
    try:
        yield conn
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    conn.execute("COMMIT")


@contextmanager
def read_tx(conn: sqlite3.Connection) -> Iterator[sqlite3.Connection]:
    conn.execute("BEGIN")
    try:
        yield conn
    finally:
        conn.execute("COMMIT")


def bump_rev(conn: sqlite3.Connection) -> int:
    """Allocate the next server_rev. Only valid inside write_tx."""
    if not conn.in_transaction:
        raise RuntimeError("bump_rev outside write_tx")
    return conn.execute(
        "UPDATE meta SET value = value + 1 WHERE key = 'server_rev' RETURNING value"
    ).fetchone()[0]


def migrate(db_path: Path) -> None:
    """alembic upgrade head. Run at startup: one process, so no migration race."""
    cfg = Config()
    cfg.set_main_option("script_location", "freetube:migrations")
    cfg.set_main_option("sqlalchemy.url", f"sqlite:///{db_path}")
    command.upgrade(cfg, "head")
    with closing(sqlite3.connect(db_path)) as conn:
        conn.execute("PRAGMA journal_mode = WAL")  # persistent in the file
