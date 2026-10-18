import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { once } from 'node:events';
import { PUBLIC_SAMPLE_URL, validateReceiver, performAction, createControllerServer } from '../scripts/kodi-controller.mjs';

const receiver = { host: '192.168.1.23', port: 8080, username: 'kodi', password: 'private-test-password' };
const playback = {
  speed: 1, time: { hours: 0, minutes: 1, seconds: 5, milliseconds: 0 },
  totaltime: { hours: 0, minutes: 9, seconds: 56, milliseconds: 0 }, canseek: true
};

function mockKodi(overrides = {}) {
  const calls = [];
  const defaults = {
    'JSONRPC.Ping': 'pong',
    'Application.GetProperties': { name: 'Kodi', version: { major: 21, minor: 3 } },
    'Player.Open': 'OK',
    'Player.GetActivePlayers': [{ playerid: 0, type: 'audio' }, { playerid: 2, type: 'video' }],
    'Player.GetProperties': playback,
    'Player.PlayPause': { speed: 1 },
    'Player.Seek': { time: playback.time, totaltime: playback.totaltime },
    'Player.Stop': 'OK'
  };
  return {
    calls,
    async fetchImpl(url, init) {
      const rpc = JSON.parse(init.body);
      calls.push({ url, init, rpc });
      assert.ok(Object.hasOwn(defaults, rpc.method), `Unexpected method ${rpc.method}`);
      const result = Object.hasOwn(overrides, rpc.method) ? overrides[rpc.method] : defaults[rpc.method];
      return new Response(JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }));
    }
  };
}

async function localServer(t, fetchImpl) {
  const server = createControllerServer({ fetchImpl });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())));
  const port = server.address().port;
  const origin = `http://127.0.0.1:${port}`;
  async function request({ method = 'POST', path = '/api/kodi', headers = {}, body = JSON.stringify({ receiver, action: 'connect' }) } = {}) {
    return new Promise((resolve, reject) => {
      const outgoingHeaders = { Host: `127.0.0.1:${port}`, Origin: origin, 'Content-Type': 'application/json', ...headers };
      for (const key of Object.keys(outgoingHeaders)) if (outgoingHeaders[key] === undefined) delete outgoingHeaders[key];
      const req = http.request({
        host: '127.0.0.1', port, method, path,
        headers: outgoingHeaders
      }, response => {
        const chunks = [];
        response.on('data', chunk => chunks.push(chunk));
        response.on('end', () => resolve({ status: response.statusCode, headers: response.headers, text: Buffer.concat(chunks).toString('utf8') }));
      });
      req.on('error', reject);
      req.end(method === 'GET' ? undefined : body);
    });
  }
  return { request, origin };
}

test('Kodi receiver accepts only literal RFC1918 IPv4 endpoints and valid Basic credentials', () => {
  for (const host of ['10.0.1.2', '172.16.0.2', '172.31.1.2', '192.168.1.23']) {
    assert.deepEqual(validateReceiver({ ...receiver, host }), { ...receiver, host });
  }
  for (const host of ['127.0.0.1', '169.254.169.254', '8.8.8.8', '172.15.0.2', '172.32.0.2', 'localhost', 'projector.local', '192.168.001.23', 'http://192.168.1.23', '192.168.1.23/path', '::1']) {
    assert.throws(() => validateReceiver({ ...receiver, host }));
  }
  for (const patch of [{ port: 0 }, { port: 65536 }, { port: '8080' }, { username: '' }, { username: 'name:other' }, { username: 'name\r\n' }, { password: '' }, { password: 'secret\n' }, { username: 'x'.repeat(257) }, { password: 'x'.repeat(257) }]) {
    assert.throws(() => validateReceiver({ ...receiver, ...patch }));
  }
});

test('Kodi connection uses authenticated JSON POST, fixed endpoint, no redirects and request IDs', async () => {
  const mock = mockKodi();
  assert.deepEqual(await performAction({ receiver, action: 'connect' }, mock), { name: 'Kodi', version: '21.3' });
  assert.deepEqual(mock.calls.map(call => call.rpc.method), ['JSONRPC.Ping', 'Application.GetProperties']);
  assert.deepEqual(mock.calls[1].rpc.params, { properties: ['name', 'version'] });
  for (const [index, { url, init, rpc }] of mock.calls.entries()) {
    assert.equal(url, 'http://192.168.1.23:8080/jsonrpc');
    assert.equal(init.method, 'POST');
    assert.equal(init.redirect, 'error');
    assert.equal(new Headers(init.headers).get('Content-Type'), 'application/json');
    assert.equal(new Headers(init.headers).get('Authorization'), `Basic ${Buffer.from('kodi:private-test-password').toString('base64')}`);
    assert.ok(init.signal instanceof AbortSignal);
    assert.equal(rpc.jsonrpc, '2.0');
    assert.equal(rpc.id, index + 1);
  }
});

test('play hands Kodi the fixed public sample without downloading media on the controller', async () => {
  const mock = mockKodi();
  assert.deepEqual(await performAction({ receiver, action: 'play', url: 'file:///unrequested-media' }, mock), { accepted: true });
  assert.equal(mock.calls.length, 1);
  assert.deepEqual(mock.calls[0].rpc, { jsonrpc: '2.0', id: 1, method: 'Player.Open', params: { item: { file: PUBLIC_SAMPLE_URL } } });
  assert.equal(mock.calls[0].url, 'http://192.168.1.23:8080/jsonrpc');
  assert.equal(new URL(PUBLIC_SAMPLE_URL).protocol, 'https:');
});

test('status selects the video player and normalizes playback rather than returning device text', async () => {
  const mock = mockKodi();
  assert.deepEqual(await performAction({ receiver, action: 'status' }, mock), {
    active: true, playing: true, positionSeconds: 65, durationSeconds: 596, canSeek: true
  });
  assert.deepEqual(mock.calls[1].rpc.params, { playerid: 2, properties: ['speed', 'time', 'totaltime', 'canseek'] });
  const idle = mockKodi({ 'Player.GetActivePlayers': [{ playerid: 0, type: 'audio' }] });
  assert.deepEqual(await performAction({ receiver, action: 'status' }, idle), { active: false, playing: false });
  assert.deepEqual(idle.calls.map(call => call.rpc.method), ['Player.GetActivePlayers']);
});

test('pause and resume use explicit playback state; seek is absolute time and stop returns inactive', async () => {
  for (const [action, play] of [['pause', false], ['resume', true]]) {
    const mock = mockKodi();
    await performAction({ receiver, action }, mock);
    assert.deepEqual(mock.calls.find(call => call.rpc.method === 'Player.PlayPause').rpc.params, { playerid: 2, play });
  }
  const seek = mockKodi();
  await performAction({ receiver, action: 'seek', position: 65 }, seek);
  assert.deepEqual(seek.calls.find(call => call.rpc.method === 'Player.Seek').rpc.params, {
    playerid: 2, value: { time: { hours: 0, minutes: 1, seconds: 5, milliseconds: 0 } }
  });
  const stop = mockKodi();
  assert.deepEqual(await performAction({ receiver, action: 'stop' }, stop), { active: false, playing: false });
  assert.deepEqual(stop.calls.find(call => call.rpc.method === 'Player.Stop').rpc.params, { playerid: 2 });
});

test('invalid actions and seek positions make no network request; unseekable playback is preserved', async () => {
  const mock = mockKodi();
  for (const payload of [null, { receiver, action: 'System.Shutdown' }, ...[-1, Infinity, '65', 86400].map(position => ({ receiver, action: 'seek', position }))]) {
    await assert.rejects(performAction(payload, mock));
  }
  assert.equal(mock.calls.length, 0);
  const unseekable = mockKodi({ 'Player.GetProperties': { ...playback, canseek: false } });
  await assert.rejects(performAction({ receiver, action: 'seek', position: 65 }, unseekable));
  assert.equal(unseekable.calls.some(call => call.rpc.method === 'Player.Seek'), false);
});

test('authentication, network and JSON-RPC failures expose no receiver supplied secrets', async () => {
  const secret = 'private-test-password https://private.invalid/signed-token';
  const failures = [
    async () => new Response(secret, { status: 401 }),
    async () => new Response(secret, { status: 500 }),
    async () => { throw new Error(secret); },
    async () => { throw new DOMException(secret, 'TimeoutError'); },
    async () => new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32602, message: secret, data: secret } }))
  ];
  for (const fetchImpl of failures) {
    await assert.rejects(performAction({ receiver, action: 'play' }, { fetchImpl }), error => {
      assert.doesNotMatch(error.message, /private-test-password|private\.invalid|signed-token/);
      assert.ok(error.message.length > 0);
      return true;
    });
  }
});

test('malformed, mismatched and oversized Kodi replies cannot be mistaken for accepted playback', async () => {
  for (const body of ['not JSON', JSON.stringify({ jsonrpc: '2.0', id: 999, result: 'OK' }), JSON.stringify({ jsonrpc: '2.0', id: 1 }), 'x'.repeat(65537)]) {
    await assert.rejects(performAction({ receiver, action: 'play' }, { fetchImpl: async () => new Response(body) }));
  }
  const badStatus = mockKodi({ 'Player.GetProperties': { ...playback, time: { hours: 0, minutes: 1, seconds: 'private-test-password' } } });
  await assert.rejects(performAction({ receiver, action: 'status' }, badStatus), error => !error.message.includes(receiver.password));
});

test('local controller accepts its own JSON page request and returns a sanitized result', async t => {
  const mock = mockKodi();
  const { request } = await localServer(t, mock.fetchImpl);
  const response = await request();
  assert.equal(response.status, 200);
  assert.deepEqual(JSON.parse(response.text), { ok: true, result: { name: 'Kodi', version: '21.3' } });
  assert.equal(response.headers['cache-control'], 'no-store');
  assert.doesNotMatch(response.text, /private-test-password|Authorization|192\.168/);
});

test('local controller rejects foreign or missing Origin and wrong Host before contacting Kodi', async t => {
  const mock = mockKodi();
  const { request } = await localServer(t, mock.fetchImpl);
  for (const headers of [{ Origin: undefined }, { Origin: '' }, { Origin: 'null' }, { Origin: 'https://foreign.invalid' }, { Origin: 'http://127.0.0.1:1' }, { Host: 'foreign.invalid' }]) {
    const response = await request({ headers });
    assert.equal(response.status, 403);
    assert.equal(JSON.parse(response.text).ok, false);
  }
  assert.equal(mock.calls.length, 0);
});

test('invalid local request paths, content types, bodies and actions never reach Kodi', async t => {
  const mock = mockKodi();
  const { request } = await localServer(t, mock.fetchImpl);
  for (const [options, status] of [
    [{ path: '/api/anything' }, 404],
    [{ path: '/api/kodi?method=System.Shutdown' }, 404],
    [{ method: 'GET', path: '/api/kodi' }, 404],
    [{ headers: { 'Content-Type': 'text/plain' } }, 415],
    [{ body: 'invalid JSON' }, 400],
    [{ body: 'x'.repeat(16385) }, 413],
    [{ body: JSON.stringify({ receiver, action: 'System.Shutdown' }) }, 400]
  ]) {
    const response = await request(options).catch(error => { throw new Error(`Rejected request ${JSON.stringify({ ...options, body: options.body?.length })}: ${error.message}`, { cause: error }); });
    assert.equal(response.status, status);
    assert.equal(JSON.parse(response.text).ok, false);
    assert.doesNotMatch(response.text, /private-test-password/);
  }
  assert.equal(mock.calls.length, 0);
});
