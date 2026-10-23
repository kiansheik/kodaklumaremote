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
const sockets = new Set(), commands = [], mutations = [], rejections = [];
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
const TOKEN = 'fixture_receiver_token_0123456789', SOURCE = 'https://media.example/first.m3u8';
const reply = (response, status, body) => { response.writeHead(status, { 'Content-Type': 'application/json' }); response.end(JSON.stringify(body)); };
// Minimal LUMA Receiver LAN API: only player.addSubtitle is expected, and its
// forwarded source guard is applied before the write is recorded.
const receiver = http.createServer(async (request, response) => {
  try {
    assert.equal(request.method, 'POST'); assert.equal(request.url, '/api/v1/player.addSubtitle');
    if (request.headers.authorization !== `Bearer ${TOKEN}`) { reply(response, 401, { ok: false, error: { code: 'UNAUTHORIZED' } }); return; }
    const chunks = []; let size = 0;
    for await (const bytes of request) { size += bytes.length; assert.ok(size < 8192); chunks.push(bytes); }
    const params = JSON.parse(Buffer.concat(chunks).toString('utf8'));
    if (params.expectedSourceUrl !== SOURCE) { rejections.push(params); reply(response, 409, { ok: false, error: { code: 'SOURCE_CHANGED' } }); return; }
    mutations.push(params); reply(response, 200, { ok: true, result: { accepted: true, index: mutations.length - 1 } });
  } catch { reply(response, 500, { ok: false, error: { code: 'INTERNAL' } }); }
});
const listen = (server, port) => new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); });
try {
  await listen(tcp, 8121); await listen(receiver, 0);
  for (const [apk, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', appPackage], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${appPackage}.test`]]) {
    const { stdout } = await run(resolve(androidSdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', resolve(apk)], { timeout: 10000 });
    assert.equal(stdout.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build the intended fixture package before installing.');
    await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
  }
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'subtitles', '-e', 'targetHost', '10.0.2.2', '-e', 'receiverPort', String(receiver.address().port), `${appPackage}.test/dev.framextv.lumaremote.BackendInstrumentation`], { timeout: 60000, maxBuffer: 32768 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u);
  assert.match(stdout, /INSTRUMENTATION_RESULT: passed=9/u);
  assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.deepEqual(commands, ['getFeatures\r\nframeXTV Remote\r\n\r\n', 'GETPASSWORDCONFIG\r\neshare\r\n\r\n']);
  const port = receiver.address().port;
  assert.deepEqual(mutations, [
    { track: { url: `http://10.0.2.2:${port}/prepared/first.srt`, mimeType: 'application/x-subrip', select: true }, expectedSourceUrl: SOURCE },
    { track: { url: `http://10.0.2.2:${port}/prepared/second.srt`, mimeType: 'application/x-subrip', select: true }, expectedSourceUrl: SOURCE },
  ], 'Expired, stale or captured LAN requests must never emit another player.addSubtitle.');
  assert.deepEqual(rejections, [{ track: { url: 'https://captions.example/subtitle', select: true }, expectedSourceUrl: 'https://media.example/other.m3u8' }],
    'Only the public-track source guard should reach the receiver and be rejected there.');
  console.log('Android subtitles: 9 charset, XML, text conversion and source-bound ticket checks passed.');
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, receiver]) { server.closeAllConnections?.(); await new Promise(done => server.close(done)); }
}
