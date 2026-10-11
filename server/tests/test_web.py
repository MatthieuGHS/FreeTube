import pytest
from fastapi.testclient import TestClient

from freetube.main import app

INDEX = "<!doctype html><title>FreeTube</title>"


@pytest.fixture
def site(s3, env, monkeypatch):
    dist = env / "dist"
    (dist / "assets").mkdir(parents=True)
    (dist / "index.html").write_text(INDEX)
    (dist / "assets" / "app-abc123.js").write_text("console.log(1)")
    (env / "secret.txt").write_text("outside web_dist")
    monkeypatch.setenv("FREETUBE_WEB_DIST", str(dist))
    with TestClient(app, base_url="https://testserver") as c:
        yield c


def test_client_route_falls_back_to_index_uncached(site) -> None:
    response = site.get("/playlists/x")
    assert response.status_code == 200
    assert response.text == INDEX
    assert response.headers["cache-control"] == "no-cache"
    assert site.get("/").text == INDEX


def test_hashed_asset_is_served_and_cached_long(site) -> None:
    response = site.get("/assets/app-abc123.js")
    assert response.status_code == 200
    assert response.text == "console.log(1)"
    assert "immutable" in response.headers["cache-control"]


def test_unknown_api_path_stays_a_json_404(site) -> None:
    for path in ("/api/nope", "/api"):
        response = site.get(path)
        assert response.status_code == 404
        assert response.json() == {"detail": "Not Found"}


@pytest.mark.parametrize("path", ["/..%2fsecret.txt", "/assets/..%2f..%2fsecret.txt"])
def test_traversal_does_not_escape_web_dist(site, path) -> None:
    response = site.get(path)
    assert "outside web_dist" not in response.text
    assert response.text == INDEX


def test_without_web_build_everything_outside_the_api_is_404(client) -> None:
    assert client.get("/playlists/x").status_code == 404
    assert client.get("/api/health").status_code == 200
