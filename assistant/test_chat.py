import json
import os
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest.mock import patch

import httpx2 as httpx
from fastapi.testclient import TestClient
from openai import AsyncOpenAI

from app import address_layer, app, limits


object_filters = (
    "ch.so.agi.av.bodenbedeckung", address_layer, "ch.so.agi.av.grundstuecke.projektierte",
    "ch.so.agi.av.grundstuecke.rechtskraeftig", "ch.so.agi.av.nomenklatur.flurnamen",
    "ch.so.agi.av.nomenklatur.gelaendenamen", "ch.so.agi.gemeindegrenzen",
    "ch.so.agi.gebaeudeadressen.strassenachse_suche",
)


def object_hit(source, label, bbox=None):
    return {"feature": {"dataproduct_id": source, "display": label, "srid": "EPSG:2056",
                        "bbox": [2607600, 1228517, 2607700, 1228518] if bbox is None else bbox}}


def call(name="get_map_state", identifier="call_m", arguments=None, intent=1):
    args = {"intent": intent, **(arguments or {})} if isinstance(arguments, (dict, type(None))) else arguments
    return {"id": identifier, "type": "function", "function": {"name": name, "arguments": json.dumps(args)}}


def declare(*intents):
    return {"id": "declare", "type": "function", "function": {
        "name": "declare_intents", "arguments": json.dumps({
            "intents": [{"goal": goal, "target": target, **(options[0] if options else {})}
                        for goal, target, *options in intents]
        })
    }}


def step(*calls, **data):
    return {"calls": list(calls), "results": [
        {"tool_call_id": entry["id"], "content": json.dumps(
            {"ok": True, "data": data.get(entry["id"], {})}
        )} for entry in calls
    ]}


def completion(content=None, calls=None):
    message = {"role": "assistant", "content": content}
    if calls is not None:
        message["tool_calls"] = calls
    return {"id": "test", "model": "mock", "created": 1, "object": "chat.completion",
            "choices": [{"index": 0, "finish_reason": "tool_calls" if calls else "stop", "message": message}]}


class ChatTests(unittest.TestCase):
    def setUp(self):
        self.api = TestClient(app, base_url="http://127.0.0.1")
        self.addCleanup(self.api.close)
        self.requests = []

    def send(self, payload, *responses, headers=None):
        def respond(req):
            self.assertEqual(req.url.path, "/chat/completions")
            self.requests.append(json.loads(req.content))
            response = responses[len(self.requests) - 1]
            if isinstance(response, Exception):
                raise response
            return response

        def client():
            return AsyncOpenAI(api_key="fixture", base_url="http://mock.test", max_retries=0,
                               http_client=httpx.AsyncClient(transport=httpx.MockTransport(respond))), "mock"

        with patch("app.infomaniak_client", side_effect=client):
            return self.api.post("/api/v1/chat", json=payload, headers=headers)

    def test_object_search_uses_live_theme_scope_and_rejects_other_filters(self):
        async def geo(path, params, *errors):
            if path == "/map/themes.json":
                provider = {"provider": "fulltext", "params": {"default": ["foreground", *object_filters]}}
                return {"themes": {"groups": [{"items": [{"id": "test", "searchProviders": [provider]}]}]}}
            self.assertEqual(path, "/api/search/v2/")
            self.assertEqual(params, {"searchtext": "Hübeli", "filter": ",".join(object_filters), "limit": 25})
            return {"results": [object_hit(source, "Hübeli (Objekt)") for source in object_filters],
                    "result_counts": [{"dataproduct_id": source, "count": 1} for source in object_filters]}

        with patch("app.get_geo", side_effect=geo), patch("app.theme_search_config", (0, {})):
            args = {"name": "search_objects", "theme_id": "test", "args": {"query": "Hübeli"}}
            response = self.api.post("/api/v1/geo", json=args)
            self.assertEqual(response.status_code, 200)
            self.assertEqual([row["filter"] for row in response.json()["candidates"]], list(object_filters))
            self.assertIsNone(response.json()["zoom_ref"])
            for forbidden in ("foreground", "not.configured"):
                args["args"]["filters"] = [forbidden]
                self.assertEqual(self.api.post("/api/v1/geo", json=args).status_code, 400)

    def test_exact_parcel_navigation_distinguishes_substrings_ambiguity_and_bad_bounds(self):
        parcel = object_filters[3]
        hits = [object_hit(parcel, "GB-Nr: 1123 - Messen (Liegenschaft)"),
                object_hit(parcel, "GB-Nr: 123 - Messen (Liegenschaft)")]
        body = {"results": hits, "result_counts": [{"dataproduct_id": parcel, "count": 2}]}
        args = {"name": "search_objects", "theme_id": "test", "args": {"query": "123 Messen", "filters": [parcel]}}
        with patch("app.object_filters", return_value=[parcel]), patch("app.get_geo", return_value=body):
            result = self.api.post("/api/v1/geo", json=args).json()
            self.assertEqual(len(result["candidates"]), 1)
            self.assertEqual(result["zoom_ref"], result["candidates"][0]["ref"])
            body["result_counts"][0]["count"] = -1
            self.assertIsNone(self.api.post("/api/v1/geo", json=args).json()["zoom_ref"])
            hits.append(object_hit(parcel, "GB-Nr: 123 - Brunnenthal [Messen] (Liegenschaft)"))
            body["result_counts"][0]["count"] = 3
            self.assertIsNone(self.api.post("/api/v1/geo", json=args).json()["zoom_ref"])
            args["args"]["query"] = "123 Brunnenthal Messen"
            result = self.api.post("/api/v1/geo", json=args).json()
            self.assertEqual(len(result["candidates"]), 1)
            self.assertIsNotNone(result["zoom_ref"])
            hits[-1]["feature"]["bbox"] = [2607700, 1228517, 2607600, 1228518]
            result = self.api.post("/api/v1/geo", json=args).json()
            self.assertIsNone(result["candidates"][0]["bbox"])
            self.assertIsNone(result["zoom_ref"])

    def test_object_names_follow_the_published_label_formats(self):
        cases = [
            (address_layer, "Klosterplatz 1 in Solothurn", "klosterplatz 1 solothurn",
             "Klosterplatz 1, 4500 Solothurn (Adresse)"),
            (object_filters[2], "Grundstück Nr. 379 in Kappel", "379 Kappel",
             "GB-Nr: 379 - Kappel [Kappel (SO)] (Proj. Liegenschaft)"),
            (object_filters[5], "Passwang", "Passwang", "Passwang, Mümliswil-Ramiswil (Geländename)"),
            (object_filters[0], "EGID: 333333", "333333", "EGID: 333333 (Gebäude)"),
            (object_filters[6], "Gemeinde Olten", "Gemeinde Olten", "Olten | 2581 (Gemeinde)"),
        ]
        for source, query, term, label in cases:
            with self.subTest(query=query):
                category = label.rsplit("(", 1)[-1].rstrip(")")
                body = {"results": [object_hit(source, label)],
                        "result_counts": [{"dataproduct_id": source, "count": 1, "filterword": category}]}
                args = {"name": "search_objects", "theme_id": "test", "args": {"query": query}}
                with patch("app.object_filters", return_value=object_filters), patch("app.get_geo", return_value=body) as geo:
                    result = self.api.post("/api/v1/geo", json=args).json()
                    self.assertEqual(geo.call_args.args[1]["searchtext"], term)
                    self.assertEqual(result["zoom_ref"], result["candidates"][0]["ref"])

    def test_chat_debug_keeps_browser_and_stdout_payload_permissions_separate(self):
        token = "test-infomaniak-secret"
        turn = "4edc7d73-8ced-4735-8053-9e183caf7d16"
        for debug, log_payload, browser_mode, browser_payload in [
            ("0", "1", "content", False), ("1", "0", "content", True), ("1", "1", "meta", False),
        ]:
            self.requests.clear()
            output = StringIO()
            with patch.dict(os.environ, {"ASSISTANT_DEBUG": debug, "ASSISTANT_DEBUG_PAYLOAD": log_payload,
                                        "INFOMANIAK_API_TOKEN": token}), redirect_stdout(output):
                response = self.send({"message": f"Private fixture {token}"},
                                     httpx.Response(200, json=completion(calls=[declare()])),
                                     headers={"X-AI-Debug": browser_mode, "X-AI-Turn-Id": turn, "X-AI-Round": "1"})
            self.assertEqual(response.status_code, 200)
            event = json.loads(output.getvalue())
            self.assertEqual((event["turn_id"], event["round"], event["status"]), (turn, 1, 200))
            self.assertEqual("provider" in event, debug == "1")
            self.assertEqual("Private fixture" in output.getvalue(), debug == log_payload == "1")
            self.assertEqual("debug" in response.json(), debug == "1")
            self.assertEqual("Private fixture" in response.text, browser_payload)
            self.assertNotIn(token, output.getvalue() + response.text)
            if browser_payload:
                preview = json.loads(response.json()["debug"]["payload"]["json"])
                self.assertEqual(preview["messages"][-1]["content"], "Private fixture [redacted]")
                self.assertEqual(preview["assistant"]["tool_calls"][0]["function"]["name"], "declare_intents")

    def test_failed_provider_call_keeps_diagnostics_and_stable_error(self):
        output = StringIO()
        with patch.dict(os.environ, {"ASSISTANT_DEBUG": "1", "ASSISTANT_DEBUG_PAYLOAD": "0"}), redirect_stdout(output):
            response = self.send({"message": "Hello."}, httpx.Response(429, json={"error": {"message": "raw upstream body"}}),
                                 headers={"X-AI-Debug": "meta", "X-AI-Turn-Id": "not-a-uuid"})
        event = json.loads(output.getvalue())
        self.assertEqual((response.status_code, response.json()["detail"]), (429, "providerRateLimit"))
        self.assertEqual(event["provider"]["http_status"], 429)
        self.assertEqual(event["error"], "providerRateLimit")
        self.assertNotIn("turn_id", event)
        self.assertNotIn("raw upstream body", output.getvalue() + response.text)

    def test_health_without_credentials_and_unconfigured_chat(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(self.api.get("/healthz").json(), {"status": "UP"})
            response = self.api.post("/api/v1/chat", json={"message": "Guten Tag"})
        self.assertEqual((response.status_code, response.json()), (503, {"detail": "modelNotConfigured"}))

    def test_normal_completion_uses_fixed_system_and_tools(self):
        payload = {"message": "Guten Tag"}
        first = self.send(payload, httpx.Response(200, json=completion(calls=[declare()])))
        self.assertEqual(first.status_code, 200)
        self.assertEqual(self.requests[0]["tool_choice"], "required")
        self.assertEqual(self.requests[0]["messages"][0]["role"], "system")
        self.assertEqual(self.requests[0]["messages"][1], {"role": "user", "content": "Guten Tag"})
        self.assertFalse(self.requests[0]["stream"])
        self.requests.clear()
        final = self.send({**payload, "rounds": [step(declare())]}, httpx.Response(200, json=completion("Hallo.")))
        self.assertEqual(final.json()["assistant"]["content"], "Hallo.")
        self.assertEqual(final.json()["pending"], [])
        self.assertNotIn("tools", self.requests[0])

    def test_one_or_multiple_calls_and_continuation_preserve_order_and_ids(self):
        for goals in [("get_map_state",), ("get_map_state", "get_visible_layers")]:
            calls = [declare(*[(goal, "Kartenstatus") for goal in goals])]
            calls += [call(goal, f"call_{i}", intent=i) for i, goal in enumerate(goals, 1)]
            self.requests.clear()
            first = self.send({"message": "Kartenstatus"}, httpx.Response(200, json=completion(calls=calls)))
            self.assertEqual(first.json()["assistant"]["tool_calls"], calls)
            self.requests.clear()
            final = self.send({"message": "Kartenstatus", "rounds": [step(*calls)]},
                              httpx.Response(200, json=completion("EPSG:2056")))
            self.assertEqual(final.status_code, 200)
            sent = self.requests[0]["messages"]
            self.assertEqual(sent[2]["tool_calls"], calls)
            self.assertEqual([message["tool_call_id"] for message in sent[3:]], [item["id"] for item in calls])

    def test_numbered_context_and_plain_history(self):
        payload = {
            "message": "Zeig Nummer 2",
            "history": [{"user": "Suche Wasser", "assistant": "Hier sind zwei Karten."}],
            "last_results": [{"id": "water.one", "title": "Fluss"}, {"id": "water.two", "title": "See"}],
        }
        intent = declare(("add_layer", "Nummer 2", {"layer_result_number": 2}))
        for target, status in [("water.one", 502), ("water.two", 200)]:
            self.requests.clear()
            response = self.send(payload, httpx.Response(200, json=completion(calls=[
                intent, call("add_layer", arguments={"id": target})
            ])))
            self.assertEqual(response.status_code, status)
            messages = self.requests[0]["messages"]
            self.assertEqual([message["role"] for message in messages], ["system", "user", "assistant", "user"])
            self.assertIn('"number": 2, "id": "water.two"', messages[-1]["content"])
        self.requests.clear()
        response = self.send({**payload, "message": "Entferne bitte den ersten Treffer wieder."},
                             httpx.Response(200, json=completion(calls=[
                                 declare(("remove_layer", "Fluss", {"layer_result_number": 1})),
                                 call("remove_layer", arguments={"id": "water.one"}),
                             ])))
        self.assertEqual(response.status_code, 200)

    def test_layer_and_object_intents_use_only_their_own_evidence(self):
        message = "Lade den Layer mit den Altlasten und gehe zu Grundstück 2084 in Solothurn."
        ref = "a" * 32
        intents = declare(("add_layer", "Altlasten"), ("zoom_to_result", "Grundstück 2084 in Solothurn"))
        layers = {"layers": [{"id": "ch.so.afu.altlasten"}]}
        geo = {"query": "2084 Solothurn", "zoom_ref": ref}
        search = call("search_layers", "layer", {"query": "Altlasten"})
        lookup = call("search_objects", "object", {"query": "2084 Solothurn"}, intent=2)
        first = step(intents, search, lookup, layer=layers, object=geo)
        payload = {"message": message, "rounds": [first]}
        add = call("add_layer", "add", {"id": "ch.so.afu.altlasten"})
        zoom = call("zoom_to_result", "zoom", {"result_ref": ref}, intent=2)
        for batch, status in [([add, zoom], 200), ([declare(), zoom], 502),
                              ([call("zoom_to_result", "zoom", {"result_ref": ref})], 502),
                              ([call("zoom_to_result", "zoom", {"result_ref": "b" * 32}, intent=2)], 502)]:
            self.requests.clear()
            response = self.send(payload, httpx.Response(200, json=completion(calls=batch)))
            self.assertEqual(response.status_code, status)
        first["results"][-1]["content"] = json.dumps({"ok": True, "data": {**geo, "zoom_ref": None}})
        self.requests.clear()
        final = self.send({**payload, "rounds": [first, step(add)]},
                          httpx.Response(200, json=completion("Welchen Grundbuchkreis meinst du?")))
        self.assertEqual(final.json()["pending"], [
            {"goal": "zoom_to_result", "target": "Grundstück 2084 in Solothurn", "source_intent": 2}
        ])
        pending = [{"goal": "zoom_to_result", "target": "Grundstück 2084 in Solothurn"}]
        followup = {"message": "Altstadt", "pending": pending}
        resumed = declare(("zoom_to_result", "Grundstück 2084 in Solothurn Altstadt", {"resume": 1}))
        self.requests.clear()
        response = self.send(followup, httpx.Response(200, json=completion(calls=[
            resumed, call("search_objects", "object", {"query": "2084 Solothurn"})
        ])))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json.loads(response.json()["assistant"]["tool_calls"][1]["function"]["arguments"])["query"],
                         "2084 Solothurn altstadt")
        lookup = call("search_objects", "object", {"query": "2084 Altstadt Solothurn"})
        followup["rounds"] = [step(resumed, lookup, object={"query": "2084 Altstadt Solothurn", "zoom_ref": ref})]
        self.requests.clear()
        response = self.send(followup, httpx.Response(200, json=completion(calls=[
            call("zoom_to_result", "zoom", {"result_ref": ref})
        ])))
        self.assertEqual(response.status_code, 200)
        self.requests.clear()
        response = self.send(followup, httpx.Response(200, json=completion(calls=[
            declare(("add_layer", "Altstadt", {"resume": 1}))
        ])))
        self.assertEqual(response.status_code, 502)

    def test_read_intent_cannot_add_a_map_action(self):
        search = step(declare(("search_objects", "Grundstück 2084")), call(
            "search_objects", arguments={"query": "2084"}
        ))
        phrase = "Suche Grundstück 2084, verschiebe die Karte aber nicht."
        response = self.send({"message": phrase, "rounds": [search]}, httpx.Response(200, json=completion(calls=[
            call("zoom_to_result", "zoom", {"result_ref": "a" * 32})
        ])))
        self.assertEqual(response.status_code, 502)
        self.assertEqual([t["function"]["name"] for t in self.requests[0]["tools"]], ["search_objects"])

    def test_pending_action_can_need_clarification_before_its_first_read(self):
        declaration = declare(("add_layer", "ch.so.kbs"), ("zoom_to_result", "meinem Grundstück"))
        add = call("add_layer", "add", {"id": "ch.so.kbs"})
        response = self.send({"message": "Lade ch.so.kbs und gehe zu meinem Grundstück.",
                              "rounds": [step(declaration, add)]},
                             httpx.Response(200, json=completion("Welche Grundstücksnummer und Gemeinde?")))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["pending"], [
            {"goal": "zoom_to_result", "target": "meinem Grundstück", "source_intent": 2}
        ])

    def test_address_and_dataset_evidence_is_scoped_to_the_feature_query(self):
        message = "Was gilt am Klosterplatz 1 in Solothurn?"
        layer = "ch.so.afu.gewaesserschutz.bereiche"
        reads = step(declare(("query_features", "Klosterplatz 1 in Solothurn")),
                     call("resolve_location", "location", {"address": "Klosterplatz 1 Solothurn"}),
                     call("search_layers", "layers", {"query": "Gewässerschutzbereiche"}),
                     layers={"layers": [{"id": layer}]},
                     location={"status": "resolved", "candidates": [{"easting": 2607600, "northing": 1228517}]})
        for easting, status in [(2607601, 502), (2607600, 200)]:
            self.requests.clear()
            response = self.send({"message": message, "rounds": [reads]}, httpx.Response(200, json=completion(calls=[
                call("query_features", "data", {"dataset_id": layer, "easting": easting, "northing": 1228517})
            ])))
            self.assertEqual(response.status_code, status)

    def test_map_reads_and_layer_search_need_fresh_evidence(self):
        for goal, terminal in [("get_visible_layers", "get_visible_layers"), ("present_layer_results", "search_layers")]:
            self.requests.clear()
            payload = {"message": "Kartenstatus", "rounds": [step(declare((goal, "Kartenstatus")))]}
            response = self.send(payload, httpx.Response(200, json=completion("Alte Daten.")))
            self.assertEqual(response.status_code, 502)
            self.requests.clear()
            args = {"query": "Wasser"} if terminal == "search_layers" else {}
            response = self.send(payload, httpx.Response(200, json=completion(calls=[call(terminal, arguments=args)])))
            self.assertEqual(response.status_code, 200)

    def test_geo_address_matches_the_exact_house_number(self):
        def hit(number):
            return {"feature": {
                "display": f"Klosterplatz {number}, 4500 Solothurn (Adresse)",
                "dataproduct_id": "ch.so.agi.av.gebaeudeadressen.gebaeudeeingaenge",
                "bbox": [2607600, 1228517, 2607600, 1228517], "srid": "EPSG:2056"
            }}

        with patch("app.get_geo", return_value={"results": [hit(11), hit(1)]}) as search:
            response = self.api.post("/api/v1/geo", json={"name": "resolve_location",
                                                         "args": {"address": "Klosterplatz 1 in Solothurn"}})
        self.assertEqual(search.call_args.args[1]["searchtext"], "klosterplatz 1 solothurn")
        self.assertEqual((response.status_code, response.json()), (200, {
            "status": "resolved", "candidates": [{"label": "Klosterplatz 1, 4500 Solothurn (Adresse)",
                                                   "easting": 2607600, "northing": 1228517}]
        }))

    def test_geo_endpoint_rejects_map_mutations_and_reports_dataset_failure(self):
        payload = {"name": "query_features", "args": {"dataset_id": "ch.so.test", "easting": 2607600,
                                                      "northing": 1228517}}
        for status, response_body in [(404, {}), (200, {"type": "FeatureCollection", "numberMatched": 0,
                                                        "features": []})]:
            upstream = httpx.AsyncClient(transport=httpx.MockTransport(
                lambda request: httpx.Response(status, json=response_body)))
            with patch("app.httpx.AsyncClient", return_value=upstream):
                response = self.api.post("/api/v1/geo", json=payload)
            self.assertEqual(response.status_code, status)
            if status == 404:
                self.assertEqual(response.json(), {"detail": "datasetUnavailable"})
            else:
                self.assertEqual(response.json()["features"], [])
                self.assertFalse(response.json()["partial"])
        response = self.api.post("/api/v1/geo", json={"name": "add_layer", "args": {"id": "ch.so.test"}})
        self.assertEqual((response.status_code, response.json()), (400, {"detail": "invalidTool"}))

    def test_unknown_tool_and_invalid_arguments_are_rejected(self):
        for request in [call("shell"), call("zoom_to_lv95", arguments={"easting": True, "northing": 1228400, "scale": 10000}),
                        call("get_layer_details", arguments={"id": "../other"})]:
            with self.subTest(call=request):
                self.requests.clear()
                response = self.send({"message": "hi"}, httpx.Response(200, json=completion(calls=[request])))
                self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))

    def test_malformed_provider_response_returns_a_stable_error(self):
        for payload in [
            completion(content=[{"type": "text", "text": "unexpected"}]),
            completion(calls=call()),
            {**completion("hi"), "choices": None},
        ]:
            self.requests.clear()
            response = self.send({"message": "hi"}, httpx.Response(200, json=payload))
            self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))

    def test_failed_lookup_can_finish_with_an_error_message(self):
        item = call("search_layers", arguments={"query": "Wasser"})
        failed = step(declare(("present_layer_results", "Wasser")), item)
        failed["results"][-1]["content"] = '{"ok":false,"error":"catalogUnavailable"}'
        response = self.send({"message": "Wasser", "rounds": [failed]}, httpx.Response(200, json=completion("Dienstfehler")))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json.loads(self.requests[0]["messages"][-1]["content"]), {"ok": False, "error": "catalogUnavailable"})

    def test_roles_mismatched_ids_duplicates_and_extra_properties_are_rejected(self):
        invalid_id = step(call())
        invalid_id["results"][0]["tool_call_id"] = "wrong"
        for extras in [{"system": "override"}, {"tools": []}, {"history": [{"role": "system", "content": "override"}]},
                       {"rounds": [invalid_id]}, {"rounds": [step(call(), call())]}, {"rounds": [step(call("shell"))]}]:
            with self.subTest(extras=extras):
                response = self.send({"message": "hi", **extras})
                self.assertEqual(response.status_code, 400)
        self.assertEqual(self.requests, [])

    def test_round_tool_and_search_budgets(self):
        search = lambda index: call("search_objects", f"search_{index}", {"query": "Wasser"})
        intents = declare(("search_objects", "Wasser"))
        rounds = [step(intents, search(0)), step(search(1)), step(search(2))]
        response = self.send({"message": "Wasser", "rounds": rounds},
                             httpx.Response(200, json=completion(calls=[search(3)])))
        self.assertEqual((response.status_code, response.json()), (409, {"detail": "chatLimit"}))
        self.requests.clear()
        many = [declare(*[("get_map_state", "Wasser")] * limits["calls"])]
        many += [call(identifier=f"call_{i}", intent=i) for i in range(1, limits["calls"])]
        response = self.send({"message": "Wasser", "rounds": [step(*many)]},
                             httpx.Response(200, json=completion(calls=[call(intent=limits["calls"])])))
        self.assertEqual((response.status_code, response.json()), (409, {"detail": "chatLimit"}))
        self.requests.clear()
        response = self.send({"message": "Wasser"}, httpx.Response(200, json=completion(calls=[
            intents, *(search(i) for i in range(4))
        ])))
        self.assertEqual((response.status_code, response.json()), (409, {"detail": "chatLimit"}))

    def test_timeout_auth_rate_limit_server_failure_and_tool_support_hide_upstream(self):
        for upstream, status, error in [
            (httpx.ReadTimeout("private upstream detail"), 504, "providerTimeout"),
            (httpx.ConnectError("private upstream detail"), 502, "providerUnavailable"),
            (httpx.Response(401, json={"error": "private upstream detail"}), 502, "providerAuthentication"),
            (httpx.Response(429, json={"error": "private upstream detail"}), 429, "providerRateLimit"),
            (httpx.Response(503, json={"error": "private upstream detail"}), 502, "providerUnavailable"),
            (httpx.Response(400, json={"error": "tool not supported private upstream detail"}), 502, "providerToolSupport"),
        ]:
            with self.subTest(error=error):
                self.requests.clear()
                response = self.send({"message": "hi"}, upstream)
                self.assertEqual((response.status_code, response.json()), (status, {"detail": error}))

    def test_json_and_payload_limit(self):
        for body, media, status in [("{}", "text/plain", 415),
                                    (" " * (limits["requestBytes"] + 1), "application/json", 413)]:
            response = self.api.post("/api/v1/chat", content=body, headers={"Content-Type": media})
            self.assertEqual(response.status_code, status)


if __name__ == "__main__":
    unittest.main()
