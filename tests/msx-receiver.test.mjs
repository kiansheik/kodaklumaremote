import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';

const source = await readFile(new URL('../src/msx/receiver-core.js', import.meta.url), 'utf8');
const context = vm.createContext({Promise, setTimeout, clearTimeout, isFinite});
vm.runInContext(source, context);
const core = context.LumaMsxReceiver;
const plain = value => JSON.parse(JSON.stringify(value));

class Video {
  constructor() {
    this.listeners = new Map(); this.paused = true; this.ended = false; this.error = null;
    this.currentTime = 0; this.duration = 100; this.volume = 1; this.readyState = 4; this.seekable = {length: 1};
    this.src = ''; this.playCount = 0; this.pauseCount = 0; this.loadCount = 0; this.rejected = false;
  }
  addEventListener(type, listener) { if (!this.listeners.has(type)) this.listeners.set(type, new Set()); this.listeners.get(type).add(listener); }
  removeEventListener(type, listener) { this.listeners.get(type)?.delete(listener); }
  fire(type) { for (const listener of [...this.listeners.get(type) || []]) listener(); }
  canPlayType() { return 'probably'; }
  pause() { this.pauseCount++; this.paused = true; this.fire('pause'); }
  play() {
    this.playCount++;
    if (this.rejected) return Promise.reject(new Error('platform rejected playback'));
    this.paused = false; queueMicrotask(() => this.fire('playing')); return Promise.resolve();
  }
  removeAttribute(name) { if (name === 'src') this.src = ''; }
  load() { this.loadCount++; }
}

const fixtureSrt = '1\r\n00:00:01,250 --> 00:00:03,500\r\n<i>Olá &amp; oi</i>\r\n\r\n2\r\n00:00:04,000 --> 00:00:05,000\r\nTudo bem?\r\n';
const tracks = [
  {url: 'https://captions.example/en.srt', language: 'en', label: 'English'},
  {url: 'https://captions.example/pt.srt', language: 'pt-BR', label: 'Português (Brasil)'},
  {url: 'https://captions.example/fr.vtt', language: 'fr', label: 'Français'}
];
function fixture(options = {}) {
  const video = new Video(), fetched = [], captions = [], states = [];
  const player = core.createPlayer({video, fetchText: async url => { fetched.push(url); return fixtureSrt; }, onCaption: text => captions.push(text), onState: state => states.push(plain(state)), ...options});
  return {video, player, fetched, captions, states};
}
const open = {source: 'https://media.example/video.m3u8', mimeType: 'application/vnd.apple.mpegurl', title: 'Synthetic film', subtitles: tracks, preferredLanguage: 'pt-BR'};

test('SRT is converted to WebVTT and safe timed plain-text cues', () => {
  const parsed = core.parseSubtitles(fixtureSrt);
  assert.match(parsed.vtt, /^WEBVTT\n\n00:00:01\.250 --> 00:00:03\.500/);
  assert.deepEqual(plain(parsed.cues[0]), {start: 1.25, end: 3.5, text: 'Olá & oi'});
  assert.throws(() => core.parseSubtitles('<script>invalid</script>'), /INVALID_SUBTITLE/);
  assert.throws(() => core.parseSubtitles('a'.repeat(2097153)), /SUBTITLE_TOO_LARGE/);
});

test('WebVTT header, cue identifiers/settings, and notes retain valid cues', () => {
  const result = core.parseSubtitles('WEBVTT\n\nNOTE ignore this\nnot a cue\n\nidentifier\n00:01.000 --> 00:03.000 align:start\nHello\n');
  assert.deepEqual(plain(result.cues), [{start: 1, end: 3, text: 'Hello'}]);
});

test('TV fetches only PT-BR initially, keeps every catalog entry and lazily loads English', async () => {
  const {player, video, fetched, captions} = fixture();
  await player.command('open', open);
  assert.equal(video.src, open.source);
  assert.deepEqual(fetched, ['https://captions.example/pt.srt']);
  assert.equal(player.state().tracks.length, 3); assert.equal(player.state().selectedIndex, 1);
  video.currentTime = 2; video.fire('timeupdate'); assert.equal(captions.at(-1), 'Olá & oi');
  await player.command('subtitle', {index: 0, enabled: true});
  assert.deepEqual(fetched, ['https://captions.example/pt.srt', 'https://captions.example/en.srt']);
  await player.command('subtitle', {index: 1, enabled: true}); assert.equal(fetched.length, 2);
  await player.command('subtitle', {enabled: false}); assert.equal(captions.at(-1), '');
});

test('missing Portuguese falls back to English and unrelated languages stay disabled', () => {
  assert.equal(core.preferredIndex(tracks.slice(0, 1), 'pt-BR'), 0);
  assert.equal(core.preferredIndex(tracks.slice(2), 'pt-BR'), -1);
  assert.equal(core.preferredIndex([{language: 'pt', label: 'Português'}, {language: 'en', label: 'English'}], 'pt-BR'), 1);
});

for (const [name, catalog, selected] of [['all languages', tracks, 1], ['missing Portuguese', [tracks[0], tracks[2]], 0]]) {
  test(`manual subtitles start video without caption requests for ${name}, then load only the chosen track`, async () => {
    const {player, video, fetched, captions} = fixture();
    await player.command('open', {...open, subtitles: catalog, autoSubtitles: false});
    assert.equal(player.state().active, true); assert.equal(video.src, open.source);
    assert.deepEqual(fetched, []); assert.equal(player.state().tracks.length, catalog.length);
    assert.equal(player.state().selectedIndex, -1); assert.equal(player.state().subtitlesEnabled, false);
    video.currentTime = 2; video.fire('timeupdate'); assert.equal(captions.at(-1), '');
    await player.command('subtitle', {index: selected, enabled: true, expectedSourceUrl: open.source});
    assert.deepEqual(fetched, [catalog[selected].url]); assert.equal(player.state().selectedIndex, selected);
    assert.equal(player.state().subtitlesEnabled, true); assert.equal(captions.at(-1), 'Olá & oi');
    assert.equal(video.playCount, 1);
  });
}

test('explicit automatic subtitles preserve preferred loading and malformed policy cannot replace playback', async () => {
  const {player, video, fetched} = fixture();
  await player.command('open', {...open, autoSubtitles: true});
  assert.deepEqual(fetched, [tracks[1].url]); assert.equal(player.state().selectedIndex, 1);
  const previousLoads = video.loadCount;
  for (const autoSubtitles of ['false', 0, null, {}]) {
    await assert.rejects(player.command('open', {...open, source: 'https://media.example/replacement.mp4', autoSubtitles}), /INVALID_SUBTITLES/);
  }
  assert.equal(video.src, open.source); assert.equal(video.loadCount, previousLoads);
  assert.deepEqual(fetched, [tracks[1].url]);
});

test('native-prepared subtitle selection replaces only its slot and never reloads video or fetches on Off', async () => {
  const {player, video, fetched} = fixture();
  await player.command('open', {...open, autoSubtitles: false});
  const loads = video.loadCount;
  const prepared = 'http://phone.example/receiver-cap/subtitles/prepared.srt';
  await player.command('subtitle', {index: 1, enabled: true, expectedSourceUrl: open.source, url: prepared});
  await player.command('subtitle', {index: 1, enabled: true, expectedSourceUrl: open.source, url: prepared});
  assert.deepEqual(fetched, [prepared]);
  await player.command('subtitle', {index: 1, enabled: false, expectedSourceUrl: open.source});
  assert.deepEqual(fetched, [prepared]); assert.equal(player.state().subtitlesEnabled, false);
  await player.command('subtitle', {index: 1, enabled: true, expectedSourceUrl: open.source, url: prepared + '?renewed=1'});
  assert.deepEqual(fetched, [prepared, prepared + '?renewed=1']);
  assert.equal(video.src, open.source); assert.equal(video.playCount, 1); assert.equal(video.loadCount, loads);
  await assert.rejects(player.command('subtitle', {index: 1, enabled: true, expectedSourceUrl: 'https://media.example/new.mp4', url: prepared}), /SOURCE_CHANGED/);
  assert.equal(fetched.length, 2);
});

test('all128 authorized subtitle slots remain lazy and selectable', async () => {
  const catalog = Array.from({length: 128}, (_, index) => ({url: `https://captions.example/${index}.srt`, language: 'en', label: `Track ${index}`}));
  const {player, fetched} = fixture();
  await player.command('open', {...open, subtitles: catalog, autoSubtitles: false});
  assert.equal(player.state().tracks.length, 128); assert.deepEqual(fetched, []);
  await player.command('subtitle', {index: 127, enabled: true});
  assert.deepEqual(fetched, [catalog[127].url]); assert.equal(player.state().selectedIndex, 127);
  await assert.rejects(player.command('open', {...open, subtitles: [...catalog, catalog[0]]}), /INVALID_SUBTITLES/);
  assert.equal(player.state().tracks.length, 128);
});

test('subtitle failure is separate from media failure and successful captions cannot clear a video error', async () => {
  let fail = true;
  const {player, video} = fixture({fetchText() { if (fail) throw new Error('private fetch details'); return fixtureSrt; }});
  await player.command('open', {...open, autoSubtitles: false});
  await assert.rejects(player.command('subtitle', {index: 1, enabled: true}), /SUBTITLE_FAILED/);
  assert.equal(player.state().active, true); assert.equal(player.state().error, null);
  assert.equal(player.state().subtitleError, 'SUBTITLE_FAILED');
  video.error = {code: 4}; video.fire('error'); fail = false;
  await player.command('subtitle', {index: 1, enabled: true});
  assert.equal(player.state().error, 'MEDIA_FAILED'); assert.equal(player.state().subtitleError, null);
  await player.command('subtitle', {enabled: false});
  assert.equal(player.state().error, 'MEDIA_FAILED'); assert.equal(video.playCount, 1);
});

test('Off supersedes a pending caption fetch without enabling stale text or changing video', async () => {
  let finish;
  const {player, video, captions} = fixture({fetchText: () => new Promise(resolve => { finish = resolve; })});
  await player.command('open', {...open, autoSubtitles: false});
  const selected = player.command('subtitle', {index: 1, enabled: true});
  await Promise.resolve();
  await player.command('subtitle', {enabled: false});
  finish(fixtureSrt); await assert.rejects(selected, /SUPERSEDED/);
  video.currentTime = 2; video.fire('timeupdate');
  assert.equal(captions.at(-1), ''); assert.equal(player.state().subtitlesEnabled, false);
  assert.equal(player.state().subtitleError, null); assert.equal(player.state().active, true); assert.equal(video.playCount, 1);
});

test('failed replacement captions preserve the previous selected language and visible cues', async () => {
  const {player, video, captions} = fixture({fetchText: async url => { if (url.endsWith('/broken.srt')) throw new Error('not caption text'); return fixtureSrt; }});
  await player.command('open', {...open, autoSubtitles: false});
  await player.command('subtitle', {index: 1, enabled: true});
  video.currentTime = 2; video.fire('timeupdate');
  await assert.rejects(player.command('subtitle', {index: 0, enabled: true, url: 'http://phone.example/broken.srt'}), /SUBTITLE_FAILED/);
  assert.equal(player.state().selectedIndex, 1); assert.equal(player.state().subtitlesEnabled, true);
  assert.equal(player.state().error, null); assert.equal(captions.at(-1), 'Olá & oi'); assert.equal(video.playCount, 1);
});

test('Open rejects a failed autoplay without claiming playback success', async () => {
  const {player, video} = fixture(); video.rejected = true;
  await assert.rejects(player.command('open', {...open, subtitles: []}), /PLAYBACK_REJECTED/);
  assert.equal(player.state().active, false);
});

test('media diagnostics expose only bounded numeric codes and tolerate unavailable native media getters', async () => {
  const {player, video} = fixture();
  video.error = {code: 4, message: 'https://private.example/media?token=private-message'};
  video.networkState = 3; video.readyState = 0;
  assert.deepEqual(plain(player.state().mediaDiagnostics), {errorCode: 4, networkState: 3, readyState: 0});
  assert.doesNotMatch(JSON.stringify(player.state()), /private\.example|private-message/);
  for (const [errorCode, networkState, readyState] of [[5, 4, 5], [-1, -1, -1], ['4', '3', '4'], [NaN, Infinity, 1.5]]) {
    video.error = {code: errorCode}; video.networkState = networkState; video.readyState = readyState;
    assert.deepEqual(plain(player.state().mediaDiagnostics), {errorCode: null, networkState: null, readyState: null});
  }
  video.error = {get code() { throw new Error('private native error details'); }};
  video.networkState = 2; video.readyState = 1;
  assert.deepEqual(plain(player.state().mediaDiagnostics), {errorCode: null, networkState: 2, readyState: 1});
  Object.defineProperty(video, 'networkState', {get() { throw new Error('private network details'); }});
  Object.defineProperty(video, 'readyState', {get() { throw new Error('private readiness details'); }});
  const reports = [], errors = [];
  const channel = core.createChannel({client: 'tv-one', player, onError: code => errors.push(code), request: async (method, path, body) => {
    if (path.startsWith('hello')) return {sessionId: 'session', approved: true};
    if (path.startsWith('poll')) return {approved: true, commands: []};
    if (path === 'state') reports.push(plain(body.playback));
    return {ok: true};
  }});
  await channel.tick(); await channel.tick();
  assert.equal(reports.length, 1);
  assert.deepEqual(reports[0].mediaDiagnostics, {errorCode: null, networkState: null, readyState: null});
  assert.deepEqual(errors, []);
});

test('explicit play recovers rejected autoplay only after real playing, retaining the loaded source and subtitle catalog', async () => {
  const {player, video, fetched} = fixture(); video.rejected = true;
  await assert.rejects(player.command('open', {...open, autoSubtitles: false}), /PLAYBACK_REJECTED/);
  const loads = video.loadCount;
  let start;
  video.rejected = false; video.readyState = 0;
  video.play = () => {
    video.playCount++; video.paused = false;
    return new Promise(resolve => { start = () => { video.readyState = 4; video.fire('playing'); resolve(); }; });
  };
  let settled = false;
  const retry = player.command('play').then(() => { settled = true; });
  await Promise.resolve(); await Promise.resolve();
  assert.equal(settled, false); assert.equal(player.state().active, false);
  assert.equal(player.state().error, 'PLAYBACK_REJECTED');
  start(); await retry;
  assert.equal(player.state().active, true); assert.equal(player.state().error, null);
  assert.equal(video.src, open.source); assert.equal(video.loadCount, loads);
  assert.equal(player.state().tracks.length, tracks.length); assert.deepEqual(fetched, []);
});

test('failed local play retry remains failed and Stop invalidates a pending recovery', async () => {
  const {player, video} = fixture(); video.rejected = true;
  await assert.rejects(player.command('open', {...open, subtitles: []}), /PLAYBACK_REJECTED/);
  await assert.rejects(player.command('play'), /PLAYBACK_REJECTED/);
  assert.equal(player.state().active, false);
  video.play = () => new Promise(() => {});
  const retry = player.command('play');
  await assert.rejects(player.command('play'), /PLAYBACK_STARTING/);
  await player.command('stop');
  await assert.rejects(retry, /SUPERSEDED/);
  video.fire('playing');
  assert.equal(player.state().active, false); assert.equal(player.state().source, '');
  await assert.rejects(player.command('play'), /NO_ACTIVE_VIDEO/);
});

test('receiver DOM keeps autoplay recovery visible across connected heartbeats and reconnects', async () => {
  const wrapper = await readFile(new URL('../src/msx/receiver.js', import.meta.url), 'utf8');
  const video = new Video(); video.rejected = true;
  const nodes = new Map();
  for (const id of ['waiting', 'status', 'caption', 'notice', 'start-playback']) {
    nodes.set(id, {style: {}, textContent: '', hidden: id === 'start-playback', listeners: new Map(), addEventListener(name, fn) { this.listeners.set(name, fn); }, focus() { this.focused = true; }});
  }
  nodes.set('video', video);
  let player, reportConnection;
  const storage = new Map();
  const runtime = vm.createContext({
    Promise, setTimeout, clearTimeout, isFinite,
    location: {href: 'http://phone.example/receiver-capability/receiver.html'},
    localStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)},
    XMLHttpRequest: function () { throw new Error('fixture must not perform network requests'); },
    document: {getElementById: id => nodes.get(id), createElement: () => ({}), head: {appendChild() {}}},
    window: {LumaMsxReceiver: {
      createPlayer(options) { player = core.createPlayer(options); return player; },
      createChannel(options) { reportConnection = options.onStatus; return {start() { reportConnection('connected'); }}; }
    }}
  });
  vm.runInContext(wrapper, runtime);
  await assert.rejects(player.command('open', {...open, subtitles: []}), /PLAYBACK_REJECTED/);
  const button = nodes.get('start-playback'), status = nodes.get('status');
  assert.equal(button.hidden, false); assert.equal(button.focused, true);
  const recoveryPrompt = status.textContent;
  assert.match(recoveryPrompt, /Press OK/);
  reportConnection('connected');
  assert.equal(status.textContent, recoveryPrompt);
  reportConnection('offline');
  assert.match(status.textContent, /reconnect/);
  reportConnection('connected');
  assert.equal(status.textContent, recoveryPrompt);
  video.rejected = false;
  button.listeners.get('click')();
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  assert.equal(player.state().active, true); assert.equal(button.hidden, true);
  assert.equal(nodes.get('waiting').style.display, 'none');
  video.error = {code: 3}; video.fire('error');
  const mediaFailure = status.textContent;
  assert.match(mediaFailure, /MEDIA_FAILED, media code 3/);
  reportConnection('connected'); reportConnection('connected');
  assert.equal(status.textContent, mediaFailure);
  assert.doesNotMatch(status.textContent, /Ready\./);
});

for (const [embedded, stateReadFails] of [[false, false], [true, false], [false, true]]) {
  test(`${embedded ? 'embedded MSX' : 'standalone browser'} receiver ${stateReadFails ? 'reports its first state-read failure without data leakage and recovers its channel' : embedded ? 'starts its real phone channel and retains the plugin bridge' : 'starts its real phone channel without an external MSX SDK dependency'}`, async () => {
    const wrapper = await readFile(new URL('../src/msx/receiver.js', import.meta.url), 'utf8');
    const base = 'http://phone.example/receiver-capability/', location = {href: base + 'receiver.html'};
    const video = new Video(), nodes = new Map(), scripts = [], requests = [], diagnostics = [], jobs = new Map();
    let getterFails = stateReadFails;
    if (stateReadFails) Object.defineProperty(video, 'volume', {get() { if (getterFails) throw new Error('https://private.example/source?token=private-error'); return 1; }});
    let nextTimer = 0, channel, bridgePlayer, pluginInit = 0, pluginReady = 0;
    const timers = {setTimeout(fn, delay) { jobs.set(++nextTimer, {fn, delay}); return nextTimer; }, clearTimeout(id) { jobs.delete(id); }};
    for (const id of ['waiting', 'status', 'caption', 'notice', 'start-playback']) nodes.set(id, {style: {}, hidden: true, textContent: '', addEventListener() {}, focus() {}});
    nodes.set('video', video);
    class Xhr {
      open(method, url) { this.method = method; this.url = new URL(url, location.href); }
      setRequestHeader() {}
      send() {
        requests.push(this.url.pathname);
        let result;
        if (this.url.pathname === '/msx/start.json') result = {parameter: 'content:' + base + 'home.json'};
        else if (this.url.pathname === '/receiver-capability/hello') result = {sessionId: 'fixture-session', approved: true};
        else if (this.url.pathname === '/receiver-capability/poll') result = {approved: true, commands: []};
        else if (this.url.pathname === '/receiver-capability/state') result = {ok: true};
        else if (this.url.pathname === '/receiver-capability/diagnostic') { diagnostics.push([...this.url.searchParams]); result = {ok: true}; }
        else assert.fail('Unexpected fixture request');
        this.status = 200; this.responseText = JSON.stringify(result);
        Promise.resolve().then(() => this.onload());
      }
    }
    const receiverWindow = {LumaMsxReceiver: {
      createPlayer(options) { return core.createPlayer({...options, timers}); },
      createChannel(options) { channel = core.createChannel({...options, timers}); return channel; }
    }, TVXPluginTools: {onReady(fn) { fn(); }}, TVXVideoPlugin: {
      setupPlayer(value) { bridgePlayer = value; }, init() { pluginInit++; bridgePlayer.ready(); }, startPlayback() { pluginReady++; }
    }};
    receiverWindow.parent = embedded ? {} : receiverWindow;
    const runtime = vm.createContext({Promise, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout, isFinite, location,
      localStorage: {getItem: () => 'fixture-tv-client-1234'}, XMLHttpRequest: Xhr, window: receiverWindow,
      document: {getElementById: id => nodes.get(id), createElement: () => ({}), head: {appendChild(script) { scripts.push(script); }}}
    });
    const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
    vm.runInContext(wrapper, runtime); await flush();
    assert.deepEqual(requests, ['/msx/start.json', '/receiver-capability/hello']);
    assert.equal(channel.state().approved, true);
    assert.equal(nodes.get('status').textContent, 'TV allowed. Sending receiver status to your phone…');
    assert.equal(scripts.length, embedded ? 1 : 0);
    if (embedded) {
      assert.equal(scripts[0].src, 'https://msx.benzac.de/js/tvx-plugin.min.js');
      scripts[0].onload();
      assert.equal(pluginInit, 1); assert.equal(pluginReady, 1);
      assert.equal(typeof bridgePlayer.play, 'function');
    } else {
      assert.equal(pluginInit, 0); assert.equal(pluginReady, 0);
      assert.equal(nodes.get('notice').hidden, true);
    }
    for (const [id, job] of [...jobs]) if (job.delay === 700 && jobs.delete(id)) job.fn();
    await flush();
    assert.equal(requests.includes('/receiver-capability/poll'), true);
    if (stateReadFails) {
      assert.equal(requests.includes('/receiver-capability/state'), false);
      assert.equal(nodes.get('status').textContent, 'Cannot read TV playback status. Retrying…');
      assert.deepEqual(diagnostics, [[['code', 'STATE_READ_FAILED']]]);
      for (const [id, job] of [...jobs]) if (job.delay === 2000 && jobs.delete(id)) job.fn();
      await flush();
      assert.equal(requests.includes('/receiver-capability/state'), false);
      assert.equal(diagnostics.length, 1);
      getterFails = false;
      for (const [id, job] of [...jobs]) if (job.delay === 2000 && jobs.delete(id)) job.fn();
      await flush();
      assert.equal(diagnostics.length, 1);
      assert.doesNotMatch(nodes.get('status').textContent + JSON.stringify(diagnostics), /private\.example|private-error/);
    }
    assert.equal(requests.includes('/receiver-capability/state'), true);
    assert.equal(nodes.get('status').textContent, 'Ready. Browse here or choose a video on your phone.');
    assert.equal(channel.state().approved, true);
    channel.stop();
  });
}

test('loaded receiver reclaims bootstrap after setup renewal and stale sessions without unloading media or changing capability', async () => {
  const wrapper = await readFile(new URL('../src/msx/receiver.js', import.meta.url), 'utf8');
  const base = 'http://phone.example/receiver-capability/';
  const location = {href: base + 'receiver.html'};
  const video = new Video(), nodes = new Map(), jobs = new Map(), requests = [];
  let timerId = 0, pairingOpen = false, pendingPeer = false, approved = false, session = null, nextSession = 0;
  let bootstrapParameter = 'content:' + base + 'home.json', player, channel;
  const clock = {setTimeout(fn, delay) { jobs.set(++timerId, {fn, delay}); return timerId; }, clearTimeout(id) { jobs.delete(id); }};
  const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve(); };
  const runDelay = async delay => {
    for (const [id, job] of [...jobs].filter(([, job]) => job.delay === delay)) { if (jobs.delete(id)) job.fn(); }
    await flush();
  };
  for (const id of ['waiting', 'status', 'caption', 'notice', 'start-playback']) nodes.set(id, {style: {}, hidden: id === 'start-playback', textContent: '', addEventListener() {}, focus() {}});
  nodes.set('video', video);
  class Xhr {
    open(method, url) { this.method = method; this.url = new URL(url, location.href); }
    setRequestHeader() {}
    send(body) {
      requests.push({method: this.method, path: this.url.pathname, query: this.url.searchParams, body});
      let status = 200, result;
      if (this.url.pathname === '/msx/start.json') {
        if (!pairingOpen && !approved) status = 403;
        else { pendingPeer = true; result = {parameter: bootstrapParameter}; }
      } else if (this.url.pathname === '/receiver-capability/hello') {
        if (!pendingPeer || !pairingOpen && !approved) status = 403;
        else { session = 'session-' + ++nextSession; result = {sessionId: session, approved}; }
      } else if (this.url.pathname === '/receiver-capability/poll') {
        if (this.url.searchParams.get('sessionId') !== session || !pairingOpen && !approved) status = 403;
        else result = {approved, commands: []};
      } else if (this.url.pathname === '/receiver-capability/state') result = {ok: true};
      else throw new Error('Unexpected fixture request: ' + this.url.href);
      this.status = status; this.responseText = JSON.stringify(result || {error: 'REQUEST_REJECTED'});
      Promise.resolve().then(() => this.onload());
    }
  }
  const storage = new Map();
  const runtime = vm.createContext({
    Promise, setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout, isFinite, location,
    localStorage: {getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value)},
    XMLHttpRequest: Xhr,
    document: {getElementById: id => nodes.get(id), createElement: () => ({}), head: {appendChild() {}}},
    window: {LumaMsxReceiver: {
      createPlayer(options) { player = core.createPlayer({...options, timers: clock}); return player; },
      createChannel(options) { channel = core.createChannel({...options, timers: clock}); return channel; }
    }}
  });
  vm.runInContext(wrapper, runtime);
  await player.command('open', {...open, subtitles: []}); await flush();
  const loaded = {pauses: video.pauseCount, loads: video.loadCount, source: video.src};
  const count = path => requests.filter(request => request.path === path).length;
  assert.equal(count('/msx/start.json'), 1); assert.equal(count('/receiver-capability/hello'), 0);
  assert.equal(channel.state().sessionId, null);

  // The phone renews its window. The already loaded page reclaims the peer.
  pairingOpen = true;
  await runDelay(2000);
  assert.equal(count('/msx/start.json'), 2); assert.equal(count('/receiver-capability/hello'), 1);
  assert.equal(pendingPeer, true); assert.equal(channel.state().approved, false);
  assert.equal(nodes.get('status').textContent, 'Approve this TV in LUMA Remote on your phone.');

  // Approval remains an explicit phone action, independent of bootstrap.
  approved = true; pairingOpen = false;
  await runDelay(700); await runDelay(700);
  assert.equal(channel.state().approved, true); assert.equal(count('/msx/start.json'), 2);
  session = null;
  await runDelay(700);
  assert.equal(channel.state().sessionId, null);
  await runDelay(2000);
  assert.equal(count('/msx/start.json'), 3); assert.equal(count('/receiver-capability/hello'), 2);
  assert.equal(channel.state().approved, true);

  // A rotated capability must not make an old receiver claim a new session.
  bootstrapParameter = 'content:http://phone.example/new-capability/home.json'; session = null;
  await runDelay(700); await runDelay(2000);
  assert.equal(count('/msx/start.json'), 4); assert.equal(count('/receiver-capability/hello'), 2);
  assert.equal(channel.state().sessionId, null);
  assert.deepEqual({pauses: video.pauseCount, loads: video.loadCount, source: video.src}, loaded);
  assert.equal(player.state().active, true); assert.equal(location.href, base + 'receiver.html');
  assert.equal(requests.some(request => request.path.endsWith('/ack')), false);
  channel.stop();
});

test('subtitle error receipt waits for playing and leaves independent video active', async () => {
  let start;
  const {player, video} = fixture({fetchText: async () => { throw new Error('private detailed source URL'); }});
  video.readyState = 0;
  video.play = () => { video.paused = false; return new Promise(resolve => { start = () => { video.readyState = 4; video.fire('playing'); resolve(); }; }); };
  let settled = false;
  const result = player.command('open', open).then(() => { settled = true; }, error => { settled = true; throw error; });
  await Promise.resolve(); await Promise.resolve(); assert.equal(settled, false);
  start(); await assert.rejects(result, /SUBTITLE_FAILED/);
  assert.equal(player.state().active, true); assert.equal(video.src, open.source); assert.equal(player.state().subtitlesEnabled, false);
});

test('replacing media invalidates old subtitle fetches and does not render stale captions', async () => {
  let oldSubtitle;
  const {player, video} = fixture({fetchText: () => new Promise(resolve => { oldSubtitle = resolve; })});
  const pending = player.command('open', open);
  await Promise.resolve();
  await player.command('open', {...open, source: 'https://media.example/new.mp4', subtitles: []});
  oldSubtitle(fixtureSrt);
  await assert.rejects(pending, /SUPERSEDED/);
  assert.equal(player.state().source, 'https://media.example/new.mp4'); assert.equal(player.state().selectedIndex, -1);
  assert.equal(video.src, 'https://media.example/new.mp4');
});

test('HLS fallback fetches the original source through HLS.js and reports fatal decoder failure', async () => {
  let instance;
  class Hls {
    static Events = {ERROR: 'error'};
    static isSupported() { return true; }
    constructor() { instance = this; }
    on(_, handler) { this.handler = handler; }
    loadSource(url) { this.source = url; }
    attachMedia(video) { this.video = video; }
    destroy() { this.destroyed = true; }
  }
  const {player, video} = fixture({Hls}); video.canPlayType = () => '';
  await player.command('open', {...open, subtitles: []});
  assert.equal(instance.source, open.source); assert.equal(instance.video, video);
  instance.handler(null, {fatal: true}); assert.equal(player.state().active, false); assert.equal(player.state().error, 'MEDIA_FAILED');
  await player.command('stop'); assert.equal(instance.destroyed, true);
});

test('bounded startup timeout returns a failure instead of hanging indefinitely', async () => {
  const scheduled = new Map(); let id = 0;
  const timers = {setTimeout(fn) { scheduled.set(++id, fn); return id; }, clearTimeout(value) { scheduled.delete(value); }};
  const {player, video} = fixture({timers}); video.play = () => new Promise(() => {});
  const pending = player.command('open', {...open, subtitles: []});
  for (const fn of [...scheduled.values()]) fn();
  await assert.rejects(pending, /PLAYBACK_TIMEOUT/);
});

test('control channel requires phone approval before reporting state or executing commands', async () => {
  const calls = []; const player = {state: () => ({active: false}), command: async () => { throw new Error('must not execute'); }};
  const request = async (method, path) => { calls.push([method, path]); return path.startsWith('hello') ? {sessionId: 'session', approved: false} : {approved: false, commands: [{id: 1, method: 'stop'}]}; };
  const channel = core.createChannel({request, player, client: 'tv-one'});
  await channel.tick(); await channel.tick();
  assert.equal(calls.length, 2); assert.ok(calls.every(call => call[0] === 'GET')); assert.equal(channel.state().after, 0);
});

test('optional beforeHello must succeed before session acquisition and is not run on ordinary polls', async () => {
  const calls = []; let renewed = false;
  const channel = core.createChannel({
    client: 'tv-one', player: {state: () => ({active: false}), command: async () => { throw new Error('No commands expected'); }},
    beforeHello: async () => { calls.push('bootstrap'); if (!renewed) throw Object.assign(new Error('window expired'), {status: 403}); },
    request: async (method, path) => { calls.push(path.split('?')[0]); return path.startsWith('hello') ? {sessionId: 'session', approved: false} : {approved: false, commands: []}; }
  });
  await assert.rejects(channel.tick(), /window expired/);
  assert.deepEqual(calls, ['bootstrap']);
  renewed = true; await channel.tick(); await channel.tick();
  assert.deepEqual(calls, ['bootstrap', 'bootstrap', 'hello', 'poll']);
});

test('channel applies ordered commands once, posts observed state before failure ACK', async () => {
  const calls = [], executed = [], acks = [];
  const request = async (method, path, body) => {
    calls.push(path);
    if (path.startsWith('hello')) return {sessionId: 'session-one', approved: true};
    if (path.startsWith('poll')) return {approved: true, commands: [{id: 2, method: 'seek', params: {}}, {id: 1, method: 'open', params: {}}, {id: 1, method: 'open', params: {}}]};
    if (path === 'ack') acks.push(plain(body));
    return {ok: true};
  };
  const player = {state: () => ({active: true}), command: async method => { executed.push(method); if (method === 'seek') throw Object.assign(new Error('details'), {code: 'SEEK_UNAVAILABLE'}); }};
  const channel = core.createChannel({request, player, client: 'tv-one'});
  await channel.tick(); await channel.tick(); await channel.tick();
  assert.deepEqual(executed, ['open', 'seek']); assert.equal(channel.state().after, 2);
  assert.equal(acks[1].ok, false); assert.equal(acks[1].error, 'SEEK_UNAVAILABLE');
  for (const [i, call] of calls.entries()) if (call === 'ack') assert.equal(calls[i - 1], 'state');
});

test('lost ACK is retried from its receipt without replaying Open', async () => {
  let opened = 0, ackAttempts = 0, polls = 0;
  const request = async (method, path) => {
    if (path.startsWith('hello')) return {sessionId: 'session-one', approved: true};
    if (path.startsWith('poll')) return {approved: true, commands: ++polls === 1 ? [{id: 1, method: 'open', params: {}}] : []};
    if (path === 'ack' && ++ackAttempts === 1) throw new Error('phone offline after receiving acknowledgement');
    return {ok: true};
  };
  const channel = core.createChannel({request, player: {state: () => ({active: true}), command: async () => { opened++; }}, client: 'tv-one'});
  await channel.tick(); await assert.rejects(channel.tick()); await channel.tick();
  assert.equal(opened, 1); assert.equal(ackAttempts, 2); assert.equal(channel.state().after, 1);
});

test('debug controls are applied once without changing active media and reject extra or arbitrary label inputs', async () => {
  const controls = [], receipts = [], media = {active: true, source: 'https://media.example/playing.mp4', positionSeconds: 19};
  const original = plain(media);
  const channel = core.createChannel({client: 'tv-debug',
    player: {state: () => ({...media}), command() { assert.fail('Debug controls must not reach the player'); }},
    onControl(method, params) { controls.push([method, plain(params)]); },
    request: async (method, path, body) => {
      if (path.startsWith('hello')) return {sessionId: 'debug-session', approved: true};
      if (path.startsWith('poll')) return {approved: true, commands: [
        {id: 1, method: 'ping', params: {}}, {id: 1, method: 'ping', params: {}},
        {id: 2, method: 'set-label', params: {label: 'LUMA debug channel ready'}},
        {id: 3, method: 'set-label', params: {label: 'https://private.example/arbitrary'}},
        {id: 4, method: 'ping', params: {unexpected: true}}
      ]};
      if (path === 'ack') receipts.push(plain(body));
      return {ok: true};
    }
  });
  await channel.tick(); await channel.tick(); await channel.tick();
  assert.deepEqual(controls, [['ping', {}], ['set-label', {label: 'LUMA debug channel ready'}]]);
  assert.deepEqual(receipts.map(value => [value.id, value.ok, value.error || null]), [[1, true, null], [2, true, null], [3, false, 'INVALID_COMMAND'], [4, false, 'INVALID_COMMAND']]);
  assert.deepEqual(media, original);
});

test('reload waits for successful state and explicit ACK, retries uncertainty, and stops before navigating once', async () => {
  const calls = [], jobs = new Map();
  let next = 0, stateAttempts = 0, ackAttempts = 0, navigations = 0;
  const timers = {setTimeout(fn, delay) { jobs.set(++next, {fn, delay}); return next; }, clearTimeout(id) { jobs.delete(id); }};
  const flush = async () => { for (let i = 0; i < 50; i++) await Promise.resolve(); };
  const channel = core.createChannel({client: 'tv-reload', timers,
    player: {state: () => ({active: false}), command() { assert.fail('No media action may follow reload'); }},
    onReload() { calls.push('navigate'); navigations++; assert.equal(jobs.size, 0); },
    request: async (method, path) => {
      calls.push(path.split('?')[0]);
      if (path.startsWith('hello')) return {sessionId: 'reload-session', approved: true};
      if (path.startsWith('poll')) return {approved: true, commands: [{id: 1, method: 'reload', params: {}}, {id: 2, method: 'pause', params: {}}]};
      if (path === 'state' && ++stateAttempts === 1) throw new Error('state unavailable');
      if (path === 'ack') {
        ackAttempts++;
        if (ackAttempts === 1) throw new Error('ACK outcome unknown');
        if (ackAttempts === 2) return {ok: false};
      }
      return {ok: true};
    }
  });
  channel.start(); await flush();
  assert.ok(jobs.size > 0);
  await assert.rejects(channel.tick(), /state unavailable/);
  assert.equal(ackAttempts, 0); assert.equal(navigations, 0);
  await assert.rejects(channel.tick(), /ACK outcome unknown/);
  assert.equal(navigations, 0);
  await assert.rejects(channel.tick(), error => error.code === 'INVALID_RESPONSE');
  assert.equal(navigations, 0); assert.equal(channel.state().after, 0);
  await channel.tick();
  assert.equal(navigations, 1); assert.equal(channel.state().after, 1);
  assert.deepEqual(calls.slice(-3), ['state', 'ack', 'navigate']);
  const settledCalls = [...calls];
  await channel.tick(); channel.start(); await flush();
  assert.deepEqual(calls, settledCalls); assert.equal(navigations, 1); assert.equal(jobs.size, 0);
});

test('reload of active playback is rejected with a real receipt while playback and channel remain usable', async () => {
  const receipts = []; let pings = 0, navigations = 0;
  const channel = core.createChannel({client: 'tv-active',
    player: {state: () => ({active: true}), command() { assert.fail('Reload must not stop media'); }},
    onControl() { pings++; }, onReload() { navigations++; },
    request: async (method, path, body) => {
      if (path.startsWith('hello')) return {sessionId: 'active-session', approved: true};
      if (path.startsWith('poll')) return {approved: true, commands: [{id: 1, method: 'reload', params: {}}, {id: 2, method: 'ping', params: {}}]};
      if (path === 'ack') receipts.push(plain(body));
      return {ok: true};
    }
  });
  await channel.tick(); await channel.tick(); await channel.tick();
  assert.equal(navigations, 0); assert.equal(pings, 1);
  assert.equal(receipts[0].ok, false); assert.equal(receipts[0].error, 'RELOAD_BLOCKED');
  assert.equal(receipts[1].ok, true); assert.equal(channel.state().after, 2);
});

for (const injected of [false, true]) {
  test(`actual wrapper exposes ${injected ? 'injected' : 'unbuilt null'} revision and bounded debug state, then reloads only after ACK`, async () => {
    let wrapper = await readFile(new URL('../src/msx/receiver.js', import.meta.url), 'utf8');
    const revision = 'a'.repeat(64);
    assert.equal(wrapper.split('__LUMA_RECEIVER_REVISION__').length, 2);
    if (injected) wrapper = wrapper.replace('__LUMA_RECEIVER_REVISION__', revision);
    const base = 'http://phone.example/receiver-capability/', nodes = new Map(), jobs = new Map(), states = [], controls = [];
    const video = new Video(); let next = 0, channel, player, elapsed = 1000, reloads = 0, ackSeen = false, poll = 0;
    const timers = {setTimeout(fn, delay) { jobs.set(++next, {fn, delay}); return next; }, clearTimeout(id) { jobs.delete(id); }};
    for (const id of ['waiting', 'status', 'caption', 'notice', 'start-playback']) nodes.set(id, {style: {}, hidden: true, textContent: '', addEventListener() {}, focus() {}});
    nodes.set('video', video);
    const location = {href: base + 'receiver.html', reload() { assert.equal(ackSeen, true); reloads++; }};
    class Xhr {
      open(method, url) { this.method = method; this.url = new URL(url, location.href); }
      setRequestHeader() {}
      send(body) {
        const path = this.url.pathname; let result = {ok: true};
        if (path === '/msx/start.json') result = {parameter: 'content:' + base + 'home.json'};
        else if (path.endsWith('/hello')) result = {sessionId: 'debug-session', approved: true};
        else if (path.endsWith('/poll')) {
          poll++;
          result = {approved: true, commands: poll === 1 ? [{id: 1, method: 'ping', params: {}}, {id: 2, method: 'set-label', params: {label: 'LUMA debug channel ready'}}] : [{id: 3, method: 'reload', params: {}}]};
        } else if (path.endsWith('/state')) states.push(JSON.parse(body).playback);
        else if (path.endsWith('/ack')) { const value = JSON.parse(body); controls.push(value); if (value.id === 3) ackSeen = true; }
        else assert.fail('Unexpected wrapper request');
        this.status = 200; this.responseText = JSON.stringify(result); Promise.resolve().then(() => this.onload());
      }
    }
    const receiverWindow = {performance: {now() { return elapsed; }}, LumaMsxReceiver: {
      createPlayer(options) { player = core.createPlayer({...options, timers}); return player; },
      createChannel(options) { channel = core.createChannel({...options, timers}); return channel; }
    }};
    receiverWindow.parent = receiverWindow;
    const runtime = vm.createContext({Promise, setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout, isFinite, location, window: receiverWindow,
      localStorage: {getItem: () => 'fixture-debug-client'}, XMLHttpRequest: Xhr,
      document: {getElementById: id => nodes.get(id), createElement: () => ({}), head: {appendChild() { assert.fail('Standalone must not load external SDK'); }}}
    });
    const flush = async () => { for (let i = 0; i < 100; i++) await Promise.resolve(); };
    vm.runInContext(wrapper, runtime); await flush();
    const before = player.state(); elapsed = 5000;
    await channel.tick();
    const debug = states.at(-1).receiverDebug;
    assert.deepEqual(debug, {revision: injected ? revision : null, uptimeSeconds: 4, pingCount: 1, remoteReload: true, remoteCommands: true, inputCount: 0, lastInput: 'none', pointerLockSupported: false, pointerLocked: false, pointerMoveCount: 0, pointerLockError: 'none'});
    assert.equal(nodes.get('notice').textContent, 'LUMA debug channel ready');
    assert.equal(nodes.get('notice').hidden, false);
    assert.equal(video.src, ''); assert.equal(video.playCount, 0); assert.equal(video.pauseCount, 0);
    assert.equal(player.state().active, before.active); assert.equal(reloads, 0);
    elapsed = 3000; assert.equal(player.state().receiverDebug.uptimeSeconds, 4);
    receiverWindow.performance.now = () => { throw new Error('private clock failure'); };
    assert.equal(player.state().receiverDebug.uptimeSeconds, 4);
    await channel.tick(); await channel.tick();
    assert.equal(reloads, 1); assert.deepEqual(controls.map(value => [value.id, value.ok]), [[1, true], [2, true], [3, true]]);
    channel.stop();
  });
}

test('synchronous status rendering and state reads cannot kill future channel ticks or independent heartbeats', async () => {
  const jobs = new Map(), errors = [], requests = [];
  let next = 0, getterFails = true;
  const timers = {setTimeout(fn, delay) { jobs.set(++next, {fn, delay}); return next; }, clearTimeout(id) { jobs.delete(id); }};
  const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve(); };
  const run = async delay => { for (const [id, job] of [...jobs]) if (job.delay === delay && jobs.delete(id)) job.fn(); await flush(); };
  const channel = core.createChannel({timers, client: 'tv-one',
    player: {state() { if (getterFails) throw new Error('private state getter details'); return {active: false}; }},
    onStatus() { throw new Error('private DOM failure'); },
    onError(code) { errors.push(code); throw new Error('error callback also failed'); },
    request(method, path) {
      requests.push(path.split('?')[0]);
      return Promise.resolve(path.startsWith('hello') ? {sessionId: 'session', approved: true} : path.startsWith('poll') ? {approved: true, commands: []} : {ok: true});
    }
  });
  channel.start(); await flush();
  await run(700); await run(2000);
  assert.equal(requests.includes('state'), false);
  assert.ok(errors.includes('STATE_READ_FAILED')); assert.ok(errors.includes('STATUS_RENDER_FAILED'));
  assert.ok([...jobs.values()].some(job => job.delay === 2000));
  getterFails = false;
  await run(2000);
  assert.ok(requests.includes('state'));
  await run(4000);
  assert.ok([...jobs.values()].some(job => job.delay === 700));
  const states = requests.filter(path => path === 'state').length;
  await run(2000);
  assert.ok(requests.filter(path => path === 'state').length > states);
  assert.ok(errors.every(code => ['STATE_READ_FAILED', 'STATUS_RENDER_FAILED'].includes(code)));
  channel.stop();
});

test('a state-read failure after executing Open preserves its uncertain receipt without replaying the command', async () => {
  let opened = 0, stateFails = true, acks = 0;
  const channel = core.createChannel({client: 'tv-one',
    player: {state() { if (stateFails) throw new Error('private getter failure'); return {active: true}; }, command() { opened++; return Promise.resolve(); }},
    request: async (method, path) => {
      if (path.startsWith('hello')) return {sessionId: 'session', approved: true};
      if (path.startsWith('poll')) return {approved: true, commands: [{id: 1, method: 'open', params: {}}]};
      if (path === 'ack') acks++;
      return {ok: true};
    }
  });
  await channel.tick();
  await assert.rejects(channel.tick(), error => error.code === 'STATE_READ_FAILED');
  assert.equal(opened, 1); assert.equal(acks, 0); assert.equal(channel.state().after, 0);
  stateFails = false;
  await channel.tick(); await channel.tick();
  assert.equal(opened, 1); assert.equal(acks, 1); assert.equal(channel.state().after, 1);
});

test('channel errors expose only fixed HTTP, network, response and fallback stage codes and keep bounded retries', async () => {
  for (const [failureValue, expected] of [
    [Object.assign(new Error('private HTTP details'), {status: 403}), 'HTTP_REJECTED'],
    [Object.assign(new Error('private network details'), {code: 'NETWORK_UNAVAILABLE'}), 'NETWORK_UNAVAILABLE'],
    [Object.assign(new Error('private response details'), {code: 'INVALID_RESPONSE'}), 'INVALID_RESPONSE'],
    [new Error('https://private.example/token'), 'CHANNEL_FAILED']
  ]) {
    const jobs = new Map(), errors = []; let next = 0;
    const timers = {setTimeout(fn, delay) { jobs.set(++next, {fn, delay}); return next; }, clearTimeout(id) { jobs.delete(id); }};
    const channel = core.createChannel({timers, client: 'tv-one', player: {state: () => ({active: false})},
      request() { throw failureValue; }, onError: code => errors.push(code), onStatus() { throw new Error('DOM offline failure'); }});
    channel.start(); for (let i = 0; i < 25; i++) await Promise.resolve();
    assert.equal(errors.includes(expected), true);
    for (let attempt = 0; attempt < 5; attempt++) {
      const timer = [...jobs].find(([, job]) => job.delay !== 2000 || job.fn.name !== 'heartbeat');
      assert.ok(timer); jobs.delete(timer[0]); timer[1].fn();
      for (let i = 0; i < 25; i++) await Promise.resolve();
    }
    assert.ok([...jobs.values()].every(job => job.delay >= 700 && job.delay <= 10000));
    assert.ok(errors.every(code => code === expected || code === 'STATUS_RENDER_FAILED'));
    channel.stop();
  }
});

test('host timer scheduling failures report a fixed stage without throwing out of channel startup', async () => {
  const errors = [], requests = [];
  const channel = core.createChannel({client: 'tv-one',
    timers: {setTimeout() { throw new TypeError('private host timer details'); }, clearTimeout() {}},
    player: {state: () => ({active: false})},
    request: async (method, path) => { requests.push(path); return {sessionId: 'session', approved: true}; },
    onError: code => errors.push(code)
  });
  assert.doesNotThrow(() => channel.start());
  for (let i = 0; i < 20; i++) await Promise.resolve();
  assert.equal(requests.length, 1);
  assert.deepEqual(errors, ['CHANNEL_FAILED', 'CHANNEL_FAILED']);
  channel.stop();
});

test('independent heartbeat stays live while Open is awaiting TV playback', async () => {
  const timers = new Map(); let nextTimer = 0, resolveOpen, states = 0;
  const clock = {setTimeout(fn, delay) { timers.set(++nextTimer, {fn, delay}); return nextTimer; }, clearTimeout(id) { timers.delete(id); }};
  const request = async (method, path) => {
    if (path.startsWith('hello')) return {sessionId: 'session', approved: true};
    if (path.startsWith('poll')) return {approved: true, commands: [{id: 1, method: 'open', params: {}}]};
    if (path === 'state') states++;
    return {ok: true};
  };
  const channel = core.createChannel({request, player: {state: () => ({active: false}), command: () => new Promise(resolve => { resolveOpen = resolve; })}, client: 'tv-one', timers: clock});
  const flush = async () => { for (let i = 0; i < 10; i++) await Promise.resolve(); };
  channel.start(); await flush();
  const poll = [...timers.values()].find(timer => timer.delay === 700); poll.fn(); await flush();
  assert.equal(typeof resolveOpen, 'function');
  const beat = [...timers.values()].find(timer => timer.delay === 2000); beat.fn(); await flush();
  assert.equal(states, 1);
  channel.stop(); resolveOpen(); await flush();
});

test('stale-session403 reconnects without replaying or unloading current media', async () => {
  const timers = new Map(); let nextTimer = 0, hellos = 0, polls = 0, commands = 0;
  const clock = {setTimeout(fn, delay) { timers.set(++nextTimer, {fn, delay}); return nextTimer; }, clearTimeout(id) { timers.delete(id); }};
  const request = async (method, path) => {
    if (path.startsWith('hello')) return {sessionId: 'session-' + ++hellos, approved: true};
    if (path.startsWith('poll') && ++polls === 1) throw Object.assign(new Error('stale session'), {status: 403});
    return path.startsWith('poll') ? {approved: true, commands: []} : {ok: true};
  };
  const channel = core.createChannel({request, player: {state: () => ({active: true}), command: async () => { commands++; }}, client: 'tv-one', timers: clock});
  const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
  channel.start(); await flush();
  [...timers.values()].find(timer => timer.delay === 700).fn(); await flush();
  assert.equal(channel.state().sessionId, null);
  [...timers.values()].find(timer => timer.delay === 2000).fn(); await flush();
  // The first2000ms timer is the heartbeat; select the reconnect loop timer too.
  for (const timer of [...timers.values()].filter(timer => timer.delay === 2000)) timer.fn();
  await flush(); channel.stop();
  assert.ok(hellos >= 2); assert.equal(commands, 0);
});

test('phone network loss leaves the playing video and loaded captions untouched', async () => {
  const {player, video, captions} = fixture(); await player.command('open', open);
  const channel = core.createChannel({request: async () => { throw new Error('phone powered off'); }, player, client: 'tv-one'});
  const pauseCount = video.pauseCount, loadCount = video.loadCount;
  await assert.rejects(channel.tick());
  video.currentTime = 2; video.fire('timeupdate');
  assert.equal(video.pauseCount, pauseCount); assert.equal(video.loadCount, loadCount); assert.equal(player.state().active, true); assert.equal(captions.at(-1), 'Olá & oi');
});

test('stale phone controls cannot mutate a replacement video', async () => {
  const {player, video, fetched} = fixture(); await player.command('open', open);
  const original = {pauseCount: video.pauseCount, time: video.currentTime, volume: video.volume, fetched: fetched.length};
  for (const [method, params] of [
    ['playPause', {play: false}], ['seek', {seconds: 50}], ['volume', {value: 2}],
    ['subtitle', {index: 0, enabled: true}], ['stop', {}]
  ]) await assert.rejects(player.command(method, {...params, expectedSourceUrl: 'https://media.example/previous.mp4'}), /SOURCE_CHANGED/);
  assert.equal(video.pauseCount, original.pauseCount); assert.equal(video.currentTime, original.time);
  assert.equal(video.volume, original.volume); assert.equal(fetched.length, original.fetched); assert.equal(player.state().active, true);
});

test('receiver assets do not embed media credentials, source links or phone proxies', async () => {
  const html = await readFile(new URL('../src/msx/receiver.html', import.meta.url), 'utf8');
  assert.match(html, /name="referrer" content="no-referrer"/);
  assert.match(html, /src="hls\.min\.js"/);
  assert.doesNotMatch(html, /192\.168|framextv\.xyz|password|https?:\/\/.*m3u8/);
});
