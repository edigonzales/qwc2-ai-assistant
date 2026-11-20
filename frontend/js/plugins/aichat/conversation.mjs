import {limits, numberedLayer} from './tools.mjs';

const idTools = new Set(['add_layer', 'remove_layer', 'get_layer_details']);

export function errorCode(error, fallback = 'chatFailed') {
    return /^[a-z][A-Za-z0-9]{0,59}$/.test(error?.message || '') ? error.message : fallback;
}

export async function runConversation({message, history, lastResults, runTool, signal, fetchImpl = fetch}) {
    const requestedNumbers = [...message.matchAll(/\b(?:nummer|number|nr\.?)\s*(\d+)\b/gi)];
    const selected = requestedNumbers.length === 1 ? numberedLayer(lastResults, Number(requestedNumbers[0][1])) : null;
    if (requestedNumbers.length === 1 && !selected) throw new Error('unknownResult');
    const keepResults = requestedNumbers.length > 0 && !/\b(?:such(?:e|en)?|search|find)\b/i.test(message);
    const knownIds = new Set((selected ? [selected] : lastResults).map(row => row.id));
    for (const word of message.match(/[A-Za-z0-9_.:-]+/g) || []) knownIds.add(word.replace(/[.:]+$/, ''));
    const payload = {
        message,
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
        let response;
        let json;
        try {
            response = await fetchImpl('/assistant-api/v1/chat', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                body,
                redirect: 'error',
                signal: signal ? AbortSignal.any([signal, timeout]) : timeout
            });
            json = await response.json();
        } catch {
            signal?.throwIfAborted();
            throw new Error(timeout.aborted ? 'providerTimeout' : 'assistantUnavailable');
        }
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
            return {assistant: assistant.content.trim(), errors: [...errors]};
        }
        searches += calls.filter(call => call?.function?.name === 'search_layers').length;
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
            try {
                const args = JSON.parse(raw);
                if (keepResults && ['search_layers', 'present_layer_results'].includes(name)) throw new Error('numberedSearch');
                if (idTools.has(name) && !knownIds.has(args?.id)) throw new Error('unknownCandidate');
                const data = await runTool(name, args);
                signal?.throwIfAborted();
                if (name === 'search_layers') data.layers.forEach(row => knownIds.add(row.id));
                if (name === 'get_visible_layers') data.layers.forEach(row => knownIds.add(row.qwcLayerName));
                content = JSON.stringify({ok: true, data});
                if (content.length > limits.resultLength) throw new Error('resultTooLarge');
            } catch (error) {
                signal?.throwIfAborted();
                const code = errorCode(error, 'toolFailed');
                errors.add(code);
                content = JSON.stringify({ok: false, error: code});
            }
            results.push({tool_call_id: call.id, content});
        }
        payload.rounds.push({content: assistant.content, calls, results});
    }
    throw new Error('chatLimit');
}
