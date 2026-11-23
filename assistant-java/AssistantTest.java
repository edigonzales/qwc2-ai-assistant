//JAVA 25
//DEPS io.avaje:avaje-jex:3.6
//DEPS io.avaje:avaje-json-core:3.16
//SOURCES Assistant.java
//FILES tools.json=../shared/tools.json
//FILES text-data.json

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.PrintStream;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;

public class AssistantTest {
    private static final String CHAT = "/api/v1/chat";
    private static final String GEO = "/api/v1/geo";
    private static final String PARCEL = "ch.so.agi.av.grundstuecke.rechtskraeftig";
    private static final String PROJECTED = "ch.so.agi.av.grundstuecke.projektierte";
    private static final String ADDRESS = Geo.ADDRESS_LAYER;
    private static final String BUILDING = "ch.so.agi.av.bodenbedeckung";
    private static final String TERRAIN = "ch.so.agi.av.nomenklatur.gelaendenamen";
    private static final String MUNICIPALITY = "ch.so.agi.gemeindegrenzen";
    private static final String DATASET = "ch.so.afu.gewaesserschutz.bereiche";
    private static final String LAYER = "ch.so.afu.altlasten.standorte";
    private static final String REF = "a".repeat(32);
    private static final String TOKEN = "controlled-fixture-token";
    private static final String TURN = "4edc7d73-8ced-4735-8053-9e183caf7d16";
    private static final List<Integer> BOX = List.of(2607721, 1228805, 2607775, 1228863);
    private static final List<Integer> POINT = List.of(2607600, 1228517, 2607600, 1228517);
    private static final Map<String, String> ENV = Map.of("INFOMANIAK_API_TOKEN", TOKEN, "INFOMANIAK_MODEL", "mock",
            "INFOMANIAK_PRODUCT_ID", "42", "ASSISTANT_DEBUG", "1");
    private static final Map<String, String> DEBUG = Map.of("X-AI-Debug", "content", "X-AI-Round", "1", "X-AI-Turn-Id", TURN);

    private final HttpClient http = Assistant.client(4);
    private URI fixtureOrigin;
    private URI javaServer;
    private URI pythonServer;
    private volatile Function<HttpExchange, Reply> provider = ignored -> new Reply(200, completion(null, declaration()));
    private volatile Function<HttpExchange, Reply> geo = ignored -> new Reply(404, Map.of());
    private volatile Map<String, Object> modelRequest;
    private int compared;
    private int checks;
    private int themeReads;

    record Reply(int status, Object body) {
        Map<String, Object> json() { return Assistant.object(body); }
    }

    @FunctionalInterface
    interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        new AssistantTest().run(args);
    }

    private void run(String[] args) throws Exception {
        var fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor(); http) {
            fixture.setExecutor(executor);
            fixture.createContext("/", this::fixture);
            fixture.start();
            fixtureOrigin = origin(fixture.getAddress().getPort());
            var app = new Assistant(ENV, fixtureOrigin, fixtureOrigin).start(0);
            javaServer = origin(app.port());
            Process python = null;
            try {
                if (args.length > 0) {
                    try (var port = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                        pythonServer = origin(port.getLocalPort());
                    }
                    var command = new ProcessBuilder(args[0], Path.of("assistant-java", "parity_fixture.py").toString(),
                            Integer.toString(pythonServer.getPort()), fixtureOrigin.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.INHERIT);
                    command.environment().putAll(ENV);
                    command.environment().put("ASSISTANT_DEBUG_PAYLOAD", "0");
                    python = command.start();
                    for (int tries = 0; ; tries++) {
                        try {
                            eq(request(pythonServer, "/healthz", null, null, Map.of()).status, 200);
                            break;
                        } catch (IOException e) {
                            if (tries >= 100 || !python.isAlive()) throw e;
                            Thread.sleep(30);
                        }
                    }
                }
                check("HTTP, ungültiges JSON und Limits", this::httpContract);
                check("Erste Modellrunde, dynamische Schemas und Verlauf", this::modelProtocol);
                check("Auftragsspezifische Layer- und Objekt-Nachweise", this::scopedEvidence);
                check("Negation, Hypothese und unerlaubte Kartenaktion", this::readOnly);
                check("Offener Auftrag, Fortsetzung und Fehler", this::pending);
                check("Nummerierte Layer und doppelte Aufrufe", this::numbered);
                check("Adress- und Datensatznachweise bei Punktabfragen", this::pointEvidence);
                check("Themenfilter und Cache", this::themeFilters);
                check("Exakte Objekte, Mehrdeutigkeit und gekürzte Suche", this::objects);
                check("Layergruppen, Layerdetails, Adressauflösung und Daten", this::geoReads);
                check("Providerfehler und Diagnostik", this::providerErrors);
                check("Geo-Antwortgrenze und Lesetimeout", this::upstreamLimits);
                check("Langsamer Request-Body", this::slowRequest);
                System.out.printf("%d groups passed; %d Python/Java HTTP comparisons%n", checks, compared);
            } finally {
                if (python != null) python.destroy();
                app.shutdown();
                fixture.stop(0);
            }
        }
    }

    private void check(String name, Check test) throws Exception {
        try {
            test.run();
        } catch (Exception | AssertionError e) {
            throw new AssertionError(name, e);
        }
        checks++;
        System.out.println("PASS " + name);
    }

    private void httpContract() throws Exception {
        eq(compare("/healthz", null, null).json(), obj("status", "UP"));
        for (URI server : pythonServer == null ? List.of(javaServer) : List.of(javaServer, pythonServer)) {
            eq(healthWithHost(server, "LOCALHOST:8085"), 200);
            eq(healthWithHost(server, "localhost:bad"), 400);
            eq(healthWithHost(server, "example.invalid"), 400);
        }
        for (String bad : List.of("{", "{} {}", "{\"message\":\"hi\",}", "{\"message\":\"hi\" \"pending\":[]}",
                "{\"message\":null}", "{\"unknown\":true}", "{\"history\":null,\"message\":\"hi\"}")) {
            error(compare(CHAT, bad, "application/json"), 400, "invalidConversation");
        }
        error(compare(CHAT, "{}", "text/plain"), 415, "jsonRequired");
        error(compare(GEO, " ".repeat(Assistant.limit("requestBytes") + 1), "application/json"), 413, "requestTooLarge");
        error(post(GEO, obj("name", "add_layer", "args", obj("id", LAYER))), 400, "invalidTool");
        error(post(GEO, obj("name", "query_features", "args",
                obj("dataset_id", "../bad", "easting", true, "northing", 1228517))), 400, "invalidTool");
        for (Object rounds : List.of(List.of(obj("calls", List.of(call("get_map_state", "state", 1)),
                "results", List.of(obj("tool_call_id", "wrong", "content", "{\"ok\":true,\"data\":{}}")))),
                List.of(step(declaration(), declaration())))) {
            error(post(CHAT, obj("message", "hi", "rounds", rounds)), 400, "invalidConversation");
        }
    }

    private void modelProtocol() throws Exception {
        var declare = declaration();
        model(completion(null, declare));
        eq(post(CHAT, obj("message", "Guten Tag")).status, 200);
        eq(modelRequest.get("tool_choice"), "required");
        eq(modelRequest.get("max_tokens"), 1600L);
        eq(modelRequest.get("temperature"), 0.2);
        eq(modelRequest.get("reasoning_effort"), "none");
        var tools = Assistant.list(modelRequest.get("tools"));
        eq(toolNames(), List.of("declare_intents", "search_objects", "zoom_to_result", "get_map_state", "get_visible_layers",
                "add_layer", "remove_layer", "zoom_to_lv95", "search_layers", "get_layer_details", "resolve_location",
                "query_features", "present_layer_results"));
        var declaration = Assistant.object(Assistant.object(Assistant.object(tools.getFirst()).get("function")).get("parameters"));
        if (Assistant.JSON.toJson(declaration).contains("\"resume\"")) throw new AssertionError(declaration);
        model(completion("Hallo."));
        var result = post(CHAT, obj("message", "Guten Tag", "history", List.of(obj("user", "Hallo", "assistant", "Grüezi")),
                "rounds", List.of(step(declare))));
        eq(result.status, 200);
        eq(result.json().get("pending"), List.of());
        eq(modelRequest.containsKey("tools"), false);
        eq(Assistant.list(modelRequest.get("messages")).size(), 6);
        error(post(CHAT, obj("message", "Guten Tag")), 502, "invalidModelResponse");
        model(completion(null, call("shell", "x", 1), declare));
        error(post(CHAT, obj("message", "Guten Tag")), 502, "invalidModelResponse");
    }

    private void scopedEvidence() throws Exception {
        var declare = declaration(intent("add_layer", "Altlasten"), intent("zoom_to_result", "Grundstück 2084 in Solothurn"));
        var layers = call("search_layers", "layers", 1, "query", "Altlasten");
        var objects = call("search_objects", "objects", 2, "query", "2084 Solothurn");
        var payload = obj("message", "Lade Altlasten und gehe zu Grundstück 2084 in Solothurn.");
        model(completion(null, declare, layers, objects));
        eq(post(CHAT, payload).status, 200);
        payload.put("rounds", List.of(step(declare, layers, objects, obj(
                "layers", ok(obj("layers", List.of(obj("id", LAYER)))),
                "objects", ok(obj("query", "2084 Solothurn", "zoom_ref", REF))))));
        var add = call("add_layer", "add", 1, "id", LAYER);
        var zoom = call("zoom_to_result", "zoom", 2, "result_ref", REF);
        model(completion(null, add, zoom));
        eq(post(CHAT, payload).status, 200);
        eq(Set.copyOf(toolNames()), Set.of("add_layer", "search_layers", "search_objects", "zoom_to_result"));
        for (var rejected : List.of(call("zoom_to_result", "zoom", 2, "result_ref", "b".repeat(32)),
                call("zoom_to_result", "zoom", 1, "result_ref", REF), call("add_layer", "add", 2, "id", LAYER),
                rawCall("add_layer", "add", "{\"intent\":1.0,\"id\":\"" + LAYER + "\"}"),
                rawCall("add_layer", "add", "{\"intent\":1e0,\"id\":\"" + LAYER + "\"}"))) {
            model(completion(null, rejected));
            error(post(CHAT, payload), 502, "invalidModelResponse");
        }
        payload.put("rounds", List.of(step(declare, layers, objects, obj(
                "layers", ok(obj("layers", List.of(obj("id", LAYER)))),
                "objects", ok(obj("query", "2084 Bern", "zoom_ref", REF))))));
        model(completion(null, zoom));
        error(post(CHAT, payload), 502, "invalidModelResponse");

        var lookup = call("search_objects", "lookup", 1, "query", "Wasser");
        var read = declaration(intent("search_objects", "Wasser"));
        var rounds = List.of(step(read, lookup), step(call("search_objects", "next", 1, "query", "Wasser")),
                step(call("search_objects", "last", 1, "query", "Wasser")));
        model(completion(null, call("search_objects", "fourth", 1, "query", "Wasser")));
        error(post(CHAT, obj("message", "Wasser", "rounds", rounds)), 409, "chatLimit");
    }

    private void readOnly() throws Exception {
        var first = step(declaration(intent("search_objects", "Grundstück 2084")),
                call("search_objects", "lookup", 1, "query", "2084"), obj("lookup", ok(obj("query", "2084", "zoom_ref", REF))));
        model(completion(null, call("zoom_to_result", "zoom", 1, "result_ref", REF)));
        error(post(CHAT, obj("message", "Suche Grundstück 2084, verschiebe die Karte aber nicht.", "rounds", List.of(first))),
                502, "invalidModelResponse");
        eq(toolNames(), List.of("search_objects"));
        model(completion(null, declaration()));
        eq(post(CHAT, obj("message", "Was würde passieren, wenn du auf Grundstück 2084 zoomst?")).status, 200);
        model(completion("Die Karte würde den Ort zeigen."));
        eq(post(CHAT, obj("message", "Was würde passieren?", "rounds", List.of(step(declaration())))).status, 200);
        model(completion(null, declaration(intent("add_layer", "erfundener Layer"))));
        error(post(CHAT, obj("message", "Lade Altlasten.")), 502, "invalidModelResponse");
    }

    private void pending() throws Exception {
        String target = "Grundstück 2084 in Solothurn";
        var declaration = declaration(intent("add_layer", LAYER), intent("zoom_to_result", target));
        var add = call("add_layer", "add", 1, "id", LAYER);
        var first = step(declaration, add);
        var lookup = call("search_objects", "objects", 2, "query", "2084 Solothurn");
        var payload = obj("message", "Lade " + LAYER + " und gehe zu " + target,
                "rounds", List.of(first, step(lookup, obj("objects", ok(obj("query", "2084 Solothurn", "zoom_ref", null))))));
        model(completion("Welchen Grundbuchkreis meinst du?"));
        var expected = intent("zoom_to_result", target, "source_intent", 2L);
        eq(post(CHAT, payload).json().get("pending"), List.of(expected));
        payload.put("rounds", List.of(first));
        eq(post(CHAT, payload).json().get("pending"), List.of(expected));
        var prior = List.of(intent("zoom_to_result", target), intent("add_layer", "Ämter"));
        var followup = obj("message", "Altstadt", "pending", prior);
        var resume = declaration(intent("zoom_to_result", target, "resume", 1));
        model(completion(null, resume, call("search_objects", "lookup", 1, "query", "2084 Solothurn")));
        var calls = Assistant.list(Assistant.object(post(CHAT, followup).json().get("assistant")).get("tool_calls"));
        eq(arguments(calls.getLast()).get("query"), "2084 Solothurn altstadt");
        lookup = call("search_objects", "lookup", 1, "query", "2084 Altstadt Solothurn");
        var query = step(resume, lookup, obj("lookup", ok(obj("query", "2084 Altstadt Solothurn", "zoom_ref", REF))));
        followup.put("rounds", List.of(query));
        var zoom = call("zoom_to_result", "zoom", 1, "result_ref", REF);
        model(completion(null, zoom));
        eq(post(CHAT, followup).status, 200);
        model(completion("Das Werkzeug meldet einen Fehler."));
        followup.put("rounds", List.of(query, step(zoom, obj("zoom", obj("ok", false, "error", "mapActionFailed")))));
        eq(post(CHAT, followup).json().get("pending"), List.of(intent("add_layer", "Ämter")));
        model(completion(null, zoom));
        error(post(CHAT, followup), 502, "invalidModelResponse");
    }

    private void numbered() throws Exception {
        var selection = List.of(obj("id", "water.one", "title", "Fluss"), obj("id", "water.two", "title", "See"));
        var payload = obj("message", "Zeige Nummer 2.", "last_results", selection);
        var declare = declaration(intent("add_layer", "See", "layer_result_number", 2));
        for (String id : List.of("water.one", "water.two")) {
            model(completion(null, declare, call("add_layer", "add", 1, "id", id)));
            eq(post(CHAT, payload).status, id.equals("water.two") ? 200 : 502);
        }
        model(completion(null, declare, call("search_layers", "search", 1, "query", "See")));
        error(post(CHAT, payload), 502, "invalidModelResponse");
        payload.put("rounds", List.of(step(declare)));
        model(completion("Kannst du das bestätigen?"));
        error(post(CHAT, payload), 502, "invalidModelResponse");
    }

    private void pointEvidence() throws Exception {
        var declare = declaration(intent("query_features", "Klosterplatz 1 in Solothurn"));
        var location = call("resolve_location", "loc", 1, "address", "Klosterplatz 1 Solothurn");
        var search = call("search_layers", "search", 1, "query", "Gewässerschutz");
        var payload = obj("message", "Was gilt am Klosterplatz 1 in Solothurn?", "rounds", List.of(step(declare, location, search,
                obj("loc", ok(obj("status", "resolved", "candidates", List.of(obj("easting", 2607600, "northing", 1228517)))),
                        "search", ok(obj("layers", List.of(obj("id", DATASET))))))));
        for (Object east : List.of(2607601, 2607600, 2607600.0)) {
            model(completion(null, call("query_features", "data", 1, "dataset_id", DATASET, "easting", east, "northing", 1228517)));
            eq(post(CHAT, payload).status, east.equals(2607601) ? 502 : 200);
        }
        model(completion(null, call("query_features", "data", 1, "dataset_id", LAYER, "easting", 2607600, "northing", 1228517)));
        error(post(CHAT, payload), 502, "invalidModelResponse");
    }

    private void themeFilters() throws Exception {
        int count = themeReads;
        geo = request -> {
            if (request.getRequestURI().getPath().equals("/map/themes.json")) {
                themeReads++;
                return new Reply(200, themes());
            }
            var query = query(request);
            eq(query.get("filter"), String.join(",", PARCEL, ADDRESS, BUILDING, PROJECTED, TERRAIN, MUNICIPALITY));
            eq(query.get("limit"), "25");
            eq(query.get("searchtext"), "Hübeli");
            return new Reply(200, obj("results", List.of(), "result_counts", List.of()));
        };
        eq(objects("Hübeli").status, 200);
        eq(objects("Hübeli").status, 200);
        eq(themeReads - count, pythonServer == null ? 1 : 2);
        for (String forbidden : List.of("foreground", "not.configured")) error(objects("2084", forbidden), 400, "invalidTool");
        error(post(GEO, obj("name", "search_objects", "theme_id", "bad", "args", obj("query", "2084"))), 503, "locationUnavailable");
        model(completion(null, declaration()));
        eq(post(CHAT, obj("message", "Guten Tag", "theme_id", "test")).status, 200);
        String prompt = Assistant.string(Assistant.object(Assistant.list(modelRequest.get("messages")).getFirst()), "content");
        eq(prompt.endsWith("\"" + MUNICIPALITY + "\"]\nIn this round declare_intents must be the first tool call."), true);
    }

    private void objects() throws Exception {
        objectFixture("123 Messen", PARCEL, 2, hit(PARCEL, "GB-Nr: 1123 - Messen (Liegenschaft)", BOX),
                hit(PARCEL, "GB-Nr: 123 - Messen (Liegenschaft)", BOX));
        var one = objects("123 Messen", PARCEL).json();
        eq(Assistant.list(one.get("candidates")).size(), 1);
        zoomable(one, true);
        objectFixture("123 Messen", PARCEL, -1, hit(PARCEL, "GB-Nr: 123 - Messen (Liegenschaft)", BOX));
        var partial = objects("123 Messen", PARCEL).json();
        eq(partial.get("partial"), true);
        zoomable(partial, false);
        objectFixture("123 Messen", PARCEL, 2, hit(PARCEL, "GB-Nr: 123 - Messen (Liegenschaft)", BOX),
                hit(PARCEL, "GB-Nr: 123 - Brunnenthal [Messen] (Liegenschaft)", BOX));
        zoomable(objects("123 Messen", PARCEL).json(), false);
        objectFixture("123 Brunnenthal Messen", PARCEL, 1, hit(PARCEL, "GB-Nr: 123 - Brunnenthal [Messen] (Liegenschaft)", BOX));
        zoomable(objects("Grundstück Nr. 123 im Grundbuchkreis Brunnenthal in der Gemeinde Messen", PARCEL).json(), true);
        objectFixture("123 Brunnenthal Messen", PARCEL, 1, hit(PARCEL,
                "GB-Nr: 123 - Brunnenthal [Messen] (Liegenschaft)", BOX.reversed()));
        zoomable(objects("123 Brunnenthal Messen", PARCEL).json(), false);
        objectFixture("klosterplatz 1 solothurn", ADDRESS, 1, hit(ADDRESS, "Klosterplatz 1, 4500 Solothurn (Adresse)", POINT));
        zoomable(objects("Klosterplatz 1 in Solothurn", ADDRESS).json(), true);
        objectFixture("333333", BUILDING, 1, hit(BUILDING, "EGID: 333333 (Gebäude)", BOX));
        zoomable(objects("EGID: 333333", BUILDING).json(), true);
        objectFixture("379 Kappel", PROJECTED, 1, "Proj. Liegenschaft",
                hit(PROJECTED, "GB-Nr: 379 - Kappel [Kappel (SO)] (Proj. Liegenschaft)", BOX));
        zoomable(objects("Grundstück Nr. 379 in Kappel", PROJECTED).json(), true);
        objectFixture("Passwang", TERRAIN, 1, "Geländename",
                hit(TERRAIN, "Passwang, Mümliswil-Ramiswil (Geländename)", BOX));
        zoomable(objects("Passwang", TERRAIN).json(), true);
        objectFixture("Gemeinde Olten", MUNICIPALITY, 1, "Gemeinde", hit(MUNICIPALITY, "Olten | 2581 (Gemeinde)", BOX));
        zoomable(objects("Gemeinde Olten", MUNICIPALITY).json(), true);
        Object[] many = new Object[26];
        Arrays.fill(many, hit(BUILDING, "EGID: 333333 (Gebäude)", BOX));
        objectFixture("333333", BUILDING, 26, many);
        var clipped = objects("333333", BUILDING).json();
        eq(clipped.get("partial"), true);
        zoomable(clipped, false);
    }

    private void geoReads() throws Exception {
        geo = request -> {
            String path = request.getRequestURI().getPath();
            var query = query(request);
            if (path.equals("/api/search/v2/fts/") && query.get("searchtext").startsWith("Karte: ")) {
                return new Reply(200, obj("results", List.of(obj("dataproduct", obj("dataproduct_id", "ch.so.gruppe",
                        "display", "Wasser &amp; Boden", "sublayers", List.of(obj("dataproduct_id", DATASET,
                                "display", "<b>Gewässerschutz</b> https://example.invalid/info")))))));
            }
            if (path.equals("/api/search/v2/fts/")) {
                eq(query.get("searchtext"), "klosterplatz 1 solothurn");
                return new Reply(200, obj("results", List.of(hit(ADDRESS, "Klosterplatz 11, 4500 Solothurn (Adresse)", POINT),
                        hit(ADDRESS, "Klosterplatz 1, 4500 Solothurn (Adresse)", POINT))));
            }
            if (path.equals("/api/dataproduct/v1/weblayers")) {
                return new Reply(200, obj("ch.so.gruppe", List.of(obj("name", "ch.so.gruppe", "title", "Wasser &amp; Boden",
                        "sublayers", List.of(obj("name", DATASET)), "abstract", "&NewLine;<p>Fachtext&nbsp;Au</p>"))));
            }
            if (path.equals("/api/data/v1/" + DATASET + "/")) {
                eq(Assistant.parse(query.get("filter_geom")), Assistant.parse("{\"type\":\"Point\",\"coordinates\":[2607600,1228517],"
                        + "\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:2056\"}}}"));
                return new Reply(200, obj("type", "FeatureCollection", "numberMatched", 1,
                        "features", List.of(obj("properties", obj("Bereich", "Au", "private", List.of("x"))))));
            }
            return new Reply(404, obj());
        };
        var layers = post(GEO, geo("search_layers", "query", "Gewässerschutz")).json();
        eq(Assistant.list(layers.get("layers")), List.of(obj("id", "ch.so.gruppe", "title", "Wasser & Boden"),
                obj("id", DATASET, "title", "Gewässerschutz", "themeTitle", "Wasser & Boden")));
        var details = post(GEO, geo("get_layer_details", "id", "ch.so.gruppe")).json();
        eq(details.get("type"), "layergroup");
        eq(details.get("description"), "Fachtext Au");
        error(post(GEO, geo("get_layer_details", "id", "ch.so.unknown")), 404, "layerUnknown");
        eq(post(GEO, geo("resolve_location", "address", "Klosterplatz 1 in Solothurn")).json().get("status"), "resolved");
        var data = post(GEO, geo("query_features", "dataset_id", DATASET, "easting", 2607600, "northing", 1228517)).json();
        eq(data.get("features"), List.of(obj("properties", obj("Bereich", "Au"))));
        eq(data.get("partial"), false);
        error(post(GEO, geo("query_features", "dataset_id", "ch.so.unknown", "easting", 2607600, "northing", 1228517)),
                404, "datasetUnavailable");
    }

    private void providerErrors() throws Exception {
        for (var expected : List.of(List.of(401, 502, "providerAuthentication"), List.of(403, 502, "providerAuthentication"),
                List.of(429, 429, "providerRateLimit"), List.of(400, 502, "providerToolSupport"),
                List.of(503, 502, "providerUnavailable"), List.of(404, 502, "providerRejected"))) {
            provider = ignored -> new Reply((int) expected.getFirst(), obj("error", obj("message", "private tool support details")));
            error(post(CHAT, obj("message", "Hallo")), (int) expected.get(1), (String) expected.get(2));
        }
        model(completion(List.of(obj("type", "text", "text", "invalid"))));
        error(post(CHAT, obj("message", "Hallo")), 502, "invalidModelResponse");
        provider = ignored -> new Reply(200, "{\"choices\":");
        String payload = "{\"message\":\"Hallo\"}";
        error(request(javaServer, CHAT, payload, "application/json", Map.of()), 502, "invalidModelResponse");
        if (pythonServer != null) {
            // The Python SDK raises JSONDecodeError before the reference validates the completion.
            var response = http.send(HttpRequest.newBuilder(pythonServer.resolve(CHAT))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString());
            eq(response.statusCode(), 500);
            eq(response.body(), "Internal Server Error");
        }

        model(completion(null, declaration()));
        var stdout = new java.io.ByteArrayOutputStream();
        PrintStream original = System.out;
        Reply debug;
        try (var captured = new PrintStream(stdout, true, UTF_8)) {
            System.setOut(captured);
            debug = request(javaServer, CHAT, Assistant.JSON.toJson(obj("message", "Private fixture " + TOKEN)),
                    "application/json", DEBUG);
            for (int i = 0; stdout.size() == 0 && i < 100; i++) Thread.sleep(5);
        } finally {
            System.setOut(original);
        }
        eq(debug.status, 200);
        var output = Assistant.object(debug.json().get("debug"));
        eq(output.get("turn_id"), TURN);
        eq(output.get("round"), 1L);
        String preview = Assistant.string(Assistant.object(output.get("payload")), "json");
        String timestamp = Assistant.string(Assistant.parse(stdout.toByteArray()), "timestamp");
        eq(timestamp.matches("[0-9-]+T[0-9:]+\\.[0-9]{3}\\+00:00"), true);
        if (!preview.contains("Private fixture [redacted]") || preview.contains(TOKEN)
                || stdout.toString(UTF_8).contains("Private fixture") || stdout.toString(UTF_8).contains(TOKEN)) {
            throw new AssertionError("Wrong diagnostic permissions or redaction");
        }
        var unconfigured = new Assistant(Map.of(), javaServer, javaServer).start(0);
        try {
            error(request(origin(unconfigured.port()), CHAT, "{\"message\":\"Hallo\"}", "application/json", Map.of()),
                    503, "modelNotConfigured");
        } finally {
            unconfigured.shutdown();
        }
    }

    private void upstreamLimits() throws Exception {
        geo = ignored -> new Reply(200, " ".repeat(4 * 1024 * 1024 + 1));
        error(post(GEO, geo("search_layers", "query", "Wasser")), 502, "invalidCatalog");
        geo = request -> {
            try {
                request.getResponseHeaders().set("Content-Type", "application/json");
                request.sendResponseHeaders(200, 0);
                request.getResponseBody().write('{');
                request.getResponseBody().flush();
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // The fixture's client closes the connection at its timeout.
            }
            return null;
        };
        try {
            Assistant.send(http, HttpRequest.newBuilder(javaServer.resolve("/healthz")).GET().build(), 1, 1, 1);
            throw new AssertionError("Expected bounded response");
        } catch (Assistant.ResponseTooLarge expected) {
            // Exercise the same transport limit with a tiny bound.
        }
        try (var fixtureClient = Assistant.client(1)) {
            try {
                Assistant.send(fixtureClient, HttpRequest.newBuilder(fixtureOrigin.resolve("/slow")).GET().build(), 1, 2, 100);
                throw new AssertionError("Expected timeout");
            } catch (HttpTimeoutException expected) {
                // Headers arrived promptly. The response body stalled.
            }
        }
    }

    private void slowRequest() throws Exception {
        try (var socket = new Socket("127.0.0.1", javaServer.getPort())) {
            socket.setSoTimeout(8000);
            socket.getOutputStream().write(("POST " + CHAT + " HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                    + "Content-Length: 100\r\n\r\n{").getBytes(UTF_8));
            var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), UTF_8));
            if (!input.readLine().contains(" 408 ")) throw new AssertionError("Expected HTTP 408");
        }
    }

    private static int healthWithHost(URI server, String host) throws IOException {
        try (var socket = new Socket("127.0.0.1", server.getPort())) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(("GET /healthz HTTP/1.1\r\nHost: " + host + "\r\n\r\n").getBytes(UTF_8));
            var input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), UTF_8));
            return Integer.parseInt(input.readLine().split(" ", 3)[1]);
        }
    }

    private void fixture(HttpExchange exchange) throws IOException {
        try (exchange) {
            Reply response;
            if (exchange.getRequestURI().getPath().equals("/2/ai/42/openai/v1/chat/completions")) {
                eq(exchange.getRequestHeaders().getFirst("Authorization"), "Bearer " + TOKEN);
                modelRequest = Assistant.parse(exchange.getRequestBody().readAllBytes());
                response = provider.apply(exchange);
            } else {
                eq(exchange.getRequestHeaders().getFirst("Authorization"), null);
                response = geo.apply(exchange);
            }
            if (response == null) return;
            byte[] body = (response.body instanceof String text ? text : Assistant.JSON.toJson(response.body)).getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(response.status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private void model(Object body) { provider = ignored -> new Reply(200, body); }

    private List<String> toolNames() {
        return Assistant.list(modelRequest.get("tools")).stream()
                .map(tool -> Assistant.string(Assistant.object(Assistant.object(tool).get("function")), "name")).toList();
    }

    private void objectFixture(String term, String source, int count, Object... hits) {
        objectFixture(term, source, count, "Objekt", hits);
    }

    private void objectFixture(String term, String source, int count, String category, Object... hits) {
        geo = request -> {
            if (request.getRequestURI().getPath().equals("/map/themes.json")) return new Reply(200, themes());
            eq(request.getRequestURI().getPath(), "/api/search/v2/");
            eq(query(request).get("searchtext"), term);
            return new Reply(200, obj("results", List.of(hits),
                    "result_counts", List.of(obj("dataproduct_id", source, "count", count, "filterword", category))));
        };
    }

    private static Object themes() {
        var values = List.of("foreground", PARCEL, ADDRESS, BUILDING, PARCEL, PROJECTED, TERRAIN, MUNICIPALITY);
        var provider = obj("provider", "fulltext", "params", obj("default", values));
        return obj("themes", obj("groups", List.of(obj("subdirs", List.of(obj("items",
                List.of(obj("id", "test", "searchProviders", List.of(provider)))))))));
    }

    private Reply objects(String text, String... filters) throws Exception {
        var args = obj("query", text);
        if (filters.length > 0) args.put("filters", List.of(filters));
        return post(GEO, obj("name", "search_objects", "theme_id", "test", "args", args));
    }

    private static void zoomable(Map<String, Object> result, boolean expected) {
        Object reference = result.get("zoom_ref");
        eq(reference != null, expected);
        if (expected) {
            var candidates = Assistant.list(result.get("candidates"));
            eq(reference, Assistant.object(candidates.getFirst()).get("ref"));
        }
    }

    private Reply post(String path, Object body) throws Exception {
        return compare(path, Assistant.JSON.toJson(body), "application/json");
    }

    private Reply compare(String path, String body, String media) throws Exception {
        var first = request(javaServer, path, body, media, Map.of());
        if (pythonServer != null) {
            var sent = modelRequest;
            var reference = request(pythonServer, path, body, media, Map.of());
            eq(first.status, reference.status);
            eq(canonical(first.body), canonical(reference.body));
            if (sent != modelRequest && sent != null) {
                eq(Assistant.list(sent.get("messages")).getFirst(), Assistant.list(modelRequest.get("messages")).getFirst());
                eq(sent.get("tools"), modelRequest.get("tools"));
                eq(sent.get("tool_choice"), modelRequest.get("tool_choice"));
            }
            compared++;
        }
        return first;
    }

    private Reply request(URI origin, String path, String body, String media, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(35));
        headers.forEach(builder::header);
        if (body != null) builder.header("Content-Type", media).POST(HttpRequest.BodyPublishers.ofString(body));
        var result = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(result.statusCode(), Assistant.parse(result.body()));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key.equals("arguments")) normalized.put(key, Assistant.parse((String) item));
                else if (key.equals("ref") || key.equals("zoom_ref")) normalized.put(key, item == null ? null : "<object-ref>");
                else normalized.put(key, canonical(item));
            });
            return normalized;
        }
        if (value instanceof List<?> list) return list.stream().map(AssistantTest::canonical).toList();
        return value;
    }

    private static URI origin(int port) { return URI.create("http://127.0.0.1:" + port); }

    private static Map<String, String> query(HttpExchange exchange) {
        var result = new HashMap<String, String>();
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            var pair = part.split("=", 2);
            result.put(pair[0], URLDecoder.decode(pair[1], UTF_8));
        }
        return result;
    }

    private static Map<String, Object> obj(Object... pairs) { return Assistant.obj(pairs); }

    private static Object geo(String name, Object... args) { return obj("name", name, "args", obj(args)); }

    private static Object hit(String source, String label, Object box) {
        return obj("feature", obj("dataproduct_id", source, "display", label, "srid", "EPSG:2056", "bbox", box));
    }

    private static Map<String, Object> intent(String goal, String target, Object... options) {
        var result = obj("goal", goal, "target", target);
        result.putAll(obj(options));
        return result;
    }

    private static Map<String, Object> declaration(Object... intents) {
        return rawCall("declare_intents", "declare", Assistant.JSON.toJson(obj("intents", List.of(intents))));
    }

    private static Map<String, Object> call(String name, String id, int index, Object... params) {
        var arguments = obj("intent", index);
        arguments.putAll(obj(params));
        return rawCall(name, id, Assistant.JSON.toJson(arguments));
    }

    private static Map<String, Object> rawCall(String name, String id, String arguments) {
        return obj("id", id, "type", "function", "function", obj("name", name, "arguments", arguments));
    }

    private static Map<String, Object> arguments(Object item) {
        return Assistant.parse(Assistant.string(Assistant.object(Assistant.object(item).get("function")), "arguments"));
    }

    private static Object step(Object... entries) {
        int size = entries.length;
        Map<String, Object> outcomes = Map.of();
        if (entries[size - 1] instanceof Map<?, ?> map && !map.containsKey("function")) {
            outcomes = Assistant.object(entries[--size]);
        }
        var calls = Arrays.asList(entries).subList(0, size);
        var results = new ArrayList<>();
        for (Object raw : calls) {
            String id = Assistant.string(Assistant.object(raw), "id");
            results.add(obj("tool_call_id", id, "content", Assistant.JSON.toJson(outcomes.getOrDefault(id, ok(obj())))));
        }
        return obj("calls", calls, "results", results);
    }

    private static Object ok(Object data) { return obj("ok", true, "data", data); }

    private static Object completion(Object content, Object... calls) {
        return obj("id", "test", "model", "mock", "created", 1, "object", "chat.completion",
                "choices", List.of(obj("index", 0, "finish_reason", calls.length == 0 ? "stop" : "tool_calls",
                        "message", obj("role", "assistant", "content", content, "tool_calls", List.of(calls)))));
    }

    private static void error(Reply reply, int status, String detail) {
        eq(reply.status, status);
        eq(reply.json().get("detail"), detail);
    }

    private static void eq(Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
}
