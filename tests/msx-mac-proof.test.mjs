import assert from 'node:assert/strict';
import http from 'node:http';
import net from 'node:net';
import {chmod, mkdtemp, rm, symlink, writeFile} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {createProofServer, parseArgs, privateIPv4, publicUrl, readPrivateConfig, sanitizeStatus, validateCommand} from '../scripts/msx-mac-proof.mjs';

const SOURCE = 'https://media.example/video.m3u8';
const OTHER = 'https://media.example/other.mp4';
const CLIENT = 'fixture-tv-client-1234';
const state = (source = SOURCE) => ({active: !!source, source, title: 'Synthetic video', paused: false,
  positionSeconds: 1, durationSeconds: 200, canSeek: true, volume: {current: 50, max: 100}, tracks: [],
  selectedIndex: -1, subtitlesEnabled: false, error: null});

async function request(url, {method = 'GET', json, raw, headers = {}, localAddress, stall = false} = {}) {
  const body = json === undefined ? raw : JSON.stringify(json);
  return new Promise((resolve, reject) => {
    const req = http.request(url, {method, localAddress, headers: {...(body === undefined ? {} : {
      'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(body)}), ...headers}}, res => {
      const chunks = [];
      res.on('data', chunk => chunks.push(chunk));
      res.on('error', reject);
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8'); let value;
        try { value = JSON.parse(text); } catch { value = text; }
        resolve({status: res.statusCode, body: value, text, headers: res.headers});
      });
    });
    req.setTimeout(2000, () => req.destroy(new Error('Fixture request timed out')));
    req.on('error', reject);
    if (stall) req.flushHeaders(); else req.end(body);
  });
}

async function fixture(t, options = {}) {
  const proof = createProofServer({host: '127.0.0.1', tvHost: '127.0.0.1', port: 0,
    allowLoopbackForTests: true, openTimeoutMs: 500, commandTimeoutMs: 500, ...options});
  await proof.listen(); t.after(() => proof.close());
  const receiver = proof.receiverUrl.replace(/receiver\.html$/, '');
  const control = proof.controlUrl;
  const hello = async (client = CLIENT) => request(`${receiver}hello?client=${client}`);
  const connected = async () => {
    const response = await hello(); assert.equal(response.status, 200);
    const session = {client: CLIENT, sessionId: response.body.sessionId};
    return {...session,
      poll: (after = 0) => request(`${receiver}poll?client=${CLIENT}&sessionId=${session.sessionId}&after=${after}`),
      state: playback => request(`${receiver}state`, {method: 'POST', json: {...session, playback}}),
      ack: (id, ok = true, error) => request(`${receiver}ack`, {method: 'POST', json: {...session, id, ok, error}})};
  };
  return {proof, receiver, control, hello, connected,
    command: (method, params) => request(`${control}command`, {method: 'POST', json: {method, params}})};
}

async function queued(f) {
  for (let attempt = 0; attempt < 100; attempt++) {
    const status = (await request(`${f.control}status`)).body;
    if (status.pendingCommand) return status.pendingCommand;
    await new Promise(resolve => setTimeout(resolve, 2));
  }
  assert.fail('Fixture command did not queue');
}

test('CLI requires explicit private host/TV and accepts only the fixed server options', () => {
  assert.deepEqual(parseArgs(['--host', '192.168.1.3', '--tv-host', '192.168.1.7']), {host: '192.168.1.3', tvHost: '192.168.1.7', port: 4179});
  for (const args of [[], ['--host', '192.168.1.3'], ['--host', '127.0.0.1', '--tv-host', '192.168.1.7'],
    ['--host', '0.0.0.0', '--tv-host', '192.168.1.7'], ['--host', '192.168.1.3', '--tv-host', '8.8.8.8'],
    ['--host', '192.168.1.3', '--tv-host', '192.168.1.7', '--port', '0'],
    ['--host', '192.168.1.3', '--tv-host', '192.168.1.7', '--allow-loopback', 'true']]) assert.throws(() => parseArgs(args));
  assert.throws(() => createProofServer({host: '127.0.0.1', tvHost: '127.0.0.1'}), /PRIVATE_IPV4_REQUIRED/);
  assert.equal(privateIPv4('172.31.255.1'), true); assert.equal(privateIPv4('172.32.0.1'), false);
});

test('media validation rejects credentials, local/special hosts and unsupported inputs without fetching', () => {
  for (const source of ['file:///tmp/video.mp4', 'https://user:secret@media.example/a', 'http://127.0.0.1/a',
    'http://192.168.1.2/a', 'http://localhost/a', 'http://server.local/a', 'http://169.254.1.1/a',
    'http://100.64.0.1/a', 'https://[::1]/a', 'http://198.51.100.2/a', 'http://2130706433/a',
    'https://media.example/a#fragment', 'https://media.example/a\\b', 'https://media.example/a|Header=x', 'https://media.example/a\n']) {
    assert.throws(() => publicUrl(source), /INVALID_MEDIA/);
  }
  assert.equal(publicUrl(SOURCE), SOURCE);
  assert.throws(() => validateCommand('open', {source: SOURCE, subtitles: [{url: 'http://10.1.1.1/sub.srt'}]}), /INVALID_MEDIA/);
  assert.throws(() => validateCommand('seek', {seconds: 3}), /INVALID_MEDIA/);
  assert.throws(() => validateCommand('playPause', {expectedSourceUrl: SOURCE, play: 'yes'}));
  assert.throws(() => validateCommand('volume', {expectedSourceUrl: SOURCE, value: 101}));
  assert.throws(() => validateCommand('subtitle', {expectedSourceUrl: SOURCE, index: 1.1, enabled: true}));
  assert.equal(validateCommand('open', {source: SOURCE, title: 'Film https://secret.example/path'}).title, 'Film');
});

test('only the pinned TV capability serves allowlisted existing receiver/HLS assets; roots expose nothing', async t => {
  const f = await fixture(t);
  const receiverOrigin = new URL(f.receiver).origin;
  for (const pathname of ['/', '/msx/start.json', '/receiver.html', '/not-a-capability/receiver.html']) {
    const response = await request(receiverOrigin + pathname); assert.equal(response.status, 404);
    assert.equal(response.text.includes(new URL(f.receiver).pathname), false);
  }
  const html = await request(f.proof.receiverUrl); assert.equal(html.status, 200);
  assert.match(html.body, /<video id="video"/); assert.match(html.body, /src="receiver.js"/);
  assert.equal(html.headers['referrer-policy'], 'no-referrer');
  for (const name of ['receiver.js', 'receiver-core.js', 'receiver.css', 'hls.min.js']) assert.equal((await request(f.receiver + name)).status, 200);
  assert.equal((await request(f.receiver + '../package.json')).status, 404);
  const wrongPeer = await fixture(t, {tvHost: '192.168.200.200'});
  assert.equal((await request(wrongPeer.proof.receiverUrl)).status, 403);
  assert.equal((await request(wrongPeer.receiver + 'hello?client=' + CLIENT)).status, 403);
  assert.equal((await request(new URL(f.control).origin + '/status')).status, 404);
  assert.equal(new URL(f.control).hostname, '127.0.0.1');
  const status = await request(f.control + 'status'); assert.equal(status.status, 200);
  assert.equal(status.text.includes(new URL(f.receiver).pathname), false);
  assert.equal(status.text.includes(new URL(f.control).pathname), false);
});

test('origins and host headers are restricted independently on receiver and loopback control', async t => {
  const f = await fixture(t);
  for (const url of [f.proof.receiverUrl, f.control + 'status']) {
    for (const origin of ['https://attacker.example', 'https://msx.benzac.de']) assert.equal((await request(url, {headers: {Origin: origin}})).status, 403);
    for (const origin of ['null', new URL(url).origin]) assert.equal((await request(url, {headers: {Origin: origin}})).status, 200);
    assert.equal((await request(url, {headers: {Host: 'attacker.example'}})).status, 403);
    assert.equal((await request(url, {method: 'OPTIONS'})).status, 404);
  }
});

test('receiver diagnostics distinguish TCP contact, TLS upgrade and malformed HTTP without retaining packets', async t => {
  const f = await fixture(t), target = new URL(f.receiver);
  async function sendRaw(bytes) {
    await new Promise((resolve, reject) => {
      const socket = net.createConnection({host: '127.0.0.1', port: +target.port}, () => socket.end(bytes));
      socket.on('data', () => {}); socket.on('error', reject); socket.on('close', resolve);
      socket.setTimeout(500, () => socket.destroy(new Error('Fixture socket timed out')));
    });
  }
  await sendRaw(Buffer.from([22, 3, 1, 0, 1, 0]));
  await sendRaw(Buffer.from('GARBAGE HTTP/1.1\r\n\r\n'));
  const status = f.proof.status();
  assert.equal(status.diagnostics.receiverConnections, 2);
  assert.equal(status.diagnostics.receiverTls, 1);
  assert.equal(status.diagnostics.receiverMalformed, 1);
  assert.equal(sanitizeStatus(status).diagnostics.receiverTls, 1);
  assert.equal(JSON.stringify(status).includes('rawPacket'), false);
});

test('one persistent TV client claims the listener and a new hello revokes stale session/queue', async t => {
  const f = await fixture(t), tv = await f.connected();
  assert.equal((await f.hello('different-tv-client-5678')).status, 403);
  const command = f.command('open', {source: SOURCE}); await queued(f);
  const again = await f.hello(); assert.equal(again.body.approved, true); assert.notEqual(again.body.sessionId, tv.sessionId);
  assert.equal((await command).body.error, 'SESSION_CHANGED');
  assert.equal((await tv.poll()).status, 403);
  assert.equal((await tv.state(state())).status, 403);
  const status = await request(f.control + 'status'); assert.equal(status.body.connected, false); assert.equal(status.body.pendingCommand, undefined);
});

test('Open returns only after delivered ACK; polls never repeat it and duplicate ACK is harmless', async t => {
  const f = await fixture(t), tv = await f.connected();
  let complete = false;
  const command = f.command('open', {source: SOURCE, mimeType: 'application/vnd.apple.mpegurl'}).then(value => { complete = true; return value; });
  const pending = await queued(f); assert.equal(complete, false);
  const poll = await tv.poll(); assert.equal(poll.body.commands.length, 1); assert.equal(poll.body.commands[0].params.source, SOURCE);
  assert.equal((await tv.poll()).body.commands.length, 0); assert.equal(complete, false);
  assert.equal((await tv.state(state())).status, 200); assert.equal(complete, false);
  assert.equal((await tv.ack(pending.id)).status, 200);
  const result = await command; assert.equal(result.status, 200); assert.equal(result.body.accepted, true);
  assert.equal(result.body.pendingCommand, undefined); assert.equal(result.body.playback.source, SOURCE);
  assert.equal((await tv.ack(pending.id)).status, 200);
  assert.equal((await tv.poll(pending.id)).body.commands.length, 0);
  assert.equal(f.proof.status().lastCommand.id, pending.id);
});

test('ACK cannot complete an undelivered command and receiver rejection exposes only fixed error codes', async t => {
  const f = await fixture(t), tv = await f.connected();
  const command = f.command('open', {source: SOURCE}); const pending = await queued(f);
  assert.equal((await tv.ack(pending.id)).status, 409);
  await tv.poll(); await tv.state(state(''));
  await tv.ack(pending.id, false, 'PLAYBACK_REJECTED');
  const result = await command; assert.equal(result.status, 409); assert.equal(result.body.receiverError, 'PLAYBACK_REJECTED');
  const second = f.command('open', {source: SOURCE}); const next = await queued(f);
  await tv.poll(); await tv.ack(next.id, false, 'https://secret.example/token');
  const rejected = await second; assert.equal(rejected.body.receiverError, 'RECEIVER_ERROR'); assert.equal(rejected.text.includes('secret.example'), false);
});

test('source guards reject stale controls both when queued and immediately before delivery', async t => {
  const f = await fixture(t), tv = await f.connected(); await tv.state(state());
  assert.equal((await f.command('seek', {seconds: 4, expectedSourceUrl: OTHER})).body.error, 'SOURCE_CHANGED');
  assert.equal((await f.command('stop', {})).body.error, 'INVALID_MEDIA');
  const command = f.command('seek', {seconds: 4, expectedSourceUrl: SOURCE}); await queued(f);
  await tv.state(state(OTHER)); assert.deepEqual((await tv.poll()).body.commands, []);
  assert.equal((await command).body.error, 'SOURCE_CHANGED');
  const stop = f.command('stop', {expectedSourceUrl: OTHER}); const pending = await queued(f);
  assert.equal((await tv.poll()).body.commands[0].method, 'stop'); await tv.state(state('')); await tv.ack(pending.id);
  assert.equal((await stop).body.accepted, true);
});

test('a delivered unknown Open blocks all retries until receipt; no automatic Open is sent', async t => {
  const f = await fixture(t, {openTimeoutMs: 50}), tv = await f.connected();
  const command = f.command('open', {source: SOURCE}); const pending = await queued(f); await tv.poll();
  const result = await command; assert.equal(result.status, 504); assert.equal(result.body.delivered, true);
  assert.equal((await f.command('open', {source: OTHER})).body.error, 'MSX_PENDING_COMMAND');
  assert.deepEqual((await tv.poll()).body.commands, []);
  await tv.state(state()); await tv.ack(pending.id);
  assert.equal(f.proof.status().pendingCommand, undefined);
  assert.equal(f.proof.status().lastCommand.ok, true);
});

test('an unsent timeout removes only the unsent command and allows an explicit subsequent request', async t => {
  const f = await fixture(t, {openTimeoutMs: 50}), tv = await f.connected();
  const first = await f.command('open', {source: SOURCE}); assert.equal(first.body.delivered, false);
  assert.equal(f.proof.status().pendingCommand, undefined);
  const second = f.command('open', {source: OTHER}); const pending = await queued(f);
  const commands = (await tv.poll()).body.commands; assert.equal(commands.length, 1); assert.equal(commands[0].params.source, OTHER);
  await tv.state(state(OTHER)); await tv.ack(pending.id); assert.equal((await second).body.accepted, true);
});

test('state/body/query validation is bounded and stale heartbeat cannot masquerade as fresh playback state', async t => {
  let now = 1000;
  const f = await fixture(t, {now: () => now, bodyTimeoutMs: 40}), tv = await f.connected();
  assert.equal((await tv.state({...state(), source: 'http://10.1.1.1/private'})).status, 400);
  assert.equal((await tv.state({...state(), tracks: [{index: 0}, {index: 0}]})).status, 400);
  assert.equal((await request(f.receiver + `poll?client=${CLIENT}&client=${CLIENT}&sessionId=${tv.sessionId}&after=0`)).status, 400);
  assert.equal((await request(f.receiver + 'state', {method: 'POST', raw: 'not JSON'})).status, 400);
  assert.equal((await request(f.receiver + 'state', {method: 'POST', raw: '{}', headers: {'Content-Type': 'text/plain'}})).status, 415);
  assert.equal((await request(f.receiver + 'state', {method: 'POST', raw: 'a'.repeat(128 * 1024 + 1)})).status, 413);
  const stalled = await request(f.receiver + 'state', {method: 'POST', headers: {'Content-Type': 'application/json', 'Content-Length': '10'}, stall: true});
  assert.equal(stalled.status, 408);
  await tv.state(state()); assert.equal(f.proof.status().connected, true);
  now += 12001; await tv.poll(); assert.equal(f.proof.status().connected, false);
  now += 12001; assert.equal((await f.command('open', {source: SOURCE})).body.error, 'MSX_NOT_CONNECTED');
});

test('private client config refuses symlinks/public modes/remote origins and sanitization removes media/capabilities', async t => {
  const directory = await mkdtemp(path.join(tmpdir(), 'msx-proof-fixture-')); t.after(() => rm(directory, {recursive: true, force: true}));
  const artifact = path.join(directory, 'config.json');
  const base = {proof: true, pid: process.pid, controlUrl: 'http://127.0.0.1:4179/' + 'a'.repeat(32) + '/'};
  await writeFile(artifact, JSON.stringify(base), {mode: 0o600}); assert.deepEqual(await readPrivateConfig(artifact), {controlUrl: base.controlUrl});
  await chmod(artifact, 0o644); await assert.rejects(readPrivateConfig(artifact), /PRIVATE_CONFIG_REQUIRED/); await chmod(artifact, 0o600);
  const linked = path.join(directory, 'link.json'); await symlink(artifact, linked); await assert.rejects(readPrivateConfig(linked));
  for (const controlUrl of ['http://attacker.example/' + 'a'.repeat(32) + '/', 'http://127.0.0.1:4179/path/',
    'http://127.0.0.1:4179/' + 'a'.repeat(32) + '/?redirect=evil', 'http://user@127.0.0.1:4179/' + 'a'.repeat(32) + '/']) {
    await writeFile(artifact, JSON.stringify({...base, controlUrl})); await assert.rejects(readPrivateConfig(artifact), /INVALID_CONFIG/);
  }
  await writeFile(artifact, ' '.repeat(16385)); await assert.rejects(readPrivateConfig(artifact), /PRIVATE_CONFIG_REQUIRED/);
  const safe = sanitizeStatus({running: true, connected: true, playback: state(), accepted: true, id: 1,
    receiverUrl: 'SECRET', controlUrl: 'SECRET', lastCommand: {id: 1, ok: false, error: 'PLAYBACK_REJECTED'}});
  assert.equal(JSON.stringify(safe).includes(SOURCE), false); assert.equal(JSON.stringify(safe).includes('SECRET'), false);
  assert.equal(safe.lastCommand.error, 'PLAYBACK_REJECTED'); assert.equal(safe.accepted, true);
});
