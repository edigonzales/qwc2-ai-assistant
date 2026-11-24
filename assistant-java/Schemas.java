import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class Schemas {
    private static final Map<String, Object> LIMITS = Assistant.resource("limits.json");

    static final int MESSAGE_LENGTH = Assistant.integer(LIMITS.get("messageLength"));
    static final int REPLY_LENGTH = Assistant.integer(LIMITS.get("replyLength"));
    static final int HISTORY_TURNS = Assistant.integer(LIMITS.get("historyTurns"));
    static final int REQUEST_BYTES = Assistant.integer(LIMITS.get("requestBytes"));

    static final int MAX_MODEL_CALLS = 4;
    static final int MAX_INTENTS = 8;
    static final int MAX_SEARCHES = 3;
    static final int LAYERS_PER_SEARCH = 20;
    static final int MAX_OBJECT_RESULTS = 25;
    static final int MAX_LAYER_RESULTS = 10;
    static final int MAX_SEARCH_TEXT_LENGTH = 160;

    static final Map<String, Object> SEARCH_TEXT = textSchema(MAX_SEARCH_TEXT_LENGTH, "\\S");
    static final Map<String, Object> CATALOG_ID = textSchema(200, "^[A-Za-z0-9_][A-Za-z0-9_.:-]*$");
    static final Map<String, Object> EASTING = numberSchema(2480000, 2840000);
    static final Map<String, Object> NORTHING = numberSchema(1070000, 1300000);
    static final Map<String, Object> SCALE = numberSchema(1, 10000000);
    static final Map<String, Object> OBJECT_FILTERS = arraySchema(25, CATALOG_ID);
    static final Map<String, Object> LAYER_IDS = arraySchema(MAX_LAYER_RESULTS, CATALOG_ID);

    static final Map<String, Map<String, Object>> GEO_SCHEMAS = Map.of(
            "search_objects", objectSchema(
                    List.of("query"), Assistant.obj("query", SEARCH_TEXT, "filters", OBJECT_FILTERS)),
            "search_layers", objectSchema(List.of("query"), Assistant.obj("query", SEARCH_TEXT)),
            "get_layer_details", objectSchema(List.of("id"), Assistant.obj("id", CATALOG_ID)),
            "resolve_location", objectSchema(List.of("address"), Assistant.obj("address", SEARCH_TEXT)),
            "query_features", objectSchema(
                    List.of("dataset_id", "easting", "northing"),
                    Assistant.obj("dataset_id", CATALOG_ID, "easting", EASTING, "northing", NORTHING))
    );

    private Schemas() {}

    static Map<String, Object> objectSchema(List<?> required, Map<String, Object> properties) {
        return Assistant.obj(
                "type", "object", "required", required, "additionalProperties", false, "properties", properties);
    }

    static Map<String, Object> arraySchema(int maximum, Map<String, Object> items) {
        return Assistant.obj("type", "array", "maxItems", maximum, "items", items);
    }

    static Map<String, Object> textSchema(int maximum, String pattern) {
        return Assistant.obj("type", "string", "minLength", 1, "maxLength", maximum, "pattern", pattern);
    }

    static Map<String, Object> integerSchema(int maximum) {
        return Assistant.obj("type", "integer", "minimum", 1, "maximum", maximum);
    }

    private static Map<String, Object> numberSchema(int minimum, int maximum) {
        return Assistant.obj("type", "number", "minimum", minimum, "maximum", maximum);
    }

    static boolean valid(Object value, Map<String, Object> schema) {
        if (schema == null) {
            return false;
        }
        return switch (Assistant.string(schema, "type")) {
            case "object" -> validObject(value, schema);
            case "string" -> validString(value, schema);
            case "number" -> validNumber(value, schema);
            case "integer" -> Assistant.isInteger(value) && validNumber(value, schema);
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List<?> items
                    && items.size() <= Assistant.integer(schema.get("maxItems"))
                    && items.stream().allMatch(item -> valid(item, Assistant.object(schema.get("items"))));
            default -> false;
        };
    }

    private static boolean validObject(Object value, Map<String, Object> schema) {
        if (!(value instanceof Map<?, ?> fields)) {
            return false;
        }
        if (!fields.keySet().containsAll(Assistant.list(schema.getOrDefault("required", List.of())))) {
            return false;
        }

        var properties = Assistant.object(schema.get("properties"));
        for (var field : fields.entrySet()) {
            if (!properties.containsKey(field.getKey())
                    || !valid(field.getValue(), Assistant.object(properties.get(field.getKey())))) {
                return false;
            }
        }
        return true;
    }

    private static boolean validString(Object value, Map<String, Object> schema) {
        if (!(value instanceof String text)) {
            return false;
        }
        if (schema.containsKey("enum")) {
            return Assistant.list(schema.get("enum")).contains(text);
        }

        int length = Assistant.length(text);
        if (length < Assistant.integer(schema.getOrDefault("minLength", 0))
                || length > Assistant.integer(schema.getOrDefault("maxLength", length))) {
            return false;
        }
        if (!(schema.get("pattern") instanceof String pattern)) {
            return true;
        }
        if (pattern.equals("\\S")) {
            return !Assistant.trim(text).isEmpty();
        }

        var matcher = Pattern.compile(pattern).matcher(text);
        return pattern.startsWith("^") ? matcher.matches() : matcher.find();
    }

    private static boolean validNumber(Object value, Map<String, Object> schema) {
        if (!(value instanceof Number number)) {
            return false;
        }

        double amount = number.doubleValue();
        return Double.isFinite(amount)
                && amount >= ((Number) schema.get("minimum")).doubleValue()
                && amount <= ((Number) schema.get("maximum")).doubleValue();
    }
}
