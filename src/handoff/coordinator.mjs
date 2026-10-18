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
// was never applied or queued, including a verified Kodi rejection or a late
// native pre-write rejection after abort.
// Cancellation or a timeout alone is not proof and must never call onNotSent.
// options.phase is 'handoff' or 'remote'. A cold-start Open retry also includes
// onlyIfInactive:true; its adapter must reject if any video became active before
// writing Player.Open. No retry is sent while an earlier Open promise is pending.
// open options.onNotSent() accepts the same explicit pre-write proof as Add.
// open options.onSettled() reports an actual native response, even when its
// receiver write outcome is unknown. JS timeout/abort alone must not call it.

const MESSAGES = Object.freeze({
  INVALID_MEDIA: 'The page did not provide a supported video and subtitle list.',
  INVALID_SUBTITLE: 'Choose a subtitle declared by the selected video.',
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
const TRANSIENT_ADAPTER_CODES = new Set(['KODI_UNAVAILABLE', 'TIMEOUT', 'BUSY', 'KODI_RESPONSE', 'KODI_REJECTED']);
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
    openRetry: bounded(limits.openRetryMs, 5000, 10000, 500),
    // Subtitle readiness and registration may settle after video starts.
    subtitle: bounded(limits.subtitleMs, 30000, 30000),
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
    const deadline = Math.min(ctx.deadline, now() + duration,
      code === 'SUBTITLE_TIMEOUT' ? ctx.subtitleDeadline ?? Infinity : Infinity);
    return { deadline, code: deadline === ctx.deadline ? 'HANDOFF_TIMEOUT' : code };
  }

  async function call(ctx, operation, window = { deadline: ctx.deadline, code: 'HANDOFF_TIMEOUT' }, observed) {
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
          return operation({ signal: operationController.signal, timeoutMs: Math.ceil(remaining), expectedSourceUrl: ctx.media.url, phase: ctx.phase });
        }),
        stopped,
        // An exact-source playback observation can finish waiting for Open's
        // response without cancelling a command the receiver already applied.
        ...(observed ? [observed] : []),
      ]);
      check(ctx);
      if (now() >= window.deadline) throw fail(window.code);
      return result;
    } catch (error) {
      if (error instanceof HandoffError) throw error;
      check(ctx);
      const fixed = fail('ADAPTER_FAILED');
      fixed.retryable = typeof error?.code !== 'string' || TRANSIENT_ADAPTER_CODES.has(error.code);
      if (['SOURCE_CHANGED', 'KODI_REJECTED', 'KODI_AUTH', 'KODI_CONFIG'].includes(error?.code)) fixed.adapterCode = error.code;
      throw fixed;
    } finally {
      clearTimeout(timer);
      ctx.controller.signal.removeEventListener('abort', abort);
    }
  }

  async function pause(ctx, window) {
    if (now() >= window.deadline) throw fail(window.code);
    await call(ctx, options => wait(Math.min(settings.poll, window.deadline - now()), options), window);
  }

  async function read(ctx, operation, window) {
    while (true) {
      try { return await call(ctx, operation, window); }
      catch (error) {
        if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED');
        if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
      }
      await pause(ctx, window);
    }
  }

  async function assertPlayback(ctx, window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT'), waitForActive = false) {
    while (true) {
      const playback = await read(ctx, options => kodi.status(options), window);
      if (playback?.active && playback.sourceUrl === ctx.media.url) return;
      if (!waitForActive || playback?.active !== false) throw fail('SOURCE_CHANGED');
      await pause(ctx, window);
    }
  }

  async function subtitles(ctx, window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT')) {
    while (true) {
      const snapshot = await read(ctx, options => kodi.getSubtitles(options), window);
      await assertPlayback(ctx, window, true);
      if (snapshot?.active === false) { await pause(ctx, window); continue; }
      if (!snapshot || !Array.isArray(snapshot.tracks) || snapshot.tracks.length > 128) throw fail('SUBTITLE_BINDING');
      const indices = new Set();
      for (const track of snapshot.tracks) {
        if (!Number.isInteger(track?.index) || track.index < 0 || track.index > 1024 || indices.has(track.index)) throw fail('SUBTITLE_BINDING');
        indices.add(track.index);
      }
      ctx.subtitleObserved = snapshot;
      return snapshot;
    }
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

  function cacheFor(media) {
    let cache = sources.get(media.url);
    if (!cache) { cache = { bindings: new Map(), pending: null, opening: null }; sources.set(media.url, cache); }
    while (sources.size > 4) sources.delete(sources.keys().next().value);
    return cache;
  }

  function preferred(tracks) {
    return [...new Set(preferences.flatMap(preference => tracks.filter(track => {
      const code = language(track);
      return code === preference || (preference === 'en' && code.startsWith('en-'));
    })))];
  }

  function resultFor(ctx, cache, selectedIndex) {
    const catalog = ctx.media.tracks.map(track => ({
      url: track.url, language: track.language, label: track.label,
      index: cache.bindings.get(track.url) ?? null,
    }));
    const tracks = catalog.filter(track => track.index !== null)
      .map(({ index, language, label }) => ({ index, language, label }));
    const selected = tracks.find(track => track.index === selectedIndex);
    return { subtitleCount: tracks.length, selectedLanguage: selected ? language(selected) : null, selectedIndex: selected?.index ?? null, tracks, catalog };
  }

  async function observeTracks(ctx, cache) {
    let snapshot = await subtitles(ctx);
    snapshot = await finishPending(ctx, cache, snapshot);
    const present = new Set(snapshot.tracks.map(track => track.index));
    for (const [url, index] of cache.bindings) {
      if (!present.has(index)) cache.bindings.delete(url);
      else {
        const observed = snapshot.tracks.find(track => track.index === index);
        if (observed.url !== undefined && observed.url !== url) throw fail('SUBTITLE_BINDING');
      }
    }
    const claimed = new Map([...cache.bindings].map(([url, index]) => [index, url]));
    const identified = ctx.media.tracks.flatMap(track => {
      const observed = snapshot.tracks.find(item => item.url === track.url);
      return observed ? [{ url: track.url, index: observed.index }] : [];
    });
    for (const track of [...identified, ...(ctx.knownCatalog || [])]) {
      if (track.index === null || !present.has(track.index)) continue;
      const observed = snapshot.tracks.find(item => item.index === track.index);
      if ((observed.url !== undefined && observed.url !== track.url)
          || (claimed.has(track.index) && claimed.get(track.index) !== track.url)) throw fail('SUBTITLE_BINDING');
      if (!cache.bindings.has(track.url)) { cache.bindings.set(track.url, track.index); claimed.set(track.index, track.url); }
    }
    return snapshot;
  }

  async function loadTrack(ctx, cache, track, snapshot) {
    if (!track || cache.bindings.has(track.url)) return snapshot;
    const existing = snapshot.tracks.find(item => item.url === track.url);
    if (existing) { cache.bindings.set(track.url, existing.index); return snapshot; }
    await assertPlayback(ctx);
    const pending = { url: track.url, before: new Set(snapshot.tracks.map(item => item.index)), invoked: false };
    cache.pending = pending;
    const window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
    let rejected;
    try {
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
    } catch (error) {
      if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
      rejected = error;
      // A transport/response failure may follow a successful AddSubtitle write.
      // Observe its result once within the registration window; never resend it.
    }
    if (!pending.invoked) throw rejected || fail('ADAPTER_FAILED');
    const registration = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
    snapshot = await subtitles(ctx, registration);
    return finishPending(ctx, cache, snapshot, registration);
  }

  async function selectTrack(ctx, cache, track) {
    const index = track ? cache.bindings.get(track.url) : null;
    if (track && !Number.isInteger(index)) throw fail('SUBTITLE_BINDING');
    status(ctx, 'selecting', track ? 'Selecting the preferred subtitles…' : 'Keeping subtitles off…');
    await assertPlayback(ctx);
    let rejected;
    try { await call(ctx, options => kodi.setSubtitle(index ?? 'off', options), stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT')); }
    catch (error) {
      if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
      rejected = error;
    }
    const selection = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
    while (true) {
      const observed = await subtitles(ctx, selection);
      if (track ? observed.enabled === true && observed.currentIndex === index : observed.enabled === false) break;
      if (rejected?.adapterCode === 'KODI_REJECTED') throw rejected;
      await pause(ctx, selection);
    }
    return resultFor(ctx, cache, index);
  }

  function sendOpen(ctx, cache, window, onlyIfInactive) {
    const attempt = { owner: ctx, invoked: false, settled: false, acknowledged: false, acceptedAt: null, unsent: false };
    const forgetCompletedPlayback = () => {
      if (attempt.nativeSettled && attempt.playbackObserved && cache.opening === attempt) cache.opening = null;
    };
    const observed = new Promise(resolve => { attempt.observed = () => {
      attempt.playbackObserved = true;
      forgetCompletedPlayback();
      resolve();
    }; });
    cache.opening = attempt;
    attempt.completion = call(ctx, options => {
      // Keep settlement independently of the cancelled JS run: a new run must
      // not overlap an old native request whose receiver outcome is unknown.
      return Promise.resolve().then(() => {
        check(ctx); attempt.invoked = true;
        return kodi.open(ctx.media, {
          ...options, ...(onlyIfInactive ? { onlyIfInactive: true } : {}),
          onSettled() { attempt.nativeSettled = true; forgetCompletedPlayback(); },
          onNotSent() {
            attempt.unsent = true; attempt.invoked = false;
            attempt.settled = true; attempt.acknowledged = false;
          },
        });
      })
        .then(result => {
          if (attempt.unsent) throw fail('ADAPTER_FAILED');
          attempt.settled = true; attempt.acknowledged = true; attempt.acceptedAt = now(); return result;
        },
          error => {
            // A bridge abort, timeout or transport error may arrive after the
            // request was written. Rejection alone never proves it was not sent.
            throw error;
          });
    }, window, observed).catch(error => { attempt.error = error; });
    return attempt;
  }

  async function openVideo(ctx, cache, initial) {
    const active = stage(ctx, settings.active, 'VIDEO_TIMEOUT');
    const previousSource = initial?.active ? initial.sourceUrl : null;
    let attempt = cache.opening;
    let sent = 1;
    if (!attempt?.invoked || (attempt.settled && (previousSource !== null || !attempt.acknowledged))) {
      attempt = sendOpen(ctx, cache, active, false);
    }
    let inactiveSince = null;
    while (true) {
      let playback;
      try { playback = await call(ctx, options => kodi.status(options), active); }
      catch (error) {
        if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED');
        if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
      }
      if (playback?.active) {
        if (playback.sourceUrl === ctx.media.url) { attempt?.observed(); return; }
        if (!previousSource || playback.sourceUrl !== previousSource) throw fail('SOURCE_CHANGED');
        inactiveSince = null;
      }
      if (attempt?.owner === ctx && attempt.unsent) {
        await attempt.completion;
        throw attempt.error || fail('ADAPTER_FAILED');
      }
      if (attempt?.owner === ctx && attempt.error && (attempt.unsent || attempt.error.code !== 'ADAPTER_FAILED'
          || attempt.error.retryable !== true)) throw attempt.error;
      if (playback?.active === false && !playback.sourceUrl) {
        inactiveSince ??= now();
        const completedPrevious = attempt?.owner !== ctx && attempt?.nativeSettled && attempt.playbackObserved;
        const retryAcknowledged = attempt?.settled && (attempt.unsent || (attempt.acknowledged
          && now() - attempt.acceptedAt >= settings.openRetry && now() - inactiveSince >= settings.poll * 2));
        if (sent < 2 && (completedPrevious || retryAcknowledged)) {
          // A later explicit cast can resume when the old invocation finishes;
          // that old playback is not an Open sent by this new attempt.
          if (attempt.unsent || completedPrevious) sent--;
          sent++;
          attempt = sendOpen(ctx, cache, active, true);
          inactiveSince = null;
        }
      } else if (!playback?.active) inactiveSince = null;
      // Reconcile a slow/failed Open against actual playback before reporting
      // an error. Unknown outcomes remain observation-only and cannot replay.
      await pause(ctx, active);
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
      } catch (error) {
        if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED');
        if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
      }
      await pause(ctx, startup);
    }
    const initial = await read(ctx, options => kodi.status(options), startup);
    const cache = cacheFor(ctx.media);
    if (initial?.active && initial.sourceUrl === ctx.media.url) cache.opening?.observed();
    if (!initial?.active || initial.sourceUrl !== ctx.media.url) {
      cache.bindings.clear(); cache.pending = null;
      status(ctx, 'opening', 'Starting the selected video…');
      await openVideo(ctx, cache, initial);
    }
    status(ctx, 'subtitles', 'Loading the preferred subtitles…');
    // Leave time to reconcile playback and publish the remote when a subtitle
    // server/decoder fails. Subtitle failure must never invite replay of video.
    ctx.subtitleDeadline = Math.min(ctx.deadline - Math.min(20000, settings.overall / 6), now() + settings.subtitle * 2);
    let result, subtitleFailure;
    try {
      let snapshot = await observeTracks(ctx, cache);
      const candidates = preferred(ctx.media.tracks);
      if (!candidates.length) result = await selectTrack(ctx, cache, null);
      for (const [position, selected] of candidates.entries()) {
        try {
          await loadTrack(ctx, cache, selected, snapshot);
          result = await selectTrack(ctx, cache, selected);
          break;
        } catch (error) {
          // Only explicit non-application proof permits another Add. An
          // uncertain Add retains its pending index observation for later.
          if (error.code !== 'ADAPTER_FAILED' || !cache.pending || cache.pending.invoked
              || position === candidates.length - 1
              || ['KODI_AUTH', 'KODI_CONFIG', 'SOURCE_CHANGED'].includes(error.adapterCode)) throw error;
          cache.pending = null;
          snapshot = await observeTracks(ctx, cache);
        }
      }
    } catch (error) {
      if (!['SUBTITLE_TIMEOUT', 'SUBTITLE_BINDING', 'ADAPTER_FAILED'].includes(error.code)
          || ['KODI_AUTH', 'KODI_CONFIG', 'SOURCE_CHANGED'].includes(error.adapterCode)) throw error;
      subtitleFailure = error;
    } finally { ctx.subtitleDeadline = null; }
    if (subtitleFailure) {
      await assertPlayback(ctx, stage(ctx, settings.active, 'VIDEO_TIMEOUT'));
      const observed = ctx.subtitleObserved;
      result = resultFor(ctx, cache, observed?.enabled === true ? observed.currentIndex : null);
      result.subtitleError = subtitleFailure.code;
    }
    status(ctx, 'remote', 'Casting is ready. Opening the remote…');
    await call(ctx, options => remote.ready(result, options));
    status(ctx, 'ready', result.subtitleError ? 'Video is playing. Retry subtitles from the remote.' : 'Casting is ready.');
    return result;
  }

  async function runSubtitle(ctx) {
    const cache = cacheFor(ctx.media);
    status(ctx, 'subtitles', 'Loading the selected subtitles…');
    const snapshot = await observeTracks(ctx, cache);
    const selected = ctx.media.tracks.find(track => track.url === ctx.trackUrl);
    await loadTrack(ctx, cache, selected, snapshot);
    const result = await selectTrack(ctx, cache, selected);
    status(ctx, 'ready', 'Subtitles are ready.');
    return result;
  }

  function execute(input, { signal, isCurrentSource = () => true, knownCatalog } = {}, phase, trackUrl) {
    let media, known = [];
    try {
      media = mediaSnapshot(input, settings.tracks);
      if (phase === 'remote') {
        trackUrl = sourceUrl(trackUrl);
        if (!media.tracks.some(track => track.url === trackUrl)) throw fail('INVALID_SUBTITLE');
        if (knownCatalog !== undefined) {
          if (!Array.isArray(knownCatalog) || knownCatalog.length > 128) throw fail('INVALID_SUBTITLE');
          known = knownCatalog.map(track => {
            const url = sourceUrl(track?.url);
            if (!media.tracks.some(item => item.url === url) || (track.index !== null && (!Number.isInteger(track.index) || track.index < 0 || track.index > 1024))) throw fail('INVALID_SUBTITLE');
            return { url, index: track.index };
          });
        }
      }
    } catch (error) { return Promise.reject(error instanceof HandoffError ? error : fail('INVALID_MEDIA')); }
    current?.controller.abort();
    const ctx = { media, controller: new AbortController(), isCurrentSource, phase, trackUrl, knownCatalog: known, deadline: now() + Math.min(settings.overall, phase === 'remote' ? 60000 : settings.overall) };
    current = ctx;
    const abort = () => ctx.controller.abort();
    if (signal?.aborted) abort();
    else signal?.addEventListener('abort', abort, { once: true });
    return (phase === 'remote' ? runSubtitle(ctx) : run(ctx)).catch(error => {
      const fixed = error instanceof HandoffError ? error : fail('ADAPTER_FAILED');
      status(ctx, fixed.code === 'CANCELLED' ? 'cancelled' : 'error', fixed.message);
      throw fixed;
    }).finally(() => {
      signal?.removeEventListener('abort', abort);
      if (current === ctx) current = null;
    });
  }

  return {
    start(input, options) { return execute(input, options, 'handoff'); },
    // knownCatalog restores only explicit per-source URL/index bindings kept in
    // native RAM. Generic receiver labels are never used to guess an index.
    selectSubtitle(input, trackUrl, options) { return execute(input, options, 'remote', trackUrl); },
    cancel() { current?.controller.abort(); },
  };
}
