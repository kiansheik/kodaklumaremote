import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { isIP } from 'node:net';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { LumaConnection, LumaProtocolError } from './luma-protocol.mjs';
import { discoverLuma } from './luma-discovery.mjs';

const ACTIONS = new Set(['discover', 'connect', 'disconnect', 'status', 'key', 'apps', 'launch-kodi', 'launch-app', 'text']);
const LOST_CONNECTION = new Set(['CONNECTION_FAILED', 'TIMEOUT', 'INVALID_RESPONSE', 'NOT_CONNECTED', 'PAIRING_UNKNOWN', 'PAIRING_REQUIRED']);
class RequestError extends Error {
  constructor(message, status = 400, disconnected = false) { super(message); this.status = status; this.disconnected = disconnected; }
}

export function validateLumaHost(host) {
  const octets = typeof host === 'string' ? host.split('.').map(Number) : [];
  if (isIP(typeof host === 'string' ? host : '') !== 4 || !(octets[0] === 10 ||
    (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168))) {
    throw new RequestError('Enter the projector’s private IPv4 address.');
  }
  return host;
}

export function createLumaControllerServer({ connectionFactory = host => new LumaConnection({ host }), discover = discoverLuma } = {}) {
  let connection;
  let connectedHost;
  let pending = 0;
  let queue = Promise.resolve();
  async function act(payload) {
    if (!payload || !ACTIONS.has(payload.action)) throw new RequestError('Unknown remote action.');
    if (payload.action === 'discover') return { devices: await discover() };
    const host = validateLumaHost(payload.host);
    if (payload.action === 'connect') {
      connection?.close();
      connection = undefined;
      connectedHost = undefined;
      const candidate = connectionFactory(host);
      try {
        const result = await candidate.connect();
        connection = candidate;
        connectedHost = host;
        return { ...result, connected: true };
      } catch (error) { candidate.close(); throw error; }
    }
    if (payload.action === 'disconnect') {
      if (connectedHost && host !== connectedHost) throw new RequestError('Connect to this projector first.');
      connection?.close();
      connection = undefined;
      connectedHost = undefined;
      return { connected: false };
    }
    if (!connection) throw new RequestError('Connect to this projector first.', 400, true);
    if (host !== connectedHost) throw new RequestError('Connect to this projector first.');
    if (payload.action === 'status') return connection.status();
    if (payload.action === 'apps') return { apps: await connection.getApps() };
    if (payload.action === 'key') await connection.key(payload.key);
    if (payload.action === 'text') await connection.sendText(payload.text);
    if (payload.action === 'launch-app' || payload.action === 'launch-kodi') {
      await connection.launchApp(payload.action === 'launch-kodi' ? 'org.xbmc.kodi' : payload.packageName);
    }
    return { accepted: true };
  }
  const server = http.createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
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

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const port = Number(process.env.LUMA_CONTROLLER_PORT || 4176);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('LUMA_CONTROLLER_PORT must be between 1 and 65535.');
    process.exitCode = 1;
  } else {
    const server = createLumaControllerServer();
    server.on('error', () => { console.error('Could not start the local LUMA remote. Its port may already be in use.'); process.exitCode = 1; });
    server.listen(port, '127.0.0.1', () => console.log(`LUMA remote: http://127.0.0.1:${port}`));
  }
}
