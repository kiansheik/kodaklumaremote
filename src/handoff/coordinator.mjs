// A JS orchestration layer for a single selected receiver. Adapters own all I/O.
// Native/WebView adapters must honor signal. For open, expectedSourceUrl binds
// media.url; for subtitles, require the receiver's active source to match it before I/O.
// JS cancellation cannot undo an already accepted command.
// receiver.launchReceiver(options); player.connect(options) -> {ready:true};
// player.open(media, options); player.status(options) -> {active, sourceUrl};
// player.getSubtitles(options) -> {tracks:[{index,url?}], enabled, currentIndex};
// player.addSubtitle(track, options); player.setSubtitle(index|'off', options);
// Optional native preparation: player.prepareSubtitle(track, options) returns an
// opaque preparedId; player.subtitleDelivery(preparedId, track, options) reports
// {downloaded:boolean} only after its complete response body reached the receiver.
// addSubtitle receives options.preparedId while track.url stays the original URL.
// remote.ready(result, options). Every options object includes AbortSignal.
// addSubtitle options.onNotSent() may report explicit proof that this request
// was never applied or queued, including a verified receiver rejection or a late
// native pre-write rejection after abort.
// Cancellation or a timeout alone is not proof and must never call onNotSent.
// options.phase is 'handoff' or 'remote'. A cold-start Open retry also includes
// onlyIfInactive:true; its adapter must reject if any video became active before
// opening. No retry is sent while an earlier Open promise is pending.
// open options.onNotSent() accepts the same explicit pre-write proof as Add.
// open options.onSettled() reports an actual native response, even when its
// receiver write outcome is unknown. JS timeout/abort alone must not call it.
// addSubtitle uses the same receipt callbacks. A settled Add remains globally
// exclusive until its registration is observed for that exact media identity;
// settlement alone cannot identify receiver subtitle entries that omit their URL.

const MESSAGES = Object.freeze({
  INVALID_MEDIA: 'The page did not provide a supported video and subtitle list.',
  INVALID_SUBTITLE: 'Choose a subtitle declared by the selected video.',
  CANCELLED: 'Casting was cancelled.',
  SOURCE_CHANGED: 'The selected video changed. Start casting again.',
  RECEIVER_START_TIMEOUT: 'LUMA Receiver did not become ready in time. Check that it is installed on the projector.',
  VIDEO_TIMEOUT: 'The projector did not start the selected video in time.',
  METADATA_UNAVAILABLE: 'Video started without its title information. Cast it again.',
  SUBTITLE_TIMEOUT: 'The projector did not load the subtitle in time.',
  HANDOFF_TIMEOUT: 'Casting setup took too long. Try again.',
  SUBTITLE_BINDING: 'The subtitle list changed unexpectedly. Start the video again.',
  SUBTITLE_DOWNLOAD: 'Could not download these subtitles. Choose the language again to retry.',
  SUBTITLE_FORMAT: 'This subtitle format is not supported.',
  SUBTITLE_INVALID: 'These subtitles could not be prepared. Choose the language again.',
  SUBTITLE_TOO_LARGE: 'This subtitle file is too large.',
  SUBTITLE_DELIVERY: 'The projector has not downloaded these subtitles. Keep the video playing and try the language again.',
  SUBTITLE_EXPIRED: 'The prepared subtitle is no longer available. Choose the language again.',
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
const TRANSIENT_ADAPTER_CODES = new Set(['RECEIVER_UNAVAILABLE', 'TIMEOUT', 'BUSY', 'RECEIVER_RESPONSE', 'RECEIVER_REJECTED']);
const SUBTITLE_ADAPTER_CODES = new Set(['SUBTITLE_DOWNLOAD', 'SUBTITLE_FORMAT', 'SUBTITLE_INVALID', 'SUBTITLE_TOO_LARGE', 'SUBTITLE_DELIVERY', 'SUBTITLE_EXPIRED']);
const preparedTicket = value => typeof value === 'string' && /^[A-Za-z0-9_-]{16,128}$/u.test(value);
function safeText(value, limit) { return typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f-\u009f]/gu, '').slice(0, limit) : ''; }
function sourceUrl(value) {
  if (typeof value !== 'string' || /[\s\u0000-\u001f\u007f\\|]/u.test(value)) throw fail('INVALID_MEDIA');
  try {
    const url = new URL(value);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) throw fail('INVALID_MEDIA');
    return url.href;
  } catch { throw fail('INVALID_MEDIA'); }
}

function canonicalContent(value) {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'object' || !['movie', 'tv'].includes(value.kind)
      || typeof value.contentId !== 'string' || !/^[1-9][0-9]{0,9}$/u.test(value.contentId)) throw fail('INVALID_MEDIA');
  const tv = value.kind === 'tv';
  if (tv && (!Number.isInteger(value.season) || value.season < 0 || value.season > 9999
      || !Number.isInteger(value.episode) || value.episode < 1 || value.episode > 9999)) throw fail('INVALID_MEDIA');
  if (!tv && (value.season !== null || value.episode !== null)) throw fail('INVALID_MEDIA');
  const key = `framextv:${value.kind}:${value.contentId}${tv ? `:${value.season}:${value.episode}` : ''}`;
  const watchUrl = `https://framextv.cc/watch/${value.contentId}${tv ? `/${value.season}/${value.episode}` : ''}`;
  if (value.key !== key || value.watchUrl !== watchUrl || typeof value.title !== 'string') throw fail('INVALID_MEDIA');
  return { key, kind: value.kind, contentId: value.contentId, season: tv ? value.season : null,
    episode: tv ? value.episode : null, watchUrl, title: safeText(value.title, 200) };
}

function observedContent(value) {
  try { return canonicalContent(value); } catch { return null; }
}

function mediaSnapshot(input, maxTracks) {
  if (!input || typeof input !== 'object' || typeof input.contentType !== 'string'
      || !/^(?:video\/[a-z0-9!#$&^_.+-]+|application\/(?:vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))$/iu.test(input.contentType)
      || (input.tracks !== undefined && !Array.isArray(input.tracks))) throw fail('INVALID_MEDIA');
  const seen = new Set();
  const tracks = [];
  // A full catalog (Library, auto-next) can list more languages than a page did, or
  // one malformed link; neither may block the video itself. Keep the first usable ones.
  for (const track of (input.tracks || []).slice(0, 256)) {
    if (tracks.length >= maxTracks) break;
    if (!track || typeof track !== 'object') continue;
    let url;
    try { url = sourceUrl(track.url ?? track.src); } catch { continue; }
    if (seen.has(url)) continue;
    seen.add(url);
    tracks.push({ url, contentType: 'text/vtt', language: safeText(track.language, 64), label: safeText(track.label, 200), active: track.active === true });
  }
  const content = canonicalContent(input.content);
  // Passed through to Open so the receiver starts at the saved position instead of
  // starting at zero and relying on a seek that may arrive before the media is seekable.
  const resume = Number.isFinite(input.resumeSeconds) && input.resumeSeconds >= 1 && input.resumeSeconds <= 17280000 ? input.resumeSeconds : 0;
  return { url: sourceUrl(input.url), contentType: input.contentType.toLowerCase(), title: safeText(input.title, 200), tracks,
    ...(content ? { content } : {}), ...(resume ? { resumeSeconds: resume } : {}) };
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
  receiver, player, remote, onStatus = () => {},
  now = () => performance.now(), wait = abortableWait,
  limits = {},
} = {}) {
  if (typeof receiver?.launchReceiver !== 'function' || typeof remote?.ready !== 'function'
      || ['connect', 'open', 'status', 'getSubtitles', 'addSubtitle', 'setSubtitle'].some(name => typeof player?.[name] !== 'function')) throw fail('ADAPTER_FAILED');
  const settings = {
    startup: bounded(limits.startupMs, 60000, 60000),
    active: bounded(limits.activeMs, 30000, 30000),
    openRetry: bounded(limits.openRetryMs, 5000, 10000, 500),
    // Subtitle readiness and registration may settle after video starts.
    subtitle: bounded(limits.subtitleMs, 30000, 30000),
    prepare: bounded(limits.prepareMs, 8000, 8000),
    delivery: bounded(limits.deliveryMs, 10000, 10000),
    overall: bounded(limits.overallMs, 120000, 120000),
    poll: bounded(limits.pollMs, 350, 2000, 100),
    tracks: Math.floor(bounded(limits.maxTracks, 128, 128)),
  };
  // Metadata only, never media bytes. Reuses confirmed bindings on a retry of
  // the still-active source. Unknown existing external tracks are not inferred.
  const sources = new Map();
  let preparedSource = null;
  let activeOpening = null;
  let activeAddition = null;
  let current = null;
  let lastMutationAt = -Infinity;

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
          return operation({ signal: operationController.signal, timeoutMs: Math.ceil(remaining), expectedSourceUrl: ctx.media.url,
            ...(ctx.media.content ? { expectedContentKey: ctx.media.content.key } : {}), phase: ctx.phase });
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
      const fixed = fail(SUBTITLE_ADAPTER_CODES.has(error?.code) ? error.code : 'ADAPTER_FAILED');
      fixed.retryable = typeof error?.code !== 'string' || TRANSIENT_ADAPTER_CODES.has(error.code);
      if (SUBTITLE_ADAPTER_CODES.has(error?.code) || ['SOURCE_CHANGED', 'RECEIVER_REJECTED', 'RECEIVER_AUTH', 'RECEIVER_CONFIG'].includes(error?.code)) fixed.adapterCode = error.code;
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

  async function settleMutation(ctx, window) {
    while (now() - lastMutationAt < 1000) {
      const remaining = Math.min(1000 - (now() - lastMutationAt), window.deadline - now());
      if (remaining <= 0) throw fail(window.code);
      await call(ctx, options => wait(remaining, options), window);
    }
  }

  function invokeMutation(operation) {
    lastMutationAt = now();
    try { return Promise.resolve(operation()).finally(() => { lastMutationAt = Math.max(lastMutationAt, now()); }); }
    catch (error) { lastMutationAt = Math.max(lastMutationAt, now()); throw error; }
  }

  async function mutate(ctx, operation, window, sourceBound = false) {
    await settleMutation(ctx, window);
    if (sourceBound) await assertPlayback(ctx, window);
    return call(ctx, options => invokeMutation(() => operation(options)), window);
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
      const playback = await read(ctx, options => player.status(options), window);
      if (playback?.active && playback.sourceUrl === ctx.media.url) {
        if (!ctx.media.content || observedContent(playback.content)?.key === ctx.media.content.key) return;
        throw fail('SOURCE_CHANGED');
      }
      if (!waitForActive || playback?.active !== false) throw fail('SOURCE_CHANGED');
      await pause(ctx, window);
    }
  }

  async function subtitles(ctx, window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT')) {
    while (true) {
      const snapshot = await read(ctx, options => player.getSubtitles(options), window);
      await assertPlayback(ctx, window, true);
      if (snapshot?.active === false) { await pause(ctx, window); continue; }
      if (!snapshot || !Array.isArray(snapshot.tracks) || snapshot.tracks.length > 128) throw fail('SUBTITLE_BINDING');
      const indices = new Set();
      for (const track of snapshot.tracks) {
        if (!Number.isInteger(track?.index) || track.index < 0 || track.index > 1024 || indices.has(track.index)) throw fail('SUBTITLE_BINDING');
        indices.add(track.index);
      }
      return snapshot;
    }
  }

  async function finishPending(ctx, cache, snapshot, window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT')) {
    if (!cache.pending) return snapshot;
    const pending = cache.pending;
    while (true) {
      if (!pending.invoked) {
        if (cache.pending === pending) cache.pending = null;
        if (activeAddition === pending) activeAddition = null;
        return snapshot;
      }
      const added = snapshot.tracks.filter(track => !pending.before.has(track.index));
      if (added.length > 1) throw fail('SUBTITLE_BINDING');
      if (added.length === 1) {
        const track = added[0];
        if (track.url !== undefined && track.url !== pending.url) throw fail('SUBTITLE_BINDING');
        pending.boundIndex = track.index;
        cache.bindings.set(pending.url, track.index);
        cache.retiredIndices.get(pending.url)?.delete(track.index);
        // An unknown Add retains its own receipt identity even if navigation
        // cleared reusable tickets. Binding it cannot skip delivery verification.
        if (pending.preparedId) cache.prepared.set(pending.url, pending.preparedId);
        if (cache.pending === pending) cache.pending = null;
        if (activeAddition === pending) activeAddition = null;
        return snapshot;
      }
      await pause(ctx, window);
      snapshot = await subtitles(ctx, window);
    }
  }

  function mediaIdentity(media) {
    // A provider may reuse one HLS URL for multiple episodes. Subtitle
    // receipts and receiver indices belong to the canonical item, not merely the
    // transport URL. Legacy captures without catalog identity stay URL-only.
    return `${media.url}\n${media.content?.key || ''}`;
  }

  function attemptIdentity(attempt) {
    return attempt ? `${attempt.sourceUrl}\n${attempt.contentKey || ''}` : '';
  }

  function safeOpening(attempt) {
    return !attempt?.invoked || attempt.unsent || (attempt.nativeSettled && attempt.playbackObserved);
  }

  function cacheFor(media) {
    const identity = mediaIdentity(media);
    let cache = sources.get(identity);
    if (!cache) { cache = { bindings: new Map(), prepared: new Map(), retiredTickets: new Set(), retiredIndices: new Map(), pending: null, opening: null }; sources.set(identity, cache); }
    // Never erase the only receipt for an Add/Open whose receiver outcome is
    // still unknown. Prefer four hot identities, with a bounded safety reserve
    // for unresolved mutations rather than authorizing a duplicate write.
    for (const [key, candidate] of sources) {
      if (sources.size <= 4) break;
      const opening = candidate.opening;
      const uncertainOpen = opening?.invoked && !opening.unsent
        && !(opening.nativeSettled && opening.playbackObserved);
      if (key !== identity && !candidate.pending?.invoked && !uncertainOpen) sources.delete(key);
    }
    if (sources.size > 16) {
      sources.delete(identity);
      throw fail('ADAPTER_FAILED');
    }
    return cache;
  }

  function resultFor(ctx, cache, selectedIndex) {
    const catalog = ctx.media.tracks.map(track => ({
      url: track.url, language: track.language, label: track.label,
      index: cache.bindings.get(track.url) ?? null,
      ...(cache.prepared.has(track.url) ? { preparedId: cache.prepared.get(track.url) } : {}),
    }));
    const tracks = catalog.filter(track => track.index !== null)
      .map(({ index, language, label }) => ({ index, language, label }));
    const selected = tracks.find(track => track.index === selectedIndex);
    return { subtitleCount: tracks.length, selectedLanguage: selected ? language(selected) : null, selectedIndex: selected?.index ?? null, tracks, catalog };
  }

  async function observeTracks(ctx, cache) {
    // A cancelled Add for another canonical item may still be executing in
    // the receiver. Its eventual anonymous index cannot be reconciled against this
    // item's list, so only explicit proof that it was not sent can release it.
    const foreign = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
    while (activeAddition && activeAddition.cache !== cache) await pause(ctx, foreign);
    let snapshot = await subtitles(ctx);
    snapshot = await finishPending(ctx, cache, snapshot);
    const present = new Set(snapshot.tracks.map(track => track.index));
    for (const [url, index] of cache.bindings) {
      if (!present.has(index)) { cache.bindings.delete(url); cache.prepared.delete(url); }
      else {
        const observed = snapshot.tracks.find(track => track.index === index);
        if (observed.url !== undefined && observed.url !== url) throw fail('SUBTITLE_BINDING');
      }
    }
    const claimed = new Map([...cache.bindings].map(([url, index]) => [index, url]));
    const identified = ctx.media.tracks.flatMap(track => {
      const observed = snapshot.tracks.find(item => item.url === track.url && !cache.retiredIndices.get(track.url)?.has(item.index));
      return observed ? [{ url: track.url, index: observed.index }] : [];
    });
    for (const track of [...identified, ...(ctx.knownCatalog || [])]) {
      if (cache.retiredTickets.has(track.preparedId) || !track.preparedId && cache.retiredIndices.get(track.url)?.has(track.index)) continue;
      if (track.index === null || !present.has(track.index)) continue;
      const observed = snapshot.tracks.find(item => item.index === track.index);
      if ((observed.url !== undefined && observed.url !== track.url)
          || (claimed.has(track.index) && claimed.get(track.index) !== track.url)) throw fail('SUBTITLE_BINDING');
      if (!cache.bindings.has(track.url)) { cache.bindings.set(track.url, track.index); claimed.set(track.index, track.url); }
      if (track.preparedId && cache.bindings.get(track.url) === track.index && !cache.prepared.has(track.url)) cache.prepared.set(track.url, track.preparedId);
    }
    return snapshot;
  }

  async function loadTrack(ctx, cache, track, snapshot) {
    if (!track || cache.bindings.has(track.url)) return snapshot;
    const existing = snapshot.tracks.find(item => item.url === track.url && !cache.retiredIndices.get(track.url)?.has(item.index));
    if (existing) { cache.bindings.set(track.url, existing.index); return snapshot; }
    let preparedId = cache.prepared.get(track.url);
    if (typeof player.prepareSubtitle === 'function' && !preparedId) {
      if (typeof player.subtitleDelivery !== 'function') throw fail('SUBTITLE_DELIVERY');
      const preparation = stage(ctx, settings.prepare, 'SUBTITLE_DOWNLOAD');
      await assertPlayback(ctx, preparation);
      let prepared;
      try { prepared = await call(ctx, options => player.prepareSubtitle(track, options), preparation); }
      catch (error) { if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED'); throw error; }
      if (!preparedTicket(prepared?.preparedId)) throw fail('SUBTITLE_INVALID');
      preparedId = prepared.preparedId;
      cache.prepared.set(track.url, preparedId);
    }
    // Preparation is read-only. A failed/cancelled download must not create an
    // uncertain Add that blocks a later explicit retry.
    if (activeAddition && activeAddition !== cache.pending) throw fail('SUBTITLE_TIMEOUT');
    const pending = { cache, url: track.url, preparedId, before: new Set(snapshot.tracks.map(item => item.index)), invoked: false,
      nativeSettled: false, unsent: false };
    cache.pending = pending;
    const window = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
    let rejected;
    try {
      await mutate(ctx, options => {
        if (activeAddition && activeAddition !== pending) throw fail('SUBTITLE_TIMEOUT');
        activeAddition = pending;
        pending.invoked = true;
        return player.addSubtitle(track, {
          ...options,
          ...(preparedId ? { preparedId } : {}),
          onNotSent() {
            pending.invoked = false;
            pending.unsent = true;
            cache.prepared.delete(pending.url);
            if (pending.boundIndex !== undefined && cache.bindings.get(pending.url) === pending.boundIndex) cache.bindings.delete(pending.url);
            if (cache.pending === pending) cache.pending = null;
            if (activeAddition === pending) activeAddition = null;
          },
          onSettled() { pending.nativeSettled = true; },
        });
      }, window, true);
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

  /** Renew a known delivery lease before selecting its already-loaded index.
   * Only the adapter's explicit unavailable-ticket proof can retire it. */
  async function preflightTrack(ctx, cache, track) {
    const preparedId = cache.prepared.get(track.url), index = cache.bindings.get(track.url);
    if (!preparedId || !Number.isInteger(index)) return;
    if (typeof player.subtitleDelivery !== 'function') throw fail('SUBTITLE_DELIVERY');
    const window = stage(ctx, settings.delivery, 'SUBTITLE_DELIVERY');
    await assertPlayback(ctx, window);
    try {
      const receipt = await call(ctx, options => player.subtitleDelivery(preparedId, track, options), window);
      if (typeof receipt?.downloaded !== 'boolean') throw fail('SUBTITLE_DELIVERY');
    } catch (error) {
      if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED');
      if (error.code !== 'SUBTITLE_EXPIRED' && error.adapterCode !== 'SUBTITLE_EXPIRED') throw error;
      // observeTracks finished any unknown Add first. Expiry is not permission
      // to erase an unobserved mutation or reapply it behind the caller's back.
      if (cache.pending?.invoked) throw error;
      cache.bindings.delete(track.url); cache.prepared.delete(track.url);
      cache.retiredTickets.add(preparedId);
      while (cache.retiredTickets.size > 256) cache.retiredTickets.delete(cache.retiredTickets.values().next().value);
      let retired = cache.retiredIndices.get(track.url);
      if (!retired) { retired = new Set(); cache.retiredIndices.set(track.url, retired); }
      retired.add(index);
    }
  }

  async function selectTrack(ctx, cache, track) {
    const index = track ? cache.bindings.get(track.url) : null;
    if (track && !Number.isInteger(index)) throw fail('SUBTITLE_BINDING');
    status(ctx, 'selecting', track ? 'Selecting the chosen subtitles…' : 'Keeping subtitles off…');
    let rejected;
    try { await mutate(ctx, options => player.setSubtitle(index ?? 'off', options), stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT'), true); }
    catch (error) {
      if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
      rejected = error;
    }
    const selection = stage(ctx, settings.subtitle, 'SUBTITLE_TIMEOUT');
    while (true) {
      const observed = await subtitles(ctx, selection);
      if (track ? observed.enabled === true && observed.currentIndex === index : observed.enabled === false) break;
      if (rejected?.adapterCode === 'RECEIVER_REJECTED') throw rejected;
      await pause(ctx, selection);
    }
    const preparedId = track && cache.prepared.get(track.url);
    if (preparedId) {
      if (typeof player.subtitleDelivery !== 'function') throw fail('SUBTITLE_DELIVERY');
      const delivery = stage(ctx, settings.delivery, 'SUBTITLE_DELIVERY');
      while (true) {
        await assertPlayback(ctx, delivery);
        let receipt;
        try { receipt = await call(ctx, options => player.subtitleDelivery(preparedId, track, options), delivery); }
        catch (error) { if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED'); throw error; }
        if (receipt?.downloaded === true) break;
        if (receipt?.downloaded !== false) throw fail('SUBTITLE_DELIVERY');
        await pause(ctx, delivery);
      }
    }
    return resultFor(ctx, cache, index);
  }

  function sendOpen(ctx, cache, window, onlyIfInactive) {
    const attempt = { owner: ctx, sourceUrl: ctx.media.url, contentKey: ctx.media.content?.key || null,
      invoked: false, settled: false, acknowledged: false, acceptedAt: null, unsent: false };
    const forgetCompletedPlayback = () => {
      if (attempt.nativeSettled && attempt.playbackObserved) {
        if (cache.opening === attempt) cache.opening = null;
        if (activeOpening === attempt) activeOpening = null;
      }
    };
    const observed = new Promise(resolve => { attempt.observed = () => {
      attempt.playbackObserved = true;
      forgetCompletedPlayback();
      resolve();
    }; });
    cache.opening = attempt;
    activeOpening = attempt;
    attempt.completion = call(ctx, options => {
      // Keep settlement independently of the cancelled JS run: a new run must
      // not overlap an old native request whose receiver outcome is unknown.
      return Promise.resolve().then(() => {
        check(ctx); attempt.invoked = true;
        return invokeMutation(() => player.open(ctx.media, {
          ...options, ...(onlyIfInactive ? { onlyIfInactive: true } : {}),
          onSettled() { attempt.nativeSettled = true; forgetCompletedPlayback(); },
          onNotSent() {
            attempt.unsent = true; attempt.invoked = false;
            attempt.settled = true; attempt.acknowledged = false;
            if (cache.opening === attempt) cache.opening = null;
            if (activeOpening === attempt) activeOpening = null;
          },
        }));
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
    let previousContentKey = initial?.active ? observedContent(initial.content)?.key ?? null : null;
    let supersededPlayback = null;
    let attempt = activeOpening || cache.opening;
    let sent = 1;
    if (!attempt?.invoked || (safeOpening(attempt) && (previousSource !== null || !attempt.acknowledged))) {
      attempt = sendOpen(ctx, cache, active, false);
    }
    let inactiveSince = null;
    let selectedWithoutMetadata = false;
    while (true) {
      let playback;
      try { playback = await call(ctx, options => player.status(options), active); }
      catch (error) {
        if (selectedWithoutMetadata && error.code === 'VIDEO_TIMEOUT') throw fail('METADATA_UNAVAILABLE');
        if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED');
        if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
      }
      if (playback?.active) {
        const playbackContentKey = observedContent(playback.content)?.key ?? null;
        const previousAttemptPlayback = attempt?.owner !== ctx && playback.sourceUrl === attempt?.sourceUrl
          && (attempt.contentKey ? playbackContentKey === attempt.contentKey : playbackContentKey === null);
        const knownSupersededPlayback = supersededPlayback && playback.sourceUrl === supersededPlayback.sourceUrl
          && (supersededPlayback.contentKey ? playbackContentKey === supersededPlayback.contentKey : playbackContentKey === null);
        if (previousAttemptPlayback) {
          supersededPlayback = { sourceUrl: attempt.sourceUrl, contentKey: attempt.contentKey };
          attempt.observed?.();
        }
        const expectedPreviousPlayback = previousAttemptPlayback || knownSupersededPlayback;
        if (playback.sourceUrl === ctx.media.url) {
          if (!ctx.media.content) {
            const sameAttempt = !attempt || attemptIdentity(attempt) === mediaIdentity(ctx.media);
            if (sameAttempt) attempt?.observed?.();
            if (sameAttempt || safeOpening(attempt)) return;
          }
          const content = observedContent(playback.content);
          if (ctx.media.content && content?.key === ctx.media.content.key) {
            const sameAttempt = !attempt || attemptIdentity(attempt) === mediaIdentity(ctx.media);
            if (sameAttempt) attempt?.observed?.();
            // Exact playback selected by somebody else does not neutralize an
            // unresolved Open for another canonical item; that older command
            // can still land after we report B ready.
            if (sameAttempt || safeOpening(attempt)) return;
          }
          // A receiver can expose the old item's identity only after the initial
          // contentless status. Adopt at most one stable non-target key as the
          // pre-Open identity; a second different key is a real source race.
          // A cancelled attempt for another canonical item may land after the
          // new run has already observed its selected item. Observe that old
          // receipt and wait until it is safe to issue the replacement Open;
          // do not misclassify the expected late identity as a third-party
          // source change.
          if (!expectedPreviousPlayback) {
            if (content && previousContentKey === null) previousContentKey = content.key;
            else if (content && content.key !== previousContentKey) throw fail('SOURCE_CHANGED');
            if (!content) selectedWithoutMetadata = true;
          }
        } else if (!expectedPreviousPlayback && (!previousSource || playback.sourceUrl !== previousSource)) throw fail('SOURCE_CHANGED');
        inactiveSince = null;
        const completedPrevious = attempt?.owner !== ctx && safeOpening(attempt);
        if (completedPrevious && sent < 2 && now() - lastMutationAt >= 1000) {
          sent--;
          sent++;
          attempt = sendOpen(ctx, cache, active, false);
        }
      }
      if (attempt?.owner === ctx && attempt.unsent) {
        await attempt.completion;
        throw attempt.error || fail('ADAPTER_FAILED');
      }
      if (attempt?.owner === ctx && attempt.error && (attempt.unsent || attempt.error.code !== 'ADAPTER_FAILED'
          || attempt.error.retryable !== true)) throw attempt.error;
      if (playback?.active === false && !playback.sourceUrl) {
        inactiveSince ??= now();
        const completedPrevious = attempt?.owner !== ctx && safeOpening(attempt);
        const retryAcknowledged = attempt?.owner === ctx && attempt.settled && (attempt.unsent || (attempt.acknowledged
          && now() - attempt.acceptedAt >= settings.openRetry && now() - inactiveSince >= settings.poll * 2));
        if (sent < 2 && now() - lastMutationAt >= 1000 && (completedPrevious || retryAcknowledged)) {
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
      try { await pause(ctx, active); }
      catch (error) {
        if (selectedWithoutMetadata && error.code === 'VIDEO_TIMEOUT') throw fail('METADATA_UNAVAILABLE');
        throw error;
      }
    }
  }

  async function run(ctx) {
    const startup = stage(ctx, settings.startup, 'RECEIVER_START_TIMEOUT');
    const connect = async () => {
      try { return (await call(ctx, options => player.connect(options), startup))?.ready === true; }
      catch (error) {
        if (error.adapterCode === 'SOURCE_CHANGED') throw fail('SOURCE_CHANGED');
        if (error.code !== 'ADAPTER_FAILED' || error.retryable !== true) throw error;
        return false;
      }
    };
    // LUMA Receiver normally runs from boot and brings itself on screen when a
    // video opens, so only launch it over EShare when it does not answer.
    status(ctx, 'waiting', 'Connecting to LUMA Receiver…');
    if (!await connect()) {
      status(ctx, 'launching', 'Opening LUMA Receiver on the projector…');
      await mutate(ctx, options => receiver.launchReceiver(options), startup);
      status(ctx, 'waiting', 'Waiting for LUMA Receiver…');
      await settleMutation(ctx, startup);
      while (!await connect()) await pause(ctx, startup);
    }
    const initial = await read(ctx, options => player.status(options), startup);
    const cache = cacheFor(ctx.media);
    const initialContent = observedContent(initial?.content);
    const conflictingOpening = activeOpening && attemptIdentity(activeOpening) !== mediaIdentity(ctx.media)
      && !safeOpening(activeOpening);
    const alreadySelected = initial?.active && initial.sourceUrl === ctx.media.url
      && (!ctx.media.content || initialContent?.key === ctx.media.content.key) && !conflictingOpening;
    if (alreadySelected) cache.opening?.observed();
    if (!alreadySelected) {
      cache.bindings.clear(); cache.prepared.clear(); cache.retiredTickets.clear(); cache.retiredIndices.clear(); cache.pending = null;
      status(ctx, 'opening', 'Starting the selected video…');
      await openVideo(ctx, cache, initial);
    }
    // Video readiness is independent of subtitle metadata. Keep every captured
    // language available; only an explicit subtitle selection performs I/O.
    const result = resultFor(ctx, cache, null);
    status(ctx, 'remote', 'Casting is ready. Opening the remote…');
    await call(ctx, options => remote.ready(result, options));
    status(ctx, 'ready', 'Video is playing. Choose subtitles from the remote.');
    return result;
  }

  async function runSubtitle(ctx) {
    const cache = cacheFor(ctx.media);
    status(ctx, 'subtitles', 'Loading the selected subtitles…');
    const snapshot = await observeTracks(ctx, cache);
    const selected = ctx.media.tracks.find(track => track.url === ctx.trackUrl);
    await preflightTrack(ctx, cache, selected);
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
          const tickets = new Map();
          known = knownCatalog.map(track => {
            const url = sourceUrl(track?.url);
            if (!media.tracks.some(item => item.url === url) || (track.index !== null && (!Number.isInteger(track.index) || track.index < 0 || track.index > 1024))) throw fail('INVALID_SUBTITLE');
            if (track.preparedId !== undefined && (!preparedTicket(track.preparedId) || tickets.has(track.preparedId) && tickets.get(track.preparedId) !== url)) throw fail('INVALID_SUBTITLE');
            if (track.preparedId) tickets.set(track.preparedId, url);
            return { url, index: track.index, ...(track.preparedId ? { preparedId: track.preparedId } : {}) };
          });
        }
      }
    } catch (error) { return Promise.reject(error instanceof HandoffError ? error : fail('INVALID_MEDIA')); }
    const identity = mediaIdentity(media);
    if (preparedSource !== identity) {
      for (const cache of sources.values()) { cache.prepared.clear(); cache.bindings.clear(); }
      preparedSource = identity;
    }
    current?.controller.abort();
    const ctx = { media, controller: new AbortController(), isCurrentSource, phase, trackUrl, knownCatalog: known, deadline: now() + Math.min(settings.overall, phase === 'remote' ? 60000 : settings.overall) };
    current = ctx;
    const abort = () => ctx.controller.abort();
    if (signal?.aborted) abort();
    else signal?.addEventListener('abort', abort, { once: true });
    return (phase === 'remote' ? runSubtitle(ctx) : run(ctx)).catch(error => {
      const fixed = error instanceof HandoffError ? error : fail('ADAPTER_FAILED');
      if (fixed.code === 'SOURCE_CHANGED' || fixed.adapterCode === 'SOURCE_CHANGED' || fixed.code === 'SUBTITLE_BINDING') {
        const cache = sources.get(mediaIdentity(ctx.media));
        if (cache) { cache.prepared.clear(); cache.bindings.clear(); }
      }
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
