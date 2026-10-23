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
const commands = [], mutations = [], sockets = new Set();
let currentSource = '', currentMetadata = null, position = 0, playing = true, appLists = 0, connections = 0, addonProbes = 0;
let openingRaceActive = false, openingRaceWrites = 0, fallbackRaceActive = false, fallbackRaceWrites = 0;
function lumaMetadata(params) {
  assert.equal(params.addonid, 'script.lumaremote.metadata');
  assert.equal(params.wait, true); assert.ok(Array.isArray(params.params) && params.params.length === 1);
  assert.match(params.params[0], /^[A-Za-z0-9_-]+$/u);
  const payload = JSON.parse(Buffer.from(params.params[0], 'base64url').toString('utf8'));
  assert.deepEqual(Object.keys(payload).sort(), ['content', 'contentType', 'showTitle', 'source', 'title', 'version']);
  assert.deepEqual(Object.keys(payload.content).sort(), ['contentId', 'episode', 'key', 'kind', 'season', 'watchUrl']);
  assert.equal(payload.version, 1); assert.equal(payload.source, currentSource || 'https://media.example/first.m3u8');
  assert.equal(payload.content.key, 'framextv:tv:42:2:3'); assert.equal(payload.showTitle, 'Fixture Series');
  return { type: 'episode', title: payload.title, showtitle: payload.showTitle,
    season: payload.content.season, episode: payload.content.episode, uniqueid: { luma: `v1:${payload.content.key}` } };
}
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
    if (method === 'Player.Open' && /\/(?:rejected|unknown|false-result|null-result)\.m3u8$/u.test(params.item.file)) {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ jsonrpc: '2.0', id, ...(/\/(?:false|null)-result\.m3u8$/u.test(params.item.file)
        ? { result: params.item.file.endsWith('/false-result.m3u8') ? false : null }
        : { error: { code: params.item.file.endsWith('/rejected.m3u8') ? -32602 : -32100, message: 'Fixture rejection' } }) }));
      return;
    }
    if (method === 'Player.AddSubtitle' && /\/(?:rejected|unknown|wrong-id|malformed|trailing|false-result|null-result)\.vtt$/u.test(params.subtitle)) {
      const known = params.subtitle.endsWith('/rejected.vtt') || params.subtitle.endsWith('/trailing.vtt');
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ jsonrpc: '2.0', id: params.subtitle.endsWith('/wrong-id.vtt') ? id + 1 : id,
        ...(/\/(?:false|null)-result\.vtt$/u.test(params.subtitle)
          ? { result: params.subtitle.endsWith('/false-result.vtt') ? false : null }
          : { error: params.subtitle.endsWith('/malformed.vtt') ? { code: -32100 }
            : { code: known ? -32100 : params.subtitle.endsWith('/wrong-id.vtt') ? -32602 : -32603, message: 'Fixture rejection' } }) })
          + (params.subtitle.endsWith('/trailing.vtt') ? ' unexpected' : ''));
      return;
    }
    if (method === 'JSONRPC.Ping') result = 'pong';
    else if (method === 'Application.GetProperties') result = { name: 'Kodi', version: { major: 21, minor: 2 } };
    else if (method === 'Addons.GetAddonDetails') {
      addonProbes++;
      if (addonProbes === 2) {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found' } }));
        return;
      }
      result = { addon: { addonid: 'script.lumaremote.metadata', type: 'xbmc.python.script', enabled: true } };
    }
    else if (method === 'Player.GetActivePlayers') result = currentSource ? [{ playerid: 1, type: 'video' }] : [];
    else if (method === 'Player.GetItem') result = { item: { file: currentSource, ...(currentMetadata || {}) } };
    else if (method === 'Addons.ExecuteAddon') {
      mutations.push(message); currentMetadata = lumaMetadata(params); currentSource = 'https://media.example/first.m3u8'; position = 0; result = 'OK';
    }
    else if (method === 'Player.GetProperties') result = params.properties.includes('subtitles')
      ? { subtitles: [{ index: 0, name: 'English', language: 'en' }, { index: 1, name: 'Português', language: 'pt-br' }], currentsubtitle: { index: -1 }, subtitleenabled: false }
      : { speed: playing ? 1 : 0, time: { hours: 0, minutes: Math.floor(position / 60), seconds: position % 60 }, totaltime: { hours: 0, minutes: 22, seconds: 35 }, canseek: true };
    else {
      mutations.push(message); result = 'OK';
      if (method === 'Player.Open') { currentSource = params.item.file; currentMetadata = null; position = 0; }
      if (method === 'Player.PlayPause') playing = params.play;
      if (method === 'Player.Seek') { const time = params.value.time; position = time.hours * 3600 + time.minutes * 60 + time.seconds; }
      if (method === 'Player.Stop') { currentSource = ''; currentMetadata = null; }
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result }));
  } catch { response.writeHead(400); response.end(); }
});
const slow = http.createServer(request => request.resume());
const deadline = http.createServer(async (request, response) => {
  const chunks = []; for await (const bytes of request) chunks.push(bytes);
  const { method, id } = JSON.parse(Buffer.concat(chunks).toString());
  const result = method === 'Player.GetActivePlayers' ? [{ playerid: 1, type: 'video' }]
    : method === 'Player.GetItem' ? { item: { file: 'https://media.example/first.m3u8' } }
      : { speed: 1, time: { hours: 0, minutes: 0, seconds: 0 }, totaltime: { hours: 0, minutes: 22, seconds: 35 }, canseek: true };
  setTimeout(() => { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result })); }, 1800);
});
let changed = false;
const changing = http.createServer(async (request, response) => {
  const chunks = []; for await (const bytes of request) chunks.push(bytes);
  const { method, params, id } = JSON.parse(Buffer.concat(chunks).toString()); let result;
  if (method === 'Player.GetActivePlayers') { changed = false; result = [{ playerid: 1, type: 'video' }]; }
  else if (method === 'Player.GetItem') result = { item: { file: `https://media.example/${changed ? 'other' : 'first'}.m3u8` } };
  else if (method === 'Player.GetProperties') {
    changed = true;
    result = params.properties.includes('subtitles')
      ? { subtitles: [{ index: 0, name: 'Other movie', language: 'en' }], currentsubtitle: { index: 0 }, subtitleenabled: true }
      : { speed: 1, time: { hours: 0, minutes: 0, seconds: 0 }, totaltime: { hours: 0, minutes: 22, seconds: 35 }, canseek: true };
  }
  response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result }));
});
let readinessReads = 0;
const readiness = http.createServer(async (request, response) => {
  const chunks = []; for await (const bytes of request) chunks.push(bytes);
  const { method, id } = JSON.parse(Buffer.concat(chunks).toString()); let result;
  if (method === 'Player.GetActivePlayers') result = [{ playerid: 1, type: 'video' }];
  else if (method === 'Player.GetItem') result = { item: { file: 'https://media.example/first.m3u8' } };
  else if (method === 'Player.GetProperties') {
    readinessReads++;
    result = { subtitles: [], subtitleenabled: false,
      ...(readinessReads === 3 ? {} : { currentsubtitle: readinessReads === 1 ? null : readinessReads === 2 ? {} : 'invalid' }) };
  }
  response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result }));
});
const openingRace = http.createServer(async (request, response) => {
  const chunks = []; for await (const bytes of request) chunks.push(bytes);
  const { method, id } = JSON.parse(Buffer.concat(chunks).toString()); let result;
  if (method === 'Player.GetActivePlayers') result = openingRaceActive ? [{ playerid: 1, type: 'video' }] : [];
  else if (method === 'Addons.GetAddonDetails') {
    openingRaceActive = true;
    result = { addon: { addonid: 'script.lumaremote.metadata', type: 'xbmc.python.script', enabled: true } };
  } else if (method === 'Addons.ExecuteAddon' || method === 'Player.Open') {
    openingRaceWrites++;
    result = 'OK';
  } else throw new Error(`Unexpected opening-race method ${method}`);
  response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result }));
});
const fallbackRace = http.createServer(async (request, response) => {
  const chunks = []; for await (const bytes of request) chunks.push(bytes);
  const { method, id } = JSON.parse(Buffer.concat(chunks).toString()); let result;
  if (method === 'Player.GetActivePlayers') result = fallbackRaceActive ? [{ playerid: 1, type: 'video' }] : [];
  else if (method === 'Addons.GetAddonDetails') {
    fallbackRaceActive = true;
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ jsonrpc: '2.0', id, error: { code: -32601, message: 'Method not found' } }));
    return;
  } else if (method === 'Addons.ExecuteAddon' || method === 'Player.Open') {
    fallbackRaceWrites++;
    result = 'OK';
  } else throw new Error(`Unexpected fallback-race method ${method}`);
  response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ jsonrpc: '2.0', id, result }));
});
function listen(server, port) { return new Promise((done, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', done); }); }
try {
  await listen(tcp, 8121); await listen(kodi, 0); await listen(slow, 0); await listen(deadline, 0); await listen(changing, 0); await listen(readiness, 0); await listen(openingRace, 0); await listen(fallbackRace, 0);
  for (const [apk, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', appPackage], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${appPackage}.test`]]) {
  const { stdout: apkMetadata } = await run(resolve(androidSdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', resolve(apk)], { timeout: 10000 });
  assert.equal(apkMetadata.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build the intended fixture package before installing.');
    await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
  }
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'targetHost', '10.0.2.2', '-e', 'kodiPort', String(kodi.address().port), '-e', 'slowPort', String(slow.address().port), '-e', 'deadlinePort', String(deadline.address().port), '-e', 'changingPort', String(changing.address().port), '-e', 'readinessPort', String(readiness.address().port), '-e', 'openingRacePort', String(openingRace.address().port), '-e', 'fallbackRacePort', String(fallbackRace.address().port), `${appPackage}.test/dev.framextv.lumaremote.BackendInstrumentation`], { timeout: 60000, maxBuffer: 128 * 1024 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=13/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.equal(connections, 2); assert.equal(appLists, 1);
  assert.ok(commands.includes('CONTENT\r\n1\r\n0 Olá 👋\r\n\r\n'));
  assert.equal(commands.filter(command => command === 'KEYEVENT\r\n26\r\n\r\n').length, 1);
  assert.ok(commands.includes('STARTAPPEVNET\r\norg.xbmc.kodi\r\n\r\n'));
  assert.equal(commands.filter(command => command.startsWith('STARTAPPEVNET')).length, 1);
  assert.deepEqual(commands.filter(command => command.includes('setVolume')), ['MediaControl\r\nsetVolume 14\r\n\r\n']);
  assert.ok(commands.every(command => !/^(?:auth|CHECKPASSWORD|getServerInfo)/u.test(command)));
  assert.deepEqual(mutations.map(message => message.method), ['Player.Open', 'Player.Open', 'Addons.ExecuteAddon', 'Player.Open', 'Addons.ExecuteAddon', 'Player.AddSubtitle', 'Player.SetSubtitle', 'Player.PlayPause', 'Player.PlayPause', 'Player.Seek', 'Player.Stop']);
  assert.equal(mutations.find(message => message.method === 'Player.SetSubtitle').params.subtitle, 1);
  assert.equal(addonProbes, 3);
  assert.ok(mutations.filter(message => message.method === 'Addons.ExecuteAddon').every(message => !message.params.params[0].includes('https://')));
  assert.equal(readinessReads, 4);
  assert.equal(openingRaceWrites, 0);
  assert.equal(fallbackRaceWrites, 0);
  console.log('Native backend: 13 instrumentation checks and wire assertions passed against emulator-only fixtures.');
} finally {
  for (const socket of sockets) socket.destroy();
  for (const server of [tcp, kodi, slow, deadline, changing, readiness, openingRace, fallbackRace]) { server.closeAllConnections?.(); await new Promise(done => server.close(done)); }
}
