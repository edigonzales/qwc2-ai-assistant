const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const {pipeline} = require('node:stream');
const {createRemoteMiddleware} = require('./remote.cjs');

const mime = {'.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.wasm': 'application/wasm', '.pdf': 'application/pdf', '.xml': 'application/xml'};

function createServer({staticRoot = path.join(__dirname, '../prod'), assistantOrigin = process.env.ASSISTANT_API_ORIGIN || 'http://127.0.0.1:8083', fetchImpl = fetch} = {}) {
    const root = fs.realpathSync(staticRoot);
    const remote = createRemoteMiddleware({staticRoot: root, assistantOrigin, fetchImpl});
    return http.createServer(async (req, res) => {
        res.setHeader('X-Content-Type-Options', 'nosniff');
        try {
            await remote(req, res, async () => {
                try {
                    const rawPath = decodeURIComponent(req.url.split('?')[0]);
                    if (rawPath === '/healthz') {
                        let ready = false;
                        try {
                            const response = await fetchImpl(assistantOrigin.replace(/\/$/, '') + '/healthz', {signal: AbortSignal.timeout(3000), redirect: 'error'});
                            ready = response.ok && (await response.json()).status === 'UP';
                        } catch { /* Readiness remains false until the backend is available. */ }
                        res.writeHead(ready ? 200 : 503, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'});
                        return res.end(req.method === 'HEAD' ? undefined : JSON.stringify({status: ready ? 'UP' : 'DOWN'}));
                    }
                    if (rawPath === '/map') {
                        res.writeHead(302, {Location: '/map/' + (new URL(req.url, 'http://localhost')).search}); return res.end();
                    }
                    if (!rawPath.startsWith('/map/')) { res.writeHead(404); return res.end('Nicht gefunden'); }
                    const relative = rawPath.slice('/map/'.length) || 'index.html';
                    if (relative.split('/').some(part => part.startsWith('.'))) { res.writeHead(404); return res.end('Nicht gefunden'); }
                    const file = await fs.promises.realpath(path.join(root, relative));
                    if (!file.startsWith(root + path.sep)) { res.writeHead(403); return res.end('Nicht freigegeben'); }
                    const stat = await fs.promises.stat(file);
                    if (!stat.isFile()) { res.writeHead(404); return res.end('Nicht gefunden'); }
                    res.writeHead(200, {'Content-Type': mime[path.extname(file).toLowerCase()] || 'application/octet-stream', 'Content-Length': stat.size, 'Cache-Control': 'no-cache'});
                    if (req.method === 'HEAD') return res.end();
                    pipeline(fs.createReadStream(file), res, () => {});
                } catch (error) {
                    res.writeHead(['ENOENT', 'ENOTDIR'].includes(error.code) ? 404 : 500); res.end('Datei nicht verfügbar');
                }
            });
        } catch (error) {
            res.writeHead(error instanceof URIError ? 400 : 500); res.end('Anfrage fehlgeschlagen');
        }
    });
}
if (require.main === module) {
    const server = createServer();
    server.listen(Number(process.env.PORT || 8080), '0.0.0.0', () => console.log('QWC HTTP server listening'));
    for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => server.close(() => process.exit(0)));
}
module.exports = {createServer};
