import http from 'node:http';
import net from 'node:net';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { StringDecoder } from 'node:string_decoder';
import { homedir } from 'node:os';

// Explicit emulator fixture only: no discovery, device scan, or projector access.
const run = promisify(execFile);
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
const appPackage = process.env.ANDROID_TEST_PACKAGE || 'dev.framextv.lumaremote';
const androidSdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
if (!/^dev\.framextv\.lumaremote(?:\.receiverfixture)?$/u.test(appPackage)) throw new Error('Unsupported Android fixture package.');
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
const commands = [], mutations = [], pairings = [], sockets = new Set();
let currentSource = '', currentContent = null, position = 0, playing = true, appLists = 0, connections = 0;
const TOKEN = 'fixture_receiver_token_0123456789', CODE = '123456', PAIRING = 'fixturePairing01';
const FIRST = 'https://media.example/first.m3u8';
const reply = (response, status, body) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(typeof body === 'string' ? body : JSON.stringify(body)); };
const failure = (response, status, code) => reply(response, status, { ok: false, error: { code } });
async function body(request) {
  const chunks = []; let size = 0;
  for await (const bytes of request) { size += bytes.length; assert.ok(size < 256 * 1024); chunks.push(bytes); }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}
function route(request) {
  const match = /^\/api\/v1\/([a-z]+(?:\.[A-Za-z]+)?)$/u.exec(request.url);
  return match ? match[1] : null;
}
function appFrame() {
  const fields = [];
  for (const [name, pkg] of [['LUMA Receiver', 'dev.framextv.lumareceiver'], ['Vídeo', 'example.video']]) {
    for (const value of [name, pkg, '1', Buffer.from([1, 0, 0, 0]), Buffer.from([0])]) {
      const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value);
      const length = Buffer.alloc(4); length.writeUInt32LE(bytes.length); fields.push(length, bytes);
    }
  }
  const length = Buffer.alloc(4); length.writeUInt32LE(4 + fields.reduce((size, bytes) => size + bytes.length, 0));
  return Buffer.concat([length, ...fields]);
}
const tcp = net.createServer(socket => {
  connections++; sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
  let pending = ''; const decoder = new StringDecoder('utf8');
  socket.on('data', bytes => {
    pending += decoder.write(bytes);
    let end;
    while ((end = pending.indexOf('\r\n\r\n')) >= 0) {
      const command = pending.slice(0, end + 4); pending = pending.slice(end + 4); commands.push(command);
      const method = command.split('\r\n')[0];
      if (method === 'getFeatures') socket.write('server_features:sn=0xbf m=Smart Projector ap=LUMA fixture');
      if (method === 'GETPASSWORDCONFIG') socket.write('configed none\r\n\r\n');
      if (method === 'GETAPPINFOSEVNET') {
        appLists++; const frame = appFrame(); socket.write(frame.subarray(0, 2)); setTimeout(() => socket.write(frame.subarray(2)), 15);
      }
      if (command === 'MediaControl\r\ngetVolume\r\n\r\n') socket.write('6 15\r\n\r\n');
      if (command === 'KEYEVENT\r\n21\r\n\r\n') socket.end();
    }
  });
});
// Minimal LUMA Receiver LAN API (android-receiver ControlServer envelope and
// PlaybackController guard semantics). Rejected and incomplete requests never mutate.
const receiver = http.createServer(async (request, response) => {
  try {
    const method = route(request);
    if (request.method === 'GET' && method === 'info') { reply(response, 200, { ok: true, result: { app: 'lumareceiver', versionName: 'fixture-1.0', receiverId: 'fixture-receiver' } }); return; }
    assert.equal(request.method, 'POST'); assert.ok(method);
    if (method === 'pair.begin' || method === 'pair.complete') {
      assert.equal(request.headers.authorization, undefined, 'Pairing must not send a bearer token.');
      const params = await body(request); pairings.push({ method, params });
      if (method === 'pair.begin') { assert.match(params.clientName, /^LUMA Remote · /u); reply(response, 200, { ok: true, result: { pairingId: PAIRING, expiresInSeconds: 120 } }); return; }
      if (params.pairingId === 'expiredPairing01') { failure(response, 410, 'PAIRING_EXPIRED'); return; }
      if (params.pairingId !== PAIRING || params.code !== CODE) { failure(response, 403, 'PAIRING_CODE_WRONG'); return; }
      reply(response, 200, { ok: true, result: { token: TOKEN, clientId: 'fixture-client' } }); return;
    }
    if (request.headers.authorization !== `Bearer ${TOKEN}`) { failure(response, 401, 'UNAUTHORIZED'); return; }
    const params = await body(request);
    const expect = () => {
      if (!currentSource) { failure(response, 409, 'NO_VIDEO'); return false; }
      if ('expectedSourceUrl' in params && params.expectedSourceUrl !== currentSource
        || 'expectedContentKey' in params && params.expectedContentKey !== currentContent?.key) { failure(response, 409, 'SOURCE_CHANGED'); return false; }
      return true;
    };
    const accept = result => { mutations.push({ method, params }); reply(response, 200, { ok: true, result: result || { accepted: true } }); };
    if (method === 'player.status') {
      reply(response, 200, { ok: true, result: currentSource
        ? { revision: mutations.length, active: true, playing, paused: !playing, state: playing ? 'playing' : 'paused', positionSeconds: position, durationSeconds: 1355, bufferedSeconds: 1355, canSeek: true, sourceUrl: currentSource, title: currentContent?.title || 'Fixture', ...(currentContent ? { content: currentContent } : {}) }
        : { revision: mutations.length, active: false, playing: false, paused: false, state: 'idle', positionSeconds: 0, durationSeconds: 0, bufferedSeconds: 0, canSeek: false, sourceUrl: '' } });
      return;
    }
    if (method === 'player.open') {
      const url = params.media?.url || '';
      const special = /\/(rejected|unknown|false-result|null-result|invalid-content)\.m3u8$/u.exec(url)?.[1];
      if (special === 'rejected') { failure(response, 422, 'UNSUPPORTED_MEDIA'); return; }
      if (special === 'unknown') { failure(response, 503, 'INTERNAL'); return; }
      if (special === 'false-result') { reply(response, 200, { ok: false }); return; }
      if (special === 'null-result') { reply(response, 200, { ok: true, result: null }); return; }
      if (special === 'invalid-content') { failure(response, 400, 'INVALID_CONTENT'); return; }
      assert.equal(typeof params.onlyIfInactive, 'boolean');
      if (params.onlyIfInactive && currentSource) { failure(response, 409, 'SOURCE_CHANGED'); return; }
      if ('expectedCurrentSourceUrl' in params && (params.expectedCurrentSourceUrl !== currentSource || params.expectedCurrentContentKey !== currentContent?.key)) { failure(response, 409, 'SOURCE_CHANGED'); return; }
      currentSource = url; currentContent = params.media.content || null; position = params.media.resumeSeconds || 0; playing = true;
      accept(); return;
    }
    if (method === 'player.subtitles') {
      if (currentSource && !expect()) return;
      reply(response, 200, { ok: true, result: currentSource
        ? { active: true, enabled: false, currentIndex: null, tracks: [{ index: 0, name: 'English', language: 'en', supported: true }, { index: 1, name: 'Português', language: 'pt-br', supported: true }] }
        : { active: false, enabled: false, currentIndex: null, tracks: [] } });
      return;
    }
    if (method === 'player.addSubtitle') {
      const special = /\/(rejected|bare-4xx|unknown|missing-result|malformed|trailing|false-result|null-result|revoked)\.vtt$/u.exec(params.track?.url || '')?.[1];
      if (special === 'rejected') { failure(response, 422, 'UNSUPPORTED_SUBTITLE'); return; }
      if (special === 'bare-4xx') { reply(response, 400, {}); return; }
      if (special === 'unknown') { failure(response, 500, 'INTERNAL'); return; }
      if (special === 'missing-result') { reply(response, 200, { ok: true }); return; }
      if (special === 'malformed') { reply(response, 200, 'not json'); return; }
      if (special === 'trailing') { reply(response, 200, `${JSON.stringify({ ok: true, result: { accepted: true } })} unexpected`); return; }
      if (special === 'false-result') { reply(response, 200, { ok: false, error: { code: 'X' } }); return; }
      if (special === 'null-result') { reply(response, 200, { ok: true, result: null }); return; }
      if (special === 'revoked') { failure(response, 401, 'UNAUTHORIZED'); return; }
      if (!expect()) return;
      accept({ accepted: true, index: 2 }); return;
    }
    if (method === 'player.setSubtitle') {
      if (!expect()) return;
      if (params.index !== 'off' && !(params.index >= 0 && params.index <= 1)) { failure(response, 400, 'INVALID_SUBTITLE'); return; }
      accept(); return;
    }
    if (['player.pause', 'player.resume', 'player.seek', 'player.stop'].includes(method)) {
      if (!expect()) return;
      if (method === 'player.seek' && params.position > 1355) { failure(response, 409, 'CANNOT_SEEK'); return; }
      if (method === 'player.pause' || method === 'player.resume') playing = method === 'player.resume';
      if (method === 'player.seek') position = params.position;
      if (method === 'player.stop') { currentSource = ''; currentContent = null; }
      accept(); return;
    }
    failure(response, 404, 'NOT_FOUND');
  } catch { if (!response.headersSent) failure(response, 400, 'INVALID_INPUT'); }
});
const slow = http.createServer(request => request.resume());
// Every call takes 2.8 s, so the second call of one operation must hit the shared 5 s budget.
let deadlineCalls = 0;
const deadline = http.createServer((request, response) => {
  deadlineCalls++; request.resume();
  const method = route(request);
  const result = method === 'info' ? { app: 'lumareceiver', versionName: 'slow', receiverId: 'slow' }
    : { active: true, playing: true, paused: false, positionSeconds: 0, durationSeconds: 1355, canSeek: true, sourceUrl: FIRST };
  const timer = setTimeout(() => { if (!response.destroyed) reply(response, 200, { ok: true, result }); }, 2800);
  response.once('close', () => clearTimeout(timer));
});
let readinessReads = 0;
const readinessSnapshots = [
  { active: false, enabled: false, currentIndex: null, tracks: [] },
  { active: true, enabled: false, currentIndex: null, tracks: [] },
  { active: true, enabled: true, currentIndex: 5, tracks: [{ index: 0, name: 'English', language: 'en', supported: true }] },
  { active: true, currentIndex: null, tracks: [] },
  { active: true, enabled: false, currentIndex: null, tracks: 'invalid' },
  { active: true, enabled: false, currentIndex: 0, tracks: [{ index: 0, name: 'A', language: 'en' }, { index: 0, name: 'B', language: 'en' }] },
];
const readiness = http.createServer(async (request, response) => {
  try {
    assert.equal(route(request), 'player.subtitles'); assert.equal(request.headers.authorization, `Bearer ${TOKEN}`);
    assert.equal((await body(request)).expectedSourceUrl, FIRST);
    reply(response, 200, { ok: true, result: readinessSnapshots[readinessReads++] });
  } catch { failure(response, 500, 'INTERNAL'); }
});
let malformedStatus = 0;
const malformed = http.createServer(async (request, response) => {
  try {
    const method = route(request);
    if (method === 'info') { reply(response, 200, { ok: true, result: { app: 'kodi', versionName: '21.2' } }); return; }
    if (method === 'pair.complete') { await body(request); reply(response, 200, { ok: true, result: { token: 'not a valid token!', clientId: 'x' } }); return; }
    assert.equal(method, 'player.status'); await body(request);
    const snapshots = [
      { active: true, playing: true, positionSeconds: 1, durationSeconds: 2, canSeek: true },
      { active: 'true', playing: true, positionSeconds: 1, durationSeconds: 2, canSeek: true, sourceUrl: FIRST },
      { active: true, playing: true, positionSeconds: 1, durationSeconds: 2, canSeek: true, sourceUrl: FIRST,
        content: { key: 'framextv:tv:42:2:4', kind: 'tv', contentId: '42', season: 2, episode: 3, title: 'Spoofed' } },
    ];
    reply(response, 200, { ok: true, result: snapshots[malformedStatus++] });
  } catch { failure(response, 500, 'INTERNAL'); }
});
function listen(server, port) { return new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); }); }
const servers = [tcp, receiver, slow, deadline, readiness, malformed];
try {
  await listen(tcp, 8121); for (const server of servers.slice(1)) await listen(server, 0);
  for (const [apk, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', appPackage], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${appPackage}.test`]]) {
    const { stdout: apkMetadata } = await run(resolve(androidSdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', resolve(apk)], { timeout: 10000 });
    assert.equal(apkMetadata.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build the intended fixture package before installing.');
    await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
  }
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'targetHost', '10.0.2.2',
    '-e', 'receiverPort', String(receiver.address().port), '-e', 'slowPort', String(slow.address().port), '-e', 'deadlinePort', String(deadline.address().port),
    '-e', 'readinessPort', String(readiness.address().port), '-e', 'malformedPort', String(malformed.address().port),
    `${appPackage}.test/dev.framextv.lumaremote.BackendInstrumentation`], { timeout: 90000, maxBuffer: 128 * 1024 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=15/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.equal(connections, 2); assert.equal(appLists, 1);
  assert.ok(commands.includes('CONTENT\r\n1\r\n0 Olá 👋\r\n\r\n'));
  assert.equal(commands.filter(command => command === 'KEYEVENT\r\n26\r\n\r\n').length, 1);
  assert.ok(commands.includes('STARTAPPEVNET\r\ndev.framextv.lumareceiver\r\n\r\n'));
  assert.equal(commands.filter(command => command.startsWith('STARTAPPEVNET')).length, 1);
  assert.deepEqual(commands.filter(command => command.includes('setVolume')), ['MediaControl\r\nsetVolume 14\r\n\r\n']);
  assert.ok(commands.every(command => !/^(?:auth|CHECKPASSWORD|getServerInfo)/u.test(command)));
  // Pairing: one window, two wrong/expired codes rejected by the receiver, then the
  // whitespace-normalized code; the locally malformed code never reaches the wire.
  assert.deepEqual(pairings.map(({ method, params }) => [method, params.pairingId, params.code]), [
    ['pair.begin', undefined, undefined], ['pair.complete', PAIRING, '000000'], ['pair.complete', 'expiredPairing01', CODE], ['pair.complete', PAIRING, CODE]]);
  assert.deepEqual(mutations.map(message => message.method), ['player.open', 'player.open', 'player.open', 'player.open', 'player.addSubtitle',
    'player.setSubtitle', 'player.setSubtitle', 'player.pause', 'player.resume', 'player.seek', 'player.stop']);
  const opens = mutations.filter(message => message.method === 'player.open').map(message => message.params);
  assert.deepEqual(opens[0], { media: { url: FIRST, contentType: 'application/vnd.apple.mpegurl' }, onlyIfInactive: true });
  assert.deepEqual(opens[1], { media: { url: FIRST, contentType: 'application/vnd.apple.mpegurl' }, onlyIfInactive: false }, 'Invalid content identity must not be forwarded.');
  const episode = { key: 'framextv:tv:42:2:3', kind: 'tv', contentId: '42', season: 2, episode: 3, watchUrl: 'https://framextv.cc/watch/42/2/3', title: 'Fixture Series · S2 E3' };
  assert.deepEqual(opens[2], { media: { url: FIRST, contentType: 'application/vnd.apple.mpegurl', content: episode, title: episode.title }, onlyIfInactive: false });
  assert.deepEqual(opens[3], { media: { url: FIRST, contentType: 'application/vnd.apple.mpegurl', content: episode, title: episode.title, resumeSeconds: 30 },
    onlyIfInactive: false, expectedCurrentSourceUrl: FIRST, expectedCurrentContentKey: episode.key });
  assert.deepEqual(mutations.slice(4).map(message => message.params), [
    { track: { url: 'https://media.example/pt.vtt', select: true, language: 'pt-BR', label: 'Português' }, expectedSourceUrl: FIRST },
    { index: 1, expectedSourceUrl: FIRST }, { index: 'off', expectedSourceUrl: FIRST },
    { expectedSourceUrl: FIRST, expectedContentKey: episode.key }, { expectedSourceUrl: FIRST },
    { position: 60, expectedSourceUrl: FIRST }, { expectedSourceUrl: FIRST },
  ]);
  assert.equal(readinessReads, 6); assert.equal(malformedStatus, 3); assert.equal(deadlineCalls, 2);
  console.log('Native backend: 15 instrumentation checks and LUMA Receiver wire assertions passed against emulator-only fixtures.');
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of servers) { server.closeAllConnections?.(); if (server.listening) await new Promise(done => server.close(done)); }
}
