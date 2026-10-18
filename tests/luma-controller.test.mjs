import assert from 'node:assert/strict';
import { after, before, test } from 'node:test';
import http from 'node:http';
import { createLumaControllerServer, validateLumaHost } from '../scripts/luma-controller.mjs';
import { LumaProtocolError } from '../scripts/luma-protocol.mjs';

let server, origin;
const calls = [];
const fake = {
  connect: async () => ({ device: { name: 'LUMA fixture' } }),
  close: () => calls.push(['close']),
  status: async () => ({ connected: true, device: { name: 'LUMA fixture' } }),
  getApps: async () => [{ label: 'Kodi', packageName: 'org.xbmc.kodi' }],
  key: async key => calls.push(['key', key]),
  sendText: async text => calls.push(['text', text]),
  launchApp: async name => calls.push(['app', name])
};
before(async () => {
  server = createLumaControllerServer({ connectionFactory: host => { calls.push(['connect', host]); return fake; }, discover: async () => [{ host: '192.168.1.50', name: 'LUMA fixture', port: 8121 }] });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${server.address().port}`;
});
after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); });
async function post(payload, extraHeaders = {}) {
  return fetch(`${origin}/api/luma`, { method: 'POST', headers: { Origin: origin, 'Content-Type': 'application/json', ...extraHeaders }, body: JSON.stringify(payload) });
}

test('LUMA receiver address accepts only private numeric IPv4, without URL or DNS input', () => {
  for (const host of ['10.0.0.2', '172.31.0.8', '192.168.1.50']) assert.equal(validateLumaHost(host), host);
  for (const host of ['127.0.0.1', '169.254.1.2', '8.8.8.8', 'luma.local', 'http://192.168.1.50', '192.168.01.50', '192.168.1.50\n', null]) assert.throws(() => validateLumaHost(host));
});

test('remote is served locally with no-store and frame protection; host/origin/route boundaries reject external requests', async () => {
  const page = await fetch(origin);
  assert.equal(page.status, 200);
  assert.equal(page.headers.get('cache-control'), 'no-store');
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.match(await page.text(), /LUMA remote/);
  assert.equal((await post({ action: 'discover' }, { Origin: 'https://unrelated.example' })).status, 403);
  const wrongHostStatus = await new Promise((resolve, reject) => {
    http.get(origin, { headers: { Host: 'unrelated.example' } }, response => { response.resume(); resolve(response.statusCode); }).on('error', reject);
  });
  assert.equal(wrongHostStatus, 403);
  assert.equal((await post({ action: 'discover' }, { 'Content-Type': 'text/plain' })).status, 415);
  assert.equal((await fetch(`${origin}/api/luma`)).status, 404);
});

test('connection identity gates commands; launch Kodi uses its package and disconnect closes transport', async () => {
  const host = '192.168.1.50';
  assert.equal((await post({ host, action: 'key', key: 'home' })).status, 400);
  assert.equal((await post({ action: 'raw-command', command: 'anything' })).status, 400);
  const connected = await (await post({ host, action: 'connect' })).json();
  assert.equal(connected.connected, true);
  assert.equal(connected.device.name, 'LUMA fixture');
  assert.equal((await post({ host: '192.168.1.51', action: 'key', key: 'home' })).status, 400);
  assert.deepEqual(await (await post({ host, action: 'key', key: 'home' })).json(), { accepted: true });
  assert.deepEqual(await (await post({ host, action: 'launch-kodi' })).json(), { accepted: true });
  assert.ok(calls.some(call => call[0] === 'app' && call[1] === 'org.xbmc.kodi'));
  assert.deepEqual(await (await post({ host, action: 'disconnect' })).json(), { connected: false });
  assert.ok(calls.some(call => call[0] === 'close'));
  assert.equal((await post({ host, action: 'status' })).status, 400);
});

test('bounded discovery needs no host; oversized input and unrecognized transport errors stay sanitized', async () => {
  const discovery = await (await post({ action: 'discover' })).json();
  assert.equal(discovery.devices[0].name, 'LUMA fixture');
  assert.equal((await post({ action: 'text', host: '192.168.1.50', text: 'x'.repeat(17000) })).status, 413);
  const failing = createLumaControllerServer({ connectionFactory: () => ({ connect: async () => { throw new Error('secret raw socket contents'); }, close() {} }) });
  await new Promise(resolve => failing.listen(0, '127.0.0.1', resolve));
  const address = `http://127.0.0.1:${failing.address().port}`;
  try {
    const result = await fetch(`${address}/api/luma`, { method: 'POST', headers: { Origin: address, 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'connect', host: '192.168.1.50' }) });
    assert.equal(result.status, 502);
    assert.doesNotMatch(await result.text(), /secret|raw socket/);
  } finally { failing.closeAllConnections(); await new Promise(resolve => failing.close(resolve)); }
});

test('transport failure clears connection authority while ordinary input errors preserve it', async () => {
  const host = '192.168.1.50';
  let closed = 0;
  const active = { ...fake, close: () => { closed++; }, key: async key => { throw new LumaProtocolError(key === 'up' ? 'TIMEOUT' : 'INVALID_INPUT'); } };
  const isolated = createLumaControllerServer({ connectionFactory: () => active });
  await new Promise(resolve => isolated.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${isolated.address().port}`;
  const send = async payload => (await fetch(`${base}/api/luma`, { method: 'POST', headers: { Origin: base, 'Content-Type': 'application/json' }, body: JSON.stringify({ host, ...payload }) })).json();
  try {
    await send({ action: 'connect' });
    assert.equal((await send({ action: 'key', key: 'invalid' })).connected, undefined);
    assert.equal((await send({ action: 'status' })).connected, true);
    assert.equal((await send({ action: 'key', key: 'up' })).connected, false);
    assert.equal(closed, 1);
    assert.equal((await send({ action: 'status' })).connected, false);
    assert.equal((await send({ action: 'connect' })).connected, true);
  } finally { isolated.closeAllConnections(); await new Promise(resolve => isolated.close(resolve)); }
});
