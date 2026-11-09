import http from 'node:http';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { isIP } from 'node:net';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { LumaConnection, LumaProtocolError } from './luma-protocol.mjs';
import { discoverLuma } from './luma-discovery.mjs';

const ACTIONS = new Set(['discover', 'connect', 'disconnect', 'status', 'key', 'apps', 'launch-kodi', 'launch-app', 'text']);
const LOST_CONNECTION = new Set(['CONNECTION_FAILED', 'TIMEOUT', 'INVALID_RESPONSE', 'NOT_CONNECTED', 'PAIRING_UNKNOWN', 'PAIRING_REQUIRED']);
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const PNG_DATA = /^data:image\/png;base64,([A-Za-z0-9+/]+={0,2})$/u;
const MAX_APPS = 200;
const MAX_ICON_DATA = 192 * 1024;
const MAX_TOTAL_ICON_BYTES = 2 * 1024 * 1024;
const RECEIVER_PORT = 4191;
function privateLumaHost(host) {
  const octets = typeof host === 'string' ? host.split('.').map(Number) : [];
  return isIP(typeof host === 'string' ? host : '') === 4 && (octets[0] === 10
    || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
    || (octets[0] === 192 && octets[1] === 168));
}
class RequestError extends Error {
  constructor(message, status = 400, disconnected = false) { super(message); this.status = status; this.disconnected = disconnected; }
}

export class ReceiverAppsError extends Error {
  constructor(code = 'UNAVAILABLE') { super('Receiver launcher API is unavailable.'); this.name = 'ReceiverAppsError'; this.code = code; }
}

function cleanReceiverIcon(value) {
  if (typeof value !== 'string' || value.length > MAX_ICON_DATA) return undefined;
  const match = value.match(PNG_DATA);
  if (!match || match[1].length % 4 === 1) return undefined;
  const padding = match[1].endsWith('==') ? 2 : match[1].endsWith('=') ? 1 : 0;
  const bytes = Math.floor(match[1].length * 3 / 4) - padding;
  if (bytes < 1 || bytes > 128 * 1024) return undefined;
  return { value, bytes };
}

/** Paired receiver launcher client. The bearer token stays in this Node process;
 * only sanitized app metadata is returned to the localhost browser. */
export class ReceiverAppsClient {
  constructor({ host, token, fetchImpl = fetch, timeoutMs = 3500 } = {}) {
    if (!privateLumaHost(host) || typeof token !== 'string' || token.length < 16 || token.length > 512 || /[\u0000-\u0020\u007f]/u.test(token)
      || typeof fetchImpl !== 'function' || !Number.isInteger(timeoutMs) || timeoutMs < 250 || timeoutMs > 10000) throw new ReceiverAppsError('INVALID_CONFIG');
    this.host = host;
    this.token = token;
    this.fetchImpl = fetchImpl;
    this.timeoutMs = timeoutMs;
  }

  async _call(method, params = {}) {
    let response, reply;
    try {
      response = await this.fetchImpl(`http://${this.host}:${RECEIVER_PORT}/api/v1/${method}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.token}` },
        body: JSON.stringify(params),
        signal: AbortSignal.timeout(this.timeoutMs),
      });
      reply = await response.json();
    } catch { throw new ReceiverAppsError(); }
    if (!response.ok || !reply || reply.ok !== true || !reply.result || typeof reply.result !== 'object') {
      const code = typeof reply?.error?.code === 'string' ? reply.error.code : 'UNAVAILABLE';
      throw new ReceiverAppsError(code);
    }
    return reply.result;
  }

  async getApps() {
    const result = await this._call('apps.list');
    if (!Array.isArray(result.apps) || result.apps.length > MAX_APPS) throw new ReceiverAppsError('INVALID_RESPONSE');
    const packages = new Set();
    let iconBytes = 0;
    return result.apps.map(app => {
      if (!app || typeof app.packageName !== 'string' || !PACKAGE_NAME.test(app.packageName) || packages.has(app.packageName)
        || typeof app.label !== 'string' || !app.label.trim() || app.label.length > 200 || /[\u0000-\u001f\u007f]/u.test(app.label)) {
        throw new ReceiverAppsError('INVALID_RESPONSE');
      }
      packages.add(app.packageName);
      const icon = cleanReceiverIcon(app.icon);
      const keepIcon = icon && iconBytes + icon.bytes <= MAX_TOTAL_ICON_BYTES;
      if (keepIcon) iconBytes += icon.bytes;
      return { packageName: app.packageName, label: app.label.trim(), ...(keepIcon ? { icon: icon.value } : {}) };
    });
  }

  async launchApp(packageName) {
    if (typeof packageName !== 'string' || !PACKAGE_NAME.test(packageName)) throw new ReceiverAppsError('INVALID_INPUT');
    await this._call('apps.launch', { packageName });
    return { accepted: true };
  }
}

export function validateLumaHost(host) {
  if (!privateLumaHost(host)) {
    throw new RequestError('Enter the projector’s private IPv4 address.');
  }
  return host;
}

export function createLumaControllerServer({
  connectionFactory = host => new LumaConnection({ host }),
  discover = discoverLuma,
  receiverAppsFactory = () => null,
} = {}) {
  let connection;
  let receiverApps;
  let connectedHost;
  let pending = 0;
  let queue = Promise.resolve();
  async function appInventory() {
    if (receiverApps) {
      try { return await receiverApps.getApps(); }
      catch { /* The receiver may be older/offline; EShare remains the safe fallback. */ }
    }
    return connection.getApps();
  }
  async function launchApp(packageName) {
    if (receiverApps) {
      try { await receiverApps.launchApp(packageName); return; }
      catch (failure) {
        if (failure instanceof ReceiverAppsError && failure.code === 'APP_NOT_AVAILABLE') throw new LumaProtocolError('APP_NOT_AVAILABLE');
        if (failure instanceof ReceiverAppsError && failure.code === 'INVALID_INPUT') throw new LumaProtocolError('INVALID_INPUT');
        // A temporarily unavailable/older receiver can still use EShare for
        // packages present in its own current inventory.
      }
    }
    await connection.launchApp(packageName);
  }
  async function act(payload) {
    if (!payload || !ACTIONS.has(payload.action)) throw new RequestError('Unknown remote action.');
    if (payload.action === 'discover') return { devices: await discover() };
    const host = validateLumaHost(payload.host);
    if (payload.action === 'connect') {
      connection?.close();
      connection = undefined;
      receiverApps = undefined;
      connectedHost = undefined;
      const candidate = connectionFactory(host);
      try {
        const result = await candidate.connect();
        connection = candidate;
        try { receiverApps = receiverAppsFactory(host) || undefined; }
        catch { receiverApps = undefined; }
        connectedHost = host;
        // App discovery is an automatic follow-up from the browser. Keep a
        // usable remote connected even when an older receiver or EShare's app
        // inventory is slow/unavailable.
        return { ...result, connected: true };
      } catch (error) { candidate.close(); connection = undefined; receiverApps = undefined; connectedHost = undefined; throw error; }
    }
    if (payload.action === 'disconnect') {
      if (connectedHost && host !== connectedHost) throw new RequestError('Connect to this projector first.');
      connection?.close();
      connection = undefined;
      receiverApps = undefined;
      connectedHost = undefined;
      return { connected: false };
    }
    if (!connection) throw new RequestError('Connect to this projector first.', 400, true);
    if (host !== connectedHost) throw new RequestError('Connect to this projector first.');
    if (payload.action === 'status') return connection.status();
    if (payload.action === 'apps') return { apps: await appInventory() };
    if (payload.action === 'key') await connection.key(payload.key);
    if (payload.action === 'text') await connection.sendText(payload.text);
    if (payload.action === 'launch-app' || payload.action === 'launch-kodi') {
      await launchApp(payload.action === 'launch-kodi' ? 'org.xbmc.kodi' : payload.packageName);
    }
    return { accepted: true };
  }
  const server = http.createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    const send = (status, body) => { response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' }); response.end(JSON.stringify(body)); };
    try {
      const host = `127.0.0.1:${server.address()?.port}`;
      if (request.headers.host !== host) throw new RequestError('Open the remote using its 127.0.0.1 address.', 403);
      if (request.method === 'GET' && request.url === '/') {
        const html = await readFile(new URL('../src/luma/remote.html', import.meta.url));
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        response.end(html);
        return;
      }
      if (request.method !== 'POST' || request.url !== '/api/luma') throw new RequestError('Not found.', 404);
      if (request.headers.origin !== `http://${host}`) throw new RequestError('Use the local remote page for commands.', 403);
      if (request.headers['content-type']?.split(';')[0].trim() !== 'application/json') throw new RequestError('Expected a JSON remote command.', 415);
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > 16 * 1024) throw new RequestError('Remote command is too large.', 413);
        chunks.push(chunk);
      }
      let payload;
      try { payload = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { throw new RequestError('Invalid remote command.'); }
      if (pending >= 16) throw new RequestError('The remote is busy. Wait a moment, then try again.', 429);
      pending++;
      const task = queue.then(() => act(payload));
      queue = task.catch(() => {});
      try { send(200, await task); }
      finally { pending--; }
    } catch (error) {
      const disconnected = error instanceof LumaProtocolError && LOST_CONNECTION.has(error.code);
      if (disconnected) {
        connection?.close();
        connection = undefined;
        receiverApps = undefined;
        connectedHost = undefined;
      }
      if (!response.headersSent) send(error instanceof RequestError ? error.status : 502, {
        error: error instanceof RequestError || error instanceof LumaProtocolError
          ? error.message : 'Could not reach the projector. Check its power, Wi-Fi and IP address.',
        ...(disconnected || error instanceof RequestError && error.disconnected ? { connected: false } : {})
      });
      else response.end();
    }
  });
  server.requestTimeout = 10000;
  server.on('close', () => connection?.close());
  return server;
}

function savedReceiverAppsFactory() {
  const file = new URL('../.local/receiver-dev.json', import.meta.url);
  try {
    if (!existsSync(file) || (statSync(file).mode & 0o077) !== 0) return () => null;
    const saved = JSON.parse(readFileSync(file, 'utf8'));
    if (!privateLumaHost(saved.host) || typeof saved.token !== 'string') return () => null;
    // Never send the saved bearer token to an arbitrary private host typed into
    // the browser. A DHCP move is repaired by pairing/updating the dev config.
    return host => host === saved.host ? new ReceiverAppsClient({ host, token: saved.token }) : null;
  } catch { return () => null; }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const port = Number(process.env.LUMA_CONTROLLER_PORT || 4176);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('LUMA_CONTROLLER_PORT must be between 1 and 65535.');
    process.exitCode = 1;
  } else {
    const server = createLumaControllerServer({ receiverAppsFactory: savedReceiverAppsFactory() });
    server.on('error', () => { console.error('Could not start the local LUMA remote. Its port may already be in use.'); process.exitCode = 1; });
    server.listen(port, '127.0.0.1', () => console.log(`LUMA remote: http://127.0.0.1:${port}`));
  }
}
