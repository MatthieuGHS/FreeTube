import sqlite3
from contextlib import closing

import pytest
from conftest import BUCKET, add_ready_track

from freetube import purge as purge_module
from freetube.backup import backup, restore
from freetube.db import connect, now_iso
from freetube.purge import purge
from freetube.storage import Storage


@pytest.fixture
def storage(s3) -> Storage:
    return Storage(BUCKET, "eu-west-3")


def _keys(s3) -> set[str]:
    return {o["Key"] for o in s3.list_objects_v2(Bucket=BUCKET).get("Contents", [])}


def _tombstone(db, video_id: str, days_ago: int) -> None:
    from datetime import timedelta

    db.execute(
        "UPDATE tracks SET deleted_at = ? WHERE video_id = ?",
        (now_iso(-timedelta(days=days_ago)), video_id),
    )


def test_purge_deletes_old_tombstones_with_their_objects(
    client, device, s3, db, env, storage
) -> None:
    for video_id in ("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"):
        add_ready_track(client, device, s3, video_id)
    _tombstone(db, "aaaaaaaaaaa", 31)
    _tombstone(db, "bbbbbbbbbbb", 29)
    with closing(connect(env / "freetube.db")) as conn:
        assert purge(conn, storage) == 1
        assert purge(conn, storage) == 0  # rerunnable
    remaining = [r[0] for r in db.execute("SELECT video_id FROM tracks ORDER BY video_id")]
    assert remaining == ["bbbbbbbbbbb", "ccccccccccc"]
    assert _keys(s3) == {
        f"{p}/{v}.{e}"
        for v in ("bbbbbbbbbbb", "ccccccccccc")
        for p, e in (("audio", "m4a"), ("thumbs", "jpg"))
    }


def test_purge_skips_a_track_resurrected_after_selection(
    client, device, s3, db, env, storage, monkeypatch
) -> None:
    add_ready_track(client, device, s3, "aaaaaaaaaaa")
    _tombstone(db, "aaaaaaaaaaa", 31)
    real_write_tx = purge_module.write_tx

    def resurrect_then_lock(conn):
        db.execute("UPDATE tracks SET deleted_at = NULL WHERE video_id = 'aaaaaaaaaaa'")
        return real_write_tx(conn)

    monkeypatch.setattr("freetube.purge.write_tx", resurrect_then_lock)
    with closing(connect(env / "freetube.db")) as conn:
        assert purge(conn, storage) == 0
    assert len(_keys(s3)) == 2


def test_purge_keeps_playlist_items_of_purged_tracks(client, device, s3, db, env, storage) -> None:
    add_ready_track(client, device, s3, "aaaaaaaaaaa")
    client.post(
        "/api/sync/ops",
        headers=device,
        json={
            "ops": [
                {
                    "op_id": "0b5d3c55-6a43-4f39-9f0e-5c0c4c1d8f11",
                    "type": "upsert_playlist",
                    "payload": {
                        "id": "6f1c2c0e-8a3f-4f7e-9a51-0d6f3c7b2a10",
                        "name": "P",
                        "items": ["aaaaaaaaaaa"],
                    },
                }
            ]
        },
    )
    rev = db.execute("SELECT rev FROM playlists").fetchone()[0]
    _tombstone(db, "aaaaaaaaaaa", 31)
    with closing(connect(env / "freetube.db")) as conn:
        purge(conn, storage)
    assert db.execute("SELECT rev FROM playlists").fetchone()[0] == rev
    assert db.execute("SELECT video_id FROM playlist_items").fetchone()[0] == "aaaaaaaaaaa"


def test_purge_cleans_expired_sessions_and_old_processed_ops(web, db, env, storage) -> None:
    db.execute("UPDATE sessions SET expires_at = '2000-01-01T00:00:00Z'")
    db.execute("INSERT INTO processed_ops (op_id, applied_at) VALUES ('x', '2000-01-01T00:00:00Z')")
    with closing(connect(env / "freetube.db")) as conn:
        purge(conn, storage)
    assert db.execute("SELECT count(*) FROM sessions").fetchone()[0] == 0
    assert db.execute("SELECT count(*) FROM processed_ops").fetchone()[0] == 0


def test_backup_uploads_a_consistent_copy_and_restore_keeps_the_old_db(
    client, device, s3, env, storage, tmp_path
) -> None:
    add_ready_track(client, device, s3, "aaaaaaaaaaa")
    db_path = env / "freetube.db"
    key = backup(db_path, storage, "20261010")
    assert key == "backups/freetube-20261010.db"
    assert not (env / "backup-tmp.db").exists()

    add_ready_track(client, device, s3, "bbbbbbbbbbb")  # after the backup
    restore(db_path, storage, key)
    with closing(sqlite3.connect(db_path)) as conn:
        assert [r[0] for r in conn.execute("SELECT video_id FROM tracks")] == ["aaaaaaaaaaa"]
    with closing(sqlite3.connect(env / "freetube.db.before-restore")) as conn:
        assert conn.execute("SELECT count(*) FROM tracks").fetchone()[0] == 2
        before = conn.execute("SELECT value FROM meta WHERE key = 'server_rev'").fetchone()[0]
    with closing(sqlite3.connect(db_path)) as conn:
        restored = conn.execute("SELECT value FROM meta WHERE key = 'server_rev'").fetchone()[0]
    assert restored == before  # revs never go back: clients' cursors stay valid
