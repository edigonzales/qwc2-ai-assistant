export function numberedLayer(results, number) {
    return Number.isInteger(number) && number > 0 ? results[number - 1] ?? null : null;
}

export async function readLayerDetails(id, {signal, diagnostics, fetchImpl = fetch} = {}) {
    signal?.throwIfAborted();
    const timeout = AbortSignal.timeout(17000);
    const start = performance.now();
    let response;
    let data;
    try {
        response = await fetchImpl('/assistant-api/v1/geo', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', ...diagnostics?.headers()},
            body: JSON.stringify({name: 'get_layer_details', args: {id}}),
            redirect: 'error',
            signal: signal ? AbortSignal.any([signal, timeout]) : timeout
        });
        data = await response.json();
    } catch {
        signal?.throwIfAborted();
        throw new Error(response && !timeout.aborted ? 'invalidGeoResponse' : 'geoUnavailable');
    } finally {
        diagnostics?.record('geo.request', {tool: 'get_layer_details', status: response?.status, duration_ms: performance.now() - start});
    }
    signal?.throwIfAborted();
    if (!response.ok) throw new Error(typeof data?.detail === 'string' ? data.detail : 'geoUnavailable');
    return data;
}
