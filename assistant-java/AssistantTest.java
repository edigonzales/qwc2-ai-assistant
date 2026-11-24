//JAVA 25
//DEPS io.avaje:avaje-jex:3.6
//DEPS io.avaje:avaje-json-core:3.16
//SOURCES Assistant.java
//FILES limits.json=../shared/limits.json
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
import java.util.concurrent.CopyOnWriteArrayList;
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
    private volatile Function<Map<String, Object>, Reply> provider = ignored -> new Reply(200, completion(null, declaration()));
    private volatile Function<HttpExchange, Reply> geo = ignored -> new Reply(404, Map.of());
    private final List<Map<String, Object>> modelRequests = new CopyOnWriteArrayList<>();
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
                check("Modellvertrag und lokale Kartenabfragen", this::modelProtocol);
                check("Themenfilter und Cache", this::themeFilters);
                check("Adresszoom und vollständige Suchnachweise", this::addressNavigation);
                check("Geordnete Layer- und Grundstücksaktionen", this::mixedIntents);
                check("Lesen ohne Kartenaktion und Bindung ans Ziel", this::readOnlyAndGrounding);
                check("Offener Auftrag, Fortsetzung und Fehler", this::pending);
                check("Nummerierte Layer und geprüfte Katalogeinträge", this::numbered);
                check("Adress- und Datensatznachweise bei Punktabfragen", this::pointEvidence);
                check("Layer-Ranking, Alternativen und Aufruflimits", this::layerRanking);
                check("Exakte Objekte, Mehrdeutigkeit und gekürzte Suche", this::objects);
                check("Layergruppen, Layerdetails, Adressauflösung und Daten", this::geoReads);
                check("Providerfehler und Diagnostik", this::providerErrors);
                check("Chat-Gesamtfrist und Teilergebnis", this::chatDeadline);
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
        for (var extra : List.of(obj("rounds", List.of()), obj("tools", List.of()), obj("system", "override"),
                obj("results", List.of(obj("intent", 1, "command", obj("type", "zoom_to_point")))),
                obj("history", List.of(obj("role", "system", "content", "override"))))) {
            var body = obj("message", "Hallo");
            body.putAll(extra);
            error(post(CHAT, body), 400, "invalidConversation");
            eq(modelRequests.size(), 0);
        }

        error(compare(CHAT, "{}", "text/plain"), 415, "jsonRequired");
        String padding = " ".repeat(Schemas.REQUEST_BYTES - 2);
        error(compare(GEO, "{}" + padding, "application/json"), 400, "invalidTool");
        error(compare(GEO, "{}" + padding + " ", "application/json"), 413, "requestTooLarge");
        error(post(GEO, obj("name", "add_layer", "args", obj("id", LAYER))), 400, "invalidTool");
        error(post(GEO, geo("query_features", "dataset_id", "../bad", "easting", true, "northing", 1228517)),
                400, "invalidTool");
    }

    private void modelProtocol() throws Exception {
        model(completion(null, toolCall("declare_intents", obj("intents", List.of(), "reply", "Hallo."))));
        eq(post(CHAT, obj("message", "Guten Tag")).json(), obj("results", List.of(), "reply", "Hallo."));
        eq(toolNames(), List.of("declare_intents"));
        var request = modelRequests.getFirst();
        eq(request.get("tool_choice"), "required");
        eq(request.get("max_tokens"), 1600L);
        eq(request.get("temperature"), 0.2);
        eq(request.get("reasoning_effort"), "none");
        eq(request.get("stream"), false);
        var messages = Assistant.list(request.get("messages"));
        eq(messages.size(), 2);
        eq(messages.getLast(), obj("role", "user", "content", "Guten Tag"));
        eq(Assistant.object(messages.getFirst()).get("role"), "system");

        model(completion(null, declaration(intent("get_map_state", null), intent("get_visible_layers", null))));
        var response = post(CHAT, obj("message", "Wo bin ich und welche Layer sind sichtbar?"));
        eq(results(response), List.of(
                obj("intent", 1L, "command", obj("type", "get_map_state")),
                obj("intent", 2L, "command", obj("type", "get_visible_layers"))));
        eq(toolNames(), List.of("declare_intents"));
    }

    private void addressNavigation() throws Exception {
        List<Integer> point = List.of(2605899, 1229278);
        String address = "Langendorfstrasse 19b Solothurn";
        String label = "Langendorfstrasse 19b, 4500 Solothurn (Adresse)";
        model(completion(null, declaration(intent("zoom_to_result", "Solothurn zur Langendorfstrasse 19b",
                "args", obj("query", address, "filters", List.of(ADDRESS))))));
        var payload = obj("message", "Gehe nach Solothurn zur Langendorfstrasse 19b", "theme_id", "test");

        objectFixture("langendorfstrasse 19b solothurn", ADDRESS, 1, "Adresse",
                hit(ADDRESS, label, List.of(2605899, 1229278, 2605899, 1229278)));
        var response = post(CHAT, payload, metaHeaders());
        eq(response.status, 200);
        var result = results(response).getFirst();
        var command = field(result, "command");
        eq(command.get("type"), "zoom_to_point");
        eq(command.get("coordinates"), point.stream().map(Integer::longValue).toList());
        eq(command.get("target_scale"), 1000L);
        eq(command.get("crs"), "EPSG:2056");
        eq(field(result, "data").containsKey("zoom_ref"), false);
        eq(toolNames(), List.of("declare_intents"));
        counts(response, 1, 1, 1);

        objectFixture("langendorfstrasse 19b solothurn", ADDRESS, -1, "Adresse",
                hit(ADDRESS, label, List.of(2605899, 1229278, 2605899, 1229278)));
        result = results(post(CHAT, payload)).getFirst();
        eq(result.containsKey("command"), false);
        eq(result.containsKey("clarification"), true);
        eq(field(result, "data").get("partial"), true);

        objectFixture("langendorfstrasse 19b solothurn", ADDRESS, 1, "Adresse",
                hit(ADDRESS, label, List.of(2605899, 1229278, 2605898, 1229277)));
        result = results(post(CHAT, payload)).getFirst();
        eq(result.containsKey("command"), false);
        eq(result.containsKey("clarification"), true);
    }

    private void mixedIntents() throws Exception {
        String target = "Grundstück 2084 in Solothurn";
        model(completion(null, declaration(intent("add_layer", "Altlasten", "args", obj("query", "Altlasten")),
                intent("zoom_to_result", target, "args", obj("query", "2084 Solothurn")))));
        geo = request -> {
            String path = request.getRequestURI().getPath();
            if (path.equals("/map/themes.json")) {
                return new Reply(200, themes());
            }
            if (path.equals("/api/search/v2/fts/")) {
                eq(query(request).get("searchtext"), "Karte: Altlasten");
                return layerHits(LAYER);
            }
            if (path.equals("/api/dataproduct/v1/weblayers")) {
                eq(query(request).get("filter"), LAYER);
                return layerDetails(LAYER, "Altlasten");
            }
            eq(path, "/api/search/v2/");
            eq(query(request).get("searchtext"), "2084 Solothurn");
            return objectHits(PARCEL, 1, "Grundstück", hit(PARCEL, "GB-Nr: 2084 - Solothurn (Liegenschaft)", BOX));
        };
        var response = post(CHAT, obj("message", "Lade die Altlasten und gehe zu " + target, "theme_id", "test"),
                metaHeaders());
        eq(response.status, 200);
        var rows = results(response);
        eq(rows.size(), 2);
        eq(rows.getFirst().get("intent"), 1L);
        eq(rows.getLast().get("intent"), 2L);
        var add = field(rows.getFirst(), "command");
        var zoom = field(rows.getLast(), "command");
        eq(add.get("type"), "add_layer");
        eq(field(add, "layer").get("id"), LAYER);
        eq(zoom.get("type"), "zoom_to_extent");
        eq(zoom.get("bbox"), BOX.stream().map(Integer::longValue).toList());
        counts(response, 1, 3, 2);
    }

    private void readOnlyAndGrounding() throws Exception {
        String parcel = "Grundstück 2084 in Solothurn";
        objectFixture("2084 Solothurn", PARCEL, 1, "Grundstück",
                hit(PARCEL, "GB-Nr: 2084 - Solothurn (Liegenschaft)", BOX));
        model(completion(null, declaration(intent("search_objects", parcel, "args", obj("query", "2084 Solothurn")))));
        var search = results(post(CHAT, obj("message", "Suche " + parcel + ", verschiebe die Karte aber nicht.",
                "theme_id", "test"))).getFirst();
        eq(search.containsKey("command"), false);
        eq(field(search, "data").get("type"), "object_results");

        model(completion(null, declaration()));
        eq(results(post(CHAT, obj("message", "Was würde passieren, wenn du auf Grundstück 2084 zoomst?"))), List.of());
        for (var invalid : List.of(
                toolCall("zoom_to_point", obj("coordinates", List.of(2605899, 1229278))),
                declaration(intent("zoom_to_result", "Passwang", "args", obj("query", "Passwang", "result_ref", "a".repeat(32)))),
                declaration(intent("add_layer", "Passwang", "args", obj("id", "ch.so.fabricated"))),
                declaration(intent("search_objects", "Passwang", "args", obj("query", 2084))))) {
            model(completion(null, invalid));
            error(post(CHAT, obj("message", "Gehe nach Passwang.")), 502, "invalidModelResponse");
        }

        String target = "2605899 1229278 bei 1:1000";
        var args = obj("easting", 2605899, "northing", 1229278, "scale", 1000);
        model(completion(null, declaration(intent("zoom_to_lv95", target, "args", args))));
        var command = field(results(post(CHAT, obj("message", "Gehe zu LV95 " + target))).getFirst(), "command");
        eq(command.get("type"), "zoom_to_point");
        eq(command.get("target_scale"), 1000L);
        args.put("easting", 2605900);
        model(completion(null, declaration(intent("zoom_to_lv95", target, "args", args))));
        error(post(CHAT, obj("message", "Gehe zu LV95 " + target)), 502, "invalidModelResponse");
    }

    private void pending() throws Exception {
        String parcel = "Grundstück 123 in Messen";
        model(completion(null, toolCall("declare_intents", obj("reply", "Die Karte wurde verschoben.", "intents",
                List.of(intent("add_layer", LAYER, "args", obj("id", LAYER)),
                        intent("zoom_to_result", parcel, "args", obj("query", "123 Messen")))))));
        geo = request -> {
            String path = request.getRequestURI().getPath();
            if (path.equals("/map/themes.json")) {
                return new Reply(200, themes());
            }
            if (path.equals("/api/dataproduct/v1/weblayers")) {
                eq(query(request).get("filter"), LAYER);
                return layerDetails(LAYER, "Altlasten");
            }
            eq(path, "/api/search/v2/");
            eq(query(request).get("searchtext"), "123 Messen");
            return objectHits(PARCEL, 2, "Grundstück", hit(PARCEL, "GB-Nr: 123 - Messen (Liegenschaft)", BOX),
                    hit(PARCEL, "GB-Nr: 123 - Brunnenthal [Messen] (Liegenschaft)", BOX));
        };
        var initial = post(CHAT, obj("message", "Lade " + LAYER + " und gehe zu " + parcel, "theme_id", "test"));
        eq(initial.json().containsKey("reply"), false);
        eq(field(results(initial).getFirst(), "command").get("type"), "add_layer");
        var waiting = results(initial).getLast();
        eq(waiting.containsKey("command"), false);
        var clarification = field(waiting, "clarification");
        eq(Assistant.string(clarification, "message").contains("Brunnenthal"), true);
        var carried = obj("goal", "zoom_to_result", "target", parcel, "query", "123 Messen");
        var emitted = new LinkedHashMap<>(carried);
        emitted.put("source_intent", 2L);
        eq(clarification.get("pending"), emitted);

        var followUp = obj("message", "Brunnenthal", "pending", List.of(carried), "theme_id", "test");
        model(completion(null, declaration(intent("zoom_to_result", "Brunnenthal", "resume", 1,
                "args", obj("query", "123 Brunnenthal Messen")))));
        objectFixture("123 Brunnenthal Messen", PARCEL, 1, "Grundstück",
                hit(PARCEL, "GB-Nr: 123 - Brunnenthal [Messen] (Liegenschaft)", BOX));
        var resumed = post(CHAT, followUp, metaHeaders());
        eq(results(resumed).size(), 1);
        eq(field(results(resumed).getFirst(), "command").get("type"), "zoom_to_extent");
        eq(resumed.json().get("pending"), List.of());
        counts(resumed, 1, 1, 1);

        model(completion(null, declaration(intent("zoom_to_result", "Brunnenthal", "resume", 1,
                "args", obj("query", "123 Brunnenthal Messen")), intent("get_map_state", null))));
        geo = request -> request.getRequestURI().getPath().equals("/map/themes.json")
                ? new Reply(200, themes()) : new Reply(503, obj());
        followUp.put("message", "Brunnenthal. Wo ist die Karte zentriert?");
        var failed = post(CHAT, followUp);
        eq(results(failed), List.of(obj("intent", 1L, "error", "locationUnavailable"),
                obj("intent", 2L, "command", obj("type", "get_map_state"))));
        eq(failed.json().get("pending"), List.of(carried));

        String completeAddress = "Langendorfstrasse 19b Solothurn";
        model(completion(null, declaration(intent("zoom_to_result", completeAddress,
                "resume", 1, "args", obj("query", completeAddress, "filters", List.of(ADDRESS))))));
        objectFixture("langendorfstrasse 19b solothurn", ADDRESS, 1, "Adresse",
                hit(ADDRESS, "Langendorfstrasse 19b, 4500 Solothurn (Adresse)", List.of(2605899, 1229278, 2605899, 1229278)));
        var resolved = post(CHAT, obj("message", completeAddress, "theme_id", "test",
                "pending", List.of(obj("goal", "zoom_to_result", "target", "zur Adresse"))));
        eq(field(results(resolved).getFirst(), "command").get("type"), "zoom_to_point");
    }

    private void numbered() throws Exception {
        var selection = List.of(obj("id", "water.one", "title", "Fluss"), obj("id", "water.two", "title", "See"));
        var history = List.of(obj("user", "Suche Wasser", "assistant", "Hier sind zwei Karten."));
        var payload = obj("message", "Zeige Nummer 2.", "last_results", selection, "history", history);
        model(completion(null, declaration(intent("add_layer", "Nummer 2", "layer_result_number", 2))));
        geo = request -> {
            eq(query(request).get("filter"), "water.two");
            return layerDetails("water.two", "Amtlicher See");
        };
        var command = field(results(post(CHAT, payload)).getFirst(), "command");
        eq(command.get("type"), "add_layer");
        eq(field(command, "layer").get("title"), "Amtlicher See");
        var context = providerContext(modelRequests.getFirst());
        eq(context.get("history"), history);
        eq(context.get("latest_layers"), List.of(obj("number", 1L, "title", "Fluss"), obj("number", 2L, "title", "See")));

        geo = ignored -> new Reply(200, obj());
        eq(results(post(CHAT, payload)), List.of(obj("intent", 1L, "error", "layerUnknown")));
        for (String number : List.of("2.0", "2e0")) {
            String arguments = "{\"intents\":[{\"goal\":\"add_layer\",\"target\":\"Nummer 2\",\"layer_result_number\":"
                    + number + "}]}";
            model(completion(null, rawCall("declare_intents", "declare", arguments)));
            error(post(CHAT, payload), 502, "invalidModelResponse");
        }
        model(completion(null, declaration(intent("add_layer", "Ja", "layer_result_number", 2, "resume", 1))));
        error(post(CHAT, obj("message", "Ja, und entferne Treffer 2", "last_results", selection,
                "pending", List.of(obj("goal", "add_layer", "target", "Wasser")))), 502, "invalidModelResponse");
    }

    private void pointEvidence() throws Exception {
        model(completion(null, declaration(intent("query_features", "Gewässerschutzbereich am Klosterplatz 1 in Solothurn",
                "args", obj("query", "Gewässerschutzbereiche", "address", "Klosterplatz 1 Solothurn")))));
        var payload = obj("message", "Welcher Gewässerschutzbereich gilt am Klosterplatz 1 in Solothurn?");
        for (int dataStatus : List.of(200, 404)) {
            geo = request -> {
                String path = request.getRequestURI().getPath();
                var params = query(request);
                if (path.equals("/api/search/v2/fts/")) {
                    if ("Karte: Gewässerschutzbereiche".equals(params.get("searchtext"))) {
                        return layerHits(DATASET);
                    }
                    eq(params.get("searchtext"), "klosterplatz 1 solothurn");
                    eq(params.get("filter"), ADDRESS);
                    return new Reply(200, obj("results", List.of(hit(ADDRESS, "Klosterplatz 1, 4500 Solothurn (Adresse)", POINT))));
                }
                eq(path, "/api/data/v1/" + DATASET + "/");
                eq(Assistant.parse(params.get("filter_geom")).get("coordinates"), List.of(2607600L, 1228517L));
                return new Reply(dataStatus, obj("type", "FeatureCollection", "numberMatched", 0, "features", List.of()));
            };
            var result = results(post(CHAT, payload)).getFirst();
            eq(result.containsKey("command"), false);
            if (dataStatus == 200) {
                eq(field(result, "data").get("type"), "feature_results");
                eq(field(result, "data").get("dataset_id"), DATASET);
                eq(field(result, "data").get("features"), List.of());
            } else {
                eq(result.get("error"), "datasetUnavailable");
            }
        }
    }

    private void layerRanking() throws Exception {
        var declaration = declaration(intent("present_layer_results", "Wasser", "args", obj("query", "Wasser")));
        geo = ignored -> layerHits("water.one", "water.two");
        for (var ids : List.of(List.of("water.two", "water.one", "water.two"), List.of("water.invented"))) {
            provider = request -> {
                if (toolName(request).equals("declare_intents")) {
                    return new Reply(200, completion(null, declaration));
                }
                eq(toolName(request), "select_layers");
                eq(providerContext(request).get("topic"), "Wasser");
                return new Reply(200, completion(null, toolCall("select_layers", obj("ids", ids))));
            };
            var response = post(CHAT, obj("message", "Suche Karten zu Wasser"), metaHeaders());
            var result = results(response).getFirst();
            if (ids.contains("water.invented")) {
                eq(result, obj("intent", 1L, "error", "invalidModelResponse"));
            } else {
                eq(layerIds(result), List.of("water.two", "water.one"));
            }
            eq(toolNames(), List.of("declare_intents", "select_layers"));
            counts(response, 2, 1, 1);
        }

        var topics = obj("Wasser", "water", "Grundwasser", "groundwater", "Boden", "soil", "Luft", "air");
        var intents = topics.keySet().stream()
                .map(topic -> intent("present_layer_results", topic, "args", obj("query", topic))).toArray();
        provider = request -> {
            if (toolName(request).equals("declare_intents")) {
                return new Reply(200, completion(null, declaration(intents)));
            }
            eq(toolName(request), "select_layers");
            String topic = Assistant.string(providerContext(request), "topic");
            return new Reply(200, completion(null, toolCall("select_layers", obj("ids", topicIds(topics.get(topic).toString())))));
        };
        geo = request -> {
            String topic = query(request).get("searchtext").substring("Karte: ".length());
            return layerHits(topicIds(topics.get(topic).toString()).toArray(String[]::new));
        };
        var limited = post(CHAT, obj("message", "Suche Karten zu Wasser, Grundwasser, Boden und Luft."), metaHeaders());
        var sorted = results(limited);
        eq(sorted.size(), 4);
        eq(sorted.getLast(), obj("intent", 4L, "error", "chatLimit"));
        var displayed = new LinkedHashSet<String>();
        for (int i = 0; i < 3; i++) {
            var ids = layerIds(sorted.get(i));
            eq(ids.isEmpty(), false);
            displayed.addAll(ids);
        }
        eq(displayed.size(), 10);
        eq(layerIds(sorted.getFirst()).size() + layerIds(sorted.get(1)).size() + layerIds(sorted.get(2)).size(), 10);
        counts(limited, 4, 3, 3);

        var alternative = declaration(intent("present_layer_results", "Hochwassergefahr",
                "args", obj("query", "Hochwasser", "alternative", "Fliesstiefen")));
        geo = request -> "Karte: Hochwasser".equals(query(request).get("searchtext"))
                ? layerHits() : layerHits("unrelated");
        provider = request -> {
            if (toolName(request).equals("declare_intents")) {
                return new Reply(200, completion(null, alternative));
            }
            eq(toolName(request), "select_layers");
            eq(providerContext(request).get("topic"), "Hochwasser");
            return new Reply(200, completion(null, toolCall("select_layers", obj("ids", List.of()))));
        };
        var fallback = post(CHAT, obj("message", "Suche Karten zur Hochwassergefahr."), metaHeaders());
        eq(layerIds(results(fallback).getFirst()), List.of());
        counts(fallback, 2, 2, 2);
    }

    private static List<String> topicIds(String prefix) {
        var ids = new ArrayList<String>();
        ids.add("shared");
        for (int i = 0; i < 9; i++) {
            ids.add(prefix + "." + i);
        }
        return ids;
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
        var context = providerContext(modelRequests.getFirst());
        eq(context.get("object_filters"), List.of(PARCEL, ADDRESS, BUILDING, PROJECTED, TERRAIN, MUNICIPALITY));
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
        error(post(CHAT, obj("message", "Hallo")), 502, "invalidModelResponse");

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
        eq(output.containsKey("round"), false);
        counts(debug, 1, 0, 0);
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

    private void chatDeadline() throws Exception {
        var shortTurn = new Assistant(ENV, fixtureOrigin, fixtureOrigin, Duration.ofMillis(850)).start(0);
        try {
            var server = origin(shortTurn.port());
            provider = ignored -> {
                pause(1400);
                return new Reply(200, completion(null, declaration()));
            };
            error(request(server, CHAT, "{\"message\":\"Hallo\"}", "application/json", Map.of()), 504, "chatTimeout");

            model(completion(null, declaration(
                    intent("get_map_state", null),
                    intent("present_layer_results", "Wasser", "args", obj("query", "Wasser")),
                    intent("get_visible_layers", null))));
            geo = ignored -> {
                pause(1400);
                return layerHits("water.one");
            };
            var payload = obj("message", "Zeige den Kartenstatus, suche Karten zu Wasser und zeige sichtbare Layer.");
            var response = request(server, CHAT, Assistant.JSON.toJson(payload), "application/json", metaHeaders());
            eq(response.status, 200);
            eq(results(response), List.of(obj("intent", 1L, "command", obj("type", "get_map_state")),
                    obj("intent", 2L, "error", "chatTimeout"),
                    obj("intent", 3L, "error", "chatTimeout")));
            counts(response, 1, 1, 1);
        } finally {
            shortTurn.shutdown();
        }
    }

    private static void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
                var request = Assistant.parse(exchange.getRequestBody().readAllBytes());
                modelRequests.add(request);
                response = provider.apply(request);
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
        return modelRequests.stream().map(AssistantTest::toolName).toList();
    }

    private static String toolName(Map<String, Object> request) {
        var tools = Assistant.list(request.get("tools"));
        eq(tools.size(), 1);
        return Assistant.string(field(Assistant.object(tools.getFirst()), "function"), "name");
    }

    private static Map<String, Object> providerContext(Map<String, Object> request) {
        String text = Assistant.string(Assistant.object(Assistant.list(request.get("messages")).getLast()), "content");
        return Assistant.parse(text.substring(contextOffset(request, text)));
    }

    private static int contextOffset(Map<String, Object> request, String text) {
        if (toolName(request).equals("select_layers")) {
            return 0;
        }
        String marker = "\n\nKontextdaten (JSON):\n";
        int offset = text.indexOf(marker);
        return offset < 0 ? text.length() : offset + marker.length();
    }

    private static Map<String, Object> normalizedModelRequest(Map<String, Object> request) {
        var normalized = new LinkedHashMap<>(request);
        var messages = Assistant.list(request.get("messages")).stream().map(Assistant::object).toList();
        var user = new LinkedHashMap<>(messages.getLast());
        String content = Assistant.string(user, "content");
        int offset = contextOffset(request, content);
        if (offset == content.length()) {
            return normalized;
        }
        // The model receives a JSON string after a plain-text prompt. Compare its decoded data.
        user.put("content", List.of(content.substring(0, offset), Assistant.parse(content.substring(offset))));
        normalized.put("messages", List.of(messages.getFirst(), user));
        return normalized;
    }

    private void objectFixture(String term, String source, int count, Object... hits) {
        objectFixture(term, source, count, "Objekt", hits);
    }

    private void objectFixture(String term, String source, int count, String category, Object... hits) {
        geo = request -> {
            if (request.getRequestURI().getPath().equals("/map/themes.json")) return new Reply(200, themes());
            eq(request.getRequestURI().getPath(), "/api/search/v2/");
            eq(query(request).get("searchtext"), term);
            return objectHits(source, count, category, hits);
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
        return post(path, body, Map.of());
    }

    private Reply post(String path, Object body, Map<String, String> headers) throws Exception {
        return compare(path, Assistant.JSON.toJson(body), "application/json", headers);
    }

    private Reply compare(String path, String body, String media) throws Exception {
        return compare(path, body, media, Map.of());
    }

    private Reply compare(String path, String body, String media, Map<String, String> headers) throws Exception {
        modelRequests.clear();
        var first = request(javaServer, path, body, media, headers);
        if (pythonServer != null) {
            var sent = List.copyOf(modelRequests);
            modelRequests.clear();
            var reference = request(pythonServer, path, body, media, headers);
            eq(first.status, reference.status);
            eq(canonical(first.body), canonical(reference.body));
            eq(sent.stream().map(AssistantTest::normalizedModelRequest).toList(),
                    modelRequests.stream().map(AssistantTest::normalizedModelRequest).toList());
            modelRequests.clear();
            modelRequests.addAll(sent);
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
                if (key.equals("ref") || key.equals("zoom_ref")) {
                    normalized.put(key, item == null ? null : "<object-ref>");
                } else if (!key.equals("duration_ms")) {
                    normalized.put(key, canonical(item));
                }
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
        var result = obj("goal", goal);
        if (target != null) {
            result.put("target", target);
        }
        result.putAll(obj(options));
        return result;
    }

    private static Map<String, Object> declaration(Object... intents) {
        return toolCall("declare_intents", obj("intents", List.of(intents)));
    }

    private static Map<String, Object> toolCall(String name, Map<String, Object> arguments) {
        return rawCall(name, name, Assistant.JSON.toJson(arguments));
    }

    private static Map<String, Object> rawCall(String name, String id, String arguments) {
        return obj("id", id, "type", "function", "function", obj("name", name, "arguments", arguments));
    }

    private static Reply layerHits(String... ids) {
        var results = Arrays.stream(ids)
                .map(id -> obj("dataproduct", obj("dataproduct_id", id, "display", id))).toList();
        return new Reply(200, obj("results", results));
    }

    private static Reply layerDetails(String id, String title) {
        return new Reply(200, obj(id, List.of(obj("name", id, "title", title))));
    }

    private static Reply objectHits(String source, int count, String category, Object... hits) {
        return new Reply(200, obj("results", List.of(hits),
                "result_counts", List.of(obj("dataproduct_id", source, "count", count, "filterword", category))));
    }

    private static Map<String, Object> field(Map<String, Object> obj, String name) {
        return Assistant.object(obj.get(name));
    }

    private static List<Map<String, Object>> results(Reply reply) {
        eq(reply.status, 200);
        return Assistant.list(reply.json().get("results")).stream().map(Assistant::object).toList();
    }

    private static List<String> layerIds(Map<String, Object> result) {
        return Assistant.list(field(result, "data").get("layers")).stream()
                .map(Assistant::object).map(layer -> Assistant.string(layer, "id")).toList();
    }

    private static Map<String, String> metaHeaders() {
        return Map.of("X-AI-Debug", "meta", "X-AI-Turn-Id", TURN);
    }

    private static void counts(Reply reply, long model, long geo, long searches) {
        var debug = field(reply.json(), "debug");
        eq(debug.get("model_calls"), model);
        eq(debug.get("geo_calls"), geo);
        eq(debug.get("searches"), searches);
    }

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
