#!/usr/bin/env node
const {execFileSync, spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const {setTimeout: delay} = require('node:timers/promises');

const repoDir = path.resolve(__dirname, '..');
const composeArgs = ['compose', '--project-directory', repoDir, '-f', path.join(repoDir, 'compose.yaml')];
function compose(...args) {
    return execFileSync('docker', [...composeArgs, ...args], {cwd: repoDir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit']}).trim();
}
function devConfig(config) {
    const source = config.services.kartenkatalog.volumes.find(volume => volume.target === '/catalog/catalog.xtf')?.source;
    if (!source || !fs.statSync(source).isFile()) throw new Error(`XTF file missing or unreadable: ${source}`);
    fs.accessSync(source, fs.constants.R_OK);
    const port = Number(config.services.qwc.ports.find(port => Number(port.target) === 8080)?.published);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('QWC_PORT must be a port between 1 and 65535');
    return {port};
}
async function waitForHealth(origin, healthPath, service) {
    const timeout = 180000;
    const deadline = Date.now() + timeout;
    let reason = 'not ready';
    while (Date.now() < deadline) {
        try {
            const response = await fetch(origin + healthPath, {signal: AbortSignal.timeout(Math.max(1, Math.min(3000, deadline - Date.now()))), redirect: 'error'});
            if (response.ok && (await response.json()).status === 'UP') return;
            reason = `readiness HTTP ${response.status}`;
        } catch (error) { reason = error.message; }
        await delay(Math.min(1000, Math.max(0, deadline - Date.now())));
    }
    throw new Error(`${service} not ready after ${timeout / 1000}s (${reason}). Check: docker compose logs ${service}`);
}
async function checkPort(port) {
    const server = net.createServer();
    await new Promise((resolve, reject) => {
        server.once('error', () => reject(new Error(`Port ${port} is occupied. Stop its process or set QWC_PORT to another port.`)));
        server.listen(port, '127.0.0.1', resolve);
    });
    await new Promise(resolve => server.close(resolve));
}
function startFrontend(port, origin) {
    return new Promise((resolve, reject) => {
        const env = {...process.env, QWC_PORT: String(port), CATALOG_API_ORIGIN: origin};
        for (const key of ['INFOMANIAK_API_TOKEN', 'INFOMANIAK_PRODUCT_ID', 'INFOMANIAK_MODEL']) delete env[key];
        const child = spawn('npm', ['--prefix', 'frontend', 'run', 'start:remote'], {cwd: repoDir, env, stdio: 'inherit', detached: true});
        let interrupted = false;
        const stop = signal => {
            interrupted = true;
            try { process.kill(-child.pid, signal); } catch (error) { if (error.code !== 'ESRCH') throw error; }
        };
        const onInt = () => stop('SIGINT');
        const onTerm = () => stop('SIGTERM');
        process.on('SIGINT', onInt);
        process.on('SIGTERM', onTerm);
        const cleanup = () => {
            process.removeListener('SIGINT', onInt);
            process.removeListener('SIGTERM', onTerm);
        };
        child.once('error', error => { cleanup(); reject(error); });
        child.once('exit', code => { cleanup(); resolve(interrupted ? 0 : (code ?? 1)); });
    });
}
async function main() {
    if (process.argv.includes('--help')) {
        console.log('Usage: npm run dev\nStart backends in Docker and local QWC with hot reload. Ctrl+C stops QWC.');
        return 0;
    }
    if (process.argv.length > 2) throw new Error('Unknown option. Use npm run dev -- --help');
    const [major, minor] = process.versions.node.split('.').map(Number);
    if (major < 22 || (major === 22 && minor < 13)) throw new Error('Node 22.13 or newer required. Run nvm use.');
    if (!fs.existsSync(path.join(repoDir, 'frontend/node_modules/.bin/webpack'))) {
        throw new Error('Frontend dependencies missing. Run npm run setup first.');
    }
    const {port} = devConfig(JSON.parse(compose('config', '--no-env-resolution', '--format', 'json')));
    // Only the QWC container belonging to this Compose project is stopped.
    compose('stop', 'qwc');
    await checkPort(port);
    execFileSync('docker', [...composeArgs, 'up', '-d', '--build', 'kartenkatalog', 'assistant'], {cwd: repoDir, stdio: 'inherit'});
    const serviceOrigin = service => {
        const address = compose('port', service, '8080');
        if (!/^127\.0\.0\.1:\d+$/.test(address)) throw new Error(`Unexpected ${service} binding: ${address}`);
        return 'http://' + address;
    };
    const origin = serviceOrigin('kartenkatalog');
    await Promise.all([
        waitForHealth(origin, '/q/health/ready', 'kartenkatalog'),
        waitForHealth(serviceOrigin('assistant'), '/healthz', 'assistant')
    ]);
    console.log(`\nQWC dev: http://127.0.0.1:${port}/map/\nCtrl+C stops QWC. Stop the backends with docker compose down.`);
    return startFrontend(port, origin);
}
main().then(code => { process.exitCode = code; }).catch(error => { console.error(`Dev start failed: ${error.message}`); process.exitCode = 1; });
