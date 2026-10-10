import sqlite3
from collections.abc import Iterator

import boto3
import pytest
from argon2 import PasswordHasher
from fastapi.testclient import TestClient
from moto import mock_aws

from freetube.main import app

PASSWORD = "correct horse battery"
BUCKET = "freetube-test"
REGION = "eu-west-3"
# Cheap parameters: verify() reads them from the hash, so tests stay fast.
PASSWORD_HASH = PasswordHasher(time_cost=1, memory_cost=8, parallelism=1).hash(PASSWORD)


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv("FREETUBE_DB_PATH", str(tmp_path / "freetube.db"))
    monkeypatch.setenv("FREETUBE_S3_BUCKET", BUCKET)
    monkeypatch.setenv("AWS_REGION", REGION)
    monkeypatch.setenv("FREETUBE_PASSWORD_HASH", PASSWORD_HASH)
    # Fake credentials: boto3 must never reach the instance metadata service from a test.
    monkeypatch.setenv("AWS_ACCESS_KEY_ID", "testing")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "testing")
    return tmp_path


@pytest.fixture
def s3(env):
    with mock_aws():
        client = boto3.client("s3", region_name=REGION)
        client.create_bucket(
            Bucket=BUCKET, CreateBucketConfiguration={"LocationConstraint": REGION}
        )
        yield client


@pytest.fixture
def client(s3) -> Iterator[TestClient]:
    # https: httpx does not send a Secure cookie over http.
    with TestClient(app, base_url="https://testserver") as c:
        yield c


@pytest.fixture
def db(env, client) -> Iterator[sqlite3.Connection]:
    """Direct access to the app database (after the lifespan migrated it)."""
    conn = sqlite3.connect(env / "freetube.db", autocommit=True)
    conn.row_factory = sqlite3.Row
    yield conn
    conn.close()


@pytest.fixture
def web(client) -> TestClient:
    assert client.post("/api/auth/login", json={"password": PASSWORD}).status_code == 204
    return client


@pytest.fixture
def device(web) -> dict[str, str]:
    """Authorization header of a fresh device token (the cookie jar stays logged in)."""
    token = web.post("/api/devices", json={"name": "A06"}).json()["token"]
    return {"Authorization": f"Bearer {token}"}


UPLOAD = {
    "title": "Titre",
    "artist": "Artiste",
    "duration_s": 200,
    "audio_ext": "m4a",
    "audio_mime": "audio/mp4",
}


def add_ready_track(client: TestClient, device: dict, s3, video_id: str) -> dict:
    """Upload + objects in S3 + complete: the normal path of a phone upload."""
    assert client.post(f"/api/tracks/{video_id}/upload", json=UPLOAD, headers=device).is_success
    s3.put_object(Bucket=BUCKET, Key=f"audio/{video_id}.m4a", Body=b"audio")
    s3.put_object(Bucket=BUCKET, Key=f"thumbs/{video_id}.jpg", Body=b"jpg")
    response = client.post(f"/api/tracks/{video_id}/complete", headers=device)
    assert response.status_code == 200
    return response.json()
