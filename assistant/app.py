from fastapi import FastAPI

app = FastAPI(openapi_url=None)


@app.get("/healthz")
def health():
    return {"status": "UP"}
