// A JS orchestration layer for a single selected receiver. Adapters own all I/O.
// Native/WebView adapters must honor signal. For open, expectedSourceUrl binds
// media.url; for subtitles, require Kodi's active source to match it before I/O.
// JS cancellation cannot undo an already accepted command.
// receiver.launchKodi(options); kodi.connect(options) -> {ready:true};
// kodi.open(media, options); kodi.status(options) -> {active, sourceUrl};
// kodi.getSubtitles(options) -> {tracks:[{index,url?}], enabled, currentIndex};
// kodi.addSubtitle(track, options); kodi.setSubtitle(index|'off', options);
// remote.ready(result, options). Every options object includes AbortSignal.
// addSubtitle options.onNotSent() may report explicit proof that this request
// never reached Kodi, including a late native pre-write rejection after abort.
// Cancellation or a timeout alone is not proof and must never call onNotSent.

const MESSAGES = Object.freeze({
  INVALID_MEDIA: 'The page did not provide a supported video and subtitle list.',
  CANCELLED: 'Casting was cancelled.',
  SOURCE_CHANGED: 'The selected video changed. Start casting again.',
  KODI_START_TIMEOUT: 'Kodi did not become ready in time.',
  VIDEO_TIMEOUT: 'Kodi did not start the selected video in time.',
  SUBTITLE_TIMEOUT: 'Kodi did not register a subtitle in time.',
  HANDOFF_TIMEOUT: 'Casting setup took too long. Try again.',
  SUBTITLE_BINDING: 'Kodi changed the subtitle list unexpectedly. Start the video again.',
  ADAPTER_FAILED: 'The receiver could not complete casting setup. Try again.',
});

export class HandoffError extends Error {
  constructor(code) {
    super(MESSAGES[code] || MESSAGES.ADAPTER_FAILED);
    this.name = 'HandoffError';
    this.code = code in MESSAGES ? code : 'ADAPTER_FAILED';
  }
}

function fail(code) { return new HandoffError(code); }
function safeText(value, limit) { return typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f-\u009f]/gu, '').slice(0, limit) : ''; }
function sourceUrl(value) {
  if (typeof value !== 'string' || /[\s\u0000-\u001f\u007f\\|]/u.test(value)) throw fail('INVALID_MEDIA');
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) throw fail('INVALID_MEDIA');
    return url.href;
  } catch { throw fail('INVALID_MEDIA'); }
}

function mediaSnapshot(input, maxTracks) {
  if (!input || typeof input !== 'object' || typeof input.contentType !== 'string'
      || !/^(?:video\/[a-z0-9!#$&^_.+-]+|application\/(?:vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))$/iu.test(input.contentType)
      || (input.tracks !== undefined && (!Array.isArray(input.tracks) || input.tracks.length > 128))) throw fail('INVALID_MEDIA');
  const seen = new Set();
  const tracks = [];
  for (const track of input.tracks || []) {
    if (!track || typeof track !== 'object') throw fail('INVALID_MEDIA');
    const url = sourceUrl(track.url ?? track.src);
    if (seen.has(url)) continue;
    seen.add(url);
    tracks.push({ url, contentType: 'text/vtt', language: safeText(track.language, 64), label: safeText(track.label, 200), active: track.active === true });
  }
  if (tracks.length > maxTracks) throw fail('INVALID_MEDIA');
  return { url: sourceUrl(input.url), contentType: input.contentType.toLowerCase(), title: safeText(input.title, 200), tracks };
}

function language(track) {
  let code = track.language.trim().toLowerCase().replaceAll('_', '-');
  if (/^por(?:-|$)/u.test(code)) code = code.replace(/^por/u, 'pt');
  if (code === 'portuguese') code = 'pt';
  if (/^(?:eng|english)(?:-|$)/u.test(code)) code = code.replace(/^(?:eng|english)/u, 'en');
  if ((code === 'pt' || !code) && /\b(?:brazil|brasil|brazilian)\b/iu.test(track.label)) code = 'pt-br';
  return code;
}

function bounded(value, fallback, max, minimum = 1) {
  return Number.isFinite(value) ? Math.max(minimum, Math.min(max, value)) : fallback;
}

function abortableWait(ms, { signal }) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(fail('CANCELLED')); return; }
    const abort = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); reject(fail('CANCELLED')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, ms);
    signal.addEventListener('abort', abort, { once: true });
  });
}

/** No network, media caching, logging, credential storage or Android APIs here. */
export function createHandoffCoordinator({
  receiver, kodi, remote, onStatus = () => {},
  now = () => performance.now(), wait = abortableWait,
  preferredLanguages = ['pt-BR', 'en'], limits = {},
} = {}) {
  if (typeof receiver?.launchKodi !== 'function' || typeof remote?.ready !== 'function'
      || ['connect', 'open', 'status', 'getSubtitles', 'addSubtitle', 'setSubtitle'].some(name => typeof kodi?.[name] !== 'function')) throw fail('ADAPTER_FAILED');
  const settings = {
    startup: bounded(limits.startupMs, 60000, 60000),
    active: bounded(limits.activeMs, 30000, 30000),
    subtitle: bounded(limits.subtitleMs, 3000, 3000),
    overall: bounded(limits.overallMs, 120000, 120000),
    poll: bounded(limits.pollMs, 350, 2000, 100),
    tracks: Math.floor(bounded(limits.maxTracks, 64, 128)),
  };
  const preferences = (Array.isArray(preferredLanguages) ? preferredLanguages : []).slice(0, 8)
    .filter(value => typeof value === 'string').map(value => value.toLowerCase().replaceAll('_', '-'));
  // Metadata only, never media bytes. Reuses confirmed bindings on a retry of
  // the still-active source. Unknown existing external tracks are not inferred.
  const sources = new Map();
  let current = null;

  function check(ctx) {
    if (ctx.controller.signal.aborted || current !== ctx) throw fail('CANCELLED');
    let unchanged = false;
    try { unchanged = ctx.isCurrentSource() === true; } catch { /* Treat a detached page as changed. */ }
    if (!unchanged) throw fail('SOURCE_CHANGED');
    if (now() >= ctx.deadline) throw fail('HANDOFF_TIMEOUT');
  }

  function status(ctx, step, message) {
    if (current !== ctx) return;
    try { onStatus({ step, message }); } catch { /* Rendering cannot interrupt receiver control. */ }
  }

  function stage(ctx, duration, code) {
    const deadline = Math.min(ctx.deadline, now() + duration);
    return { deadline, code: deadline === ctx.deadline ? 'HANDOFF_TIMEOUT' : code };
  }

  async function call(ctx, operation, window = { deadline: ctx.deadline, code: 'HANDOFF_TIMEOUT' }) {
    check(ctx);
    const remaining = window.deadline - now();
    if (remaining <= 0) throw fail(window.code);
    const operationController = new AbortController();
    let abort;
    let timer;
    const stopped = new Promise((_, reject) => {
      abort = () => { operationController.abort(); reject(fail('CANCELLED')); };
      ctx.controller.signal.addEventListener('abort', abort, { once: true });
      timer = setTimeout(() => { operationController.abort(); reject(fail(window.code)); }, remaining);
    });
    try {
      const result = await Promise.race([
        Promise.resolve().then(() => {
          check(ctx);
          return operation({ signal: operationController.signal, expectedSourceUrl: ctx.media.url });
        }),
        stopped,
      ]);
      check(ctx);
      if (now() >= window.deadline) throw fail(window.code);
      return result;
    } catch (error) {
      if (error instanceof HandoffError) throw error;
      check(ctx);
      throw fail('ADAPTER_FAILED');
    } finally {
      clearTimeout(timer);
      ctx.controller.signal.removeEventListener('abort', abort);
    }
  }

  async function pause(ctx, window) {
    if (now() >= window.deadline) throw fail(window.code);
    await call(ctx, options => wait(Math.min(settings.poll, window.deadline - now()), options), window);
  }

  async function assertPlayback(ctx, window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT')) {
    const playback = await call(ctx, options => kodi.status(options), window);
    if (!playback?.active || playback.sourceUrl !== ctx.media.url) throw fail('SOURCE_CHANGED');
  }

  async function subtitles(ctx, window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT')) {
    const snapshot = await call(ctx, options => kodi.getSubtitles(options), window);
    await assertPlayback(ctx, window);
    if (!snapshot || !Array.isArray(snapshot.tracks) || snapshot.tracks.length > 128) throw fail('SUBTITLE_BINDING');
    const indices = new Set();
    for (const track of snapshot.tracks) {
      if (!Number.isInteger(track?.index) || track.index < 0 || track.index > 1024 || indices.has(track.index)) throw fail('SUBTITLE_BINDING');
      indices.add(track.index);
    }
    return snapshot;
  }

  async function finishPending(ctx, cache, snapshot, window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT')) {
    if (!cache.pending) return snapshot;
    while (true) {
      if (!cache.pending.invoked) { cache.pending = null; return snapshot; }
      const added = snapshot.tracks.filter(track => !cache.pending.before.has(track.index));
      if (added.length > 1) throw fail('SUBTITLE_BINDING');
      if (added.length === 1) {
        const track = added[0];
        if (track.url !== undefined && track.url !== cache.pending.url) throw fail('SUBTITLE_BINDING');
        cache.pending.boundIndex = track.index;
        cache.bindings.set(cache.pending.url, track.index);
        cache.pending = null;
        return snapshot;
      }
      await pause(ctx, window);
      snapshot = await subtitles(ctx, window);
    }
  }

  async function run(ctx) {
    status(ctx, 'launching', 'Opening Kodi on the projector…');
    const startup = stage(ctx, settings.startup, 'KODI_START_TIMEOUT');
    await call(ctx, options => receiver.launchKodi(options), startup);
    status(ctx, 'waiting', 'Waiting for Kodi…');
    while (true) {
      try {
        if ((await call(ctx, options => kodi.connect(options), startup))?.ready === true) break;
      } catch (error) { if (error.code !== 'ADAPTER_FAILED') throw error; }
      await pause(ctx, startup);
    }
    const initial = await call(ctx, options => kodi.status(options), startup);
    let cache = sources.get(ctx.media.url);
    if (!initial?.active || initial.sourceUrl !== ctx.media.url) {
      cache = { bindings: new Map(), pending: null };
      sources.set(ctx.media.url, cache);
      status(ctx, 'opening', 'Starting the selected video…');
      const active = stage(ctx, settings.active, 'VIDEO_TIMEOUT');
      await call(ctx, options => kodi.open(ctx.media, options), active);
      while (true) {
        let playback;
        try { playback = await call(ctx, options => kodi.status(options), active); }
        catch (error) { if (error.code !== 'ADAPTER_FAILED') throw error; }
        if (playback?.active && playback.sourceUrl === ctx.media.url) break;
        await pause(ctx, active);
      }
    }
    if (!cache) { cache = { bindings: new Map(), pending: null }; sources.set(ctx.media.url, cache); }
    while (sources.size > 4) sources.delete(sources.keys().next().value);
    status(ctx, 'subtitles', 'Loading available subtitles…');
    let snapshot = await subtitles(ctx);
    snapshot = await finishPending(ctx, cache, snapshot);
    const present = new Set(snapshot.tracks.map(track => track.index));
    for (const [url, index] of cache.bindings) if (!present.has(index)) cache.bindings.delete(url);
    for (const track of ctx.media.tracks) {
      if (cache.bindings.has(track.url)) continue;
      const existing = snapshot.tracks.find(item => item.url === track.url);
      if (existing) { cache.bindings.set(track.url, existing.index); continue; }
      await assertPlayback(ctx);
      const pending = { url: track.url, before: new Set(snapshot.tracks.map(item => item.index)), invoked: false };
      cache.pending = pending;
      const window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
      await call(ctx, options => {
        pending.invoked = true;
        return kodi.addSubtitle(track, {
          ...options,
          onNotSent() {
            pending.invoked = false;
            if (pending.boundIndex !== undefined && cache.bindings.get(pending.url) === pending.boundIndex) cache.bindings.delete(pending.url);
          },
        });
      }, window);
      if (!pending.invoked) throw fail('ADAPTER_FAILED');
      snapshot = await subtitles(ctx, window);
      snapshot = await finishPending(ctx, cache, snapshot, window);
    }
    const tracks = ctx.media.tracks.map(track => ({ index: cache.bindings.get(track.url), language: track.language, label: track.label }));
    const selected = preferences.flatMap(preference => tracks.filter(track => {
      const code = language(track);
      return code === preference || (preference === 'en' && code.startsWith('en-'));
    }))[0];
    status(ctx, 'selecting', selected ? 'Selecting the preferred subtitles…' : 'Keeping subtitles off…');
    await assertPlayback(ctx);
    const selection = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
    await call(ctx, options => kodi.setSubtitle(selected?.index ?? 'off', options), selection);
    while (true) {
      const observed = await subtitles(ctx, selection);
      if (selected ? observed.enabled === true && observed.currentIndex === selected.index : observed.enabled === false) break;
      await pause(ctx, selection);
    }
    const result = { subtitleCount: tracks.length, selectedLanguage: selected ? language(selected) : null, selectedIndex: selected?.index ?? null, tracks };
    status(ctx, 'remote', 'Casting is ready. Opening the remote…');
    await call(ctx, options => remote.ready(result, options));
    status(ctx, 'ready', 'Casting is ready.');
    return result;
  }

  return {
    start(input, { signal, isCurrentSource = () => true } = {}) {
      let media;
      try { media = mediaSnapshot(input, settings.tracks); } catch { return Promise.reject(fail('INVALID_MEDIA')); }
      current?.controller.abort();
      const ctx = { media, controller: new AbortController(), isCurrentSource, deadline: now() + settings.overall };
      current = ctx;
      const abort = () => ctx.controller.abort();
      if (signal?.aborted) abort();
      else signal?.addEventListener('abort', abort, { once: true });
      return run(ctx).catch(error => {
        const fixed = error instanceof HandoffError ? error : fail('ADAPTER_FAILED');
        status(ctx, fixed.code === 'CANCELLED' ? 'cancelled' : 'error', fixed.message);
        throw fixed;
      }).finally(() => {
        signal?.removeEventListener('abort', abort);
        if (current === ctx) current = null;
      });
    },
    cancel() { current?.controller.abort(); },
  };
}
