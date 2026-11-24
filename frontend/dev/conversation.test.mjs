import assert from 'node:assert/strict';
import {test} from 'node:test';

import {runConversation} from '../js/plugins/aichat/conversation.mjs';

const options = {message: 'Gehe nach Solothurn zur Langendorfstrasse 19b', history: [],
    lastResults: [], themeId: 'default'};
const point = {type: 'zoom_to_point', coordinates: [2605899, 1229278], crs: 'EPSG:2056',
    target_scale: 1000, label: 'Langendorfstrasse 19b, Solothurn'};

test('one chat request carries context and the browser executes verified coordinates locally', async() => {
    const outcome = {center: point.coordinates, scale: 1000};
    const previous = {id: 'layer.one', title: 'Eins', description: 'A longer catalog description'};
    const history = [{user: 'Suche Wasser', assistant: 'Matching layers (1)'}];
    const pending = [{goal: 'zoom_to_result', target: 'Langendorfstrasse 19b', query: 'Langendorfstrasse 19b in Solothurn'}];
    const calls = [];
    const response = await runConversation({
        ...options, history, lastResults: [previous], pending,
        fetchImpl: async(url, request) => {
            calls.push(url);
            assert.equal(request.method, 'POST');
            assert.equal(request.redirect, 'error');
            assert.equal(request.headers['X-AI-Round'], undefined);
            assert.deepEqual(JSON.parse(request.body), {
                message: options.message, history, pending, theme_id: 'default',
                last_results: [{id: previous.id, title: previous.title}]
            });
            return Response.json({results: [{intent: 1, command: point}]});
        },
        executeCommand(command) {
            assert.deepEqual(command, point);
            return outcome;
        }
    });
    assert.deepEqual(calls, ['/assistant-api/v1/chat']);
    assert.deepEqual(response.results, [{intent: 1, command: point, outcome}]);
});

test('local reads wait for preceding commands and a failed command preserves other results', async() => {
    const add = {type: 'add_layer', layer: {id: 'layer.one', title: 'Eins', qwcLayerName: 'layer.one'}};
    const read = {type: 'get_visible_layers'};
    const data = {type: 'object_results', candidates: []};
    const clarification = {message: 'Welchen Ort meinst du?', pending: {goal: 'zoom_to_result', target: 'Olten', source_intent: 3}};
    const error = {intent: 4, error: 'datasetUnavailable'};
    let finishAdding;
    const visibleLayers = [];
    const commands = [];
    const waiting = runConversation({
        ...options,
        fetchImpl: async() => Response.json({
            results: [{intent: 1, command: add}, {intent: 2, command: read},
                {intent: 3, data, clarification, command: point, outcome: {fake: true}}, error],
            pending: [{goal: 'zoom_to_result', target: 'Alte Adresse'}]
        }),
        async executeCommand(command) {
            commands.push(command.type);
            if (command.type === 'add_layer') {
                await new Promise(resolve => { finishAdding = resolve; });
                visibleLayers.push(command.layer);
            } else if (command.type === 'get_visible_layers') {
                return {layers: visibleLayers, total: visibleLayers.length};
            } else {
                throw new Error('mapNotReady');
            }
            return {title: command.layer.title, enabled: 1, total: 1};
        }
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.deepEqual(commands, ['add_layer']);
    finishAdding();
    const response = await waiting;
    assert.deepEqual(commands, ['add_layer', 'get_visible_layers', 'zoom_to_point']);
    assert.equal(response.results[0].outcome.title, 'Eins');
    assert.deepEqual(response.results[1].outcome, {layers: [add.layer], total: 1});
    assert.deepEqual(response.results[2], {intent: 3, data, clarification, command: point, outcome: null, error: 'mapNotReady'});
    assert.deepEqual(response.results[3], {...error, outcome: null});
    assert.deepEqual(response.pending, [{goal: 'zoom_to_result', target: 'Alte Adresse'}]);
});

test('a changed theme prevents late command execution', async() => {
    const theme = {id: 'default'};
    let currentTheme = theme;
    await assert.rejects(runConversation({
        ...options,
        fetchImpl: async() => {
            currentTheme = {...theme};
            return Response.json({results: [{intent: 1, command: point}]});
        },
        checkTheme() {
            if (currentTheme !== theme) throw new Error('themeChanged');
        },
        executeCommand: () => assert.fail('no execution on a changed theme')
    }), {message: 'themeChanged'});
});

test('cancelling with a response in flight prevents late QWC execution', async() => {
    const controller = new AbortController();
    await assert.rejects(runConversation({
        ...options, signal: controller.signal,
        fetchImpl: async() => {
            controller.abort();
            return Response.json({results: [{intent: 1, command: point}]});
        },
        executeCommand: () => assert.fail('no execution after cancellation')
    }), {name: 'AbortError'});
});
