from datetime import UTC, datetime, timedelta

from conftest import PASSWORD

from freetube.auth import COOKIE, sha256_hex


def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def test_login_wrong_password_401_without_cookie(client) -> None:
    response = client.post("/api/auth/login", json={"password": "wrong"})
    assert response.status_code == 401
    assert "set-cookie" not in response.headers


def test_login_sets_secure_httponly_strict_cookie(client) -> None:
    cookie = client.post("/api/auth/login", json={"password": PASSWORD}).headers["set-cookie"]
    for attribute in ("HttpOnly", "Secure", "SameSite=strict", "Max-Age=2592000", "Path=/"):
        assert attribute in cookie
    assert client.get("/api/auth/me").json() == {"kind": "session"}


def test_session_is_stored_hashed(web, db) -> None:
    raw = web.cookies[COOKIE]
    stored = [row["id_sha256"] for row in db.execute("SELECT id_sha256 FROM sessions")]
    assert stored == [sha256_hex(raw)]


def test_login_rate_limited_after_5_attempts_per_ip(client) -> None:
    for _ in range(5):
        client.post("/api/auth/login", json={"password": "wrong"})
    response = client.post("/api/auth/login", json={"password": PASSWORD})
    assert response.status_code == 429


def test_expired_session_401(web, db) -> None:
    db.execute("UPDATE sessions SET expires_at = ?", (_iso(datetime.now(UTC) - timedelta(1)),))
    assert web.get("/api/tracks").status_code == 401


def test_session_slides_only_after_a_day(web, db) -> None:
    first = db.execute("SELECT expires_at FROM sessions").fetchone()[0]
    response = web.get("/api/tracks")
    assert "set-cookie" not in response.headers  # same day: no write, no new cookie
    assert db.execute("SELECT expires_at FROM sessions").fetchone()[0] == first

    db.execute("UPDATE sessions SET expires_at = ?", (_iso(datetime.now(UTC) + timedelta(20)),))
    response = web.get("/api/tracks")
    assert COOKIE in response.headers["set-cookie"]
    assert db.execute("SELECT expires_at FROM sessions").fetchone()[0] > first[:10]


def test_logout_invalidates_session(web) -> None:
    raw = web.cookies[COOKIE]
    assert web.post("/api/auth/logout").status_code == 204
    web.cookies.set(COOKIE, raw)
    assert web.get("/api/tracks").status_code == 401


def test_device_token_shown_once_and_stored_hashed(web, db) -> None:
    created = web.post("/api/devices", json={"name": "A06"}).json()
    listed = web.get("/api/devices").json()
    assert "token" not in listed[0]
    stored = db.execute("SELECT token_sha256 FROM device_tokens").fetchone()[0]
    assert stored == sha256_hex(created["token"])
    assert created["token"] not in str(dict(db.execute("SELECT * FROM device_tokens").fetchone()))


def test_revoked_device_401_and_revoke_is_idempotent(web, device) -> None:
    assert web.get("/api/sync", headers=device).status_code == 200
    device_id = web.get("/api/devices").json()[0]["id"]
    assert web.delete(f"/api/devices/{device_id}").status_code == 204
    assert web.delete(f"/api/devices/{device_id}").status_code == 204
    assert web.get("/api/sync", headers=device).status_code == 401
    assert web.delete("/api/devices/999").status_code == 404


def test_device_routes_reject_session_and_session_routes_reject_device(client, web, device) -> None:
    upload = {
        "title": "t",
        "artist": "a",
        "duration_s": 1,
        "audio_ext": "m4a",
        "audio_mime": "audio/mp4",
    }
    assert web.post("/api/tracks/aaaaaaaaaaa/upload", json=upload).status_code == 401
    web.cookies.clear()
    assert client.get("/api/tracks", headers=device).status_code == 401
    assert client.get("/api/devices", headers=device).status_code == 401


def test_bad_bearer_does_not_fall_back_to_cookie(web) -> None:
    response = web.get("/api/sync", headers={"Authorization": "Bearer nope"})
    assert response.status_code == 401
