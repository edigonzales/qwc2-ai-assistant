import assert from 'node:assert/strict';
import {test} from 'node:test';

import {runConversation} from '../js/plugins/aichat/conversation.mjs';
import {createTurnTools, limits} from '../js/plugins/aichat/tools.mjs';

const call = (id, name, args = {}) => ({
    id, type: 'function', function: {name, arguments: JSON.stringify(args)}
});
const reply = (content, ...toolCalls) => Response.json({assistant: {role: 'assistant', content, tool_calls: toolCalls}});
const previous = [{id: 'layer.one', title: 'Eins'}, {id: 'layer.two', title: 'Zwei'}];

test('numbered follow-up correlates the exact result and rejects the wrong past ID', async() => {
    const steps = [
        reply(null, call('call_1', 'add_layer', {id: 'layer.one'}), call('call_2', 'get_layer_details', {id: 'layer.two'}),
            call('call_3', 'search_layers', {query: 'Zwei'})),
        reply('Zwei')
    ];
    const result = await runConversation({
        message: 'Mehr Informationen zu Ebene 2', history: [], lastResults: previous,
        runTool: (name, args) => {
            assert.equal(name, 'get_layer_details');
            assert.equal(args.id, 'layer.two');
            return {title: 'Zwei'};
        },
        fetchImpl: (url, options) => {
            assert.equal(url, '/assistant-api/v1/chat');
            const payload = JSON.parse(options.body);
            assert.deepEqual(payload.last_results, previous);
            if (steps.length === 1) {
                assert.deepEqual(payload.rounds[0].results, [
                    {tool_call_id: 'call_1', content: '{"ok":false,"error":"unknownCandidate"}'},
                    {tool_call_id: 'call_2', content: '{"ok":true,"data":{"title":"Zwei"}}'},
                    {tool_call_id: 'call_3', content: '{"ok":false,"error":"numberedSearch"}'}
                ]);
            }
            return steps.shift();
        }
    });
    assert.deepEqual(result, {assistant: 'Zwei', errors: ['unknownCandidate', 'numberedSearch']});
    await assert.rejects(runConversation({message: 'Zeig Nummer 3', history: [], lastResults: previous}), {message: 'unknownResult'});
});

test('a searched ID may be used in the same response batch after its search', async() => {
    const executed = [];
    const steps = [
        reply(null, call('a', 'search_layers', {query: 'Wasser'}), call('b', 'add_layer', {id: 'known'})),
        reply('Layer angezeigt.')
    ];
    await runConversation({
        message: 'Zeig den Wasserlayer', history: [], lastResults: [],
        fetchImpl: () => steps.shift(),
        runTool: (name, args) => {
            executed.push([name, args]);
            return name === 'search_layers' ? {layers: [{id: 'known'}]} : {id: 'known'};
        }
    });
    assert.deepEqual(executed, [['search_layers', {query: 'Wasser'}], ['add_layer', {id: 'known'}]]);
});

test('a visible QWC name cannot substitute for a catalog ID', async() => {
    const steps = [
        reply(null, call('v', 'get_visible_layers'), call('r', 'remove_layer', {id: 'qwc.alias'})),
        reply(null, call('s', 'search_layers', {query: 'QWC-Titel'})),
        reply(null, call('c', 'remove_layer', {id: 'catalog.id'})),
        reply('Layer entfernt.')
    ];
    const executed = [];
    const confirmations = [];
    const turn = await runConversation({
        message: 'Entferne den sichtbaren Layer', history: [], lastResults: [],
        onAction: (name, data) => confirmations.push([name, data]),
        fetchImpl: (_url, options) => {
            if (steps.length === 3) {
                assert.deepEqual(JSON.parse(options.body).rounds[0].results[1], {
                    tool_call_id: 'r', content: '{"ok":false,"error":"unknownCandidate"}'
                });
            }
            return steps.shift();
        },
        runTool: (name, args) => {
            if (name === 'get_visible_layers') return {layers: [{qwcLayerName: 'qwc.alias', title: 'QWC-Titel'}]};
            if (name === 'search_layers') return {layers: [{id: 'catalog.id', title: 'QWC-Titel'}]};
            executed.push(args.id);
            return {removed: true};
        }
    });
    assert.deepEqual(executed, ['catalog.id']);
    assert.deepEqual(confirmations, [['remove_layer', {removed: true}]]);
    assert.deepEqual(turn.errors, ['unknownCandidate']);
});

test('a catalog 404 remains visible alongside a verified removal in the same batch', async() => {
    const steps = [
        reply(null, call('a', 'remove_layer', {id: 'catalog.missing'}), call('b', 'remove_layer', {id: 'catalog.known'})),
        reply('Beide Layer entfernt.')
    ];
    const confirmations = [];
    const runTool = createTurnTools({
        map: {removeLayer: row => ({id: row.id, title: row.title, removed: true})},
        fetchImpl: url => url.endsWith('/catalog.missing') ? new Response(null, {status: 404}) :
            Response.json({id: 'catalog.known', qwcLayerName: 'known', title: 'Bekannter Layer'})
    });
    const turn = await runConversation({
        message: 'Entferne die Layer catalog.missing und catalog.known.', history: [], lastResults: [],
        fetchImpl: () => steps.shift(), runTool, onAction: (name, data) => confirmations.push([name, data])
    });
    assert.deepEqual(confirmations, [['remove_layer', {id: 'catalog.known', title: 'Bekannter Layer', removed: true}]]);
    assert.deepEqual(turn.errors, ['layerUnknown']);
});

test('the browser stops before executing a fourth-round or excess batch', async() => {
    let requests = 0;
    let executed = 0;
    await assert.rejects(runConversation({
        message: 'Kartenstatus', history: [], lastResults: [],
        fetchImpl: () => reply(null, call('c' + ++requests, 'get_map_state')),
        runTool: () => { ++executed; return {}; }
    }), {message: 'chatLimit'});
    assert.equal(requests, limits.rounds);
    assert.equal(executed, limits.rounds - 1);

    const calls = Array.from({length: limits.calls + 1}, (_, i) => call('c' + i, 'get_map_state'));
    await assert.rejects(runConversation({
        message: 'Kartenstatus', history: [], lastResults: [],
        fetchImpl: () => reply(null, ...calls),
        runTool: () => assert.fail('oversized batch ran')
    }), {message: 'chatLimit'});
});

test('cancelling with a response in flight prevents late QWC execution', async() => {
    const controller = new AbortController();
    let respond;
    const chat = runConversation({
        message: 'Zeig Nummer 1', history: [], lastResults: previous, signal: controller.signal,
        fetchImpl: () => new Promise(resolve => { respond = resolve; }),
        runTool: () => assert.fail('old tool ran')
    });
    controller.abort();
    respond(reply(null, call('c', 'add_layer', {id: 'layer.one'})));
    await assert.rejects(chat, {name: 'AbortError'});
});
