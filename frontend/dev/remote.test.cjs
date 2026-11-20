const {test} = require('node:test');
const assert = require('node:assert/strict');
const http = require('node:http');
const {adaptConfig, rewriteUrls, allowed, createRemoteMiddleware} = require('../server/remote.cjs');
test('anonymous overlay preserves unrelated live settings and input', () => {
    const original = {username: 'private', plugins: {desktop: [{name: 'Map'}, {name: 'Editing'}, {name: 'Authentication'}, {name: 'Kartenkatalog'}]}, assetsPath: '/map/assets'};
    const result = adaptConfig(original);
    assert.equal(original.username, 'private');
    assert.equal(result.username, null);
    assert.deepEqual(result.plugins.desktop.map(p => p.name), ['Map']);
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
    const server = http.createServer(createRemoteMiddleware({fetchImpl, timeout, catalogOrigin: 'http://127.0.0.1:19083'}));
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
test('nested editing and login menu entries are removed', () => {
    const config = adaptConfig({plugins: {desktop: [{name: 'TopBar', cfg: {menuItems: [{key: 'Tools', subitems: [{key: 'Editing'}, {key: 'Measure'}]}, {key: 'LoginMySoCh'}, {key: 'Kartenkatalog'}]}}]}});
    assert.deepEqual(config.plugins.desktop[0].cfg.menuItems, [{key: 'Tools', subitems: [{key: 'Measure'}]}]);
});
test('root redirect keeps query and targets local app', async t => {
    const res = await request(t, () => { throw new Error('must not run'); }, '/?l=example', {redirect: 'manual'});
    assert.equal(res.status, 302);
    assert.equal(res.headers.get('location'), '/map/?l=example');
});
test('local catalog requests reach only the configured API without cookies', async t => {
    const res = await request(t, async (url, options) => {
        assert.equal(url, 'http://127.0.0.1:19083/api/v1/ebenen?q=wasser+%26+grundwasser&sort=relevance');
        assert.equal(options.headers.Authorization, undefined);
        assert.equal(options.headers.Cookie, undefined);
        return Response.json([{id: "test"}]);
    }, '/catalog-api/v1/ebenen?q=wasser%20%26%20grundwasser&sort=relevance', {headers: {Cookie: 'secret', Authorization: 'Bearer fake'}});
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), [{id: "test"}]);
});
test('catalog proxy denies other routes and writes', async t => {
    for (const [route, method] of [['/catalog-api/v1/ebenen', 'POST'], ['/catalog-api/v1/catalog', 'GET'], ['/catalog-api/actuator/env', 'GET'], ['/catalog-api/v1/ebenen/a%2Fb', 'GET'], ['/catalog-api/v1/ebenen/a/b', 'GET']]) {
        const res = await request(t, () => { throw new Error('must not forward'); }, route, {method});
        assert.equal(res.status, 403);
    }
});
test('unreachable catalog API gives 502 without production fallback', async t => {
    const res = await request(t, async url => {
        assert.equal(url, 'http://127.0.0.1:19083/api/v1/ebenen');
        throw new Error('connection refused');
    }, '/catalog-api/v1/ebenen');
    assert.equal(res.status, 502);
    assert.match(await res.text(), /connection refused/);
});

test('catalog detail HEAD and missing ID keep their real status', async t => {
    const head = await request(t, async (url, options) => {
        assert.equal(url, 'http://127.0.0.1:19083/api/v1/ebenen/ch.so.agi.gemeindegrenzen');
        assert.equal(options.method, 'HEAD');
        return new Response(null);
    }, '/catalog-api/v1/ebenen/ch.so.agi.gemeindegrenzen', {method: 'HEAD'});
    assert.equal(head.status, 200);
    assert.equal(await head.text(), '');
    const absent = await request(t, async () => new Response(null, {status: 404}), '/catalog-api/v1/ebenen/absent');
    assert.equal(absent.status, 404);
});

test('catalog rejects unsupported and overlong queries before fetching', async t => {
    for (const query of ['?q=a&q=b', '?q=' + 'a'.repeat(161), '?other=x', '?sort=title']) {
        const res = await request(t, () => { throw new Error('must not forward'); }, '/catalog-api/v1/ebenen' + query);
        assert.equal(res.status, 400);
    }
});
