import json
import os
import unittest
from unittest.mock import patch

import httpx2 as httpx
from fastapi.testclient import TestClient
from openai import AsyncOpenAI

from app import Chat, app, limits, tool_choice_for


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

    def send(self, payload, *responses):
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
            return self.api.post("/api/v1/chat", json=payload)

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
        self.assertEqual(len(self.requests[0]["tools"]), 8)

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
