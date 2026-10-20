import http from 'node:http';
import net from 'node:net';
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { homedir } from 'node:os';

// Explicit isolated emulator only. No browser, discovery, saved setup or household receiver actions.
const run = promisify(execFile);
const androidSdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
const adb = process.env.ADB || resolve(androidSdk, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
const appPackage = process.env.ANDROID_TEST_PACKAGE || 'dev.framextv.lumaremote.receiverfixture';
if (appPackage !== 'dev.framextv.lumaremote.receiverfixture') throw new Error('Use the isolated receiver fixture APK.');
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
const sockets = new Set(), commands = [], mutations = [];
const tcp = net.createServer(socket => {
  sockets.add(socket); socket.on('error', () => {}); socket.on('close', () => sockets.delete(socket));
  let pending = '';
  socket.on('data', bytes => {
    pending += bytes.toString('utf8');
    let end;
    while ((end = pending.indexOf('\r\n\r\n')) >= 0) {
      const command = pending.slice(0, end + 4); pending = pending.slice(end + 4); commands.push(command);
      if (command.startsWith('getFeatures\r\n')) socket.write('server_features:sn=0xbf m=Smart Projector ap=Subtitle fixture');
      else if (command === 'GETPASSWORDCONFIG\r\neshare\r\n\r\n') socket.write('configed none');
      else socket.destroy(new Error('Unexpected fixture command.'));
    }
  });
});
const kodi = http.createServer(async (request, response) => {
  try {
    assert.equal(request.url, '/jsonrpc'); assert.equal(request.method, 'POST');
    assert.equal(request.headers.authorization, `Basic ${Buffer.from('fixture:fixture').toString('base64')}`);
    const chunks = []; let size = 0;
    for await (const bytes of request) { size += bytes.length; assert.ok(size < 8192); chunks.push(bytes); }
    const message = JSON.parse(Buffer.concat(chunks).toString('utf8')); const { method, id } = message;
    let result;
    if (method === 'Player.GetActivePlayers') result = [{ playerid: 1, type: 'video' }];
    else if (method === 'Player.GetItem') result = { item: { file: 'https://media.example/first.m3u8' } };
    else if (method === 'Player.AddSubtitle') { mutations.push(message); result = 'OK'; }
    else throw new Error('Unexpected Kodi fixture command.');
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result }));
  } catch (error) { response.writeHead(500); response.end(); }
});
const listen = (server, port) => new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); });
try {
  await listen(tcp, 8121); await listen(kodi, 0);
  for (const [apk, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', appPackage], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${appPackage}.test`]]) {
    const { stdout } = await run(resolve(androidSdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', resolve(apk)], { timeout: 10000 });
    assert.equal(stdout.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build the intended fixture package before installing.');
    await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
  }
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'subtitles', '-e', 'targetHost', '10.0.2.2', '-e', 'kodiPort', String(kodi.address().port), `${appPackage}.test/dev.framextv.lumaremote.BackendInstrumentation`], { timeout: 60000, maxBuffer: 32768 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u);
  assert.match(stdout, /INSTRUMENTATION_RESULT: passed=8/u);
  assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.deepEqual(commands, ['getFeatures\r\nframeXTV Remote\r\n\r\n', 'GETPASSWORDCONFIG\r\neshare\r\n\r\n']);
  assert.deepEqual(mutations.map(message => message.params), [
    { playerid: 1, subtitle: `http://10.0.2.2:${kodi.address().port}/prepared/first.srt` },
    { playerid: 1, subtitle: `http://10.0.2.2:${kodi.address().port}/prepared/second.srt` },
  ], 'Expired, stale or captured LAN requests must never emit another AddSubtitle.');
  console.log('Android subtitles: 8 charset, XML, text conversion and source-bound ticket checks passed.');
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, kodi]) { server.closeAllConnections?.(); await new Promise(done => server.close(done)); }
}
