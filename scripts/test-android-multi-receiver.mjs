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
const kodi = http.createServer(async (request, response) => {
  try {
    response.setHeader('Content-Type', 'application/json');
    if (request.method === 'GET' && request.url === '/fixture') {
      response.end(JSON.stringify({connections, stops, playing, position, mutations: mutations.length})); return;
    }
    assert.equal(request.url, '/jsonrpc');
    assert.equal(request.headers.authorization, `Basic ${Buffer.from('fixture:fixture').toString('base64')}`);
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const {method, params, id} = JSON.parse(Buffer.concat(chunks).toString()); let result;
    if (method === 'JSONRPC.Ping') result = 'pong';
    else if (method === 'Application.GetProperties') result = {name: 'Kodi', version: {major: 21, minor: 2}};
    else if (method === 'Player.GetActivePlayers') result = [{playerid: 1, type: 'video'}];
    else if (method === 'Player.GetItem') result = {item: {file: currentSource}};
    else if (method === 'Player.GetProperties') result = {speed: playing ? 1 : 0, time: {hours: Math.floor(position / 3600), minutes: Math.floor(position / 60) % 60, seconds: position % 60},
      totaltime: {hours: 0, minutes: 2, seconds: 0}, canseek: true, subtitles: [], currentsubtitle: {index: -1}, subtitleenabled: false};
    else {
      mutations.push({method, params}); result = 'OK';
      if (method === 'Player.PlayPause') playing = params.play;
      if (method === 'Player.Seek') { const time = params.value.time; position = time.hours * 3600 + time.minutes * 60 + time.seconds; }
      if (method === 'Player.Stop') stops++;
    }
    response.end(JSON.stringify({jsonrpc: '2.0', id, result}));
  } catch { response.writeHead(400); response.end('{}'); }
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
  await listen(tcp, 8121); await listen(kodi, 0);
  for (const [apk] of apks) await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], {timeout: 60000});
  const {stdout: version} = await run(adb, ['-s', serial, 'shell', 'getprop', 'ro.build.version.sdk'], {timeout: 10000});
  if (Number(version.trim()) >= 33) await run(adb, ['-s', serial, 'shell', 'pm', 'grant', appPackage, 'android.permission.POST_NOTIFICATIONS'], {timeout: 10000});
  const {stdout, stderr} = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'multi-receiver',
    '-e', 'targetHost', '10.0.2.2', '-e', 'kodiPort', String(kodi.address().port), `${appPackage}.test/dev.framextv.lumaremote.BackendInstrumentation`], {timeout: 90000, maxBuffer: 128 * 1024});
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=5/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.equal(connections, 2); assert.equal(stops, 0); assert.equal(playing, false);
  assert.deepEqual(mutations, [
    {method: 'Player.Seek', params: {playerid: 1, value: {time: {hours: 0, minutes: 0, seconds: 0, milliseconds: 0}}}},
    {method: 'Player.PlayPause', params: {playerid: 1, play: false}},
  ]);
  assert.deepEqual(keys, ['KEYEVENT\r\n22\r\n\r\n']);
  console.log('Android dual receiver: 5 independent-transport, canonical-handoff, queue, selected-system-control and disconnect checks passed.');
} catch (error) {
  console.error(JSON.stringify({fixture: {connections, stops, playing, mutations: mutations.map(({method}) => method), keys: keys.length}})); throw error;
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, kodi]) { server.closeAllConnections?.(); if (server.listening) await new Promise(resolve => server.close(resolve)); }
}
