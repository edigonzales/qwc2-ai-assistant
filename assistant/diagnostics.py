import json
import os
import re
import sys
from datetime import datetime, timezone
from time import perf_counter

from fastapi.exception_handlers import http_exception_handler
from fastapi.responses import JSONResponse

turn_id = re.compile(r"[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")
round_number = re.compile(r"[1-9][0-9]?")


def elapsed_ms(start):
    return round((perf_counter() - start) * 1000, 1)


def clean(value):
    token = os.environ.get("INFOMANIAK_API_TOKEN", "").strip()
    if not token:
        return value

    def redact(item):
        if isinstance(item, str):
            return item.replace(token, "[redacted]")
        if isinstance(item, dict):
            return {redact(key): redact(value) for key, value in item.items()}
        if isinstance(item, list):
            return [redact(value) for value in item]
        return item

    return redact(value)


def preview(value):
    data = json.dumps(clean(value), ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    limit = 16_384
    return {"json": data[:limit].decode("utf-8", errors="ignore"), "truncated": len(data) > limit}


async def observe_request(request, call_next):
    if request.method != "POST" or request.url.path not in {"/api/v1/chat", "/api/v1/geo"}:
        return await call_next(request)
    start = perf_counter()
    event = request.state.diagnostic = {"event": "assistant.request", "route": request.url.path}
    debug = request.state.debug = os.environ.get("ASSISTANT_DEBUG") == "1"
    mode = request.headers.get("x-ai-debug")
    request.state.browser_debug = mode if debug and mode in {"meta", "content"} else None
    request.state.log_payload = debug and os.environ.get("ASSISTANT_DEBUG_PAYLOAD") == "1"
    turn = request.headers.get("x-ai-turn-id", "")
    round_header = request.headers.get("x-ai-round", "")
    if turn_id.fullmatch(turn):
        event["turn_id"] = turn
    if round_number.fullmatch(round_header):
        event["round"] = int(round_header)
    status = 500
    try:
        response = await call_next(request)
        status = response.status_code
        return response
    finally:
        if status >= 500:
            event.setdefault("error", "internalError")
        event.update(timestamp=datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
                     status=status, duration_ms=elapsed_ms(start))
        if request.state.log_payload and hasattr(request.state, "payload_preview"):
            event["payload"] = request.state.payload_preview
        sys.stdout.write(json.dumps(clean(event), ensure_ascii=False) + "\n")
        sys.stdout.flush()


def browser_debug(request):
    mode = request.state.browser_debug
    if not mode:
        return {}
    detail = dict(request.state.diagnostic)
    if mode == "content" and hasattr(request.state, "payload_preview"):
        detail["payload"] = request.state.payload_preview
    return {"debug": clean(detail)}


async def report_http_error(request, exc):
    if hasattr(request.state, "diagnostic"):
        request.state.diagnostic["error"] = exc.detail if isinstance(exc.detail, str) else "requestFailed"
        if request.url.path == "/api/v1/chat" and request.state.browser_debug:
            return JSONResponse({"detail": exc.detail, **browser_debug(request)},
                                status_code=exc.status_code, headers=exc.headers)
    return await http_exception_handler(request, exc)
