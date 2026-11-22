import definition from '../../../../shared/tools.json' with {type: 'json'};

export const {limits, locationOrdinalPrefix, ordinalPattern} = definition;
const schemas = new Map(definition.tools.map(({function: tool}) => [tool.name, tool.parameters]));
const idSchema = schemas.get('get_layer_details').properties.id;

function valid(value, schema) {
    switch (schema.type) {
    case 'object':
        return value !== null && typeof value === 'object' && !Array.isArray(value) &&
            schema.required.every(key => Object.hasOwn(value, key)) &&
            Object.keys(value).every(key => Object.hasOwn(schema.properties, key) && valid(value[key], schema.properties[key]));
    case 'string':
        return typeof value === 'string' && value.length >= schema.minLength && value.length <= schema.maxLength &&
            new RegExp(schema.pattern, 'u').test(value);
    case 'number':
        return Number.isFinite(value) && value >= schema.minimum && value <= schema.maximum;
    case 'array':
        return Array.isArray(value) && value.length <= schema.maxItems && value.every(item => valid(item, schema.items));
    default:
        return false;
    }
}

const text = (value, length) => typeof value === 'string' ? value.replace(/\s+/g, ' ').trim().slice(0, length) : '';

function layerSummary(row, descriptionLength) {
    if (!valid(row?.id, idSchema) || !text(row.title, 120)) return null;
    return {
        id: row.id,
        title: text(row.title, 120),
        themeTitle: text(row.themeTitle, 100),
        description: text(row.description, descriptionLength)
    };
}

export function numberedLayer(results, number) {
    return Number.isInteger(number) && number > 0 ? results[number - 1] ?? null : null;
}

export function createTurnTools({map, fetchImpl = fetch, signal, onResults = () => {}}) {
    const candidates = new Map();
    let calls = 0;
    let searches = 0;

    async function readGeo(name, args) {
        const timeout = AbortSignal.timeout(17000);
        let response;
        let data;
        try {
            response = await fetchImpl('/assistant-api/v1/geo', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({name, args}),
                redirect: 'error',
                signal: signal ? AbortSignal.any([signal, timeout]) : timeout
            });
            data = await response.json();
        } catch {
            signal?.throwIfAborted();
            throw new Error(response && !timeout.aborted ? 'invalidGeoResponse' : 'geoUnavailable');
        }
        signal?.throwIfAborted();
        if (!response.ok) throw new Error(typeof data?.detail === 'string' ? data.detail : 'geoUnavailable');
        return data;
    }

    async function search(query) {
        if (++searches > limits.searches) throw new Error('searchLimit');
        const {layers: rows} = await readGeo('search_layers', {query: query.trim()}) ?? {};
        if (!Array.isArray(rows)) throw new Error('invalidCatalog');
        const found = [];
        for (const row of rows.slice(0, limits.perSearch)) {
            if (candidates.size >= limits.candidates) break;
            const layer = layerSummary(row, 180);
            if (layer && !candidates.has(layer.id)) {
                candidates.set(layer.id, layer);
                found.push(layer);
            }
        }
        return {layers: found};
    }

    async function details(id) {
        const row = await readGeo('get_layer_details', {id});
        const layer = layerSummary(row, 1800);
        if (layer?.id !== id || !valid(row.qwcLayerName, idSchema)) throw new Error('invalidCatalog');
        return {...layer, qwcLayerName: row.qwcLayerName, type: text(row.type, 40)};
    }

    function present(ids) {
        const results = [...new Set(ids)].map(id => candidates.get(id));
        if (results.some(result => !result)) throw new Error('unknownCandidate');
        onResults(results);
        return {ids: results.map(result => result.id)};
    }

    return async function run(name, args) {
        signal?.throwIfAborted();
        const schema = schemas.get(name);
        if (!schema || !valid(args, schema)) throw new Error('invalidTool');
        if (++calls > limits.calls) throw new Error('toolLimit');
        switch (name) {
        case 'get_map_state': return map.getState();
        case 'get_visible_layers': return map.getVisibleLayers();
        case 'add_layer': return map.addLayer(await details(args.id), signal);
        case 'remove_layer': return map.removeLayer(await details(args.id), signal);
        case 'zoom_to_lv95': return map.zoom(args);
        case 'search_layers': return search(args.query);
        case 'get_layer_details': return details(args.id);
        case 'resolve_location':
        case 'query_features': return readGeo(name, args);
        case 'present_layer_results': return present(args.ids);
        default: throw new Error('invalidTool');
        }
    };
}
