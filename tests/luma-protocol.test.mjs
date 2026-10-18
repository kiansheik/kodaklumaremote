import test from 'node:test';
import assert from 'node:assert/strict';
import { createServer, createConnection } from 'node:net';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { LumaConnection, LumaProtocolError } from '../scripts/luma-protocol.mjs';

function appFrame(apps = [{ label: 'Kodi', packageName: 'org.xbmc.kodi' }]) {
  const fields = [];
  for (const app of apps) {
    for (const value of [app.label, app.packageName, '1.0', Buffer.from([1, 0, 0, 0]), Buffer.from([0, 1, 2])]) {
      const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value);
      const size = Buffer.alloc(4);
      size.writeUInt32LE(bytes.length);
      fields.push(size, bytes);
    }
  }
  const size = Buffer.alloc(4);
  size.writeUInt32LE(4 + fields.reduce((total, bytes) => total + bytes.length, 0));
  return Buffer.concat([size, ...fields]);
}

async function fixture(t, overrides = {}) {
  const sockets = new Set();
  const commands = [];
  let connects = 0;
  const server = createServer(socket => {
    connects += 1;
    sockets.add(socket);
    socket.on('error', () => {});
    socket.once('close', () => sockets.delete(socket));
    let pending = '';
    socket.on('data', data => {
      pending += data.toString('utf8');
      let end;
      while ((end = pending.indexOf('\r\n\r\n')) !== -1) {
        const command = pending.slice(0, end + 4);
        pending = pending.slice(end + 4);
        commands.push(command);
        const kind = command.split('\r\n')[0];
        const defaults = {
          getFeatures: 'server_features:sn=0xbf v=162 m=Test LUMA\r\n\r\n',
          GETPASSWORDCONFIG: 'configed none\r\n\r\n',
          getServerInfo: '{"name":"Test LUMA"}\r\n\r\n',
          MediaControl: 'volume: 6 / 15\r\n\r\n',
          GETAPPINFOSEVNET: appFrame(),
        };
        const reply = Object.hasOwn(overrides, kind) ? overrides[kind] : defaults[kind];
        if (typeof reply === 'function') reply(socket, command);
        else if (reply !== undefined && reply !== null) socket.write(reply);
      }
    });
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const port = server.address().port;
  const connection = new LumaConnection({
    host: '192.168.1.2',
    connectImpl: options => {
      assert.deepEqual(options, { host: '192.168.1.2', port: 8121 });
      return createConnection({ host: '127.0.0.1', port });
    },
  });
  t.after(async () => {
    connection.close();
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
  });
  return { connection, commands, server, get connects() { return connects; } };
}

async function eventually(predicate) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    if (predicate()) return;
    await delay(10);
  }
  assert.fail('Expected receiver observation did not arrive');
}

function codeIs(code) {
  return failure => failure instanceof LumaProtocolError && failure.code === code;
}

test('connect identifies the receiver and reuses one socket without advertising files or auth', async t => {
  const state = await fixture(t);
  const first = await state.connection.connect();
  assert.deepEqual(first, { connected: true, device: { name: 'Test LUMA' } });
  assert.deepEqual(await state.connection.connect(), first);
  assert.equal(state.connects, 1);
  assert.deepEqual(state.commands, [
    'getFeatures\r\nLUMA Remote\r\n\r\n',
    'GETPASSWORDCONFIG\r\neshare\r\n\r\n',
  ]);
  assert.ok(state.commands.every(command => !/^(?:auth|CHECKPASSWORD)/u.test(command)));
  assert.ok(state.commands.every(command => !command.startsWith('getServerInfo')));
});

test('feature identity supports the observed LUMA firmware without optional server-info replies', async t => {
  const state = await fixture(t, {
    getFeatures: socket => {
      socket.write('server_features:sn=0xbf v=162 m=Smart ');
      setTimeout(() => socket.write('Projector ap=Kodak Luma'), 20);
    },
    GETPASSWORDCONFIG: 'configed none\r\n\r\n',
    getServerInfo: null,
  });
  assert.deepEqual(await state.connection.connect(), { connected: true, device: { name: 'Kodak Luma' } });
  assert.equal(state.commands.length, 2);
});

test('password configuration blocks controls and closes the connection', async t => {
  const state = await fixture(t, { GETPASSWORDCONFIG: 'PASSWORD required\r\n\r\n' });
  await assert.rejects(state.connection.connect(), codeIs('PAIRING_REQUIRED'));
  await assert.rejects(state.connection.key('home'), codeIs('NOT_CONNECTED'));
  assert.equal(state.commands.length, 2);
});

test('empty, unknown and invalid-text pairing replies fail closed', async t => {
  for (const reply of ['\r\n\r\n', 'unrecognized pairing mode\r\n\r\n', Buffer.from([0xff, 0xfe])]) {
    const state = await fixture(t, { GETPASSWORDCONFIG: reply });
    await assert.rejects(state.connection.connect(), codeIs('PAIRING_UNKNOWN'));
    assert.equal(state.commands.length, 2);
  }
});

test('unanswered pairing check has a bounded timeout and never sends a control command', async t => {
  const state = await fixture(t, { GETPASSWORDCONFIG: null });
  const started = Date.now();
  await assert.rejects(state.connection.connect(), codeIs('PAIRING_UNKNOWN'));
  assert.ok(Date.now() - started < 4000);
  await assert.rejects(state.connection.status(), codeIs('NOT_CONNECTED'));
  assert.equal(state.commands.length, 2);
});

test('unsupported identity replies expose fixed errors rather than raw receiver data', async t => {
  for (const overrides of [
    { getFeatures: 'secret raw bad features' },
    { getFeatures: 'server_features:   ' },
    { getFeatures: 'server_features:ap=' + 'x'.repeat(201) },
  ]) {
    const state = await fixture(t, overrides);
    await assert.rejects(state.connection.connect(), failure => {
      assert.ok(codeIs('INVALID_RESPONSE')(failure));
      assert.doesNotMatch(failure.message, /secret|bad identity|192\.168/u);
      return true;
    });
  }
});

test('volume status preserves the receiver scale and omits ambiguous or invalid values', async t => {
  const replies = ['volume: 6 / 15', '6 15 30', '-6 15', '7 0', '16 15', 'not supported'];
  let index = 0;
  const state = await fixture(t, { MediaControl: socket => socket.write(replies[index++]) });
  await state.connection.connect();
  assert.deepEqual(await state.connection.status(), {
    connected: true, device: { name: 'Test LUMA' }, volume: { current: 6, max: 15 },
  });
  for (let i = 1; i < replies.length; i += 1) {
    assert.deepEqual(await state.connection.status(), { connected: true, device: { name: 'Test LUMA' } });
  }
});

test('app inventory handles fragmented binary headers and fields and exposes only names/packages', async t => {
  const frame = appFrame([
    { label: 'Kodi', packageName: 'org.xbmc.kodi' },
    { label: 'Vídeo', packageName: 'example.video_player' },
  ]);
  const state = await fixture(t, { GETAPPINFOSEVNET: socket => {
    socket.write(frame.subarray(0, 1));
    setTimeout(() => socket.write(frame.subarray(1, 3)), 10);
    setTimeout(() => socket.write(frame.subarray(3, 17)), 20);
    setTimeout(() => socket.write(frame.subarray(17)), 30);
  } });
  await state.connection.connect();
  assert.deepEqual(await state.connection.getApps(), [
    { packageName: 'org.xbmc.kodi', label: 'Kodi' },
    { packageName: 'example.video_player', label: 'Vídeo' },
  ]);
  await state.connection.launchApp('org.xbmc.kodi');
  await eventually(() => state.commands.some(command => command.startsWith('STARTAPPEVNET')));
  assert.equal(state.commands.at(-1), 'STARTAPPEVNET\r\norg.xbmc.kodi\r\n\r\n');
  assert.equal(state.commands.filter(command => command.startsWith('GETAPPINFOSEVNET')).length, 1);
});

test('launch fetches an inventory once and never assumes that Kodi is installed', async t => {
  const state = await fixture(t, { GETAPPINFOSEVNET: appFrame([{ label: 'Other', packageName: 'example.other' }]) });
  await state.connection.connect();
  await assert.rejects(state.connection.launchApp('org.xbmc.kodi'), codeIs('APP_NOT_AVAILABLE'));
  assert.equal(state.commands.at(-1), 'GETAPPINFOSEVNET\r\n1\r\n\r\n');
  await state.connection.launchApp('example.other');
  await eventually(() => state.commands.some(command => command.startsWith('STARTAPPEVNET')));
  assert.equal(state.commands.filter(command => command.startsWith('GETAPPINFOSEVNET')).length, 1);
});

test('malformed, duplicate and excessive app inventories fail closed', async t => {
  const invalidField = appFrame();
  invalidField.writeUInt32LE(0xffffffff, 4);
  const oversize = Buffer.alloc(4);
  oversize.writeUInt32LE(16 * 1024 * 1024 + 1);
  const invalidUtf8 = appFrame();
  invalidUtf8[8] = 0xff;
  const extraBytes = Buffer.concat([appFrame(), Buffer.from([1])]);
  const frames = [
    invalidField, oversize, invalidUtf8, extraBytes,
    appFrame([{ label: 'A', packageName: 'example.a' }, { label: 'Duplicate', packageName: 'example.a' }]),
    appFrame([{ label: 'Bad', packageName: 'example.app\r\nKEYEVENT' }]),
    appFrame(Array.from({ length: 201 }, (_, i) => ({ label: 'App', packageName: `example.app${i}` }))),
  ];
  for (const frame of frames) {
    const state = await fixture(t, { GETAPPINFOSEVNET: frame });
    await state.connection.connect();
    await assert.rejects(state.connection.getApps(), codeIs('INVALID_RESPONSE'));
    await assert.rejects(state.connection.key('enter'), codeIs('NOT_CONNECTED'));
  }
});

test('truncated app frames close on EOF instead of caching a partial app list', async t => {
  const frame = appFrame();
  const state = await fixture(t, { GETAPPINFOSEVNET: socket => socket.end(frame.subarray(0, frame.length - 1)) });
  await state.connection.connect();
  await assert.rejects(state.connection.getApps(), codeIs('INVALID_RESPONSE'));
  await assert.rejects(state.connection.launchApp('org.xbmc.kodi'), codeIs('NOT_CONNECTED'));
});

test('only named keys, bounded single-line text and valid package identifiers are accepted', async t => {
  const state = await fixture(t);
  await state.connection.connect();
  const before = state.commands.length;
  for (const key of ['power', 'constructor', '__proto__', 'up\r\nKEYEVENT', 19]) {
    await assert.rejects(state.connection.key(key), codeIs('INVALID_INPUT'));
  }
  for (const text of ['a\r\nKEYEVENT\r\n3', 'a\nb', 'a\u0000b', 'a\tb', '', 'á'.repeat(1001)]) {
    await assert.rejects(state.connection.sendText(text), codeIs('INVALID_TEXT'));
  }
  for (const pkg of ['org.xbmc.kodi\r\nKEYEVENT', 'unqualified', '', '__proto__']) {
    await assert.rejects(state.connection.launchApp(pkg), codeIs('INVALID_INPUT'));
  }
  assert.equal(state.commands.length, before);
  assert.deepEqual(await state.connection.key('enter'), { accepted: true });
  assert.deepEqual(await state.connection.sendText('Olá 👋'), { accepted: true });
  await eventually(() => state.commands.length === before + 2);
  assert.deepEqual(state.commands.slice(-2), ['KEYEVENT\r\n23\r\n\r\n', 'CONTENT\r\n1\r\n0 Olá 👋\r\n\r\n']);
  assert.deepEqual(await state.connection.sendText('á'.repeat(1000)), { accepted: true });
  await eventually(() => state.commands.length === before + 3);
  assert.equal(state.commands.at(-1), `CONTENT\r\n1\r\n0 ${'á'.repeat(1000)}\r\n\r\n`);
});

test('requests serialize with navigation so a late response cannot be consumed by the next command', async t => {
  let replySent = false;
  const state = await fixture(t, { MediaControl: socket => {
    setTimeout(() => { replySent = true; socket.write('4 20'); }, 60);
  }, KEYEVENT: () => assert.equal(replySent, true) });
  await state.connection.connect();
  const status = state.connection.status();
  const key = state.connection.key('home');
  assert.deepEqual((await status).volume, { current: 4, max: 20 });
  await key;
  await eventually(() => state.commands.at(-1).startsWith('KEYEVENT'));
  assert.deepEqual(state.commands.slice(-2), ['MediaControl\r\ngetVolume\r\n\r\n', 'KEYEVENT\r\n3\r\n\r\n']);
});

test('the command queue is bounded and recovers after pending commands settle', async t => {
  const state = await fixture(t, { MediaControl: socket => setTimeout(() => socket.write('1 10'), 60) });
  await state.connection.connect();
  const status = state.connection.status();
  const commands = Array.from({ length: 31 }, () => state.connection.key('up'));
  await assert.rejects(state.connection.key('down'), codeIs('QUEUE_FULL'));
  await Promise.all([status, ...commands]);
  await state.connection.key('down');
});

test('closed and disconnected sockets clear app authority and reject subsequent commands', async t => {
  const state = await fixture(t);
  await state.connection.connect();
  await state.connection.getApps();
  state.connection.close();
  state.connection.close();
  await assert.rejects(state.connection.key('home'), codeIs('NOT_CONNECTED'));
  await assert.rejects(state.connection.status(), codeIs('NOT_CONNECTED'));
});

test('numeric IPv4 and valid ports prevent DNS selection through direct callers', () => {
  for (const host of ['projector.local', 'https://192.168.1.2', '192.168.1.2\r\n', '::1', undefined]) {
    assert.throws(() => new LumaConnection({ host }), codeIs('INVALID_INPUT'));
  }
  for (const port of [0, 65536, '8121', NaN]) {
    assert.throws(() => new LumaConnection({ host: '192.168.1.2', port }), codeIs('INVALID_INPUT'));
  }
  assert.equal(new LumaProtocolError('unrecognized raw data').code, 'INVALID_RESPONSE');
});
