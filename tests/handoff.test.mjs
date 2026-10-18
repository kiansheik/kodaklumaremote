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

test('handoff launches, waits for Kodi and loads only preferred Portuguese while retaining the declared catalog', async () => {
  const f = fixture({ readyAfter: 2, tracks: [{ index: 7, language: 'en', name: 'Unknown external' }] });
  const english = track('en');
  const result = await f.coordinator.start(media([english, track('de'), track('pt-BR'), { ...english }]));
  assert.equal(f.state.calls[0], 'launch');
  assert.equal(f.state.connections, 2);
  assert.equal(f.state.opened, 1);
  assert.deepEqual(f.state.adds, [track('pt-BR').url]);
  assert.deepEqual(result.tracks.map(item => item.index), [8]);
  assert.equal(result.subtitleCount, 1);
  assert.deepEqual(result.catalog.map(item => item.index), [null, null, 8]);
  assert.equal(result.selectedLanguage, 'pt-br');
  assert.equal(result.selectedIndex, 8);
  assert.equal(f.state.currentIndex, 8);
  assert.equal(f.state.enabled, true);
  assert.equal(f.state.ready.length, 1);
  assert.ok(f.state.calls.indexOf('open') < f.state.calls.indexOf('addSubtitle'));
  assert.equal(f.state.statuses.at(-1).step, 'ready');
  assert.ok(!JSON.stringify(result.tracks).includes('https:'));
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
  assert.equal(f.state.adds.length, 1);
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

test('cold Kodi accepts an early Open without playback, then a guarded retry starts the video', async () => {
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
  assert.equal(result.selectedLanguage, 'pt-br');
  assert.equal(f.state.adds.length, 1);
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
    await assert.rejects(f.coordinator.start(media([])), { code: 'ADAPTER_FAILED' });
    retrying = true;
    if (latePlayback) await f.coordinator.start(media([]));
    else await assert.rejects(f.coordinator.start(media([])), { code: 'VIDEO_TIMEOUT' });
    assert.equal(f.state.opened, 1);
    assert.ok(f.state.time <= 700);
  }
});

test('a verified unapplied Open rejection permits one explicit later retry', async () => {
  let applied = 0;
  const f = fixture({ open: (state, options) => {
    if (state.opened === 1) {
      state.playback = { active: false, sourceUrl: '' };
      options.onNotSent();
      throw Object.assign(new Error('Private native rejection detail'), { code: 'KODI_REJECTED' });
    }
    applied++;
  } });
  const request = media([]);
  await assert.rejects(f.coordinator.start(request), { code: 'ADAPTER_FAILED', adapterCode: 'KODI_REJECTED' });
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

test('lazy subtitles load only the chosen declared track, bind the observed index and reuse it', async () => {
  const f = fixture();
  const request = media([track('en'), track('pt-BR'), track('de')]);
  const first = await f.coordinator.start(request);
  assert.deepEqual(first.catalog.map(item => item.index), [null, 0, null]);
  const next = await f.coordinator.selectSubtitle(request, track('de').url);
  assert.deepEqual(f.state.adds, [track('pt-BR').url, track('de').url]);
  assert.equal(next.selectedLanguage, 'de');
  assert.equal(next.selectedIndex, 1);
  assert.deepEqual(next.catalog.map(item => item.index), [null, 0, 1]);
  await f.coordinator.selectSubtitle(request, track('de').url);
  assert.equal(f.state.adds.length, 2);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.ready.length, 1);
});

test('a recreated coordinator restores explicit RAM catalog bindings but never guesses unknown external tracks', async () => {
  const request = media([track('pt-BR'), track('en')]);
  const f = fixture({ playback: { active: true, sourceUrl: url }, tracks: [{ index: 3, name: 'External' }, { index: 4, name: 'External' }] });
  const restored = await f.coordinator.selectSubtitle(request, track('pt-BR').url, {
    knownCatalog: [{ ...track('pt-BR'), index: 3 }, { ...track('en'), index: null }],
  });
  assert.equal(restored.selectedIndex, 3);
  assert.equal(f.state.adds.length, 0);
  assert.equal(f.state.opened, 0);
  const english = await f.coordinator.selectSubtitle(request, track('en').url, { knownCatalog: restored.catalog });
  assert.equal(english.selectedIndex, 5);
  assert.equal(f.state.adds.length, 1);
  assert.ok(f.state.calls.every(name => !['launch', 'connect', 'open', 'remote'].includes(name)));
});

test('lazy subtitle changes stay source-bound, validate declared choices and preserve selection on add failure', async () => {
  const f = fixture();
  const request = media([track('pt-BR'), track('en')]);
  await f.coordinator.start(request);
  await assert.rejects(f.coordinator.selectSubtitle(request, 'https://captions.example.org/undeclared.vtt'), { code: 'INVALID_SUBTITLE' });
  f.state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' };
  await assert.rejects(f.coordinator.selectSubtitle(request, track('en').url), { code: 'SOURCE_CHANGED' });
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.currentIndex, 0);
  f.state.playback = { active: true, sourceUrl: url };
  f.adapters.kodi.addSubtitle = async () => { throw new Error('Fixture add failed'); };
  await assert.rejects(f.coordinator.selectSubtitle(request, track('en').url), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(f.state.currentIndex, 0);
  assert.equal(f.state.enabled, true);
});

test('cold metadata and playback reads recover transient native failures without repeating mutations', async () => {
  let initialStatusFailures = 2, captionFailures = 2, postAddStatusFailures = 2;
  const f = fixture({
    status: state => {
      if ((!state.opened && initialStatusFailures-- > 0) || (state.adds.length && postAddStatusFailures-- > 0)) {
        state.time += 1000; throw Object.assign(new Error('Transient native startup read'), { code: 'KODI_UNAVAILABLE' });
      }
    },
    getSubtitles: state => {
      if (captionFailures-- > 0) { state.time += 1000; throw Object.assign(new Error('Transient metadata response'), { code: 'KODI_RESPONSE' }); }
    },
  });
  const result = await f.coordinator.start(media([track('pt-BR'), track('en')]));
  assert.equal(f.state.opened, 1);
  assert.deepEqual(f.state.adds, [track('pt-BR').url]);
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 1);
  assert.equal(result.selectedIndex, 0);
  assert.equal(f.state.ready.length, 1);
  assert.ok(f.state.time > 6000);
});

test('multi-RPC subtitle reads and delayed registration have bounded windows longer than three seconds', async () => {
  const budgets = [];
  const f = fixture({
    status: state => { state.time += 1200; },
    getSubtitles: (state, options) => { budgets.push(options.timeoutMs); state.time += 4500; },
    add: (state, value, options) => {
      budgets.push(options.timeoutMs); state.time += 4500;
      state.pending.push({ index: 0, polls: 2 });
    },
    select: (state, index, options) => {
      budgets.push(options.timeoutMs); state.time += 7000;
      state.currentIndex = index; state.enabled = true;
    },
  });
  const result = await f.coordinator.start(media([track('pt-BR')]));
  assert.equal(result.selectedIndex, 0);
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 1);
  assert.ok(budgets.every(value => Number.isFinite(value) && value > 3000 && value <= 30000));
  assert.ok(f.state.time > 30000 && f.state.time < 120000);
  assert.equal(f.state.ready.length, 1);
});

test('inactive subtitle metadata waits for readiness and permanent read failure has a fixed bound', async () => {
  let warming = 2;
  const f = fixture({ getSubtitles: () => warming-- > 0 ? { active: false, tracks: [], enabled: false, currentIndex: null } : undefined });
  await f.coordinator.start(media([track('pt-BR')]));
  assert.equal(f.state.adds.length, 1);
  assert.ok(f.state.time >= 700);
  const unavailable = fixture({
    getSubtitles: () => { throw new Error('Native read unavailable'); },
    coordinator: { limits: { subtitleMs: 700 } },
  });
  await assert.rejects(unavailable.coordinator.start(media([track('pt-BR')])), { code: 'SUBTITLE_TIMEOUT' });
  assert.equal(unavailable.state.time, 700);
  assert.equal(unavailable.state.adds.length, 0);
  assert.equal(unavailable.state.ready.length, 0);
});

test('a transient post-add read never permits subtitle selection after the receiver changes source', async () => {
  let fail = true;
  const f = fixture({
    status: state => {
      if (state.adds.length && fail) { fail = false; throw new Error('Temporary native response'); }
      if (state.adds.length) state.playback = { active: true, sourceUrl: 'https://media.example.org/other.m3u8' };
    },
  });
  await assert.rejects(f.coordinator.start(media([track('pt-BR')])), { code: 'SOURCE_CHANGED' });
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.calls.filter(name => name === 'setSubtitle').length, 0);
  assert.equal(f.state.ready.length, 0);
});

test('read retries stop immediately for native authentication, configuration and source errors', async () => {
  for (const code of ['KODI_AUTH', 'KODI_CONFIG', 'SOURCE_CHANGED', 'INVALID_INPUT']) {
    const f = fixture({ getSubtitles: () => { throw Object.assign(new Error('Fixed native failure'), { code }); } });
    await assert.rejects(f.coordinator.start(media([track('pt-BR')])), { code: code === 'SOURCE_CHANGED' ? 'SOURCE_CHANGED' : 'ADAPTER_FAILED' });
    assert.equal(f.state.time, 0);
    assert.equal(f.state.calls.filter(name => name === 'getSubtitles').length, 1);
    assert.equal(f.state.adds.length, 0);
  }
});

test('an uncertain Add response reconciles a late observed index without sending Add again', async () => {
  const f = fixture({ add: state => {
    state.pending.push({ index: 0, polls: 3 });
    throw Object.assign(new Error('Add response was unavailable'), { code: 'KODI_RESPONSE' });
  } });
  const result = await f.coordinator.start(media([track('pt-BR')]));
  assert.equal(result.selectedIndex, 0);
  assert.equal(f.state.adds.length, 1);
  assert.equal(f.state.ready.length, 1);
});

test('a verified unapplied Add rejection fails promptly and an explicit retry adds once without replay', async () => {
  let applied = 0;
  const f = fixture({ add: (state, value, options) => {
    if (state.adds.length === 1) {
      options.onNotSent();
      throw Object.assign(new Error('Private native rejection detail'), { code: 'KODI_REJECTED' });
    }
    applied++;
    state.tracks.push({ index: 0 });
  } });
  const request = media([track('pt-BR')]);
  await assert.rejects(f.coordinator.start(request), { code: 'ADAPTER_FAILED', adapterCode: 'KODI_REJECTED' });
  assert.equal(f.state.time, 0);
  assert.equal(f.state.adds.length, 1);
  assert.equal(applied, 0);
  await f.coordinator.start(request);
  await f.coordinator.start(request);
  assert.equal(f.state.adds.length, 2);
  assert.equal(applied, 1);
  assert.equal(f.state.opened, 1);
  assert.equal(f.state.currentIndex, 0);
});

test('an uncertain Add with no index times out without a duplicate while unsent proof permits manual retry', async () => {
  for (const unsent of [false, true]) {
    const f = fixture({
      add: (state, value, options) => {
        if (state.adds.length === 1) {
          if (unsent) options.onNotSent();
          throw Object.assign(new Error('Native Add request failed'), { code: 'KODI_UNAVAILABLE' });
        }
        state.tracks.push({ index: 0 });
      },
      coordinator: { limits: { subtitleMs: 700 } },
    });
    const request = media([track('pt-BR')]);
    await assert.rejects(f.coordinator.start(request), { code: unsent ? 'ADAPTER_FAILED' : 'SUBTITLE_TIMEOUT' });
    assert.equal(f.state.adds.length, 1);
    if (unsent) {
      await f.coordinator.start(request);
      assert.equal(f.state.adds.length, 2);
      assert.equal(f.state.ready.length, 1);
    } else {
      await assert.rejects(f.coordinator.start(request), { code: 'SUBTITLE_TIMEOUT' });
      assert.equal(f.state.adds.length, 1);
      assert.equal(f.state.ready.length, 0);
    }
  }
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
