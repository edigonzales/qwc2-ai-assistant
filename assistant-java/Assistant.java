//JAVA 25
//DEPS io.avaje:avaje-jex:3.6
//DEPS io.avaje:avaje-json-core:3.16
//SOURCES Geo.java
//SOURCES Schemas.java
//SOURCES Intents.java
//SOURCES ChatTurn.java
//FILES limits.json=../shared/limits.json
//FILES text-data.json
//NATIVE_OPTIONS --no-fallback -H:IncludeResources=(limits|text-data)\.json

import com.sun.net.httpserver.HttpExchange;
import io.avaje.jex.Jex;
import io.avaje.jex.http.NotFoundException;
import io.avaje.json.JsonAdapter;
import io.avaje.json.JsonReader;
import io.avaje.json.JsonWriter;
import io.avaje.json.mapper.JsonMapper;
import io.avaje.json.stream.JsonStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

public class Assistant {
    static {
        // An early 408/413 must close the socket without waiting to drain an unfinished body.
        System.setProperty("sun.net.httpserver.drainAmount", "0");
    }

    static final JsonStream STREAM = JsonStream.builder().serializeNulls(true).serializeEmpty(true).build();
    private static final JsonMapper MAPPER = JsonMapper.builder().jsonStream(STREAM).build();
    static final JsonMapper.Type<Object> JSON = MAPPER.type(new JsonAdapter<>() {
        @Override
        public Object fromJson(JsonReader reader) {
            return readValue(reader, 0);
        }

        @Override
        public void toJson(JsonWriter writer, Object value) {
            writer.jsonValue(value);
        }
    });
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    static final Pattern SPACE = Pattern.compile("[\\p{javaWhitespace}\\p{Z}\\x85]+");
    private static final int RESPONSE_LIMIT = 4 * 1024 * 1024;
    private static final String CHAT = "/api/v1/chat";
    private static final String GEO = "/api/v1/geo";
    private static final Map<String, String> METHODS = Map.of("/healthz", "GET", CHAT, "POST", GEO, "POST");
    private static final Pattern HOST = Pattern.compile("(?:127\\.0\\.0\\.1|localhost|assistant)(?::[0-9]+)?",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSxxx", Locale.ROOT).withZone(ZoneOffset.UTC);

    private static final Map<String, Object> MODEL_OPTIONS = Map.of(
            "stream", false,
            "max_tokens", 1600,
            "temperature", 0.2,
            "reasoning_effort", "none");
    static final long NO_TURN_DEADLINE = Long.MAX_VALUE;

    private final Map<String, String> env;
    private final URI providerOrigin;
    private final HttpClient providerClient = client(25);
    private final Geo geo;
    private final String token;
    private final long chatTimeoutNanos;

    // Only main supplies production origins. Tests use loopback HTTP fixtures.
    Assistant(Map<String, String> env, URI geoOrigin, URI providerOrigin) {
        this(env, geoOrigin, providerOrigin, Duration.ofSeconds(60));
    }

    Assistant(Map<String, String> env, URI geoOrigin, URI providerOrigin, Duration chatTimeout) {
        this.env = env;
        this.chatTimeoutNanos = chatTimeout.toNanos();
        this.providerOrigin = providerOrigin;
        token = trim(env.getOrDefault("INFOMANIAK_API_TOKEN", ""));
        geo = new Geo(client(6), geoOrigin);
    }

    public static void main(String[] args) {
        var env = System.getenv();
        new Assistant(env, URI.create("https://geo.so.ch"), URI.create("https://api.infomaniak.com"))
                .start(Integer.parseInt(env.getOrDefault("ASSISTANT_JAVA_PORT", "8085")));
    }

    Jex.Server start(int port) {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var server = Jex.create()
                .config(config -> config.host("127.0.0.1").executor(executor).ignoreTrailingSlashes(false))
                .port(port)
                .get("/healthz", ctx -> route(ctx.exchange()))
                .post(CHAT, ctx -> route(ctx.exchange()))
                .post(GEO, ctx -> route(ctx.exchange()))
                .error(NotFoundException.class, (ctx, error) -> route(ctx.exchange()))
                .start();
        server.onShutdown(() -> {
            executor.shutdown();
            providerClient.close();
            geo.client.close();
        });
        return server;
    }

    private void route(HttpExchange exchange) {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String host = header(exchange, "Host");
        var log = method.equals("POST") && (CHAT.equals(path) || GEO.equals(path))
                ? new Diagnostic(exchange, path) : null;
        int status = 500;
        try (exchange) {
            try {
                Map<String, Object> response;
                if (host == null || !HOST.matcher(host).matches()) {
                    status = 400;
                    write(exchange, status, "text/plain; charset=utf-8", "Invalid host header");
                    return;
                }
                String expected = METHODS.get(path);
                if (expected == null && path.endsWith("/") && METHODS.containsKey(path.substring(0, path.length() - 1))) {
                    String target = path.substring(0, path.length() - 1);
                    String query = exchange.getRequestURI().getRawQuery();
                    exchange.getResponseHeaders().set("Location", "http://" + host + target + (query == null ? "" : "?" + query));
                    status = 307;
                    exchange.sendResponseHeaders(status, -1);
                    return;
                }
                if (expected == null) throw new Failure(404, "Not Found");
                if (!method.equals(expected)) {
                    exchange.getResponseHeaders().set("Allow", expected);
                    throw new Failure(405, "Method Not Allowed");
                }
                if (log == null) {
                    response = Map.of("status", "UP");
                } else {
                    byte[] body = requestBody(exchange);
                    response = path.equals(CHAT) ? chat(body, log) : geo(body, log);
                }
                status = 200;
                write(exchange, status, "application/json", JSON.toJson(response));
            } catch (Failure failure) {
                status = failure.status;
                var error = obj("detail", failure.getMessage());
                if (log != null) {
                    log.event.put("error", failure.getMessage());
                    if (path.equals(CHAT)) error.putAll(log.browserDebug());
                }
                write(exchange, status, "application/json", JSON.toJson(error));
            }
        } catch (IOException ignored) {
            // The peer can disconnect while the response is being written.
        } finally {
            if (log != null) log.finish(status);
        }
    }

    private static void write(HttpExchange exchange, int status, String contentType, String content) throws IOException {
        byte[] bytes = content.getBytes(UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
            exchange.sendResponseHeaders(status, -1);
        } else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().flush();
        }
    }

    private static byte[] requestBody(HttpExchange exchange) {
        String contentType = header(exchange, "Content-Type");
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/json")) {
            throw new Failure(415, "jsonRequired");
        }
        int max = Schemas.REQUEST_BYTES;
        var read = new FutureTask<>(() -> exchange.getRequestBody().readNBytes(max + 1));
        Thread.startVirtualThread(read);
        try {
            byte[] body = read.get(5, TimeUnit.SECONDS);
            if (body.length > max) {
                exchange.getResponseHeaders().set("Connection", "close");
                throw new Failure(413, "requestTooLarge");
            }
            return body;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(408, "requestTimeout");
        } catch (ExecutionException | TimeoutException e) {
            // Keep the exchange writable for the 408. Closing it cancels the outstanding read.
            exchange.getResponseHeaders().set("Connection", "close");
            throw new Failure(408, "requestTimeout");
        }
    }

    private Map<String, Object> geo(byte[] body, Diagnostic log) {
        Map<String, Object> payload;
        Map<String, Object> args;
        String name;
        try {
            payload = parse(body);
            fields(payload, Set.of("name", "args", "theme_id"));
            name = string(payload, "name");
            require(Geo.SERVICES.containsKey(name));
            args = object(payload.get("args"));
            require(Schemas.valid(args, Schemas.GEO_SCHEMAS.get(name)));
            optionalString(payload.get("theme_id"), 1, 200);
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "invalidTool");
        }
        if (!log.debug) return geo.run(name, args, (String) payload.get("theme_id"));

        var details = obj("tool", name, "service", Geo.SERVICES.get(name));
        log.event.put("geo", details);
        if (log.logPayload) log.capture(obj("arguments", args));
        long start = System.nanoTime();
        Map<String, Object> result;
        try {
            result = geo.run(name, args, (String) payload.get("theme_id"));
        } finally {
            details.put("duration_ms", elapsed(start));
        }
        for (String key : List.of("layers", "candidates", "features")) {
            if (result.containsKey(key)) {
                details.put("result_count", list(result.get(key)).size());
                break;
            }
        }
        if (log.logPayload) log.capture(obj("arguments", args, "result", result));
        return result;
    }

    private Map<String, Object> chat(byte[] body, Diagnostic log) {
        Intents.ChatRequest request;
        try {
            request = Intents.readRequest(parse(body));
        } catch (IllegalArgumentException e) {
            throw new Failure(400, "invalidConversation");
        }

        var result = new ChatTurn(this, geo, log).run(request, System.nanoTime() + chatTimeoutNanos);
        result.putAll(log.browserDebug());
        return result;
    }

    Map<String, Object> callModel(List<Map<String, Object>> messages, Map<String, Object> tool,
                                  Diagnostic log, long deadlineNanos) {
        requireTime(deadlineNanos);
        int calls = integer(log.event.get("model_calls"));
        if (calls >= Schemas.MAX_MODEL_CALLS) {
            throw new Failure(409, "chatLimit");
        }

        String product = trim(env.getOrDefault("INFOMANIAK_PRODUCT_ID", ""));
        String model = trim(env.getOrDefault("INFOMANIAK_MODEL", ""));
        if (token.isEmpty() || !product.matches("[0-9]{1,20}") || model.isEmpty()) {
            throw new Failure(503, "modelNotConfigured");
        }

        log.event.put("model_calls", calls + 1);
        String toolName = string(object(tool.get("function")), "name");
        var modelRequest = obj(
                "model", model,
                "messages", messages,
                "tools", List.of(tool),
                "tool_choice", "required");
        modelRequest.putAll(MODEL_OPTIONS);

        var provider = obj(
                "name", "infomaniak",
                "model", model,
                "tool_choice", "required",
                "available_tools", List.of(toolName),
                "options", MODEL_OPTIONS);
        if (log.debug) {
            log.event.put("provider", provider);
        }
        boolean capture = log.logPayload || "content".equals(log.browserMode);
        if (capture) {
            log.capture(obj("messages", messages));
        }

        var uri = providerOrigin.resolve("/2/ai/" + product + "/openai/v1/chat/completions");
        var request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(modelRequest)))
                .build();
        WireResponse upstream;
        long start = System.nanoTime();
        try {
            upstream = send(providerClient, request, 25, 30, RESPONSE_LIMIT, deadlineNanos);
            if (upstream.status() < 200 || upstream.status() >= 300) {
                provider.put("http_status", upstream.status());
                throw providerFailure(upstream);
            }
        } catch (HttpTimeoutException e) {
            throw new Failure(504, "providerTimeout");
        } catch (IOException e) {
            throw new Failure(502, "providerUnavailable");
        } finally {
            provider.put("duration_ms", elapsed(start));
        }

        try {
            var response = parse(upstream.body());
            var choice = object(list(response.get("choices")).getFirst());
            var message = object(choice.get("message"));
            var finish = choice.get("finish_reason");
            provider.put("finish_reason", finish);
            if (response.get("usage") instanceof Map<?, ?>) {
                var usage = object(response.get("usage"));
                provider.put("usage", obj(
                        "prompt_tokens", usage.get("prompt_tokens"),
                        "completion_tokens", usage.get("completion_tokens"),
                        "total_tokens", usage.get("total_tokens")));
            }
            require("assistant".equals(message.get("role"))
                    && !"length".equals(finish)
                    && !"content_filter".equals(finish));

            var callsReturned = array(message.get("tool_calls"), 1, 1);
            var call = object(callsReturned.getFirst());
            String id = checkedString(call.get("id"), 1, 128);
            require(id.matches("[A-Za-z0-9_-]+") && "function".equals(call.get("type")));
            var function = object(call.get("function"));
            require(toolName.equals(checkedString(function.get("name"), 1, 32)));
            String arguments = checkedString(function.get("arguments"), 2, 12_000);

            provider.put("tool_calls", List.of(toolName));
            if (capture) {
                var capturedCall = obj("id", id, "type", "function",
                        "function", obj("name", toolName, "arguments", arguments));
                log.capture(obj("messages", messages, "assistant",
                        obj("role", "assistant", "tool_calls", List.of(capturedCall))));
            }
            var result = parse(arguments);
            requireTime(deadlineNanos);
            return result;
        } catch (IllegalArgumentException | NoSuchElementException e) {
            throw new Failure(502, "invalidModelResponse");
        }
    }

    private static Failure providerFailure(WireResponse response) {
        int status = response.status();
        String error = switch (status) {
            case 401, 403 -> "providerAuthentication";
            case 429 -> "providerRateLimit";
            case 400, 422 -> new String(response.body(), UTF_8).toLowerCase(Locale.ROOT).contains("tool")
                    ? "providerToolSupport" : "providerRejected";
            default -> status >= 500 ? "providerUnavailable" : "providerRejected";
        };
        return new Failure(status == 429 ? 429 : 502, error);
    }

    static Map<String, Object> resource(String name) {
        try (var file = Assistant.class.getResourceAsStream("/" + name)) {
            if (file == null) throw new IllegalStateException("Missing resource " + name);
            return parse(file.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load resource " + name, e);
        }
    }

    static Map<String, Object> parse(byte[] bytes) {
        try {
            return parse(UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString());
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8", e);
        }
    }

    static Map<String, Object> parse(String json) {
        int start = 0;
        int end = json.length();
        while (start < end && " \r\n\t".indexOf(json.charAt(start)) >= 0) start++;
        while (end > start && " \r\n\t".indexOf(json.charAt(end - 1)) >= 0) end--;
        String input = json.substring(start, end);
        require(input.startsWith("{") && input.endsWith("}"));
        // Avaje's raw scanner validates separators and the full document. Its default
        // Object adapter rounds 1.0 to an integer, so read numeric tokens by spelling.
        try (var reader = STREAM.reader("[" + input + "]")) {
            reader.beginArray();
            require(reader.hasNextElement() && reader.readRaw().equals(input));
            require(!reader.hasNextElement());
            reader.endArray();
            try (var values = STREAM.reader(input)) {
                return object(JSON.fromJson(values));
            }
        } catch (RuntimeException | StackOverflowError e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }

    private static Object readValue(JsonReader reader, int depth) {
        require(depth <= 200);
        return switch (reader.currentToken()) {
            case BEGIN_OBJECT -> {
                var value = new LinkedHashMap<String, Object>();
                reader.beginObject();
                while (reader.hasNextField()) {
                    String key = reader.nextField();
                    value.put(key, readValue(reader, depth + 1));
                }
                reader.endObject();
                yield value;
            }
            case BEGIN_ARRAY -> {
                var value = new ArrayList<>();
                reader.beginArray();
                while (reader.hasNextElement()) value.add(readValue(reader, depth + 1));
                reader.endArray();
                yield value;
            }
            case NUMBER -> {
                String raw = reader.readRaw().stripTrailing();
                require(JSON_NUMBER.matcher(raw).matches());
                if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                    require(raw.length() <= (raw.startsWith("-") ? 4301 : 4300));
                    try {
                        yield Long.valueOf(raw);
                    } catch (NumberFormatException e) {
                        yield new BigDecimal(raw);
                    }
                }
                double number = Double.parseDouble(raw);
                require(Double.isFinite(number));
                yield number;
            }
            default -> MAPPER.fromJson(reader);
        };
    }

    static HttpClient client(int timeout) {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeout))
                .proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    record WireResponse(int status, byte[] body) {}

    static void requireTime(long deadlineNanos) {
        if (deadlineNanos != NO_TURN_DEADLINE && deadlineNanos - System.nanoTime() <= 0) {
            throw new Failure(504, "chatTimeout");
        }
    }

    static WireResponse send(HttpClient client, HttpRequest request, int idle, int total, int max) throws IOException {
        return send(client, request, idle, total, max, NO_TURN_DEADLINE);
    }

    static WireResponse send(HttpClient client, HttpRequest request, int idle, int total, int max,
                             long turnDeadline) throws IOException {
        requireTime(turnDeadline);
        long deadline = Math.min(System.nanoTime() + TimeUnit.SECONDS.toNanos(total), turnDeadline);
        try {
            var response = await(client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()), deadline, idle);
            try (InputStream input = response.body()) {
                var output = new ByteArrayOutputStream();
                var buffer = new byte[16384];
                while (true) {
                    var read = new FutureTask<>(() -> input.read(buffer));
                    Thread.startVirtualThread(read);
                    int size = await(read, deadline, idle);
                    if (size < 0) {
                        break;
                    }
                    if (output.size() + size > max) {
                        throw new ResponseTooLarge();
                    }
                    output.write(buffer, 0, size);
                }
                requireTime(turnDeadline);
                return new WireResponse(response.statusCode(), output.toByteArray());
            }
        } catch (IOException e) {
            requireTime(turnDeadline);
            throw e;
        }
    }

    private static <T> T await(Future<T> future, long deadline, int idle) throws IOException {
        try {
            long remaining = Math.min(TimeUnit.SECONDS.toNanos(idle), deadline - System.nanoTime());
            return future.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("Upstream timed out");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(e.getCause());
        }
    }

    final class Diagnostic {
        final Map<String, Object> event;
        final long start = System.nanoTime();
        final boolean debug = "1".equals(env.get("ASSISTANT_DEBUG"));
        final boolean logPayload = debug && "1".equals(env.get("ASSISTANT_DEBUG_PAYLOAD"));
        final String browserMode;
        Map<String, Object> preview;

        Diagnostic(HttpExchange exchange, String path) {
            event = obj("event", "assistant.request", "route", path);
            String mode = header(exchange, "X-AI-Debug");
            browserMode = debug && ("meta".equals(mode) || "content".equals(mode)) ? mode : null;
            String turn = header(exchange, "X-AI-Turn-Id");
            if (turn != null && turn.matches("[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}")) {
                event.put("turn_id", turn);
            }
        }

        void capture(Object value) {
            byte[] data = JSON.toJson(redact(value)).getBytes(UTF_8);
            int end = Math.min(data.length, 16384);
            if (end < data.length) {
                while ((data[end] & 0xc0) == 0x80) end--;
            }
            preview = obj("json", new String(data, 0, end, UTF_8), "truncated", data.length > 16384);
        }

        Map<String, Object> browserDebug() {
            if (browserMode == null) return Map.of();
            var details = new LinkedHashMap<>(event);
            if ("content".equals(browserMode) && preview != null) details.put("payload", preview);
            return obj("debug", redact(details));
        }

        void finish(int status) {
            if (status >= 500) event.putIfAbsent("error", "internalError");
            event.putAll(obj("timestamp", TIMESTAMP.format(Instant.now()),
                    "status", status, "duration_ms", elapsed(start)));
            if (logPayload && preview != null) event.put("payload", preview);
            System.out.println(JSON.toJson(redact(event)));
        }
    }

    private Object redact(Object value) {
        if (token.isEmpty()) return value;
        if (value instanceof String text) return text.replace(token, "[redacted]");
        if (value instanceof List<?> items) return items.stream().map(this::redact).toList();
        if (value instanceof Map<?, ?> map) {
            var cleaned = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> cleaned.put((String) redact(key), redact(item)));
            return cleaned;
        }
        return value;
    }

    static double elapsed(long start) {
        return Math.round((System.nanoTime() - start) / 100_000.0) / 10.0;
    }

    private static String header(HttpExchange exchange, String name) {
        return exchange.getRequestHeaders().getFirst(name);
    }

    static class Failure extends RuntimeException {
        final int status;

        Failure(int status, String code) {
            super(code);
            this.status = status;
        }
    }

    static class ResponseTooLarge extends IOException {}

    static Map<String, Object> obj(Object... pairs) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        require(value instanceof Map<?, ?>);
        return (Map<String, Object>) value;
    }

    static Map<String, Object> objectOrEmpty(Object value) {
        return value instanceof Map<?, ?> ? object(value) : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object value) {
        require(value instanceof List<?>);
        return (List<Object>) value;
    }

    static List<Object> array(Object value, int min, int max) {
        var items = list(value);
        require(items.size() >= min && items.size() <= max);
        return items;
    }

    static void fields(Map<String, Object> value, Set<String> allowed) {
        require(allowed.containsAll(value.keySet()));
    }

    static boolean isInteger(Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof BigDecimal;
    }

    static int integer(Object value) {
        return ((Number) value).intValue();
    }

    static boolean sameNumber(Object a, Object b) {
        return a instanceof Number first && b instanceof Number second && first.doubleValue() == second.doubleValue();
    }

    static String string(Map<String, Object> value, String name) {
        return checkedString(value.get(name), 0, Integer.MAX_VALUE);
    }

    static String optionalString(Object value, int min, int max) {
        return value == null ? null : checkedString(value, min, max);
    }

    static String checkedString(Object value, int min, int max) {
        require(value instanceof String);
        String str = (String) value;
        require(length(str) >= min && length(str) <= max);
        return str;
    }

    static int length(String value) {
        return value.codePointCount(0, value.length());
    }

    static String trim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && space(value.charAt(start))) start++;
        while (end > start && space(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }

    private static boolean space(char value) {
        return Character.isWhitespace(value) || Character.isSpaceChar(value) || value == 0x85;
    }

    static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("Invalid data");
    }
}
