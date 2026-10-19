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

test('Open rejects a failed autoplay without claiming playback success', async () => {
  const {player, video} = fixture(); video.rejected = true;
  await assert.rejects(player.command('open', {...open, subtitles: []}), /PLAYBACK_REJECTED/);
  assert.equal(player.state().active, false);
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
