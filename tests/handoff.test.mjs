import test from 'node:test';
import assert from 'node:assert/strict';
import { createHandoffCoordinator, HandoffError } from '../src/handoff/coordinator.mjs';

const url = 'https://media.example.org/episode.m3u8?token=private-fixture';
const track = (language, suffix = language, label = language) => ({ url: `https://captions.example.org/${suffix}.vtt`, contentType: 'text/vtt', language, label });
const media = tracks => ({ url, contentType: 'application/vnd.apple.mpegurl', title: 'Example', tracks });
const canonical = (episode = 1) => ({ key:`framextv:tv:42:1:${episode}`,kind:'tv',contentId:'42',season:1,episode,
  watchUrl:`https://framextv.cc/watch/42/1/${episode}`,title:`Example · S1 E${episode}` });
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };

function fixture(options = {}) {
  const state = {
    time: 0, calls: [], events: [], statuses: [], ready: [], adds: [], addOptions: [], preparations: [], deliveries: [], opened: 0, connections: 0,
    playback: options.playback || { active: false, sourceUrl: '' }, expectedSourceUrl: url,
    tracks: options.tracks || [], enabled: false, currentIndex: null, pending: [],
  };
  const record = (name, opts) => {
    assert.ok(opts.signal instanceof AbortSignal);
    assert.equal(opts.expectedSourceUrl, state.expectedSourceUrl);
    state.calls.push(name);
    state.events.push({ name, time: state.time });
  };
  const adapters = {
    receiver: { async launchReceiver(opts) { record('launch', opts); await options.launch?.(state, opts); } },
    player: {
      async connect(opts) { record('connect', opts); state.connections++; return { ready: state.connections >= (options.readyAfter || 1) }; },
      async open(value, opts) {
        record('open', opts); state.opened++; state.openedAt = state.time;
        assert.equal(value.url, state.expectedSourceUrl);
        state.playback = { active: !options.neverActive, sourceUrl: options.neverActive ? '' : value.url };
        await options.open?.(state, opts);
      },
      async status(opts) { record('status', opts); await options.status?.(state, opts); return { ...state.playback }; },
      async getSubtitles(opts) {
        record('getSubtitles', opts);
        const provided = await options.getSubtitles?.(state, opts);
        if (provided !== undefined) return provided;
        for (const pending of [...state.pending]) {
          if (--pending.polls <= 0) {
            state.tracks.push({ index: pending.index });
            state.pending.splice(state.pending.indexOf(pending), 1);
          }
        }
        return { tracks: state.tracks.map(item => ({ ...item })), enabled: state.enabled, currentIndex: state.currentIndex };
      },
      async addSubtitle(value, opts) {
        record('addSubtitle', opts); state.adds.push(value.url); state.addOptions.push(opts);
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
  if (options.prepare) adapters.player.prepareSubtitle = async (value, opts) => {
    record('prepareSubtitle', opts); state.preparations.push(value.url); return options.prepare(state, value, opts);
  };
  if (options.delivery) adapters.player.subtitleDelivery = async (preparedId, value, opts) => {
    record('subtitleDelivery', opts); state.deliveries.push(preparedId); return options.delivery(state, preparedId, value, opts);
  };
  return { state, adapters, coordinator: createHandoffCoordinator(adapters) };
}

async function startAndSelect(fixture, request, link = track('pt-BR').url) {
  await fixture.coordinator.start(request);
  return fixture.coordinator.selectSubtitle(request, link);
}

test('handoff uses an already answering receiver without launching it', async () => {
  const f = fixture();
  const result = await f.coordinator.start(media([track('en')]));
  assert.deepEqual(f.state.calls.slice(0, 2), ['connect', 'status']);
  assert.ok(!f.state.calls.includes('launch'));
  assert.equal(f.state.connections, 1);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
  assert.equal(f.state.statuses[0].message, 'Connecting to LUMA Receiver…');
  assert.ok(!f.state.statuses.some(item => item.step === 'launching'));
});

test('an unreachable receiver is launched once while an authentication failure stops without launching', async () => {
  let failures = 1;
  const unreachable = fixture();
  const connect = unreachable.adapters.player.connect;
  unreachable.adapters.player.connect = async opts => {
    if (failures-- > 0) { unreachable.state.calls.push('connect'); throw Object.assign(new Error('No answer'), { code: 'RECEIVER_UNAVAILABLE' }); }
    return connect(opts);
  };
  await unreachable.coordinator.start(media([]));
  assert.deepEqual(unreachable.state.calls.filter(name => ['connect', 'launch', 'open'].includes(name)), ['connect', 'launch', 'connect', 'open']);

  const unauthorized = fixture();
  unauthorized.adapters.player.connect = async () => { throw Object.assign(new Error('Unpaired'), { code: 'RECEIVER_AUTH' }); };
  await assert.rejects(unauthorized.coordinator.start(media([])), { code: 'ADAPTER_FAILED', adapterCode: 'RECEIVER_AUTH' });
  assert.ok(!unauthorized.state.calls.includes('launch'));
  assert.equal(unauthorized.state.opened, 0);
});

test('handoff launches a receiver that does not answer, waits for video and exposes every declared language without subtitle I/O', async () => {
  const f = fixture({ readyAfter: 2, tracks: [{ index: 7, language: 'en', name: 'Unknown external' }] });
  const english = track('en');
  const result = await f.coordinator.start(media([english, track('de'), track('pt-BR'), { ...english }]));
  assert.deepEqual(f.state.calls.slice(0, 3), ['connect', 'launch', 'wait']);
  assert.equal(f.state.calls.filter(name => name === 'launch').length, 1);
  assert.deepEqual(f.state.statuses.slice(0, 3).map(item => item.message),
    ['Connecting to LUMA Receiver…', 'Opening LUMA Receiver on the projector…', 'Waiting for LUMA Receiver…']);
  assert.equal(f.state.connections, 2);
  assert.equal(f.state.opened, 1);
  assert.deepEqual(f.state.adds, []);
  assert.deepEqual(result.tracks, []);
  assert.equal(result.subtitleCount, 0);
  assert.deepEqual(result.catalog.map(item => item.index), [null, null, null]);
  assert.equal(result.selectedLanguage, null);
  assert.equal(result.selectedIndex, null);
  assert.equal(f.state.currentIndex, null);
  assert.ok(!f.state.calls.some(name => ['getSubtitles', 'addSubtitle', 'setSubtitle'].includes(name)));
  assert.equal(f.state.ready.length, 1);
  assert.ok(f.state.events.find(event => event.name === 'open').time - f.state.events.find(event => event.name === 'launch').time >= 1000);
  assert.equal(f.state.statuses.at(-1).step, 'ready');
  assert.ok(!JSON.stringify(result.tracks).includes('https:'));
  assert.ok(!JSON.stringify(f.state.statuses).includes('private-fixture'));
});

test('video becomes ready without PT-BR or subtitle readiness and leaves existing receiver subtitles unchanged', async () => {
  const f = fixture({
    getSubtitles: () => new Promise(() => {}),
    add: () => { throw new Error('Startup must not add subtitles'); },
    select: () => { throw new Error('Startup must not select or disable subtitles'); },
  });
  f.state.enabled = true; f.state.currentIndex = 6;
  const result = await f.coordinator.start(media([track('pt-PT'), track('eng')]));
  assert.equal(result.selectedIndex, null);
  assert.deepEqual(result.catalog.map(item => item.language), ['pt-PT', 'eng']);
  assert.equal(f.state.currentIndex, 6);
  assert.equal(f.state.enabled, true);
  assert.equal(f.state.ready.length, 1);
});

test('canonical handoff requires the receiver to preserve the exact content identity', async () => {
  const content = canonical(1);
  const verified = fixture({ open: state => { state.playback.content = content; } });
  await verified.coordinator.start({ ...media([]), content });
  assert.equal(verified.state.ready.length, 1);

  const delayed = fixture({
    open: state => { delete state.playback.content; },
    wait: state => { if (state.time >= 350) state.playback.content = content; },
    coordinator: { limits: { activeMs: 1400 } },
  });
  await delayed.coordinator.start({ ...media([]), content });
  assert.equal(delayed.state.ready.length, 1);

  const missing = fixture({ coordinator: { limits: { activeMs: 700 } } });
  await assert.rejects(missing.coordinator.start({ ...media([]), content }), { code: 'METADATA_UNAVAILABLE' });
  assert.equal(missing.state.opened, 1);
  assert.equal(missing.state.ready.length, 0);

  const mismatched = fixture({ playback: { active: true, sourceUrl: url, content: canonical(2) },
    open: state => { state.playback.content = canonical(3); } });
  await assert.rejects(mismatched.coordinator.start({ ...media([]), content }), { code: 'SOURCE_CHANGED' });
  assert.equal(mismatched.state.opened, 1);
  assert.equal(mismatched.state.ready.length, 0);
});

test('same-source canonical episode change opens once and waits through old and missing identity windows', async () => {
  const previous = canonical(1), selected = canonical(2);
  const f = fixture({
    playback: { active: true, sourceUrl: url, content: previous },
    open: state => { state.playback = { active: true, sourceUrl: url, content: previous }; },
    wait: state => {
      if (!state.opened) return;
      const elapsed = state.time - state.openedAt;
      if (elapsed >= 700) state.playback.content = selected;
      else if (elapsed >= 350) delete state.playback.content;
    },
    coordinator: { limits: { activeMs: 2000 } },
  });
  await f.coordinator.start({ ...media([]), content: selected });
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('contentless initial status may reveal one old canonical key before the selected episode', async () => {
  const previous = canonical(1), selected = canonical(2);
  const f = fixture({
    playback: { active: true, sourceUrl: url },
    open: state => { state.playback = { active: true, sourceUrl: url, content: previous }; },
    wait: state => {
      if (!state.opened) return;
      const elapsed = state.time - state.openedAt;
      if (elapsed >= 700) state.playback.content = selected;
      else if (elapsed >= 350) delete state.playback.content;
    },
    coordinator: { limits: { activeMs: 2000 } },
  });
  await f.coordinator.start({ ...media([]), content: selected });
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('explicit subtitle selection recognizes a Brazil label and English regional language metadata', async () => {
  const f = fixture();
  const request = media([track('en_US'), track('pt', 'brazil', 'Português (Brasil)')]);
  assert.equal((await startAndSelect(f, request, request.tracks[1].url)).selectedLanguage, 'pt-br');
  assert.equal((await f.coordinator.selectSubtitle(request, request.tracks[0].url)).selectedLanguage, 'en-us');
});

test('handoff reuses a still-active source and confirmed bindings on retry without adding duplicates', async () => {
  const f = fixture();
  const request = media([track('en'), track('pt-BR')]);
  await startAndSelect(f, request);
  await f.coordinator.start(request);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.ready.length, 2);
});

test('same stream URL on a different canonical episode never reuses subtitle bindings', async () => {
  const f = fixture({ playback: { active: true, sourceUrl: url, content: canonical(1) } });
  const subtitle = track('pt-BR');
  const first = { ...media([subtitle]), content: canonical(1) };
  const second = { ...media([subtitle]), content: canonical(2) };

  assert.equal((await f.coordinator.selectSubtitle(first, subtitle.url)).selectedIndex, 0);
  f.state.playback.content = canonical(2);
  assert.equal((await f.coordinator.selectSubtitle(second, subtitle.url)).selectedIndex, 1);
  assert.deepEqual(f.state.adds, [subtitle.url, subtitle.url]);
});

test('manual selection adopts an explicitly identified track without guessing unknown external tracks', async () => {
  const english = track('en');
  const f = fixture({ playback: { active: true, sourceUrl: url }, tracks: [{ index: 4, language: 'en', name: 'Unknown external' }, { index: 9, url: english.url }] });
  const result = await startAndSelect(f, media([english, track('pt-BR')]), english.url);
  assert.equal(f.state.opened, 0);
  assert.equal(f.state.adds.length, 0);
  assert.equal(result.tracks[0].index, 9);
  assert.equal(result.catalog[1].index, null);
});

test('cold receiver accepts an early Open without playback, then a guarded retry starts the video', async () => {
  const retries = [];
  const f = fixture({
    readyAfter: 2,
    open: (state, options) => {
      retries.push(options.onlyIfInactive === true);
      if (state.opened === 1) state.playback = { active: false, sourceUrl: '' };
    },
    status: state => { if (state.opened === 1 && state.time < 1050) throw new Error('Transient fixture RPC startup'); },
    coordinator: { limits: { openRetryMs: 1000 } },
  });
  const result = await f.coordinator.start(media([track('en'), track('pt-BR')]));
  assert.deepEqual(retries, [false, true]);
  assert.equal(f.state.opened, 2);
  assert.ok(f.state.time >= 1750 && f.state.time < 30000);
  assert.equal(result.selectedLanguage, null);
  assert.equal(f.state.adds.length, 0);
  assert.equal(f.state.ready.length, 1);
});

test('cold Open waits for late selected playback and never retries over another observed source', async () => {
  const late = fixture({
    neverActive: true,
    status: state => { if (state.opened && state.time >= 700) state.playback = { active: true, sourceUrl: url }; },
    coordinator: { limits: { openRetryMs: 1000 } },
  });
  await late.coordinator.start(media([]));
  assert.equal(late.state.opened, 1);
  const changed = fixture({
    neverActive: true,
    status: state => { if (state.opened && state.time >= 350) state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' }; },
    coordinator: { limits: { openRetryMs: 500 } },
  });
  await assert.rejects(changed.coordinator.start(media([])), { code: 'SOURCE_CHANGED' });
  assert.equal(changed.state.opened, 1);
  assert.equal(changed.state.ready.length, 0);
});

test('a native retry guard race adopts only the selected source and does not retry a rejected mutation', async () => {
  for (const selectedStarted of [true, false]) {
    const f = fixture({
      neverActive: true,
      open: (state, options) => {
        if (options.onlyIfInactive) {
          state.playback = { active: true, sourceUrl: selectedStarted ? url : 'https://media.example.org/other.m3u8' };
          throw new Error('Native pre-write guard rejected active playback');
        }
      },
      coordinator: { limits: { openRetryMs: 500 } },
    });
    if (selectedStarted) await f.coordinator.start(media([]));
    else await assert.rejects(f.coordinator.start(media([])), { code: 'SOURCE_CHANGED' });
    assert.equal(f.state.opened, 2);
    assert.equal(f.state.ready.length, selectedStarted ? 1 : 0);
  }
});

test('cancelling an unresolved Open cannot overlap it with a new native Open on retry', async () => {
  const started = deferred(), opened = deferred();
  let retrying = false;
  const f = fixture({
    neverActive: true,
    open: async () => { started.resolve(); await opened.promise; },
    wait: state => {
      if (retrying && state.time >= 700) { state.playback = { active: true, sourceUrl: url }; opened.resolve(); }
    },
  });
  const first = f.coordinator.start(media([]));
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise; f.coordinator.cancel(); await cancelled;
  retrying = true;
  await f.coordinator.start(media([]));
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('an unresolved replacement Open is not overlapped while the previous source remains active', async () => {
  const started = deferred(), opened = deferred();
  const previous = { active: true, sourceUrl: 'https://media.example.org/previous.m3u8' };
  let retrying = false;
  const f = fixture({
    playback: previous,
    open: async state => { state.playback = { ...previous }; started.resolve(); await opened.promise; },
    wait: state => {
      if (retrying && state.time >= 700) { state.playback = { active: true, sourceUrl: url }; opened.resolve(); }
    },
  });
  const first = f.coordinator.start(media([]));
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise; f.coordinator.cancel(); await cancelled;
  retrying = true;
  await f.coordinator.start(media([]));
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('an unresolved Open cannot overlap a same-URL cast for a different canonical episode', async () => {
  const started = deferred(), firstOpen = deferred();
  let firstOptions;
  let retrying = false;
  let settled = false;
  let retryStartedAt = 0;
  const f = fixture({
    neverActive: true,
    open: async (state, options) => {
      if (state.opened === 1) { firstOptions = options; started.resolve(); await firstOpen.promise; }
      else state.playback = { active: true, sourceUrl: url, content: canonical(2) };
    },
    wait: state => {
      if (!retrying) return;
      const elapsed = state.time - retryStartedAt;
      assert.equal(state.ready.length, 0, 'exact B playback reported ready while unresolved A could still land');
      if (elapsed >= 350) state.playback = { active: true, sourceUrl: url, content: canonical(1) };
      if (elapsed >= 700 && !settled) {
        assert.equal(state.opened, 1, 'second canonical Open overlapped an unresolved first Open');
        settled = true; firstOptions.onSettled(); firstOpen.resolve();
      }
    },
    coordinator: { limits: { activeMs: 2400 } },
  });
  const first = f.coordinator.start({ ...media([]), content: canonical(1) });
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise; f.coordinator.cancel(); await cancelled;
  retrying = true;
  retryStartedAt = f.state.time;
  f.state.playback = { active: true, sourceUrl: url, content: canonical(2) };
  await f.coordinator.start({ ...media([]), content: canonical(2) });
  assert.equal(f.state.opened, 2);
  assert.equal(f.state.ready.length, 1);
});

test('an accepted Open stays exclusive until its canonical playback is observed', async () => {
  const accepted = deferred();
  let firstOptions;
  let retrying = false;
  let retryStatusReads = 0;
  const f = fixture({
    neverActive: true,
    open: async (state, options) => {
      if (state.opened === 1) {
        firstOptions = options;
        options.onSettled();
        accepted.resolve();
      } else {
        state.playback = { active: true, sourceUrl: url, content: canonical(2) };
      }
    },
    status: state => {
      if (!retrying) return;
      retryStatusReads++;
      if (retryStatusReads === 2) {
        assert.equal(state.opened, 1, 'accepted A overlapped B before A playback was observed');
        state.playback = { active: true, sourceUrl: url, content: canonical(1) };
      }
    },
    coordinator: { limits: { activeMs: 2400 } },
  });
  const first = f.coordinator.start({ ...media([]), content: canonical(1) });
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await accepted.promise;
  f.coordinator.cancel();
  await cancelled;
  retrying = true;
  f.state.playback = { active: true, sourceUrl: url, content: canonical(2) };
  await f.coordinator.start({ ...media([]), content: canonical(2) });
  assert.equal(typeof firstOptions.onSettled, 'function');
  assert.equal(f.state.opened, 2);
  assert.equal(f.state.ready.length, 1);
});

test('a foreign accepted Open cannot use the cold retry path before its playback is observed', async () => {
  const accepted = deferred();
  const f = fixture({
    neverActive: true,
    open: (state, options) => {
      if (state.opened === 1) { options.onSettled(); accepted.resolve(); }
      else state.playback = { active: true, sourceUrl: url, content: canonical(2) };
    },
    coordinator: { limits: { activeMs: 1400, openRetryMs: 500 } },
  });
  const first = f.coordinator.start({ ...media([]), content: canonical(1) });
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await accepted.promise;
  f.coordinator.cancel();
  await cancelled;
  await assert.rejects(f.coordinator.start({ ...media([]), content: canonical(2) }), { code: 'VIDEO_TIMEOUT' });
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 0);
});

test('a foreign Open proven unsent can be replaced while the prior source stays active', async () => {
  const started = deferred();
  let notSent;
  let retrying = false;
  let retryReads = 0;
  const previous = 'https://media.example.org/previous.m3u8';
  const f = fixture({
    playback: { active: true, sourceUrl: previous },
    open: (state, options) => {
      if (state.opened === 1) { notSent = options.onNotSent; started.resolve(); return new Promise(() => {}); }
      state.playback = { active: true, sourceUrl: url, content: canonical(2) };
    },
    status: () => {
      if (retrying && ++retryReads === 2) notSent();
    },
    coordinator: { limits: { activeMs: 2400 } },
  });
  const first = f.coordinator.start({ ...media([]), content: canonical(1) });
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise;
  f.coordinator.cancel();
  await cancelled;
  retrying = true;
  await f.coordinator.start({ ...media([]), content: canonical(2) });
  assert.equal(f.state.opened, 2);
  assert.equal(f.state.ready.length, 1);
});

test('canonical playback does not falsely observe a foreign legacy same-URL Open', async () => {
  const accepted = deferred();
  let retrying = false;
  let retryReads = 0;
  const f = fixture({
    neverActive: true,
    open: (state, options) => {
      if (state.opened === 1) { options.onSettled(); accepted.resolve(); }
      else state.playback = { active: true, sourceUrl: url, content: canonical(2) };
    },
    status: state => {
      if (!retrying) return;
      retryReads++;
      if (retryReads === 2) {
        assert.equal(state.ready.length, 0, 'canonical B falsely proved legacy A was observed');
        state.playback = { active: true, sourceUrl: url };
      }
    },
    coordinator: { limits: { activeMs: 2400 } },
  });
  const first = f.coordinator.start(media([]));
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await accepted.promise;
  f.coordinator.cancel();
  await cancelled;
  retrying = true;
  f.state.playback = { active: true, sourceUrl: url, content: canonical(2) };
  await f.coordinator.start({ ...media([]), content: canonical(2) });
  assert.equal(f.state.opened, 2);
  assert.equal(f.state.ready.length, 1);
});

test('an unresolved foreign Open cannot adopt independently selected legacy playback on another URL', async () => {
  const started = deferred();
  let notSent;
  let retrying = false;
  let retryReads = 0;
  const replacementUrl = 'https://media.example.org/replacement.m3u8';
  const f = fixture({
    neverActive: true,
    open: (state, options) => {
      if (state.opened === 1) { notSent = options.onNotSent; started.resolve(); return new Promise(() => {}); }
    },
    status: state => {
      if (retrying) {
        retryReads++;
        if (retryReads === 2) assert.equal(state.ready.length, 0, 'foreign Open was ignored before explicit not-sent proof');
        if (retryReads === 3) notSent();
      }
    },
    coordinator: { limits: { activeMs: 1400 } },
  });
  const first = f.coordinator.start(media([]));
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise;
  f.coordinator.cancel();
  await cancelled;
  retrying = true;
  f.state.expectedSourceUrl = replacementUrl;
  f.state.playback = { active: true, sourceUrl: replacementUrl };
  await f.coordinator.start({ ...media([]), url: replacementUrl });
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('a replacement Open tolerates one delayed poll of the observed foreign canonical item', async () => {
  const accepted = deferred();
  let retrying = false;
  let retryReads = 0;
  let secondSent = false;
  let afterSecondReads = 0;
  const f = fixture({
    neverActive: true,
    open: (state, options) => {
      if (state.opened === 1) { options.onSettled(); accepted.resolve(); }
      else secondSent = true;
    },
    status: state => {
      if (!retrying) return;
      retryReads++;
      if (retryReads === 2) state.playback = { active: true, sourceUrl: url, content: canonical(1) };
      if (secondSent && ++afterSecondReads === 2) state.playback = { active: true, sourceUrl: url, content: canonical(2) };
    },
    coordinator: { limits: { activeMs: 2800 } },
  });
  const first = f.coordinator.start({ ...media([]), content: canonical(1) });
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await accepted.promise;
  f.coordinator.cancel();
  await cancelled;
  retrying = true;
  f.state.playback = { active: true, sourceUrl: url, content: canonical(2) };
  await f.coordinator.start({ ...media([]), content: canonical(2) });
  assert.equal(f.state.opened, 2);
  assert.equal(afterSecondReads, 2);
  assert.equal(f.state.ready.length, 1);
});

test('an aborted bridge rejection does not prove an outstanding native Open failed', async () => {
  const started = deferred();
  let retrying = false;
  const f = fixture({
    neverActive: true,
    open: (state, options) => new Promise((resolve, reject) => {
      started.resolve();
      options.signal.addEventListener('abort', () => reject(new Error('JS bridge aborted before native receipt')), { once: true });
    }),
    wait: state => { if (retrying && state.time >= 700) state.playback = { active: true, sourceUrl: url }; },
  });
  const first = f.coordinator.start(media([]));
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise; f.coordinator.cancel(); await cancelled;
  retrying = true;
  await f.coordinator.start(media([]));
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('a bridge timeout without an aborted signal stays uncertain and retry observation is bounded', async () => {
  for (const latePlayback of [true, false]) {
    let retrying = false;
    const f = fixture({
      neverActive: true,
      open: (state, options) => {
        assert.equal(options.signal.aborted, false);
        throw Object.assign(new Error('Bridge timed out without a native receipt'), { code: 'TIMEOUT' });
      },
      wait: state => { if (retrying && latePlayback && state.time >= 350) state.playback = { active: true, sourceUrl: url }; },
      coordinator: { limits: { activeMs: 700 } },
    });
    await assert.rejects(f.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
    retrying = true;
    if (latePlayback) await f.coordinator.start(media([]));
    else await assert.rejects(f.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
    assert.equal(f.state.opened, 1);
    assert.ok(f.state.time <= 4000);
  }
});

test('selected playback finishes the same handoff while Open is unresolved, without a later abort of the cast job', async () => {
  let openSignal;
  const f = fixture({
    open: (state, options) => { openSignal = options.signal; return new Promise(() => {}); },
    coordinator: { limits: { activeMs: 20 } },
  });
  const result = await f.coordinator.start(media([track('pt-BR'), track('en')]));
  assert.equal(result.selectedLanguage, null);
  assert.equal(f.state.opened, 1);
  assert.deepEqual(f.state.adds, []);
  assert.equal(f.state.ready.length, 1);
  await new Promise(resolve => setTimeout(resolve, 35));
  assert.equal(openSignal.aborted, false);
});

test('an unknown Open response reconciles late selected playback on the first attempt without subtitle I/O', async () => {
  const f = fixture({
    neverActive: true,
    open: () => { throw Object.assign(new Error('Private unavailable Open receipt'), { code: 'TIMEOUT' }); },
    wait: state => { if (state.opened && state.time - state.openedAt >= 700) state.playback = { active: true, sourceUrl: url }; },
    coordinator: { limits: { activeMs: 2000, openRetryMs: 500 } },
  });
  const result = await f.coordinator.start(media([track('pt-BR'), track('en')]));
  assert.equal(result.selectedLanguage, null);
  assert.equal(f.state.opened, 1);
  assert.deepEqual(f.state.adds, []);
  assert.equal(f.state.ready.length, 1);
});

test('same-source recast after observed playback ends requires an actual native completion receipt for an unknown Open', async () => {
  for (const nativeCompleted of [true, false]) {
    const f = fixture({
      open: (state, options) => {
        if (nativeCompleted) options.onSettled?.();
        if (state.opened === 1) throw Object.assign(new Error('Unknown Open outcome'), { code: 'TIMEOUT' });
      },
      coordinator: { limits: { activeMs: 700 } },
    });
    await f.coordinator.start(media([]));
    f.state.playback = { active: false, sourceUrl: '' };
    if (nativeCompleted) await f.coordinator.start(media([]));
    else await assert.rejects(f.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
    assert.equal(f.state.opened, nativeCompleted ? 2 : 1);
  }
});

test('a late native completion receipt releases only its observed attempt after a pending same-source recast was protected', async () => {
  const completed = deferred();
  let receipt;
  const f = fixture({
    open: (state, options) => {
      if (state.opened === 1) { receipt = options.onSettled; return completed.promise; }
      return new Promise(() => {});
    },
    coordinator: { limits: { activeMs: 700 } },
  });
  await f.coordinator.start(media([]));
  f.state.playback = { active: false, sourceUrl: '' };
  await assert.rejects(f.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
  assert.equal(f.state.opened, 1);
  assert.equal(typeof receipt, 'function');
  receipt(); completed.resolve();
  await f.coordinator.start(media([]));
  receipt(); // A duplicate receipt cannot clear the newer Open attempt.
  f.state.playback = { active: false, sourceUrl: '' };
  await assert.rejects(f.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
  assert.equal(f.state.opened, 2);
});

test('an explicit same-source recast resumes within that run when the previous observed Open receives late native completion', async () => {
  let receipt, retrying = false;
  const f = fixture({
    open: (state, options) => {
      if (state.opened === 1) {
        receipt = options.onSettled;
        throw Object.assign(new Error('Bridge response timeout'), { code: 'TIMEOUT' });
      }
      assert.equal(options.onlyIfInactive, true);
    },
    wait: state => { if (retrying && state.time >= 350) receipt(); },
    // No launch settle precedes the recast, so the retry needs the full
    // one-second mutation spacing plus a poll inside this window.
    coordinator: { limits: { activeMs: 2400 } },
  });
  await f.coordinator.start(media([]));
  f.state.playback = { active: false, sourceUrl: '' };
  retrying = true;
  await f.coordinator.start(media([]));
  assert.equal(f.state.opened, 2);
  assert.equal(f.state.ready.length, 2);
  assert.ok(f.state.time < 3500);
});

test('an unknown Open never adopts or replaces independently selected playback', async () => {
  const f = fixture({
    neverActive: true,
    open: () => { throw Object.assign(new Error('Unavailable response'), { code: 'RECEIVER_RESPONSE' }); },
    wait: state => { if (state.opened) state.playback = { active: true, sourceUrl: 'https://media.example.org/independent.m3u8' }; },
  });
  await assert.rejects(f.coordinator.start(media([track('pt-BR')])), { code: 'SOURCE_CHANGED' });
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.adds.length, 0);
  assert.equal(f.state.ready.length, 0);
});

test('a verified unapplied Open rejection permits one explicit later retry', async () => {
  let applied = 0;
  const f = fixture({ open: (state, options) => {
    if (state.opened === 1) {
      state.playback = { active: false, sourceUrl: '' };
      options.onNotSent();
      throw Object.assign(new Error('Private native rejection detail'), { code: 'RECEIVER_REJECTED' });
    }
    applied++;
  } });
  const request = media([]);
  await assert.rejects(f.coordinator.start(request), { code: 'ADAPTER_FAILED', adapterCode: 'RECEIVER_REJECTED' });
  assert.equal(f.state.time, 0);
  assert.equal(f.state.opened, 1);
  assert.equal(applied, 0);
  await f.coordinator.start(request);
  await f.coordinator.start(request);
  assert.equal(f.state.opened, 2);
  assert.equal(applied, 1);
});

test('a cancelled unknown Open is reissued only after explicit native proof that it was not sent', async () => {
  for (const lateReceipt of [false, true]) {
    const queued = deferred();
    let notSent, retrying = false;
    const f = fixture({
      open: (state, options) => {
        if (state.opened === 1) {
          state.playback = { active: false, sourceUrl: '' };
          notSent = options.onNotSent; queued.resolve();
          return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => reject(new Error('Bridge abort without native receipt')), { once: true }));
        }
      },
      wait: () => { if (retrying && lateReceipt) notSent(); },
    });
    const first = f.coordinator.start(media([]));
    const cancelled = assert.rejects(first, { code: 'CANCELLED' });
    await queued.promise; f.coordinator.cancel(); await cancelled;
    if (!lateReceipt) notSent();
    retrying = true;
    await f.coordinator.start(media([]));
    assert.equal(f.state.opened, 2);
    assert.equal(f.state.ready.length, 1);
    notSent(); // A duplicate late receipt cannot invalidate the newer attempt.
    await f.coordinator.start(media([]));
    assert.equal(f.state.opened, 2);
  }
});

test('cold Open retries once only after confirmed inactivity and remains bounded', async () => {
  const f = fixture({ neverActive: true, coordinator: { limits: { activeMs: 3000, openRetryMs: 500 } } });
  await assert.rejects(f.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
  assert.equal(f.state.opened, 2);
  assert.equal(f.state.time, 3000);
  const unavailable = fixture({
    neverActive: true,
    status: state => { if (state.opened) throw new Error('Unavailable status'); },
    coordinator: { limits: { activeMs: 1000, openRetryMs: 500 } },
  });
  await assert.rejects(unavailable.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
  assert.equal(unavailable.state.opened, 1);
});

test('manual subtitles load only the chosen declared track, bind its index and reuse it', async () => {
  const f = fixture(), request = media([track('en'), track('pt-BR'), track('de')]);
  const first = await f.coordinator.start(request);
  assert.deepEqual(first.catalog.map(item => item.index), [null, null, null]);
  const next = await f.coordinator.selectSubtitle(request, track('de').url);
  assert.deepEqual(f.state.adds, [track('de').url]);
  assert.equal(next.selectedLanguage, 'de');
  assert.equal(next.selectedIndex, 0);
  assert.deepEqual(next.catalog.map(item => item.index), [null, null, 0]);
  await f.coordinator.selectSubtitle(request, track('de').url);
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('manual selection restores explicit RAM bindings without guessing unknown external tracks', async () => {
  const request = media([track('pt-BR'), track('en')]);
  const f = fixture({ playback: { active: true, sourceUrl: url }, tracks: [{ index: 3 }, { index: 4 }] });
  const restored = await f.coordinator.selectSubtitle(request, track('pt-BR').url, {
    knownCatalog: [{ ...track('pt-BR'), index: 3 }, { ...track('en'), index: null }],
  });
  assert.equal(restored.selectedIndex, 3);
  assert.equal(f.state.adds.length, 0);
  const english = await f.coordinator.selectSubtitle(request, track('en').url, { knownCatalog: restored.catalog });
  assert.equal(english.selectedIndex, 5);
  assert.equal(f.state.adds.length, 1);
  assert.ok(f.state.calls.every(name => !['launch', 'connect', 'open', 'remote'].includes(name)));
});

test('manual changes validate declared choices and preserve selection on source change or Add failure', async () => {
  const f = fixture(), request = media([track('pt-BR'), track('en')]);
  await startAndSelect(f, request);
  await assert.rejects(f.coordinator.selectSubtitle(request, 'https://captions.example.org/undeclared.vtt'), { code: 'INVALID_SUBTITLE' });
  f.state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' };
  await assert.rejects(f.coordinator.selectSubtitle(request, track('en').url), { code: 'SOURCE_CHANGED' });
  assert.equal(f.state.adds.length, 1);
  f.state.playback = { active: true, sourceUrl: url };
  f.adapters.player.addSubtitle = async () => { throw new Error('Fixture add failed'); };
  await assert.rejects(f.coordinator.selectSubtitle(request, track('en').url), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(f.state.currentIndex, 0);
  assert.equal(f.state.enabled, true);
  assert.equal(f.state.ready.length, 1);
});

test('startup and manual subtitle reads recover transient failures without repeating mutations', async () => {
  let initial = 2, captions = 2, postAdd = 2;
  const f = fixture({
    status: state => {
      if ((!state.opened && initial-- > 0) || (state.adds.length && postAdd-- > 0)) {
        state.time += 1000; throw Object.assign(new Error('Transient startup read'), { code: 'RECEIVER_UNAVAILABLE' });
      }
    },
    getSubtitles: state => { if (captions-- > 0) { state.time += 1000; throw Object.assign(new Error('Transient captions'), { code: 'RECEIVER_RESPONSE' }); } },
  });
  const result = await startAndSelect(f, media([track('pt-BR'), track('en')]));
  assert.equal(result.selectedIndex, 0);
  assert.equal(f.state.opened, 1);
  assert.deepEqual(f.state.adds, [track('pt-BR').url]);
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 1);
});

test('manual subtitle reads and registration retain bounded windows longer than three seconds', async () => {
  const budgets = [];
  const f = fixture({
    status: state => { state.time += 1200; },
    getSubtitles: (state, options) => { budgets.push(options.timeoutMs); state.time += 4500; },
    add: (state, value, options) => { budgets.push(options.timeoutMs); state.time += 4500; state.pending.push({ index: 0, polls: 2 }); },
    select: (state, index, options) => { budgets.push(options.timeoutMs); state.time += 7000; state.currentIndex = index; state.enabled = true; },
  });
  const result = await startAndSelect(f, media([track('pt-BR')]));
  assert.equal(result.selectedIndex, 0);
  assert.ok(budgets.every(value => value > 3000 && value <= 30000));
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 1);
});

test('manual subtitle readiness waits and a bounded read failure leaves the video remote already ready', async () => {
  let warming = 2;
  const f = fixture({ getSubtitles: () => warming-- > 0 ? { active: false, tracks: [] } : undefined });
  await startAndSelect(f, media([track('pt-BR')]));
  assert.equal(f.state.adds.length, 1);
  const unavailable = fixture({ getSubtitles: () => { throw new Error('Unavailable captions'); }, coordinator: { limits: { subtitleMs: 700 } } });
  const request = media([track('pt-BR')]);
  await unavailable.coordinator.start(request);
  const began = unavailable.state.time;
  await assert.rejects(unavailable.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(unavailable.state.time - began, 700);
  assert.equal(unavailable.state.adds.length, 0);
  assert.equal(unavailable.state.ready.length, 1);
});

test('manual reads stop immediately for authentication, configuration and source errors', async () => {
  for (const code of ['RECEIVER_AUTH', 'RECEIVER_CONFIG', 'SOURCE_CHANGED']) {
    const f = fixture({ getSubtitles: () => { throw Object.assign(new Error('Fixed failure'), { code }); } });
    const request = media([track('pt-BR')]);
    await f.coordinator.start(request);
    await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: code === 'SOURCE_CHANGED' ? 'SOURCE_CHANGED' : 'ADAPTER_FAILED' });
    assert.equal(f.state.calls.filter(name => name === 'getSubtitles').length, 1);
    assert.equal(f.state.adds.length, 0);
  }
});

test('an uncertain manual Add reconciles late registration without another Add', async () => {
  const f = fixture({ add: state => { state.pending.push({ index: 0, polls: 3 }); throw Object.assign(new Error('Lost Add response'), { code: 'RECEIVER_RESPONSE' }); } });
  assert.equal((await startAndSelect(f, media([track('pt-BR')]))).selectedIndex, 0);
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.opened, 1);
});

test('a rejected manual Portuguese request never automatically loads English; the user may choose it', async () => {
  const f = fixture({ add: (state, value, options) => {
    if (value.url === track('pt-BR').url) { options.onNotSent(); throw Object.assign(new Error('Rejected caption'), { code: 'RECEIVER_REJECTED' }); }
    state.tracks.push({ index: 3 });
  } });
  const request = media([track('pt-BR'), track('en'), track('de')]);
  await f.coordinator.start(request);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'ADAPTER_FAILED' });
  assert.deepEqual(f.state.adds, [track('pt-BR').url]);
  const result = await f.coordinator.selectSubtitle(request, track('en').url);
  assert.equal(result.selectedLanguage, 'en');
  assert.deepEqual(result.catalog.map(item => item.index), [null, 3, null]);
  assert.equal(f.state.opened, 1);
});

test('an uncertain manual Add without registration cannot overlap another language or reopen video', async () => {
  const f = fixture({ add: () => { throw Object.assign(new Error('Unknown Add outcome'), { code: 'RECEIVER_UNAVAILABLE' }); }, coordinator: { limits: { subtitleMs: 1500 } } });
  const request = media([track('pt-BR'), track('en')]);
  await f.coordinator.start(request);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_TIMEOUT' });
  await assert.rejects(f.coordinator.selectSubtitle(request, track('en').url), { code: 'SUBTITLE_TIMEOUT' });
  assert.deepEqual(f.state.adds, [track('pt-BR').url]);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('a lost manual selection response reconciles the enabled track without repeating Set', async () => {
  const f = fixture({ select: (state, index) => { state.enabled = true; state.currentIndex = index; throw Object.assign(new Error('Lost Set response'), { code: 'RECEIVER_RESPONSE' }); } });
  assert.equal((await startAndSelect(f, media([track('pt-BR')]))).selectedLanguage, 'pt-br');
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 1);
});

test('a proved-unsent manual Add permits explicit retry without video replay', async () => {
  const f = fixture({ add: (state, value, options) => {
    if (state.adds.length === 1) { options.onNotSent(); throw Object.assign(new Error('Rejected Add'), { code: 'RECEIVER_REJECTED' }); }
    state.tracks.push({ index: 0 });
  } });
  const request = media([track('pt-BR')]);
  await f.coordinator.start(request);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'ADAPTER_FAILED' });
  assert.equal((await f.coordinator.selectSubtitle(request, track('pt-BR').url)).selectedIndex, 0);
  assert.equal(f.state.adds.length, 2);
  assert.equal(f.state.opened, 1);
});

test('source changes during manual Add or its pacing stop before subtitle selection', async () => {
  for (const changeDuringPace of [true, false]) {
    const f = fixture({
      add: state => {
        state.tracks.push({ index: 0 });
        if (!changeDuringPace) state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' };
      },
      wait: state => { if (changeDuringPace && state.adds.length) state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' }; },
    });
    const request = media([track('pt-BR')]);
    await f.coordinator.start(request);
    await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SOURCE_CHANGED' });
    assert.equal(f.state.adds.length, 1);
    assert.ok(!f.state.calls.includes('setSubtitle'));
    assert.equal(f.state.ready.length, 1);
  }
});

test('source navigation during launch stops before Open', async () => {
  let current = true;
  const f = fixture({ readyAfter: 2, launch: () => { current = false; } });
  await assert.rejects(f.coordinator.start(media([]), { isCurrentSource: () => current }), { code: 'SOURCE_CHANGED' });
  assert.equal(f.state.opened, 0);
});

test('cancellation and a new start suppress late launch operations', async () => {
  const launch = deferred(); let launches = 0;
  const f = fixture({ readyAfter: 2, launch: async () => { if (++launches === 1) await launch.promise; } });
  const first = f.coordinator.start(media([]));
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await Promise.resolve(); await Promise.resolve();
  const second = f.coordinator.start(media([])); launch.resolve();
  await cancelled; await second;
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('a cancelled manual Add may reconcile its late registration without another write', async () => {
  const addition = deferred(), started = deferred(); let resumed = false;
  const f = fixture({
    add: async () => { started.resolve(); await addition.promise; },
    wait: state => { if (resumed && !state.tracks.length) { state.tracks.push({ index: 0 }); addition.resolve(); } },
  });
  const request = media([track('pt-BR')]); await f.coordinator.start(request);
  const first = f.coordinator.selectSubtitle(request, track('pt-BR').url);
  const cancelled = assert.rejects(first, { code: 'CANCELLED' });
  await started.promise; f.coordinator.cancel(); await cancelled; resumed = true;
  assert.equal((await f.coordinator.selectSubtitle(request, track('pt-BR').url)).selectedIndex, 0);
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.opened, 1);
});

test('a cancelled manual Add can be retried only after explicit unsent proof', async () => {
  for (const proof of [true, false]) {
    const queued = deferred(); let notSent;
    const f = fixture({ add: (state, value, options) => {
      if (state.adds.length === 1) { notSent = options.onNotSent; queued.resolve(); return new Promise(() => {}); }
      state.tracks.push({ index: 0 });
    }, coordinator: { limits: { subtitleMs: 1500 } } });
    const request = media([track('pt-BR')]); await f.coordinator.start(request);
    const first = f.coordinator.selectSubtitle(request, track('pt-BR').url);
    const cancelled = assert.rejects(first, { code: 'CANCELLED' });
    await queued.promise; f.coordinator.cancel(); await cancelled;
    if (proof) { notSent(); await f.coordinator.selectSubtitle(request, track('pt-BR').url); }
    else await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_TIMEOUT' });
    assert.equal(f.state.adds.length, proof ? 2 : 1);
    assert.equal(f.state.opened, 1);
  }
});

test('an unresolved Add globally blocks a different canonical item until explicit unsent proof', async () => {
  const invoked = deferred(), released = deferred();
  let notSent;
  const firstTrack = track('pt-BR', 'episode-a'), secondTrack = track('pt-BR', 'episode-b');
  const first = { ...media([firstTrack]), content: canonical(1) };
  const second = { ...media([secondTrack]), content: canonical(2) };
  const f = fixture({
    playback: { active: true, sourceUrl: url, content: canonical(1) },
    add: async (state, value, options) => {
      if (state.adds.length === 1) { notSent = options.onNotSent; invoked.resolve(); await released.promise; }
      else state.tracks.push({ index: 0 });
    },
    coordinator: { limits: { subtitleMs: 1500 } },
  });
  await f.coordinator.start(first);
  const selection = f.coordinator.selectSubtitle(first, firstTrack.url);
  const cancelled = assert.rejects(selection, { code: 'CANCELLED' });
  await invoked.promise;
  f.coordinator.cancel();
  await cancelled;
  f.state.playback = { active: true, sourceUrl: url, content: canonical(2) };
  await assert.rejects(f.coordinator.selectSubtitle(second, secondTrack.url), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 0);
  notSent(); released.resolve();
  const result = await f.coordinator.selectSubtitle(second, secondTrack.url);
  assert.equal(result.selectedIndex, 0);
  assert.deepEqual(f.state.adds, [firstTrack.url, secondTrack.url]);
});

test('a settled Add without registration still blocks another canonical item', async () => {
  const firstTrack = track('pt-BR', 'accepted-a'), secondTrack = track('pt-BR', 'accepted-b');
  const first = { ...media([firstTrack]), content: canonical(1) };
  const second = { ...media([secondTrack]), content: canonical(2) };
  const f = fixture({
    playback: { active: true, sourceUrl: url, content: canonical(1) },
    add: (state, value, options) => { options.onSettled(); },
    coordinator: { limits: { subtitleMs: 700 } },
  });
  await f.coordinator.start(first);
  await assert.rejects(f.coordinator.selectSubtitle(first, firstTrack.url), { code: 'SUBTITLE_TIMEOUT' });
  f.state.playback = { active: true, sourceUrl: url, content: canonical(2) };
  await assert.rejects(f.coordinator.selectSubtitle(second, secondTrack.url), { code: 'SUBTITLE_TIMEOUT' });
  assert.deepEqual(f.state.adds, [firstTrack.url]);
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 0);
});

test('ambiguous or absent manual registrations fail without guessed bindings or duplicate Add', async () => {
  for (const ambiguous of [true, false]) {
    const f = fixture({ add: state => { if (ambiguous) state.tracks.push({ index: 0 }, { index: 1 }); }, coordinator: { limits: { subtitleMs: 1500 } } });
    const request = media([track('pt-BR')]); await f.coordinator.start(request);
    await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: ambiguous ? 'SUBTITLE_BINDING' : 'SUBTITLE_TIMEOUT' });
    assert.equal(f.state.adds.length, 1);
    assert.equal(f.state.currentIndex, null);
    assert.equal(f.state.ready.length, 1);
  }
});

test('manual selection waits for the chosen track to be observed', async () => {
  let selected, reads = 0;
  const f = fixture({ select: (state, index) => { selected = index; }, getSubtitles: state => { if (selected !== undefined && ++reads >= 2) { state.enabled = true; state.currentIndex = selected; } } });
  await startAndSelect(f, media([track('pt-BR')]));
  assert.equal(reads, 2);
  assert.equal(f.state.ready.length, 1);
});

test('launch, first connection, Open, manual Add and Set respect one-second mutation settling', async () => {
  const f = fixture({ readyAfter: 2, launch: state => { state.time += 800; }, add: state => { state.time += 200; state.tracks.push({ index: 0 }); } });
  await startAndSelect(f, media([track('pt-BR')]));
  const at = name => f.state.events.find(event => event.name === name).time;
  const launchIndex = f.state.events.findIndex(event => event.name === 'launch');
  const connectAfterLaunch = f.state.events.find((event, index) => index > launchIndex && event.name === 'connect').time;
  assert.ok(connectAfterLaunch - at('launch') >= 1800);
  assert.ok(at('open') - at('launch') >= 1800);
  assert.ok(at('addSubtitle') - at('open') >= 1000);
  assert.ok(at('setSubtitle') - at('addSubtitle') >= 1200);
});

test('cancelling the one-second launch settle sends neither connect nor Open', async () => {
  let f;
  f = fixture({ readyAfter: 2, wait: () => f.coordinator.cancel() });
  await assert.rejects(f.coordinator.start(media([])), { code: 'CANCELLED' });
  assert.deepEqual(f.state.calls.filter(name => ['launch', 'connect', 'open'].includes(name)), ['connect', 'launch']);
});

test('handoff startup, active-video and overall polling have separate bounded deadlines', async () => {
  const startup = fixture({ readyAfter: Infinity, coordinator: { limits: { startupMs: 600 } } });
  await assert.rejects(startup.coordinator.start(media([])), { code: 'RECEIVER_START_TIMEOUT' });
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
  const f = fixture({ readyAfter: 2, launch: (state, options) => { adapterSignal = options.signal; return new Promise(() => {}); }, coordinator: { limits: { startupMs: 20 } } });
  await assert.rejects(f.coordinator.start(media([])), { code: 'RECEIVER_START_TIMEOUT' });
  assert.equal(adapterSignal.aborted, true);
});

test('handoff honors caller AbortSignal and snapshots media before any async launch', async () => {
  const launched = deferred();
  const block = deferred();
  const f = fixture({ readyAfter: 2, launch: async () => { launched.resolve(); await block.promise; } });
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
  assert.equal(result.subtitleCount, 0);
  assert.equal(result.selectedLanguage, null);
  assert.equal(result.catalog.length, 1);
  assert.equal(result.catalog[0].language, 'por-BR');
});

test('handoff validates and snapshots declared media, caps tracks, and never surfaces adapter errors', async () => {
  const bad = fixture();
  await assert.rejects(bad.coordinator.start({ ...media([]), url: 'https://user:secret@example.org/video.mp4' }), { code: 'INVALID_MEDIA' });
  await assert.rejects(bad.coordinator.start(media(Array.from({ length: 65 }, (_, index) => track('en', `track-${index}`)))), { code: 'INVALID_MEDIA' });
  assert.equal(bad.state.calls.length, 0);
  const f = fixture({ neverActive: true, open: () => { throw new Error(url); }, coordinator: { limits: { activeMs: 700 } } });
  await assert.rejects(f.coordinator.start(media([])), error => error instanceof HandoffError && error.code === 'VIDEO_TIMEOUT' && !error.message.includes(url) && error.cause === undefined);
  assert.ok(!JSON.stringify(f.state.statuses).includes(url));
});

const preparedId = suffix => `prepared_ticket_${suffix}`;
const preparedPlayback = { active: true, sourceUrl: url };

test('subtitle preparation failures preserve playback and do not create an uncertain Add or reopen video', async () => {
  for (const code of ['SUBTITLE_DOWNLOAD', 'SUBTITLE_FORMAT', 'SUBTITLE_INVALID', 'SUBTITLE_TOO_LARGE', 'SUBTITLE_DELIVERY', 'SUBTITLE_EXPIRED']) {
    let rejectPreparation = true;
    const f = fixture({ playback: preparedPlayback,
      prepare: () => { if (rejectPreparation) throw Object.assign(new Error('Private provider failure: ' + url), { code }); return { preparedId: preparedId(code) }; },
      delivery: () => ({ downloaded: true }),
    });
    const request = media([track('pt-BR'), track('en')]);
    await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), error => error.code === code && error.adapterCode === code && !error.message.includes(url));
    assert.equal(f.state.adds.length, 0); assert.equal(f.state.opened, 0); assert.equal(f.state.playback.active, true);
    assert.equal(request.tracks.length, 2); assert.ok(!f.state.calls.includes('setSubtitle'));
    rejectPreparation = false;
    const selected = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
    assert.equal(selected.selectedIndex, 0); assert.equal(selected.catalog.length, 2); assert.equal(f.state.adds.length, 1);
  }
});

test('preparation accepts only opaque bounded tickets and requires a delivery observer before Add', async () => {
  for (const value of [null, {}, { preparedId: 'short' }, { preparedId: 'https://private.example/subtitle.srt' }, { preparedId: 'a'.repeat(129) }, { preparedId: 'a'.repeat(15) }]) {
    const f = fixture({ playback: preparedPlayback, prepare: () => value, delivery: () => ({ downloaded: true }) });
    await assert.rejects(f.coordinator.selectSubtitle(media([track('pt-BR')]), track('pt-BR').url), { code: 'SUBTITLE_INVALID' });
    assert.equal(f.state.adds.length, 0); assert.equal(f.state.opened, 0);
  }
  for (const length of [16, 128]) {
    const f = fixture({ playback: preparedPlayback, prepare: () => ({ preparedId: 'a'.repeat(length) }), delivery: () => ({ downloaded: true }) });
    assert.equal((await f.coordinator.selectSubtitle(media([track('pt-BR')]), track('pt-BR').url)).catalog[0].preparedId, 'a'.repeat(length));
  }
  const missing = fixture({ playback: preparedPlayback, prepare: () => ({ preparedId: preparedId('missing') }) });
  await assert.rejects(missing.coordinator.selectSubtitle(media([track('pt-BR')]), track('pt-BR').url), { code: 'SUBTITLE_DELIVERY' });
  assert.equal(missing.state.preparations.length, 0); assert.equal(missing.state.adds.length, 0);
});

test('cancelled or changed-source preparation cannot send Add, and an explicit retry can prepare again', async () => {
  for (const capturedPageChanged of [false, true]) {
    let current = true, change = true;
    const f = fixture({ playback: preparedPlayback,
      prepare: state => { if (change) { if (capturedPageChanged) current = false; else state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' }; } return { preparedId: preparedId('source') }; },
      delivery: () => ({ downloaded: true }),
    });
    const request = media([track('pt-BR')]);
    await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url, { isCurrentSource: () => current }), { code: 'SOURCE_CHANGED' });
    assert.equal(f.state.adds.length, 0); assert.equal(f.state.opened, 0);
    change = false; current = true; f.state.playback = preparedPlayback;
    await f.coordinator.selectSubtitle(request, track('pt-BR').url);
    assert.equal(f.state.preparations.length, 2); assert.equal(f.state.adds.length, 1);
  }
  const started = deferred(), download = deferred(); let signal;
  const f = fixture({ playback: preparedPlayback,
    prepare: async (state, value, options) => { if (state.preparations.length === 1) { signal = options.signal; started.resolve(); await download.promise; } return { preparedId: preparedId('cancel') }; },
    delivery: () => ({ downloaded: true }),
  });
  const request = media([track('pt-BR')]), selecting = f.coordinator.selectSubtitle(request, track('pt-BR').url);
  const rejected = assert.rejects(selecting, { code: 'CANCELLED' }); await started.promise; f.coordinator.cancel(); await rejected;
  assert.equal(signal.aborted, true); assert.equal(f.state.adds.length, 0); download.resolve();
  await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.equal(f.state.preparations.length, 2); assert.equal(f.state.adds.length, 1); assert.equal(f.state.opened, 0);
});

test('preparation has an eight-second cap and aborts a stalled read without poisoning Add retry', async () => {
  let adapterSignal, first = true;
  const f = fixture({ playback: preparedPlayback, coordinator: { limits: { prepareMs: 20 } },
    prepare: (state, value, options) => { assert.ok(options.timeoutMs <= 8000); if (!first) return { preparedId: preparedId('retry') }; adapterSignal = options.signal; return new Promise(() => {}); },
    delivery: () => ({ downloaded: true }),
  });
  const request = media([track('pt-BR')]);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_DOWNLOAD' });
  assert.equal(adapterSignal.aborted, true); assert.equal(f.state.adds.length, 0); first = false;
  await f.coordinator.selectSubtitle(request, track('pt-BR').url); assert.equal(f.state.adds.length, 1);
});

test('read-only preparation preserves mutation pacing and passes its ticket without changing the original URL', async () => {
  const f = fixture({ prepare: state => { state.time += 250; return { preparedId: preparedId('paced') }; }, delivery: () => ({ downloaded: true }) });
  const request = media([track('pt-BR')]); await startAndSelect(f, request);
  const event = name => f.state.events.find(item => item.name === name);
  assert.equal(event('prepareSubtitle').time - event('open').time, 0);
  assert.equal(event('addSubtitle').time - event('open').time, 1000);
  assert.equal(event('setSubtitle').time - event('addSubtitle').time, 1000);
  assert.equal(f.state.addOptions[0].preparedId, preparedId('paced')); assert.deepEqual(f.state.adds, [track('pt-BR').url]);
  assert.ok(f.state.events.slice(f.state.events.indexOf(event('prepareSubtitle')) + 1, f.state.events.indexOf(event('addSubtitle'))).some(item => item.name === 'status'));
});

test('prepared subtitles become ready only after selection and full response delivery, retaining the ticket for reuse', async () => {
  const f = fixture({ playback: preparedPlayback, prepare: () => ({ preparedId: preparedId('delivered') }),
    delivery: (state, id, value, options) => {
      assert.equal(state.enabled, true); assert.equal(state.currentIndex, 0); assert.equal(value.url, track('pt-BR').url);
      assert.equal(id, preparedId('delivered')); assert.ok(options.timeoutMs <= 10000);
      assert.ok(!state.statuses.some(item => item.step === 'ready'));
      return { downloaded: state.deliveries.length >= 3 };
    },
  });
  const request = media([track('pt-BR'), track('en')]), result = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.equal(f.state.deliveries.length, 3); assert.equal(result.catalog[0].url, track('pt-BR').url); assert.equal(result.catalog[0].preparedId, preparedId('delivered'));
  assert.equal(result.selectedLanguage, 'pt-br');
  f.adapters.player.subtitleDelivery = async () => ({ downloaded: true });
  await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.equal(f.state.preparations.length, 1); assert.equal(f.state.adds.length, 1); assert.equal(f.state.opened, 0);
});

test('delivery timeout preserves the playing video and binding; retry observes delivery without another Add', async () => {
  let downloaded = false;
  const f = fixture({ playback: preparedPlayback, coordinator: { limits: { deliveryMs: 700 } },
    prepare: () => ({ preparedId: preparedId('timeout') }), delivery: () => ({ downloaded }),
  });
  const request = media([track('pt-BR')]);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_DELIVERY' });
  assert.equal(f.state.adds.length, 1); assert.equal(f.state.opened, 0); assert.equal(f.state.playback.active, true);
  assert.equal(f.state.currentIndex, 0); assert.equal(f.state.enabled, true); assert.equal(f.state.statuses.at(-1).step, 'error');
  downloaded = true; const result = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.equal(result.selectedIndex, 0); assert.equal(f.state.adds.length, 1); assert.equal(f.state.preparations.length, 1);
});

test('a lost prepared Add response still reconciles one registration and waits for delivery', async () => {
  const f = fixture({ playback: preparedPlayback, prepare: () => ({ preparedId: preparedId('lost') }), delivery: () => ({ downloaded: true }),
    add: state => { state.pending.push({ index: 0, polls: 3 }); throw Object.assign(new Error('Lost acknowledgement'), { code: 'RECEIVER_RESPONSE' }); },
  });
  assert.equal((await f.coordinator.selectSubtitle(media([track('pt-BR')]), track('pt-BR').url)).selectedIndex, 0);
  assert.equal(f.state.adds.length, 1); assert.equal(f.state.preparations.length, 1); assert.equal(f.state.deliveries.length, 1); assert.equal(f.state.opened, 0);
});

test('stale receiver bindings and an actual video reopen discard prepared tickets', async () => {
  const f = fixture({ playback: preparedPlayback, prepare: state => ({ preparedId: preparedId(`generation_${state.preparations.length}`) }), delivery: () => ({ downloaded: true }) });
  const request = media([track('pt-BR')]);
  const first = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  f.state.tracks = []; f.state.currentIndex = null; f.state.enabled = false;
  const second = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.notEqual(first.catalog[0].preparedId, second.catalog[0].preparedId); assert.equal(f.state.preparations.length, 2);
  f.state.playback = { active: false, sourceUrl: '' }; f.state.tracks = [];
  const reopened = await f.coordinator.start(request); assert.equal(reopened.catalog[0].preparedId, undefined);
  const third = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.notEqual(second.catalog[0].preparedId, third.catalog[0].preparedId); assert.equal(f.state.preparations.length, 3); assert.equal(f.state.adds.length, 3);
});

test('restored catalogs retain only valid source-bound prepared tickets and verify delivery without Add', async () => {
  const f = fixture({ playback: preparedPlayback, tracks: [{ index: 5 }], prepare: () => { throw new Error('Already prepared'); }, delivery: () => ({ downloaded: true }) });
  const request = media([track('pt-BR'), track('en')]);
  const result = await f.coordinator.selectSubtitle(request, track('pt-BR').url, { knownCatalog: [{ ...track('pt-BR'), index: 5, preparedId: preparedId('restored') }] });
  assert.equal(result.selectedIndex, 5); assert.equal(f.state.preparations.length, 0); assert.equal(f.state.adds.length, 0); assert.deepEqual(f.state.deliveries, [preparedId('restored'), preparedId('restored')]);
  assert.ok(f.state.calls.indexOf('subtitleDelivery') < f.state.calls.indexOf('setSubtitle'));
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url, { knownCatalog: [{ ...track('pt-BR'), index: 5, preparedId: 'invalid' }] }), { code: 'INVALID_SUBTITLE' });
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url, { knownCatalog: [
    { ...track('pt-BR'), index: 5, preparedId: preparedId('shared') }, { ...track('en'), index: null, preparedId: preparedId('shared') },
  ] }), { code: 'INVALID_SUBTITLE' });
});

test('a different selected media source discards earlier generic index bindings and prepared tickets', async () => {
  const f = fixture({ playback: preparedPlayback, prepare: state => ({ preparedId: preparedId(`source_${state.preparations.length}`) }), delivery: () => ({ downloaded: true }) });
  const request = media([track('pt-BR')]), first = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  const otherUrl = 'https://media.example.org/another-episode.m3u8';
  f.state.expectedSourceUrl = otherUrl; f.state.playback = { active: true, sourceUrl: otherUrl };
  await f.coordinator.start({ ...request, url: otherUrl });
  f.state.expectedSourceUrl = url; f.state.playback = preparedPlayback;
  const restored = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.equal(f.state.preparations.length, 2); assert.equal(f.state.adds.length, 2);
  assert.notEqual(first.catalog[0].preparedId, restored.catalog[0].preparedId);
  assert.equal(first.selectedIndex, 0); assert.equal(restored.selectedIndex, 1);
});

test('source changes or cancellation while delivery is pending never replay Add or claim ready', async () => {
  for (const cancelled of [false, true]) {
    let f;
    f = fixture({ playback: preparedPlayback, prepare: () => ({ preparedId: preparedId('pending') }), delivery: () => ({ downloaded: false }),
      wait: state => {
        if (!state.deliveries.length) return;
        if (cancelled) f.coordinator.cancel();
        else state.playback = { active: true, sourceUrl: 'https://media.example.org/changed.m3u8' };
      },
    });
    await assert.rejects(f.coordinator.selectSubtitle(media([track('pt-BR')]), track('pt-BR').url), { code: cancelled ? 'CANCELLED' : 'SOURCE_CHANGED' });
    assert.equal(f.state.adds.length, 1); assert.equal(f.state.opened, 0); assert.equal(f.state.deliveries.length, 1);
    assert.ok(!f.state.statuses.some(item => item.step === 'ready'));
  }
});

test('preparation and delivery caps stay bounded and malformed delivery is never accepted', async () => {
  const f = fixture({ playback: preparedPlayback, coordinator: { limits: { prepareMs: Infinity, deliveryMs: 999999 } },
    prepare: (state, value, options) => { assert.equal(options.timeoutMs, 8000); return { preparedId: preparedId('budgets') }; },
    delivery: (state, id, value, options) => { assert.equal(options.timeoutMs, 10000); return { downloaded: 'true' }; },
  });
  await assert.rejects(f.coordinator.selectSubtitle(media([track('pt-BR')]), track('pt-BR').url), { code: 'SUBTITLE_DELIVERY' });
  assert.equal(f.state.adds.length, 1); assert.equal(f.state.deliveries.length, 1); assert.equal(f.state.playback.active, true);
});

test('an unresolved prepared Add blocks another language before any new preparation or mutation', async () => {
  const f = fixture({ playback: preparedPlayback, prepare: () => ({ preparedId: preparedId('unknown') }), delivery: () => ({ downloaded: false }),
    add: () => { throw Object.assign(new Error('No write receipt'), { code: 'RECEIVER_UNAVAILABLE' }); }, coordinator: { limits: { subtitleMs: 700 } },
  });
  const request = media([track('pt-BR'), track('en')]);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_TIMEOUT' });
  await assert.rejects(f.coordinator.selectSubtitle(request, track('en').url), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(f.state.preparations.length, 1); assert.equal(f.state.adds.length, 1); assert.equal(f.state.deliveries.length, 0); assert.equal(f.state.opened, 0);
});

test('source changes do not turn a pending prepared Add into a delivery-free success on retry', async () => {
  let delivered = false;
  const f = fixture({ playback: preparedPlayback, prepare: () => ({ preparedId: preparedId('pending_receipt') }),
    delivery: () => ({ downloaded: delivered }), coordinator: { limits: { deliveryMs: 700 } },
    add: state => { state.pending.push({ index: 0, polls: 2 }); state.playback = { active: true, sourceUrl: 'https://media.example.org/changed.m3u8' }; },
  });
  const request = media([track('pt-BR')]);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SOURCE_CHANGED' });
  f.state.playback = preparedPlayback;
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_DELIVERY' });
  assert.equal(f.state.adds.length, 1); assert.equal(f.state.preparations.length, 1); assert.ok(f.state.deliveries.every(id => id === preparedId('pending_receipt')));
  delivered = true; await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.equal(f.state.adds.length, 1); assert.equal(f.state.opened, 0);
});

test('a proven expired bound ticket is refreshed by one Add before Set and stale catalogs cannot restore it', async () => {
  let expired = false; const selected = [];
  const f = fixture({ playback: preparedPlayback,
    prepare: state => ({ preparedId: preparedId(`lease_${state.preparations.length}`) }),
    delivery: (state, id) => { if (expired && id === preparedId('lease_1')) throw Object.assign(new Error('Private expired lease'), { code: 'SUBTITLE_EXPIRED' }); return { downloaded: true }; },
    select: (state, index) => { selected.push(index); state.currentIndex = index; state.enabled = true; },
  });
  const request = media([track('pt-BR')]), first = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  expired = true;
  const next = await f.coordinator.selectSubtitle(request, track('pt-BR').url, { knownCatalog: first.catalog });
  assert.equal(next.selectedIndex, 1); assert.equal(next.catalog[0].preparedId, preparedId('lease_2'));
  assert.deepEqual(selected, [0, 1]); assert.equal(f.state.adds.length, 2); assert.equal(f.state.preparations.length, 2);
  const again = await f.coordinator.selectSubtitle(request, track('pt-BR').url, { knownCatalog: first.catalog });
  assert.equal(again.selectedIndex, 1); assert.equal(again.catalog[0].preparedId, preparedId('lease_2'));
  assert.equal(f.state.adds.length, 2); assert.equal(f.state.opened, 0);
});

test('retiring an expired restored binding survives a failed download and a later explicit choice', async () => {
  let failPreparation = true;
  const stale = preparedId('restored_expired');
  const f = fixture({ playback: preparedPlayback, tracks: [{ index: 4, url: track('pt-BR').url }],
    prepare: () => { if (failPreparation) throw Object.assign(new Error('Unavailable provider'), { code: 'SUBTITLE_DOWNLOAD' }); return { preparedId: preparedId('fresh_restored') }; },
    delivery: (state, id) => { if (id === stale) throw Object.assign(new Error('Expired'), { code: 'SUBTITLE_EXPIRED' }); return { downloaded: true }; },
  });
  const request = media([track('pt-BR')]), knownCatalog = [{ ...track('pt-BR'), index: 4, preparedId: stale }];
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url, { knownCatalog }), { code: 'SUBTITLE_DOWNLOAD' });
  assert.equal(f.state.adds.length, 0); assert.ok(!f.state.calls.includes('setSubtitle'));
  failPreparation = false;
  const result = await f.coordinator.selectSubtitle(request, track('pt-BR').url, { knownCatalog });
  assert.equal(result.selectedIndex, 5); assert.equal(result.catalog[0].preparedId, preparedId('fresh_restored'));
  assert.equal(f.state.adds.length, 1); assert.equal(f.state.preparations.length, 2); assert.equal(f.state.opened, 0);
});

test('a prepared lease that expires at prewrite resolution permits only a later explicit retry', async () => {
  let writes = 0;
  const f = fixture({ playback: preparedPlayback, prepare: state => ({ preparedId: preparedId(`resolve_${state.preparations.length}`) }), delivery: () => ({ downloaded: true }),
    add: (state, value, options) => {
      if (state.adds.length === 1) { options.onNotSent(); throw Object.assign(new Error('Expired before write'), { code: 'SUBTITLE_EXPIRED' }); }
      writes++; state.pending.push({ index: 0, polls: 2 });
    },
  });
  const request = media([track('pt-BR')]);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_EXPIRED', adapterCode: 'SUBTITLE_EXPIRED' });
  assert.equal(writes, 0); assert.equal(f.state.adds.length, 1); assert.ok(!f.state.calls.includes('setSubtitle'));
  const result = await f.coordinator.selectSubtitle(request, track('pt-BR').url);
  assert.equal(result.selectedIndex, 0); assert.equal(writes, 1); assert.equal(f.state.adds.length, 2); assert.equal(f.state.preparations.length, 2); assert.equal(f.state.opened, 0);
});

test('an expired-ticket observer cannot clear a different Add with an unknown write outcome', async () => {
  const f = fixture({ playback: preparedPlayback, coordinator: { limits: { subtitleMs: 700 } },
    prepare: () => ({ preparedId: preparedId('unknown_write') }),
    delivery: () => { throw Object.assign(new Error('Expired'), { code: 'SUBTITLE_EXPIRED' }); },
    add: () => { throw Object.assign(new Error('Unknown write'), { code: 'RECEIVER_UNAVAILABLE' }); },
  });
  const request = media([track('pt-BR'), track('en')]);
  await assert.rejects(f.coordinator.selectSubtitle(request, track('pt-BR').url), { code: 'SUBTITLE_TIMEOUT' });
  await assert.rejects(f.coordinator.selectSubtitle(request, track('en').url), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(f.state.adds.length, 1); assert.equal(f.state.preparations.length, 1); assert.equal(f.state.deliveries.length, 0); assert.equal(f.state.opened, 0);
});
