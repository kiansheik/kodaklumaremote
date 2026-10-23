import http from 'node:http';
import net from 'node:net';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { StringDecoder } from 'node:string_decoder';
import { homedir } from 'node:os';

// Explicit emulator-only service fixtures. No LAN discovery or projector access.
const run = promisify(execFile);
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
const appPackage = process.env.ANDROID_TEST_PACKAGE || 'dev.framextv.lumaremote';
const androidSdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
if (!/^dev\.framextv\.lumaremote(?:\.receiverfixture)?$/u.test(appPackage)) throw new Error('Unsupported Android fixture package.');
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
const FIRST = 'https://media.example/first.m3u8';
const OTHER = 'https://media.example/other.m3u8';
const NEXT = 'https://media.example/next.m3u8';
const commands = [], mutations = [], sockets = new Set();
let connections = 0, currentSource = '', playing = true, position = 20, stops = 0, contentOpens = 0, authRejections = 0, holdProperties = false, volume = 6;
let failStatus = false, failStatusOnce = 0, failAfterPause = 0, failedStatusReads = 0;
let lumaMetadata = false, lumaEpisode = 3;
let switchBeforeOpen = 0, expectedNextSource = NEXT, failNextSeek = false, seekFailures = 0;
const TOKEN = 'fixture_receiver_token_0123456789', CODE = '123456', PAIRING = 'servicePairing01';
// A fresh receiver identity per run, as after a receiver reinstall: its change cursor restarts.
const RECEIVER_ORIGIN = `fixture-${Date.now().toString(36)}`;
const KEY = /^[a-z0-9][a-z0-9:._-]{0,199}$/u, ORIGIN = /^[A-Za-z0-9_-]{8,64}$/u;
const libraryRecords = [], libraryPushed = {}, pairings = [];
let librarySeq = 0, libraryPulls = 0, libraryPushes = 0;
function libraryMerge(record) {
  const index = libraryRecords.findIndex(entry => entry.key === record.key);
  const existing = libraryRecords[index];
  if (existing && (existing.updatedAt > record.updatedAt || existing.updatedAt === record.updatedAt && existing.origin >= record.origin)) return false;
  if (index >= 0) libraryRecords.splice(index, 1);
  libraryRecords.push({ ...record, seq: ++librarySeq }); return true;
}
{
  // Progress the receiver recorded on its own (no phone involved).
  const now = Date.now();
  libraryMerge({ key: 'framextv:movie:77', type: 'progress', updatedAt: now, origin: RECEIVER_ORIGIN, data: {
    key: 'framextv:movie:77', kind: 'movie', contentId: '77', season: null, episode: null, watchUrl: 'https://framextv.cc/watch/77',
    title: 'Receiver movie', positionSeconds: 500, durationSeconds: 5400, updatedAt: now, lastStartedAt: now, lastReceiver: 'luma' } });
}
const reply = (response, status, body) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(body)); };
const failure = (response, status, code) => reply(response, status, { ok: false, error: { code } });
let holdAfterVolumeSet = false, heldVolumeReads = [], volumeSets = 0, volumeGets = 0;
let holdVolumeSetThreshold = Infinity;
let holdNextVolumeRead = false;
const heldResponses = new Set();
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
      if (command === 'MediaControl\r\ngetVolume\r\n\r\n') {
        volumeGets++;
        if (holdNextVolumeRead || holdAfterVolumeSet && volumeSets >= holdVolumeSetThreshold) { heldVolumeReads.push({ socket, value: volume }); holdNextVolumeRead = false; }
        else socket.write(`${volume} 15\r\n\r\n`);
      }
      const setVolume = /^MediaControl\r\nsetVolume (\d+)\r\n\r\n$/u.exec(command);
      if (setVolume) { volumeSets++; volume = Math.round(Number(setVolume[1]) / 2); }
      if (command === 'KEYEVENT\r\n25\r\n\r\n') volume = Math.max(0, volume - 1);
      if (command === 'KEYEVENT\r\n24\r\n\r\n') volume = Math.min(15, volume + 1);
    }
  });
});
function content(episode) {
  return { key: `framextv:tv:42:1:${episode}`, kind: 'tv', contentId: '42', season: 1, episode, watchUrl: `https://framextv.cc/watch/42/1/${episode}`, title: `Shared series · S1 E${episode}` };
}
// Minimal LUMA Receiver LAN API (android-receiver ControlServer envelope and
// PlaybackController guard semantics) on its default port 4191, plus /fixture controls.
const receiver = http.createServer(async (request, response) => {
  try {
    const address = new URL(request.url, 'http://fixture');
    if (request.method === 'GET' && address.pathname === '/fixture') {
      if (address.searchParams.get('source') === 'other') { currentSource = OTHER; playing = true; }
      if (address.searchParams.get('source') === 'first') { currentSource = FIRST; playing = true; }
      if (address.searchParams.has('position')) position = Number(address.searchParams.get('position'));
      if (address.searchParams.has('lumaMetadata')) lumaMetadata = address.searchParams.get('lumaMetadata') === '1';
      if (address.searchParams.has('episode')) lumaEpisode = Number(address.searchParams.get('episode'));
      if (address.searchParams.has('switchBeforeOpen')) switchBeforeOpen = Number(address.searchParams.get('switchBeforeOpen'));
      if (address.searchParams.has('nextSource')) expectedNextSource = address.searchParams.get('nextSource') === 'other' ? OTHER : NEXT;
      if (address.searchParams.get('failNextSeek') === '1') failNextSeek = true;
      if (address.searchParams.has('failStatus')) failStatus = address.searchParams.get('failStatus') === '1';
      if (address.searchParams.has('failStatusOnce')) failStatusOnce = Number(address.searchParams.get('failStatusOnce'));
      if (address.searchParams.has('failAfterPause')) failAfterPause = Number(address.searchParams.get('failAfterPause'));
      if (address.searchParams.has('holdAfterVolumeSet')) { holdAfterVolumeSet = address.searchParams.get('holdAfterVolumeSet') === '1'; holdVolumeSetThreshold = volumeSets + 1; }
      if (address.searchParams.get('holdNextVolumeRead') === '1') holdNextVolumeRead = true;
      if (address.searchParams.get('releaseVolume') === '1') {
        holdAfterVolumeSet = false;
        for (const held of heldVolumeReads) if (!held.socket.destroyed) held.socket.write(`${held.value} 15\r\n\r\n`);
        heldVolumeReads = [];
      }
      if (address.searchParams.get('dropTcp') === '1') for (const socket of sockets) socket.destroy();
      if (address.searchParams.get('hold') === '1') holdProperties = true;
      if (address.searchParams.get('hold') === '0') {
        holdProperties = false;
        for (const held of [...heldResponses]) {
          heldResponses.delete(held);
          if (!held.response.destroyed) reply(held.response, 200, held.message);
        }
      }
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ source: currentSource, playing, position, lumaMetadata, lumaEpisode, switchBeforeOpen, expectedNextSource, failNextSeek, seekFailures, stops, contentOpens, volume, mutations: mutations.length, connections, held: heldResponses.size, failedStatusReads,
        libraryPulls, libraryPushes, libraryPushed,
        volumeSets, volumeGets, volumeReadHeld: heldVolumeReads.length,
        volumeKeys: commands.filter(command => /^KEYEVENT\r\n(?:24|25)\r\n\r\n$/u.test(command)).length,
        powerCommands: commands.filter(command => command === 'KEYEVENT\r\n26\r\n\r\n').length })); return;
    }
    const method = /^\/api\/v1\/([a-z]+(?:\.[A-Za-z]+)?)$/u.exec(address.pathname)?.[1];
    if (request.method === 'GET' && method === 'info') { reply(response, 200, { ok: true, result: { app: 'lumareceiver', versionName: 'fixture-1.0', receiverId: 'service-fixture' } }); return; }
    assert.equal(request.method, 'POST'); assert.ok(method);
    const chunks = []; let size = 0;
    for await (const bytes of request) { size += bytes.length; assert.ok(size < 512 * 1024); chunks.push(bytes); }
    const params = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (method === 'pair.begin' || method === 'pair.complete') {
      assert.equal(request.headers.authorization, undefined); pairings.push({ method, params });
      if (method === 'pair.begin') { reply(response, 200, { ok: true, result: { pairingId: PAIRING, expiresInSeconds: 120 } }); return; }
      if (params.pairingId !== PAIRING || params.code !== CODE) { failure(response, 403, 'PAIRING_CODE_WRONG'); return; }
      reply(response, 200, { ok: true, result: { token: TOKEN, clientId: 'service-client' } }); return;
    }
    if (request.headers.authorization !== `Bearer ${TOKEN}`) { authRejections++; failure(response, 401, 'UNAUTHORIZED'); return; }
    const currentContent = () => currentSource && lumaMetadata ? content(lumaEpisode) : null;
    const expect = () => {
      if (!currentSource) { failure(response, 409, 'NO_VIDEO'); return false; }
      if ('expectedSourceUrl' in params && params.expectedSourceUrl !== currentSource
        || 'expectedContentKey' in params && params.expectedContentKey !== currentContent()?.key) { failure(response, 409, 'SOURCE_CHANGED'); return false; }
      return true;
    };
    const accept = () => { mutations.push({ method, params }); reply(response, 200, { ok: true, result: { accepted: true } }); };
    if (method === 'player.status') {
      if (failStatus || failStatusOnce > 0) {
        if (failStatusOnce > 0) failStatusOnce--;
        failedStatusReads++; failure(response, 503, 'INTERNAL'); return;
      }
      const current = currentContent();
      const message = { ok: true, result: currentSource
        ? { revision: mutations.length, active: true, playing, paused: !playing, state: playing ? 'playing' : 'paused', positionSeconds: position, durationSeconds: 1355,
          bufferedSeconds: 1355, canSeek: true, sourceUrl: currentSource, title: current ? current.title : 'Fixture', ...(current ? { content: current } : {}) }
        : { revision: mutations.length, active: false, playing: false, paused: false, state: 'idle', positionSeconds: 0, durationSeconds: 0, bufferedSeconds: 0, canSeek: false, sourceUrl: '' } };
      if (holdProperties) { const held = { response, message }; heldResponses.add(held); response.once('close', () => heldResponses.delete(held)); return; }
      reply(response, 200, message); return;
    }
    if (method === 'player.subtitles') {
      if (currentSource && !expect()) return;
      reply(response, 200, { ok: true, result: { active: !!currentSource, enabled: false, currentIndex: null, tracks: [] } }); return;
    }
    if (method === 'player.open') {
      assert.equal(typeof params.onlyIfInactive, 'boolean');
      if (switchBeforeOpen > 0) { lumaMetadata = true; lumaEpisode = switchBeforeOpen; position = 91; switchBeforeOpen = 0; }
      if (params.onlyIfInactive && currentSource) { failure(response, 409, 'SOURCE_CHANGED'); return; }
      if ('expectedCurrentSourceUrl' in params && (params.expectedCurrentSourceUrl !== currentSource || params.expectedCurrentContentKey !== currentContent()?.key)) {
        failure(response, 409, 'SOURCE_CHANGED'); return;
      }
      const opened = params.media.content;
      if (opened) {
        assert.equal(params.media.url, expectedNextSource); assert.deepEqual(opened, content(6));
        assert.equal(typeof params.expectedCurrentSourceUrl, 'string'); assert.match(params.expectedCurrentContentKey, /^framextv:tv:42:1:\d+$/u);
        contentOpens++;
      }
      // Models a receiver that starts from 0:20 without applying resumeSeconds, so the phone's guarded resume seek remains observable.
      currentSource = params.media.url; lumaMetadata = !!opened; if (opened) lumaEpisode = opened.episode; playing = true; position = 20;
      accept(); return;
    }
    if (['player.pause', 'player.resume', 'player.seek', 'player.stop'].includes(method)) {
      if (method === 'player.seek' && failNextSeek) { failNextSeek = false; seekFailures++; failure(response, 500, 'INTERNAL'); return; }
      if (!expect()) return;
      if (method === 'player.pause' || method === 'player.resume') { playing = method === 'player.resume'; if (method === 'player.pause' && failAfterPause > 0) { failStatusOnce = failAfterPause; failAfterPause = 0; } }
      if (method === 'player.seek') position = params.position;
      if (method === 'player.stop') { currentSource = ''; stops++; }
      accept(); return;
    }
    if (method === 'library.pull') {
      libraryPulls++;
      assert.ok(Number.isInteger(params.since) && params.since >= 0); assert.equal(params.limit, 200);
      const page = libraryRecords.filter(record => record.seq > params.since).sort((a, b) => a.seq - b.seq).slice(0, params.limit);
      reply(response, 200, { ok: true, result: { records: page, cursor: page.length ? page.at(-1).seq : params.since, more: false, origin: RECEIVER_ORIGIN } }); return;
    }
    if (method === 'library.push') {
      libraryPushes++;
      assert.ok(Array.isArray(params.records) && params.records.length <= 200);
      for (const record of params.records) {
        // Same validation as android-receiver SyncRecord.parse.
        if (!KEY.test(record?.key) || record.type !== 'progress' || !ORIGIN.test(record.origin) || !Number.isInteger(record.updatedAt) || record.updatedAt < 0
          || record.updatedAt > Date.now() + 86400000 || !record.deleted && (typeof record.data !== 'object' || JSON.stringify(record.data).length > 32768)) {
          failure(response, 400, 'INVALID_RECORD'); return;
        }
      }
      let applied = 0;
      for (const record of params.records) {
        if (libraryMerge(record)) applied++;
        if (/^framextv:(?:tv:42:1:\d+|movie:77)$/u.test(record.key)) libraryPushed[record.key] = { origin: record.origin, updatedAt: record.updatedAt, positionSeconds: record.data?.positionSeconds };
      }
      reply(response, 200, { ok: true, result: { applied, cursor: librarySeq } }); return;
    }
    failure(response, 404, 'NOT_FOUND');
  } catch { if (!response.headersSent) failure(response, 400, 'INVALID_INPUT'); }
});
function listen(server, port) { return new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); }); }
try {
  await listen(tcp, 8121); await listen(receiver, 4191);
  for (const [apk, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', appPackage], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${appPackage}.test`]]) {
  const { stdout: apkMetadata } = await run(resolve(androidSdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', resolve(apk)], { timeout: 10000 });
  assert.equal(apkMetadata.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build the intended fixture package before installing.');
    await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
  }
  const { stdout: sdk } = await run(adb, ['-s', serial, 'shell', 'getprop', 'ro.build.version.sdk'], { timeout: 10000 });
  if (Number(sdk.trim()) >= 33) await run(adb, ['-s', serial, 'shell', 'pm', 'grant', appPackage, 'android.permission.POST_NOTIFICATIONS'], { timeout: 10000 });
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'service', '-e', 'targetHost', '10.0.2.2', '-e', 'receiverPort', String(receiver.address().port), `${appPackage}.test/dev.framextv.lumaremote.BackendInstrumentation`], { timeout: 150000, maxBuffer: 128 * 1024 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=18/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.equal(connections, 3);
  assert.ok(authRejections >= 2);
  assert.deepEqual(commands.filter(command => command.includes('setVolume')), [14, 14, 12, 14, 12, 14, 16, 18, 20, 16]
    .map(level => `MediaControl\r\nsetVolume ${level}\r\n\r\n`));
  assert.equal(volume, 8);
  assert.equal(stops, 1);
  assert.equal(contentOpens, 2); assert.equal(seekFailures, 1);
  assert.equal(currentSource, FIRST); assert.equal(playing, true);
  const seekMutations = mutations.filter(message => message.method === 'player.seek');
  const resumedNext = seekMutations.length === 3;
  assert.ok(seekMutations.length === 2 || resumedNext);
  assert.deepEqual(mutations.map(message => message.method), ['player.open', 'player.open', ...(resumedNext ? ['player.seek'] : []), 'player.open', 'player.seek', 'player.seek',
    'player.pause', 'player.resume', 'player.pause', 'player.resume', 'player.stop', 'player.open', 'player.pause', 'player.resume']);
  // Back 30 forwards the absolute position plus the source and canonical guards for the receiver to apply atomically.
  assert.deepEqual(seekMutations.slice(-2).map(message => message.params), [
    { position: 45, expectedSourceUrl: OTHER, expectedContentKey: 'framextv:tv:42:1:6' },
    { position: 0, expectedSourceUrl: OTHER, expectedContentKey: 'framextv:tv:42:1:6' },
  ]);
  for (const message of mutations.filter(message => /^player\.(?:pause|resume|stop|seek)$/u.test(message.method))) assert.equal(typeof message.params.expectedSourceUrl, 'string', `${message.method} did not forward its source guard`);
  const opens = mutations.filter(message => message.method === 'player.open').map(message => message.params);
  assert.deepEqual(opens.map(open => open.media.url), [FIRST, NEXT, OTHER, FIRST]);
  assert.deepEqual(opens.map(open => open.media.content?.key ?? null), [null, 'framextv:tv:42:1:6', 'framextv:tv:42:1:6', null]);
  // Service Next replaces only the exact observed item; same-source Next also forwards its saved resume.
  assert.deepEqual(opens.slice(1, 3).map(open => [open.onlyIfInactive, open.expectedCurrentContentKey]), [[false, 'framextv:tv:42:1:5'], [false, 'framextv:tv:42:1:5']]);
  assert.equal(opens[2].expectedCurrentSourceUrl, OTHER); assert.equal(opens[2].media.resumeSeconds, 120);
  assert.ok(libraryPulls >= 2 && libraryPushes >= 1, 'Two-way Library sync did not run.');
  assert.equal(libraryPushed['framextv:tv:42:1:3']?.positionSeconds, 444);
  assert.deepEqual(pairings.map(({ method, params }) => [method, params.code]), [['pair.begin', undefined], ['pair.complete', '000000'], ['pair.complete', CODE]]);
  assert.deepEqual(commands.filter(command => command.startsWith('KEYEVENT\r\n')), [
    'KEYEVENT\r\n26\r\n\r\n',
  ]);
  assert.ok(commands.every(command => !/^(?:auth|CHECKPASSWORD|STARTAPPEVNET)/u.test(command)));
  console.log('Android connection service: 18 pairing, lifecycle, Library-sync, canonical-transition, locked-screen, rewind, coalesced-volume, transient-error, pending-catalog, independent-transport, disconnect, power and Matter checks passed against emulator-only fixtures.');
} catch (error) {
  console.error(JSON.stringify({ fixture: { connections, stops, playing, mutations: mutations.map(({ method, params }) => ({ method, ...(typeof params.play === 'boolean' ? { play: params.play } : {}) })) } }));
  throw error;
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, receiver]) { server.closeAllConnections?.(); if (server.listening) await new Promise(done => server.close(done)); }
}
