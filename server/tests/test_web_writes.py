from conftest import add_ready_track

PLAYLIST = "6f1c2c0e-8a3f-4f7e-9a51-0d6f3c7b2a10"
BODY = {"id": PLAYLIST, "name": "Soir", "items": ["aaaaaaaaaaa"]}


def test_patch_track_with_current_rev_bumps_rev(web, device, s3) -> None:
    track = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    response = web.patch(
        "/api/tracks/aaaaaaaaaaa",
        json={"title": "T", "artist": "A"},
        headers={"If-Match": f'"{track["rev"]}"'},
    )
    assert response.status_code == 200
    assert response.json()["rev"] > track["rev"]


def test_patch_track_with_stale_rev_409(web, device, s3) -> None:
    track = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    web.patch(
        "/api/tracks/aaaaaaaaaaa",
        json={"title": "T", "artist": "A"},
        headers={"If-Match": str(track["rev"])},
    )
    response = web.patch(
        "/api/tracks/aaaaaaaaaaa",
        json={"title": "X", "artist": "A"},
        headers={"If-Match": str(track["rev"])},
    )
    assert response.status_code == 409
    assert response.json() == {"detail": "rev_conflict", "current_rev": track["rev"] + 1}
    assert web.get("/api/tracks").json()[0]["title"] == "T"


def test_write_without_if_match_428(web, device, s3) -> None:
    add_ready_track(web, device, s3, "aaaaaaaaaaa")
    response = web.patch("/api/tracks/aaaaaaaaaaa", json={"title": "T", "artist": "A"})
    assert response.status_code == 428


def test_web_cannot_resurrect_or_see_pending(web, device, s3) -> None:
    track = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    web.delete("/api/tracks/aaaaaaaaaaa", headers={"If-Match": str(track["rev"])})
    tombstone_rev = web.get("/api/sync?since=0").json()["tracks"][0]["rev"]
    response = web.patch(
        "/api/tracks/aaaaaaaaaaa",
        json={"title": "T", "artist": "A"},
        headers={"If-Match": str(tombstone_rev)},
    )
    assert response.status_code == 404


def test_phone_delete_reads_as_409_on_the_web(web, device, s3) -> None:
    track = add_ready_track(web, device, s3, "aaaaaaaaaaa")
    web.post(
        "/api/sync/ops",
        headers=device,
        json={
            "ops": [
                {
                    "op_id": "0b5d3c55-6a43-4f39-9f0e-5c0c4c1d8f11",
                    "type": "delete_track",
                    "payload": {"video_id": "aaaaaaaaaaa"},
                }
            ]
        },
    )
    response = web.patch(
        "/api/tracks/aaaaaaaaaaa",
        json={"title": "T", "artist": "A"},
        headers={"If-Match": str(track["rev"])},
    )
    assert response.status_code == 409  # "modifié ailleurs", the web reloads


def test_create_playlist_and_existing_id_409(web) -> None:
    created = web.post("/api/playlists", json=BODY)
    assert created.status_code == 201
    assert created.json()["items"] == ["aaaaaaaaaaa"]
    assert web.post("/api/playlists", json=BODY).status_code == 409


def test_put_playlist_replaces_items_in_order(web) -> None:
    rev = web.post("/api/playlists", json=BODY).json()["rev"]
    items = ["bbbbbbbbbbb", "aaaaaaaaaaa", "bbbbbbbbbbb"]
    response = web.put(
        f"/api/playlists/{PLAYLIST}",
        json={"name": "Soir", "items": items},
        headers={"If-Match": str(rev)},
    )
    assert response.json()["items"] == items
    assert web.get("/api/playlists").json()[0]["items"] == items


def test_delete_playlist_with_stale_rev_409_then_current_rev_204(web) -> None:
    rev = web.post("/api/playlists", json=BODY).json()["rev"]
    stale = web.delete(f"/api/playlists/{PLAYLIST}", headers={"If-Match": str(rev - 1)})
    assert stale.status_code == 409
    assert (
        web.delete(f"/api/playlists/{PLAYLIST}", headers={"If-Match": str(rev)}).status_code == 204
    )
    assert web.get("/api/playlists").json() == []
