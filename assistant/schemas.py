import json
import math
import re
from pathlib import Path


_limits = json.loads((Path(__file__).resolve().parents[1] / "shared" / "limits.json").read_text())
MESSAGE_LENGTH = _limits["messageLength"]
REPLY_LENGTH = _limits["replyLength"]
HISTORY_TURNS = _limits["historyTurns"]
REQUEST_BYTES = _limits["requestBytes"]

MAX_MODEL_CALLS = 4
MAX_INTENTS = 8
MAX_SEARCHES = 3
LAYERS_PER_SEARCH = 20
MAX_OBJECT_RESULTS = 25
MAX_LAYER_RESULTS = 10

SEARCH_TEXT = {"type": "string", "minLength": 1, "maxLength": 160, "pattern": r"\S"}
CATALOG_ID = {"type": "string", "minLength": 1, "maxLength": 200, "pattern": r"^[A-Za-z0-9_][A-Za-z0-9_.:-]*$"}
EASTING = {"type": "number", "minimum": 2480000, "maximum": 2840000}
NORTHING = {"type": "number", "minimum": 1070000, "maximum": 1300000}
SCALE = {"type": "number", "minimum": 1, "maximum": 10000000}
OBJECT_FILTERS = {"type": "array", "maxItems": 25, "items": CATALOG_ID}
LAYER_IDS = {"type": "array", "maxItems": MAX_LAYER_RESULTS, "items": CATALOG_ID}


def object_schema(properties, *, required):
    return {"type": "object", "required": required, "additionalProperties": False, "properties": properties}


GEO_SCHEMAS = {
    "search_objects": object_schema({"query": SEARCH_TEXT, "filters": OBJECT_FILTERS}, required=["query"]),
    "search_layers": object_schema({"query": SEARCH_TEXT}, required=["query"]),
    "get_layer_details": object_schema({"id": CATALOG_ID}, required=["id"]),
    "resolve_location": object_schema({"address": SEARCH_TEXT}, required=["address"]),
    "query_features": object_schema({"dataset_id": CATALOG_ID, "easting": EASTING, "northing": NORTHING},
                                    required=["dataset_id", "easting", "northing"]),
}


def valid(value, schema):
    kind = schema["type"]
    if kind == "object":
        return (isinstance(value, dict) and set(schema.get("required", ())) <= value.keys()
                and all(k in schema["properties"] and valid(v, schema["properties"][k]) for k, v in value.items()))
    if kind == "string":
        if "enum" in schema:
            return isinstance(value, str) and value in schema["enum"]
        if not isinstance(value, str) or not schema.get("minLength", 0) <= len(value) <= schema.get("maxLength", len(value)):
            return False
        pattern = schema.get("pattern")
        if pattern is None:
            return True
        return (re.fullmatch(pattern, value) if pattern.startswith("^") else re.search(pattern, value)) is not None
    if kind == "number":
        return (type(value) in (int, float) and schema["minimum"] <= value <= schema["maximum"]
                and math.isfinite(value))
    if kind == "integer":
        return type(value) is int and schema["minimum"] <= value <= schema["maximum"]
    if kind == "boolean":
        return isinstance(value, bool)
    if kind == "array":
        return isinstance(value, list) and len(value) <= schema["maxItems"] and all(valid(v, schema["items"]) for v in value)
    return False
