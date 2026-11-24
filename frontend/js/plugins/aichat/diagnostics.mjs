export function createDiagnostics() {
    const turnId = crypto.randomUUID();
    let mode;
    try {
        mode = globalThis.sessionStorage?.getItem('qwc-ai-debug');
    } catch {
        // Storage may be disabled by the browser.
    }
    const enabled = mode === 'meta' || mode === 'content';
    return {
        headers() {
            return {
                'X-AI-Turn-Id': turnId,
                ...(enabled ? {'X-AI-Debug': mode} : {})
            };
        },
        record(event, fields) {
            // eslint-disable-next-line no-console -- explicit browser debug output
            if (enabled) console.debug('[QWC KI]', {event, turn_id: turnId, ...fields});
        }
    };
}
