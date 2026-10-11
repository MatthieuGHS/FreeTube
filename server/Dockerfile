# Build context: the repository root (see infra/docker-compose.yml).

# The web build is arch-independent JS: run it natively, not under arm64 emulation.
FROM --platform=$BUILDPLATFORM node:24-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web ./
# No gen:api here: src/api/schema.ts is committed.
RUN npm run build

FROM python:3.14-slim

COPY --from=ghcr.io/astral-sh/uv:0.12.19 /uv /bin/uv
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_NO_DEV=1 UV_PYTHON_DOWNLOADS=never

WORKDIR /app
# Dependencies first: this layer stays cached while only the source changes.
COPY server/pyproject.toml server/uv.lock ./
RUN uv sync --locked --no-install-project
COPY server/src ./src
RUN uv sync --locked --no-editable
# Served by main.py (FREETUBE_WEB_DIST defaults to this path).
COPY --from=web /web/dist ./web/dist

# Fixed uid so the host data directory can be chowned to match (see docs/deploy.md).
RUN useradd --system --uid 1000 freetube
USER freetube
ENV PATH=/app/.venv/bin:$PATH

EXPOSE 8000
# One worker on purpose: SQLite and the in-memory rate limit assume a single process (D4).
# Only Caddy can reach this port, so trusting its X-Forwarded-For is safe.
CMD ["uvicorn", "freetube.main:app", "--host", "0.0.0.0", "--port", "8000", "--proxy-headers", "--forwarded-allow-ips", "*"]
