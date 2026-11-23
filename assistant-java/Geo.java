import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

// Solothurn's public read APIs. Map actions remain in QWC2.
final class Geo {
    static final Map<String, String> SERVICES = Map.of(
            "search_layers", "search", "search_objects", "search", "resolve_location", "search",
            "get_layer_details", "weblayers", "query_features", "data");
    static final String ADDRESS_LAYER = "ch.so.agi.av.gebaeudeadressen.gebaeudeeingaenge";
    private static final String FTS = "/api/search/v2/fts/";
    private static final String SEARCH = "/api/search/v2/";
    private static final int CASE = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern ADDRESS = Pattern.compile(
            "(.+?)\\s+(\\d+[a-z]?)(?:\\s+(?:in\\s+)?(?:(\\d{4})(?:\\s+|$))?(.*))?", CASE);
    private static final Pattern PARCEL = Pattern.compile("GB-Nr:\\s*(\\S+)\\s+-\\s+([^\\[\\]]+?)(?:\\s*\\[([^\\]]+)\\])?");
    private static final Pattern PARCEL_QUERY = Pattern.compile(
            "^(?:(?:(?:projektiertes|rechtskräftiges)\\s+)?(?:grundstück|grundstueck|parzelle|gb-nr\\.?)"
                    + "\\s*:?\\s*(?:nr\\.?\\s*)?(?=\\d)|adresse\\s*:?\\s+)", CASE);
    private static final Pattern EGID = Pattern.compile("EGID\\s*:?\\s*(\\d+)", CASE);
    private static final Pattern NUMBER_LOCALITY = Pattern.compile("^\\p{Nd}+\\s", CASE);
    private static final Pattern ENTITIES = Pattern.compile("&(#(?:[xX][0-9a-fA-F]+|[0-9]+);?|[^\\t\\n\\f <&#;]{1,32};?)");
    private static final Pattern LINKS = Pattern.compile("(?:https?://|attachment://|www\\.)\\S+", CASE);
    private static final Map<String, Object> TEXT_DATA = Assistant.resource("text-data.json");
    private static final Map<String, Object> HTML_ENTITIES = Assistant.object(TEXT_DATA.get("htmlEntities"));
    private static final Map<String, Object> CASE_FOLD = Assistant.object(TEXT_DATA.get("caseFold"));
    private static final String C1_ENTITIES = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008dŽ\u008f\u0090‘’“”•–—˜™š›œ\u009džŸ";

    final HttpClient client;
    private final URI origin;
    private volatile ThemeFilters cache;
    private record ThemeFilters(long time, Map<String, List<String>> values) {}

    Geo(HttpClient client, URI origin) {
        this.client = client;
        this.origin = origin;
    }

    Map<String, Object> run(String name, Map<String, Object> args, String theme) {
        return switch (name) {
            case "search_layers" -> searchLayers(Assistant.string(args, "query"));
            case "get_layer_details" -> layerDetails(Assistant.string(args, "id"));
            case "resolve_location" -> resolveLocation(Assistant.string(args, "address"));
            case "query_features" -> queryFeatures(Assistant.string(args, "dataset_id"), args.get("easting"), args.get("northing"));
            case "search_objects" -> searchObjects(Assistant.string(args, "query"),
                    args.containsKey("filters") ? Assistant.list(args.get("filters")) : List.of(), theme);
            default -> throw new IllegalArgumentException("Unknown read tool");
        };
    }

    private Map<String, Object> get(String path, Map<String, ?> query, String unavailable, String invalid, String missing, int seconds) {
        var params = new StringJoiner("&");
        query.forEach((key, value) -> params.add(key + "=" + URLEncoder.encode(value.toString(), UTF_8)));
        var uri = origin.resolve(path + (params.length() == 0 ? "" : "?" + params));
        var request = HttpRequest.newBuilder(uri).header("Accept", "application/json").GET().build();
        try {
            var response = Assistant.send(client, request, 6, seconds, 4 * 1024 * 1024);
            if (response.status() == 404 && missing != null) throw new Assistant.Failure(404, missing);
            if (response.status() < 200 || response.status() >= 300) throw new Assistant.Failure(502, unavailable);
            return Assistant.parse(response.body());
        } catch (Assistant.ResponseTooLarge | IllegalArgumentException e) {
            throw new Assistant.Failure(502, invalid);
        } catch (IOException e) {
            throw new Assistant.Failure(502, unavailable);
        }
    }

    List<String> filters(String theme) {
        if (theme == null) return List.of();
        var current = cache;
        if (current != null && System.nanoTime() - current.time < 30_000_000_000L) {
            return current.values.getOrDefault(theme, List.of());
        }
        var config = get("/map/themes.json", Map.of(), "locationUnavailable", "invalidLocation", null, 3);
        var byTheme = new HashMap<String, List<String>>();
        var pending = new ArrayList<>();
        pending.add(config.get("themes"));
        while (!pending.isEmpty()) {
            var node = Assistant.objectOrEmpty(pending.removeLast());
            for (String key : List.of("groups", "items", "subdirs")) {
                if (node.get(key) instanceof List<?> items) pending.addAll(items);
            }
            var id = node.get("id");
            if (!Assistant.valid(id, Assistant.ID_SCHEMA) || !(node.get("searchProviders") instanceof List<?> providers)) continue;
            var filters = new LinkedHashSet<String>();
            for (Object raw : providers) {
                var provider = Assistant.objectOrEmpty(raw);
                if (!"fulltext".equals(provider.get("provider"))) continue;
                if (Assistant.objectOrEmpty(provider.get("params")).get("default") instanceof List<?> defaults) {
                    for (Object filter : defaults) {
                        if (Assistant.valid(filter, Assistant.ID_SCHEMA) && !"foreground".equals(filter)) filters.add((String) filter);
                    }
                }
            }
            byTheme.put((String) id, List.copyOf(filters));
        }
        cache = new ThemeFilters(System.nanoTime(), byTheme);
        return byTheme.getOrDefault(theme, List.of());
    }

    private Map<String, Object> searchObjects(String query, List<Object> filters, String theme) {
        var defaults = filters(theme);
        if (defaults.isEmpty()) throw new Assistant.Failure(503, "locationUnavailable");
        if (!defaults.containsAll(filters)) throw new Assistant.Failure(400, "invalidTool");
        var selected = filters.isEmpty() ? defaults : filters.stream().map(String.class::cast).distinct().toList();
        String term = objectSearchText(query, selected);
        int limit = Assistant.limit("objectResults");
        var data = get(SEARCH, Map.of("searchtext", term, "filter", String.join(",", selected), "limit", limit),
                "locationUnavailable", "invalidLocation", null, 9);
        if (!(data.get("results") instanceof List<?> raw) || !(data.get("result_counts") instanceof List<?> rawCounts)) {
            throw new Assistant.Failure(502, "invalidLocation");
        }
        var counts = new LinkedHashMap<String, Map<String, Object>>();
        for (Object row : rawCounts) {
            if (!(row instanceof Map<?, ?>)) throw new Assistant.Failure(502, "invalidLocation");
            var item = Assistant.object(row);
            Object source = item.get("dataproduct_id");
            Object count = item.get("count");
            if (source instanceof String id && selected.contains(id) && !"dataproduct".equals(id) && Assistant.isInteger(count)) {
                counts.put(id, Assistant.obj("filter", id, "type", geoText(item.get("filterword"), 50), "count", count));
            }
        }
        var candidates = new ArrayList<Map<String, Object>>();
        var returned = new HashMap<String, Integer>();
        var seen = new HashSet<List<Object>>();
        int exact = 0;
        String proof = term;
        String zoom = null;
        for (Object row : raw.subList(0, Math.min(raw.size(), limit))) {
            var hit = Assistant.objectOrEmpty(Assistant.objectOrEmpty(row).get("feature"));
            if (!(hit.get("dataproduct_id") instanceof String source) || !selected.contains(source)) continue;
            returned.merge(source, 1, Integer::sum);
            String label = geoText(hit.get("display"), 160);
            if (label.isEmpty()) throw new Assistant.Failure(502, "invalidLocation");
            String category = (String) counts.getOrDefault(source, Map.of()).getOrDefault("type", "");
            String name = category.isEmpty() ? term : Pattern.compile("^" + Pattern.quote(category) + "[:\\s]+", CASE)
                    .matcher(term).replaceFirst("");
            boolean matches = objectExact(name, label, source);
            if (label.startsWith("GB-Nr:") && NUMBER_LOCALITY.matcher(term).find() && !matches) continue;
            var bbox = bbox(hit);
            if (!seen.add(List.of(source, label, bbox == null ? List.of() : bbox.stream().map(v -> ((Number) v).doubleValue()).toList()))) {
                continue;
            }
            String ref = UUID.randomUUID().toString().replace("-", "");
            candidates.add(Assistant.obj("ref", ref, "label", label, "filter", source, "type", category, "bbox", bbox));
            if (matches) {
                exact++;
                zoom = bbox == null ? null : ref;
                proof = name;
            }
        }
        boolean partial = raw.size() > limit;
        for (String id : selected) {
            Number count = (Number) counts.getOrDefault(id, Map.of()).getOrDefault("count", 0);
            if (count.doubleValue() != returned.getOrDefault(id, 0)) partial = true;
        }
        boolean unique = exact == 1 && !partial;
        return Assistant.obj("query", unique ? proof : term, "candidates", candidates,
                "counts", new ArrayList<>(counts.values()), "partial", partial, "zoom_ref", unique ? zoom : null);
    }

    static String objectSearchText(String query, List<String> selected) {
        String term = PARCEL_QUERY.matcher(compact(query.replace(',', ' '))).replaceFirst("");
        var egid = EGID.matcher(term);
        if (egid.matches()) return egid.group(1);
        if (NUMBER_LOCALITY.matcher(term).find()) {
            return Pattern.compile("\\s+(?:in(?: der gemeinde)?|im grundbuchkreis)\\s+", CASE).matcher(term).replaceAll(" ");
        }
        if (selected.contains(ADDRESS_LAYER) && (selected.equals(List.of(ADDRESS_LAYER))
                || Pattern.compile("\\b\\d+[a-z]?\\s+in\\s+", CASE).matcher(term).find())) {
            var parts = addressFields(term);
            if (parts != null) return joinAddress(parts);
        }
        return term;
    }

    private static boolean objectExact(String term, String label, String source) {
        String name = label.replaceFirst("(?U)\\s+\\([^()]+\\)$", "");
        if (source.equals(ADDRESS_LAYER)) {
            var requested = addressFields(term);
            return requested != null && !requested.get(3).isEmpty() && addressMatches(requested, addressFields(name));
        }
        if (name.startsWith("GB-Nr:")) {
            var match = PARCEL.matcher(name);
            if (!match.matches()) return false;
            String number = match.group(1);
            String district = match.group(2);
            String municipality = match.group(3);
            var names = municipality == null ? List.of(number + " " + district)
                    : List.of(number + " " + municipality, number + " " + district + " " + municipality,
                            number + " " + municipality + " " + district);
            for (String spelling : names) {
                if (wordText(term).equals(wordText(spelling))
                        || wordText(term).equals(wordText(spelling.replaceFirst("(?Ui)\\s*\\(SO\\)\\s*$", "")))) return true;
            }
            return false;
        }
        var egid = Pattern.compile("EGID:\\s*(\\d+)").matcher(name);
        if (egid.matches()) return term.equals(egid.group(1));
        String baseName = name.split("(?U)\\s*[,|]\\s*", 2)[0];
        name = name.replaceAll("(?U)\\s*\\|\\s*\\d{4}\\b", " ");
        return wordText(term).equals(wordText(name)) || wordText(term).equals(wordText(baseName));
    }

    private static String wordText(String text) {
        var matcher = Pattern.compile("[\\p{L}\\p{N}]+").matcher(fold(text));
        var normalized = new StringJoiner(" ");
        while (matcher.find()) normalized.add(matcher.group());
        return normalized.toString();
    }

    private static List<?> bbox(Map<String, Object> hit) {
        if (!"EPSG:2056".equals(hit.get("srid")) || !(hit.get("bbox") instanceof List<?> bounds) || bounds.size() != 4) return null;
        for (int i = 0; i < bounds.size(); i++) {
            var schema = Assistant.object(Assistant.COORDINATES.get(i % 2 == 0 ? "easting" : "northing"));
            if (!Assistant.valid(bounds.get(i), schema)) return null;
        }
        if (((Number) bounds.get(0)).doubleValue() > ((Number) bounds.get(2)).doubleValue()
                || ((Number) bounds.get(1)).doubleValue() > ((Number) bounds.get(3)).doubleValue()) return null;
        return bounds;
    }

    private Map<String, Object> searchLayers(String query) {
        String term = Assistant.trim(query);
        var data = get(FTS, Map.of("searchtext", "Karte: " + term, "limit", 10),
                "catalogUnavailable", "invalidCatalog", null, 9);
        if (!(data.get("results") instanceof List<?> rows)) throw new Assistant.Failure(502, "invalidCatalog");

        record Node(Object value, String parent) {}
        record Child(long relevance, Map<String, Object> entry) {}
        var roots = new LinkedHashMap<String, Map<String, Object>>();
        var children = new ArrayList<Child>();
        var pending = new ArrayList<Node>();
        for (Object row : rows) pending.add(new Node(Assistant.objectOrEmpty(row).get("dataproduct"), ""));
        var words = Pattern.compile("[\\p{L}\\p{N}_]+").matcher(fold(term)).results().map(match -> match.group()).toList();
        for (int i = 0; i < pending.size(); i++) {
            var next = pending.get(i);
            var node = Assistant.objectOrEmpty(next.value);
            Object id = node.get("dataproduct_id");
            String title = geoText(node.get("display"), 120);
            if (!Assistant.valid(id, Assistant.ID_SCHEMA) || title.isEmpty()) continue;
            var entry = Assistant.obj("id", id, "title", title);
            if (next.parent.isEmpty()) {
                roots.putIfAbsent((String) id, entry);
            } else {
                String text = fold(title + " " + id);
                long relevance = words.stream().filter(text::contains).count();
                if (relevance > 0) {
                    entry.put("themeTitle", next.parent);
                    children.add(new Child(relevance, entry));
                }
            }
            if (node.get("sublayers") instanceof List<?> layers) {
                for (Object child : layers) pending.add(new Node(child, title));
            }
        }
        children.sort(Comparator.comparingLong(Child::relevance).reversed());
        for (var child : children) roots.putIfAbsent(Assistant.string(child.entry, "id"), child.entry);
        return Assistant.obj("layers", roots.values().stream().limit(Assistant.limit("perSearch")).toList());
    }

    private Map<String, Object> layerDetails(String id) {
        var data = get("/api/dataproduct/v1/weblayers", Map.of("filter", id),
                "catalogUnavailable", "invalidCatalog", "layerUnknown", 9);
        if (!(data.getOrDefault(id, List.of()) instanceof List<?> rows)) throw new Assistant.Failure(502, "invalidCatalog");
        for (Object raw : rows) {
            var node = Assistant.objectOrEmpty(raw);
            if (!id.equals(node.get("name"))) continue;
            String title = geoText(node.get("title"), 120);
            if (title.isEmpty()) throw new Assistant.Failure(502, "invalidCatalog");
            Object sublayers = node.get("sublayers");
            boolean group = sublayers instanceof List<?> list && !list.isEmpty();
            return Assistant.obj("id", id, "qwcLayerName", id, "title", title,
                    "type", group ? "layergroup" : "layer", "description", geoText(node.get("abstract"), 1800));
        }
        throw new Assistant.Failure(404, "layerUnknown");
    }

    private static List<String> addressFields(String value) {
        var match = ADDRESS.matcher(compact(fold(value).replace(',', ' ')));
        if (!match.matches()) return null;
        var result = new ArrayList<String>(4);
        for (int i = 1; i <= 4; i++) result.add(Objects.requireNonNullElse(match.group(i), ""));
        return result;
    }

    private static String joinAddress(List<String> parts) {
        return String.join(" ", parts.stream().filter(part -> !part.isEmpty()).toList());
    }

    private static boolean addressMatches(List<String> requested, List<String> actual) {
        if (actual == null) return false;
        for (int i = 0; i < requested.size(); i++) {
            if (!requested.get(i).isEmpty() && !requested.get(i).equals(actual.get(i))) return false;
        }
        return true;
    }

    private Map<String, Object> resolveLocation(String address) {
        var requested = addressFields(address);
        if (requested == null) throw new Assistant.Failure(400, "invalidLocation");
        var data = get(FTS, Map.of("searchtext", joinAddress(requested), "filter", ADDRESS_LAYER, "limit", 10),
                "locationUnavailable", "invalidLocation", null, 9);
        if (!(data.get("results") instanceof List<?> rows)) throw new Assistant.Failure(502, "invalidLocation");
        var candidates = new LinkedHashMap<List<Object>, Map<String, Object>>();
        for (Object raw : rows) {
            var hit = Assistant.objectOrEmpty(Assistant.objectOrEmpty(raw).get("feature"));
            if (!ADDRESS_LAYER.equals(hit.get("dataproduct_id"))) continue;
            String label = geoText(hit.get("display"), 160);
            if (!addressMatches(requested, addressFields(label.replaceFirst(" \\(Adresse\\)$", "")))) continue;
            var bounds = bbox(hit);
            if (bounds == null || !Assistant.sameNumber(bounds.get(0), bounds.get(2))
                    || !Assistant.sameNumber(bounds.get(1), bounds.get(3))) throw new Assistant.Failure(502, "invalidLocation");
            var key = List.<Object>of(((Number) bounds.get(0)).doubleValue(), ((Number) bounds.get(1)).doubleValue(), label);
            candidates.put(key, Assistant.obj("label", label, "easting", bounds.get(0), "northing", bounds.get(1)));
        }
        String status = candidates.size() == 1 ? "resolved" : candidates.isEmpty() ? "not_found" : "ambiguous";
        return Assistant.obj("status", status, "candidates", candidates.values().stream().limit(5).toList());
    }

    private Map<String, Object> queryFeatures(String id, Object easting, Object northing) {
        var geometry = Assistant.obj("type", "Point", "coordinates", List.of(easting, northing),
                "crs", Assistant.obj("type", "name", "properties", Assistant.obj("name", "EPSG:2056")));
        int limit = 5;
        var data = get("/api/data/v1/" + URLEncoder.encode(id, UTF_8) + "/", Map.of(
                "filter_geom", Assistant.JSON.toJson(geometry), "limit", limit),
                "dataUnavailable", "invalidData", "datasetUnavailable", 9);
        Object matched = data.get("numberMatched");
        if (!"FeatureCollection".equals(data.get("type")) || !(data.get("features") instanceof List<?> raw)
                || !Assistant.isInteger(matched) || ((Number) matched).doubleValue() < raw.size()) {
            throw new Assistant.Failure(502, "invalidData");
        }
        var features = new ArrayList<Map<String, Object>>();
        for (Object row : raw.subList(0, Math.min(raw.size(), limit))) {
            if (!(Assistant.objectOrEmpty(row).get("properties") instanceof Map<?, ?> values)) {
                throw new Assistant.Failure(502, "invalidData");
            }
            var properties = new LinkedHashMap<String, Object>();
            for (var entry : values.entrySet()) {
                String key = geoText(entry.getKey(), 64);
                Object value = entry.getValue();
                if (key.isEmpty()) continue;
                if (value instanceof String) {
                    value = geoText(value, 150);
                    if ("".equals(value)) continue;
                } else if (!(value instanceof Boolean || value instanceof Number)) {
                    continue;
                }
                properties.putIfAbsent(key, value);
                if (properties.size() >= 12) break;
            }
            features.add(Assistant.obj("properties", properties));
        }
        return Assistant.obj("dataset_id", id, "features", features, "numberMatched", matched,
                "numberReturned", features.size(), "partial", ((Number) matched).doubleValue() > features.size());
    }

    static String fold(String text) {
        var result = new StringBuilder(text.length());
        for (int start = 0; start < text.length(); ) {
            int end = text.offsetByCodePoints(start, 1);
            String point = text.substring(start, end);
            result.append(CASE_FOLD.getOrDefault(point, point));
            start = end;
        }
        return result.toString();
    }

    private static String compact(String text) {
        return Assistant.SPACE.matcher(text).replaceAll(" ").strip();
    }

    private static String geoText(Object value, int max) {
        if (!(value instanceof String text)) return "";
        String decoded = ENTITIES.matcher(text).replaceAll(match -> Matcher.quoteReplacement(decodeEntity(match.group(1))));
        String cleaned = compact(LINKS.matcher(decoded.replaceAll("<[^>]*>", " ")).replaceAll(" "));
        return cleaned.substring(0, cleaned.offsetByCodePoints(0, Math.min(Assistant.length(cleaned), max)));
    }

    private static String decodeEntity(String value) {
        if (value.startsWith("#")) {
            String name = value.endsWith(";") ? value.substring(0, value.length() - 1) : value;
            boolean hex = name.startsWith("#x") || name.startsWith("#X");
            try {
                int code = Integer.parseInt(name.substring(hex ? 2 : 1), hex ? 16 : 10);
                if (code == 0 || !Character.isValidCodePoint(code) || code >= 0xd800 && code <= 0xdfff) return "�";
                if (code >= 0x80 && code <= 0x9f) {
                    return C1_ENTITIES.substring(code - 0x80, code - 0x80 + 1);
                }
                if (code < 0x20 && code != 9 && code != 10 && code != 12 && code != 13 || code == 0x7f
                        || code >= 0xfdd0 && code <= 0xfdef || (code & 0xffff) >= 0xfffe) return "";
                return Character.toString(code);
            } catch (NumberFormatException e) {
                return "�";
            }
        }
        for (int end = value.length(); end > 1; end--) {
            if (HTML_ENTITIES.get(value.substring(0, end)) instanceof String decoded) return decoded + value.substring(end);
        }
        return "&" + value;
    }
}
