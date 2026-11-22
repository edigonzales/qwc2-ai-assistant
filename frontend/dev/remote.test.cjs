const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {adaptConfig, rewriteUrls, allowed, createRemoteMiddleware} = require('../server/remote.cjs');
test('anonymous overlay preserves unrelated live settings and input', () => {
    const original = {username: 'private', plugins: {desktop: [{name: 'Map'}, {name: 'Editing'}, {name: 'Authentication'}, {name: 'Kartenkatalog'}]}, assetsPath: '/map/assets'};
    const result = adaptConfig(original);
    assert.equal(original.username, 'private');
    assert.equal(result.username, null);
    assert.deepEqual(result.plugins.desktop.map(p => p.name), ['Map', 'AIChat']);
    assert.equal(result.assetsPath, '/map/assets');
});
test('rewrite live WMTS origin and strip editing metadata', () => {
    assert.deepEqual(
        rewriteUrls({url: 'https://geo.so.ch/api/wmts/{TileRow}', themes: [{url: 'https://other.ch/api/wmts/x', editConfigUrl: 'private'}]}),
        {url: '/api/wmts/{TileRow}', themes: [{url: 'https://other.ch/api/wmts/x'}]}
    );
});
test('catalog stays closed when no startup task is configured', () => {
    const original = {plugins: {}};
    const config = adaptConfig(original);
    assert.equal(Object.hasOwn(config, 'startupTask'), false);
    assert.deepEqual(original, {plugins: {}});
});
test('catalog startup task is removed without changing input', () => {
    const original = {plugins: {}, startupTask: {key: 'Kartenkatalog'}};
    const config = adaptConfig(original);
    assert.equal(Object.hasOwn(config, 'startupTask'), false);
    assert.deepEqual(original.startupTask, {key: 'Kartenkatalog'});
});
test('unrelated startup tasks and their options are preserved', () => {
    const original = {plugins: {}, startupTask: {key: 'LayerTree', mode: 'example'}};
    const config = adaptConfig(original);
    assert.deepEqual(config.startupTask, original.startupTask);
    assert.notEqual(config.startupTask, original.startupTask);
});
test('allowlist blocks writes and authentication', () => {
    assert.ok(allowed(new URL('https://localhost/ows/somap?REQUEST=GetMap'), 'GET'));
    for (const [route, method] of [['/api/data/v1/x', 'POST'], ['/auth/info', 'GET'], ['/ows/somap?request=Transaction', 'GET'], ['/ows/somap?REQUEST=GetMap&request=Transaction', 'GET'], ['/api/dataproduct/v1/private', 'GET']]) assert.equal(allowed(new URL(route, 'https://localhost'), method), false);
});
async function request(t, fetchImpl, route, options = {}, timeout = 10000) {
    const server = http.createServer(createRemoteMiddleware({fetchImpl, timeout, assistantOrigin: 'http://127.0.0.1:19083'}));
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    t.after(() => server.close());
    return fetch(`http://127.0.0.1:${server.address().port}${route}`, options);
}
test('live JSON forwards queries, strips credentials, disables caching', async t => {
    const res = await request(t, async (url, options) => {
        assert.equal(url, 'https://geo.so.ch/map/themes.json?lang=de-CH');
        assert.equal(options.headers.Cookie, undefined);
        return Response.json({themes: {items: [{sublayers: []}]}});
    }, '/map/themes.json?lang=de-CH', {headers: {Cookie: 'secret'}});
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await res.json(), {themes: {items: [{sublayers: []}]}});
});
for (const failure of [async () => new Response('bad', {status: 503}), async () => new Response('not json'), async () => Response.json({wrong: true}), async (_, {signal}) => { await new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason))); }]) {
    test('upstream failure yields 502 without fallback', async t => {
        const res = await request(t, failure, '/map/config.json', {}, 20);
        assert.equal(res.status, 502);
        assert.match(await res.text(), /Live-Anbindung fehlgeschlagen/);
    });
}
test('blocked request never contacts production', async t => {
    const res = await request(t, () => { throw new Error('must not run'); }, '/api/data/v1/x', {method: 'POST'});
    assert.equal(res.status, 403);
});
test('disabled plugins and their nested menu entries are removed', () => {
    const config = adaptConfig({plugins: {desktop: [{name: 'TopBar', cfg: {menuItems: [{key: 'Tools', subitems: [{key: 'Editing'}, {key: 'Measure'}, {key: 'MapExport'}]}, {key: 'LoginMySoCh'}, {key: 'Kartenkatalog'}]}}, {name: 'MapExport'}]}});
    assert.deepEqual(config.plugins.desktop[0].cfg.menuItems, [{key: 'Tools', subitems: [{key: 'Measure'}]}, {key: 'AIChat', icon: 'catalog'}]);
    assert.deepEqual(config.plugins.desktop.map(plugin => plugin.name), ['TopBar', 'AIChat']);
});
test('root redirect keeps query and targets local app', async t => {
    const res = await request(t, () => { throw new Error('must not run'); }, '/?l=example', {redirect: 'manual'});
    assert.equal(res.status, 302);
    assert.equal(res.headers.get('location'), '/map/?l=example');
});
test('the fixed chat and geo POSTs reach FastAPI as JSON without credentials', async t => {
    for (const [route, payload, status, result] of [
        ['chat', {message: 'Hello'}, 429, {detail: 'providerRateLimit'}],
        ['geo', {name: 'search_layers', args: {query: 'Wasser'}}, 200, {layers: []}]
    ]) {
        const body = JSON.stringify(payload);
        const response = await request(t, async (target, options) => {
            assert.equal(target, 'http://127.0.0.1:19083/api/v1/' + route);
            assert.equal(options.method, 'POST');
            assert.equal(options.body.toString(), body);
            assert.equal(options.redirect, 'error');
            assert.deepEqual(options.headers, {'Content-Type': 'application/json', Accept: 'application/json'});
            return Response.json(result, {status});
        }, '/assistant-api/v1/' + route, {
            method: 'POST', body, headers: {'Content-Type': 'application/json', Authorization: 'Bearer fake', Cookie: 'fake'}
        });
        assert.equal(response.status, status);
        assert.deepEqual(await response.json(), result);
    }
});

test('the assistant rejects another origin, method, URL, or media type before any upstream', async t => {
    const path = '/assistant-api/v1/chat';
    const json = {'Content-Type': 'application/json'};
    for (const [route, options, status] of [
        [path, {}, 403],
        [path, {method: 'POST', headers: {...json, Origin: 'https://elsewhere.test'}}, 403],
        [path + '?extra=1', {method: 'POST', headers: json}, 403],
        ['/assistant-api/v1/geo?extra=1', {method: 'POST', headers: json}, 403],
        ['/assistant-api/v1/other', {method: 'POST', headers: json}, 403],
        [path, {method: 'POST', headers: {'Content-Type': 'text/plain'}, body: '{}'}, 415],
        [path, {method: 'POST', headers: json, body: ' '.repeat(131073)}, 413]
    ]) {
        const response = await request(t, () => assert.fail('should not forward'), route, options);
        assert.equal(response.status, status);
    }
});

test('unavailable Python service has a stable response and no upstream detail', async t => {
    const response = await request(t, async () => { throw new Error('private upstream information'); },
        '/assistant-api/v1/chat', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: '{}'});
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), {detail: 'assistantUnavailable'});
});

test('chat registers once for desktop and mobile and stays closed at startup', () => {
    const config = adaptConfig(adaptConfig({
        plugins: {
            desktop: [{name: 'TopBar', cfg: {menuItems: [{key: 'AIChat', icon: 'invalid'}]}}, {name: 'AIChat'}],
            mobile: [{name: 'TopBar'}]
        },
        startupTask: {key: 'AIChat'}
    }));
    for (const device of ['desktop', 'mobile']) {
        const plugins = config.plugins[device];
        assert.equal(plugins.filter(plugin => plugin.name === 'AIChat').length, 1);
        assert.deepEqual(plugins.find(plugin => plugin.name === 'TopBar').cfg.menuItems, [{key: 'AIChat', icon: 'catalog'}]);
    }
    assert.equal(config.startupTask, undefined);
});
