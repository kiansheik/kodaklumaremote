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
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
const commands = [], mutations = [], sockets = new Set();
let currentSource = '', position = 0, playing = true, appLists = 0, connections = 0;
function appFrame() {
  const fields = [];
  for (const [name, pkg] of [['Kodi', 'org.xbmc.kodi'], ['Vídeo', 'example.video']]) {
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
const kodi = http.createServer(async (request, response) => {
  try {
    assert.equal(request.url, '/jsonrpc'); assert.equal(request.headers.authorization, `Basic ${Buffer.from('fixture:fixture').toString('base64')}`);
    const chunks = []; for await (const bytes of request) chunks.push(bytes);
    const message = JSON.parse(Buffer.concat(chunks).toString());
    const { method, params, id } = message; let result;
    if (method === 'Player.Open' && /\/(?:rejected|unknown)\.m3u8$/u.test(params.item.file)) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ jsonrpc: '2.0', id,
        error: { code: params.item.file.endsWith('/rejected.m3u8') ? -32602 : -32100, message: 'Fixture rejection' } }));
      return;
    }
    if (method === 'Player.AddSubtitle' && /\/(?:rejected|unknown|wrong-id|malformed|trailing)\.vtt$/u.test(params.subtitle)) {
      const known = params.subtitle.endsWith('/rejected.vtt') || params.subtitle.endsWith('/trailing.vtt');
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ jsonrpc: '2.0', id: params.subtitle.endsWith('/wrong-id.vtt') ? id + 1 : id,
        error: params.subtitle.endsWith('/malformed.vtt') ? { code: -32100 }
          : { code: known ? -32100 : params.subtitle.endsWith('/wrong-id.vtt') ? -32602 : -32603, message: 'Fixture rejection' } })
          + (params.subtitle.endsWith('/trailing.vtt') ? ' unexpected' : ''));
      return;
    }
    if (method === 'JSONRPC.Ping') result = 'pong';
    else if (method === 'Application.GetProperties') result = { name: 'Kodi', version: { major: 21, minor: 2 } };
    else if (method === 'Player.GetActivePlayers') result = currentSource ? [{ playerid: 1, type: 'video' }] : [];
    else if (method === 'Player.GetItem') result = { item: { file: currentSource } };
    else if (method === 'Player.GetProperties') result = params.properties.includes('subtitles')
      ? { subtitles: [{ index: 0, name: 'English', language: 'en' }, { index: 1, name: 'Português', language: 'pt-br' }], currentsubtitle: { index: -1 }, subtitleenabled: false }
      : { speed: playing ? 1 : 0, time: { hours: 0, minutes: Math.floor(position / 60), seconds: position % 60 }, totaltime: { hours: 0, minutes: 22, seconds: 35 }, canseek: true };
    else {
      mutations.push(message); result = 'OK';
      if (method === 'Player.Open') { currentSource = params.item.file; position = 0; }
      if (method === 'Player.PlayPause') playing = params.play;
      if (method === 'Player.Seek') { const time = params.value.time; position = time.hours * 3600 + time.minutes * 60 + time.seconds; }
      if (method === 'Player.Stop') currentSource = '';
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result }));
  } catch { response.writeHead(400); response.end(); }
});
const slow = http.createServer(request => request.resume());
function listen(server, port) { return new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); }); }
try {
  await listen(tcp, 8121); await listen(kodi, 0); await listen(slow, 0);
  for (const apk of ['android/app/build/outputs/apk/debug/app-debug.apk', 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
    await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
  }
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'targetHost', '10.0.2.2', '-e', 'kodiPort', String(kodi.address().port), '-e', 'slowPort', String(slow.address().port), 'dev.framextv.lumaremote.test/dev.framextv.lumaremote.BackendInstrumentation'], { timeout: 60000, maxBuffer: 128 * 1024 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=9/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.equal(connections, 2); assert.equal(appLists, 1);
  assert.ok(commands.includes('CONTENT\r\n1\r\n0 Olá 👋\r\n\r\n'));
  assert.equal(commands.filter(command => command === 'KEYEVENT\r\n26\r\n\r\n').length, 1);
  assert.ok(commands.includes('STARTAPPEVNET\r\norg.xbmc.kodi\r\n\r\n'));
  assert.equal(commands.filter(command => command.startsWith('STARTAPPEVNET')).length, 1);
  assert.deepEqual(commands.filter(command => command.includes('setVolume')), ['MediaControl\r\nsetVolume 14\r\n\r\n']);
  assert.ok(commands.every(command => !/^(?:auth|CHECKPASSWORD|getServerInfo)/u.test(command)));
  assert.deepEqual(mutations.map(message => message.method), ['Player.Open', 'Player.AddSubtitle', 'Player.SetSubtitle', 'Player.PlayPause', 'Player.PlayPause', 'Player.Seek', 'Player.Stop']);
  assert.equal(mutations[2].params.subtitle, 1);
  console.log('Native backend: 9 instrumentation checks and wire assertions passed against emulator-only fixtures.');
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, kodi, slow]) { server.closeAllConnections?.(); await new Promise(done => server.close(done)); }
}
