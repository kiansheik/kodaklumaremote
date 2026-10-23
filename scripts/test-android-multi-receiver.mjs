import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {homedir} from 'node:os';
import {resolve} from 'node:path';
import {StringDecoder} from 'node:string_decoder';

// Isolated emulator package only. Both fake receiver services bind Mac loopback.
const run = promisify(execFile);
const sdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
const adb = process.env.ADB || resolve(sdk, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
const appPackage = process.env.ANDROID_TEST_PACKAGE || 'dev.framextv.lumaremote.receiverfixture';
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
assert.equal(appPackage, 'dev.framextv.lumaremote.receiverfixture', 'This test only resets the isolated receiver fixture app.');
const sockets = new Set(), keys = [], mutations = [];
let connections = 0, stops = 0, playing = true, position = 20;
const currentSource = 'https://media.example/luma-first.m3u8';
const tcp = net.createServer(socket => {
  connections++; sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
  let pending = ''; const decoder = new StringDecoder('utf8');
  socket.on('data', bytes => {
    pending += decoder.write(bytes); let end;
    while ((end = pending.indexOf('\r\n\r\n')) >= 0) {
      const command = pending.slice(0, end + 4); pending = pending.slice(end + 4);
      if (command.startsWith('getFeatures\r\n')) socket.write('server_features:sn=0xbf m=Smart Projector ap=LUMA fixture');
      else if (command.startsWith('GETPASSWORDCONFIG\r\n')) socket.write('configed none\r\n\r\n');
      else if (command === 'MediaControl\r\ngetVolume\r\n\r\n') socket.write('6 15\r\n\r\n');
      else if (command.startsWith('KEYEVENT\r\n')) keys.push(command);
    }
  });
});
const TOKEN = 'fixture_receiver_token_0123456789';
function reply(response, status, body) { response.writeHead(status, {'Content-Type': 'application/json'}); response.end(JSON.stringify(body)); }
// Minimal LUMA Receiver LAN API (android-receiver ControlServer envelope).
const receiver = http.createServer(async (request, response) => {
  try {
    if (request.method === 'GET' && request.url === '/fixture') {
      reply(response, 200, {connections, stops, playing, position, mutations: mutations.length}); return;
    }
    if (request.method === 'GET' && request.url === '/api/v1/info') {
      reply(response, 200, {ok: true, result: {app: 'lumareceiver', versionName: 'fixture', receiverId: 'fixture-receiver'}}); return;
    }
    assert.equal(request.method, 'POST'); assert.match(request.url, /^\/api\/v1\/[a-z]+\.[A-Za-z]+$/u);
    if (request.headers.authorization !== `Bearer ${TOKEN}`) { reply(response, 401, {ok: false, error: {code: 'UNAUTHORIZED'}}); return; }
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const method = request.url.slice('/api/v1/'.length), params = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    const guarded = () => {
      if ('expectedSourceUrl' in params && params.expectedSourceUrl !== currentSource) { reply(response, 409, {ok: false, error: {code: 'SOURCE_CHANGED'}}); return false; }
      return true;
    };
    let result;
    if (method === 'player.status') result = {revision: mutations.length, active: true, playing, paused: !playing, state: playing ? 'playing' : 'paused',
      positionSeconds: position, durationSeconds: 120, bufferedSeconds: 120, canSeek: true, sourceUrl: currentSource, title: 'Fixture'};
    else if (method === 'player.subtitles') result = {active: true, enabled: false, currentIndex: null, tracks: []};
    else if (method === 'library.pull') result = {records: [], cursor: 0, more: false, origin: 'fixture-multi-receiver'};
    else if (method === 'library.push') result = {applied: params.records.length, cursor: 0};
    else if (['player.pause', 'player.resume', 'player.seek', 'player.stop'].includes(method)) {
      if (!guarded()) return;
      mutations.push({method, params}); result = {accepted: true};
      if (method === 'player.pause' || method === 'player.resume') playing = method === 'player.resume';
      if (method === 'player.seek') position = params.position;
      if (method === 'player.stop') stops++;
    } else { reply(response, 404, {ok: false, error: {code: 'NOT_FOUND'}}); return; }
    reply(response, 200, {ok: true, result});
  } catch { reply(response, 400, {ok: false, error: {code: 'INVALID_INPUT'}}); }
});
function listen(server, port) { return new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); }); }
try {
  const apks = [
    ['android/app/build/outputs/apk/debug/app-debug.apk', appPackage],
    ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${appPackage}.test`],
  ];
  for (const [apk, expected] of apks) {
    const {stdout} = await run(resolve(sdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', resolve(apk)], {timeout: 10000});
    assert.equal(stdout.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build with -PreceiverFixture=true before running.');
  }
  await listen(tcp, 8121); await listen(receiver, 0);
  for (const [apk] of apks) await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], {timeout: 60000});
  const {stdout: version} = await run(adb, ['-s', serial, 'shell', 'getprop', 'ro.build.version.sdk'], {timeout: 10000});
  if (Number(version.trim()) >= 33) await run(adb, ['-s', serial, 'shell', 'pm', 'grant', appPackage, 'android.permission.POST_NOTIFICATIONS'], {timeout: 10000});
  const {stdout, stderr} = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'multi-receiver',
    '-e', 'targetHost', '10.0.2.2', '-e', 'receiverPort', String(receiver.address().port), `${appPackage}.test/dev.framextv.lumaremote.BackendInstrumentation`], {timeout: 90000, maxBuffer: 128 * 1024});
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=5/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.equal(connections, 2); assert.equal(stops, 0); assert.equal(playing, false);
  assert.deepEqual(mutations, [
    {method: 'player.seek', params: {position: 0, expectedSourceUrl: currentSource}},
    {method: 'player.pause', params: {expectedSourceUrl: currentSource}},
  ]);
  assert.deepEqual(keys, ['KEYEVENT\r\n22\r\n\r\n']);
  console.log('Android dual receiver: 5 independent-transport, canonical-handoff, queue, selected-system-control and disconnect checks passed.');
} catch (error) {
  console.error(JSON.stringify({fixture: {connections, stops, playing, mutations: mutations.map(({method}) => method), keys: keys.length}})); throw error;
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, receiver]) { server.closeAllConnections?.(); if (server.listening) await new Promise(resolve => server.close(resolve)); }
}
