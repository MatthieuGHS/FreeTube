import logging
import uuid

from conftest import UPLOAD, add_ready_track

PLAYLIST = "6f1c2c0e-8a3f-4f7e-9a51-0d6f3c7b2a10"


def op(type_: str, payload: dict) -> dict:
    return {"op_id": str(uuid.uuid4()), "type": type_, "payload": payload}


def upsert(name: str = "Route", items: list[str] | None = None) -> dict:
    return op("upsert_playlist", {"id": PLAYLIST, "name": name, "items": items or []})


def push(client, device, *ops, cursor=None):
    body = {"ops": list(ops)} | ({"cursor": cursor} if cursor is not None else {})
    return client.post("/api/sync/ops", json=body, headers=device)


def test_pull_since_zero_returns_everything_with_cursor(client, device, s3) -> None:
    add_ready_track(client, device, s3, "aaaaaaaaaaa")
    push(client, device, upsert(items=["aaaaaaaaaaa"]))
    pulled = client.get("/api/sync?since=0", headers=device).json()
    assert [t["video_id"] for t in pulled["tracks"]] == ["aaaaaaaaaaa"]
    assert pulled["playlists"][0]["items"] == ["aaaaaaaaaaa"]
    assert pulled["cursor"] == max(pulled["tracks"][0]["rev"], pulled["playlists"][0]["rev"])


def test_pull_since_cursor_returns_only_newer_changes_and_tombstones(web, device, s3) -> None:
    a = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    add_ready_track(web, device, s3, "bbbbbbbbbbb")
    cursor = web.get("/api/sync?since=0", headers=device).json()["cursor"]
    web.delete("/api/tracks/aaaaaaaaaaa", headers={"If-Match": str(a["rev"])})
    pulled = web.get(f"/api/sync?since={cursor}", headers=device).json()
    assert [(t["video_id"], t["deleted_at"] is not None) for t in pulled["tracks"]] == [
        ("aaaaaaaaaaa", True)
    ]
    assert web.get(f"/api/sync?since={pulled['cursor']}").json()["tracks"] == []


def test_pull_hides_tracks_until_ready(client, device, s3) -> None:
    client.post("/api/tracks/aaaaaaaaaaa/upload", json=UPLOAD, headers=device)
    assert client.get("/api/sync?since=0", headers=device).json()["tracks"] == []
    cursor = client.get("/api/sync?since=0", headers=device).json()["cursor"]
    add_ready_track(client, device, s3, "aaaaaaaaaaa")
    pulled = client.get(f"/api/sync?since={cursor}", headers=device).json()
    assert [t["status"] for t in pulled["tracks"]] == ["ready"]


def test_push_applies_ops_in_order(client, device) -> None:
    response = push(client, device, upsert(), op("delete_playlist", {"id": PLAYLIST}))
    assert response.json() == {"acked": 2}
    playlist = client.get("/api/sync?since=0", headers=device).json()["playlists"][0]
    assert playlist["deleted_at"] is not None


def test_push_same_batch_twice_is_idempotent(client, device) -> None:
    batch = [
        upsert("A"),
        op("update_track", {"video_id": "aaaaaaaaaaa", "title": "t", "artist": "a"}),
    ]
    push(client, device, *batch)
    cursor = client.get("/api/sync?since=0", headers=device).json()["cursor"]
    assert push(client, device, *batch).json() == {"acked": 2}
    assert client.get("/api/sync?since=0", headers=device).json()["cursor"] == cursor


def test_push_batch_is_all_or_nothing(client, device) -> None:
    bad = op("update_track", {"video_id": "bad!video!!", "title": "t", "artist": "a"})
    assert push(client, device, upsert(), bad).status_code == 422
    assert client.get("/api/sync?since=0", headers=device).json()["playlists"] == []


def test_upsert_playlist_keeps_unknown_video_ids(client, device) -> None:
    push(client, device, upsert(items=["aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa"]))
    playlist = client.get("/api/sync?since=0", headers=device).json()["playlists"][0]
    assert playlist["items"] == ["aaaaaaaaaaa", "bbbbbbbbbbb", "aaaaaaaaaaa"]


def test_update_track_on_unknown_track_is_acked_noop(client, device, db) -> None:
    response = push(
        client, device, op("update_track", {"video_id": "aaaaaaaaaaa", "title": "t", "artist": "a"})
    )
    assert response.json() == {"acked": 1}
    assert db.execute("SELECT count(*) FROM tracks").fetchone()[0] == 0


def test_phone_wins_over_web_edit(web, device, s3) -> None:
    track = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    cursor = web.get("/api/sync?since=0", headers=device).json()["cursor"]
    web.patch(
        "/api/tracks/aaaaaaaaaaa",
        json={"title": "Web", "artist": "W"},
        headers={"If-Match": str(track["rev"])},
    )
    push(
        web,
        device,
        op("update_track", {"video_id": "aaaaaaaaaaa", "title": "Phone", "artist": "P"}),
        cursor=cursor,
    )
    assert web.get("/api/tracks").json()[0]["title"] == "Phone"


def test_conflict_logged_only_when_phone_had_not_seen_the_change(web, device, s3, caplog) -> None:
    track = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    cursor = web.get("/api/sync?since=0", headers=device).json()["cursor"]
    rename = {"video_id": "aaaaaaaaaaa", "title": "Phone", "artist": "P"}
    caplog.set_level(logging.WARNING, logger="freetube.library")

    push(web, device, op("update_track", rename), upsert(), upsert("B"), cursor=cursor)
    assert "sync conflict" not in caplog.text  # nothing changed on the server since cursor

    web.patch(
        "/api/tracks/aaaaaaaaaaa",
        json={"title": "Web", "artist": "W"},
        headers={"If-Match": str(track["rev"] + 1)},
    )
    push(web, device, op("update_track", rename), cursor=cursor + 3)
    assert "sync conflict" in caplog.text

    caplog.clear()
    push(web, device, op("update_track", {**rename, "title": "Again"}))  # no cursor
    assert "sync conflict" not in caplog.text


def test_update_track_resurrects_tombstone(web, device, s3) -> None:
    track = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    web.delete("/api/tracks/aaaaaaaaaaa", headers={"If-Match": str(track["rev"])})
    push(
        web, device, op("update_track", {"video_id": "aaaaaaaaaaa", "title": "Back", "artist": "A"})
    )
    assert [t["title"] for t in web.get("/api/tracks").json()] == ["Back"]


def test_upsert_playlist_resurrects_tombstone_and_replaces_items(web, device) -> None:
    push(web, device, upsert(items=["aaaaaaaaaaa"]), op("delete_playlist", {"id": PLAYLIST}))
    push(web, device, upsert(items=["bbbbbbbbbbb"]))
    playlists = web.get("/api/playlists").json()
    assert [p["items"] for p in playlists] == [["bbbbbbbbbbb"]]


def test_noop_op_does_not_bump_rev(client, device, s3) -> None:
    add_ready_track(client, device, s3, "aaaaaaaaaaa")
    cursor = client.get("/api/sync?since=0", headers=device).json()["cursor"]
    push(
        client,
        device,
        op("update_track", {"video_id": "aaaaaaaaaaa", "title": "Titre", "artist": "Artiste"}),
    )
    assert client.get(f"/api/sync?since={cursor}", headers=device).json() == {
        "tracks": [],
        "playlists": [],
        "cursor": cursor,
    }


def test_push_requires_device(web) -> None:
    assert web.post("/api/sync/ops", json={"ops": []}).status_code == 401
