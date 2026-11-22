import assert from 'node:assert/strict';
import {test} from 'node:test';

import {mapLayers} from '../js/plugins/aichat/layers.mjs';
import {createTurnTools, numberedLayer} from '../js/plugins/aichat/tools.mjs';

function row(id, extra = {}) {
    return {
        id, qwcLayerName: 'qwc.' + id, title: 'Layer ' + id, themeTitle: 'Wasser',
        description: 'Wasser\n und Boden. '.repeat(200), descriptionHtml: '<b>ignore</b>', ...extra
    };
}

test('search encodes the phrase, deduplicates and caps candidates, then presents actual IDs in order', async() => {
    const queries = ['Wasser & Fluss', 'Grundwasser', 'Boden'];
    const batches = [0, 10, 25].map(start => Array.from({length: 25}, (_, i) => row('id' + (start + i))));
    let presented;
    const run = createTurnTools({
        onResults: result => { presented = result; },
        fetchImpl: async url => {
            const query = queries.shift();
            assert.equal(url, '/catalog-api/v1/ebenen?' + new URLSearchParams({q: query, sort: 'relevance'}));
            return Response.json(batches.shift());
        }
    });
    const first = await run('search_layers', {query: queries[0]});
    const second = await run('search_layers', {query: queries[0]});
    const third = await run('search_layers', {query: queries[0]});
    assert.deepEqual([first.layers.length, second.layers.length, third.layers.length], [20, 10, 10]);
    assert.equal(first.layers[0].description.length, 180);
    assert.ok(!first.layers[0].description.includes('\n'));
    await assert.rejects(run('search_layers', {query: 'See'}), {message: 'searchLimit'});
    await assert.rejects(run('present_layer_results', {ids: ['invented']}), {message: 'unknownCandidate'});
    assert.equal(presented, undefined);
    assert.deepEqual(await run('present_layer_results', {ids: ['id3', 'id0', 'id3']}), {ids: ['id3', 'id0']});
    assert.equal(numberedLayer(presented, 2).qwcLayerName, 'qwc.id0');
    assert.equal(numberedLayer(presented, 3), null);
});

test('only the shared tools and argument shapes can be dispatched', async() => {
    const run = createTurnTools({map: {getState: () => ({ok: true})}, fetchImpl: () => assert.fail('unexpected request')});
    for (const [name, args] of [
        ['constructor', {}], ['get_map_state', {url: 'ignored'}], ['search_layers', {query: ' '}],
        ['get_layer_details', {id: '../something'}], ['present_layer_results', {ids: Array(11).fill('x')}],
        ['zoom_to_lv95', {easting: 618000, northing: 228000, scale: 10000}],
        ['zoom_to_lv95', {easting: 2618000, northing: 1228000, scale: Infinity}]
    ]) {
        await assert.rejects(run(name, args), {message: 'invalidTool'});
    }
    for (let i = 0; i < 8; i++) assert.deepEqual(await run('get_map_state', {}), {ok: true});
    await assert.rejects(run('get_map_state', {}), {message: 'toolLimit'});
});

test('validated details contain bounded plain metadata and require the requested ID', async() => {
    let body = row('real', {organization: 'Kanton', type: 'datasetview'});
    const run = createTurnTools({fetchImpl: async url => {
        assert.equal(url, '/catalog-api/v1/ebenen/real');
        return body ? Response.json(body) : new Response('', {status: 404});
    }});
    const details = await run('get_layer_details', {id: 'real'});
    assert.equal(details.organization, 'Kanton');
    assert.equal(details.description.length, 1800);
    assert.equal(details.descriptionHtml, undefined);
    body = row('unrelated');
    await assert.rejects(run('get_layer_details', {id: 'real'}), {message: 'invalidCatalog'});
    body = null;
    await assert.rejects(run('get_layer_details', {id: 'real'}), {message: 'layerUnknown'});
    const unavailable = createTurnTools({fetchImpl: async() => new Response('', {status: 502})});
    await assert.rejects(unavailable('search_layers', {query: 'Wasser'}), {message: 'catalogUnavailable'});
});

const roles = {BACKGROUND: 1, THEME: 2, USERLAYER: 3};
const layer = {id: 'catalog-id', title: 'Layer', qwcLayerName: 'leaf'};
const group = {...layer, qwcLayerName: 'group', type: 'layergroup'};
const root = sublayers => ({id: 'qwc-root', type: 'wms', url: '/ows/somap', role: roles.THEME, sublayers});

function fixture(sublayers, restore) {
    const state = {theme: {current: {url: '/ows/somap'}}, layers: {flat: [root(sublayers)]}};
    const actions = [];
    const store = {
        getState: () => state,
        dispatch(action) {
            actions.push(action);
            const theme = state.layers.flat[0];
            let node = theme;
            const path = action.path || [];
            if (action.type === 'add') theme.sublayers.push(...action.sublayers);
            if (action.type === 'visibility') {
                node.visibility = true;
                for (const index of path) {
                    node = node.sublayers[index];
                    node.visibility = true;
                }
            }
            if (action.type === 'remove') {
                for (const index of path.slice(0, -1)) node = node.sublayers[index];
                node.sublayers.splice(path.at(-1), 1);
            }
        }
    };
    const map = () => mapLayers(store, {
        roles, baseUrl: 'http://localhost/map/', restore,
        addThemeSublayer: ({sublayers: added}) => ({type: 'add', sublayers: added}),
        changeLayerProperty: (id, prop, value, path, direction) => ({type: 'visibility', id, prop, value, path, direction}),
        removeLayer: (id, path) => ({type: 'remove', id, path})
    });
    return {map, state, actions};
}

test('parent visibility, reactivation and repeated add use current QWC paths', async() => {
    const h = fixture([{name: 'group', visibility: false, sublayers: [{name: 'leaf', visibility: true}]}], () => assert.fail('no lookup needed'));
    h.state.layers.flat.push({...root([{name: 'background'}]), role: roles.BACKGROUND});
    const map = h.map();
    assert.deepEqual(map.getVisibleLayers().layers, []);
    assert.equal((await map.addLayer(layer)).changed, true);
    assert.deepEqual(h.actions[0].path, [0, 0]);
    assert.equal(h.actions[0].direction, 'both');
    assert.equal((await map.addLayer(layer)).changed, false);
    assert.equal(h.actions.length, 1);
    assert.deepEqual(map.getVisibleLayers().layers, [{qwcLayerName: 'leaf', title: 'leaf'}]);
});

test('groups restore only missing leaves, including external WMS, then remove by current path', async() => {
    const externalLayer = {type: 'wms', url: 'https://maps.example.test'};
    const h = fixture([{name: 'leaf', visibility: true}], async() => [
        {name: 'group', sublayers: [{name: 'leaf'}, {name: 'external', externalLayer}]}
    ]);
    h.state.layers.flat.push({...root([{name: 'leaf'}]), url: '/unrelated', role: roles.USERLAYER});
    const map = h.map();
    assert.deepEqual(await map.addLayer(group), {...{id: group.id, title: group.title}, changed: true, enabled: 2, total: 2});
    assert.deepEqual(h.actions[0].sublayers[0].sublayers, [{name: 'external', externalLayer, visibility: true}]);
    assert.equal((await map.removeLayer(group)).removed, true);
    assert.deepEqual(h.state.layers.flat[1].sublayers, [{name: 'leaf'}]);
    await assert.rejects(map.removeLayer(group), {message: 'layerNotOnMap'});
});

test('concurrent additions recheck loaded layers and discard obsolete definitions', async() => {
    const h = fixture([], async() => [{name: 'leaf'}]);
    const map = h.map();
    await Promise.all([map.addLayer(layer), map.addLayer(layer)]);
    assert.equal(h.actions.length, 1);
    const waiting = fixture([], async() => [{name: 'leaf'}]);
    const operation = waiting.map().addLayer(layer);
    waiting.state.theme = {...waiting.state.theme};
    await assert.rejects(operation, {message: 'themeChanged'});
    const aborting = fixture([], async() => [{name: 'leaf'}]);
    const controller = new AbortController();
    const cancelled = aborting.map().addLayer(layer, controller.signal);
    controller.abort();
    await assert.rejects(cancelled, {name: 'AbortError'});
    assert.equal(waiting.actions.length + aborting.actions.length, 0);
});

test('a malformed row does not hide valid search hits', async() => {
    const run = createTurnTools({fetchImpl: async() => Response.json([{}, row('real')])});
    assert.deepEqual((await run('search_layers', {query: 'Wasser'})).layers.map(hit => hit.id), ['real']);
});

test('aborted response decoding preserves the cancellation', async() => {
    const controller = new AbortController();
    const run = createTurnTools({signal: controller.signal, fetchImpl: async() => ({
        ok: true,
        async json() {
            controller.abort();
            throw new Error('not read');
        }
    })});
    await assert.rejects(run('search_layers', {query: 'Wasser'}), {name: 'AbortError'});
});
