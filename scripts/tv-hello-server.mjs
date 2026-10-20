import http from 'node:http';
import {createHash, randomBytes} from 'node:crypto';
import {constants} from 'node:fs';
import {mkdir, open, readFile, stat, unlink} from 'node:fs/promises';
import {isIP} from 'node:net';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const ROOT = new URL('../', import.meta.url);
const CONFIG = new URL('.local/tv-hello/config.json', ROOT);
const SESSION = new URL('.local/tv-hello/session.json', ROOT);
const KEYS = new Set(['up', 'down', 'left', 'right', 'ok', 'back', 'other']);
const BRIDGES = ['loadApp', 'enableDevtools', 'smartTvApi', 'oipfFactory'];
const COMMAND_METHODS = new Set(['ping', 'set-label']);
const COMMAND_LABEL = 'Controlled remotely';
const MSX_ORIGINS = ['http://msx.benzac.de', 'https://msx.benzac.de', 'http://www.msx.benzac.de', 'https://www.msx.benzac.de'];
const SOCKET_ROOTS = ['window', 'navigator', 'chrome', 'opera', 'external', 'SmartTvA_API',
  'opera_omi', 'NetTV_Connection', 'NetTV_ContentManager', 'NetTV_DeviceUI', 'opera_omi.constructors'];
const VERSION_FIELDS = ['chromeVersion', 'chromiumVersion', 'appleWebKitVersion', 'firefoxVersion', 'browserVersion',
  'operaVersion', 'omiVersion', 'titanOsVersion', 'nettvVersion', 'smartTvAllianceVersion'];
const DIMENSION_FIELDS = ['viewportWidth', 'viewportHeight', 'screenWidth', 'screenHeight'];
const FEATURE_FIELDS = ['fetch', 'promise', 'webAssembly', 'webSocket', 'mediaSource', 'eme', 'webCodecs', 'webGL', 'webGL2', 'serviceWorker', 'cryptoSubtle', 'localStorage', 'indexedDB',
  'tcpServerSocket', 'tcpSocket', 'udpSocket', 'webTransport', 'rtcPeerConnection'];
const MEDIA_IDS = ['h264Baseline', 'h264High', 'hevc', 'vp8', 'vp9', 'av1', 'hls', 'dash', 'aac', 'opus', 'ac3', 'eac3'];
const COMPAT_KEYS = [...VERSION_FIELDS, ...DIMENSION_FIELDS, ...FEATURE_FIELDS, 'platform', 'devicePixelRatio', 'secureContext', 'crossOriginIsolated', 'media'];
const object = value => value && typeof value === 'object' && !Array.isArray(value);
const commandIdentityOK = value => object(value) && Object.keys(value).length === 2 &&
  typeof value.id === 'string' && /^[A-Za-z0-9_-]{32}$/.test(value.id) && COMMAND_METHODS.has(value.method);
const commandResultOK = value => object(value) && Object.keys(value).length === 3 && value.ok === true &&
  commandIdentityOK({id: value.id, method: value.method});
const descriptorEntryOK = entry => object(entry) && Object.keys(entry).length === 2 &&
  typeof entry.name === 'string' && /^[A-Za-z_$][A-Za-z0-9_$]{0,63}$/.test(entry.name) &&
  ['function', 'accessor', 'value'].includes(entry.kind);
const csManagerSourceOK = value => object(value) && Object.keys(value).length === 6 && value.v === 1 &&
  typeof value.source === 'string' && value.source.length <= 4096 && Number.isSafeInteger(value.totalLength) &&
  value.totalLength >= value.source.length && value.totalLength <= 131072 &&
  value.complete === (value.totalLength === value.source.length) && typeof value.nativeMarker === 'boolean' &&
  Array.isArray(value.prototype) && value.prototype.length <= 16 && value.prototype.every(descriptorEntryOK);
const hbbtvObjectOK = value => object(value) && Object.keys(value).length === 7 && value.v === 1 &&
  ['factoryAvailable', 'elementCreated', 'localMethod', 'remoteMethod'].every(key => typeof value[key] === 'boolean') &&
  (value.objectSupported === null || typeof value.objectSupported === 'boolean') &&
  [null, 'TypeError', 'NotAllowedError', 'SecurityError', 'NotSupportedError', 'Error'].includes(value.errorName);
const csManagerAttemptOK = value => object(value) && Object.keys(value).length === 7 && value.v === 1 &&
  ['attempted', 'constructed', 'localMethod', 'remoteMethod'].every(key => typeof value[key] === 'boolean') &&
  [null, 'TypeError', 'NotAllowedError', 'SecurityError', 'NotSupportedError', 'Error'].includes(value.errorName) &&
  Array.isArray(value.instance) && value.instance.length <= 16 && value.instance.every(descriptorEntryOK);
const probeContextOK = value => object(value) && Object.keys(value).length === 3 && value.v === 1 &&
  typeof value.embedded === 'boolean' && typeof value.activeDiagnostics === 'boolean';
const canonicalOrigin = value => {
  if (typeof value !== 'string' || value.length > 200 || /[;*'"\s]/.test(value)) return null;
  try {
    const url = new URL(value);
    const host = url.hostname;
    const safeHost = isIP(host) === 4 || (host.startsWith('[') && host.endsWith(']') && isIP(host.slice(1, -1)) === 6) ||
      /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/i.test(host);
    return ['http:', 'https:'].includes(url.protocol) && safeHost && value === url.origin ? value : null;
  }
  catch { return null; }
};
const msxStatusOK = value => object(value) && Object.keys(value).length === 18 && typeof value.enabled === 'boolean' &&
  ['requests', 'gets', 'options', 'accepted', 'rejected', 'clientErrors', 'tlsClientErrors', 'activeConnections',
    'completedConnections', 'zeroByteConnections', 'idleTimeoutConnections', 'receivedBytes'].every(key => Number.isSafeInteger(value[key]) && value[key] >= 0) &&
  value.receivedBytes <= 16 * 1024 * 1024 && [null, 'idle-timeout', 'request-timeout', 'invalid-method', 'invalid-header', 'other'].includes(value.lastClientError) &&
  [null, 'start', 'home'].includes(value.lastRoute) && [null, 'missing', 'trusted', 'opaque', 'other', 'invalid'].includes(value.lastOriginClass) &&
  (value.lastOrigin === null || value.lastOrigin === 'null' || canonicalOrigin(value.lastOrigin) !== null) && [null, 200, 204, 403].includes(value.lastStatus);
function socketDiagnosticsOK(value) {
  if (!object(value) || Object.keys(value).length !== 4 || value.v !== 1 ||
      !['allowed', 'blocked', 'unsupported', 'error'].includes(value.policy)) return false;
  const constructor = value.constructor;
  if (!object(constructor) || Object.keys(constructor).length !== 3 ||
      !['absent', 'function', 'accessor', 'other'].includes(constructor.exposure) ||
      !['not-exposed', 'not-callable', 'not-attempted', 'type-error', 'permission-rejected', 'unexpected-result', 'error'].includes(constructor.attempt) ||
      ![null, 'TypeError', 'NotAllowedError', 'SecurityError', 'NotSupportedError', 'Error'].includes(constructor.errorName)) return false;
  let entries = 0;
  return Array.isArray(value.surfaces) && value.surfaces.length === SOCKET_ROOTS.length && value.surfaces.every((surface, index) => {
    if (!object(surface) || Object.keys(surface).length !== 3 || surface.root !== SOCKET_ROOTS[index] ||
        !['missing', 'accessor', 'object', 'other'].includes(surface.state) || !Array.isArray(surface.entries) ||
        surface.entries.length > 32 || (surface.state !== 'object' && surface.entries.length)) return false;
    entries += surface.entries.length;
    return entries <= 64 && surface.entries.every(descriptorEntryOK);
  });
}
function compatibilityOK(value) {
  if (!object(value) || Object.keys(value).length !== COMPAT_KEYS.length || COMPAT_KEYS.some(key => !Object.hasOwn(value, key))) return false;
  if (VERSION_FIELDS.some(key => value[key] !== null && (typeof value[key] !== 'string' || value[key].length > 32 || !/^\d+(?:\.\d+)*$/.test(value[key])))) return false;
  if (value.platform !== null && (typeof value.platform !== 'string' || !/^[A-Za-z0-9_. ()/-]{0,64}$/.test(value.platform))) return false;
  if (DIMENSION_FIELDS.some(key => value[key] !== null && (!Number.isInteger(value[key]) || value[key] < 0 || value[key] > 16384))) return false;
  if (value.devicePixelRatio !== null && (typeof value.devicePixelRatio !== 'number' || !Number.isFinite(value.devicePixelRatio) || value.devicePixelRatio < 0 || value.devicePixelRatio > 16)) return false;
  if (['secureContext', 'crossOriginIsolated'].some(key => value[key] !== null && typeof value[key] !== 'boolean') || FEATURE_FIELDS.some(key => typeof value[key] !== 'boolean')) return false;
  return Array.isArray(value.media) && value.media.length === MEDIA_IDS.length && value.media.every((entry, index) =>
    object(entry) && Object.keys(entry).length === 3 && entry.id === MEDIA_IDS[index] &&
    [null, '', 'maybe', 'probably'].includes(entry.canPlayType) && (entry.mse === null || typeof entry.mse === 'boolean'));
}
const surfaceOK = value => value === null || (Array.isArray(value) && value.length <= 48 && value.every(entry =>
  entry && typeof entry === 'object' && !Array.isArray(entry) && Object.keys(entry).length === 4 &&
  typeof entry.name === 'string' && /^[A-Za-z_$][A-Za-z0-9_$]{0,63}$/.test(entry.name) &&
  ['function', 'accessor', 'value'].includes(entry.kind) && Number.isInteger(entry.level) &&
  entry.level >= 0 && entry.level <= 2 && typeof entry.nativeCode === 'boolean'));
const secret = () => randomBytes(24).toString('base64url');
const privateIPv4 = value => isIP(value || '') === 4 && (() => {
  const [a, b] = value.split('.').map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
})();
const reject = () => { throw new Error('REQUEST_REJECTED'); };
const loopback = address => address === '127.0.0.1' || address === '::ffff:127.0.0.1';

// MSX appends these two cache fields to JSON requests. Callers still restrict
// the returned path to bootstrap/home; this grants no query support elsewhere.
export function msxCachePath(value) {
  if (typeof value !== 'string' || value.length > 256 || value.includes('#')) return null;
  const marker = value.indexOf('?');
  if (marker < 0) return value;
  const parts = value.slice(marker + 1).split('&');
  if (!parts.length || parts.length > 2) return null;
  const seen = new Set();
  for (const part of parts) {
    const match = /^(v|t)=([^=]+)$/.exec(part);
    if (!match || seen.has(match[1])) return null;
    const [ , key, text] = match;
    if (key === 'v' ? text.length > 32 || !/^\d+(?:\.\d+)*$/.test(text) : !/^\d{1,16}$/.test(text)) return null;
    seen.add(key);
  }
  return value.slice(0, marker);
}

function send(res, code, value, html = false, {corsOrigin, frameOrigins} = {}) {
  const bytes = code === 204 ? Buffer.alloc(0) : Buffer.from(html ? value : JSON.stringify(value));
  res.writeHead(code, {'Content-Type': html ? 'text/html; charset=utf-8' : 'application/json; charset=utf-8',
    'Content-Length': bytes.length, 'Cache-Control': 'no-store', 'Referrer-Policy': 'same-origin',
    'X-Content-Type-Options': 'nosniff', 'Connection': 'close',
    'Content-Security-Policy': `default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; object-src 'self'; frame-ancestors ${frameOrigins?.length ? frameOrigins.join(' ') : "'none'"}; base-uri 'none'; form-action 'none'`,
    ...(corsOrigin === undefined ? {} : {'Access-Control-Allow-Origin': corsOrigin, 'Vary': 'Origin',
      'Access-Control-Allow-Methods': 'GET, OPTIONS', 'Access-Control-Allow-Headers': 'Content-Type, Accept', 'Access-Control-Max-Age': '0'})});
  res.end(bytes);
}

function admitted(req, host) {
  const names = new Set();
  for (let i = 0; i < req.rawHeaders.length; i += 2) {
    const name = req.rawHeaders[i].toLowerCase();
    if (names.has(name)) reject();
    names.add(name);
  }
  if (req.headers.host !== host || typeof req.url !== 'string' || req.url.length > 256) reject();
}

async function report(req) {
  if (req.headers['content-type'] !== 'application/json' || req.headers['content-encoding'] !== undefined) reject();
  let length = 0;
  const chunks = [];
  for await (const chunk of req) {
    length += chunk.length;
    if (length > 16384) reject();
    chunks.push(chunk);
  }
  const data = JSON.parse(Buffer.concat(chunks).toString('utf8'));
  if (!data || Array.isArray(data) || typeof data !== 'object' || data.v !== 1 ||
      !['ready', 'key', 'heartbeat'].includes(data.event) ||
      !Number.isSafeInteger(data.sequence) || data.sequence < 0 ||
      !Number.isSafeInteger(data.uptimeSeconds) || data.uptimeSeconds < 0 ||
      Object.keys(data).some(key => !['v', 'event', 'sequence', 'uptimeSeconds', 'key', 'bridges', 'smartTvSurface', 'compatibility', 'socketDiagnostics', 'csManagerSource', 'hbbtvObject', 'csManagerAttempt', 'probeContext', 'revision', 'remoteReload', 'remoteCommands', 'commandResult'].includes(key)) ||
      (data.event === 'key' ? !KEYS.has(data.key) : data.key !== undefined)) reject();
  if (data.bridges !== undefined && (data.event !== 'ready' || !data.bridges ||
      Array.isArray(data.bridges) || typeof data.bridges !== 'object' ||
      Object.keys(data.bridges).length !== BRIDGES.length ||
      BRIDGES.some(key => typeof data.bridges[key] !== 'boolean'))) reject();
  if (data.smartTvSurface !== undefined && (data.event !== 'ready' || !surfaceOK(data.smartTvSurface))) reject();
  if (data.compatibility !== undefined && (data.event !== 'ready' || !compatibilityOK(data.compatibility))) reject();
  if (data.socketDiagnostics !== undefined && (data.event !== 'ready' || !socketDiagnosticsOK(data.socketDiagnostics))) reject();
  if (data.csManagerSource !== undefined && (data.event !== 'ready' || !csManagerSourceOK(data.csManagerSource))) reject();
  if (data.hbbtvObject !== undefined && (data.event !== 'ready' || !hbbtvObjectOK(data.hbbtvObject))) reject();
  if (data.csManagerAttempt !== undefined && (data.event !== 'ready' || !csManagerAttemptOK(data.csManagerAttempt))) reject();
  if (data.probeContext !== undefined && (data.event !== 'ready' || !probeContextOK(data.probeContext))) reject();
  if (data.revision !== undefined && (typeof data.revision !== 'string' || !/^[a-f0-9]{64}$/.test(data.revision))) reject();
  if (data.remoteReload !== undefined && (!['ready', 'heartbeat'].includes(data.event) || data.remoteReload !== true || data.revision === undefined)) reject();
  if (data.remoteCommands !== undefined && (!['ready', 'heartbeat'].includes(data.event) || data.remoteCommands !== true || data.revision === undefined)) reject();
  if (data.commandResult !== undefined && (data.remoteCommands !== true || !commandResultOK(data.commandResult))) reject();
  return data;
}

function listen(server, host, port) {
  server.requestTimeout = 3000;
  server.headersTimeout = 3000;
  server.maxConnections = 8;
  return new Promise((resolve, rejectListen) => {
    server.once('error', rejectListen);
    server.listen(port, host, () => { server.removeListener('error', rejectListen); resolve(); });
  });
}
const shutdown = server => new Promise(resolve => { server.close(resolve); server.closeAllConnections(); });

// Imports never open a listener. Loopback targets are an explicit fixture-only option.
export async function createHelloServer({host, tvHost, port = 4180, allowLoopback = false,
  receiverToken = secret(), msx = false, msxFrameOrigins = MSX_ORIGINS, msxAllowNullOrigin = false, activeDiagnostics = false,
  indexPath = new URL('src/tv-hello/index.html', ROOT)} = {}) {
  const addressOK = value => privateIPv4(value) || (allowLoopback && value === '127.0.0.1');
  if (!addressOK(host) || !addressOK(tvHost) || !Number.isInteger(port) ||
      port < (allowLoopback ? 0 : 1) || port > 65535) throw new Error('INVALID_CONFIG');
  if (typeof receiverToken !== 'string' || !/^[A-Za-z0-9_-]{32}$/.test(receiverToken)) throw new Error('INVALID_CONFIG');
  if (typeof msx !== 'boolean' || typeof msxAllowNullOrigin !== 'boolean' || typeof activeDiagnostics !== 'boolean' || !Array.isArray(msxFrameOrigins) ||
      msxFrameOrigins.length > 8 || msxFrameOrigins.some(value => canonicalOrigin(value) === null)) throw new Error('INVALID_CONFIG');
  msxFrameOrigins = [...new Set(msxFrameOrigins)];
  let assetRevision;
  const loadPage = async () => {
    const page = await readFile(indexPath, 'utf8');
    if (Buffer.byteLength(page) > 128 * 1024) throw new Error('PAGE_TOO_LARGE');
    const prepared = page.replaceAll('__TV_PROBE_DIAGNOSTICS__', activeDiagnostics && !msx ? 'active' : 'passive');
    assetRevision = createHash('sha256').update(prepared).digest('hex');
    return prepared.replaceAll('__TV_PROBE_REVISION__', assetRevision);
  };
  await loadPage();
  const prefix = `/${receiverToken}/`, statusPath = `/${secret()}/status`;
  const receiverPath = msx ? `${prefix}msx-probe.html` : prefix;
  const msxBootstrap = {enabled: msx, requests: 0, gets: 0, options: 0, accepted: 0, rejected: 0,
    clientErrors: 0, tlsClientErrors: 0, activeConnections: 0, completedConnections: 0, zeroByteConnections: 0,
    idleTimeoutConnections: 0, receivedBytes: 0, lastClientError: null,
    lastRoute: null, lastOriginClass: null, lastOrigin: null, lastStatus: null};
  const counts = {get: 0, ready: 0, key: 0, heartbeat: 0, connections: 0, unexpectedPeerConnections: 0, rejected: 0,
    commandsQueued: 0, commandsAcknowledged: 0};
  let lastSequence = null, uptimeSeconds = null, lastKey = null, bridges = null, smartTvSurface = null, compatibility = null, socketDiagnostics = null, csManagerSource = null, hbbtvObject = null, csManagerAttempt = null, probeContext = null, origin, controlOrigin, closed;
  let remoteReload = false, remoteCommands = false, loadedRevision = null, reloadPending = false;
  let pendingCommand = null, lastCommandResult = null;
  const status = () => ({...counts, lastSequence, uptimeSeconds, lastKey,
    bridges: bridges && {...bridges}, smartTvSurface: smartTvSurface && smartTvSurface.map(entry => ({...entry})),
    compatibility: compatibility && structuredClone(compatibility),
    socketDiagnostics: socketDiagnostics && structuredClone(socketDiagnostics),
    csManagerSource: csManagerSource && structuredClone(csManagerSource),
    hbbtvObject: hbbtvObject && {...hbbtvObject},
    csManagerAttempt: csManagerAttempt && structuredClone(csManagerAttempt),
    probeContext: probeContext && {...probeContext}, msxBootstrap: {...msxBootstrap},
    remoteReload, remoteCommands, loadedRevision, assetRevision, reloadPending,
    pendingCommand: pendingCommand && {id: pendingCommand.id, method: pendingCommand.method},
    lastCommandResult: lastCommandResult && {...lastCommandResult}});
  const server = http.createServer(async (req, res) => {
    try {
      admitted(req, new URL(origin).host);
      const peer = req.socket.remoteAddress;
      if (peer !== tvHost && !(allowLoopback && loopback(peer))) reject();
      const cachePath = msx ? msxCachePath(req.url) : null;
      const msxRoute = cachePath === '/msx/start.json' ? 'start' : cachePath === `${prefix}msx-home.json` ? 'home' : null;
      if (msx && msxRoute) {
        const supplied = req.headers.origin;
        const parsed = canonicalOrigin(supplied);
        const originClass = supplied === undefined ? 'missing' : supplied === 'null' ? 'opaque' :
          parsed === null ? 'invalid' : msxFrameOrigins.includes(parsed) ? 'trusted' : 'other';
        const allowed = originClass === 'missing' || originClass === 'trusted' || (originClass === 'opaque' && msxAllowNullOrigin);
        msxBootstrap.requests++; if (req.method === 'GET') msxBootstrap.gets++; if (req.method === 'OPTIONS') msxBootstrap.options++;
        Object.assign(msxBootstrap, {lastRoute: msxRoute, lastOriginClass: originClass, lastOrigin: supplied === 'null' ? 'null' : parsed});
        const preflightHeaders = (req.headers['access-control-request-headers'] || '').toLowerCase().split(',').map(value => value.trim()).filter(Boolean);
        const preflightOK = req.method === 'OPTIONS' && supplied !== undefined && req.headers['access-control-request-method'] === 'GET' &&
          preflightHeaders.length <= 2 && preflightHeaders.every(value => ['content-type', 'accept'].includes(value));
        if (!allowed || (req.method !== 'GET' && !preflightOK)) {
          msxBootstrap.rejected++; msxBootstrap.lastStatus = 403; reject();
        }
        msxBootstrap.accepted++; msxBootstrap.lastStatus = req.method === 'OPTIONS' ? 204 : 200;
        const cors = supplied === undefined ? {} : {corsOrigin: supplied};
        if (req.method === 'OPTIONS') { send(res, 204, null, false, cors); return; }
        const value = msxRoute === 'start' ? {name: 'Philips API diagnostic', version: '1.0.0', parameter: `content:${origin}${prefix}msx-home.json`} :
          {type: 'pages', headline: 'Philips API diagnostic', template: {type: 'separate', layout: '0,0,12,4', icon: 'play-circle-outline'},
            items: [{title: 'Open API diagnostic', action: `video:plugin:${origin}${receiverPath}`}]};
        send(res, 200, value, false, cors); return;
      }
      if (msx && req.method === 'GET' && req.url === receiverPath) {
        const page = await loadPage(); counts.get++; send(res, 200, page, true, {frameOrigins: msxFrameOrigins}); return;
      }
      if (req.method === 'GET' && [prefix, `${prefix}index.html`].includes(req.url)) {
        const page = await loadPage(); counts.get++; send(res, 200, page, true); return;
      }
      if (req.method !== 'POST' || req.url !== `${prefix}report`) reject();
      const sameOrigin = req.headers.origin === origin || (req.headers.origin === undefined &&
        [origin + prefix, origin + prefix + 'index.html', ...(msx ? [origin + receiverPath] : [])].includes(req.headers.referer));
      if (!sameOrigin) reject();
      const data = await report(req);
      await loadPage();
      const result = data.commandResult;
      const matches = command => command && command.id === result?.id && command.method === result?.method;
      const acknowledgesPending = result && matches(pendingCommand);
      if (result && !acknowledgesPending && !matches(lastCommandResult)) reject();
      const nextRemoteReload = data.event === 'ready' ? data.remoteReload === true : remoteReload || data.remoteReload === true;
      const nextRemoteCommands = data.event === 'ready' ? data.remoteCommands === true : remoteCommands || data.remoteCommands === true;
      if (nextRemoteReload && data.revision === undefined) reject();
      remoteReload = nextRemoteReload;
      remoteCommands = nextRemoteCommands;
      counts[data.event]++; lastSequence = data.sequence; uptimeSeconds = data.uptimeSeconds;
      if (data.event === 'ready') bridges = data.bridges ? {...data.bridges} : null;
      if (data.event === 'ready') smartTvSurface = data.smartTvSurface ? data.smartTvSurface.map(entry => ({...entry})) : null;
      if (data.event === 'ready') compatibility = data.compatibility ? structuredClone(data.compatibility) : null;
      if (data.event === 'ready') socketDiagnostics = data.socketDiagnostics ? structuredClone(data.socketDiagnostics) : null;
      if (data.event === 'ready') csManagerSource = data.csManagerSource ? structuredClone(data.csManagerSource) : null;
      if (data.event === 'ready') hbbtvObject = data.hbbtvObject ? {...data.hbbtvObject} : null;
      if (data.event === 'ready') csManagerAttempt = data.csManagerAttempt ? structuredClone(data.csManagerAttempt) : null;
      if (data.event === 'ready') probeContext = data.probeContext ? {...data.probeContext} : null;
      if (data.event === 'key') lastKey = data.key;
      if (data.revision !== undefined) loadedRevision = data.revision;
      if (acknowledgesPending) {
        lastCommandResult = {...result}; pendingCommand = null; counts.commandsAcknowledged++;
      }
      const reload = remoteReload && (reloadPending || data.revision !== assetRevision);
      if (reload) reloadPending = false;
      send(res, 200, {ok: true, revision: assetRevision, reload,
        ...(!reload && data.remoteCommands === true && pendingCommand ? {command: {...pendingCommand}} : {})});
    } catch { counts.rejected++; if (!res.headersSent && !res.destroyed) send(res, 403, {error: 'REQUEST_REJECTED'}); }
  });
  server.on('connection', socket => {
    counts.connections++;
    if (socket.remoteAddress !== tvHost && !(allowLoopback && loopback(socket.remoteAddress))) counts.unexpectedPeerConnections++;
    else if (msx) {
      msxBootstrap.activeConnections++;
      let timedOut = false;
      socket.setTimeout(5000, () => {
        // Node's HTTP timeout listener may destroy the socket before this
        // listener runs; the timeout event still needs its diagnostic receipt.
        if (timedOut) return;
        timedOut = true;
        msxBootstrap.idleTimeoutConnections++; msxBootstrap.lastClientError = 'idle-timeout'; socket.destroy();
      });
      socket.once('close', () => {
        msxBootstrap.activeConnections--; msxBootstrap.completedConnections++;
        if (socket.bytesRead === 0) msxBootstrap.zeroByteConnections++;
        msxBootstrap.receivedBytes = Math.min(16 * 1024 * 1024,
          msxBootstrap.receivedBytes + Math.min(socket.bytesRead, 1024 * 1024));
      });
    }
  });
  server.on('clientError', (error, socket) => {
    if (msx && (socket.remoteAddress === tvHost || (allowLoopback && loopback(socket.remoteAddress)))) {
      msxBootstrap.clientErrors++;
      msxBootstrap.lastClientError = error.code === 'ERR_HTTP_REQUEST_TIMEOUT' ? 'request-timeout' :
        error.code === 'HPE_INVALID_METHOD' ? 'invalid-method' :
        ['HPE_INVALID_HEADER_TOKEN', 'HPE_HEADER_OVERFLOW', 'HPE_INVALID_CONTENT_LENGTH', 'HPE_UNEXPECTED_CONTENT_LENGTH',
          'HPE_INVALID_TRANSFER_ENCODING', 'HPE_INVALID_VERSION'].includes(error.code) ? 'invalid-header' : 'other';
      if (error.rawPacket?.[0] === 0x16 && error.rawPacket?.[1] === 0x03) msxBootstrap.tlsClientErrors++;
    }
    socket.destroy();
  });
  const control = http.createServer((req, res) => {
    try {
      admitted(req, new URL(controlOrigin).host);
      if (!loopback(req.socket.remoteAddress) ||
          (req.headers.origin !== undefined && req.headers.origin !== controlOrigin)) reject();
      if (req.method === 'GET' && req.url === statusPath) { send(res, 200, status()); return; }
      if (req.method !== 'POST' ||
          req.headers['transfer-encoding'] !== undefined || ![undefined, '0'].includes(req.headers['content-length'])) reject();
      const method = [...COMMAND_METHODS].find(method => req.url === statusPath.replace(/status$/, `command/${method}`));
      if (method) {
        if (!remoteCommands) { send(res, 409, {error: 'REMOTE_COMMANDS_UNAVAILABLE'}); return; }
        if (pendingCommand) { send(res, 409, {error: 'COMMAND_PENDING'}); return; }
        pendingCommand = {id: secret(), method, ...(method === 'set-label' ? {value: COMMAND_LABEL} : {})};
        counts.commandsQueued++;
        send(res, 200, {queued: true, completed: false, id: pendingCommand.id, method}); return;
      }
      if (req.url !== statusPath.replace(/status$/, 'reload')) reject();
      if (!remoteReload) { send(res, 409, {error: 'REMOTE_RELOAD_UNAVAILABLE'}); return; }
      reloadPending = true;
      send(res, 200, {queued: true, pageReloadVerified: false});
    } catch { send(res, 403, {error: 'REQUEST_REJECTED'}); }
  });
  try {
    await listen(control, '127.0.0.1', 0);
    controlOrigin = `http://127.0.0.1:${control.address().port}`;
    await listen(server, host, port);
    origin = `http://${host}:${server.address().port}`;
  } catch (error) { await Promise.all([shutdown(server), shutdown(control)]); throw error; }
  return {receiverUrl: origin + receiverPath, statusUrl: controlOrigin + statusPath, status,
    close: () => closed ??= Promise.all([shutdown(server), shutdown(control)]).then(() => undefined)};
}

async function main() {
  if (['status', 'reload', 'ping', 'label'].includes(process.argv[2])) {
    if (process.argv.length !== 3) throw new Error('USAGE');
    const session = JSON.parse(await readFile(SESSION, 'utf8'));
    const url = new URL(session.statusUrl);
    if (url.protocol !== 'http:' || url.hostname !== '127.0.0.1' || !url.port || url.username ||
        url.password || url.search || url.hash || !/^\/[A-Za-z0-9_-]{32}\/status$/.test(url.pathname)) reject();
    const action = process.argv[2];
    const method = action === 'label' ? 'set-label' : action;
    if (action !== 'status') url.pathname = url.pathname.replace(/status$/, action === 'reload' ? 'reload' : `command/${method}`);
    const response = await fetch(url,
      {method: action === 'status' ? 'GET' : 'POST', redirect: 'error', signal: AbortSignal.timeout(2000)});
    if (!response.ok) reject();
    const data = await response.json();
    if (action === 'reload') {
      if (data.queued !== true || data.pageReloadVerified !== false) reject();
      console.log(JSON.stringify({queued: true, pageReloadVerified: false})); return;
    }
    if (COMMAND_METHODS.has(method)) {
      if (data.queued !== true || data.completed !== false || data.method !== method || !commandIdentityOK({id: data.id, method: data.method})) reject();
      console.log(JSON.stringify({queued: true, completed: false, id: data.id, method: data.method})); return;
    }
    const safe = {};
    for (const key of ['get', 'ready', 'key', 'heartbeat', 'connections', 'unexpectedPeerConnections', 'rejected', 'lastSequence', 'uptimeSeconds', 'commandsQueued', 'commandsAcknowledged']) {
      if (data[key] !== null && (!Number.isSafeInteger(data[key]) || data[key] < 0)) reject();
      safe[key] = data[key];
    }
    if (data.lastKey !== null && !KEYS.has(data.lastKey)) reject();
    if (data.bridges !== null && (!data.bridges || typeof data.bridges !== 'object' ||
        BRIDGES.some(key => typeof data.bridges[key] !== 'boolean') ||
        Object.keys(data.bridges).length !== BRIDGES.length)) reject();
    if (!surfaceOK(data.smartTvSurface)) reject();
    if (data.compatibility !== null && !compatibilityOK(data.compatibility)) reject();
    if (data.socketDiagnostics !== null && !socketDiagnosticsOK(data.socketDiagnostics)) reject();
    if (data.csManagerSource !== null && !csManagerSourceOK(data.csManagerSource)) reject();
    if (data.hbbtvObject !== null && !hbbtvObjectOK(data.hbbtvObject)) reject();
    if (data.csManagerAttempt !== null && !csManagerAttemptOK(data.csManagerAttempt)) reject();
    if (data.probeContext !== null && !probeContextOK(data.probeContext)) reject();
    if (!msxStatusOK(data.msxBootstrap)) reject();
    if (typeof data.remoteReload !== 'boolean' || typeof data.remoteCommands !== 'boolean' || typeof data.reloadPending !== 'boolean' ||
        !/^[a-f0-9]{64}$/.test(data.assetRevision) ||
        (data.loadedRevision !== null && !/^[a-f0-9]{64}$/.test(data.loadedRevision))) reject();
    if ((data.pendingCommand !== null && !commandIdentityOK(data.pendingCommand)) ||
        (data.lastCommandResult !== null && !commandResultOK(data.lastCommandResult))) reject();
    console.log(JSON.stringify({...safe, lastKey: data.lastKey,
      bridges: data.bridges && Object.fromEntries(BRIDGES.map(key => [key, data.bridges[key]])),
      smartTvSurface: data.smartTvSurface && data.smartTvSurface.map(({name, kind, level, nativeCode}) => ({name, kind, level, nativeCode})),
      compatibility: data.compatibility,
      socketDiagnostics: data.socketDiagnostics,
      csManagerSource: data.csManagerSource && {v: 1, totalLength: data.csManagerSource.totalLength,
        complete: data.csManagerSource.complete, nativeMarker: data.csManagerSource.nativeMarker,
        sourceLength: data.csManagerSource.source.length, prototype: data.csManagerSource.prototype.map(({name, kind}) => ({name, kind}))},
      hbbtvObject: data.hbbtvObject && {...data.hbbtvObject},
      csManagerAttempt: data.csManagerAttempt && structuredClone(data.csManagerAttempt),
      probeContext: data.probeContext && {...data.probeContext}, msxBootstrap: {...data.msxBootstrap},
      remoteReload: data.remoteReload, remoteCommands: data.remoteCommands, loadedRevision: data.loadedRevision, assetRevision: data.assetRevision, reloadPending: data.reloadPending,
      pendingCommand: data.pendingCommand, lastCommandResult: data.lastCommandResult}));
    return;
  }
  if (process.argv.length !== 2) throw new Error('USAGE');
  const config = JSON.parse(await readFile(CONFIG, 'utf8'));
  if (config.mode !== undefined && !['hello', 'bridge-probe', 'msx-probe'].includes(config.mode)) throw new Error('INVALID_CONFIG');
  await mkdir(new URL('.local/tv-hello/', ROOT), {recursive: true, mode: 0o700});
  const file = await open(SESSION, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW, 0o600);
  const identity = await file.stat();
  let running, closing;
  const cleanup = () => closing ??= (async () => {
    await running?.close(); await file.close();
    const current = await stat(SESSION).catch(() => null);
    if (current?.ino === identity.ino && current?.dev === identity.dev) await unlink(SESSION);
  })();
  try {
    running = await createHelloServer({host: config.host, tvHost: config.tvHost, port: config.port ?? 4180,
      receiverToken: config.receiverToken,
      msx: config.mode === 'msx-probe', msxFrameOrigins: config.msxFrameOrigins, msxAllowNullOrigin: config.msxAllowNullOrigin,
      activeDiagnostics: config.activeDiagnostics,
      indexPath: new URL(['bridge-probe', 'msx-probe'].includes(config.mode) ? 'src/tv-bridge-probe/index.html' : 'src/tv-hello/index.html', ROOT)});
    await file.writeFile(JSON.stringify({receiverUrl: running.receiverUrl, statusUrl: running.statusUrl, pid: process.pid}));
    await file.sync();
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => cleanup().catch(() => { process.exitCode = 1; }));
    console.log('TV hello server started; private session saved.');
  } catch (error) { await cleanup(); throw error; }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch(error => {
    console.error(error.code === 'EEXIST' ? 'Session exists; preserve or rename it before restarting.' : 'TV hello server failed.');
    process.exitCode = 1;
  });
}
