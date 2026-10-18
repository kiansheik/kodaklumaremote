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
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
const FIRST = 'https://media.example/first.m3u8';
const OTHER = 'https://media.example/other.m3u8';
const commands = [], mutations = [], sockets = new Set();
let connections = 0, currentSource = '', playing = true, stops = 0, authRejections = 0, holdProperties = false, volume = 6;
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
      if (command === 'MediaControl\r\ngetVolume\r\n\r\n') socket.write(`${volume} 15\r\n\r\n`);
      if (command === 'MediaControl\r\nsetVolume 14\r\n\r\n') volume = 7;
    }
  });
});
const kodi = http.createServer(async (request, response) => {
  try {
    const address = new URL(request.url, 'http://fixture');
    if (request.method === 'GET' && address.pathname === '/fixture') {
      if (address.searchParams.get('source') === 'other') { currentSource = OTHER; playing = true; }
      if (address.searchParams.get('source') === 'first') { currentSource = FIRST; playing = true; }
      if (address.searchParams.get('hold') === '1') holdProperties = true;
      if (address.searchParams.get('hold') === '0') {
        holdProperties = false;
        for (const held of [...heldResponses]) {
          heldResponses.delete(held);
          if (!held.response.destroyed) { held.response.writeHead(200, { 'Content-Type': 'application/json' }); held.response.end(JSON.stringify(held.message)); }
        }
      }
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ source: currentSource, playing, stops, mutations: mutations.length, connections, held: heldResponses.size, volumeKeys: commands.filter(command => command.startsWith('KEYEVENT\r\n')).length })); return;
    }
    assert.equal(request.url, '/jsonrpc');
    if (request.headers.authorization !== `Basic ${Buffer.from('fixture:fixture').toString('base64')}`) { authRejections++; response.writeHead(401); response.end(); return; }
    const chunks = []; for await (const bytes of request) chunks.push(bytes);
    const { method, params, id } = JSON.parse(Buffer.concat(chunks).toString()); let result;
    if (method === 'JSONRPC.Ping') result = 'pong';
    else if (method === 'Application.GetProperties') result = { name: 'Kodi', version: { major: 21, minor: 2 } };
    else if (method === 'Player.GetActivePlayers') result = currentSource ? [{ playerid: 1, type: 'video' }] : [];
    else if (method === 'Player.GetItem') result = { item: { file: currentSource } };
    else if (method === 'Player.GetProperties') result = params.properties.includes('subtitles')
      ? { subtitles: [], currentsubtitle: { index: -1 }, subtitleenabled: false }
      : { speed: playing ? 1 : 0, time: { hours: 0, minutes: 0, seconds: 20 }, totaltime: { hours: 0, minutes: 22, seconds: 35 }, canseek: true };
    else {
      mutations.push({ method, params }); result = 'OK';
      if (method === 'Player.Open') { currentSource = params.item.file; playing = true; }
      if (method === 'Player.PlayPause') playing = params.play;
      if (method === 'Player.Stop') { currentSource = ''; stops++; }
    }
    const message = { jsonrpc: '2.0', id, result };
    if (holdProperties && method === 'Player.GetProperties') {
      const held = { response, message }; heldResponses.add(held);
      response.once('close', () => heldResponses.delete(held)); return;
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(message));
  } catch { response.writeHead(400); response.end(); }
});
function listen(server, port) { return new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); }); }
try {
  await listen(tcp, 8121); await listen(kodi, 0);
  for (const apk of ['android/app/build/outputs/apk/debug/app-debug.apk', 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
    await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
  }
  const { stdout: sdk } = await run(adb, ['-s', serial, 'shell', 'getprop', 'ro.build.version.sdk'], { timeout: 10000 });
  if (Number(sdk.trim()) >= 33) await run(adb, ['-s', serial, 'shell', 'pm', 'grant', 'dev.framextv.lumaremote', 'android.permission.POST_NOTIFICATIONS'], { timeout: 10000 });
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'service', '-e', 'targetHost', '10.0.2.2', '-e', 'kodiPort', String(kodi.address().port), 'dev.framextv.lumaremote.test/dev.framextv.lumaremote.BackendInstrumentation'], { timeout: 90000, maxBuffer: 128 * 1024 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=4/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.equal(connections, 2);
  assert.ok(authRejections >= 2);
  assert.deepEqual(commands.filter(command => command.includes('setVolume')), ['MediaControl\r\nsetVolume 14\r\n\r\n']);
  assert.equal(volume, 7);
  assert.equal(stops, 0);
  assert.equal(currentSource, FIRST); assert.equal(playing, true);
  assert.deepEqual(mutations.map(message => message.method), ['Player.Open', 'Player.PlayPause', 'Player.PlayPause']);
  assert.equal(mutations[1].params.play, false); assert.equal(mutations[2].params.play, true);
  assert.deepEqual(commands.filter(command => command.startsWith('KEYEVENT\r\n')), ['KEYEVENT\r\n24\r\n\r\n']);
  assert.ok(commands.every(command => !/^(?:auth|CHECKPASSWORD|STARTAPPEVNET)/u.test(command)));
  console.log('Android connection service: 4 lifecycle, MediaSession, notification and disconnect checks passed against emulator-only fixtures.');
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, kodi]) { server.closeAllConnections?.(); await new Promise(done => server.close(done)); }
}
