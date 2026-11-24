import assert from 'node:assert/strict';
import {test} from 'node:test';

import {mapLayers} from '../js/plugins/aichat/layers.mjs';
import {numberedLayer, readLayerDetails} from '../js/plugins/aichat/tools.mjs';

test('a direct catalog click reads the selected layer from the fixed endpoint', async() => {
    const layers = [{id: 'layer.one'}, {id: 'layer.two'}];
    const selected = numberedLayer(layers, 2);
    assert.equal(numberedLayer(layers, 3), null);
    const details = {...selected, title: 'Layer two', qwcLayerName: 'layer.two', description: 'Catalog description'};
    let response = Response.json(details);
    const options = {fetchImpl: async(url, request) => {
        assert.equal(url, '/assistant-api/v1/geo');
        assert.equal(request.method, 'POST');
        assert.deepEqual(JSON.parse(request.body), {name: 'get_layer_details', args: {id: selected.id}});
        return response;
    }};
    assert.deepEqual(await readLayerDetails(selected.id, options), details);
    response = Response.json({detail: 'layerUnknown'}, {status: 404});
    await assert.rejects(readLayerDetails(selected.id, options), {message: 'layerUnknown'});
});

test('aborted catalog response decoding preserves the cancellation', async() => {
    const controller = new AbortController();
    await assert.rejects(readLayerDetails('layer.one', {signal: controller.signal, fetchImpl: async() => ({
        ok: true,
        async json() {
            controller.abort();
            throw new Error('not read');
        }
    })}), {name: 'AbortError'});
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
