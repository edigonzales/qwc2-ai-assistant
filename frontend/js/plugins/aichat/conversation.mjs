import {createDiagnostics} from './diagnostics.mjs';
import {intentTool, limits, numberedLayer} from './tools.mjs';

const idTools = new Set(['add_layer', 'remove_layer', 'get_layer_details']);
const mapActions = new Set(['add_layer', 'remove_layer', 'zoom_to_lv95', 'zoom_to_result']);

export function errorCode(error, fallback = 'chatFailed') {
    return /^[a-z][A-Za-z0-9]{0,59}$/.test(error?.message || '') ? error.message : fallback;
}

export async function runConversation({message, history, lastResults, pending = [], themeId, runTool, signal,
    diagnostics = createDiagnostics(), onAction = () => {}, fetchImpl = fetch}) {
    let intents;
    const payload = {
        message,
        pending,
        theme_id: themeId,
        history: history.slice(-limits.historyTurns),
        last_results: lastResults.map(({id, title}) => ({id, title})),
        rounds: []
    };
    const callIds = new Set();
    const errors = new Set();
    let searches = 0;

    for (let round = 0; round < limits.rounds; round++) {
        signal?.throwIfAborted();
        const body = JSON.stringify(payload);
        if (new TextEncoder().encode(body).byteLength > limits.requestBytes) throw new Error('requestTooLarge');
        const timeout = AbortSignal.timeout(38000);
        const roundId = round + 1;
        const start = performance.now();
        let response;
        let json;
        try {
            response = await fetchImpl('/assistant-api/v1/chat', {
                method: 'POST',
                headers: {'Content-Type': 'application/json', ...diagnostics.headers(roundId)},
                body,
                redirect: 'error',
                signal: signal ? AbortSignal.any([signal, timeout]) : timeout
            });
            json = await response.json();
        } catch {
            diagnostics.record('chat.request', {round: roundId, status: response?.status, duration_ms: performance.now() - start,
                error: signal?.aborted ? 'aborted' : timeout.aborted ? 'providerTimeout' : 'assistantUnavailable'});
            signal?.throwIfAborted();
            throw new Error(timeout.aborted ? 'providerTimeout' : 'assistantUnavailable');
        }
        diagnostics.record('chat.request', {round: roundId, status: response.status, duration_ms: performance.now() - start,
            history_count: payload.history.length, previous_rounds: payload.rounds.length});
        if (json?.debug) diagnostics.record('chat.backend', {round: roundId, details: json.debug});
        signal?.throwIfAborted();
        if (!response.ok) throw new Error(typeof json?.detail === 'string' ? json.detail : 'chatFailed');
        const assistant = json?.assistant;
        const calls = assistant?.tool_calls;
        if (assistant?.role !== 'assistant' || !Array.isArray(calls) ||
            (assistant.content !== null && (typeof assistant.content !== 'string' || assistant.content.length > limits.replyLength))) {
            throw new Error('invalidModelResponse');
        }
        if (!calls.length) {
            if (!assistant.content?.trim()) throw new Error('invalidModelResponse');
            if (!Array.isArray(json.pending)) throw new Error('invalidModelResponse');
            const confirmedActionsOnly = intents?.length && intents.every(scope => mapActions.has(scope.goal)) &&
                !errors.size && !json.pending.length;
            return {assistant: confirmedActionsOnly ? '' : assistant.content.trim(), errors: [...errors], pending: json.pending};
        }
        searches += calls.filter(call => ['search_layers', 'search_objects'].includes(call?.function?.name)).length;
        if (round + 1 === limits.rounds || callIds.size + calls.length > limits.calls || searches > limits.searches) {
            throw new Error('chatLimit');
        }
        for (const call of calls) {
            if (call?.type !== 'function' || typeof call.id !== 'string' || !call.id || callIds.has(call.id) ||
                typeof call.function?.name !== 'string' || typeof call.function?.arguments !== 'string') {
                throw new Error('invalidModelResponse');
            }
            callIds.add(call.id);
        }

        const results = [];
        for (const call of calls) {
            signal?.throwIfAborted();
            const {name, arguments: raw} = call.function;
            let content;
            const toolStart = performance.now();
            try {
                if (name === intentTool) {
                    if (intents || round !== 0 || results.length) throw new Error('invalidModelResponse');
                    intents = JSON.parse(raw).intents.map(({goal, target, layer_result_number: number}) => {
                        const selected = numberedLayer(lastResults, number);
                        if (number && !selected) throw new Error('unknownResult');
                        const knownIds = selected ? [selected.id] :
                            (target.match(/[A-Za-z0-9_.:-]+/g) || []).map(word => word.replace(/[.:]+$/, ''));
                        return {goal, selected, knownIds: new Set(knownIds)};
                    });
                    results.push({tool_call_id: call.id, content: '{"ok":true,"data":{}}'});
                    continue;
                }
                const {intent, ...args} = JSON.parse(raw);
                const scope = intents?.[intent - 1];
                if (!scope) throw new Error('invalidModelResponse');
                if (scope.selected && name === 'search_layers') throw new Error('numberedSearch');
                if (idTools.has(name) && !scope.knownIds.has(args.id)) throw new Error('unknownCandidate');
                const data = await runTool(name, args, roundId, intent);
                signal?.throwIfAborted();
                if (name === 'search_layers') data.layers.forEach(row => scope.knownIds.add(row.id));
                content = JSON.stringify({ok: true, data});
                if (content.length > limits.resultLength) throw new Error('resultTooLarge');
                if (mapActions.has(name)) onAction(name, data);
                const entries = data?.layers ?? data?.candidates ?? data?.features ?? data?.ids;
                diagnostics.record('tool.result', {round: roundId, tool: name, call_index: results.length + 1,
                    duration_ms: performance.now() - toolStart, result_count: Array.isArray(entries) ? entries.length : undefined,
                    map_confirmed: mapActions.has(name)});
                if (name === 'present_layer_results' && args.search_only === true && calls.length === 1 &&
                    intents.length === 1 && errors.size === 0) {
                    return {assistant: '', errors: [], pending: []};
                }
            } catch (error) {
                signal?.throwIfAborted();
                const code = errorCode(error, 'toolFailed');
                diagnostics.record('tool.result', {round: roundId, tool: name, call_index: results.length + 1,
                    duration_ms: performance.now() - toolStart, error: code});
                errors.add(code);
                content = JSON.stringify({ok: false, error: code});
            }
            results.push({tool_call_id: call.id, content});
        }
        payload.rounds.push({content: assistant.content, calls, results});
    }
    throw new Error('chatLimit');
}
