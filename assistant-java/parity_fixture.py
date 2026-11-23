"""Run the unchanged reference ASGI app against the Java test's HTTP fixture."""
import os
import sys
from pathlib import Path

import uvicorn
from openai import AsyncOpenAI

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "assistant"))
import app

port, fixture = sys.argv[1:]
app.geo_origin = fixture


def client():
    return AsyncOpenAI(
        api_key=os.environ["INFOMANIAK_API_TOKEN"],
        base_url=f"{fixture}/2/ai/42/openai/v1",
        timeout=25.0,
        max_retries=0,
    ), os.environ["INFOMANIAK_MODEL"]


app.infomaniak_client = client
uvicorn.run(app.app, host="127.0.0.1", port=int(port), log_level="error")
