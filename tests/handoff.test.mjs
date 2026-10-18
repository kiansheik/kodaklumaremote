import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandoffCoordinator, HandoffError } from '../src/handoff/coordinator.mjs';

const url = 'https://media.example.org/episode.m3u8?token=private-fixture';
const track = (language, suffix = language, label = language) => ({ url: `https://captions.example.org/${suffix}.vtt`, contentType: 'text/vtt', language, label });
const media = tracks => ({ url, contentType: 'application/vnd.apple.mpegurl', title: 'Example', tracks });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

function fixture(options = {}) {
  const state = {
    time: 0, calls: [], statuses: [], ready: [], adds: [], opened: 0, connections: 0,
    playback: options.playback || { active: false, sourceUrl: '' },
    tracks: options.tracks || [], enabled: false, currentIndex: null, pending: [],
  };
  const record = (name, opts) => {
    assert.ok(opts.signal instanceof AbortSignal);
    assert.equal(opts.expectedSourceUrl, url);
    state.calls.push(name);
  };
  const adapters = {
    receiver: { async launchKodi(opts) { record('launch', opts); await options.launch?.(state, opts); } },
    kodi: {
      async connect(opts) { record('connect', opts); state.connections++; return { ready: state.connections >= (options.readyAfter || 1) }; },
      async open(value, opts) {
        record('open', opts); state.opened++;
        assert.equal(value.url, url);
        state.playback = { active: !options.neverActive, sourceUrl: value.url };
        await options.open?.(state, opts);
      },
      async status(opts) { record('status', opts); return { ...state.playback }; },
      async getSubtitles(opts) {
        record('getSubtitles', opts);
        await options.getSubtitles?.(state, opts);
        for (const pending of [...state.pending]) {
          if (--pending.polls <= 0) {
            state.tracks.push({ index: pending.index });
            state.pending.splice(state.pending.indexOf(pending), 1);
          }
        }
        return { tracks: state.tracks.map(item => ({ ...item })), enabled: state.enabled, currentIndex: state.currentIndex };
      },
      async addSubtitle(value, opts) {
        record('addSubtitle', opts); state.adds.push(value.url);
        if (options.add) { await options.add(state, value, opts); return; }
        const index = Math.max(-1, ...state.tracks.map(item => item.index), ...state.pending.map(item => item.index)) + 1;
        state.pending.push({ index, polls: 2 });
      },
      async setSubtitle(index, opts) {
        record('setSubtitle', opts);
        if (options.select) { await options.select(state, index, opts); return; }
        state.currentIndex = index === 'off' ? null : index;
        state.enabled = index !== 'off';
      },
    },
    remote: { async ready(result, opts) { record('remote', opts); state.ready.push(result); } },
    now: () => state.time,
    wait: async (ms, opts) => { record('wait', opts); state.time += ms; await options.wait?.(state, opts); },
    onStatus: value => state.statuses.push(value),
    ...options.coordinator,
  };
  return { state, adapters, coordinator: createHandoffCoordinator(adapters) };
}

test('handoff launches, waits for Kodi, loads every unique declared track sequentially and selects Brazilian Portuguese', async () => {
  const f = fixture({ readyAfter: 2, tracks: [{ index: 7, language: 'en', name: 'Unknown external' }] });
  const english = track('en');
  const result = await f.coordinator.start(media([english, track('de'), track('pt-BR'), { ...english }]));
  assert.equal(f.state.calls[0], 'launch');
  assert.equal(f.state.connections, 2);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.adds.length, 3);
  assert.deepEqual(result.tracks.map(item => item.index), [8, 9, 10]);
  assert.equal(result.subtitleCount, 3);
  assert.equal(result.selectedLanguage, 'pt-br');
  assert.equal(result.selectedIndex, 10);
  assert.equal(f.state.currentIndex, 10);
  assert.equal(f.state.enabled, true);
  assert.equal(f.state.ready.length, 1);
  assert.ok(f.state.calls.indexOf('open') < f.state.calls.indexOf('addSubtitle'));
  assert.equal(f.state.statuses.at(-1).step, 'ready');
  assert.ok(!JSON.stringify(result).includes('https:'));
  assert.ok(!JSON.stringify(f.state.statuses).includes('private-fixture'));
});

test('handoff defaults to English after Brazilian Portuguese; other Portuguese requires an explicit preference', async () => {
  const f = fixture();
  assert.equal((await f.coordinator.start(media([track('pt-PT'), track('eng')]))).selectedLanguage, 'en');
  const other = fixture();
  const off = await other.coordinator.start(media([track('pt-PT')]));
  assert.equal(off.selectedIndex, null);
  assert.equal(other.state.enabled, false);
  const explicit = fixture({ coordinator: { preferredLanguages: ['pt-BR', 'en', 'pt-PT'] } });
  assert.equal((await explicit.coordinator.start(media([track('pt-PT')]))).selectedLanguage, 'pt-pt');
});

test('handoff recognizes a Brazil label and English regional language metadata', async () => {
  const f = fixture();
  assert.equal((await f.coordinator.start(media([track('en_US'), track('pt', 'brazil', 'Português (Brasil)')]))).selectedLanguage, 'pt-br');
});

test('handoff reuses a still-active source and confirmed bindings on retry without adding duplicates', async () => {
  const f = fixture();
  const request = media([track('en'), track('pt-BR')]);
  await f.coordinator.start(request);
  await f.coordinator.start(request);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.adds.length, 2);
  assert.equal(f.state.ready.length, 2);
});

test('handoff adopts an explicitly identified existing track and does not infer unknown external tracks', async () => {
  const english = track('en');
  const f = fixture({ playback: { active: true, sourceUrl: url }, tracks: [{ index: 4, language: 'en', name: 'Unknown external' }, { index: 9, url: english.url }] });
  const result = await f.coordinator.start(media([english, track('pt-BR')]));
  assert.equal(f.state.opened, 0);
  assert.equal(f.state.adds.length, 1);
  assert.equal(result.tracks[0].index, 9);
  assert.equal(result.tracks[1].index, 10);
});

test('handoff stops before opening when the page source changes during launch', async () => {
  let current = true;
  const f = fixture({ launch: () => { current = false; } });
  await assert.rejects(f.coordinator.start(media([]), { isCurrentSource: () => current }), { code: 'SOURCE_CHANGED' });
  assert.equal(f.state.connections, 0);
  assert.equal(f.state.opened, 0);
  assert.equal(f.state.ready.length, 0);
});

test('handoff detects a receiver source change after an awaited subtitle operation', async () => {
  const f = fixture({ add: state => { state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' }; } });
  await assert.rejects(f.coordinator.start(media([track('en'), track('pt-BR')])), { code: 'SOURCE_CHANGED' });
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.ready.length, 0);
  assert.ok(!f.state.calls.includes('setSubtitle'));
});

test('handoff cancellation and a new start suppress late operations from the old run', async () => {
  const launch = deferred();
  let launches = 0;
  const f = fixture({ launch: async () => { if (++launches === 1) await launch.promise; } });
  const first = f.coordinator.start(media([]));
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await Promise.resolve(); await Promise.resolve();
  const second = f.coordinator.start(media([]));
  launch.resolve();
  await cancelled;
  await second;
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
  assert.equal(f.state.statuses.at(-1).step, 'ready');
});

test('handoff retry adopts a late subtitle registration after cancellation instead of adding it again', async () => {
  const addition = deferred();
  const started = deferred();
  let resumed = false;
  const f = fixture({
    add: async () => { started.resolve(); await addition.promise; },
    wait: state => {
      if (resumed && !state.tracks.length) { state.tracks.push({ index: 0 }); addition.resolve(); }
    },
  });
  const request = media([track('pt-BR')]);
  const first = f.coordinator.start(request);
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise;
  f.coordinator.cancel();
  await cancelled;
  resumed = true;
  const result = await f.coordinator.start(request);
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.opened, 1);
  assert.equal(result.selectedIndex, 0);
  assert.equal(f.state.ready.length, 1);
});

test('handoff retry reissues a cancelled subtitle only after explicit native proof that it was not sent', async () => {
  const queued = deferred();
  let notSent;
  const f = fixture({ add: (state, value, options) => {
    if (state.adds.length === 1) {
      notSent = options.onNotSent;
      queued.resolve();
      return new Promise(() => {});
    }
    state.tracks.push({ index: 0 });
  } });
  const request = media([track('pt-BR')]);
  const first = f.coordinator.start(request);
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await queued.promise;
  f.coordinator.cancel();
  await cancelled;
  notSent(); // A receipt from the old native job, not cancellation by itself.
  const result = await f.coordinator.start(request);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.adds.length, 2);
  assert.equal(result.selectedIndex, 0);
  assert.equal(f.state.ready.length, 1);
});

test('handoff can receive an unsent receipt during bounded retry reconciliation', async () => {
  const queued = deferred();
  let notSent;
  let retrying = false;
  const f = fixture({
    add: (state, value, options) => {
      if (state.adds.length === 1) { notSent = options.onNotSent; queued.resolve(); return new Promise(() => {}); }
      state.tracks.push({ index: 0 });
    },
    wait: () => { if (retrying) notSent(); },
  });
  const request = media([track('pt-BR')]);
  const first = f.coordinator.start(request);
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await queued.promise;
  f.coordinator.cancel();
  await cancelled;
  retrying = true;
  await f.coordinator.start(request);
  assert.equal(f.state.adds.length, 2);
  assert.equal(f.state.time, 350);
  assert.equal(f.state.ready.length, 1);
});

test('handoff does not reissue an uncertain cancelled AddSubtitle without native proof', async () => {
  const queued = deferred();
  const f = fixture({ add: () => { queued.resolve(); return new Promise(() => {}); }, coordinator: { limits: { subtitleMs: 700 } } });
  const request = media([track('pt-BR')]);
  const first = f.coordinator.start(request);
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await queued.promise;
  f.coordinator.cancel();
  await cancelled;
  await assert.rejects(f.coordinator.start(request), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.ready.length, 0);
});

test('handoff rejects ambiguous new subtitle indices instead of binding guessed metadata', async () => {
  const f = fixture({ add: state => { state.tracks.push({ index: 0 }, { index: 1 }); } });
  await assert.rejects(f.coordinator.start(media([track('pt-BR')])), { code: 'SUBTITLE_BINDING' });
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.ready.length, 0);
});

test('handoff bounds a missing subtitle registration without retrying AddSubtitle', async () => {
  const f = fixture({ add: () => {}, coordinator: { limits: { subtitleMs: 700 } } });
  await assert.rejects(f.coordinator.start(media([track('pt-BR')])), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.time, 700);
  assert.equal(f.state.ready.length, 0);
});

test('handoff waits for the selected subtitle to be observed before showing the remote', async () => {
  let selected;
  let reads = 0;
  const f = fixture({
    select: (state, index) => { selected = index; },
    getSubtitles: state => { if (selected !== undefined && ++reads >= 2) { state.enabled = true; state.currentIndex = selected; } },
  });
  await f.coordinator.start(media([track('pt-BR')]));
  assert.equal(reads, 2);
  assert.equal(f.state.ready.length, 1);
});

test('handoff startup, active-video and overall polling have separate bounded deadlines', async () => {
  const startup = fixture({ readyAfter: Infinity, coordinator: { limits: { startupMs: 600 } } });
  await assert.rejects(startup.coordinator.start(media([])), { code: 'KODI_START_TIMEOUT' });
  assert.equal(startup.state.time, 600);
  const active = fixture({ neverActive: true, coordinator: { limits: { activeMs: 700 } } });
  await assert.rejects(active.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
  assert.equal(active.state.time, 700);
  const overall = fixture({ readyAfter: Infinity, coordinator: { limits: { overallMs: 500 } } });
  await assert.rejects(overall.coordinator.start(media([])), { code: 'HANDOFF_TIMEOUT' });
  assert.equal(overall.state.time, 500);
});

test('handoff also times out an adapter promise that never settles and aborts its signal', async () => {
  let adapterSignal;
  const f = fixture({ launch: (state, options) => { adapterSignal = options.signal; return new Promise(() => {}); }, coordinator: { limits: { startupMs: 20 } } });
  await assert.rejects(f.coordinator.start(media([])), { code: 'KODI_START_TIMEOUT' });
  assert.equal(adapterSignal.aborted, true);
});

test('handoff honors caller AbortSignal and snapshots media before any async launch', async () => {
  const launched = deferred();
  const block = deferred();
  const f = fixture({ launch: async () => { launched.resolve(); await block.promise; } });
  const controller = new AbortController();
  const first = f.coordinator.start(media([]), { signal: controller.signal });
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await launched.promise;
  controller.abort();
  await cancelled;
  assert.equal(f.state.opened, 0);
  const second = fixture();
  const request = media([track('por-BR')]);
  const done = second.coordinator.start(request);
  request.url = 'https://media.example.org/changed.mp4';
  request.tracks.length = 0;
  const result = await done;
  assert.equal(result.subtitleCount, 1);
  assert.equal(result.selectedLanguage, 'pt-br');
});

test('handoff validates and snapshots declared media, caps tracks, and never surfaces adapter errors', async () => {
  const bad = fixture();
  await assert.rejects(bad.coordinator.start({ ...media([]), url: 'https://user:secret@example.org/video.mp4' }), { code: 'INVALID_MEDIA' });
  await assert.rejects(bad.coordinator.start(media(Array.from({ length: 65 }, (_, index) => track('en', `track-${index}`)))), { code: 'INVALID_MEDIA' });
  assert.equal(bad.state.calls.length, 0);
  const f = fixture({ open: () => { throw new Error(url); } });
  await assert.rejects(f.coordinator.start(media([])), error => error instanceof HandoffError && error.code === 'ADAPTER_FAILED' && !error.message.includes(url) && error.cause === undefined);
  assert.ok(!JSON.stringify(f.state.statuses).includes(url));
});
