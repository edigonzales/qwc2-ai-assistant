import json
import os
import unittest
from contextlib import redirect_stdout
from io import StringIO
from unittest.mock import patch

import httpx2 as httpx
from fastapi.testclient import TestClient
from openai import AsyncOpenAI

from app import Chat, address_layer, app, limits, map_command, tool_choice_for as choose_tool


object_filters = (
    "ch.so.agi.av.bodenbedeckung", address_layer, "ch.so.agi.av.grundstuecke.projektierte",
    "ch.so.agi.av.grundstuecke.rechtskraeftig", "ch.so.agi.av.nomenklatur.flurnamen",
    "ch.so.agi.av.nomenklatur.gelaendenamen", "ch.so.agi.gemeindegrenzen",
    "ch.so.agi.gebaeudeadressen.strassenachse_suche",
)


def object_hit(source, label, bbox=None):
    return {"feature": {"dataproduct_id": source, "display": label, "srid": "EPSG:2056",
                        "bbox": [2607600, 1228517, 2607700, 1228518] if bbox is None else bbox}}


def tool_choice_for(chat):
    message = chat.message.strip().lower()
    return choose_tool(chat, message, map_command(message))


def call(name="get_map_state", identifier="call_m", arguments=None):
    return {"id": identifier, "type": "function",
            "function": {"name": name, "arguments": json.dumps({} if arguments is None else arguments)}}


def step(*calls):
    return {"calls": list(calls), "results": [
        {"tool_call_id": entry["id"], "content": '{"ok":true,"data":{"crs":"EPSG:2056"}}'} for entry in calls
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

    def test_zoom_needs_explicit_navigation_and_latest_matching_search(self):
        query = "Klosterplatz 1 Solothurn"
        ref = "a" * 32
        reading = call("search_objects", "search", {"query": query})
        matching = step(reading)
        matching["results"][0]["content"] = json.dumps({"ok": True, "data": {"query": query, "zoom_ref": ref}})
        denied = step(call("search_objects", "newer", {"query": query}))
        message = "Gehe zu Klosterplatz 1 in Solothurn"
        zoom = call("zoom_to_result", "zoom", {"result_ref": ref})
        with patch("app.object_filters", return_value=[address_layer]):
            for phrase, rounds, expected in [
                (message, [], 502), (message, [matching], 200), (message, [matching, denied], 502),
                ("Gehe zu Klosterplatz 1 Solothurn", [matching], 200),
                ("Suche Klosterplatz 1 in Solothurn", [matching], 502),
                ("Gehe nicht zu Klosterplatz 1 in Solothurn", [matching], 502),
                ("Gehe zu Bahnhofstrasse 3 in Solothurn", [matching], 502),
            ]:
                self.requests.clear()
                response = self.send({"message": phrase, "theme_id": "default", "rounds": rounds},
                                     httpx.Response(200, json=completion(calls=[zoom])))
                self.assertEqual(response.status_code, expected, phrase)
            self.requests.clear()
            response = self.send({"message": "Suche Hübeli", "theme_id": "default"},
                                 httpx.Response(200, json=completion(calls=[
                                     call("search_objects", "search", {"query": "Hübeli"})])))
            self.assertEqual(response.status_code, 200)

    def test_object_and_layer_tool_choice_follow_the_requested_operation(self):
        lookup = call("search_objects", "search", {"query": "Wasser"})
        response = httpx.Response(200, json=completion(calls=[lookup]))
        with patch("app.object_filters", return_value=[address_layer]):
            for phrase, status in [
                ("Suche nach dem Layer Wasser", 502),
                ("Suche im Layer Wasser", 502),
                ("Gehe zu Wasser, wo es kartiert ist", 200),
            ]:
                self.requests.clear()
                self.assertEqual(self.send({"message": phrase, "theme_id": "default"}, response).status_code, status)
            self.requests.clear()
            phrase = "Gehe zu Solothurn und prüfe den Gewässerschutz am Klosterplatz 1"
            point = call("query_features", "point", {"dataset_id": "water", "easting": 2607000, "northing": 1228500})
            response = self.send({"message": phrase, "theme_id": "default", "rounds": [step(lookup)]},
                                 httpx.Response(200, json=completion(calls=[point])))
            self.assertEqual(response.status_code, 502)

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
                                     httpx.Response(200, json=completion("Hello.")),
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
                self.assertEqual(preview["assistant"]["content"], "Hello.")

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
        response = self.send({"message": "Guten Tag"}, httpx.Response(200, json=completion("Hallo.")))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json()["assistant"]["content"], "Hallo.")
        self.assertEqual(self.requests[0]["messages"][0]["role"], "system")
        self.assertEqual(self.requests[0]["messages"][1], {"role": "user", "content": "Guten Tag"})
        self.assertFalse(self.requests[0]["stream"])
        self.assertEqual({entry["function"]["name"] for entry in self.requests[0]["tools"]}, {
            "get_map_state", "get_visible_layers", "search_layers", "get_layer_details",
            "resolve_location", "query_features", "present_layer_results"
        })

    def test_one_or_multiple_calls_and_continuation_preserve_order_and_ids(self):
        calls = [call(), call("get_visible_layers", "call_l")]
        for batch in [calls[:1], calls]:
            with self.subTest(size=len(batch)):
                self.requests.clear()
                first = self.send({"message": "Kartenstatus"}, httpx.Response(200, json=completion(calls=batch)))
                self.assertEqual(first.json()["assistant"]["tool_calls"], batch)
                self.requests.clear()
                follow_up = self.send({"message": "Kartenstatus", "rounds": [step(*batch)]},
                                      httpx.Response(200, json=completion("EPSG:2056")))
                self.assertEqual(follow_up.status_code, 200)
                sent = self.requests[0]["messages"]
                self.assertEqual(sent[2]["tool_calls"], batch)
                self.assertEqual([message["tool_call_id"] for message in sent[3:]], [entry["id"] for entry in batch])

    def test_numbered_context_and_plain_history(self):
        payload = {
            "message": "Zeig Nummer 2",
            "history": [{"user": "Suche Wasser", "assistant": "Hier sind zwei Karten."}],
            "last_results": [{"id": "water.one", "title": "Fluss"}, {"id": "water.two", "title": "See"}],
        }
        self.send(payload, httpx.Response(200, json=completion(calls=[call("add_layer", arguments={"id": "water.two"})])))
        messages = self.requests[0]["messages"]
        self.assertEqual([message["role"] for message in messages], ["system", "user", "assistant", "user"])
        self.assertIn('"number": 2, "id": "water.two"', messages[-1]["content"])
        self.assertEqual(self.requests[0]["tool_choice"], {"type": "function", "function": {"name": "add_layer"}})

    def test_explicit_map_action_requires_the_bound_tool(self):
        for model_reply in [completion("Angezeigt."), completion(calls=[call()])]:
            self.requests.clear()
            response = self.send({"message": "Zeige den Layer mit ID ch.so.agi.gemeindegrenzen auf der Karte."},
                                 httpx.Response(200, json=model_reply))
            self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))

    def test_numbered_details_and_negated_or_list_requests_do_not_force_mutations(self):
        references = [{"id": "water.one", "title": "Fluss"}]
        details = Chat(message="Zeig mir Details zu Nummer 1", last_results=references)
        self.assertEqual(tool_choice_for(details), {"type": "function", "function": {"name": "get_layer_details"}})
        for message in ["Zeige Nummer 1 nicht auf der Karte", "Entferne Nummer 1 aus der Trefferliste"]:
            self.assertEqual(tool_choice_for(Chat(message=message, last_results=references)), "auto")

    def test_address_questions_require_resolving_and_querying_the_point(self):
        references = [{"id": "water.one", "title": "Fluss"}]
        parcel = Chat(message="Gib mir Informationen zum Grundstück Nr. 101 in Solothurn.", last_results=references)
        self.assertEqual(tool_choice_for(parcel), "auto")
        message = "In welchem Gewässerschutzbereich liegt Klosterplatz 1 in Solothurn?"
        plain_reply = httpx.Response(200, json=completion("Au"))
        response = self.send({"message": message}, plain_reply)
        self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))
        self.assertEqual(self.requests[0]["tool_choice"], "required")

        lookups = [call("resolve_location", "location", {"address": "Klosterplatz 1 in Solothurn"}),
                   call("search_layers", "layer", {"query": "Gewässerschutzbereiche"})]
        self.requests.clear()
        response = self.send({"message": "Suche den Gewässerschutzbereich am Klosterplatz 1 in Solothurn."},
                             httpx.Response(200, json=completion(calls=lookups)))
        self.assertEqual(response.json()["assistant"]["tool_calls"], lookups)

        lookup_round = step(*lookups)
        location = {"status": "resolved", "candidates": [{"easting": 2607600, "northing": 1228517}]}
        layers = {"layers": [{"id": "ch.so.afu.gewaesserschutz.bereiche"}]}
        for result, data in zip(lookup_round["results"], [location, layers]):
            result["content"] = json.dumps({"ok": True, "data": data})
        payload = {"message": message, "rounds": [lookup_round]}
        self.requests.clear()
        response = self.send(payload, plain_reply)
        self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))
        self.assertEqual(self.requests[0]["tool_choice"]["function"]["name"], "query_features")
        for easting, status in [(2607601, 502), (2607600, 200)]:
            self.requests.clear()
            query = call("query_features", "query", {"dataset_id": layers["layers"][0]["id"],
                                                      "easting": easting, "northing": 1228517})
            response = self.send(payload, httpx.Response(200, json=completion(calls=[query])))
            self.assertEqual(response.status_code, status)

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

    def test_named_layer_requires_search_then_corresponding_map_tool(self):
        message = "Entferne den Layer Gemeindegrenzen."
        query = call("search_layers", "search", {"query": "Gemeindegrenzen"})
        first = self.send({"message": message}, httpx.Response(200, json=completion(calls=[query])))
        self.assertEqual(first.status_code, 200)
        self.assertEqual(self.requests[0]["tool_choice"]["function"]["name"], "search_layers")
        actual = step(query)
        actual["results"][0]["content"] = '{"ok":true,"data":{"layers":[{"id":"ch.so.agi.gemeindegrenzen"}]}}'
        self.requests.clear()
        output = call("remove_layer", arguments={"id": "ch.so.agi.gemeindegrenzen"})
        second = self.send({"message": message, "rounds": [actual]}, httpx.Response(200, json=completion(calls=[output])))
        self.assertEqual(second.status_code, 200)
        self.assertEqual(self.requests[0]["tool_choice"]["function"]["name"], "remove_layer")

    def test_read_requests_never_force_map_mutation(self):
        message = "Zeige mir Details zu Layer Gemeindegrenzen."
        self.assertEqual(tool_choice_for(Chat(message=message))["function"]["name"], "search_layers")
        actual = step(call("search_layers", "search", {"query": "Gemeindegrenzen"}))
        actual["results"][0]["content"] = '{"ok":true,"data":{"layers":[{"id":"ch.so.agi.gemeindegrenzen"}]}}'
        self.assertEqual(tool_choice_for(Chat(message=message, rounds=[actual]))["function"]["name"], "get_layer_details")
        matches = [{"id": "visible.one", "title": "Eins"}, {"id": "visible.two", "title": "Zwei"}]
        for phrase in ["Zeige die Legende zu Nummer 2.", "Zeige die Beschreibung von Ebene 2."]:
            self.assertEqual(tool_choice_for(Chat(message=phrase, last_results=matches))["function"]["name"],
                             "get_layer_details")
        self.assertEqual(tool_choice_for(Chat(message="Zeige Layer 2 auf der Karte.", last_results=matches))["function"]["name"],
                         "add_layer")
        self.assertEqual(tool_choice_for(Chat(message="Nummer 2 anzeigen.", last_results=matches))["function"]["name"],
                         "add_layer")

    def test_read_turns_reject_model_proposed_mutations(self):
        for message in ["Zeige die Legende der Gemeindegrenzen.", "Was ist der Layer ch.so.agi.gemeindegrenzen?",
                        "Erkläre den Layer Gemeindegrenzen.", "Zeige mir, was der Layer Gemeindegrenzen enthält.",
                        "Zeige die Layer die sichtbar sind", "Zeige die Layer auf der Karte",
                        "Zeige mir ob Layer Gemeindegrenzen sichtbar ist",
                        "Show me whether the layer ch.so.agi.gemeindegrenzen is visible."]:
            self.requests.clear()
            response = self.send({"message": message}, httpx.Response(200, json=completion(calls=[call(
                "add_layer", arguments={"id": "ch.so.agi.gemeindegrenzen"}
            )])))
            self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))
            names = {tool["function"]["name"] for tool in self.requests[0]["tools"]}
            self.assertIn("get_layer_details", names)
            self.assertTrue(names.isdisjoint({"add_layer", "remove_layer", "zoom_to_lv95"}))

    def test_named_show_without_map_suffix_starts_with_catalog_search(self):
        search = step(call("search_layers", "search", {"query": "Gemeindegrenzen"}))
        search["results"][0]["content"] = '{"ok":true,"data":{"layers":[{"id":"ch.so.agi.gemeindegrenzen"}]}}'
        for phrase in ["Zeige den Layer Gemeindegrenzen.", "Zeige den Layer «Gemeindegrenzen».",
                       "Zeige den Layer „Gemeindegrenzen“.", "Zeige den Layer: Gemeindegrenzen."]:
            self.assertEqual(tool_choice_for(Chat(message=phrase))["function"]["name"], "search_layers")
            self.assertEqual(tool_choice_for(Chat(message=phrase, rounds=[search]))["function"]["name"], "add_layer")
        self.assertEqual(tool_choice_for(Chat(message="Zeige die sichtbaren Layer im Kartenausschnitt."))["function"]["name"],
                         "get_visible_layers")
        self.assertEqual(tool_choice_for(Chat(message="Zeige Layer zum Thema Wasser.")), "required")

    def test_visible_layer_question_requires_fresh_tool_data(self):
        history = [{"user": "Welche Layer sind sichtbar?", "assistant": "Gemeindegrenzen sind sichtbar."}]
        for message in ["Zeige die Layer die sichtbar sind", "Zeige die Layer auf der Karte"]:
            self.requests.clear()
            answer = self.send({"message": message, "history": history},
                               httpx.Response(200, json=completion("Gemeindegrenzen sind sichtbar.")))
            self.assertEqual((answer.status_code, answer.json()), (502, {"detail": "invalidModelResponse"}))
            self.assertEqual(self.requests[0]["tool_choice"]["function"]["name"], "get_visible_layers")

    def test_argument_free_read_discards_provider_fields(self):
        output = call("get_visible_layers", arguments={"theme": "default"})
        for message in ["Welche Layer sind sichtbar?", "Kartenstatus"]:
            self.requests.clear()
            response = self.send({"message": message}, httpx.Response(200, json=completion(calls=[output])))
            self.assertEqual(response.status_code, 200)
            self.assertEqual(response.json()["assistant"]["tool_calls"], [call("get_visible_layers")])
        self.requests.clear()
        response = self.send({"message": "Welche Layer sind sichtbar?"},
                             httpx.Response(200, json=completion(calls=[call("get_visible_layers", arguments=[])])))
        self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))

    def test_remove_layer_can_name_a_visible_layer_or_use_a_relative_clause(self):
        search = step(call("search_layers", "search", {"query": "Gemeindegrenzen"}))
        search["results"][0]["content"] = '{"ok":true,"data":{"layers":[{"id":"ch.so.agi.gemeindegrenzen"}]}}'
        for message in ["Entferne den sichtbaren Layer Gemeindegrenzen.", "Entferne den Layer, der Gemeindegrenzen heisst."]:
            self.assertEqual(tool_choice_for(Chat(message=message))["function"]["name"], "search_layers")
            self.assertEqual(tool_choice_for(Chat(message=message, rounds=[search]))["function"]["name"], "remove_layer")

    def test_explicit_add_turn_excludes_other_map_tools(self):
        response = self.send({"message": "Zeige Layer Gemeindegrenzen."}, httpx.Response(200, json=completion(calls=[call(
            "search_layers", arguments={"query": "Gemeindegrenzen"}
        )])))
        self.assertEqual(response.status_code, 200)
        names = {tool["function"]["name"] for tool in self.requests[0]["tools"]}
        self.assertIn("add_layer", names)
        self.assertTrue(names.isdisjoint({"remove_layer", "zoom_to_lv95"}))

    def test_explicit_search_rejects_an_unverified_text_list_and_requires_presentation(self):
        for message, query in [
            ("Suche Karten zu Wasser und Grundwasser.", "Wasser"),
            ("Suche digitale Terrainmodelle auf Basis von LiDAR-Aufnahmen.", "LiDAR"),
            ("Suche die landwirtschaftlichen Fruchtfolgeflächen.", "Fruchtfolgeflächen"),
        ]:
            self.requests.clear()
            response = self.send({"message": message},
                                 httpx.Response(200, json=completion("1. Erfunden (ch.so.fake)")))
            self.assertEqual((response.status_code, response.json()), (502, {"detail": "invalidModelResponse"}))
            self.assertEqual(self.requests[0]["tool_choice"], "required")
            actual = step(call("search_layers", "search", {"query": query}))
            self.assertEqual(tool_choice_for(Chat(message=message, rounds=[actual]))["function"]["name"],
                             "present_layer_results")

    def test_empty_thematic_search_can_retry_before_presentation(self):
        message = "Wo ist Strassenverkehrslärm kartiert?"
        first = step(call("search_layers", "search", {"query": "Strassenverkehrslärm"}))
        first["results"][0]["content"] = '{"ok":true,"data":{"layers":[]}}'
        retry = step(call("search_layers", "retry", {"query": "Strassenlärm"}))
        retry["results"][0]["content"] = first["results"][0]["content"]
        self.assertEqual(tool_choice_for(Chat(message=message, rounds=[first]))["function"]["name"], "search_layers")
        self.assertEqual(tool_choice_for(Chat(message=message, rounds=[first, retry]))["function"]["name"],
                         "present_layer_results")
        first["results"][0]["content"] = '{"ok":false,"error":"catalogUnavailable"}'
        self.assertEqual(tool_choice_for(Chat(message=message, rounds=[first]))["function"]["name"],
                         "present_layer_results")

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

    def test_hallucinated_id_error_survives_as_a_failed_browser_result(self):
        item = call("present_layer_results", arguments={"ids": ["invented"]})
        failed = step(item)
        failed["results"][0]["content"] = '{"ok":false,"error":"unknownCandidate"}'
        response = self.send({"message": "Wasser", "rounds": [failed]}, httpx.Response(200, json=completion("Kein Treffer")))
        self.assertEqual(response.status_code, 200)
        self.assertEqual(json.loads(self.requests[0]["messages"][-1]["content"]), {"ok": False, "error": "unknownCandidate"})

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
        rounds = [step(call(identifier=f"call_{index}")) for index in range(limits["rounds"] - 1)]
        response = self.send({"message": "hi", "rounds": rounds},
                             httpx.Response(200, json=completion(calls=[call()])))
        self.assertEqual((response.status_code, response.json()), (409, {"detail": "chatLimit"}))
        self.requests.clear()
        eight = [call(identifier=f"call_{index}") for index in range(limits["calls"])]
        response = self.send({"message": "hi", "rounds": [step(*eight)]},
                             httpx.Response(200, json=completion(calls=[call()])))
        self.assertEqual((response.status_code, response.json()), (409, {"detail": "chatLimit"}))
        self.requests.clear()
        calls = [call("search_layers", f"search_{i}", {"query": "Wasser"}) for i in range(4)]
        response = self.send({"message": "hi"}, httpx.Response(200, json=completion(calls=calls)))
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
