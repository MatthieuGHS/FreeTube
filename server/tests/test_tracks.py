from urllib.parse import urlparse

from conftest import BUCKET, UPLOAD, add_ready_track


def test_upload_creates_pending_and_returns_signed_put_urls(client, device) -> None:
    body = client.post("/api/tracks/dQw4w9WgXcQ/upload", json=UPLOAD, headers=device).json()
    assert body["status"] == "pending_upload"
    audio, thumb = urlparse(body["audio_put_url"]), urlparse(body["thumb_put_url"])
    assert audio.hostname == f"{BUCKET}.s3.eu-west-3.amazonaws.com"
    assert audio.path == "/audio/dQw4w9WgXcQ.m4a"
    assert thumb.path == "/thumbs/dQw4w9WgXcQ.jpg"
    assert "content-type" in audio.query.lower()  # Content-Type is signed into the URL


def test_upload_is_idempotent(client, device, db) -> None:
    client.post("/api/tracks/dQw4w9WgXcQ/upload", json=UPLOAD, headers=device)
    rev = db.execute("SELECT rev FROM tracks").fetchone()[0]
    again = client.post("/api/tracks/dQw4w9WgXcQ/upload", json=UPLOAD, headers=device)
    assert again.json()["status"] == "pending_upload"
    assert db.execute("SELECT rev FROM tracks").fetchone()[0] == rev


def test_upload_rejects_bad_input(client, device) -> None:
    for video_id, body in [
        ("short", UPLOAD),
        ("aaaaaaaaaa.", UPLOAD),
        ("dQw4w9WgXcQ", {**UPLOAD, "audio_ext": "m4a/../x"}),
        ("dQw4w9WgXcQ", {**UPLOAD, "audio_mime": "text/html"}),
        ("dQw4w9WgXcQ", {**UPLOAD, "extra": 1}),
    ]:
        response = client.post(f"/api/tracks/{video_id}/upload", json=body, headers=device)
        assert response.status_code == 422, (video_id, body)


def test_complete_409_when_objects_missing(client, device, s3, db) -> None:
    client.post("/api/tracks/dQw4w9WgXcQ/upload", json=UPLOAD, headers=device)
    s3.put_object(Bucket=BUCKET, Key="audio/dQw4w9WgXcQ.m4a", Body=b"audio")
    response = client.post("/api/tracks/dQw4w9WgXcQ/complete", headers=device)
    assert response.status_code == 409
    assert response.json() == {"detail": "upload_incomplete", "missing": ["thumb"]}
    assert db.execute("SELECT status FROM tracks").fetchone()[0] == "pending_upload"


def test_complete_marks_ready_once(client, device, s3) -> None:
    first = add_ready_track(client, device, s3, "dQw4w9WgXcQ")
    again = client.post("/api/tracks/dQw4w9WgXcQ/complete", headers=device).json()
    assert first["status"] == "ready"
    assert again["rev"] == first["rev"]


def test_upload_after_ready_returns_ready_without_urls(client, device, s3) -> None:
    add_ready_track(client, device, s3, "dQw4w9WgXcQ")
    response = client.post("/api/tracks/dQw4w9WgXcQ/upload", json=UPLOAD, headers=device)
    assert response.json() == {"status": "ready"}


def test_upload_resurrects_tombstone_as_pending(web, device, s3) -> None:
    track = add_ready_track(web, device, s3, "dQw4w9WgXcQ")
    web.delete("/api/tracks/dQw4w9WgXcQ", headers={"If-Match": str(track["rev"])})
    response = web.post("/api/tracks/dQw4w9WgXcQ/upload", json=UPLOAD, headers=device)
    assert response.json()["status"] == "pending_upload"  # files re-sent, complete re-checks
    done = web.post("/api/tracks/dQw4w9WgXcQ/complete", headers=device).json()
    assert done["status"] == "ready"
    assert done["deleted_at"] is None


def test_stream_redirects_with_cookie_and_with_bearer(client, web, device, s3) -> None:
    add_ready_track(web, device, s3, "dQw4w9WgXcQ")
    for headers in ({}, device):
        response = web.get(
            "/api/tracks/dQw4w9WgXcQ/stream", headers=headers, follow_redirects=False
        )
        assert response.status_code == 302
        location = urlparse(response.headers["location"])
        assert location.path == "/audio/dQw4w9WgXcQ.m4a"
        assert response.headers["cache-control"] == "no-store"


def test_stream_404_for_pending_deleted_or_unknown(web, device, s3) -> None:
    web.post("/api/tracks/aaaaaaaaaaa/upload", json=UPLOAD, headers=device)
    track = add_ready_track(web, device, s3, "bbbbbbbbbbb")
    web.delete("/api/tracks/bbbbbbbbbbb", headers={"If-Match": str(track["rev"])})
    for video_id in ("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"):
        response = web.get(f"/api/tracks/{video_id}/stream", follow_redirects=False)
        assert response.status_code == 404


def test_thumb_redirect_is_cached_less_than_url_lifetime(web, device, s3) -> None:
    add_ready_track(web, device, s3, "dQw4w9WgXcQ")
    response = web.get("/api/tracks/dQw4w9WgXcQ/thumb", follow_redirects=False)
    assert response.status_code == 302
    assert urlparse(response.headers["location"]).path == "/thumbs/dQw4w9WgXcQ.jpg"
    assert response.headers["cache-control"] == "private, max-age=3000"


def test_web_list_hides_pending_tracks(web, device, s3) -> None:
    web.post("/api/tracks/aaaaaaaaaaa/upload", json=UPLOAD, headers=device)
    add_ready_track(web, device, s3, "bbbbbbbbbbb")
    assert [t["video_id"] for t in web.get("/api/tracks").json()] == ["bbbbbbbbbbb"]
