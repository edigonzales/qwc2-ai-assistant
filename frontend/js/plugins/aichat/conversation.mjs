import limits from '../../../../shared/limits.json' with {type: 'json'};
import {createDiagnostics} from './diagnostics.mjs';

export function errorCode(error, fallback = 'chatFailed') {
    return /^[a-z][A-Za-z0-9]{0,59}$/.test(error?.message || '') ? error.message : fallback;
}

export async function runConversation({message, history, lastResults, pending = [], themeId, executeCommand, signal,
    diagnostics = createDiagnostics(), checkTheme = () => {}, fetchImpl = fetch}) {
    signal?.throwIfAborted();
    const payload = {
        message,
        theme_id: themeId,
        history: history.slice(-limits.historyTurns),
        last_results: lastResults.map(({id, title}) => ({id, title})),
        pending
    };
    const timeout = AbortSignal.timeout(70000);
    const start = performance.now();
    let response;
    let json;
    try {
        response = await fetchImpl('/assistant-api/v1/chat', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', ...diagnostics.headers()},
            body: JSON.stringify(payload),
            redirect: 'error',
            signal: signal ? AbortSignal.any([signal, timeout]) : timeout
        });
        json = await response.json();
    } catch {
        signal?.throwIfAborted();
        throw new Error(timeout.aborted ? 'chatTimeout' : 'assistantUnavailable');
    } finally {
        diagnostics.record('chat.request', {status: response?.status, duration_ms: performance.now() - start,
            history_count: payload.history.length});
    }
    if (json?.debug) diagnostics.record('chat.backend', {details: json.debug});
    signal?.throwIfAborted();
    if (!response.ok) throw new Error(typeof json?.detail === 'string' ? json.detail : 'chatFailed');
    if (!Array.isArray(json?.results)) throw new Error('invalidModelResponse');
    checkTheme();

    const results = [];
    for (const record of json.results) {
        const result = {...record, outcome: null};
        if (record.command) {
            const commandStart = performance.now();
            try {
                result.outcome = await executeCommand(record.command);
            } catch (error) {
                signal?.throwIfAborted();
                result.error = errorCode(error, 'mapActionFailed');
            }
            diagnostics.record('map.command', {command: record.command.type, intent: record.intent,
                duration_ms: performance.now() - commandStart, error: result.error});
        }
        signal?.throwIfAborted();
        results.push(result);
    }
    return {results, reply: json.reply, pending: json.pending ?? []};
}
