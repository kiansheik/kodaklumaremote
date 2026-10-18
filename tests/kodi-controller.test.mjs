import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import { PUBLIC_SAMPLE_URL, validateReceiver, validateMedia, performAction, createControllerServer } from '../scripts/kodi-controller.mjs';

const receiver = { host: '192.168.1.23', port: 8080, username: 'kodi', password: 'private-test-password' };
const playback = {
  speed: 1, time: { hours: 0, minutes: 1, seconds: 5, milliseconds: 0 },
  totaltime: { hours: 0, minutes: 9, seconds: 56, milliseconds: 0 }, canseek: true
};
const subtitleState = {
  subtitles: [{ index: 0, name: 'English', language: 'eng' }, { index: 1, name: 'Portuguese', language: 'por' }],
  currentsubtitle: { index: 0, name: 'English', language: 'eng' }, subtitleenabled: true
};

function mockKodi(overrides = {}) {
  const calls = [];
  const defaults = {
    'JSONRPC.Ping': 'pong',
    'Application.GetProperties': { name: 'Kodi', version: { major: 21, minor: 3 } },
    'Player.Open': 'OK',
    'Player.GetActivePlayers': [{ playerid: 0, type: 'audio' }, { playerid: 2, type: 'video' }],
    'Player.GetProperties': { ...playback, ...subtitleState },
    'Player.GetItem': { item: { file: PUBLIC_SAMPLE_URL } },
    'Player.PlayPause': { speed: 1 },
    'Player.Seek': { time: playback.time, totaltime: playback.totaltime },
    'Player.Stop': 'OK',
    'Player.SetSubtitle': 'OK',
    'Player.AddSubtitle': 'OK'
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

test('declared public MP4, WebM, HLS and DASH sources accept public DNS and global IP hosts', () => {
  for (const contentType of ['video/mp4', 'video/webm', 'application/vnd.apple.mpegurl', 'application/x-mpegurl', 'application/dash+xml']) {
    for (const url of ['https://media.example.org/stream?token=private-test-token', 'http://8.8.8.8/video', 'https://[2001:4860:4860::8888]/video']) {
      assert.deepEqual(validateMedia({ url, contentType, title: 'Private title' }), { url, contentType });
    }
  }
  assert.deepEqual(validateMedia({ url: PUBLIC_SAMPLE_URL, contentType: 'VIDEO/MP4; codecs="avc1"' }), {
    url: PUBLIC_SAMPLE_URL, contentType: 'video/mp4'
  });
});

test('source handoff sends only the declared URL to Kodi and neither retrieves media nor returns source metadata', async () => {
  const mock = mockKodi();
  const url = 'https://media.example.org/declared.m3u8?token=private-test-token';
  const result = await performAction({ receiver, action: 'play-source', media: {
    url, contentType: 'application/vnd.apple.mpegurl', title: 'Private title',
    headers: { Cookie: 'private-browser-cookie', Authorization: 'Bearer private-media-auth' },
    tracks: [{ url: 'https://media.example.org/private.vtt' }]
  } }, mock);
  assert.deepEqual(result, { accepted: true });
  assert.equal(mock.calls.length, 1);
  assert.equal(mock.calls[0].url, 'http://192.168.1.23:8080/jsonrpc');
  assert.deepEqual(mock.calls[0].rpc, { jsonrpc: '2.0', id: 1, method: 'Player.Open', params: { item: { file: url } } });
  assert.doesNotMatch(JSON.stringify(mock.calls), /private-browser-cookie|private-media-auth|Private title|private\.vtt/);
  assert.doesNotMatch(JSON.stringify(result), /private-test-token|media\.example/);
});

test('invalid source schemes, hosts, credentials and Kodi header-suffix injection fail before any network request', async () => {
  const mock = mockKodi();
  const invalidURLs = [
    'blob:https://media.example.org/id', 'file:///video.mp4', 'javascript:alert(1)',
    'https://user:secret@media.example.org/video.mp4', 'https://media.example.org/video.mp4#fragment',
    'https://media.example.org/video.mp4#', 'https://media.example.org/video.mp4|Cookie=secret',
    'https://media.example.org/video\n.mp4', 'https://media.example.org/video\u0000.mp4',
    ' https://media.example.org/video.mp4', 'https:\\media.example.org/video.mp4',
    ...['localhost', 'projector.local', 'media.lan', 'media.home', 'media.internal', '0.0.0.0',
      '10.0.0.2', '100.64.0.1', '127.0.0.1', '127.1', '2130706433', '169.254.169.254',
      '172.16.0.2', '192.168.0.2', '192.0.2.1', '198.18.0.1', '198.51.100.1',
      '203.0.113.1', '224.0.0.1', '[::1]', '[::ffff:192.168.0.2]', '[fe80::1]',
      '[fc00::1]', '[2001::1]', '[2001:db8::1]', '[2002:c0a8:1::1]'].map(host => `https://${host}/video.mp4`)
  ];
  for (const url of invalidURLs) {
    await assert.rejects(performAction({ receiver, action: 'play-source', media: { url, contentType: 'video/mp4' } }, mock), error => {
      assert.equal(error.message, 'Enter a supported public HTTP media source.');
      return true;
    });
  }
  for (const media of [undefined, {}, { url: PUBLIC_SAMPLE_URL }, { url: PUBLIC_SAMPLE_URL, contentType: 'text/html' },
    { url: PUBLIC_SAMPLE_URL, contentType: 'application/octet-stream' }, { url: 'https://media.example.org/' + 'x'.repeat(8192), contentType: 'video/mp4' }]) {
    await assert.rejects(performAction({ receiver, action: 'play-source', media }, mock));
  }
  assert.equal(mock.calls.length, 0);
});

test('source handoff transport and receiver failures never echo the declared URL', async () => {
  const url = 'https://media.example.org/stream.m3u8?token=private-test-token';
  for (const fetchImpl of [
    async () => { throw new Error(url); },
    async () => new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, error: { message: url } }))
  ]) {
    await assert.rejects(performAction({ receiver, action: 'play-source', media: { url, contentType: 'application/vnd.apple.mpegurl' } }, { fetchImpl }), error => {
      assert.doesNotMatch(error.message, /media\.example|private-test-token/);
      return true;
    });
  }
});

test('subtitle listing selects the video player and returns receiver state; idle returns an empty list', async () => {
  const mock = mockKodi();
  assert.deepEqual(await performAction({ receiver, action: 'subtitles' }, mock), {
    active: true, enabled: true, currentIndex: 0, tracks: subtitleState.subtitles
  });
  assert.deepEqual(mock.calls.map(call => call.rpc.method), ['Player.GetActivePlayers', 'Player.GetProperties']);
  assert.deepEqual(mock.calls[1].rpc.params, { playerid: 2, properties: ['subtitles', 'currentsubtitle', 'subtitleenabled'] });
  const idle = mockKodi({ 'Player.GetActivePlayers': [] });
  assert.deepEqual(await performAction({ receiver, action: 'subtitles' }, idle), {
    active: false, enabled: false, currentIndex: null, tracks: []
  });
  assert.equal(idle.calls.length, 1);
});

test('subtitle selection verifies availability, explicitly enables the selected index and refreshes observed state', async () => {
  const mock = mockKodi();
  const result = await performAction({ receiver, action: 'set-subtitle', subtitle: 1 }, mock);
  assert.deepEqual(mock.calls.map(call => call.rpc.method), [
    'Player.GetActivePlayers', 'Player.GetProperties', 'Player.SetSubtitle', 'Player.GetProperties'
  ]);
  assert.deepEqual(mock.calls[2].rpc.params, { playerid: 2, subtitle: 1, enable: true });
  // A command acknowledgement must not fabricate a changed receiver selection.
  assert.equal(result.currentIndex, 0);
  const off = mockKodi({ 'Player.GetProperties': { ...subtitleState, subtitleenabled: false } });
  assert.deepEqual(await performAction({ receiver, action: 'set-subtitle', subtitle: 'off' }, off), {
    active: true, enabled: false, currentIndex: 0, tracks: subtitleState.subtitles
  });
  assert.deepEqual(off.calls[2].rpc.params, { playerid: 2, subtitle: 'off' });
});

test('invalid subtitle commands fail before network requests; absent tracks and idle video cannot be changed', async () => {
  const mock = mockKodi();
  for (const subtitle of [undefined, null, true, -1, 1.5, 1025, '0', 'next', 'on']) {
    await assert.rejects(performAction({ receiver, action: 'set-subtitle', subtitle }, mock));
  }
  for (const subtitleUrl of [undefined, '', 'blob:https://media.example.org/id', 'file:///subtitles.vtt',
    'https://user:secret@media.example.org/track', 'https://media.example.org/track#fragment',
    'https://media.example.org/track|Cookie=private-cookie', 'https://media.example.org/track\n',
    'https://127.0.0.1/track', 'https://192.168.1.2/track', 'https://projector.local/track',
    'https://media.example.org/' + 'x'.repeat(8192)]) {
    await assert.rejects(performAction({ receiver, action: 'add-subtitle', subtitleUrl }, mock));
  }
  assert.equal(mock.calls.length, 0);
  await assert.rejects(performAction({ receiver, action: 'set-subtitle', subtitle: 99 }, mock));
  assert.deepEqual(mock.calls.map(call => call.rpc.method), ['Player.GetActivePlayers', 'Player.GetProperties']);
  for (const payload of [{ action: 'set-subtitle', subtitle: 'off' },
    { action: 'add-subtitle', subtitleUrl: 'https://media.example.org/track' }]) {
    const idle = mockKodi({ 'Player.GetActivePlayers': [] });
    await assert.rejects(performAction({ receiver, ...payload }, idle));
    assert.deepEqual(idle.calls.map(call => call.rpc.method), ['Player.GetActivePlayers']);
  }
});

test('external subtitles hand an extensionless public URL directly to Kodi without fetching or echoing it', async () => {
  const mock = mockKodi();
  const url = 'https://media.example.org/subtitle-proxy?id=private-subtitle-token';
  assert.deepEqual(await performAction({ receiver, action: 'add-subtitle', subtitleUrl: url }, mock), { accepted: true });
  assert.deepEqual(mock.calls.map(call => call.rpc.method), ['Player.GetActivePlayers', 'Player.AddSubtitle']);
  assert.deepEqual(mock.calls[1].rpc.params, { playerid: 2, subtitle: url });
  assert.ok(mock.calls.every(call => call.url === 'http://192.168.1.23:8080/jsonrpc'));
});

test('subtitle metadata is bounded and strips URLs, control characters, duplicate indices and unrecognized fields', async () => {
  const mock = mockKodi({ 'Player.GetProperties': {
    subtitleenabled: true, currentsubtitle: { index: 7, file: 'https://private.example.org/current?secret' },
    subtitles: [null, { index: '7' }, { index: -1 }, { index: 1025 },
      { index: 7, name: 'Eng\u0000lish https://private.example.org/sub?secret', language: 'en\u0000g',
        isforced: true, isimpaired: false, file: 'file:///private-subtitle', url: 'https://private.example.org/private', secret: 'private-value' },
      { index: 7, name: 'duplicate' }, { index: 8, name: 'x'.repeat(250), language: 'y'.repeat(80), forced: 'true' }]
  } });
  const result = await performAction({ receiver, action: 'subtitles' }, mock);
  assert.deepEqual(result, { active: true, enabled: true, currentIndex: 7, tracks: [
    { index: 7, name: 'English', language: 'eng', forced: true, impaired: false },
    { index: 8, name: 'x'.repeat(200), language: 'y'.repeat(64) }
  ] });
  assert.doesNotMatch(JSON.stringify(result), /https:|file:|private-|secret|duplicate/);
  const many = mockKodi({ 'Player.GetProperties': {
    subtitles: Array.from({ length: 129 }, (_, index) => ({ index, name: 'English', language: 'eng' })),
    subtitleenabled: false, currentsubtitle: { index: 128 }
  } });
  const capped = await performAction({ receiver, action: 'subtitles' }, many);
  assert.equal(capped.tracks.length, 128);
  assert.equal(capped.currentIndex, null);
});

test('malformed subtitle status and external subtitle failures return fixed errors without raw metadata or links', async () => {
  for (const properties of [null, { subtitles: [], subtitleenabled: 'private-value' },
    { subtitles: 'https://private.example.org/secret', subtitleenabled: true }]) {
    const mock = mockKodi({ 'Player.GetProperties': properties });
    await assert.rejects(performAction({ receiver, action: 'subtitles' }, mock), error => {
      assert.equal(error.message, 'Kodi returned an unexpected subtitle status.');
      return true;
    });
  }
  const url = 'https://media.example.org/track?private-subtitle-token';
  for (const failure of ['network', 'rpc']) {
    const mock = mockKodi();
    await assert.rejects(performAction({ receiver, action: 'add-subtitle', subtitleUrl: url }, {
      fetchImpl: async (endpoint, init) => {
        const rpc = JSON.parse(init.body);
        if (rpc.method !== 'Player.AddSubtitle') return mock.fetchImpl(endpoint, init);
        if (failure === 'network') throw new Error(url);
        return Response.json({ jsonrpc: '2.0', id: rpc.id, error: { message: url } });
      }
    }), error => {
      assert.doesNotMatch(error.message, /media\.example|private-subtitle-token/);
      return true;
    });
  }
});

test('subtitle labels bind to the current media hash and persist only as sanitized metadata in the shared cache', async () => {
  const url = 'https://media.example.org/private-video.m3u8?token=private-media-token';
  const mediaKey = createHash('sha256').update(url).digest('hex');
  const subtitleLabels = new Map();
  const mock = mockKodi({
    'Player.GetItem': { item: { file: url } },
    'Player.GetProperties': { ...subtitleState, subtitles: [{ index: 0, name: '(External)', language: '' }] }
  });
  const options = { ...mock, subtitleLabels };
  const result = await performAction({ receiver, action: 'label-subtitles', mediaKey, tracks: [
    { index: 0, label: 'Portugu\u0000ese https://private.example.org/subtitle?private-subtitle-token', language: 'pt-BR', url }
  ] }, options);
  assert.deepEqual(result.tracks, [{ index: 0, name: 'Portuguese', language: 'pt-BR' }]);
  assert.equal(subtitleLabels.size, 1);
  assert.deepEqual([...subtitleLabels.keys()], [`${receiver.host}:${receiver.port}:${mediaKey}`]);
  const stored = [...subtitleLabels.values()].map(labels => [...labels]);
  assert.doesNotMatch(JSON.stringify(stored), /https:|private-|username|password|url/);
  assert.doesNotMatch(JSON.stringify(result), /private-|https:|mediaKey/);
  const next = await performAction({ receiver, action: 'subtitles' }, options);
  assert.deepEqual(next.tracks, result.tracks);
  for (const call of mock.calls.filter(call => call.rpc.method === 'Player.GetItem')) {
    assert.deepEqual(call.rpc.params, { playerid: 2, properties: ['file'] });
  }
});

test('invalid label metadata makes no network request and mismatched video hashes cannot populate the cache', async () => {
  const subtitleLabels = new Map();
  const mock = mockKodi();
  const valid = { receiver, action: 'label-subtitles', mediaKey: createHash('sha256').update(PUBLIC_SAMPLE_URL).digest('hex'), tracks: [{ index: 0, name: 'English', language: 'eng' }] };
  for (const patch of [
    { mediaKey: 'not-a-hash' }, { mediaKey: 'A'.repeat(64) }, { mediaKey: null },
    { tracks: [] }, { tracks: [{ index: -1, name: 'English' }] }, { tracks: [{ index: 1025, name: 'English' }] },
    { tracks: [{ index: '0', name: 'English' }] }, { tracks: [{ index: 0, name: 1 }] },
    { tracks: [{ index: 0, name: 'English', language: {} }] },
    { tracks: [{ index: 0, name: 'English' }, { index: 0, name: 'duplicate' }] },
    { tracks: [{ index: 0, name: 'https://private.example.org/subtitle?secret' }] },
    { tracks: Array.from({ length: 129 }, (_, index) => ({ index, name: 'English' })) }
  ]) {
    await assert.rejects(performAction({ ...valid, ...patch }, { ...mock, subtitleLabels }), error => {
      assert.equal(error.message, 'Provide valid subtitle labels for the current video.');
      return true;
    });
  }
  assert.equal(mock.calls.length, 0);
  await assert.rejects(performAction({ ...valid, mediaKey: '0'.repeat(64) }, { ...mock, subtitleLabels }), error => {
    assert.equal(error.message, 'Subtitle labels do not match the video currently playing.');
    return true;
  });
  assert.deepEqual(mock.calls.map(call => call.rpc.method), ['Player.GetActivePlayers', 'Player.GetItem']);
  assert.equal(subtitleLabels.size, 0);
});

test('subtitle aliases never cross media or receiver boundaries and new playback clears the receiver cache', async () => {
  const subtitleLabels = new Map();
  const mediaKey = createHash('sha256').update(PUBLIC_SAMPLE_URL).digest('hex');
  const overrides = { 'Player.GetProperties': { ...subtitleState, subtitles: [{ index: 0, name: '(External)', language: '' }] } };
  const mock = mockKodi(overrides);
  const options = { ...mock, subtitleLabels };
  const register = () => performAction({ receiver, action: 'label-subtitles', mediaKey, tracks: [{ index: 0, name: 'Portuguese', language: 'por' }] }, options);
  await register();
  overrides['Player.GetItem'] = { item: { file: 'https://media.example.org/different-video.m3u8?private-other-token' } };
  assert.deepEqual((await performAction({ receiver, action: 'subtitles' }, options)).tracks, [{ index: 0, name: '(External)', language: '' }]);
  delete overrides['Player.GetItem'];
  const otherReceiver = { ...receiver, host: '192.168.1.24' };
  assert.deepEqual((await performAction({ receiver: otherReceiver, action: 'subtitles' }, options)).tracks, [{ index: 0, name: '(External)', language: '' }]);
  for (const payload of [{ action: 'play' }, { action: 'play-source', media: { url: PUBLIC_SAMPLE_URL, contentType: 'video/mp4' } }, { action: 'stop' }]) {
    await register();
    await performAction({ receiver, ...payload }, options);
    assert.equal(subtitleLabels.size, 0);
  }
  assert.doesNotMatch(JSON.stringify([...subtitleLabels]), /Portuguese|private-other-token/);
});

test('subtitle label cache is bounded and malformed current-file replies never expose receiver metadata', async () => {
  const subtitleLabels = new Map();
  const overrides = {};
  const mock = mockKodi(overrides);
  for (let index = 0; index < 9; index++) {
    const url = `https://media.example.org/video-${index}.m3u8?private-token-${index}`;
    overrides['Player.GetItem'] = { item: { file: url } };
    await performAction({ receiver, action: 'label-subtitles', mediaKey: createHash('sha256').update(url).digest('hex'),
      tracks: [{ index: 0, name: `Language ${index}` }] }, { ...mock, subtitleLabels });
  }
  assert.equal(subtitleLabels.size, 8);
  assert.doesNotMatch(JSON.stringify([...subtitleLabels.keys()]), /https:|private-token/);
  for (const item of [null, { item: { file: { private: 'https://private.example.org/secret' } } }, { item: { file: '' } }]) {
    overrides['Player.GetItem'] = item;
    await assert.rejects(performAction({ receiver, action: 'subtitles' }, { ...mock, subtitleLabels }), error => {
      assert.equal(error.message, 'Kodi could not identify the current video.');
      return true;
    });
  }
});

test('local controller retains verified subtitle labels across commands without returning source URLs', async t => {
  const mock = mockKodi({ 'Player.GetProperties': { ...subtitleState, subtitles: [{ index: 0, name: '(External)', language: '' }] } });
  const { request } = await localServer(t, mock.fetchImpl);
  const registered = await request({ body: JSON.stringify({ receiver, action: 'label-subtitles',
    mediaKey: createHash('sha256').update(PUBLIC_SAMPLE_URL).digest('hex'), tracks: [{ index: 0, label: 'Portuguese', language: 'por' }] }) });
  assert.equal(registered.status, 200);
  const listed = await request({ body: JSON.stringify({ receiver, action: 'subtitles' }) });
  assert.equal(listed.status, 200);
  assert.deepEqual(JSON.parse(listed.text).result.tracks, [{ index: 0, name: 'Portuguese', language: 'por' }]);
  assert.doesNotMatch(listed.text, /https:|mediaKey|private-test-password/);
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

test('local source endpoint preserves origin checks and never returns active media URLs', async t => {
  const mock = mockKodi();
  const { request } = await localServer(t, mock.fetchImpl);
  const body = JSON.stringify({ receiver, action: 'play-source', media: {
    url: 'https://media.example.org/stream.m3u8?token=private-test-token', contentType: 'application/vnd.apple.mpegurl'
  } });
  const foreign = await request({ body, headers: { Origin: 'https://foreign.invalid' } });
  assert.equal(foreign.status, 403);
  assert.equal(mock.calls.length, 0);
  const response = await request({ body });
  assert.equal(response.status, 200);
  assert.deepEqual(JSON.parse(response.text), { ok: true, result: { accepted: true } });
  assert.doesNotMatch(response.text, /private-test-token|media\.example|private-test-password/);
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
