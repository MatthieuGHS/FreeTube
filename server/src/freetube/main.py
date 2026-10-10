from fastapi import FastAPI

app = FastAPI(title="FreeTube", docs_url=None, redoc_url=None)


@app.get("/api/health")
def health() -> dict[str, str]:
    return {"status": "ok"}
