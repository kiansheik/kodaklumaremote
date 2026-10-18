import test from 'node:test';
import assert from 'node:assert/strict';
import { CastController } from '../src/cast/session.js';

class Events {
  listeners = new Map();
  addEventListener(type, handler) {
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(handler);
  }
  removeEventListener(type, handler) { this.listeners.get(type)?.delete(handler); }
  emit(type, event) { for (const listener of [...(this.listeners.get(type) || [])]) listener(event); }
  get count() { return [...this.listeners.values()].reduce((sum, listeners) => sum + listeners.size, 0); }
}

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};
const media = {
  url: 'https://media.example/stream.m3u8?token=private', contentType: 'application/vnd.apple.mpegurl',
  title: 'Test episode', currentTime: 32, duration: 180,
  tracks: [{ id: 1, url: 'https://media.example/pt.vtt', contentType: 'text/vtt', language: 'pt', label: 'Português', active: true }],
};

function fixture({ existing = false, request, load } = {}) {
  const context = new Events();
  const changes = [];
  const statuses = [];
  const calls = [];
  const sdkCallback = () => {};
  const pageListener = () => {};
  context.addEventListener('session', pageListener);
  const mediaSession = {
    currentTime: media.currentTime, media: { duration: media.duration },
    editTracksInfo(request, success) { calls.push(['tracks', request]); success(); },
  };
  const session = {
    async loadMedia(request) { calls.push(['load', request]); return load?.(request); },
    endSession(stop) { calls.push(['end', stop]); context.currentSession = null; context.emit('session', { sessionState: 'ended' }); },
    getMediaSession: () => mediaSession,
  };
  context.currentSession = existing ? session : null;
  context.getCurrentSession = () => context.currentSession;
  context.requestSession = async () => {
    calls.push(['request']);
    await request?.();
    context.currentSession = session;
  };
  context.setOptions = () => { throw new Error('Page context must not be reconfigured.'); };
  let player;
  let remote;
  class RemotePlayer {
    constructor() {
      player = this;
      Object.assign(this, { currentTime: 32, duration: 180, isPaused: false, volumeLevel: 0.6, isMuted: false, isConnected: true, isMediaLoaded: true });
    }
  }
  class RemotePlayerController extends Events {
    constructor(player) { super(); this.player = player; remote = this; }
    playOrPause() { this.player.isPaused = !this.player.isPaused; calls.push(['togglePause']); }
    seek() { calls.push(['seek', this.player.currentTime]); }
    setVolumeLevel() { calls.push(['volume', this.player.volumeLevel]); }
    muteOrUnmute() { this.player.isMuted = !this.player.isMuted; calls.push(['toggleMute']); }
  }
  const window = {
    __onGCastApiAvailable: sdkCallback,
    cast: { framework: {
      CastContext: { getInstance: () => context }, RemotePlayer, RemotePlayerController,
      CastContextEventType: { SESSION_STATE_CHANGED: 'session' },
      SessionState: { SESSION_ENDED: 'ended', SESSION_START_FAILED: 'failed' },
      RemotePlayerEventType: { ANY_CHANGE: 'change' },
    } },
    chrome: { cast: { media: {
      MediaInfo: class { constructor(url, type) { this.contentId = url; this.contentType = type; } },
      LoadRequest: class { constructor(info) { this.media = info; } },
      GenericMediaMetadata: class {},
      Track: class { constructor(id, type) { this.trackId = id; this.type = type; } },
      TrackType: { TEXT: 'TEXT' }, TextTrackType: { SUBTITLES: 'SUBTITLES' },
      EditTracksInfoRequest: class { constructor(ids) { this.activeTrackIds = ids; } },
    } } },
  };
  const controller = new CastController({ window, onStatus: (status) => statuses.push(status), onRemote: (state) => changes.push(state) });
  return { controller, window, context, session, mediaSession, calls, statuses, changes, sdkCallback, pageListener, get player() { return player; }, get remote() { return remote; } };
}

test('requires the page SDK and never overwrites the page availability callback', async () => {
  const statuses = [];
  const window = { __onGCastApiAvailable() {} };
  const original = window.__onGCastApiAvailable;
  const controller = new CastController({ window, onStatus: (status) => statuses.push(status) });
  await assert.rejects(controller.connect(media), { code: 'SDK_UNAVAILABLE' });
  assert.equal(window.__onGCastApiAvailable, original);
  assert.equal(statuses.at(-1).state, 'unavailable');
});

test('loads direct media with offset, metadata and active subtitles after selection', async () => {
  const f = fixture();
  const state = await f.controller.connect(media);
  const request = f.calls.find(([name]) => name === 'load')[1];
  assert.equal(request.media.contentId, media.url);
  assert.equal(request.media.contentType, media.contentType);
  assert.equal(request.media.metadata.title, media.title);
  assert.equal(request.currentTime, 32);
  assert.equal(request.autoplay, true);
  assert.deepEqual(request.activeTrackIds, [1]);
  assert.equal(request.media.tracks[0].trackContentId, media.tracks[0].url);
  assert.equal(state.connected, true);
  assert.equal(f.statuses.at(-1).state, 'connected');
  assert.equal(f.window.__onGCastApiAvailable, f.sdkCallback);
  assert.equal(f.context.listeners.get('session').has(f.pageListener), true);
});

test('reuses an existing session without changing the page receiver options', async () => {
  const f = fixture({ existing: true });
  await f.controller.connect(media);
  assert.equal(f.calls.some(([name]) => name === 'request'), false);
});

test('does not report connected before the receiver load resolves', async () => {
  const pending = deferred();
  const f = fixture({ load: () => pending.promise });
  const connecting = f.controller.connect(media);
  await Promise.resolve();
  await Promise.resolve();
  assert.equal(f.statuses.some(({ state }) => state === 'connected'), false);
  pending.resolve();
  await connecting;
  assert.equal(f.statuses.at(-1).state, 'connected');
});

test('chooser cancellation returns a sanitized error and leaves no own listeners', async () => {
  const f = fixture({ request: () => Promise.reject({ code: 'cancel', description: 'https://secret.example/?token=hidden' }) });
  await assert.rejects(f.controller.connect(media), (error) => {
    assert.equal(error.code, 'CANCELED');
    assert.doesNotMatch(error.message, /secret|hidden|https/);
    return true;
  });
  assert.equal(f.statuses.at(-1).state, 'canceled');
  assert.equal(f.context.count, 1);
  assert.equal(f.calls.some(([name]) => name === 'load'), false);
});

test('CAF string cancellation is distinguished from a receiver connection failure', async () => {
  const f = fixture({ request: () => Promise.reject('cancel') });
  await assert.rejects(f.controller.connect(media), { code: 'CANCELED' });
  assert.equal(f.statuses.at(-1).state, 'canceled');
  assert.equal(f.context.count, 1);
});

test('receiver discovery errors do not claim the receiver rejected media', async () => {
  for (const reason of ['receiver_unavailable', { code: 'receiver_unavailable', description: 'https://secret.example/?token=hidden' }]) {
    const f = fixture({ request: () => Promise.reject(reason) });
    await assert.rejects(f.controller.connect(media), (error) => {
      assert.equal(error.code, 'RECEIVER_UNAVAILABLE');
      assert.match(error.message, /No compatible Cast receiver/);
      assert.doesNotMatch(error.message, /media|source|secret|hidden|https/);
      return true;
    });
    assert.equal(f.calls.some(([name]) => name === 'load'), false);
    assert.equal(f.statuses.at(-1).state, 'error');
    assert.equal(f.context.count, 1);
  }
});

test('SDK readiness is distinct from discovery and does not reconfigure page options', async () => {
  const notInitialized = fixture({ request: () => Promise.reject('api_not_initialized') });
  const notConfigured = fixture();
  notConfigured.context.requestSession = () => { throw new Error('https://secret.example/?token=hidden'); };
  for (const f of [notInitialized, notConfigured]) {
    await assert.rejects(f.controller.connect(media), { code: 'SDK_NOT_READY' });
    assert.equal(f.statuses.at(-1).state, 'unavailable');
    assert.equal(f.calls.some(([name]) => name === 'load'), false);
    assert.doesNotMatch(JSON.stringify(f.statuses), /secret|hidden|https/);
    assert.equal(f.context.count, 1);
  }
});

test('session timeout and unknown connection errors stay separate from media load failures', async () => {
  for (const [reason, code] of [
    ['timeout', 'SESSION_TIMEOUT'],
    ['session_error', 'SESSION_FAILED'],
    [{ code: 'channel_error', details: 'https://secret.example/?token=hidden' }, 'SESSION_FAILED'],
    [new Error('https://secret.example/?token=hidden'), 'SESSION_FAILED'],
  ]) {
    const f = fixture({ request: () => Promise.reject(reason) });
    await assert.rejects(f.controller.connect(media), { code });
    assert.equal(f.calls.some(([name]) => name === 'load'), false);
    assert.doesNotMatch(JSON.stringify(f.statuses), /media|source|secret|hidden|https/);
    assert.equal(f.context.count, 1);
  }
});

test('load failure stops a newly opened session but preserves a reused page session', async () => {
  for (const existing of [false, true]) {
    const f = fixture({ existing, load: () => Promise.reject(new Error('https://secret.example/?token=hidden')) });
    await assert.rejects(f.controller.connect(media), { code: 'LOAD_FAILED' });
    assert.equal(f.calls.filter(([name]) => name === 'end').length, existing ? 0 : 1);
    assert.equal(f.statuses.at(-1).state, 'error');
    assert.equal(f.context.count, 1);
    assert.doesNotMatch(JSON.stringify(f.statuses), /secret|token|hidden/);
  }
});

test('both resolved and rejected CAF load errors report media failure after connection', async () => {
  for (const load of [
    () => 'load_media_failed',
    () => Promise.reject('load_media_failed'),
    () => Promise.reject({ code: 'cancel', description: 'https://secret.example/?token=hidden' }),
    () => Promise.reject('receiver_unavailable'),
  ]) {
    const f = fixture({ load });
    await assert.rejects(f.controller.connect(media), { code: 'LOAD_FAILED' });
    assert.equal(f.statuses.at(-1).state, 'error');
    assert.match(f.statuses.at(-1).message, /connected but could not load/);
    assert.doesNotMatch(JSON.stringify(f.statuses), /secret|hidden|https/);
    assert.equal(f.calls.filter(([name]) => name === 'load').length, 1);
    assert.equal(f.calls.filter(([name]) => name === 'end').length, 1);
    assert.equal(f.context.count, 1);
  }
});

test('remote controls act on receiver state and subtitle IDs', async () => {
  const f = fixture();
  await f.controller.connect(media);
  f.controller.pause();
  f.controller.pause();
  f.controller.resume();
  f.controller.seek(200);
  f.controller.setVolume(0.4);
  f.controller.setMuted(true);
  f.controller.setMuted(true);
  await f.controller.setTracks([]);
  assert.equal(f.calls.filter(([name]) => name === 'togglePause').length, 2);
  assert.equal(f.calls.filter(([name]) => name === 'toggleMute').length, 1);
  assert.deepEqual(f.calls.find(([name]) => name === 'seek'), ['seek', 180]);
  assert.deepEqual(f.calls.find(([name]) => name === 'volume'), ['volume', 0.4]);
  assert.deepEqual(f.calls.find(([name]) => name === 'tracks')[1].activeTrackIds, []);
  assert.throws(() => f.controller.seek(NaN), { code: 'INVALID_VALUE' });
  assert.throws(() => f.controller.setVolume(2), { code: 'INVALID_VALUE' });
  await assert.rejects(f.controller.setTracks([99]), { code: 'INVALID_VALUE' });
});

test('remote status snapshots contain no URL and preserve saved position when ended', async () => {
  const f = fixture();
  await f.controller.connect(media);
  f.player.currentTime = 77;
  f.remote.emit('change', { field: 'currentTime', value: 77 });
  assert.equal(f.changes.at(-1).currentTime, 77);
  f.player.savedPlayerState = { currentTime: 79, isPaused: true };
  f.player.currentTime = 0;
  f.remote.emit('change', { field: 'isConnected', value: false });
  assert.deepEqual(f.changes.at(-1), { currentTime: 79, duration: 180, paused: true, volume: 0.6, muted: false, connected: false });
  assert.equal(f.statuses.at(-1).state, 'ended');
  assert.equal(f.context.count, 1);
  assert.equal(f.remote.count, 0);
  assert.doesNotMatch(JSON.stringify(f.changes), /https|token|private/);
});

test('disconnect captures receiver position, stops once and removes only own listeners', async () => {
  const f = fixture();
  await f.controller.connect(media);
  f.player.currentTime = 84;
  const state = await f.controller.disconnect();
  assert.equal(state.currentTime, 84);
  assert.equal(state.connected, false);
  assert.equal(f.calls.filter(([name]) => name === 'end').length, 1);
  assert.equal(f.context.count, 1);
  assert.equal(f.remote.count, 0);
  assert.throws(() => f.controller.pause(), { code: 'NOT_CONNECTED' });
});

test('session-ended event retains the last remote position if CAF already cleared its player', async () => {
  const f = fixture();
  await f.controller.connect(media);
  f.player.currentTime = 91;
  f.remote.emit('change', { field: 'currentTime', value: 91 });
  Object.assign(f.player, { currentTime: 0, duration: 0, isMediaLoaded: false, volumeLevel: 0 });
  f.context.emit('session', { sessionState: 'ended' });
  assert.equal(f.changes.at(-1).currentTime, 91);
  assert.equal(f.changes.at(-1).duration, 180);
  assert.equal(f.changes.at(-1).volume, 0.6);
  assert.equal(f.changes.at(-1).connected, false);
});

test('disconnect during a pending chooser prevents late receiver playback', async () => {
  const pending = deferred();
  const f = fixture({ request: () => pending.promise });
  const connecting = f.controller.connect(media);
  await f.controller.disconnect();
  pending.resolve();
  await assert.rejects(connecting, { code: 'CANCELED' });
  assert.equal(f.calls.some(([name]) => name === 'load'), false);
  assert.equal(f.calls.filter(([name]) => name === 'end').length, 1);
  assert.equal(f.statuses.at(-1).state, 'disconnected');
});

test('dispose while load is pending prevents success callbacks and cleans the new session', async () => {
  const pending = deferred();
  const f = fixture({ load: () => pending.promise });
  const connecting = f.controller.connect(media);
  await Promise.resolve();
  await Promise.resolve();
  f.controller.dispose();
  const statusCount = f.statuses.length;
  pending.resolve();
  await assert.rejects(connecting, { code: 'CANCELED' });
  assert.equal(f.statuses.length, statusCount);
  assert.equal(f.calls.filter(([name]) => name === 'end').length, 1);
  assert.equal(f.context.count, 1);
  await assert.rejects(f.controller.connect(media), { code: 'DISPOSED' });
});

test('dispose during a reused session load stops a late successful own load', async () => {
  const pending = deferred();
  const f = fixture({ existing: true, load: () => pending.promise });
  const connecting = f.controller.connect(media);
  f.controller.dispose();
  pending.resolve();
  await assert.rejects(connecting, { code: 'CANCELED' });
  assert.equal(f.calls.filter(([name]) => name === 'end').length, 1);
  assert.equal(f.context.count, 1);
});

test('rejects non-HTTP direct loads before opening any chooser', async () => {
  const f = fixture();
  await assert.rejects(f.controller.connect({ ...media, url: 'blob:https://media.example/id' }), { code: 'INVALID_MEDIA' });
  assert.equal(f.calls.length, 0);
});
