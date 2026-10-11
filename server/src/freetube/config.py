import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    db_path: Path
    bucket: str
    region: str  # explicit: eu-west-3 is SigV4-only, pre-signed URLs need the regional endpoint
    password_hash: str  # argon2; single-quote it in infra/.env, compose interpolates `$`
    web_dist: Path  # the built web app; absent directory = API only (tests, dev)

    @classmethod
    def from_env(cls) -> Settings:
        missing = [
            name
            for name in ("FREETUBE_S3_BUCKET", "AWS_REGION", "FREETUBE_PASSWORD_HASH")
            if not os.environ.get(name)
        ]
        if missing:
            raise RuntimeError(f"missing environment variables: {', '.join(missing)}")
        return cls(
            db_path=Path(os.environ.get("FREETUBE_DB_PATH", "/data/freetube.db")),
            bucket=os.environ["FREETUBE_S3_BUCKET"],
            region=os.environ["AWS_REGION"],
            password_hash=os.environ["FREETUBE_PASSWORD_HASH"],
            web_dist=Path(os.environ.get("FREETUBE_WEB_DIST", "/app/web/dist")),
        )
