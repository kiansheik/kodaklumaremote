import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { discoveryRequest, parseDiscoveryReply, discoveryInterfaces, discoverLuma } from '../scripts/luma-discovery.mjs';

const entry = (address = '192.168.10.2', netmask = '255.255.255.0') => ({ address, netmask, family: 'IPv4', internal: false });
// Synthetic fixtures follow b.b.a.b$n.a's plain colon-delimited response.
const reply = (name = 'LUMA test', port = 8121) => Buffer.from(`ECloudBox:${name}:${port}:25123:0:sn=0x0000`);
// Sanitized shape of the physical response: numeric version 516 and a seventh field.
const versionedReply = () => Buffer.from('ECloudBox516:LUMA test:8121:25123:56789:sn=0xbf:6');

function harness(inventory = { en0: [entry()] }) {
  const sockets = [];
  const scheduled = new Map();
  let nextTimer = 0;
  const options = {
    getInterfaces: () => inventory,
    setTimeoutFn: (callback, delay) => { const id = ++nextTimer; scheduled.set(id, { callback, delay }); return id; },
    clearTimeoutFn: id => scheduled.delete(id),
    createSocket: config => {
      const socket = new EventEmitter();
      Object.assign(socket, {
        config, sent: [], closed: false,
        bind(port, address, ready) { this.bound = { port, address }; ready(); },
        setBroadcast(value) { this.broadcast = value; },
        send(packet, port, host, done) { this.sent.push({ packet, port, host }); done(); },
        close() { this.closed = true; },
      });
      sockets.push(socket);
      return socket;
    },
  };
  const advance = delay => {
    for (const [id, timer] of [...scheduled]) {
      if (timer.delay === delay) { scheduled.delete(id); timer.callback(); }
    }
  };
  return { sockets, scheduled, options, advance };
}

test('LUMA discovery request matches the source-derived binary frame', () => {
  const packet = discoveryRequest();
  assert.equal(packet.length, 50);
  assert.deepEqual([...packet.subarray(0, 20)], [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 13, 0, 0, 0, 0]);
  assert.equal(packet.subarray(20, 33).toString(), 'FindECloudBox');
  assert.ok(packet.subarray(33).every(byte => byte === 0));
});

test('LUMA reply parser uses source address and permits optional serial metadata', () => {
  assert.deepEqual(parseDiscoveryReply(reply(), '192.168.10.3'), { host: '192.168.10.3', name: 'LUMA test', port: 8121 });
  assert.deepEqual(parseDiscoveryReply(versionedReply(), '192.168.10.3'), { host: '192.168.10.3', name: 'LUMA test', port: 8121 });
  assert.deepEqual(parseDiscoveryReply(Buffer.from('ECloudBox: Projetor português :8121:0\0\r\n'), '10.1.2.3'), { host: '10.1.2.3', name: 'Projetor português', port: 8121 });
  assert.deepEqual(parseDiscoveryReply(reply('other port', 8122), '192.168.10.3', { includeUnsupportedPorts: true }), { host: '192.168.10.3', name: 'other port', port: 8122, supported: false });
});

test('LUMA reply parser rejects malformed, unsupported or non-private responses', () => {
  for (const packet of [Buffer.alloc(0), Buffer.alloc(16385), Buffer.from([0xff]), Buffer.from('ECloudBox'), reply('', 8121), reply('x'.repeat(121)), reply('test', 8122), reply('test', '08121'), reply('test\nother'), Buffer.from('ECloudBoxevil:test:8121:0'), Buffer.from('ECloudBox:test:8121:65536'), Buffer.from('ECloudBox:test:8121:hello')]) {
    assert.equal(parseDiscoveryReply(packet, '192.168.10.3'), null);
  }
  for (const host of ['127.0.0.1', '8.8.8.8', '169.254.1.2', '172.32.0.1', '::1', 'projector.local']) assert.equal(parseDiscoveryReply(reply(), host), null);
});

test('LUMA interface selection calculates real netmask broadcasts and excludes virtual or invalid ranges', () => {
  const found = discoveryInterfaces({ en0: [entry('192.168.11.2', '255.255.254.0')], eth0: [entry('10.0.3.2', '255.255.252.0')], wlan0: [entry('172.16.1.2')], utun0: [entry()], bridge0: [entry()], awdl0: [entry()], en1: [entry('8.8.8.8'), entry('192.168.10.2', '255.0.255.0'), entry('192.168.10.2', '0.0.0.0'), entry('192.168.10.2', '255.255.255.255'), entry('192.168.10.2', '255.255.255.254'), entry('192.168.10.0'), entry('192.168.10.255'), { ...entry(), internal: true }, { ...entry(), family: 'IPv6' }] });
  assert.deepEqual(found.map(({ address, broadcast }) => ({ address, broadcast })), [{ address: '192.168.11.2', broadcast: '192.168.11.255' }, { address: '10.0.3.2', broadcast: '10.0.3.255' }, { address: '172.16.1.2', broadcast: '172.16.1.255' }]);
  assert.equal(discoveryInterfaces({ en0: [entry(), entry()] }).length, 1);
  assert.equal(discoveryInterfaces({ en0: [entry('192.168.10.2', '255.0.0.0')] }).length, 0);
});

test('LUMA discovery sends only three bounded broadcasts, deduplicates and closes sockets', async () => {
  const h = harness();
  const result = discoverLuma(h.options);
  const socket = h.sockets[0];
  assert.deepEqual(socket.bound, { port: 48689, address: '192.168.10.2' });
  assert.equal(socket.broadcast, true);
  assert.equal(socket.sent.length, 1);
  socket.emit('message', versionedReply(), { address: '192.168.10.3' });
  socket.emit('message', reply('duplicate'), { address: '192.168.10.3' });
  socket.emit('message', reply(), { address: '192.168.20.3' });
  socket.emit('message', reply(), { address: '192.168.10.255' });
  socket.emit('message', reply(), { address: '192.168.10.0' });
  h.advance(700); h.advance(1400);
  assert.equal(socket.sent.length, 3);
  assert.ok(socket.sent.every(sent => sent.port === 48689 && sent.host === '192.168.10.255'));
  h.advance(2500);
  assert.deepEqual(await result, [{ host: '192.168.10.3', name: 'LUMA test', port: 8121 }]);
  assert.equal(socket.closed, true);
  assert.equal(h.scheduled.size, 0);
  socket.emit('message', reply(), { address: '192.168.10.4' });
});

test('LUMA discovery caps devices and interfaces', async () => {
  const inventory = Object.fromEntries(Array.from({ length: 20 }, (_, i) => [`en${i}`, [entry(`10.0.${i}.2`)]]));
  const h = harness(inventory);
  const result = discoverLuma(h.options);
  assert.equal(h.sockets.length, 16);
  for (let i = 3; i < 23; i++) h.sockets[0].emit('message', reply(), { address: `10.0.0.${i}` });
  h.advance(2500);
  assert.equal((await result).length, 16);
  assert.ok(h.sockets.every(socket => socket.closed));
  assert.equal(h.scheduled.size, 0);
});

test('LUMA discovery cleans up socket failures and bounds total duration', async () => {
  const h = harness();
  const result = discoverLuma({ ...h.options, timeoutMs: 60000 });
  assert.ok([...h.scheduled.values()].some(timer => timer.delay === 5000));
  h.sockets[0].emit('error', new Error('synthetic network error'));
  assert.deepEqual(await result, []);
  assert.equal(h.sockets[0].closed, true);
  assert.equal(h.scheduled.size, 0);
  const b = harness();
  b.options.createSocket = () => { throw new Error('synthetic bind failure'); };
  assert.deepEqual(await discoverLuma(b.options), []);
  assert.equal(b.scheduled.size, 0);
  const noInterfaces = harness({ utun0: [entry()] });
  assert.deepEqual(await discoverLuma(noInterfaces.options), []);
  assert.equal(noInterfaces.sockets.length, 0);
});

test('LUMA discovery times out an unready bind and ignores late ready callbacks', async () => {
  const h = harness();
  const create = h.options.createSocket;
  let ready;
  h.options.createSocket = config => {
    const socket = create(config);
    socket.bind = (port, address, callback) => { ready = callback; };
    return socket;
  };
  const result = discoverLuma({ ...h.options, timeoutMs: 1000 });
  h.advance(1000);
  assert.deepEqual(await result, []);
  ready();
  assert.equal(h.sockets[0].closed, true);
  assert.equal(h.sockets[0].sent.length, 0);
  assert.equal(h.scheduled.size, 0);
});

test('LUMA discovery closes immediately after send failure without retries', async () => {
  const h = harness();
  const create = h.options.createSocket;
  h.options.createSocket = config => {
    const socket = create(config);
    socket.send = (packet, port, host, callback) => callback(new Error('synthetic send failure'));
    return socket;
  };
  assert.deepEqual(await discoverLuma(h.options), []);
  assert.equal(h.sockets[0].closed, true);
  assert.equal(h.scheduled.size, 0);
});
