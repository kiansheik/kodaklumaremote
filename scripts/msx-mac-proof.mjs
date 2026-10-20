import http from 'node:http';
import {randomBytes} from 'node:crypto';
import {mkdir, open, readFile, unlink} from 'node:fs/promises';
import {constants} from 'node:fs';
import {isIP} from 'node:net';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

// Isolated proof, not a replacement for the phone server. Assets and commands
// only: this process never fetches, relays or persists media/subtitle sources.
const ROOT = fileURLToPath(new URL('../', import.meta.url));
const MAX_BODY = 128 * 1024;
const ASSETS = new Map([
  ['receiver.html', ['src/msx/receiver.html', 'text/html; charset=utf-8']],
  ['receiver.js', ['src/msx/receiver.js', 'text/javascript; charset=utf-8']],
  ['receiver-core.js', ['src/msx/receiver-core.js', 'text/javascript; charset=utf-8']],
  ['receiver.css', ['src/msx/receiver.css', 'text/css; charset=utf-8']],
  ['hls.min.js', ['node_modules/hls.js/dist/hls.min.js', 'text/javascript; charset=utf-8']]
]);
const secret = () => randomBytes(24).toString('base64url');
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const copy = value => structuredClone(value);

class ProofError extends Error {
  constructor(code, status = 400, detail = {}) { super(code); this.code = code; this.status = status; this.detail = detail; }
}
const fail = (code = 'INVALID_INPUT', status = 400, detail) => { throw new ProofError(code, status, detail); };

export function privateIPv4(value) {
  if (isIP(value || '') !== 4) return false;
  const [a, b] = value.split('.').map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function publicHost(hostname) {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (isIP(host) === 4) {
    const [a, b, c] = host.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && (b === 168 || (b === 0 && (c === 0 || c === 2)) || (b === 88 && c === 99))) ||
      (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) ||
      (a === 203 && b === 0 && c === 113));
  }
  if (isIP(host) === 6) {
    const [first, second = '0'] = host.split(':').map(part => parseInt(part || '0', 16));
    return first >= 0x2000 && first < 0x4000 && first !== 0x2002 && first !== 0x3fff &&
      !(first === 0x2001 && (second < 0x200 || second === 0xdb8));
  }
  const domain = host.replace(/\.$/, '');
  return domain.length <= 253 && domain.includes('.') && !/^\d+$/.test(domain.split('.').at(-1)) &&
    !/(^|\.)(localhost|local|localdomain|lan|home|internal|arpa)$/.test(domain) &&
    domain.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label));
}

export function publicUrl(value) {
  if (typeof value !== 'string' || !value || value.length > 8192 || /[\u0000-\u0020\u007f|\\#]/.test(value)) fail('INVALID_MEDIA');
  let url;
  try { url = new URL(value); } catch { fail('INVALID_MEDIA'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || !publicHost(url.hostname)) fail('INVALID_MEDIA');
  return url.href;
}

function number(value, min, max) {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) fail();
  return value;
}
function language(value = '') {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{0,35}$/.test(value)) fail();
  return value;
}
function label(value, max, fallback = '') {
  if (value === undefined) value = fallback;
  if (typeof value !== 'string') fail();
  return value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, ' ')
    .replace(/(?:[a-z][a-z0-9+.-]*:\/\/|www\.)\S+/gi, '').trim().slice(0, max);
}
function boolean(value) { if (typeof value !== 'boolean') fail(); return value; }

export function validateCommand(method, params = {}) {
  if (!object(params)) fail();
  if (method === 'open') {
    const tracks = params.subtitles ?? [];
    // Existing receiver accepts at most 100 tracks.
    if (!Array.isArray(tracks) || tracks.length > 100) fail();
    const mimeType = params.mimeType ?? '';
    if (typeof mimeType !== 'string' || !/^[A-Za-z0-9.+/-]{0,100}$/.test(mimeType)) fail();
    return {source: publicUrl(params.source), title: label(params.title, 256, 'Video'), mimeType,
      subtitles: tracks.map(track => {
        if (!object(track)) fail();
        return {url: publicUrl(track.url), language: language(track.language), label: label(track.label, 100)};
      }), preferredLanguage: language(params.preferredLanguage ?? 'pt-BR')};
  }
  if (!['playPause', 'seek', 'volume', 'subtitle', 'stop'].includes(method)) fail();
  const safe = {expectedSourceUrl: publicUrl(params.expectedSourceUrl)};
  if (method === 'playPause') safe.play = boolean(params.play);
  if (method === 'seek') safe.seconds = number(params.seconds, 0, 17280000);
  if (method === 'volume') safe.value = number(params.value, 0, 100);
  if (method === 'subtitle') {
    safe.index = number(params.index, -1, 127);
    if (!Number.isInteger(safe.index)) fail();
    safe.enabled = boolean(params.enabled);
  }
  return safe;
}

function validatePlayback(state) {
  if (!object(state)) fail();
  const active = boolean(state.active), source = state.source ?? '';
  if (typeof source !== 'string') fail();
  const safe = {active, source: active || source ? publicUrl(source) : '', title: label(state.title, 256),
    paused: boolean(state.paused), positionSeconds: number(state.positionSeconds, 0, 17280000),
    durationSeconds: number(state.durationSeconds, 0, 17280000), canSeek: boolean(state.canSeek)};
  if (state.volume !== undefined) {
    if (!object(state.volume)) fail();
    safe.volume = {current: number(state.volume.current, 0, 100), max: 100};
  }
  const tracks = state.tracks ?? [], indices = new Set();
  if (!Array.isArray(tracks) || tracks.length > 128) fail();
  safe.tracks = tracks.map(track => {
    if (!object(track)) fail();
    const index = number(track.index, 0, 127);
    if (!Number.isInteger(index) || indices.has(index)) fail();
    indices.add(index);
    return {index, language: language(track.language), label: label(track.label, 100)};
  });
  safe.selectedIndex = number(state.selectedIndex, -1, 127);
  if (!Number.isInteger(safe.selectedIndex) || (safe.selectedIndex >= 0 && !indices.has(safe.selectedIndex))) fail();
  safe.subtitlesEnabled = boolean(state.subtitlesEnabled);
  const error = state.error ?? '';
  if (typeof error !== 'string' || !/^[A-Z_]{0,64}$/.test(error)) fail();
  safe.error = error;
  return safe;
}

function send(res, status, value, type = 'application/json; charset=utf-8') {
  if (res.destroyed || res.writableEnded) return;
  const bytes = Buffer.isBuffer(value) ? value : Buffer.from(JSON.stringify(value));
  res.writeHead(status, {'Content-Type': type, 'Content-Length': bytes.length, 'Cache-Control': 'no-store',
    'Referrer-Policy': 'no-referrer', 'X-Content-Type-Options': 'nosniff', 'Connection': 'close'});
  res.end(bytes);
}

function requestInfo(req, expectedOrigin, prefix) {
  if (typeof req.url !== 'string' || req.url.length > 4096 || !req.url.startsWith('/') || req.url.startsWith('//')) fail('REQUEST_REJECTED');
  const seen = new Set();
  for (let i = 0; i < req.rawHeaders.length; i += 2) {
    const name = req.rawHeaders[i].toLowerCase();
    if (seen.has(name)) fail('REQUEST_REJECTED');
    seen.add(name);
  }
  if (req.headers.host !== new URL(expectedOrigin).host) fail('REQUEST_REJECTED', 403);
  const origin = req.headers.origin;
  if (origin !== undefined && origin !== 'null' && origin !== expectedOrigin) fail('REQUEST_REJECTED', 403);
  if (req.headers['transfer-encoding']) fail('REQUEST_REJECTED');
  const size = req.headers['content-length'];
  if (size !== undefined && !/^\d{1,6}$/.test(size)) fail('REQUEST_REJECTED');
  if (Number(size || 0) > MAX_BODY) fail('REQUEST_REJECTED', 413);
  if (req.method !== 'POST' && Number(size || 0)) fail('REQUEST_REJECTED');
  const [pathname, rawQuery = ''] = req.url.split('?');
  if (!pathname.startsWith(prefix)) fail('REQUEST_REJECTED', 404);
  if (rawQuery.length > 2048) fail('REQUEST_REJECTED');
  const query = new URLSearchParams(rawQuery), keys = new Set();
  for (const [key] of query) { if (keys.has(key)) fail('REQUEST_REJECTED'); keys.add(key); }
  return {relative: pathname.slice(prefix.length), query};
}

function readBody(req, timeoutMs) {
  if (req.headers['content-type']?.split(';', 1)[0].trim().toLowerCase() !== 'application/json') fail('REQUEST_REJECTED', 415);
  return new Promise((resolve, reject) => {
    const chunks = []; let size = 0, settled = false;
    const timer = setTimeout(() => finish(new ProofError('REQUEST_REJECTED', 408)), timeoutMs);
    const finish = (error, value) => {
      if (settled) return;
      settled = true; clearTimeout(timer);
      req.removeListener('data', data); req.removeListener('end', end); req.removeListener('aborted', aborted); req.removeListener('error', aborted);
      if (error) { req.resume(); reject(error); } else resolve(value);
    };
    const data = chunk => { size += chunk.length; if (size > MAX_BODY) finish(new ProofError('REQUEST_REJECTED', 413)); else chunks.push(chunk); };
    const aborted = () => finish(new ProofError('REQUEST_REJECTED'));
    const end = () => {
      let value;
      try { value = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { finish(new ProofError('REQUEST_REJECTED')); return; }
      if (!object(value)) finish(new ProofError('REQUEST_REJECTED')); else finish(null, value);
    };
    req.on('data', data); req.on('end', end); req.on('aborted', aborted); req.on('error', aborted);
  });
}

/** Importing creates no listener or file. Loopback fixtures are opt-in only here,
 * never accepted by the CLI. now/timeouts are injected for bounded tests. */
export function createProofServer({host, tvHost, port = 4179, controlPort = 0,
  allowLoopbackForTests = false, now = Date.now, bodyTimeoutMs = 4000,
  openTimeoutMs = 45000, commandTimeoutMs = 10000, freshMs = 12000} = {}) {
  const fixtureAddress = value => allowLoopbackForTests && value === '127.0.0.1';
  if ((!privateIPv4(host) && !fixtureAddress(host)) || (!privateIPv4(tvHost) && !fixtureAddress(tvHost))) fail('PRIVATE_IPV4_REQUIRED');
  for (const value of [port, controlPort]) if (!Number.isInteger(value) || value < 0 || value > 65535) fail('INVALID_PORT');
  for (const value of [bodyTimeoutMs, openTimeoutMs, commandTimeoutMs, freshMs]) if (!Number.isFinite(value) || value < 1 || value > 60000) fail();
  const token = secret(), controlToken = secret(), assets = new Map();
  let receiverOrigin, controlOrigin, listening = false, closed = false, client, sessionId;
  let sequence = 0, lastSeen = null, lastState = null, playback = {}, lastCommand = {}, pending;
  const counters = {requests: 0, assets: 0, hellos: 0, polls: 0, states: 0, acks: 0, rejected: 0,
    receiverConnections: 0, receiverTls: 0, receiverMalformed: 0};
  const connections = new Set();
  function status() {
    return {running: listening, configured: true, connected: listening && sessionId !== undefined && lastState !== null && now() - lastState <= freshMs,
      name: 'Philips TV Mac proof', playback: copy(playback), lastCommand: copy(lastCommand),
      diagnostics: copy(counters), ...(pending ? {pendingCommand: {id: pending.id, method: pending.command.method, delivered: pending.delivered}} : {})};
  }
  function finishPending(error, result, keep = false) {
    const owned = pending;
    if (!owned) return;
    clearTimeout(owned.timer);
    if (!keep) pending = undefined;
    if (owned.settled) return;
    owned.settled = true;
    if (error) owned.reject(error); else owned.resolve(result);
  }
  function requireSession(value) {
    if (!sessionId || value.client !== client || value.sessionId !== sessionId) fail('SESSION_REJECTED', 403);
  }
  async function submit(method, params) {
    const safe = validateCommand(method, params);
    if (!listening || !sessionId || lastSeen === null || now() - lastSeen > freshMs) fail('MSX_NOT_CONNECTED', 409);
    if (pending) fail('MSX_PENDING_COMMAND', 409, {id: pending.id, delivered: pending.delivered});
    if (method !== 'open' && safe.expectedSourceUrl !== playback.source) fail('SOURCE_CHANGED', 409);
    const id = ++sequence;
    return new Promise((resolve, reject) => {
      pending = {id, sessionId, command: {id, method, params: safe}, delivered: false, settled: false, resolve, reject};
      const owned = pending;
      owned.timer = setTimeout(() => {
        if (pending !== owned) return;
        // A delivered timeout is an unknown outcome. Keep it blocked until ACK
        // or a fresh hello; never repeat a possibly successful Open.
        finishPending(new ProofError('MSX_TIMEOUT', 504, {id, delivered: owned.delivered}), undefined, owned.delivered);
      }, method === 'open' ? openTimeoutMs : commandTimeoutMs);
    });
  }
  async function receiver(req, res) {
    if (req.socket.remoteAddress !== tvHost) fail('REQUEST_REJECTED', 403);
    const {relative: pathname, query} = requestInfo(req, receiverOrigin, '/');
    // The shared receiver checks its loaded capability before each fresh hello.
    // This marker does not claim or renew a session and is TV-peer restricted.
    if (req.method === 'GET' && pathname === 'msx/start.json' && !query.size) {
      send(res, 200, {parameter: `content:${receiverOrigin}/${token}/home.json`}); return;
    }
    const prefix = `${token}/`;
    if (!pathname.startsWith(prefix)) fail('REQUEST_REJECTED', 404);
    const relative = pathname.slice(prefix.length);
    if (req.method === 'GET' && ASSETS.has(relative) && !query.size) {
      counters.assets++; const [bytes, type] = assets.get(relative); send(res, 200, bytes, type); return;
    }
    if (req.method === 'GET' && relative === 'hello') {
      const requested = query.get('client');
      if (!/^[A-Za-z0-9_-]{16,128}$/.test(requested || '')) fail('REQUEST_REJECTED');
      if (client && client !== requested) fail('CLIENT_REJECTED', 403);
      finishPending(new ProofError('SESSION_CHANGED', 409));
      client = requested; sessionId = secret(); playback = {}; lastCommand = {}; lastState = null; lastSeen = now(); counters.hellos++;
      send(res, 200, {sessionId, approved: true}); return;
    }
    if (req.method === 'GET' && relative === 'poll') {
      requireSession({client: query.get('client'), sessionId: query.get('sessionId')});
      const after = query.get('after');
      if (!/^\d{1,16}$/.test(after || '') || !Number.isSafeInteger(Number(after))) fail('REQUEST_REJECTED');
      const commands = [];
      if (pending && !pending.delivered && pending.id > Number(after)) {
        const expected = pending.command.params.expectedSourceUrl;
        if (expected && expected !== playback.source) {
          lastCommand = {id: pending.id, method: pending.command.method, ok: false, error: 'SOURCE_CHANGED'};
          finishPending(new ProofError('SOURCE_CHANGED', 409));
        } else { pending.delivered = true; commands.push(copy(pending.command)); }
      }
      lastSeen = now(); counters.polls++; send(res, 200, {approved: true, commands}); return;
    }
    if (req.method === 'POST' && (relative === 'state' || relative === 'ack')) {
      const body = await readBody(req, bodyTimeoutMs); requireSession(body);
      if (relative === 'state') {
        playback = validatePlayback(body.playback); lastSeen = lastState = now(); counters.states++;
      } else {
        if (!Number.isSafeInteger(body.id) || body.id < 1 || body.id > sequence || typeof body.ok !== 'boolean') fail('REQUEST_REJECTED');
        if (pending?.id === body.id && !pending.delivered) fail('ACK_BEFORE_DELIVERY', 409);
        if (pending?.id === body.id) {
          const error = typeof body.error === 'string' && /^[A-Z_]{0,64}$/.test(body.error) ? body.error : 'RECEIVER_ERROR';
          lastCommand = {id: body.id, method: pending.command.method, ok: body.ok, error: body.ok ? '' : error};
          if (body.ok) finishPending(null, {...status(), pendingCommand: undefined, accepted: true, id: body.id});
          else finishPending(new ProofError(error === 'SOURCE_CHANGED' ? 'SOURCE_CHANGED' : 'MSX_REJECTED', 409, {id: body.id, receiverError: error}));
        }
        lastSeen = now(); counters.acks++;
      }
      send(res, 200, {ok: true}); return;
    }
    fail('REQUEST_REJECTED', 404);
  }
  async function control(req, res) {
    if (req.socket.remoteAddress !== '127.0.0.1') fail('REQUEST_REJECTED', 403);
    const {relative, query} = requestInfo(req, controlOrigin, `/${controlToken}/`);
    if (query.size) fail('REQUEST_REJECTED');
    if (req.method === 'GET' && relative === 'status') { send(res, 200, status()); return; }
    if (req.method === 'POST' && relative === 'command') {
      const body = await readBody(req, bodyTimeoutMs);
      send(res, 200, await submit(body.method, body.params)); return;
    }
    fail('REQUEST_REJECTED', 404);
  }
  function server(handler, receiverSide = false) {
    const value = http.createServer({maxHeaderSize: 8192, headersTimeout: 4000, requestTimeout: 5000,
      keepAliveTimeout: 1000, connectionsCheckingInterval: 250}, (req, res) => {
      counters.requests++;
      Promise.resolve().then(() => handler(req, res)).catch(error => {
        counters.rejected++;
        const known = error instanceof ProofError;
        send(res, known ? error.status : 500, {error: known ? error.code : 'INTERNAL_ERROR', ...(known ? error.detail : {})});
      });
    });
    value.maxConnections = 8; value.maxRequestsPerSocket = 16;
    value.on('connection', socket => {
      if (receiverSide) counters.receiverConnections++;
      connections.add(socket); socket.on('close', () => connections.delete(socket));
    });
    // Do not let Node's parser error include a request target or body in logs.
    value.on('clientError', (error, socket) => {
      if (receiverSide) {
        if (error.rawPacket?.[0] === 22) counters.receiverTls++; else counters.receiverMalformed++;
      }
      if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\nContent-Length: 0\r\n\r\n'); else socket.destroy();
    });
    return value;
  }
  const receiverServer = server(receiver, true), controlServer = server(control);
  async function bind(value, bindHost, bindPort) {
    await new Promise((resolve, reject) => {
      value.once('error', reject);
      value.listen(bindPort, bindHost, () => { value.removeListener('error', reject); resolve(); });
    });
    return `http://${bindHost}:${value.address().port}`;
  }
  async function close() {
    listening = false; closed = true; finishPending(new ProofError('SERVER_CLOSED', 503));
    for (const socket of connections) socket.destroy();
    await Promise.all([receiverServer, controlServer].map(value => new Promise(resolve => {
      if (!value.listening) resolve(); else value.close(() => resolve());
    })));
  }
  return {
    async listen() {
      if (closed || listening) fail('SERVER_STATE', 409);
      for (const [name, [file, type]] of ASSETS) assets.set(name, [await readFile(path.join(ROOT, file)), type]);
      try { receiverOrigin = await bind(receiverServer, host, port); controlOrigin = await bind(controlServer, '127.0.0.1', controlPort); }
      catch (error) { await close(); throw error; }
      listening = true; return this;
    },
    get receiverUrl() { return listening ? `${receiverOrigin}/${token}/receiver.html` : undefined; },
    get controlUrl() { return listening ? `${controlOrigin}/${controlToken}/` : undefined; },
    status, close
  };
}

export function parseArgs(args) {
  const values = {};
  for (let i = 0; i < args.length; i += 2) {
    const key = args[i], value = args[i + 1];
    if (!['--host', '--tv-host', '--port'].includes(key) || value === undefined || key in values) fail('INVALID_ARGUMENT');
    values[key] = value;
  }
  if (!privateIPv4(values['--host']) || !privateIPv4(values['--tv-host'])) fail('PRIVATE_IPV4_REQUIRED');
  const rawPort = values['--port'] ?? '4179';
  if (!/^\d{1,5}$/.test(rawPort) || +rawPort < 1024 || +rawPort > 65535) fail('INVALID_PORT');
  return {host: values['--host'], tvHost: values['--tv-host'], port: +rawPort};
}

export function sanitizeStatus(value) {
  const safe = {running: value.running === true, connected: value.connected === true, active: value.playback?.active === true,
    paused: value.playback?.paused !== false};
  for (const key of ['positionSeconds', 'durationSeconds']) if (Number.isFinite(value.playback?.[key])) safe[key] = value.playback[key];
  if (Number.isFinite(value.playback?.volume?.current)) safe.volume = value.playback.volume.current;
  if (Number.isInteger(value.playback?.selectedIndex)) safe.selectedIndex = value.playback.selectedIndex;
  if (typeof value.playback?.subtitlesEnabled === 'boolean') safe.subtitlesEnabled = value.playback.subtitlesEnabled;
  if (value.accepted === true) safe.accepted = true;
  if (Number.isSafeInteger(value.id)) safe.id = value.id;
  if (typeof value.delivered === 'boolean') safe.delivered = value.delivered;
  if (object(value.diagnostics)) {
    safe.diagnostics = {};
    for (const key of ['requests', 'assets', 'hellos', 'polls', 'states', 'acks', 'rejected', 'receiverConnections', 'receiverTls', 'receiverMalformed']) {
      if (Number.isSafeInteger(value.diagnostics[key]) && value.diagnostics[key] >= 0) safe.diagnostics[key] = value.diagnostics[key];
    }
  }
  if (object(value.pendingCommand)) safe.pendingCommand = {id: value.pendingCommand.id,
    method: ['open', 'playPause', 'seek', 'volume', 'subtitle', 'stop'].includes(value.pendingCommand.method) ? value.pendingCommand.method : 'unknown',
    delivered: value.pendingCommand.delivered === true};
  if (object(value.lastCommand) && Number.isSafeInteger(value.lastCommand.id)) safe.lastCommand = {id: value.lastCommand.id,
    ok: value.lastCommand.ok === true, error: /^[A-Z_]{0,64}$/.test(value.lastCommand.error || '') ? value.lastCommand.error || '' : 'RECEIVER_ERROR'};
  if (typeof value.error === 'string' && /^[A-Z_]{1,64}$/.test(value.error)) safe.error = value.error;
  if (typeof value.receiverError === 'string' && /^[A-Z_]{1,64}$/.test(value.receiverError)) safe.receiverError = value.receiverError;
  return safe;
}

export async function readPrivateConfig(artifact) {
  const handle = await open(artifact, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size > 16384 || (stat.mode & 0o077) !== 0 || stat.uid !== process.getuid()) fail('PRIVATE_CONFIG_REQUIRED');
    const bytes = Buffer.alloc(16385), {bytesRead} = await handle.read(bytes, 0, bytes.length, 0);
    if (bytesRead > 16384) fail('PRIVATE_CONFIG_REQUIRED');
    let value;
    try { value = JSON.parse(bytes.toString('utf8', 0, bytesRead)); } catch { fail('INVALID_CONFIG'); }
    if (!object(value) || value.proof !== true || !Number.isSafeInteger(value.pid) || value.pid < 1 || typeof value.controlUrl !== 'string') fail('INVALID_CONFIG');
    let url;
    try { url = new URL(value.controlUrl); } catch { fail('INVALID_CONFIG'); }
    if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.username || url.password ||
      !/^\/[A-Za-z0-9_-]{32}\/$/.test(url.pathname) || url.search || url.hash || value.controlUrl !== url.href) fail('INVALID_CONFIG');
    return {controlUrl: url.href};
  } finally { await handle.close(); }
}

async function clientRequest(controlUrl, action, body) {
  const bytes = body === undefined ? undefined : Buffer.from(JSON.stringify(body));
  return new Promise((resolve, reject) => {
    const req = http.request(new URL(action, controlUrl), {method: bytes ? 'POST' : 'GET',
      headers: bytes ? {'Content-Type': 'application/json', 'Content-Length': bytes.length} : {}, timeout: 55000}, res => {
      const chunks = []; let size = 0;
      res.on('data', chunk => { size += chunk.length; if (size > MAX_BODY) res.destroy(new ProofError('INVALID_RESPONSE')); else chunks.push(chunk); });
      res.on('error', reject);
      res.on('end', () => {
        let value;
        try { value = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { reject(new ProofError('INVALID_RESPONSE')); return; }
        if (!object(value) || res.statusCode < 200 || res.statusCode >= 300) {
          // No redirects are followed, and raw errors/bodies never reach output.
          reject(new ProofError('CONTROL_REJECTED', 400, object(value) ? sanitizeStatus(value) : {}));
        } else resolve(sanitizeStatus(value));
      });
    });
    req.on('timeout', () => req.destroy(new ProofError('CONTROL_TIMEOUT'))); req.on('error', reject);
    req.end(bytes);
  });
}

async function stdinCommand() {
  const chunks = []; let size = 0;
  for await (const chunk of process.stdin) { size += chunk.length; if (size > MAX_BODY) fail('INVALID_INPUT'); chunks.push(chunk); }
  let value;
  try { value = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { fail(); }
  if (!object(value)) fail();
  // Validate before opening even the loopback control connection.
  return {method: value.method, params: validateCommand(value.method, value.params)};
}

async function main() {
  let proof;
  const artifact = path.join(ROOT, '.local/msx-mac-proof.json');
  let shutdown;
  try {
    const args = process.argv.slice(2);
    if (['status', 'command'].includes(args[0])) {
      if (args.length !== 1) fail('INVALID_ARGUMENT');
      const {controlUrl} = await readPrivateConfig(artifact);
      const body = args[0] === 'command' ? await stdinCommand() : undefined;
      process.stdout.write(JSON.stringify(await clientRequest(controlUrl, args[0], body)) + '\n'); return;
    }
    proof = createProofServer(parseArgs(args)); await proof.listen();
    const directory = path.dirname(artifact);
    await mkdir(directory, {recursive: true, mode: 0o700});
    // Refuse existing files/symlinks so another running proof keeps its details.
    const handle = await open(artifact, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
    try {
      await handle.chmod(0o600);
      await handle.writeFile(JSON.stringify({proof: true, pid: process.pid, startedAt: new Date().toISOString(),
        receiverUrl: proof.receiverUrl, controlUrl: proof.controlUrl}, null, 2) + '\n');
    } finally { await handle.close(); }
    process.stdout.write('Mac receiver proof ready; private connection details: .local/msx-mac-proof.json\n');
    shutdown = async () => { await proof.close(); await unlink(artifact).catch(() => {}); };
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { shutdown().then(() => process.exit(0)); });
  } catch (error) {
    await proof?.close();
    // Fixed codes only. OS messages may contain addresses or paths.
    process.stderr.write(`Mac receiver proof failed (${error instanceof ProofError ? error.code : 'START_FAILED'}).\n`);
    if (error instanceof ProofError && Object.keys(error.detail).length) process.stderr.write(JSON.stringify(sanitizeStatus(error.detail)) + '\n');
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) await main();
