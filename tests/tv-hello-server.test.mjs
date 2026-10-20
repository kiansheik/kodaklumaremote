import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {mkdtemp, rm, writeFile} from 'node:fs/promises';
import http from 'node:http';
import net from 'node:net';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {createHelloServer} from '../scripts/tv-hello-server.mjs';

const TOKEN = 'a'.repeat(32);
const PAGE = '<!doctype html><title>Fixture</title><script>window.revision="__TV_PROBE_REVISION__";</script>';
const digest = value => createHash('sha256').update(value).digest('hex');
const bridges = {loadApp: false, enableDevtools: false, smartTvApi: true, oipfFactory: true};
const surface = [{name: 'fixtureMethod', kind: 'function', level: 1, nativeCode: true}];
const event = (name, extra = {}) => ({v: 1, event: name, sequence: 1, uptimeSeconds: 0, ...extra});

function request(url, {method = 'GET', json, raw, headers = {}} = {}) {
  const body = json === undefined ? raw : JSON.stringify(json);
  return new Promise((resolve, reject) => {
    const req = http.request(url, {method, headers: {...(body === undefined ? {} : {
      'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body)}), ...headers}}, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('error', reject);
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let body;
        try { body = JSON.parse(text); } catch { body = text; }
        resolve({status: res.statusCode, body, headers: res.headers});
      });
    });
    req.setTimeout(2000, () => req.destroy(new Error('Fixture request timed out')));
    req.on('error', reject);
    req.end(body);
  });
}

const report = (server, value, extra = {}) => request(new URL('report', server.receiverUrl), {
  method: 'POST', json: value, headers: {Origin: new URL(server.receiverUrl).origin}, ...extra});
const reload = (server, options = {}) => request(server.statusUrl.replace(/status$/, 'reload'), {method: 'POST', ...options});
const command = (server, method, options = {}) => request(server.statusUrl.replace(/status$/, `command/${method}`), {method: 'POST', ...options});
const commandReport = (server, extra = {}) => report(server, event('heartbeat', {
  revision: digest(PAGE), remoteCommands: true, ...extra}));

async function fixture(t, options = {}) {
  const directory = await mkdtemp(path.join(tmpdir(), 'tv-hello-fixture-'));
  const indexPath = path.join(directory, 'index.html');
  const servers = [];
  t.after(async () => {
    await Promise.all(servers.map(server => server.close()));
    await rm(directory, {recursive: true, force: true});
  });
  await writeFile(indexPath, PAGE);
  const start = async (options = {}) => {
    const server = await createHelloServer({host: '127.0.0.1', tvHost: '127.0.0.1', port: 0,
      allowLoopback: true, receiverToken: TOKEN, indexPath, ...options});
    servers.push(server);
    return server;
  };
  return {server: await start(options), start, indexPath};
}

test('remote reload requires an announced capability and authenticated loopback command, then ACKs once', async t => {
  const listen = http.Server.prototype.listen;
  http.Server.prototype.listen = () => assert.fail('Import must not start a listener');
  try { await import('../scripts/tv-hello-server.mjs?import-only-fixture'); }
  finally { http.Server.prototype.listen = listen; }
  const {server} = await fixture(t);
  assert.equal((await reload(server)).status, 409);
  const revision = digest(PAGE);
  const ready = await report(server, event('ready', {revision, remoteReload: true, bridges, smartTvSurface: surface}));
  assert.deepEqual(ready.body, {ok: true, revision, reload: false});
  assert.equal((await reload(server, {headers: {Origin: 'https://attacker.example'}})).status, 403);
  const wrong = new URL(server.statusUrl); wrong.pathname = '/' + 'b'.repeat(32) + '/reload';
  assert.equal((await request(wrong, {method: 'POST'})).status, 403);
  assert.equal((await reload(server, {json: {reload: true}})).status, 403);
  assert.deepEqual((await reload(server)).body, {queued: true, pageReloadVerified: false});
  assert.equal(server.status().reloadPending, true);
  assert.equal((await report(server, event('heartbeat', {revision, remoteReload: true}))).body.reload, true);
  assert.equal(server.status().reloadPending, false);
  assert.equal((await report(server, event('heartbeat', {revision, remoteReload: true}))).body.reload, false);
  const status = (await request(server.statusUrl)).body;
  assert.deepEqual(status.bridges, bridges);
  assert.deepEqual(status.smartTvSurface, surface);
});

test('an existing document heartbeat reannounces reload capability after a same-URL server restart', async t => {
  const f = await fixture(t);
  const revision = digest(PAGE);
  await report(f.server, event('ready', {revision, remoteReload: true}));
  const port = Number(new URL(f.server.receiverUrl).port);
  await f.server.close();
  const restarted = await f.start({port});
  assert.equal(restarted.receiverUrl, f.server.receiverUrl);
  assert.equal(restarted.status().remoteReload, false);
  assert.equal((await reload(restarted)).status, 409);
  const response = await report(restarted, event('heartbeat', {sequence: 42, uptimeSeconds: 410, revision, remoteReload: true}));
  assert.deepEqual(response.body, {ok: true, revision, reload: false});
  assert.equal(restarted.status().ready, 0);
  assert.equal(restarted.status().heartbeat, 1);
  assert.equal(restarted.status().remoteReload, true);
  assert.equal((await reload(restarted)).status, 200);
  assert.equal((await report(restarted, event('heartbeat', {sequence: 43, uptimeSeconds: 420, revision, remoteReload: true}))).body.reload, true);
});

test('a source-file change requests reload and the next page GET injects the matching revision', async t => {
  const {server, indexPath} = await fixture(t);
  const oldRevision = digest(PAGE);
  const first = await request(server.receiverUrl);
  assert.equal(first.body, PAGE.replaceAll('__TV_PROBE_REVISION__', oldRevision));
  await report(server, event('ready', {revision: oldRevision, remoteReload: true}));
  const changed = PAGE.replace('Fixture', 'Changed fixture');
  await writeFile(indexPath, changed);
  const revision = digest(changed);
  assert.deepEqual((await report(server, event('heartbeat', {revision: oldRevision, remoteReload: true}))).body,
    {ok: true, revision, reload: true});
  assert.equal((await request(server.receiverUrl + 'index.html')).body, changed.replaceAll('__TV_PROBE_REVISION__', revision));
  assert.equal((await report(server, event('ready', {revision, remoteReload: true}))).body.reload, false);
  assert.equal(server.status().loadedRevision, revision);
});

test('ready-only bridge and surface metadata reject heartbeat extras and malformed bounded surfaces', async t => {
  const {server} = await fixture(t);
  const invalid = [event('heartbeat', {bridges}), event('heartbeat', {smartTvSurface: surface}),
    event('ready', {bridges: {...bridges, extra: false}}), event('ready', {bridges: {...bridges, loadApp: 'false'}}),
    event('ready', {smartTvSurface: Array.from({length: 49}, () => surface[0])}),
    ...[{name: 'private URL'}, {name: 'a'.repeat(65)}, {kind: 'getter'}, {level: 3}, {nativeCode: 1}, {extra: 'hidden'}]
      .map(extra => event('ready', {smartTvSurface: [{...surface[0], ...extra}]}))];
  for (const value of invalid) assert.equal((await report(server, value)).status, 403);
  assert.equal(server.status().ready, 0);
  assert.equal(server.status().heartbeat, 0);
  assert.equal((await report(server, event('ready', {bridges, smartTvSurface: surface}))).status, 200);
  assert.deepEqual(server.status().smartTvSurface, surface);
});

test('malformed, extra-field and oversized reports never advance receiver state', async t => {
  const {server} = await fixture(t);
  for (const value of [event('ready', {extra: 'private'}), event('ready', {revision: 'bad'}),
    event('heartbeat', {remoteReload: true}), event('key', {key: 'power'}), event('ready', {sequence: -1})]) {
    assert.equal((await report(server, value)).status, 403);
  }
  assert.equal((await report(server, undefined, {raw: '{invalid'})).status, 403);
  const oversized = await report(server, undefined, {raw: JSON.stringify(event('ready', {padding: 'x'.repeat(16384)}))})
    .catch(error => ({status: 0, code: error.code}));
  assert.ok(oversized.status === 403 || (oversized.status === 0 && ['ECONNRESET', 'EPIPE'].includes(oversized.code)));
  assert.equal(server.status().ready + server.status().heartbeat + server.status().key, 0);
  assert.equal(server.status().lastSequence, null);
  assert.equal((await report(server, event('ready'))).status, 200);
});

test('complete compatibility profiles are ready-only, preserve media order and exclude raw user agents', async t => {
  const {server} = await fixture(t);
  const compatibility = {
    chromeVersion: '70.0.3538.110', chromiumVersion: null, appleWebKitVersion: '537.36', firefoxVersion: null, browserVersion: '1.2',
    operaVersion: null, omiVersion: null, titanOsVersion: null, nettvVersion: null, smartTvAllianceVersion: null,
    viewportWidth: 1280, viewportHeight: 720, screenWidth: 1920, screenHeight: 1080, platform: 'Linux armv7l', devicePixelRatio: 1.5,
    secureContext: false, crossOriginIsolated: null, fetch: true, promise: true, webAssembly: false, webSocket: true,
    mediaSource: true, eme: false, webCodecs: false, webGL: true, webGL2: false, serviceWorker: false,
    cryptoSubtle: false, localStorage: true, indexedDB: true,
    tcpServerSocket: false, tcpSocket: false, udpSocket: false, webTransport: false, rtcPeerConnection: true,
    media: ['h264Baseline', 'h264High', 'hevc', 'vp8', 'vp9', 'av1', 'hls', 'dash', 'aac', 'opus', 'ac3', 'eac3']
      .map((id, index) => ({id, canPlayType: index < 2 ? 'probably' : '', mse: index < 2 ? true : null}))
  };
  assert.equal((await report(server, event('ready', {compatibility}))).status, 200);
  assert.deepEqual(server.status().compatibility, compatibility);
  assert.deepEqual((await request(server.statusUrl)).body.compatibility, compatibility);
  const invalid = [event('ready', {compatibility: {...compatibility, rawUserAgent: 'Fixture/1.0'}}),
    event('ready', {compatibility: {...compatibility, media: [...compatibility.media].reverse()}}),
    event('ready', {compatibility: {...compatibility, media: compatibility.media.slice(1)}}),
    event('ready', {compatibility: {...compatibility, media: compatibility.media.map((entry, index) =>
      index === 0 ? {...entry, canPlayType: 'yes'} : entry)}}),
    event('ready', {compatibility: {...compatibility, media: compatibility.media.map((entry, index) =>
      index === 0 ? {...entry, extra: 'private'} : entry)}}),
    event('heartbeat', {compatibility})];
  for (const value of invalid) assert.equal((await report(server, value)).status, 403);
  assert.deepEqual(server.status().compatibility, compatibility);
  assert.equal(server.status().ready, 1);
  assert.equal(server.status().heartbeat, 0);
});

test('fixed commands require receiver capability and authenticated empty-body loopback requests', async t => {
  const {server} = await fixture(t);
  assert.equal((await command(server, 'ping')).status, 409);
  assert.equal((await report(server, event('ready', {remoteCommands: true}))).status, 403);
  await commandReport(server);
  assert.equal(server.status().remoteCommands, true);
  for (const options of [{headers: {Origin: 'https://attacker.example'}}, {headers: {Host: 'attacker.example'}},
    {json: {value: 'arbitrary'}}, {method: 'GET'}]) {
    assert.equal((await command(server, 'ping', options)).status, 403);
  }
  assert.equal((await command(server, 'eval')).status, 403);
  const wrong = new URL(server.statusUrl); wrong.pathname = '/' + 'b'.repeat(32) + '/command/ping';
  assert.equal((await request(wrong, {method: 'POST'})).status, 403);
  const queued = await command(server, 'ping');
  assert.equal(queued.status, 200);
  assert.equal(queued.body.queued, true);
  assert.equal(queued.body.completed, false);
  assert.equal(queued.body.method, 'ping');
  assert.match(queued.body.id, /^[A-Za-z0-9_-]{32}$/);
  assert.equal((await command(server, 'set-label')).status, 409);
  const expected = {id: queued.body.id, method: 'ping'};
  assert.deepEqual((await commandReport(server)).body.command, expected);
  assert.deepEqual((await commandReport(server)).body.command, expected);
  assert.deepEqual(server.status().pendingCommand, expected);
  assert.equal(server.status().commandsQueued, 1);
  assert.equal(server.status().commandsAcknowledged, 0);
});

test('only matching exact receipts complete a command; concurrent duplicates cannot clear the next command', async t => {
  const {server} = await fixture(t);
  await commandReport(server);
  const queued = (await command(server, 'ping')).body;
  const receipt = {id: queued.id, method: 'ping', ok: true};
  for (const commandResult of [{...receipt, id: 'z'.repeat(32)}, {...receipt, method: 'set-label'},
    {...receipt, ok: false}, {...receipt, extra: 'private'}, {...receipt, id: [queued.id]}]) {
    assert.equal((await commandReport(server, {commandResult})).status, 403);
  }
  assert.equal((await report(server, event('heartbeat', {commandResult: receipt}))).status, 403);
  assert.equal(server.status().commandsAcknowledged, 0);
  assert.equal(server.status().pendingCommand.id, queued.id);
  const responses = await Promise.all(Array.from({length: 4}, () => commandReport(server, {commandResult: receipt})));
  assert.ok(responses.every(response => response.status === 200 && response.body.command === undefined));
  assert.equal(server.status().commandsAcknowledged, 1);
  assert.equal(server.status().pendingCommand, null);
  assert.deepEqual(server.status().lastCommandResult, receipt);
  const next = (await command(server, 'set-label')).body;
  const repeated = await commandReport(server, {commandResult: receipt});
  assert.deepEqual(repeated.body.command, {id: next.id, method: 'set-label', value: 'Controlled remotely'});
  assert.equal(server.status().pendingCommand.id, next.id);
  assert.equal(server.status().commandsAcknowledged, 1);
  const labelReceipt = {id: next.id, method: 'set-label', ok: true};
  assert.equal((await commandReport(server, {commandResult: labelReceipt})).status, 200);
  const status = (await request(server.statusUrl)).body;
  assert.deepEqual(status.lastCommandResult, labelReceipt);
  assert.equal(status.commandsAcknowledged, 2);
  assert.equal(JSON.stringify(status).includes('Controlled remotely'), false);
});

test('source reload retains the pending command and restart requires a fresh capability announcement', async t => {
  const f = await fixture(t);
  await commandReport(f.server, {remoteReload: true});
  const queued = (await command(f.server, 'ping')).body;
  const changed = PAGE.replace('Fixture', 'Changed fixture');
  await writeFile(f.indexPath, changed);
  const response = await commandReport(f.server, {remoteReload: true});
  assert.equal(response.body.reload, true);
  assert.equal(response.body.command, undefined);
  assert.equal(f.server.status().pendingCommand.id, queued.id);
  const revision = digest(changed);
  const ready = await report(f.server, event('ready', {revision, remoteReload: true, remoteCommands: true}));
  assert.deepEqual(ready.body.command, {id: queued.id, method: 'ping'});
  const port = Number(new URL(f.server.receiverUrl).port);
  await f.server.close();
  const restarted = await f.start({port});
  assert.equal(restarted.receiverUrl, f.server.receiverUrl);
  assert.equal((await command(restarted, 'ping')).status, 409);
  assert.equal((await commandReport(restarted, {revision, remoteReload: true})).status, 200);
  assert.equal(restarted.status().remoteCommands, true);
  assert.equal(restarted.status().pendingCommand, null);
  assert.equal((await commandReport(restarted, {revision, commandResult: {id: queued.id, method: 'ping', ok: true}})).status, 403);
  assert.equal(restarted.status().commandsAcknowledged, 0);
  assert.equal((await command(restarted, 'ping')).status, 200);
});

test('socket diagnostics are ready-only and bound constructor roles, root names and descriptor metadata', async t => {
  const {server} = await fixture(t);
  const roots = ['window', 'navigator', 'chrome', 'opera', 'external', 'SmartTvA_API',
    'opera_omi', 'NetTV_Connection', 'NetTV_ContentManager', 'NetTV_DeviceUI', 'opera_omi.constructors'];
  const socketDiagnostics = {v: 1, policy: 'unsupported',
    constructor: {exposure: 'absent', attempt: 'not-exposed', errorName: null},
    surfaces: roots.map(root => ({root, state: 'missing', entries: []}))};
  assert.equal((await report(server, event('ready', {socketDiagnostics}))).status, 200);
  assert.deepEqual((await request(server.statusUrl)).body.socketDiagnostics, socketDiagnostics);
  const typeError = {...socketDiagnostics, policy: 'allowed',
    constructor: {exposure: 'function', attempt: 'type-error', errorName: 'TypeError'}};
  assert.equal((await report(server, event('ready', {socketDiagnostics: typeError}))).status, 200);
  const entry = {name: 'TCPServerSocket', kind: 'accessor'};
  const bounded = {...typeError, surfaces: typeError.surfaces.map((surface, index) => index < 2 ? {...surface, state: 'object',
    entries: Array.from({length: 32}, (_, index) => ({name: `safe${index}`, kind: ['function', 'accessor', 'value'][index % 3]}))} : surface)};
  assert.equal((await report(server, event('ready', {socketDiagnostics: bounded}))).status, 200);
  const invalid = [{...typeError, extra: 'raw'}, {...typeError, constructor: {...typeError.constructor, exposure: 'object'}},
    {...typeError, constructor: {...typeError.constructor, attempt: 'invalid-argument-rejected'}},
    {...typeError, constructor: {...typeError.constructor, errorName: 'TypeError: raw detail'}},
    {...typeError, surfaces: [...typeError.surfaces].reverse()},
    ...[{...entry, value: 'private'}, {...entry, name: 'unsafe URL'}, {...entry, kind: 'getter'}].map(value =>
      ({...typeError, surfaces: typeError.surfaces.map((surface, index) => index === 0 ? {...surface, state: 'object', entries: [value]} : surface)})),
    {...typeError, surfaces: typeError.surfaces.map((surface, index) => ({...surface, state: 'object',
      entries: Array.from({length: index === 0 ? 33 : 0}, () => entry)}))},
    {...typeError, surfaces: typeError.surfaces.map((surface, index) => ({...surface, state: 'object',
      entries: Array.from({length: index < 2 ? 32 : index === 2 ? 1 : 0}, () => entry)}))}];
  for (const value of invalid) assert.equal((await report(server, event('ready', {socketDiagnostics: value}))).status, 403);
  assert.equal((await report(server, event('heartbeat', {socketDiagnostics}))).status, 403);
  assert.deepEqual(server.status().socketDiagnostics, bounded);
});

test('the fixed CSManager source capture is ready-only, bounded and has consistent truncation metadata', async t => {
  const {server} = await fixture(t);
  const source = 'function CSManager() { return fixtureFactory(); }';
  const prototype = [{name: 'fixtureMethod', kind: 'function'}, {name: 'fixtureAccessor', kind: 'accessor'}, {name: 'fixtureValue', kind: 'value'}];
  const complete = {v: 1, source, totalLength: source.length, complete: true, nativeMarker: false, prototype};
  assert.equal((await report(server, event('ready', {csManagerSource: complete}))).status, 200);
  assert.deepEqual((await request(server.statusUrl)).body.csManagerSource, complete);
  const snapshot = server.status(); snapshot.csManagerSource.source = 'changed fixture';
  snapshot.csManagerSource.prototype[0].name = 'changedFixture';
  assert.deepEqual(server.status().csManagerSource, complete);
  const truncated = {v: 1, source: 'x'.repeat(4096), totalLength: 131072, complete: false, nativeMarker: false, prototype: []};
  assert.equal((await report(server, event('ready', {csManagerSource: truncated}))).status, 200);
  for (const value of [{...complete, extra: 'raw'}, {...complete, source: ['fixture']}, {...complete, totalLength: source.length - 1},
    {...complete, complete: false}, {...truncated, complete: true}, {...truncated, totalLength: 131073},
    {...truncated, source: 'x'.repeat(4097)}, {...complete, totalLength: 1.5}, {...complete, nativeMarker: 'false'},
    {...complete, prototype: Array.from({length: 17}, () => prototype[0])},
    ...[{name: 'invalid name', kind: 'function'}, {...prototype[0], source: 'raw'}, {...prototype[0], kind: 'getter'}]
      .map(entry => ({...complete, prototype: [entry]}))]) {
    assert.equal((await report(server, event('ready', {csManagerSource: value}))).status, 403);
  }
  assert.equal((await report(server, event('heartbeat', {csManagerSource: complete}))).status, 403);
  assert.deepEqual(server.status().csManagerSource, truncated);
});

test('HbbTV object presence is ready-only, exact-shaped and served with same-origin-only object CSP', async t => {
  const {server} = await fixture(t);
  const page = await request(server.receiverUrl);
  assert.match(page.headers['content-security-policy'], /(?:^|; )object-src 'self'(?:;|$)/);
  assert.equal(page.headers['content-security-policy'].includes('object-src *'), false);
  const hbbtvObject = {v: 1, factoryAvailable: false, objectSupported: null, elementCreated: true,
    localMethod: false, remoteMethod: false, errorName: null};
  assert.equal((await report(server, event('ready', {hbbtvObject}))).status, 200);
  assert.deepEqual((await request(server.statusUrl)).body.hbbtvObject, hbbtvObject);
  const copy = server.status(); copy.hbbtvObject.localMethod = true;
  assert.deepEqual(server.status().hbbtvObject, hbbtvObject);
  for (const value of [{...hbbtvObject, extra: 'raw'}, {...hbbtvObject, v: 2}, {...hbbtvObject, factoryAvailable: 'false'},
    {...hbbtvObject, elementCreated: 1}, {...hbbtvObject, objectSupported: 'unknown'}, {...hbbtvObject, localMethod: null},
    {...hbbtvObject, remoteMethod: 1}, {...hbbtvObject, errorName: 'Error: raw detail'}]) {
    assert.equal((await report(server, event('ready', {hbbtvObject: value}))).status, 403);
  }
  assert.equal((await report(server, event('heartbeat', {hbbtvObject}))).status, 403);
  assert.deepEqual(server.status().hbbtvObject, hbbtvObject);
});

test('the fixed CSManager construction-attempt report is ready-only and bounds safe instance descriptors', async t => {
  const {server} = await fixture(t);
  const csManagerAttempt = {v: 1, attempted: true, constructed: false, localMethod: false, remoteMethod: false,
    errorName: 'TypeError', instance: []};
  assert.equal((await report(server, event('ready', {csManagerAttempt}))).status, 200);
  assert.deepEqual((await request(server.statusUrl)).body.csManagerAttempt, csManagerAttempt);
  const bounded = {...csManagerAttempt, constructed: true, localMethod: true, errorName: null,
    instance: Array.from({length: 16}, (_, index) => ({name: `fixture${index}`, kind: ['function', 'accessor', 'value'][index % 3]}))};
  assert.equal((await report(server, event('ready', {csManagerAttempt: bounded}))).status, 200);
  const copy = server.status(); copy.csManagerAttempt.instance[0].name = 'changedFixture';
  assert.deepEqual(server.status().csManagerAttempt, bounded);
  for (const value of [{...bounded, extra: 'raw'}, {...bounded, attempted: 1}, {...bounded, constructed: 'true'},
    {...bounded, localMethod: null}, {...bounded, remoteMethod: 1}, {...bounded, errorName: 'TypeError: raw detail'},
    {...bounded, instance: [...bounded.instance, {name: 'extra', kind: 'value'}]},
    ...[{name: 'invalid name', kind: 'function'}, {name: 'safe', kind: 'getter'}, {name: 'safe', kind: 'value', value: 'raw'}]
      .map(entry => ({...bounded, instance: [entry]}))]) {
    assert.equal((await report(server, event('ready', {csManagerAttempt: value}))).status, 403);
  }
  assert.equal((await report(server, event('heartbeat', {csManagerAttempt}))).status, 403);
  assert.deepEqual(server.status().csManagerAttempt, bounded);
});

test('MSX bootstrap/home CORS and framing are scoped; bootstrap grants no report or command privilege', async t => {
  const {server} = await fixture(t, {msx: true});
  const origin = new URL(server.receiverUrl).origin;
  const msxOrigin = 'https://msx.benzac.de';
  const start = await request(origin + '/msx/start.json', {headers: {Origin: msxOrigin}});
  assert.deepEqual(start.body, {name: 'Philips API diagnostic', version: '1.0.0', parameter: `content:${origin}/${TOKEN}/msx-home.json`});
  assert.equal(start.headers['access-control-allow-origin'], msxOrigin);
  const home = await request(start.body.parameter.slice(8), {headers: {Origin: msxOrigin}});
  assert.equal(home.body.items.length, 1);
  assert.equal(home.body.items[0].action, `video:plugin:${server.receiverUrl}`);
  assert.equal(server.status().ready, 0);
  assert.equal((await command(server, 'ping')).status, 409);
  const preflight = await request(origin + '/msx/start.json', {method: 'OPTIONS', headers: {Origin: msxOrigin,
    'Access-Control-Request-Method': 'GET', 'Access-Control-Request-Headers': 'content-type,accept'}});
  assert.equal(preflight.status, 204);
  assert.equal(preflight.headers['access-control-allow-origin'], msxOrigin);
  const framed = await request(server.receiverUrl);
  assert.match(framed.headers['content-security-policy'], /frame-ancestors http:\/\/msx\.benzac\.de https:\/\/msx\.benzac\.de/);
  assert.equal(framed.headers['content-security-policy'].includes('frame-ancestors *'), false);
  assert.match((await request(`${origin}/${TOKEN}/index.html`)).headers['content-security-policy'], /frame-ancestors 'none'/);
  const rejectedReport = await report(server, event('ready'), {headers: {Origin: msxOrigin}});
  assert.equal(rejectedReport.status, 403);
  assert.equal(rejectedReport.headers['access-control-allow-origin'], undefined);
  assert.equal((await request(new URL('report', server.receiverUrl), {method: 'OPTIONS', headers: {Origin: msxOrigin}})).status, 403);
  assert.equal((await report(server, event('ready'), {headers: {Referer: server.receiverUrl}})).status, 200);
});

test('MSX JSON accepts only bounded version/timestamp cache queries without changing report or page routes', async t => {
  const {server} = await fixture(t, {msx: true});
  const origin = new URL(server.receiverUrl).origin;
  const headers = {Origin: 'https://msx.benzac.de'};
  for (const route of ['/msx/start.json', `/${TOKEN}/msx-home.json`]) {
    for (const query of ['?v=0.1.167&t=1792504210807', '?t=1792504210807&v=0.1.167', '?v=0.1.167', '?t=1792504210807']) {
      const response = await request(origin + route + query, {headers});
      assert.equal(response.status, 200);
      assert.equal(response.headers['access-control-allow-origin'], headers.Origin);
    }
    assert.equal((await request(origin + route + '?v=0.1.167&t=1792504210807', {method: 'OPTIONS', headers: {
      ...headers, 'Access-Control-Request-Method': 'GET'}})).status, 204);
    for (const query of ['?other=1', '?v=1&v=2', '?t=1&t=2', '?v=-1', '?t=-1', '?v=1..2', '?t=1.2', '?v=',
      '?t=12345678901234567', '?v=' + '1'.repeat(33), '?%76=1', '?v=1&']) {
      assert.equal((await request(origin + route + query, {headers})).status, 403, `Reject synthetic cache query ${query}`);
    }
  }
  assert.equal((await request(server.receiverUrl + '?v=1', {headers})).status, 403);
  assert.equal((await request(new URL('report?v=1', server.receiverUrl), {method: 'POST', json: event('ready'), headers: {Origin: origin}})).status, 403);
  assert.equal((await request(server.statusUrl + '?v=1')).status, 403);
  assert.equal(server.status().ready, 0);
});

test('MSX records bounded origin outcomes, denies opaque/foreign origins by default and detects TLS-to-HTTP', async t => {
  const {server} = await fixture(t, {msx: true});
  const origin = new URL(server.receiverUrl).origin;
  for (const supplied of ['https://installed.example', 'null', 'https://installed.example/private?secret=value']) {
    assert.equal((await request(origin + '/msx/start.json', {headers: {Origin: supplied}})).status, 403);
  }
  assert.equal(server.status().msxBootstrap.lastOriginClass, 'invalid');
  assert.equal(server.status().msxBootstrap.lastOrigin, null);
  assert.equal((await request(origin + '/msx/start.json', {headers: {Origin: 'https://installed.example'}})).status, 403);
  assert.equal(server.status().msxBootstrap.lastOrigin, 'https://installed.example');
  assert.equal(server.status().msxBootstrap.lastStatus, 403);
  await new Promise((resolve, reject) => {
    const socket = net.createConnection({host: '127.0.0.1', port: Number(new URL(origin).port)},
      () => socket.end(Buffer.from([0x16, 0x03, 0x01, 0x00, 0x01, 0x00])));
    socket.setTimeout(2000, () => { socket.destroy(); reject(new Error('TLS fixture timed out')); });
    socket.on('error', error => { if (error.code !== 'ECONNRESET') reject(error); });
    socket.on('close', resolve);
  });
  assert.equal(server.status().msxBootstrap.clientErrors, 1);
  assert.equal(server.status().msxBootstrap.tlsClientErrors, 1);
  assert.equal(server.status().msxBootstrap.lastClientError, 'invalid-method');
  assert.equal(JSON.stringify(server.status()).includes('rawPacket'), false);
  const opaque = await fixture(t, {msx: true, msxAllowNullOrigin: true, msxFrameOrigins: ['https://msx.benzac.de']});
  assert.equal((await request(new URL('/msx/start.json', opaque.server.receiverUrl), {headers: {Origin: 'null'}})).headers['access-control-allow-origin'], 'null');
  assert.match((await request(opaque.server.receiverUrl)).headers['content-security-policy'], /frame-ancestors https:\/\/msx\.benzac\.de;/);
});

test('diagnostics default passive, remain passive in MSX and expose ready-only exact probe context', async t => {
  const f = await fixture(t);
  await writeFile(f.indexPath, PAGE + '<script>window.mode="__TV_PROBE_DIAGNOSTICS__";</script>');
  assert.match((await request(f.server.receiverUrl)).body, /window.mode="passive"/);
  const active = await f.start({activeDiagnostics: true});
  assert.match((await request(active.receiverUrl)).body, /window.mode="active"/);
  assert.notEqual(active.status().assetRevision, f.server.status().assetRevision);
  const msx = await f.start({msx: true, activeDiagnostics: true});
  assert.match((await request(msx.receiverUrl)).body, /window.mode="passive"/);
  const probeContext = {v: 1, embedded: true, activeDiagnostics: false};
  assert.equal((await report(msx, event('ready', {probeContext}))).status, 200);
  assert.deepEqual((await request(msx.statusUrl)).body.probeContext, probeContext);
  for (const value of [{...probeContext, extra: true}, {...probeContext, embedded: 'true'}, {...probeContext, activeDiagnostics: 0}]) {
    assert.equal((await report(msx, event('ready', {probeContext: value}))).status, 403);
  }
  assert.equal((await report(msx, event('heartbeat', {probeContext}))).status, 403);
  assert.deepEqual(msx.status().probeContext, probeContext);
  assert.equal((await request(new URL('/msx/start.json', f.server.receiverUrl))).status, 403);
  for (const origin of ['*', 'https://*.msx.benzac.de', 'https://msx.benzac.de;script-src',
    "https://msx.benzac.de'", 'https://msx.benzac.de"', 'https://msx.benzac.de,example.test']) {
    await assert.rejects(f.start({msx: true, msxFrameOrigins: [origin]}), /INVALID_CONFIG/);
  }
  await assert.rejects(f.start({activeDiagnostics: 'true'}), /INVALID_CONFIG/);
});

test('MSX distinguishes an active empty connection from a closed zero-byte idle timeout', async t => {
  const {server} = await fixture(t, {msx: true});
  const socket = net.createConnection({host: '127.0.0.1', port: Number(new URL(server.receiverUrl).port)});
  t.after(() => socket.destroy());
  await new Promise((resolve, reject) => { socket.once('connect', resolve); socket.once('error', reject); });
  for (let attempt = 0; attempt < 50 && server.status().msxBootstrap.activeConnections === 0; attempt++) {
    await new Promise(setImmediate);
  }
  assert.equal(server.status().msxBootstrap.activeConnections, 1);
  assert.equal(server.status().msxBootstrap.completedConnections, 0);
  assert.equal(server.status().msxBootstrap.zeroByteConnections, 0);
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('MSX idle socket did not close')), 8000);
    socket.once('close', () => { clearTimeout(timer); resolve(); });
    socket.once('error', error => { if (error.code !== 'ECONNRESET') { clearTimeout(timer); reject(error); } });
  });
  for (let attempt = 0; attempt < 50 && server.status().msxBootstrap.activeConnections !== 0; attempt++) {
    await new Promise(setImmediate);
  }
  const stats = server.status().msxBootstrap;
  assert.equal(stats.activeConnections, 0);
  assert.equal(stats.completedConnections, 1);
  assert.equal(stats.zeroByteConnections, 1);
  assert.equal(stats.idleTimeoutConnections, 1);
  assert.equal(stats.receivedBytes, 0);
  assert.equal(stats.lastClientError, 'idle-timeout');
  assert.equal(stats.clientErrors, 0);
});
