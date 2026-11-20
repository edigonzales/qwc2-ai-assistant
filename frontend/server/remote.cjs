const fs = require('node:fs');
const path = require('node:path');
const {limits} = require('../../shared/tools.json');
const ORIGIN = 'https://geo.so.ch';
const chatPath = '/assistant-api/v1/chat';
const disabled = new Set([
    'NewsPopup', 'Login', 'Logout', 'LoginMySoCh', 'Authentication', 'Autologin', 'LoginUser',
    'Editing', 'CCCInterface', 'Print', 'MapExport', 'LegendPrint', 'LandRegisterExtract',
    'PlotInfoTool', 'Share', 'Bookmark', 'HeightProfile', 'MapInfoTooltip', 'Kartenkatalog', 'AIChat'
]);
const catalogPath = /^\/catalog-api\/v1\/ebenen(?:\/[A-Za-z0-9_.:-]{1,200})?$/;

function adaptConfig(input) {
    const config = structuredClone(input);
    config.plugins ||= {};
    config.username = null;
    config.user_infos = {};
    for (const key of ['authServiceUrl', 'editServiceUrl', 'cccConfigService', 'permalinkServiceUrl']) delete config[key];
    const filterMenu = items => items.filter(item => !disabled.has(item.key || item.task || item.name)).map(item => ({...item, ...(item.subitems ? {subitems: filterMenu(item.subitems)} : {})}));
    for (const entries of Object.values(config.plugins || {})) {
        if (!Array.isArray(entries)) continue;
        for (const plugin of entries) {
            if (plugin.cfg?.menuItems) plugin.cfg.menuItems = filterMenu(plugin.cfg.menuItems);
        }
    }
    for (const key of Object.keys(config.plugins || {})) {
        if (Array.isArray(config.plugins[key])) config.plugins[key] = config.plugins[key].filter(plugin => !disabled.has(plugin.name));
    }
    for (const device of ['desktop', 'mobile']) {
        const plugins = config.plugins[device] ||= [];
        plugins.push({name: 'AIChat'});
        const topbar = plugins.find(plugin => plugin.name === 'TopBar');
        if (topbar) {
            topbar.cfg ||= {};
            topbar.cfg.menuItems ||= [];
            topbar.cfg.menuItems.push({key: 'AIChat', icon: 'catalog'});
        }
    }
    if (disabled.has(config.startupTask?.key)) delete config.startupTask;
    return config;
}
function rewriteUrls(value) {
    if (Array.isArray(value)) return value.map(rewriteUrls);
    if (value && typeof value === 'object') {
        return Object.fromEntries(Object.entries(value).filter(([key]) => key !== 'editConfigUrl').map(([key, entry]) => [key, rewriteUrls(entry)]));
    }
    if (typeof value === 'string' && value.startsWith(ORIGIN + '/api/wmts/')) return value.slice(ORIGIN.length);
    return value;
}
function allowed(url, method) {
    if (!['GET', 'HEAD'].includes(method)) return false;
    if (url.pathname === '/ows/somap') {
        const requests = [...url.searchParams].filter(([key]) => key.toLowerCase() === 'request');
        return requests.length === 1 && ['getmap', 'getfeatureinfo', 'getlegendgraphic', 'getcapabilities'].includes(requests[0][1].toLowerCase());
    }
    return [/^\/api\/wmts\//, /^\/api\/search\/v2\//, /^\/api\/dataproduct\/v1\/weblayers$/, /^\/api\/data\/v1\//, /^\/api\/v1\/(featureinfo|legend)\/somap$/].some(pattern => pattern.test(url.pathname));
}
function chatResponse(res, status, body) {
    res.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'});
    res.end(body);
}

async function proxyChat(req, res, origin, fetchImpl) {
    const error = (status, detail) => chatResponse(res, status, JSON.stringify({detail}));
    const host = req.headers.host || '';
    if (req.url !== chatPath || req.method !== 'POST' || !/^(127\.0\.0\.1|localhost)(:\d+)?$/.test(host) ||
        (req.headers.origin !== undefined && req.headers.origin !== 'http://' + host)) {
        return error(403, 'chatForbidden');
    }
    if (req.headers['content-type']?.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
        return error(415, 'jsonRequired');
    }
    if (Number(req.headers['content-length']) > limits.requestBytes) return error(413, 'requestTooLarge');

    const chunks = [];
    let length = 0;
    const controller = new AbortController();
    res.once('close', () => controller.abort());
    try {
        req.setTimeout(5000, () => req.destroy());
        for await (const chunk of req.iterator({destroyOnReturn: false})) {
            length += chunk.length;
            if (length > limits.requestBytes) {
                res.setHeader('Connection', 'close');
                return error(413, 'requestTooLarge');
            }
            chunks.push(chunk);
        }
        req.setTimeout(0);
        const upstream = await fetchImpl(origin.replace(/\/$/, '') + '/api/v1/chat', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', Accept: 'application/json'},
            body: Buffer.concat(chunks, length),
            signal: AbortSignal.any([controller.signal, AbortSignal.timeout(35000)]),
            redirect: 'error'
        });
        if (!upstream.headers.get('content-type')?.startsWith('application/json')) return error(502, 'assistantUnavailable');
        chatResponse(res, upstream.status, Buffer.from(await upstream.arrayBuffer()));
    } catch (err) {
        if (!controller.signal.aborted) error(err.name === 'TimeoutError' ? 504 : 502, 'assistantUnavailable');
    }
}

function createRemoteMiddleware({
    fetchImpl = fetch, timeout = 10000,
    catalogOrigin = process.env.CATALOG_API_ORIGIN || 'http://127.0.0.1:8084',
    assistantOrigin = process.env.ASSISTANT_API_ORIGIN || 'http://127.0.0.1:8083',
    staticRoot = path.join(__dirname, '../static')
} = {}) {
    return async (req, res, next) => {
        let rawPath;
        let url;
        try {
            rawPath = decodeURIComponent(req.url.split('?')[0]);
            if (!rawPath.startsWith('/') || rawPath.startsWith('//') || rawPath.includes('\\') || rawPath.includes('\0') || rawPath.split('/').some(part => part === '.' || part === '..')) {
                throw new URIError();
            }
            url = new URL(req.url, 'http://localhost');
        } catch {
            res.writeHead(400); return res.end('Ungültiger Pfad');
        }
        if (rawPath.startsWith('/assistant-api/')) return proxyChat(req, res, assistantOrigin, fetchImpl);
        const catalogRequest = rawPath.startsWith('/catalog-api/');
        const config = rawPath.toLowerCase() === '/map/config.json';
        const themes = rawPath.toLowerCase() === '/map/themes.json';
        const definitions = url.pathname === '/api/dataproduct/v1/weblayers';
        const image = /^\/map\/(img|assets\/img)\//.test(url.pathname);
        const service = /^\/(api|ows|wms|auth|mysochauth|elevation)(\/|$)/.test(url.pathname) || url.pathname === '/map/editConfig.json';
        if (!['GET', 'HEAD'].includes(req.method) || (catalogRequest && !catalogPath.test(rawPath)) || (service && !allowed(url, req.method))) {
            res.writeHead(403, {'Content-Type': 'text/plain; charset=utf-8'}); return res.end('Im anonymen Dev-Modus ist dieser Zugriff nicht freigegeben.');
        }
        if (url.pathname === '/') { res.writeHead(302, {Location: '/map/' + url.search}); return res.end(); }
        if (!(catalogRequest || config || themes || image || service)) return next();
        if (image && fs.existsSync(path.join(staticRoot, url.pathname.slice('/map/'.length)))) return next();
        if (catalogRequest) {
            const listing = rawPath === '/catalog-api/v1/ebenen';
            const keys = [...url.searchParams.keys()];
            if (new Set(keys).size !== keys.length || keys.some(key => !listing || !['q', 'sort'].includes(key)) ||
                (url.searchParams.get('q') || '').length > 160 || ![null, 'relevance'].includes(url.searchParams.get('sort'))) {
                res.writeHead(400); return res.end('Ungültige Katalogabfrage');
            }
        }
        try {
            const lang = url.searchParams.get('lang');
            const liveQuery = config || themes ? (/^[a-z]{2}(?:-[A-Z]{2})?$/.test(lang ?? '') ? '?lang=' + lang : '') : url.search;
            const target = catalogRequest ? catalogOrigin.replace(/\/$/, '') + '/api' + rawPath.slice('/catalog-api'.length) +
                (url.search ? '?' + url.searchParams.toString() : '') : ORIGIN + (config || themes ? rawPath.toLowerCase() : url.pathname) + liveQuery;
            const upstream = await fetchImpl(target, {
                method: catalogRequest ? req.method : 'GET', signal: AbortSignal.timeout(timeout), redirect: 'error',
                headers: {Accept: catalogRequest ? 'application/json' : req.headers.accept || '*/*'}
            });
            if (catalogRequest) {
                if (upstream.status === 404) {
                    res.writeHead(404, {'Cache-Control': 'no-store'}); return res.end(req.method === 'HEAD' ? undefined : 'Kartenebene nicht gefunden');
                }
                if (!upstream.ok || (req.method !== 'HEAD' && !upstream.headers.get('content-type')?.startsWith('application/json'))) {
                    throw new Error(`Ungültige Katalogantwort (HTTP ${upstream.status})`);
                }
                const body = req.method === 'HEAD' ? undefined : Buffer.from(await upstream.arrayBuffer());
                res.writeHead(200, {'Content-Type': 'application/json', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff'});
                return res.end(body);
            }
            if (!upstream.ok) throw new Error(`Live-System antwortet mit HTTP ${upstream.status}`);
            let body;
            if (config || themes || definitions) {
                const json = await upstream.json();
                if (!json || typeof json !== 'object' || Array.isArray(json) || (config && !json.plugins) || (themes && !json.themes)) throw new Error('Ungültige Konfiguration vom Live-System');
                body = Buffer.from(JSON.stringify(rewriteUrls(config ? adaptConfig(json) : json)));
            } else body = Buffer.from(await upstream.arrayBuffer());
            res.writeHead(200, {'Content-Type': config || themes || definitions ? 'application/json' : upstream.headers.get('content-type') || 'application/octet-stream', 'Cache-Control': 'no-store'});
            res.end(req.method === 'HEAD' ? undefined : body);
        } catch (error) {
            res.writeHead(502, {'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store'});
            res.end(`Live-Anbindung fehlgeschlagen (${url.pathname}): ${error.message}`);
        }
    };
}
module.exports = {adaptConfig, rewriteUrls, allowed, createRemoteMiddleware};
