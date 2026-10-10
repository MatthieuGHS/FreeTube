"""`uv run python -m freetube.export_openapi`: writes server/openapi.json (committed).
The web generates its types from it; a test fails when it is stale."""

import json
from pathlib import Path

from freetube.main import app

OPENAPI_PATH = Path(__file__).resolve().parents[2] / "openapi.json"


def render() -> str:
    return json.dumps(app.openapi(), indent=2, sort_keys=True, ensure_ascii=False) + "\n"


if __name__ == "__main__":
    OPENAPI_PATH.write_text(render())
