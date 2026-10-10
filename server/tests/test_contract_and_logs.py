import logging
import threading
import uuid
from contextlib import closing

from conftest import PASSWORD, add_ready_track

from freetube import library
from freetube.db import connect, read_tx, write_tx
from freetube.export_openapi import OPENAPI_PATH, render
from freetube.library import PHONE_WINS


def test_openapi_json_is_up_to_date() -> None:
    assert OPENAPI_PATH.read_text() == render(), (
        "server/openapi.json is stale: run `uv run python -m freetube.export_openapi`"
    )


def test_concurrent_writes_and_pulls_never_miss_a_change(client, env) -> None:
    """Writers on their own connections + a puller following the cursor: every rev is unique
    and the puller ends up with exactly the final state."""
    path = env / "freetube.db"
    ids = [uuid.uuid4() for _ in range(4)]
    seen: dict[str, int] = {}
    done = threading.Event()

    def writer(playlist_id: uuid.UUID) -> None:
        with closing(connect(path)) as conn:
            for i in range(25):
                with write_tx(conn):
                    library.put_playlist(conn, playlist_id, f"v{i}", None, [], PHONE_WINS)

    def puller() -> None:
        cursor = 0
        with closing(connect(path)) as conn:
            while True:
                finished = done.is_set()
                with read_tx(conn):
                    pulled = library.changes_since(conn, cursor)
                for p in pulled.playlists:
                    seen[str(p.id)] = p.rev
                cursor = pulled.cursor
                if finished:
                    return

    threads = [threading.Thread(target=writer, args=(i,)) for i in ids]
    reader = threading.Thread(target=puller)
    reader.start()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    done.set()
    reader.join()

    with closing(connect(path)) as conn, read_tx(conn):
        final = library.changes_since(conn, 0)
    assert final.cursor == 100  # 4 writers x 25 writes, one rev each, no gap, no duplicate
    assert seen == {str(p.id): p.rev for p in final.playlists}


def test_logs_never_contain_secrets(client, s3, caplog) -> None:
    caplog.set_level(logging.DEBUG)
    client.post("/api/auth/login", json={"password": PASSWORD})
    cookie = client.cookies["freetube_session"]
    token = client.post("/api/devices", json={"name": "A06"}).json()["token"]
    device = {"Authorization": f"Bearer {token}"}
    add_ready_track(client, device, s3, "aaaaaaaaaaa")
    client.get("/api/tracks/aaaaaaaaaaa/stream", headers=device, follow_redirects=False)
    for secret in (PASSWORD, cookie, token, "X-Amz-Signature"):
        assert secret not in caplog.text


def test_migrate_is_rerunnable(tmp_path) -> None:
    from freetube.db import migrate

    path = tmp_path / "m.db"
    migrate(path)
    migrate(path)
    with closing(connect(path)) as conn:
        assert conn.execute("SELECT value FROM meta WHERE key = 'server_rev'").fetchone()[0] == 0
        assert conn.execute("PRAGMA journal_mode").fetchone()[0] == "wal"
