import assert from 'node:assert/strict';
import {test} from 'node:test';

import {runConversation} from '../js/plugins/aichat/conversation.mjs';
import {createTurnTools, limits} from '../js/plugins/aichat/tools.mjs';

const call = (id, name, args = {}, intent = 1) => ({
    id, type: 'function', function: {name, arguments: JSON.stringify({intent, ...args})}
});
const declare = (...intents) => ({
    id: 'declare', type: 'function', function: {name: 'declare_intents', arguments: JSON.stringify({intents})}
});
const scope = (goal, target, extra = {}) => ({goal, target, ...extra});
const reply = (content, ...toolCalls) => Response.json({assistant: {role: 'assistant', content, tool_calls: toolCalls}, pending: []});
const previous = [{id: 'layer.one', title: 'Eins'}, {id: 'layer.two', title: 'Zwei'}];

test('numbered follow-up correlates the exact result and rejects the wrong past ID', async() => {
    const steps = [
        reply(null, declare(scope('get_layer_details', 'Ebene 2', {layer_result_number: 2})), call('call_1', 'get_layer_details', {id: 'layer.one'}), call('call_2', 'get_layer_details', {id: 'layer.two'}),
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
                assert.deepEqual(payload.rounds[0].results.slice(1), [
                    {tool_call_id: 'call_1', content: '{"ok":false,"error":"unknownCandidate"}'},
                    {tool_call_id: 'call_2', content: '{"ok":true,"data":{"title":"Zwei"}}'},
                    {tool_call_id: 'call_3', content: '{"ok":false,"error":"numberedSearch"}'}
                ]);
            }
            return steps.shift();
        }
    });
    assert.deepEqual(result, {assistant: 'Zwei', errors: ['unknownCandidate', 'numberedSearch'], pending: []});
});

test('a parcel number reaches the chat with an existing layer result list', async() => {
    const message = 'Gehe zum Grundstück Nr. 101 in Solothurn';
    const result = await runConversation({
        message, history: [], lastResults: previous,
        fetchImpl: (_url, options) => {
            assert.equal(JSON.parse(options.body).message, message);
            return reply('Welche Adresse gehört zum Grundstück?');
        }
    });
    assert.equal(result.assistant, 'Welche Adresse gehört zum Grundstück?');
});

test('a searched ID may be used in its intent after the search', async() => {
    const executed = [];
    const steps = [
        reply(null, declare(scope('add_layer', 'Wasserlayer')), call('a', 'search_layers', {query: 'Wasser'})),
        reply(null, call('b', 'add_layer', {id: 'known'})),
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

test('only a successful search-only presentation ends the turn without another request', async() => {
    const layer = {id: 'ch.so.wasser', title: 'Wasser'};
    for (const [searchOnly, id, requests] of [[true, layer.id, 2], [false, layer.id, 3], [true, 'invented', 3]]) {
        const steps = [
            reply(null, declare(scope('present_layer_results', 'Wasser')), call('search', 'search_layers', {query: 'Wasser'})),
            reply(null, call('list', 'present_layer_results', {ids: [id], search_only: searchOnly})),
            reply('Weitere Antwort')
        ];
        let presented;
        const result = await runConversation({
            message: 'Suche Karten zu Wasser', history: [], lastResults: [],
            fetchImpl: () => steps.shift(),
            runTool: createTurnTools({
                fetchImpl: () => Response.json({layers: [layer]}),
                onResults: rows => { presented = rows; }
            })
        });
        assert.equal(steps.length, 3 - requests);
        assert.deepEqual(presented?.map(row => row.id), id === layer.id ? [id] : undefined);
        assert.deepEqual(result.errors, id === layer.id ? [] : ['unknownCandidate']);
    }
});

test('layer removal uses the catalog ID returned by its own search', async() => {
    const steps = [
        reply(null, declare(scope('remove_layer', 'sichtbaren Layer')),
            call('s', 'search_layers', {query: 'QWC-Titel'})),
        reply(null, call('c', 'remove_layer', {id: 'catalog.id'})),
        reply('Layer entfernt.')
    ];
    const confirmations = [];
    const turn = await runConversation({
        message: 'Entferne den sichtbaren Layer', history: [], lastResults: [],
        onAction: (name, data) => confirmations.push([name, data]),
        fetchImpl: () => steps.shift(),
        runTool: (name, args) => {
            if (name === 'search_layers') return {layers: [{id: 'catalog.id', title: 'QWC-Titel'}]};
            assert.equal(args.id, 'catalog.id');
            return {removed: true};
        }
    });
    assert.deepEqual(confirmations, [['remove_layer', {removed: true}]]);
    assert.deepEqual(turn.errors, []);
});

test('a layer details 404 remains visible alongside a verified removal in the same batch', async() => {
    const steps = [
        reply(null, declare(scope('remove_layer', 'catalog.missing'), scope('remove_layer', 'catalog.known')),
            call('a', 'remove_layer', {id: 'catalog.missing'}), call('b', 'remove_layer', {id: 'catalog.known'}, 2)),
        reply('Beide Layer entfernt.')
    ];
    const confirmations = [];
    const runTool = createTurnTools({
        map: {removeLayer: row => ({id: row.id, title: row.title, removed: true})},
        fetchImpl: (_url, options) => JSON.parse(options.body).args.id === 'catalog.missing' ?
            Response.json({detail: 'layerUnknown'}, {status: 404}) :
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
        fetchImpl: () => ++requests === 1 ?
            reply(null, declare(scope('search_objects', 'Kartenstatus')), call('c1', 'search_objects', {query: 'Kartenstatus'})) :
            reply(null, call('c' + requests, 'search_objects', {query: 'Kartenstatus'})),
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
