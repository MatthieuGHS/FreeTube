"""The only module that knows S3 keys and talks to S3.

Credentials come from the instance role (IMDSv2). Never log a returned URL: its query string
is a bearer credential for its whole lifetime.
"""

from pathlib import Path
from typing import Literal

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

PUT_TTL_S = 15 * 60  # brief §7
GET_TTL_S = 60 * 60  # brief §7
THUMB_MIME = "image/jpeg"

Kind = Literal["audio", "thumb"]


def object_key(kind: Kind, video_id: str, audio_ext: str) -> str:
    return f"audio/{video_id}.{audio_ext}" if kind == "audio" else f"thumbs/{video_id}.jpg"


class Storage:
    def __init__(self, bucket: str, region: str) -> None:
        self.bucket = bucket
        # SigV4 + regional endpoint: eu-west-3 rejects anything else in pre-signed URLs.
        self.client = boto3.client(
            "s3",
            region_name=region,
            endpoint_url=f"https://s3.{region}.amazonaws.com",
            config=Config(signature_version="s3v4", s3={"addressing_style": "virtual"}),
        )

    def presign_put(self, kind: Kind, video_id: str, audio_ext: str, audio_mime: str) -> str:
        """Content-Type is signed: the stored object gets the right type for <audio>/<img>,
        and the client must send exactly that header."""
        return self.client.generate_presigned_url(
            "put_object",
            Params={
                "Bucket": self.bucket,
                "Key": object_key(kind, video_id, audio_ext),
                "ContentType": audio_mime if kind == "audio" else THUMB_MIME,
            },
            ExpiresIn=PUT_TTL_S,
        )

    def presign_get(self, kind: Kind, video_id: str, audio_ext: str) -> str:
        return self.client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self.bucket, "Key": object_key(kind, video_id, audio_ext)},
            ExpiresIn=GET_TTL_S,
        )

    def missing_objects(self, video_id: str, audio_ext: str) -> list[Kind]:
        """HeadObject on both keys. Only a 404 counts as missing: any other error propagates,
        so an S3 outage never looks like an incomplete upload."""
        missing: list[Kind] = []
        for kind in ("audio", "thumb"):
            try:
                self.client.head_object(
                    Bucket=self.bucket, Key=object_key(kind, video_id, audio_ext)
                )
            except ClientError as error:
                if error.response["Error"]["Code"] not in ("404", "NoSuchKey"):
                    raise
                missing.append(kind)
        return missing

    def delete_track_objects(self, video_id: str, audio_ext: str) -> None:
        """Idempotent: S3 answers 204 for an absent key. Versioning keeps the noncurrent
        versions 30 more days (lifecycle rule)."""
        for kind in ("audio", "thumb"):
            self.client.delete_object(Bucket=self.bucket, Key=object_key(kind, video_id, audio_ext))

    def upload_file(self, path: Path, key: str) -> None:
        self.client.upload_file(str(path), self.bucket, key)

    def download_file(self, key: str, path: Path) -> None:
        self.client.download_file(self.bucket, key, str(path))
