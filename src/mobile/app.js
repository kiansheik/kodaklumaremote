import { createHandoffCoordinator } from '../handoff/coordinator.mjs';
import { syncStep } from '../handoff/sync.mjs';

const $ = selector => document.querySelector(selector);
const DEFAULT_SITE = 'https://framextv.cc/watch/284833/1/1';
const TV_TEXT_ACK = 'TV acknowledged the keys. Check the address in MSX Setup before selecting Go / OK.';
const TV_BROWSER_ACK = 'TV acknowledged the keys. Check the Internet browser address before selecting Go / OK.';
const TV_BROWSER_WAIT = 'Launch requested. Waiting for the TV to connect…';
const TV_BROWSER_BOOKMARK = 'Open Internet on the TV, then choose your saved LUMA bookmark. The receiver is not connected yet.';
const MSX_SETUP_HINTS = Object.freeze({
  HTTPS_REQUEST: 'The TV is trying a secure connection. In Media Station X setup, leave the padlock open.',
  ORIGIN_REJECTED: 'The TV reached this phone, but its app connection was rejected. Share the error shown on the TV.',
  PAIRING_EXPIRED: 'Tap Restart TV setup, then try the setup again on the TV.',
  BOOTSTRAP_READY: 'The TV loaded the setup. Open “Connect this TV” on the TV next.',
  HELLO_READY: 'The TV receiver reached this phone.',
  POLL_READY: 'The TV receiver checked its connection.',
  STATE_READY: 'The TV returned receiver status.',
  ACK_READY: 'The TV returned a command receipt.',
});
const TV_TEXT_ERRORS = Object.freeze({
  TEXT_PAIRING_REQUIRED: 'Enable address entry once with the TV’s PIN.',
  TEXT_PAIRING_PENDING: 'Enter the four-digit PIN shown on the TV.',
  TEXT_PAIRING_EXPIRED: 'The TV PIN expired. Request a new PIN.',
  TEXT_ALREADY_PAIRED: 'Address entry is already enabled on this phone.',
  TEXT_INVALID_PIN: 'Enter the four-digit PIN shown on the TV.',
  TEXT_ADDRESS_UNAVAILABLE: 'Reconnect your paired TV remote, then try address entry again.',
  TEXT_UNSUPPORTED: 'This TV is not offering address entry. Enter the shown address manually.',
  TEXT_AUTH_REQUIRED: 'Enable address entry again with the TV’s PIN.',
  TEXT_CERTIFICATE_CHANGED: 'The TV connection changed. Pair address entry again.',
  TEXT_REJECTED: 'The TV did not accept address entry. Select and clear its address field before retrying.',
  TEXT_TIMEOUT: 'Entry may be incomplete. Clear the TV’s address field before trying again.',
  TEXT_UNREACHABLE: 'Cannot reach the TV. Check its power and Wi-Fi.',
  TEXT_STORAGE: 'Permission could not be saved on this phone. Try again.',
  TEXT_INVALID_INPUT: 'Check the TV PIN and try again.',
  TEXT_BUSY: 'Wait for address entry to finish.',
  TEXT_PARTIAL: 'Entry may be incomplete. Clear the TV’s address field before trying again.',
  TEXT_CANCELLED: 'Address entry stopped. Clear the TV’s address field before trying again.',
  TEXT_BROWSER_UNSUPPORTED: 'Automatic browser launch is unavailable. Use Enter link manually with the TV browser address bar selected.',
});
const errors = {
  ...TV_TEXT_ERRORS,
  NATIVE_UNAVAILABLE: 'Open this page in the LUMA app to use the remote.',
  TIMEOUT: 'The projector did not reply. Check its connection and try again.',
  CONNECTION_FAILED: 'Could not connect to the projector. Check its power and Wi-Fi.',
  NOT_CONNECTED: 'Connect to your projector first.',
  UNAUTHORIZED: 'LUMA Receiver no longer recognizes this phone. Pair it again.',
  PAIRING_REQUIRED: 'This projector requires pairing that this app does not support yet.',
  PAIRING_UNKNOWN: 'The projector pairing setting could not be verified.',
  APP_NOT_AVAILABLE: 'That app is not in the projector’s current app list.',
  INVALID_INPUT: 'Check the entered details and try again.',
  INVALID_HOST: 'Enter the projector’s local IP address.',
  INVALID_RESPONSE: 'The projector returned an unsupported response. Reconnect and try again.',
  INVALID_TEXT: 'Use a single line of up to 1000 characters.',
  RECEIVER_CONFIG: 'Pair this phone with LUMA Receiver on the projector first.',
  PAIRING_CODE_WRONG: 'That code does not match the one on the projector. Check it and try again.',
  PAIRING_EXPIRED: 'The pairing code expired. Show a new code on the projector.',
  RECEIVER_AUTH: 'LUMA Receiver no longer recognizes this phone. Pair it again.',
  RECEIVER_UNAVAILABLE: 'Could not reach LUMA Receiver. Check that the projector is on and on your home Wi-Fi.',
  RECEIVER_RESPONSE: 'LUMA Receiver did not accept this control command. Try refreshing playback.',
  RECEIVER_REJECTED: 'LUMA Receiver rejected this request before applying it. Try casting again.',
  METADATA_UNAVAILABLE: 'Video started without its title information. Cast it again.',
  NO_VIDEO: 'Nothing is playing on the projector. Choose a video in Browse first.',
  INVALID_MEDIA: 'The page did not expose a supported video. Open its player and try Cast again.',
  INVALID_SUBTITLE: 'Choose an available subtitle track or turn subtitles off.',
  SUBTITLE_DOWNLOAD: 'The subtitle could not be downloaded. Choose another language or try again.',
  SUBTITLE_FORMAT: 'This subtitle format is not supported. Choose another subtitle.',
  SUBTITLE_INVALID: 'This subtitle file has no valid timed captions. Choose another subtitle.',
  SUBTITLE_TOO_LARGE: 'This subtitle file is too large. Choose another subtitle.',
  SUBTITLE_EXPIRED: 'The prepared subtitle is no longer available. Choose it again.',
  SUBTITLE_FAILED: 'The subtitle could not load. Video playback is unchanged; choose another language or try again.',
  SUBTITLE_DELIVERY: 'The projector could not retrieve the prepared subtitles. Check that the phone and projector share the same Wi-Fi.',
  CANNOT_SEEK: 'This video cannot seek.',
  BUSY: 'The remote is busy. Wait a moment and try again.',
  CANCELLED: 'Casting was cancelled.',
  SOURCE_UNAVAILABLE: 'No supported video was found. Open its player and try Cast again.',
  INVALID_REQUEST: 'That app action is unavailable. Try again.',
  SOURCE_CHANGED: 'The selected video changed. Choose it again in Browse.',
  STORAGE_UNAVAILABLE: 'Settings could not be saved on this phone. Try setup again.',
  SETUP_TIMEOUT: 'LUMA Receiver did not answer. Check that it is installed on the projector and try again.',
  MSX_NOT_CONNECTED: 'Open your TV receiver from TV connection setup.',
  MSX_TIMEOUT: 'The TV did not finish this request. Refresh its connection and check the TV screen.',
  MSX_PLAYBACK: 'The TV could not play this video. Check the TV screen and try another source.',
  MSX_SUBTITLE: 'The TV could not load the selected subtitles. Choose another language and try again.',
  MSX_NETWORK: 'Could not start the TV connection. Check this phone’s Wi-Fi and try TV setup again.',
  MSX_PAIRING: 'Allow your TV in TV connection setup before using its remote.',
  MSX_UNAVAILABLE: 'The TV receiver is not connected. Open it from TV connection setup and refresh.',
  MSX_REJECTED: 'The TV could not finish this request. Check playback or try another subtitle language.',
  MATTER_UNAVAILABLE: 'TV pairing is unavailable on this device.',
  MATTER_PAIRING: 'Pairing did not finish. Check that the TV is ready to add a controller and try again.',
  MATTER_CODE: 'Enter the Matter setup code shown on the TV, or scan its QR code.',
  MATTER_NOT_CONNECTED: 'Could not reach the paired TV. Check its power and Wi-Fi, then reconnect.',
  MATTER_UNSUPPORTED: 'This TV does not support that control.',
  MATTER_BUSY: 'The TV connection is busy. Wait a moment and try again.',
  MATTER_REJECTED: 'The TV did not accept this command.',
  MATTER_TIMEOUT: 'The TV did not reply in time. Pairing already saved on this phone is kept.',
  MATTER_STORAGE: 'The TV connection could not be saved securely on this phone.',
  MATTER_ALREADY_PAIRED: 'This phone is already paired. Use Reconnect for the saved TV.',
  MATTER_NOT_PAIRED: 'Pair your TV remote first.',
  MATTER_SCAN_CANCELLED: 'Scanning cancelled.',
  CAMERA_DENIED: 'Camera access is needed to scan. You can enter the setup code instead.',
};
const state = {
  native: false, busy: false, handoff: false, configured: false, connected: false, nativeConnected: false, receiverConnected: false,
  host: '', name: '', playback: null, activeMedia: null, pendingMedia: null,
  tracks: [], catalog: [], selectedIndex: null, subtitlesEnabled: false, nextEpisode: null, apps: [],
  appPage: 0, subtitlePage: 0, sheet: null, sourceGeneration: 0, sourceChanged: false, autoStart: false, setupError: null,
  jobToken: null, flowController: null, setupFlow: false, volume: null, skipNextLaunch: false, subtitleFlow: false, subtitleError: null,
  pairingId: null, subtitleScale: 1,
  settings: { hasReceiver: false, hasPairing: false, autoConnect: true },
  target: 'luma', pendingTarget: 'luma',
  tvSetup: 'remote', tvEntry: 'browser', tvBrowserManual: false, matterShareTimer: null,
  matter: { available: false, paired: false, connected: false, pairing: false, capabilities: {}, volume: null, inputs: [] },
  philips: { connected: false, host: '', name: 'Philips TV', startupUrl: '', awaitingApproval: false, pendingHost: '', playback: null, activeMedia: null, tracks: [], catalog: [], selectedIndex: null, subtitlesEnabled: false, nextEpisode: null, volume: null, subtitlePage: 0 },
};
const pending = new Map();
let requestId = 0;
let flowId = 0;
let lastKeyTime = -Infinity;
let returnFocus;
let keyboardCompact = false;
let msxPolling = false;
let deferredNativeTarget = null;
let changingTarget = false;
let pendingVolume = null;
let volumeTimer = null;
let volumeFlight = false;
let scrubPosition = null;
let scrubPreviewTimer = null;
let scrubPreviewInFlight = false, scrubPreviewWanted = null, scrubPreviewShown = null;
// Frames already received for the current item, so fast sweeps render locally at once.
const scrubFrameCache = new Map();
let scrubFrameCacheKey = null;
function cacheScrubFrame(context, position, dataUri) {
  const key = `${context.source}\n${context.contentKey}`;
  if (key !== scrubFrameCacheKey) { scrubFrameCache.clear(); scrubFrameCacheKey = key; }
  scrubFrameCache.delete(position);
  scrubFrameCache.set(position, dataUri);
  if (scrubFrameCache.size > 600) scrubFrameCache.delete(scrubFrameCache.keys().next().value);
}
// Pulls generated frames into the page in bulk (nearest to {@code around} first),
// so a sweep can show every frame that exists without a round trip per position.
let scrubBatchInFlight = false, scrubBatchTimer = null;
function warmScrubFrames(around, again = true) {
  const context = currentScrubPreviewContext();
  if (!context || scrubBatchInFlight || !state.native) return;
  const key = `${context.source}\n${context.contentKey}`;
  if (key !== scrubFrameCacheKey) { scrubFrameCache.clear(); scrubFrameCacheKey = key; }
  scrubBatchInFlight = true;
  void rpc('preview.batch', { expectedSourceUrl: context.source, contentKey: context.contentKey, around: Math.max(0, Math.floor(around)), have: [...scrubFrameCache.keys()], limit: 48 }, { timeoutMs: 5000 })
    .then(result => {
      for (const frame of Array.isArray(result?.frames) ? result.frames : []) {
        const dataUri = scrubPreviewDataUri(frame?.dataUri);
        if (dataUri && Number.isFinite(frame.position)) cacheScrubFrame(context, frame.position, dataUri);
      }
      if (scrubPreviewVisible && Number.isFinite(scrubPosition)) {
        const local = nearestCachedFrame(context, scrubPosition);
        if (local) renderScrubPreview(scrubPosition, local.dataUri, local.position);
      }
      // Keep pulling while there is more and the viewer is scrubbing.
      if (again && result?.more && scrubPreviewVisible) setTimeout(() => warmScrubFrames(scrubPosition ?? around), 0);
    })
    .catch(() => {})
    .finally(() => { scrubBatchInFlight = false; });
}
function scheduleScrubWarmup() {
  clearTimeout(scrubBatchTimer);
  scrubBatchTimer = setTimeout(() => {
    const playback = receiver().playback;
    if (state.page === 'remote' || !$('#page-remote').hidden) warmScrubFrames(playback?.positionSeconds ?? 0, false);
    scheduleScrubWarmup();
  }, 3000);
}
function nearestCachedFrame(context, seconds) {
  if (!context || `${context.source}\n${context.contentKey}` !== scrubFrameCacheKey) return null;
  let best = null;
  for (const [position, dataUri] of scrubFrameCache) if (!best || Math.abs(position - seconds) < Math.abs(best.position - seconds)) best = { position, dataUri };
  return best;
}
let scrubPreviewSerial = 0;
let scrubPreviewVisible = false;
let scrubPreviewContext = null;
let historySelection = 0;
let historyController = null;
let libraryItems = [], libraryDetail = null, librarySelected = null, libraryNext = null;
let libraryLoading = false, libraryOffset = 0, libraryLimit = 50, libraryHasMore = false, libraryQuery = '';
const VOLUME_COOLDOWN_MS = 1000;
// Frames are local lookups now; only coalesce bursts within one display frame.
const SCRUB_PREVIEW_DELAY_MS = 16;
const SEEK_HISTORY_LIMIT = 20;
const fineScrub = { active: false, pointer: null, lastX: 0, lastTime: 0, lastFast: 0, value: 0, gain: 1, from: null };
const MAX_SCRUB_PREVIEW_BYTES = 128 * 1024;

function contentDescriptor(value) {
  if (!value || !['movie', 'tv'].includes(value.kind) || typeof value.contentId !== 'string' || !/^[1-9][0-9]{0,9}$/.test(value.contentId)) return null;
  const tv = value.kind === 'tv';
  if (tv && (!Number.isInteger(value.season) || value.season < 0 || value.season > 9999 || !Number.isInteger(value.episode) || value.episode < 1 || value.episode > 9999)) return null;
  const suffix = tv ? `/${value.season}/${value.episode}` : '';
  const key = `framextv:${value.kind}:${value.contentId}${tv ? `:${value.season}:${value.episode}` : ''}`;
  if (value.key !== key || value.watchUrl !== `https://framextv.cc/watch/${value.contentId}${suffix}`) return null;
  return { key, kind: value.kind, contentId: value.contentId, season: tv ? value.season : null, episode: tv ? value.episode : null, watchUrl: value.watchUrl, title: title(value.title) };
}
function mediaMetadata(media) {
  const content = contentDescriptor(media?.content);
  const resumeSeconds = Number.isFinite(media?.resumeSeconds) && media.resumeSeconds >= 0 && media.resumeSeconds <= 17280000 ? media.resumeSeconds : 0;
  return { ...(content ? { content } : {}), ...(resumeSeconds > 0 ? { resumeSeconds } : {}) };
}
function currentScrubPreviewContext() {
  const remote = receiver();
  const snapshot = remote.playback;
  const source = remote.activeMedia?.url;
  const content = contentDescriptor(remote.activeMedia?.content);
  if (!snapshot?.active || snapshot.canSeek === false || !(snapshot.durationSeconds > 0)
      || typeof source !== 'string' || !source || snapshot.sourceUrl !== source || !content) return null;
  return { target: state.target, source, contentKey: content.key };
}
function sameScrubPreviewContext(expected) {
  const current = currentScrubPreviewContext();
  return !!current && current.target === expected?.target && current.source === expected.source && current.contentKey === expected.contentKey;
}
function scrubPreviewDataUri(value) {
  if (typeof value !== 'string' || value.length > Math.ceil(MAX_SCRUB_PREVIEW_BYTES * 4 / 3) + 64) return null;
  const match = /^data:image\/(?:jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/u.exec(value);
  if (!match || match[1].length % 4 !== 0) return null;
  try { if (atob(match[1]).length > MAX_SCRUB_PREVIEW_BYTES) return null; }
  catch { return null; }
  return value;
}
// Mirrors the phone's scrub preview onto the projector so either screen shows
// the same position and frame. Latest-wins: at most one request in flight.
const scrubMirror = { active: false, inFlight: false, next: null, method: 'receiver.scrub' };
// Declared before any render: the remote renders during startup.
const SCREEN_NAMES = { luma: 'LUMA', philips: 'Philips TV' };
const SYNC_TICK_MS = 1500, SYNC_SETTLE_MS = 3000;
const sync = { on: false, leader: 'luma', follower: 'philips', rate: 1, settleUntil: 0, timer: null, offset: null, ticking: false };
let autoNextWanted = null;
let captionPreferences = {}, picturePreferences = {};
function mirrorScrub(update) {
  // A clear is always delivered to the screen that showed the mirror, even after switching targets.
  if (!update.active && !scrubMirror.active) return;
  if (update.active && !(state.target === 'philips' ? state.philips.connected : state.configured)) return;
  if (update.active && scrubMirror.active && scrubMirror.method !== (state.target === 'philips' ? 'msx.scrub' : 'receiver.scrub')) return;
  if (update.active && !scrubMirror.active) scrubMirror.method = state.target === 'philips' ? 'msx.scrub' : 'receiver.scrub';
  scrubMirror.active = update.active;
  scrubMirror.next = update;
  if (scrubMirror.inFlight) return;
  const send = () => {
    const payload = scrubMirror.next;
    scrubMirror.next = null;
    if (!payload) { scrubMirror.inFlight = false; return; }
    scrubMirror.inFlight = true;
    void rpc(scrubMirror.method, payload, { timeoutMs: 3000 }).catch(() => {}).finally(send);
  };
  send();
}
function clearScrubPreview() {
  mirrorScrub({ active: false });
  scrubPreviewWanted = null;
  scrubPreviewShown = null;
  clearTimeout(scrubPreviewTimer);
  scrubPreviewTimer = null;
  scrubPreviewSerial++;
  scrubPreviewVisible = false;
  scrubPreviewContext = null;
  const preview = $('#scrub-preview');
  preview.hidden = true;
  preview.dataset.image = 'false';
  preview.removeAttribute('data-position');
  $('#scrub-preview-image').removeAttribute('src');
}
/** Updates the time/progress for {@code position}; a new frame replaces the image, otherwise the last one stays. */
function renderScrubPreview(position, dataUri = undefined, framePosition = null) {
  const duration = receiver().playback?.durationSeconds;
  if (!scrubPreviewVisible || !Number.isFinite(position) || !(duration > 0)) return;
  const seconds = Math.max(0, Math.min(Math.floor(duration), Math.floor(position)));
  const preview = $('#scrub-preview');
  preview.hidden = false;
  preview.style.setProperty('--preview-progress', `${Math.max(0, Math.min(100, seconds / duration * 100))}%`);
  $('#scrub-preview-time').textContent = `${clock(seconds)} / ${clock(duration)}${fineScrub.active && fineScrub.gain < 1 ? ' · fine' : ''}`;
  const image = dataUri === undefined ? undefined : scrubPreviewDataUri(dataUri);
  let fresh = null;
  if (image) {
    if (image !== scrubPreviewShown) { $('#scrub-preview-image').src = image; scrubPreviewShown = image; fresh = image; }
    preview.dataset.image = 'true';
    if (Number.isFinite(framePosition)) preview.dataset.position = String(Math.max(0, Math.floor(framePosition)));
  } else if (!scrubPreviewShown) {
    preview.dataset.image = 'false';
    preview.removeAttribute('data-position');
  }
  // Positions only: the projector draws frames phones have already shared with it.
  void fresh;
  mirrorScrub({ active: true, positionSeconds: seconds, durationSeconds: duration });
}
function requestScrubPreview(position) {
  const duration = receiver().playback?.durationSeconds;
  if (!Number.isFinite(position) || !(duration > 0)) { clearScrubPreview(); return; }
  const seconds = Math.max(0, Math.min(Math.floor(duration), Math.floor(position)));
  clearTimeout(scrubPreviewTimer);
  scrubPreviewVisible = true;
  scrubPreviewContext = currentScrubPreviewContext();
  const cached = nearestCachedFrame(scrubPreviewContext, seconds);
  if (cached) renderScrubPreview(seconds, cached.dataUri, cached.position); else renderScrubPreview(seconds);
  if (!scrubPreviewContext) return;
  scrubPreviewWanted = seconds;
  scrubPreviewTimer = setTimeout(pumpScrubPreview, SCRUB_PREVIEW_DELAY_MS);
}
/** At most one lookup in flight, always for the latest wanted position; every arriving frame is shown. */
function pumpScrubPreview() {
  if (scrubPreviewInFlight || scrubPreviewWanted == null || !scrubPreviewVisible || !scrubPreviewContext) return;
  const seconds = scrubPreviewWanted, expected = { ...scrubPreviewContext }, serial = scrubPreviewSerial;
  scrubPreviewWanted = null;
  scrubPreviewInFlight = true;
  void rpc('preview.frame', { expectedSourceUrl: expected.source, contentKey: expected.contentKey, position: seconds }, { timeoutMs: 4000 })
    .then(result => {
      if (serial !== scrubPreviewSerial || !scrubPreviewVisible || !sameScrubPreviewContext(expected)) return;
      const dataUri = result?.available === false ? null : scrubPreviewDataUri(result?.dataUri);
      if (dataUri && Number.isFinite(result?.positionSeconds)) cacheScrubFrame(expected, result.positionSeconds, dataUri);
      const current = Number.isFinite(scrubPosition) ? scrubPosition : seconds;
      const local = nearestCachedFrame(expected, current);
      // Show whichever known frame is nearest to where the finger is now.
      if (local && (!dataUri || Math.abs(local.position - current) < Math.abs(result.positionSeconds - current))) {
        renderScrubPreview(current, local.dataUri, local.position);
      } else renderScrubPreview(current, dataUri, Number.isFinite(result?.positionSeconds) ? result.positionSeconds : null);
      // Frames keep generating in the background; while resting on an uncovered spot, look again.
      const interval = Number.isFinite(result?.intervalSeconds) ? result.intervalSeconds : 10;
      const far = !dataUri ? result?.pending === true : Math.abs(result.positionSeconds - seconds) > interval;
      if (far && scrubPreviewWanted == null) scrubPreviewTimer = setTimeout(() => {
        if (serial === scrubPreviewSerial && scrubPreviewVisible && scrubPreviewWanted == null) { scrubPreviewWanted = Math.floor(current); pumpScrubPreview(); }
      }, 400);
    })
    .catch(() => { /* A missing preview never disables or reports failure for seeking. */ })
    .finally(() => { scrubPreviewInFlight = false; pumpScrubPreview(); });
}
async function resumeMedia(media, target, signal) {
  const seconds = mediaMetadata(media).resumeSeconds || 0;
  if (seconds < 1) return true;
  let identityChanged = false;
  try {
    if (signal?.aborted) return false;
    const content = contentDescriptor(media?.content);
    await rpc(target === 'philips' ? 'msx.seek' : 'receiver.seek', { [target === 'philips' ? 'seconds' : 'position']: seconds,
      expectedSourceUrl: media.url, ...(target === 'luma' && content ? { expectedContentKey: content.key } : {}) }, { signal, timeoutMs: 15000 });
    for (let attempt = 0; attempt < 3; attempt++) {
      if (signal?.aborted) return false;
      const result = await rpc(target === 'philips' ? 'msx.status' : 'receiver.status', target === 'philips' ? {} : {
        expectedSourceUrl: media.url, ...(content ? { expectedContentKey: content.key } : {}),
      }, { signal, timeoutMs: 5000 });
      if (target === 'philips') msxState(result); else playback(result);
      const snapshot = target === 'philips' ? result?.playback : result;
      const source = snapshot?.sourceUrl || snapshot?.source;
      const sameMedia = target === 'philips' ? source === media.url : playbackMatchesMedia(media, snapshot);
      if (snapshot?.active === true && snapshot.stale !== true && (target !== 'philips' || result.connected === true)
          && sameMedia && Number.isFinite(snapshot.positionSeconds) && snapshot.positionSeconds >= seconds - 5 && snapshot.positionSeconds <= seconds + 20) return true;
      if (snapshot?.active === true && snapshot.stale !== true && source === media.url && target === 'luma' && content && !sameMedia) identityChanged = true;
      if (attempt < 2) await new Promise(resolve => setTimeout(resolve, 500));
    }
    if (identityChanged) throw fixedError('SOURCE_CHANGED');
  } catch (error) {
    if (error?.code === 'SOURCE_CHANGED') throw error;
    // Playback has started. A failed seek must never reopen it.
  }
  return false;
}

function receiver(target = state.target) { return target === 'philips' ? state.philips : state; }
function tvConnected() { return state.philips.connected || state.matter.connected; }
function volumeContext() {
  if (state.target !== 'philips') return state.nativeConnected ? { route: 'luma', key: `luma:${state.host}` } : null;
  if (state.matter.connected && state.matter.capabilities.volume && state.matter.volume) return { route: 'matter', key: 'philips:matter' };
  return state.philips.connected ? { route: 'msx', key: `philips:msx:${state.philips.host}:${state.philips.activeMedia?.url || ''}` } : null;
}
function clearPendingVolume() {
  clearTimeout(volumeTimer);
  volumeTimer = null;
  pendingVolume = null;
}
function remoteVolume() {
  if (pendingVolume && pendingVolume.key === volumeContext()?.key) return pendingVolume.volume;
  if (state.target !== 'philips') return state.volume;
  if (state.matter.connected && state.matter.capabilities.volume && state.matter.volume) return state.matter.volume;
  return state.philips.connected ? state.philips.volume : null;
}
function navigationReady() { return state.native && !state.busy && !state.handoff && (state.target === 'philips' ? state.matter.connected && state.matter.capabilities.navigation : state.nativeConnected); }
function matterState(value) {
  if (!value || typeof value !== 'object') return;
  const remote = state.matter;
  for (const key of ['available', 'paired', 'connected', 'pairing', 'paused']) if (typeof value[key] === 'boolean') remote[key] = value[key];
  if (value.errorCode === null || value.errorCode === '') delete remote.errorCode;
  else if (typeof value.errorCode === 'string' && Object.hasOwn(errors, value.errorCode)) remote.errorCode = value.errorCode;
  if (value.capabilities && typeof value.capabilities === 'object') {
    const capabilities = value.capabilities;
    remote.capabilities = {};
    for (const key of ['navigation', 'power', 'powerOn', 'volume', 'inputs', 'openWindow']) remote.capabilities[key] = capabilities[key] === true;
  }
  if (value.volume === null) remote.volume = null;
  else if (Number.isInteger(value.volume?.current) && Number.isInteger(value.volume?.max) && value.volume.max > 0 && value.volume.current >= (value.volume.min || 0) && value.volume.current <= value.volume.max) {
    remote.volume = { current: value.volume.current, min: Math.max(0, value.volume.min || 0), max: value.volume.max };
  }
  if (Array.isArray(value.inputs)) remote.inputs = value.inputs.slice(0, 30).filter(input => Number.isInteger(input?.index) && input.index >= 0).map(input => ({ index: input.index, name: safeText(input.name, 60) || `Input ${input.index}` }));
  if (value.currentInput === null) delete remote.currentInput;
  else if (Number.isInteger(value.currentInput)) remote.currentInput = value.currentInput;
  if (state.target === 'philips') renderControls();
}
function msxParams(extra = {}) { return state.philips.activeMedia ? { ...extra, expectedSourceUrl: state.philips.activeMedia.url } : extra; }
function renderRemote() {
  const remote = receiver();
  $('#media-title').textContent = remote.activeMedia ? title(remote.activeMedia.title) : remote.playback?.active ? 'Now playing' : remote.playback?.ended && remote.playback?.content?.title ? title(remote.playback.content.title) : 'Ready to cast';
  const snapshot = remote.playback;
  if (!snapshot?.active || snapshot.canSeek === false || !(snapshot.durationSeconds > 0)) clearScrubPreview();
  if (!snapshot) $('#playback-status').textContent = 'Choose a video in Browse.';
  else if (!snapshot.active) $('#playback-status').textContent = snapshot.ended && snapshot.content ? (snapshot.upNext ? 'Finished · next episode starts in a moment' : snapshot.content.kind === 'tv' ? 'Finished · Next episode is ready' : 'Finished') : 'No video playing.';
  else {
    const label = typeof snapshot.playing === 'boolean' ? (snapshot.playing ? 'Playing' : 'Paused') : 'Video active';
    // The seek row shows the time; without a seekable duration the status line carries it.
    const timing = Number.isFinite(snapshot.positionSeconds) && !(snapshot.durationSeconds > 0) ? ` · ${clock(snapshot.positionSeconds)}` : '';
    // After auto-play, say where a person last actually was (they may have fallen asleep).
    const last = snapshot.lastInteraction;
    const asleep = snapshot.autoplayed && last?.content?.key && last.content.key !== snapshot.content?.key
      ? ` · auto-played (you were on ${title(last.content.title).replace(/^.* · /, '')} at ${clock(last.positionSeconds || 0)})` : snapshot.autoplayed ? ' · auto-played' : '';
    const inStep = sync.on ? ` · in step with ${SCREEN_NAMES[otherScreen()]}${Number.isFinite(sync.offset) ? ` (${Math.abs(sync.offset) < 0.1 ? '±0.1' : `${sync.offset > 0 ? '+' : '−'}${Math.abs(sync.offset).toFixed(1)}`} s)` : ''}` : '';
    $('#playback-status').textContent = label + timing + asleep + inStep;
  }
  $('#play-label').textContent = snapshot?.playing === false ? 'Resume' : 'Pause';
  $('#play-toggle').dataset.state = snapshot?.playing === false ? 'paused' : 'playing';
  const duration = Number.isFinite(snapshot?.durationSeconds) && snapshot.durationSeconds > 0 ? Math.floor(snapshot.durationSeconds) : 0;
  const observed = Number.isFinite(snapshot?.positionSeconds) ? Math.max(0, Math.min(duration || snapshot.positionSeconds, Math.floor(snapshot.positionSeconds))) : 0;
  const position = scrubPosition == null ? observed : Math.max(0, Math.min(duration || scrubPosition, scrubPosition));
  $('#seek-range').max = String(Math.max(1, duration));
  $('#seek-range').value = String(position);
  $('#seek-time').textContent = clock(position);
  $('#seek-duration').textContent = clock(duration);
  const value = remoteVolume();
  $('#volume-level').textContent = value ? state.target === 'philips' ? `${Math.round(value.current / value.max * 100)}%` : `${value.current} / ${value.max}` : '—';
  $('#volume-range').max = String(value?.max || 1);
  $('#volume-range').min = String(value?.min || 0);
  $('#volume-range').value = String(value?.current || 0);
  $('#volume-range').setAttribute('aria-valuetext', value ? `${value.current} of ${value.max}` : 'Unavailable');
}
function receiverRevision(value) { return typeof value === 'string' && /^[a-f0-9]{64}$/i.test(value) ? value.toLowerCase() : null; }
function receiverDebug(value) {
  if (!value || typeof value !== 'object') return null;
  return { revision: receiverRevision(value.revision), remoteReload: value.remoteReload === true, remoteCommands: value.remoteCommands === true,
    uptimeSeconds: Number.isFinite(value.uptimeSeconds) && value.uptimeSeconds >= 0 && value.uptimeSeconds <= 315360000 ? Math.floor(value.uptimeSeconds) : null,
    pingCount: Number.isSafeInteger(value.pingCount) && value.pingCount >= 0 ? value.pingCount : null,
    inputCount: Number.isSafeInteger(value.inputCount) && value.inputCount >= 0 && value.inputCount <= 1000000000 ? value.inputCount : null,
    lastInput: ['none', 'key-enter', 'key-arrow', 'key-back', 'key-media', 'pointer-background', 'pointer-arrow'].includes(value.lastInput) ? value.lastInput : null,
    pointerLockSupported: typeof value.pointerLockSupported === 'boolean' ? value.pointerLockSupported : null,
    pointerLocked: typeof value.pointerLocked === 'boolean' ? value.pointerLocked : null,
    pointerMoveCount: Number.isSafeInteger(value.pointerMoveCount) && value.pointerMoveCount >= 0 && value.pointerMoveCount <= 1000000000 ? value.pointerMoveCount : null,
    pointerLockError: ['none','not-allowed','unsupported','event-error','other'].includes(value.pointerLockError) ? value.pointerLockError : null };
}
function msxState(value) {
  if (!value || typeof value !== 'object') return;
  const remote = state.philips;
  if (value.matter) matterState(value.matter);
  const wasConnected = remote.connected;
  let reloadReconnected = false;
  const wasTextPairing = remote.textPairing;
  remote.typingAvailable = value.typingAvailable === true;
  remote.textPermissionSaved = value.textPermissionSaved === true || remote.typingAvailable;
  remote.typingBusy = value.typingBusy === true;
  remote.textPairing = value.textPairing === true;
  remote.textPairingExpiresInSeconds = Number.isFinite(value.textPairingExpiresInSeconds) && value.textPairingExpiresInSeconds > 0 ? Math.min(600, value.textPairingExpiresInSeconds) : 0;
  remote.textError = Object.hasOwn(TV_TEXT_ERRORS, value.textError) ? value.textError : null;
  if (wasTextPairing && !remote.textPairing) {
    $('#msx-text-pin').value = '';
    remote.typingNotice = !remote.textPermissionSaved && !remote.typingBusy ? TV_TEXT_ERRORS[remote.textError] || TV_TEXT_ERRORS.TEXT_PAIRING_EXPIRED : '';
    remote.typingNoticeError = !!remote.typingNotice;
  }
  if (value.diagnostics && typeof value.diagnostics === 'object') {
    // Native diagnostics are reduced to fixed reasons and counts. Never copy
    // request origins, URLs, capability tokens or server messages into the UI.
    remote.diagnostics = {};
    remote.diagnostics.assetRevision = receiverRevision(value.diagnostics.assetRevision);
    for (const key of ['connections', 'tlsRequests', 'bootstrapRequests', 'bootstrapAccepted', 'helloAccepted', 'pollAccepted', 'stateAccepted', 'lastStatus', 'lastRequestMsAgo']) {
      const count = value.diagnostics[key];
      if (Number.isSafeInteger(count) && count >= 0) remote.diagnostics[key] = count;
    }
    for (const key of ['lastHelloMsAgo', 'lastPollMsAgo', 'lastStateMsAgo']) {
      const age = value.diagnostics[key];
      if (Number.isSafeInteger(age) && age >= -1) remote.diagnostics[key] = age;
    }
    for (const key of ['sessionPresent', 'sessionApproved']) if (typeof value.diagnostics[key] === 'boolean') remote.diagnostics[key] = value.diagnostics[key];
    const reason = value.diagnostics.lastReason;
    if (Object.hasOwn(MSX_SETUP_HINTS, reason)) remote.diagnostics.lastReason = reason;
  }
  if (typeof value.connected === 'boolean') remote.connected = value.connected;
  for (const key of ['configured', 'pairingOpen']) if (typeof value[key] === 'boolean') remote[key] = value[key];
  if (typeof value.host === 'string' && privateHost(value.host)) remote.host = value.host;
  if (typeof value.name === 'string') remote.name = safeText(value.name) || 'Philips TV';
  if (typeof value.awaitingApproval === 'boolean') remote.awaitingApproval = value.awaitingApproval;
  if ((remote.connected || remote.awaitingApproval) && remote.typingNotice === TV_BROWSER_WAIT) remote.typingNotice = '';
  remote.pendingCommand = value.pendingCommand || null;
  if (remote.openUncertain && !remote.pendingCommand && (value.lastCommand || Object.hasOwn(value, 'pendingCommand'))) remote.openUncertain = false;
  if (Number.isSafeInteger(value.lastCommand?.id)) remote.lastReceiptId = value.lastCommand.id;
  if (remote.sampleBridgeUncertain) {
    const settledSample = value.lastCommand?.method === 'open' && Number.isSafeInteger(value.lastCommand.id) && value.lastCommand.id > remote.sampleAfterReceipt;
    if (!remote.pendingCommand && settledSample) remote.sampleBridgeUncertain = false;
    else remote.openUncertain = true;
  }
  remote.pendingHost = privateHost(value.pendingHost || '') ? value.pendingHost : '';
  if (typeof value.startupUrl === 'string') {
    if (!value.startupUrl) remote.startupUrl = '';
    try {
      const url = new URL(value.startupUrl);
      if (url.protocol === 'http:' && privateHost(url.hostname) && !url.username && !url.password) remote.startupUrl = url.origin;
    } catch { /* Only show the native listener's local address. */ }
  }
  remote.browserSetupUrl = remote.startupUrl && value.browserSetupUrl === remote.startupUrl + '/msx/connect.html' ? value.browserSetupUrl : '';
  const snapshot = value.playback;
  if (snapshot && typeof snapshot.active === 'boolean') {
    const sourceUrl = snapshot.sourceUrl || snapshot.source;
    if (remote.activeMedia && snapshot.active && sourceUrl && sourceUrl !== remote.activeMedia.url) {
      if (state.target === 'philips') clearScrubPreview();
      remote.activeMedia = null; remote.catalog = []; remote.tracks = []; remote.nextEpisode = null;
    }
    remote.playback = { ...snapshot, receiverDebug: receiverDebug(snapshot.receiverDebug), sourceUrl, playing: typeof snapshot.playing === 'boolean' ? snapshot.playing : snapshot.paused !== true };
    if (snapshot.active && !remote.activeMedia && typeof sourceUrl === 'string') remote.activeMedia = { url: sourceUrl, title: title(snapshot.title), ...mediaMetadata(snapshot) };
    if (Object.hasOwn(snapshot, 'nextEpisode')) remote.nextEpisode = nextEpisodeSelection(snapshot.nextEpisode, remote.activeMedia);
    if (Array.isArray(snapshot.tracks)) {
      remote.tracks = snapshot.tracks.slice(0, 128).filter(track => Number.isInteger(track?.index)).map(track => ({ index: track.index, language: safeText(track.language, 64), label: safeText(track.label, 200) }));
      if (!remote.catalog.length) remote.catalog = remote.tracks;
    }
    if (typeof snapshot.subtitlesEnabled === 'boolean') remote.subtitlesEnabled = snapshot.subtitlesEnabled;
    if (snapshot.selectedIndex === null || Number.isInteger(snapshot.selectedIndex)) remote.selectedIndex = snapshot.selectedIndex;
    volume(snapshot, 'philips');
  }
  const reloadHello = value.diagnostics?.helloAccepted;
  if (Number.isSafeInteger(remote.debugReloadHelloBaseline) && Number.isSafeInteger(reloadHello) && reloadHello >= 0) {
    if (reloadHello < remote.debugReloadHelloBaseline) remote.debugReloadHelloBaseline = null;
    else if (reloadHello > remote.debugReloadHelloBaseline && value.connected === true && receiverRevision(snapshot?.receiverDebug?.revision)) {
      remote.debugReloadHelloBaseline = null;
      remote.debugNotice = 'TV page reconnected.';
      remote.debugNoticeError = false;
      reloadReconnected = true;
      if ($('#message').textContent === 'Reload acknowledged. Waiting for the TV to reconnect…') message(remote.debugNotice);
    }
  }
  volume(value, 'philips');
  if (state.target === 'philips') renderTracks();
  renderControls();
  if (!wasConnected && remote.connected && state.target === 'philips' && $('#page-devices').hidden === false) {
    page('remote');
    message(reloadReconnected ? 'TV page reconnected.' : 'Philips TV connected. Your remote is ready.');
    if (state.pendingMedia && state.pendingTarget === 'philips') {
      state.autoStart = true;
      if (!state.busy && !state.handoff) queueMicrotask(() => void startCasting());
    }
  }
}

async function selectTarget(target) {
  if (!['luma', 'philips'].includes(target) || target === state.target || state.busy || state.handoff) return;
  clearPendingVolume();
  clearScrubPreview();
  scrubPosition = null;
  changingTarget = true;
  try { await command('app.selectTarget', { target }, '', result => {
    state.target = target;
    deferredNativeTarget = null;
    if (result?.receivers?.philips) msxState(result.receivers.philips);
    closeSheet();
    $('#subtitle-search').value = '';
    renderTracks();
    if (target === 'philips' && !tvConnected()) page('devices'); else page('remote');
    message(target === 'philips' ? (tvConnected() ? `Philips TV selected.${state.connected ? ' LUMA stays connected.' : ''}` : 'Pair your TV remote or connect its video receiver.') : `LUMA selected.${tvConnected() ? ' Your TV stays connected.' : ''}`);
  }); } finally { changingTarget = false; deferredNativeTarget = null; }
}
function restoreSelectedTarget(target) {
  if (!['luma', 'philips'].includes(target) || changingTarget) return false;
  if (state.busy || state.handoff) { deferredNativeTarget = target; return false; }
  if (target === state.target) { deferredNativeTarget = null; return false; }
  clearScrubPreview();
  state.target = target;
  scrubPosition = null;
  closeSheet();
  $('#subtitle-search').value = '';
  renderTracks();
  page(target === 'philips' && !tvConnected() ? 'devices' : 'remote');
  return true;
}
function applyDeferredTarget() {
  if (!deferredNativeTarget || state.busy || state.handoff) return;
  const target = deferredNativeTarget;
  deferredNativeTarget = null;
  restoreSelectedTarget(target);
}

function fixedError(code) {
  const error = new Error(errors[code] || 'That request could not be completed. Try again.');
  error.code = code;
  return error;
}
function message(text, error = false) {
  $('#message').textContent = text;
  $('#message').dataset.error = String(error);
  if (state.sheet && state.sheet !== 'handoff') {
    $('#sheet-message').textContent = text;
    $('#sheet-message').dataset.error = String(error);
    $('#sheet-message').hidden = false;
  }
}
function safeText(value, max = 120) {
  return typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f-\u009f]/gu, '').slice(0, max) : '';
}
function title(value) {
  return safeText(value, 160).replace(/https?:\/\/\S+/giu, '').trim() || 'Selected video';
}
function clock(value) {
  const seconds = Math.max(0, Math.floor(Number(value) || 0));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}
function privateHost(value) {
  const parts = value.split('.');
  if (parts.length !== 4 || parts.some(part => !/^\d{1,3}$/u.test(part) || String(Number(part)) !== part || Number(part) > 255)) return false;
  const [a, b] = parts.map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

function cancelNative(sourceToken) {
  if (typeof sourceToken !== 'string' || !sourceToken || !window.LumaNative?.postMessage) return;
  try { window.LumaNative.postMessage(JSON.stringify({ id: String(++requestId), method: 'app.cancelHandoff', params: { sourceToken } })); }
  catch { /* A disconnected bridge cannot accept a cancellation. */ }
}
function reportStatus(stage, code) {
  if (!window.LumaNative?.postMessage) return;
  const params = { stage };
  if (typeof code === 'string' && /^[A-Z_]{1,40}$/u.test(code)) params.code = code;
  try { window.LumaNative.postMessage(JSON.stringify({ id: String(++requestId), method: 'app.handoffStatus', params })); }
  catch { /* Diagnostics never change the result of a user action. */ }
}
function rpc(method, params = {}, { signal, timeoutMs = 10000, onNotSent, onSettled } = {}) {
  const mutationReceipt = method === 'receiver.addSubtitle' || method === 'receiver.open';
  const notSent = () => { try { onNotSent?.(); } catch { /* Receipt observers cannot alter transport outcome. */ } };
  const settled = () => { try { onSettled?.(); } catch { /* Only a native reply supplies this receipt. */ } };
  if (!window.LumaNative?.postMessage) { notSent(); return Promise.reject(fixedError('NATIVE_UNAVAILABLE')); }
  if (signal?.aborted) { notSent(); return Promise.reject(fixedError('CANCELLED')); }
  return new Promise((resolve, reject) => {
    const id = String(++requestId);
    let item;
    let timer;
    const remove = () => {
      clearTimeout(timer);
      signal?.removeEventListener('abort', abort);
      pending.delete(id);
    };
    const retainReceipt = () => {
      const receipts = [...pending.values()].filter(entry => entry.receiptOnly).length;
      if (!mutationReceipt || (typeof onNotSent !== 'function' && typeof onSettled !== 'function') || receipts >= 32) { remove(); return; }
      item.receiptOnly = true;
      signal?.removeEventListener('abort', abort);
      clearTimeout(timer);
      // A bridge timeout is not native completion. Retain only a bounded
      // observer for an eventual real reply, never an automatic replay.
      timer = setTimeout(remove, 60000);
    };
    const abort = () => {
      // A stage deadline can expire while the cast itself remains valid.
      // User cancellation/source changes revoke the job explicitly; a single
      // timed-out read must not invalidate later playback/subtitle recovery.
      // A cancelled mutation may still receive proof that the native guard stopped
      // it before writing. Keep only this bounded, expiring receipt observer;
      // the caller stops immediately and unknown outcomes remain unknown.
      retainReceipt();
      reject(fixedError('CANCELLED'));
    };
    timer = setTimeout(() => { retainReceipt(); reject(fixedError('TIMEOUT')); }, timeoutMs);
    item = {
      receiptOnly: false,
      resolve: result => { settled(); remove(); resolve(result); },
      reject: code => { if (mutationReceipt && ['SOURCE_CHANGED', 'RECEIVER_REJECTED', 'SUBTITLE_EXPIRED', 'SUBTITLE_DOWNLOAD', 'SUBTITLE_INVALID', 'SUBTITLE_TOO_LARGE'].includes(code)) notSent(); settled(); remove(); reject(fixedError(code)); },
    };
    pending.set(id, item);
    signal?.addEventListener('abort', abort, { once: true });
    try { window.LumaNative.postMessage(JSON.stringify({ id, method, params })); }
    catch { notSent(); remove(); reject(fixedError('NATIVE_UNAVAILABLE')); }
  });
}
window.LumaRPC = Object.freeze({
  receive(response) {
    if (!response || typeof response !== 'object') return;
    const item = pending.get(String(response.id));
    if (!item) return;
    if (response.ok === true) item.resolve(response.result);
    else item.reject(typeof response.error?.code === 'string' ? response.error.code : 'REQUEST_FAILED');
  },
});

function viewport() {
  const height = window.visualViewport?.height || window.innerHeight;
  document.documentElement.style.setProperty('--app-height', `${Math.floor(height)}px`);
  if (height >= 480) keyboardCompact = false;
  else if (document.activeElement?.closest('input, textarea')) keyboardCompact = true;
  document.body.classList.toggle('keyboard-small', keyboardCompact);
}
window.visualViewport?.addEventListener('resize', viewport);
window.addEventListener('resize', viewport);
document.addEventListener('focusin', viewport);
document.addEventListener('focusout', () => requestAnimationFrame(viewport));
viewport();

// Without a projector connection and receiver pairing the LUMA remote cannot work,
// so the Remote tab shows the connect view in its place and swaps back once ready.
let remoteRedirected = false;
function lumaRemoteReady() { return state.connected && state.configured; }
function page(name) {
  let shown = name;
  remoteRedirected = false;
  if (name === 'remote' && state.target !== 'philips' && !lumaRemoteReady()) {
    shown = 'devices'; remoteRedirected = true;
    deviceStep(state.connected ? 'pair' : 'receiver');
  }
  for (const item of ['browse', 'remote', 'devices']) {
    $(`#page-${item}`).hidden = item !== shown;
    $(`#tab-${item}`).setAttribute('aria-selected', String(item === name));
  }
}
function syncRemoteRedirect() {
  if (remoteRedirected && (state.target === 'philips' || lumaRemoteReady()) && !state.handoff) page('remote');
}
function deviceStep(name) {
  $('#receiver-panel').hidden = name !== 'receiver';
  $('#pair-panel').hidden = name !== 'pair';
  $('#receiver-step').toggleAttribute('aria-current', name === 'receiver');
  $('#pair-step').toggleAttribute('aria-current', name === 'pair');
  if (name === 'receiver') $('#receiver-step').setAttribute('aria-current', 'step');
  if (name === 'pair') $('#pair-step').setAttribute('aria-current', 'step');
}
function closeSheet() {
  if (state.sheet === 'history') {
    historySelection++;
    if (historyController) { historyController.abort(); historyController = null; void rpc('app.cancelCatalog', {}, { timeoutMs: 2000 }).catch(() => {}); state.busy = false; renderControls(); }
  }
  if (state.sheet === 'matter-share') {
    clearTimeout(state.matterShareTimer);
    $('#matter-qr').removeAttribute('src');
    $('#matter-qr').hidden = true;
    $('#matter-share-code').textContent = '';
    $('#matter-share-expiry').textContent = '';
  }
  $('#sheet').hidden = true;
  $('#power-backdrop').hidden = true;
  state.sheet = null;
  $('#sheet-message').hidden = true;
  returnFocus?.focus();
}
function sheet(name) {
  if ($('#sheet').hidden) returnFocus = document.activeElement;
  state.sheet = name;
  $('#sheet').dataset.panel = name;
  $('#power-backdrop').hidden = name !== 'power';
  $('#sheet-message').hidden = true;
  $('#sheet').hidden = false;
  for (const panel of document.querySelectorAll('.sheet-panel')) panel.hidden = panel.id !== `panel-${name}`;
  $('#sheet-title').textContent = { settings: 'Configuration', reset: 'Forget saved setup', power: state.target === 'philips' ? 'Turn off the TV?' : 'Turn off the projector?', more: 'More controls', typing: 'Type text', apps: 'Apps', subtitles: 'Subtitles', inputs: 'TV inputs', 'matter-share': 'Add another phone', handoff: state.setupFlow ? 'Pair LUMA Receiver' : 'Start casting' }[name];
  if (name === 'history') $('#sheet-title').textContent = 'Library';
  $('#close-sheet').focus();
  if (name === 'power') $('#cancel-power').focus();
  if (name === 'typing') $('#remote-text').focus();
  renderControls();
}
function renderControls() {
  syncRemoteRedirect();
  if (pendingVolume && pendingVolume.key !== volumeContext()?.key) clearPendingVolume();
  const free = state.native && !state.busy && !state.handoff;
  const tv = state.target === 'philips';
  const remote = receiver();
  const browserEntry = state.tvEntry === 'browser';
  const matter = state.matter;
  const tvRemote = free && tv && matter.connected;
  const navigation = navigationReady();
  const projector = free && !tv && state.connected;
  const luma = free && !tv && state.nativeConnected;
  const player = free && (tv ? remote.connected : state.configured);
  const active = player && playbackMatchesMedia(remote.activeMedia, remote.playback);
  const saved = tv ? remote.connected || matter.paired : state.settings.hasReceiver && state.settings.hasPairing;
  renderRemote();
  for (const target of ['luma', 'philips']) {
    $(`#target-${target}`).disabled = !free;
    $(`#target-${target}`).setAttribute('aria-pressed', String(state.target === target));
    $(`#target-${target}`).dataset.connected = String(target === 'philips' ? tvConnected() : state.connected);
  }
  $('#tab-devices').hidden = saved;
  $('#show-settings').hidden = !saved;
  $('#show-settings').disabled = !free;
  $('#device-badge').textContent = tv ? (matter.connected ? 'TV remote connected' : remote.connected ? 'TV receiver connected' : 'TV not connected')
    : state.receiverConnected && !state.nativeConnected ? 'LUMA Receiver ready' : state.connected ? (state.name || 'Projector connected') : 'No projector connected';
  $('#device-badge').dataset.connected = String(tv ? tvConnected() : state.connected);
  $('#tv-setup-tabs').hidden = !tv || !matter.available;
  $('#tv-setup-remote').setAttribute('aria-selected', String(state.tvSetup === 'remote'));
  $('#tv-setup-video').setAttribute('aria-selected', String(state.tvSetup === 'video'));
  $('#matter-panel').hidden = !tv || !matter.available || state.tvSetup !== 'remote';
  $('#msx-panel').hidden = !tv || matter.available && state.tvSetup !== 'video';
  $('#matter-title').textContent = matter.paired ? 'Your TV remote' : 'Pair your TV remote';
  $('#matter-status').textContent = matter.pairing ? 'Pairing with the TV…' : matter.connected ? 'Connected. Navigation, volume and supported TV controls are ready.' : matter.paired ? 'Pairing is saved. Reconnect when the TV is available.' : 'Open Matter pairing on the TV, then scan its QR code or enter its setup code.';
  $('#matter-form').hidden = matter.paired;
  $('#matter-paired-actions').hidden = !matter.paired;
  for (const control of $('#matter-panel').querySelectorAll('button,input')) control.disabled = !free || matter.pairing;
  $('#matter-share').disabled = !tvRemote || matter.capabilities.openWindow !== true;
  $('.device-steps').hidden = tv;
  $('#receiver-panel').hidden ||= tv;
  $('#pair-panel').hidden ||= tv;
  if (!tv && $('#receiver-panel').hidden && $('#pair-panel').hidden) deviceStep('receiver');
  $('#msx-startup').hidden = !remote.startupUrl || browserEntry;
  $('#msx-startup').textContent = remote.startupUrl ? new URL(remote.startupUrl).host : '';
  $('#msx-title').textContent = remote.textOperation === 'msx.textPairGrant' ? 'Checking the TV PIN…' : remote.textOperation === 'msx.textPairRequest' ? 'Requesting the TV PIN…' : remote.textOperation === 'msx.launchBrowser' ? 'Requesting the TV browser…' : ['msx.typeAddress', 'msx.typeBrowserAddress'].includes(remote.textOperation) ? browserEntry ? 'Entering the browser link…' : 'Entering the MSX address…' : remote.textPairing ? 'Enter the TV PIN' : remote.connected ? 'TV connected' : remote.awaitingApproval ? 'Allow your TV' : remote.textPermissionSaved ? browserEntry ? 'TV browser permission saved' : 'Address entry enabled' : 'Connect your Philips TV';
  $('#tv-entry-methods').hidden = remote.textPairing === true;
  for (const method of ['browser', 'msx']) {
    $(`#tv-entry-${method}`).setAttribute('aria-pressed', String(state.tvEntry === method));
    $(`#tv-entry-${method}`).disabled = !free;
  }
  $('#msx-pairing').textContent = remote.awaitingApproval && remote.pendingHost ? `Allow the TV at ${remote.pendingHost} if it is yours.` : remote.connected ? 'TV connected. You can choose a video in Browse.' : 'Use the same Wi-Fi network on both devices.';
  $('#msx-pairing').hidden = remote.textPairing || !!remote.startupUrl && !remote.awaitingApproval && !remote.connected;
  const setupWindowClosed = remote.configured === false && remote.pairingOpen === false;
  const diagnostic = remote.diagnostics;
  let receiverStage = '';
  if (diagnostic?.sessionPresent === true) {
    if (diagnostic.sessionApproved === false) receiverStage = 'TV reached this phone. Approval needed.';
    else if (diagnostic.sessionApproved === true) {
      if (remote.connected && diagnostic.lastStateMsAgo >= 0) receiverStage = 'Receiver status received. TV connection ready.';
      else if (diagnostic.lastStateMsAgo >= 0) receiverStage = 'TV status is no longer current. Waiting for the receiver.';
      else receiverStage = 'TV allowed. Waiting for receiver status.';
    }
  } else if (diagnostic?.sessionPresent === false && (diagnostic.bootstrapAccepted > 0 || diagnostic.helloAccepted > 0)) receiverStage = 'TV reached this phone. Waiting for receiver connection.';
  const setupHint = !remote.connected && !remote.awaitingApproval && setupWindowClosed ? browserEntry && !state.tvBrowserManual ? 'TV setup window closed. Tap Open TV receiver to reopen it.' : 'TV setup window closed. Tap Restart TV setup before connecting the TV.' : receiverStage || (!remote.connected && !remote.awaitingApproval ? MSX_SETUP_HINTS[diagnostic?.lastReason] : '');
  $('#msx-diagnostic').textContent = setupHint || '';
  $('#msx-diagnostic').hidden = !setupHint;
  $('#msx-panel').dataset.diagnostic = String(Boolean(setupHint));
  $('#msx-panel').dataset.textPairing = String(remote.textPairing === true);
  $('#msx-start').disabled = !free;
  $('#msx-start').hidden = browserEntry && !state.tvBrowserManual || remote.textPairing || !!remote.startupUrl && !setupWindowClosed && remote.diagnostics?.lastReason !== 'PAIRING_EXPIRED';
  $('#msx-start').textContent = remote.startupUrl ? 'Restart TV setup' : 'Set up TV';
  $('#msx-refresh').hidden = !remote.startupUrl || remote.textPairing;
  $('#msx-refresh').disabled = !free;
  $('#msx-confirm').hidden = !remote.awaitingApproval || !remote.pendingHost;
  $('#msx-confirm').disabled = !free;
  $('#browser-launch').hidden = !browserEntry || remote.textPairing;
  $('#browser-launch').disabled = !free || remote.typingBusy === true;
  $('#browser-manual').hidden = !browserEntry || remote.textPairing || state.tvBrowserManual;
  $('#browser-manual').disabled = !free;
  $('#msx-type-address').hidden = remote.textPairing || browserEntry && !state.tvBrowserManual;
  $('#msx-type-address').textContent = browserEntry ? 'Type browser link' : 'Enter TV address';
  $('#msx-type-address').disabled = !free || !remote.startupUrl || !remote.typingAvailable || remote.typingBusy === true || setupWindowClosed || browserEntry && !remote.browserSetupUrl;
  $('#msx-text-pair').hidden = !remote.startupUrl || remote.textPermissionSaved === true || remote.textPairing === true;
  $('#msx-text-pair').textContent = browserEntry ? 'Enable TV browser control' : 'Enable address entry';
  $('#msx-text-pair').disabled = !free || remote.typingBusy === true;
  $('#msx-text-form').hidden = remote.textPairing !== true;
  for (const control of $('#msx-text-form').querySelectorAll('button,input')) control.disabled = !free;
  $('#msx-startup').hidden ||= remote.textPairing === true;
  const hideEntrySteps = !!remote.startupUrl && !remote.textPermissionSaved;
  $('#msx-steps').hidden = browserEntry || hideEntrySteps;
  $('#browser-steps').hidden = !browserEntry || !state.tvBrowserManual || hideEntrySteps;
  const textProgress = { 'msx.textPairRequest': 'Waiting for the TV to show its PIN…', 'msx.textPairGrant': 'Checking the PIN and saving permission. Please wait…', 'msx.typeAddress': 'Keep the empty MSX address field selected on the TV. Waiting for its reply…', 'msx.typeBrowserAddress': 'Keep the empty Internet browser address bar selected on the TV. Waiting for its reply…', 'msx.launchBrowser': 'Preparing the phone receiver and requesting its TV page…' }[remote.textOperation];
  const fieldHint = browserEntry ? state.tvBrowserManual ? 'Permission saved. Open the TV Internet browser and select and clear its address bar.' : 'Permission saved. Tap Open TV receiver, then allow the TV when it connects.' : 'Permission saved. Open MSX Setup and select and clear its address field before tapping Enter TV address.';
  $('#msx-type-status').textContent = textProgress || remote.typingNotice || TV_TEXT_ERRORS[remote.textError] || (remote.textPairing ? 'Enter the four-digit PIN shown on the TV.' : remote.typingAvailable ? fieldHint : remote.textPermissionSaved ? browserEntry ? 'Permission saved. Reconnect your TV remote to open the receiver.' : 'Permission saved. Reconnect your TV remote to enable address entry.' : remote.startupUrl ? browserEntry ? 'Enable TV browser control once with the TV’s PIN.' : 'Enable address entry once with the TV’s PIN.' : browserEntry ? 'Tap Open TV receiver. First time only, enable TV browser control with its PIN.' : 'Tap Set up TV to prepare this phone’s address.');
  $('#msx-type-status').dataset.error = String(!textProgress && (remote.typingNotice ? remote.typingNoticeError === true : !!remote.textError));
  $('#msx-text-grant').textContent = remote.textOperation === 'msx.textPairGrant' ? 'Checking PIN…' : 'Save permission';
  $('#msx-sample').hidden = !tv;
  $('#msx-sample').disabled = !free || !remote.connected || !!remote.pendingCommand || remote.openUncertain === true;
  const developer = $('#msx-developer'), debug = remote.playback?.receiverDebug;
  developer.hidden = remote.textPairing === true || (!remote.connected && !debug);
  $('#msx-setup-fields').hidden = !developer.hidden && developer.open;
  const loaded = debug?.revision, serverRevision = remote.diagnostics?.assetRevision;
  $('#msx-debug-revisions').textContent = `Loaded: ${loaded ? loaded.slice(0, 10) : 'not reported'} · Server: ${serverRevision ? serverRevision.slice(0, 10) : 'not reported'}${loaded && serverRevision && loaded !== serverRevision ? ' · Revision mismatch.' : ''}`;
  $('#msx-debug-runtime').textContent = `Uptime: ${debug?.uptimeSeconds == null ? 'not reported' : debug.uptimeSeconds + 's'} · Ping count: ${debug?.pingCount == null ? 'not reported' : debug.pingCount}`;
  $('#msx-debug-support').textContent = debug?.remoteReload ? 'Remote reload: reported by this page.' : 'Remote reload: not reported by this page.';
  if (debug?.inputCount != null && debug.lastInput != null) $('#msx-debug-support').textContent += ` Inputs: ${debug.inputCount} · Last: ${debug.lastInput}.`;
  if (debug?.pointerLockSupported != null && debug.pointerLocked != null) $('#msx-debug-support').textContent += ` Pointer lock: ${debug.pointerLockSupported ? debug.pointerLocked ? 'active' : 'API present' : 'unavailable'}.`;
  if (debug?.pointerMoveCount != null) $('#msx-debug-support').textContent += ` Pointer moves: ${debug.pointerMoveCount}.`;
  if (debug?.pointerLockError != null && debug.pointerLockError !== 'none') $('#msx-debug-support').textContent += ` Pointer capture: ${debug.pointerLockError}.`;
  const debugFree = free && remote.connected && !remote.pendingCommand;
  $('#msx-debug-ping').disabled = !debugFree || debug?.remoteCommands !== true;
  $('#msx-debug-label').disabled = !debugFree || debug?.remoteCommands !== true;
  $('#msx-debug-reload').disabled = !debugFree || debug?.remoteReload !== true || remote.playback?.active !== false;
  $('#msx-debug-result').textContent = remote.debugNotice || '';
  $('#msx-debug-result').hidden = !remote.debugNotice;
  $('#msx-debug-result').dataset.error = String(remote.debugNoticeError === true);
  $('.dpad').hidden = tv && !matter.capabilities.navigation;
  $('.dpad').setAttribute('aria-label', tv ? 'TV navigation' : 'Projector navigation');
  $('#tv-remote').hidden = !tv || matter.capabilities.navigation === true;
  $('#tv-remote-hint').textContent = remote.connected ? 'TV receiver is ready. Playback controls apply to this TV.' : 'Open the TV receiver from TV connection setup.';
  $('#show-tv-setup').disabled = !free;
  $('#browse-hint').textContent = `Find a video, then tap Cast in the browser toolbar. ${tv ? 'Philips TV' : 'Your projector'} plays it.`;
  const hardwareVolume = tv && matter.connected && matter.volume;
  $('.volume-row').setAttribute('aria-label', tv ? hardwareVolume ? 'TV speaker volume' : 'TV player volume' : 'Projector volume');
  $('#volume-range').setAttribute('aria-label', tv ? hardwareVolume ? 'TV speaker volume level' : 'TV player volume level' : 'Projector volume level');
  $('.volume-control label').firstChild.textContent = tv && !hardwareVolume ? 'Player volume ' : 'Volume ';
  $('#cast-selected').textContent = `Cast selected video${tv ? ' to Philips' : ''}`;
  $('#receiver-host').disabled = !free || state.connected;
  $('#discover').disabled = !free || state.connected;
  $('#connect-device').disabled = !free || state.connected;
  $('#disconnect-device').hidden = !state.connected;
  $('#disconnect-device').disabled = !free;
  $('#pair-submit').disabled = !projector;
  $('#pair-show').disabled = !projector;
  for (const input of $('#pair-form').querySelectorAll('input')) input.disabled = !free;
  for (const button of document.querySelectorAll('[data-key]')) { button.disabled = !navigation; if (button.closest('.topline')) button.style.visibility = tv && !matter.capabilities.navigation ? 'hidden' : ''; }
  // LUMA status polling keeps volume current; Philips may need a manual refresh.
  $('#refresh-volume').hidden = !tv;
  $('#refresh-volume').disabled = !(tv ? player || tvRemote : luma);
  const displayedVolume = remoteVolume();
  for (const id of ['volume-range', 'volume-down', 'volume-up']) $(`#${id}`).disabled = !(tv ? hardwareVolume || player : luma) || !displayedVolume;
  if (displayedVolume) {
    $('#volume-down').disabled ||= displayedVolume.current <= (displayedVolume.min || 0);
    $('#volume-up').disabled ||= displayedVolume.current >= displayedVolume.max;
  }
  $('#settings-disconnect').disabled = !(tv ? player || tvRemote : projector);
  $('#show-power').hidden = tv && !matter.capabilities.power;
  $('#show-power').setAttribute('aria-label', tv ? 'Power off TV' : 'Power off projector');
  $('#show-power').disabled = !(tv ? tvRemote && matter.capabilities.power : luma);
  $('#confirm-power').disabled = $('#show-power').disabled;
  for (const button of $('#panel-settings').querySelectorAll('button:not(#settings-disconnect)')) button.disabled = !free;
  $('#settings-hint').textContent = tv ? 'TV setup and LUMA settings are kept separately. Switching devices leaves both connected.' : 'Your projector and LUMA Receiver pairing are saved on this phone. Opening the app reconnects automatically.';
  $('#edit-receiver').textContent = tv ? 'TV connection' : 'Projector connection';
  $('#edit-pairing').hidden = tv;
  $('#rescan-projectors').hidden = tv;
  $('#show-reset').textContent = tv ? 'Forget this TV' : 'Forget saved setup';
  $('#reset-hint').textContent = tv ? 'Forget the Philips TV pairing on this phone? LUMA setup stays saved.' : 'Choose what to forget on this phone. A playing video stays on the projector.';
  $('#forget-matter-local').hidden = !(tv && matter.paired && state.tvSetup === 'remote');
  $('#forget-matter-local').disabled = !free;
  $('#matter-local-hint').hidden = $('#forget-matter-local').hidden;
  for (const button of document.querySelectorAll('[data-reset]')) button.disabled = !free;
  for (const button of document.querySelectorAll('[data-reset]')) {
    button.hidden = tv && button.dataset.reset !== 'all';
    button.textContent = tv ? 'Forget Philips TV pairing' : { pairing: 'Forget receiver pairing', receiver: 'Forget projector', all: 'Forget all saved settings' }[button.dataset.reset];
  }
  $('#play-toggle').disabled = !active;
  const canSeek = active && !!remote.playback?.durationSeconds && remote.playback?.canSeek !== false;
  $('#back-30').disabled = !canSeek || (remote.playback?.positionSeconds || 0) <= 0;
  // On LUMA the receiver finds and opens the next episode itself, even after one has ended.
  const lumaEpisode = !tv && state.configured && remote.playback?.content?.kind === 'tv';
  $('#next-episode').disabled = tv ? !(active || remote.playback?.ended) || !remote.nextEpisode : !free || !lumaEpisode;
  $('#auto-next-row').hidden = tv ? !state.philips.connected : !state.configured;
  $('#auto-next').disabled = tv ? !state.philips.connected : !state.configured;
  const reportedAutoNext = remote.playback?.autoNext;
  if (autoNextWanted && (autoNextWanted.target !== state.target || autoNextWanted.confirmed && reportedAutoNext === autoNextWanted.value)) autoNextWanted = null;
  if (autoNextWanted) $('#auto-next').checked = autoNextWanted.value;
  else if (typeof reportedAutoNext === 'boolean') $('#auto-next').checked = reportedAutoNext;
  $('#show-subtitles').disabled = !active;
  $('#show-more').disabled = !free || (tv ? !tvConnected() : !state.connected && !state.configured);
  $('#show-tv-inputs').hidden = !tv || !matter.capabilities.inputs;
  $('#show-tv-inputs').disabled = !tvRemote;
  $('#show-matter-setup').hidden = !tv || !matter.available;
  $('#open-receiver').disabled = !luma;
  $('#open-tv-receiver').hidden = !tv;
  $('#open-tv-receiver').disabled = !free;
  $('#turn-on-tv').hidden = !tv || !matter.paired || matter.capabilities.powerOn !== true;
  $('#turn-on-tv').disabled = !free;
  $('#stop-video').disabled = !active;
  const bothScreens = screenReady('luma') && screenReady('philips'), movable = active && !!screenContent(state.target);
  $('#move-playback').hidden = !bothScreens;
  $('#move-playback').textContent = `Move to ${SCREEN_NAMES[otherScreen()]}`;
  $('#move-playback').disabled = !free || !movable;
  $('#sync-playback').hidden = !bothScreens && !sync.on;
  $('#sync-playback').textContent = sync.on ? 'Stop playing in step' : 'Play on both screens';
  $('#sync-playback').disabled = !free || !sync.on && !movable;
  $('#seek-range').disabled = !canSeek;
  renderSeekHistory();
  $('#remote-text').disabled = !luma;
  $('#send-text').disabled = !luma || !$('#remote-text').value.trim();
  $('#clear-text').disabled = !free || !$('#remote-text').value;
  $('#subtitles-off').disabled = !active;
  for (const button of $('#app-list').querySelectorAll('button')) button.disabled = !luma;
  for (const button of $('#subtitle-list').querySelectorAll('button')) button.disabled = !active;
  for (const button of document.querySelectorAll('button[data-panel]')) { button.disabled = !free; button.hidden = tv && ['typing', 'apps'].includes(button.dataset.panel); }
  $('#open-receiver').hidden = tv;
  $('#menu-key').hidden = tv;
  $('#capture-video').disabled = !free;
  $('#show-history').disabled = !free;
  $('#open-library').disabled = !free;
  renderLibraryControls();
  $('#choose-video').textContent = tv && !remote.connected ? 'Open TV receiver' : 'Choose video';
  $('#choose-video').disabled = tv && !remote.connected ? !free : !state.native;
  // Browse and Library are one tap away in the tab bar; only offer this when nothing is loaded.
  $('#choose-video').hidden = !(tv && !remote.connected) && !!(remote.playback?.active || remote.playback?.ended && remote.playback?.content);
  $('#cast-selected').hidden = !state.pendingMedia;
  $('#cast-selected').disabled = !free || state.pendingTarget !== state.target || (tv && remote.openUncertain === true);
  $('#cancel-handoff').hidden = !state.handoff;
  $('#cancel-handoff').textContent = state.setupFlow ? 'Cancel waiting' : 'Cancel casting';
  $('#retry-handoff').hidden = state.handoff || !state.pendingMedia || state.setupFlow;
}
function playback(snapshot, target = 'luma') {
  if (!snapshot || typeof snapshot.active !== 'boolean') return;
  const remote = receiver(target);
  if (remote.activeMedia && snapshot.active && !playbackMatchesMedia(remote.activeMedia, snapshot)) {
    if (target === state.target) clearScrubPreview();
    remote.activeMedia = null;
    remote.tracks = [];
    remote.catalog = [];
    remote.nextEpisode = null;
    if (target === state.target) {
      $('#media-title').textContent = 'Now playing';
      renderTracks();
    }
  }
  if (target === state.target && !snapshot.active) clearScrubPreview();
  remote.playback = snapshot;
  renderControls();
}
function volume(result, target = 'luma') {
  const value = result?.volume;
  if (value && Number.isInteger(value.current) && Number.isInteger(value.max) && value.max > 0 && value.current >= 0 && value.current <= value.max) {
    receiver(target).volume = { current: value.current, max: value.max };
    renderRemote();
  }
}
function remoteParams(extra = {}) {
  if (!state.activeMedia) return extra;
  const content = contentDescriptor(state.activeMedia.content);
  return { ...extra, expectedSourceUrl: state.activeMedia.url, ...(content ? { expectedContentKey: content.key } : {}) };
}
function playbackMatchesMedia(media, snapshot) {
  if (!media || !snapshot?.active || snapshot.sourceUrl !== media.url) return false;
  const expected = contentDescriptor(media.content);
  if (!expected) return true;
  return contentDescriptor(snapshot.content)?.key === expected.key;
}
function restoredRemote(value, observedPlayback) {
  if (!value || typeof value !== 'object' || typeof value.media?.url !== 'string' || !Array.isArray(value.tracks) || value.tracks.length > 128) return null;
  let url;
  try {
    url = new URL(value.media.url);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) return null;
  } catch { return null; }
  if (observedPlayback?.active === true && !playbackMatchesMedia({ url: url.href, ...mediaMetadata(value.media) }, observedPlayback)) return null;
  const indices = new Set();
  const tracks = [];
  for (const track of value.tracks) {
    if (!Number.isInteger(track?.index) || track.index < 0 || track.index > 1024 || indices.has(track.index)) return null;
    indices.add(track.index);
    tracks.push({ index: track.index, language: safeText(track.language, 64), label: safeText(track.label, 200) });
  }
  const catalog = [];
  const urls = new Set();
  const bound = new Set();
  if (value.catalog !== undefined) {
    if (!Array.isArray(value.catalog) || value.catalog.length > 128) return null;
    for (const track of value.catalog) {
      let trackUrl;
      try {
        trackUrl = new URL(track.url);
        if (!['http:', 'https:'].includes(trackUrl.protocol) || trackUrl.username || trackUrl.password || trackUrl.hash || urls.has(trackUrl.href)) return null;
      } catch { return null; }
      if (track.index !== null && (!Number.isInteger(track.index) || !indices.has(track.index) || bound.has(track.index))) return null;
      if (track.index !== null) bound.add(track.index);
      urls.add(trackUrl.href);
      if (track.preparedId !== undefined && (typeof track.preparedId !== 'string' || !/^[A-Za-z0-9_-]{16,128}$/u.test(track.preparedId))) return null;
      catalog.push({ url: trackUrl.href, contentType: 'text/vtt', language: safeText(track.language, 64), label: safeText(track.label, 200), index: track.index,
        ...(Number.isInteger(track.index) && track.preparedId ? { preparedId: track.preparedId } : {}) });
    }
  }
  const media = { url: url.href, contentType: safeText(value.media.contentType, 100), title: title(value.media.title), ...mediaMetadata(value.media) };
  return {
    // A service-restored remote is not a newly captured source. Never restore
    // its capture/job tokens or feed it to the handoff coordinator.
    media,
    tracks,
    catalog,
    selectedIndex: Number.isInteger(value.selectedIndex) && indices.has(value.selectedIndex) ? value.selectedIndex : null,
    subtitlesEnabled: value.subtitlesEnabled === true,
    nextEpisode: nextEpisodeSelection(value.nextEpisode, media),
  };
}
function connectionState(snapshot, { initial = false } = {}) {
  if (!snapshot || typeof snapshot !== 'object') return;
  if (initial && ['luma', 'philips'].includes(snapshot.selectedTarget)) state.target = snapshot.selectedTarget;
  if (snapshot.receivers?.philips) msxState(snapshot.receivers.philips);
  if (snapshot.settings && typeof snapshot.settings === 'object') {
    for (const key of ['hasReceiver', 'hasPairing', 'autoConnect']) if (typeof snapshot.settings[key] === 'boolean') state.settings[key] = snapshot.settings[key];
  }
  const connection = snapshot.connection;
  if (connection && typeof connection === 'object') {
    if (typeof connection.connected === 'boolean') state.connected = connection.connected;
    if (typeof connection.nativeConnected === 'boolean') state.nativeConnected = connection.nativeConnected;
    else if (typeof connection.connected === 'boolean') state.nativeConnected = connection.connected;
    if (typeof connection.receiverConnected === 'boolean') state.receiverConnected = connection.receiverConnected;
    else if (typeof connection.connected === 'boolean') state.receiverConnected = false;
    if (typeof connection.configured === 'boolean') state.configured = connection.configured;
    if (typeof connection.host === 'string' && privateHost(connection.host)) {
      if (state.host && state.host !== connection.host) {
        state.activeMedia = null; state.tracks = []; state.catalog = []; state.selectedIndex = null; state.nextEpisode = null;
        $('#media-title').textContent = 'Ready to cast';
        playback({ active: false });
        renderTracks();
      }
      state.host = connection.host;
      if (initial || !$('#receiver-host').value.trim()) $('#receiver-host').value = connection.host;
    }
    if (typeof connection.name === 'string') {
      state.name = safeText(connection.name);
      $('#receiver-name').textContent = state.name;
    }
  }
  volume(snapshot);
  if (state.connected && $('#message').textContent === 'Connecting to your saved projector…')
    message(state.nativeConnected ? 'Projector connected. Your remote is ready.' : 'LUMA Receiver is ready. You can cast now; projector navigation will connect later.');
  if (!state.handoff) {
    const remote = state.subtitleFlow ? null : restoredRemote(snapshot.remote, snapshot.playback);
    if (remote) {
      state.activeMedia = state.activeMedia?.url === remote.media.url ? Object.assign(state.activeMedia, remote.media) : remote.media;
      state.tracks = remote.tracks;
      state.catalog = remote.catalog;
      state.selectedIndex = remote.selectedIndex;
      state.subtitlesEnabled = remote.subtitlesEnabled;
      state.nextEpisode = remote.nextEpisode;
      $('#media-title').textContent = remote.media.title;
      renderTracks();
    }
    playback(snapshot.playback);
  }
  renderControls();
  if (!initial) restoreSelectedTarget(snapshot.selectedTarget);
  if (initial && state.target === 'philips') {
    page(tvConnected() || state.matter.paired ? 'remote' : 'devices');
    message(tvConnected() ? 'Philips TV connected. Your remote is ready.' : state.matter.paired ? 'Reconnecting to your paired TV…' : 'Pair your TV remote or connect its video receiver.');
  } else if (initial && (state.connected || (state.settings.hasReceiver && state.settings.hasPairing))) {
    page('remote');
    message(state.connected ? state.nativeConnected ? 'Projector connected. Your remote is ready.' : 'LUMA Receiver is ready. You can cast now; projector navigation will connect later.' : 'Connecting to your saved projector…');
  } else if (initial && state.settings.hasReceiver) {
    deviceStep('pair');
  }
}
async function command(method, params, success, consume) {
  if (state.busy || state.handoff) return false;
  state.busy = true;
  renderControls();
  try {
    const result = await (typeof method === 'function' ? method() : rpc(method, params));
    await consume?.(result);
    if (success) message(success);
    return true;
  } catch (error) {
    if (method === 'app.capture') reportStatus('error', error.code);
    if (typeof method === 'string' && method.startsWith('luma.') && ['CONNECTION_FAILED', 'TIMEOUT', 'NOT_CONNECTED', 'INVALID_RESPONSE'].includes(error.code)) {
      state.nativeConnected = false;
      state.connected = state.receiverConnected;
      state.apps = [];
      state.volume = null;
      $('#volume-level').textContent = '—';
      renderApps();
    }
    if (typeof method === 'string' && method.startsWith('msx.') && ['MSX_NOT_CONNECTED', 'NOT_CONNECTED', 'CONNECTION_FAILED'].includes(error.code)) state.philips.connected = false;
    if (['RECEIVER_AUTH', 'RECEIVER_CONFIG'].includes(error.code)) {
      state.configured = false;
      closeSheet();
      page('devices');
      deviceStep('pair');
    }
    message(errors[error.adapterCode] || error.message, error.code !== 'MATTER_SCAN_CANCELLED');
    return false;
  } finally {
    state.busy = false;
    applyDeferredTarget();
    renderControls();
    if (state.autoStart) void startCasting();
  }
}

function handoffParams(options, extra = {}) {
  return { ...extra, ...(options.phase === 'remote' ? {} : { sourceToken: state.jobToken }), expectedSourceUrl: options.expectedSourceUrl,
    ...(typeof options.expectedContentKey === 'string' ? { expectedContentKey: options.expectedContentKey } : {}) };
}
async function handoffRpc(method, params, options) {
  try { return await rpc(method, params, options); }
  catch (error) {
    if (['RECEIVER_AUTH', 'RECEIVER_CONFIG'].includes(error.code)) {
      if (options.phase === 'remote') { state.subtitleError = error; throw error; }
      state.setupError = error;
      coordinator.cancel();
    }
    throw error;
  }
}
const coordinator = createHandoffCoordinator({
  receiver: { launchReceiver: options => {
    if (state.skipNextLaunch) { state.skipNextLaunch = false; return Promise.resolve({ accepted: true }); }
    return rpc('luma.launchReceiver', handoffParams(options), options);
  } },
  // LUMA Receiver downloads and renders the catalog file itself, so there is no
  // phone-side prepare/delivery step (that existed only because Kodi needed it).
  player: {
    connect: options => handoffRpc('receiver.connect', handoffParams(options), options),
    open: (media, options) => handoffRpc('receiver.open', handoffParams(options, { media, ...(options.onlyIfInactive === true ? { onlyIfInactive: true } : {}) }), options),
    status: async options => { const result = await handoffRpc('receiver.status', handoffParams(options), options); playback(result); return result; },
    getSubtitles: options => handoffRpc('receiver.getSubtitles', handoffParams(options), options),
    addSubtitle: (track, options) => handoffRpc('receiver.addSubtitle', handoffParams(options, { track, ...(options.preparedId ? { preparedId: options.preparedId } : {}) }), options),
    setSubtitle: (index, options) => handoffRpc('receiver.setSubtitle', handoffParams(options, { index }), options),
  },
  remote: { async ready(result, options) {
    const selected = state.pendingMedia, token = state.jobToken;
    state.activeMedia = state.pendingMedia;
    state.tracks = result.tracks;
    state.catalog = result.catalog || [];
    state.selectedIndex = result.selectedIndex;
    state.subtitlesEnabled = result.selectedIndex !== null;
    $('#media-title').textContent = title(state.activeMedia?.title);
    state.subtitlePage = 0;
    renderTracks();
    page('remote');
    closeSheet();
    const remote = {
      media: { url: state.activeMedia.url, contentType: state.activeMedia.contentType, title: state.activeMedia.title, ...mediaMetadata(state.activeMedia) },
      tracks: result.tracks.map(track => ({ index: track.index, language: track.language, label: track.label })),
      catalog: state.catalog.map(track => ({ url: track.url, language: track.language, label: track.label, index: track.index, ...(track.preparedId ? { preparedId: track.preparedId } : {}) })),
      selectedIndex: result.selectedIndex,
      subtitlesEnabled: result.selectedIndex !== null,
    };
    // Give the service a bounded chance to remember aliases before reporting
    // completion. Receiver playback remains successful if local pausing fails.
    const resumeApplied = await resumeMedia(selected, 'luma', options.signal);
    if (options.signal.aborted || state.pendingMedia !== selected) throw fixedError('SOURCE_CHANGED');
    state.resumeNotice = resumeApplied ? '' : 'Video is playing, but the saved position could not be restored. Use the playback scrubber to continue.';
    await rpc('app.handoffReady', { sourceToken: token, remote, resumeApplied }, { timeoutMs: 2000 }).catch(() => {});
  } },
  onStatus({ step, message: text }) {
    reportStatus(step);
    const steps = ['launching', 'waiting', 'opening', 'subtitles', 'selecting', 'remote', 'ready'];
    $('#handoff-progress').value = Math.max(0, steps.indexOf(step) + 1);
    $('#handoff-label').textContent = text;
    message(text, step === 'error');
  },
});

async function startCasting() {
  if (!state.pendingMedia || state.handoff || state.busy) return;
  clearPendingVolume();
  state.autoStart = false;
  if (state.pendingTarget === 'philips') { await startMsxCasting(); return; }
  if (state.target !== 'luma') {
    message('This selected video belongs to LUMA. Select LUMA to continue.', true);
    return;
  }
  if (!state.connected || !state.configured) {
    reportStatus('setup');
    page('devices');
    deviceStep(state.connected ? 'pair' : 'receiver');
    message('Video selected. Finish connecting to start casting.');
    return;
  }
  const selected = state.pendingMedia;
  const generation = state.sourceGeneration;
  const id = ++flowId;
  state.sourceChanged = false;
  state.setupError = null;
  state.resumeNotice = '';
  state.handoff = true;
  const controller = new AbortController();
  state.flowController = controller;
  let castDone = null;
  let jobToken;
  sheet('handoff');
  renderControls();
  try {
    const begun = await rpc('app.beginHandoff', { sourceToken: selected.captureToken || selected.sourceToken }, { signal: controller.signal });
    if (typeof begun?.sourceToken !== 'string' || !begun.sourceToken) throw fixedError('SOURCE_CHANGED');
    jobToken = begun.sourceToken;
    if (id !== flowId || state.sourceGeneration !== generation || state.pendingMedia !== selected) throw fixedError('SOURCE_CHANGED');
    state.jobToken = jobToken;
    selected.sourceToken = jobToken;
    const result = await coordinator.start(selected, { signal: controller.signal, isCurrentSource: () => state.sourceGeneration === generation && state.pendingMedia === selected });
    if (id === flowId) {
      state.pendingMedia = null;
      message(state.resumeNotice || 'Video is playing. Choose a language in Subtitles.', !!state.resumeNotice);
      castDone = selected;
    }
  } catch (error) {
    cancelNative(jobToken);
    if (id !== flowId) return;
    const text = state.sourceChanged ? errors.SOURCE_CHANGED : state.setupError?.message || errors[error.adapterCode] || error.message;
    reportStatus(error.code === 'CANCELLED' ? 'cancelled' : 'error', state.setupError?.code || error.code);
    if (state.setupError) {
      state.configured = false;
      closeSheet();
      page('devices');
      deviceStep('pair');
    }
    message(text, error.code !== 'CANCELLED');
    $('#handoff-label').textContent = text;
  } finally { if (id === flowId) { state.handoff = false; state.flowController = null; applyDeferredTarget(); renderControls(); } }
  if (castDone && id === flowId) {
    await restoreBrightness('luma', castDone);
    await restoreCaption('luma', castDone);
  }
}
async function startMsxCasting() {
  if (state.target !== 'philips') { message('Select Philips to continue casting this video.', true); return; }
  if (state.philips.openUncertain) { message('Wait while the TV finishes the previous request.'); return; }
  if (!state.philips.connected) { page('devices'); message('Connect your TV receiver to start this video.'); return; }
  const selected = state.pendingMedia;
  const generation = state.sourceGeneration;
  const id = ++flowId;
  let castDone = null;
  const controller = new AbortController();
  state.flowController = controller;
  state.sourceChanged = false;
  state.handoff = true;
  sheet('handoff');
  $('#handoff-label').textContent = 'Starting this video on Philips TV…';
  $('#handoff-progress').value = 2;
  let jobToken;
  try {
    // The capture token and destination stay fixed for the entire handoff.
    const begun = await rpc('app.beginHandoff', { sourceToken: selected.captureToken || selected.sourceToken, target: 'philips' }, { signal: controller.signal });
    if (typeof begun?.sourceToken !== 'string' || !begun.sourceToken) throw fixedError('SOURCE_CHANGED');
    jobToken = begun.sourceToken;
    state.jobToken = jobToken;
    if (generation !== state.sourceGeneration || selected !== state.pendingMedia) throw fixedError('SOURCE_CHANGED');
    const result = await rpc('msx.open', {
      sourceToken: jobToken, source: selected.url, mimeType: selected.contentType,
      title: title(selected.title), subtitles: (selected.tracks || []).map(track => ({ url: track.url, language: track.language, label: track.label })),
      preferredLanguage: 'pt-BR',
      autoSubtitles: false,
    }, { signal: controller.signal, timeoutMs: 60000 });
    if (id !== flowId) return;
    state.philips.activeMedia = { url: selected.url, contentType: selected.contentType, title: title(selected.title), ...mediaMetadata(selected) };
    state.philips.catalog = (selected.tracks || []).map((track, index) => ({ ...track, index }));
    msxState(result);
    const resumeApplied = await resumeMedia(selected, 'philips', controller.signal);
    if (id !== flowId || generation !== state.sourceGeneration) return;
    state.pendingMedia = null;
    await rpc('app.handoffReady', { target: 'philips', sourceToken: jobToken, resumeApplied }, { timeoutMs: 2000 }).catch(() => {});
    closeSheet(); page('remote');
    message(resumeApplied ? 'Ready on Philips TV. You can use the remote.' : 'Video is playing, but the saved position could not be restored. Use the playback scrubber to continue.', !resumeApplied);
    castDone = selected;
  } catch (error) {
    cancelNative(jobToken);
    if (id !== flowId) return;
    const uncertain = ['TIMEOUT', 'MSX_TIMEOUT', 'CANCELLED'].includes(error.code);
    // Even an explicit subtitle rejection can leave a correctly playing video.
    // Reconcile every failed Open before offering a retry of the whole handoff.
    const result = await rpc('msx.status', {}, { timeoutMs: 5000 }).catch(() => null);
    if (result) msxState(result);
    const snapshot = state.philips.playback;
    if (result && snapshot?.active && snapshot.sourceUrl === selected.url && !result.pendingCommand) {
      state.philips.activeMedia = { url: selected.url, contentType: selected.contentType, title: title(selected.title), ...mediaMetadata(selected) };
      state.philips.catalog = (selected.tracks || []).map((track, index) => ({ ...track, index }));
      state.pendingMedia = null;
      // A reconciled Open must not silently discard saved progress. Seek once,
      // then keep playing even if position cannot be verified.
      const resumeApplied = await resumeMedia(selected, 'philips', controller.signal);
      if (id !== flowId || generation !== state.sourceGeneration) return;
      await rpc('app.handoffReady', { target: 'philips', sourceToken: jobToken, resumeApplied }, { timeoutMs: 2000 }).catch(() => {});
      closeSheet(); page('remote');
      message(!resumeApplied ? 'Video is playing, but the saved position could not be restored. Use the playback scrubber to continue.' : state.philips.subtitlesEnabled ? 'Video is playing on Philips TV.' : 'Video is playing. Choose subtitles in the remote to retry them.', !resumeApplied);
    } else {
      // A delivered command may finish after the phone's timeout. Do not offer a
      // second Open while its result is unknown; refresh observes the first one.
      state.philips.openUncertain = uncertain && (!result || Boolean(result.pendingCommand));
      const text = state.philips.openUncertain ? 'The TV is still checking this video. Wait a moment before trying again.' : error.message;
      message(text, error.code !== 'CANCELLED');
      $('#handoff-label').textContent = text;
      if (state.philips.openUncertain) { closeSheet(); page('remote'); }
    }
  } finally {
    if (id === flowId) { state.handoff = false; state.flowController = null; applyDeferredTarget(); renderControls(); }
  }
  if (castDone && id === flowId) {
    await restoreBrightness('philips', castDone);
    await restoreCaption('philips', castDone);
  }
}
window.LumaApp = Object.freeze({
  onConnectionState(snapshot) { connectionState(snapshot); },
  onOpenRemote() {
    if (!state.handoff) closeSheet();
    page('remote');
  },
  onPlayNext() {
    if (!state.handoff) closeSheet();
    page('remote');
    void playNextEpisode();
  },
  onCapturedMedia(media) {
    clearScrubPreview();
    if (!media || typeof media.url !== 'string') { message('No supported video was found. Open its player and try Cast again.', true); return; }
    reportStatus('captured');
    if (state.setupFlow) {
      state.sourceGeneration++;
      state.pendingMedia = { ...media, resumeSeconds: 0, ...mediaMetadata(media), captureToken: media.sourceToken, title: title(media.title) };
      state.pendingTarget = state.target;
      state.autoStart = true;
      return;
    }
    const interruptedMsx = state.handoff && state.pendingTarget === 'philips';
    if (interruptedMsx) state.philips.openUncertain = true;
    if (state.handoff) cancelNative(state.jobToken);
    state.flowController?.abort();
    coordinator.cancel();
    flowId++;
    state.handoff = false;
    state.sourceGeneration++;
    state.pendingMedia = { ...media, resumeSeconds: 0, ...mediaMetadata(media), captureToken: media.sourceToken, title: title(media.title) };
    state.pendingTarget = state.target;
    state.autoStart = true;
    void startCasting();
    if (interruptedMsx) void rpc('msx.status', {}, { timeoutMs: 5000 }).then(msxState).catch(() => {});
  },
  onSourceChanged() {
    clearScrubPreview();
    state.sourceGeneration++;
    state.pendingMedia = null;
    state.autoStart = false;
    if (state.handoff && !state.setupFlow) {
      state.sourceChanged = true;
      cancelNative(state.jobToken);
      state.flowController?.abort();
      coordinator.cancel();
      message(errors.SOURCE_CHANGED, true);
    }
  },
});

function languageRank(track) {
  const language = safeText(track.language).toLowerCase().replaceAll('_', '-');
  if (language === 'pt-br' || language === 'por-br') return 0;
  if (language === 'en' || language.startsWith('en-') || language === 'eng') return 1;
  if (language === 'pt' || language.startsWith('pt-')) return 2;
  return 3;
}
function paged(items, value, index) {
  const query = value.trim().toLocaleLowerCase();
  const filtered = items.filter(item => item.name.toLocaleLowerCase().includes(query));
  const total = Math.max(1, Math.ceil(filtered.length / 3));
  const current = Math.min(index, total - 1);
  return { items: filtered.slice(current * 3, current * 3 + 3), current, total };
}
function listButton(label, click, pressed) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = label;
  if (typeof pressed === 'boolean') button.setAttribute('aria-pressed', String(pressed));
  button.addEventListener('click', click);
  return button;
}
function renderApps() {
  const result = paged(state.apps.map(app => ({ ...app, name: safeText(app.label) || 'Unnamed app' })), $('#app-search').value, state.appPage);
  state.appPage = result.current;
  $('#app-list').replaceChildren(...result.items.map(app => listButton(app.name, () => command('luma.launchApp', { packageName: app.packageName }, 'App launch sent. Check the projector.'))));
  if (!result.items.length) $('#app-list').textContent = 'No matching apps.';
  $('#apps-page').textContent = `${result.current + 1} / ${result.total}`;
  $('#apps-previous').disabled = result.current === 0;
  $('#apps-next').disabled = result.current >= result.total - 1;
  renderControls();
}
function renderTracks() {
  const remote = receiver();
  const tracks = [...(remote.catalog.length ? remote.catalog : remote.tracks)].sort((a, b) => languageRank(a) - languageRank(b) || safeText(a.label).localeCompare(safeText(b.label))).map(track => ({ ...track, name: safeText(track.label) || safeText(track.language) || (Number.isInteger(track.index) ? `Track ${track.index + 1}` : 'Subtitle track') }));
  const result = paged(tracks, $('#subtitle-search').value, remote.subtitlePage);
  remote.subtitlePage = result.current;
  $('#subtitle-list').replaceChildren(...result.items.map(track => listButton(track.name, () => selectSubtitle(track), Number.isInteger(track.index) && remote.subtitlesEnabled && remote.selectedIndex === track.index)));
  if (!result.items.length) $('#subtitle-list').textContent = 'No matching subtitle tracks.';
  $('#subtitles-off').setAttribute('aria-pressed', String(!remote.subtitlesEnabled));
  $('#subtitles-page').textContent = `${result.current + 1} / ${result.total}`;
  $('#subtitles-previous').disabled = result.current === 0;
  $('#subtitles-next').disabled = result.current >= result.total - 1;
  renderControls();
}
function remoteDescriptor() {
  return {
    media: { url: state.activeMedia.url, contentType: state.activeMedia.contentType, title: state.activeMedia.title, ...mediaMetadata(state.activeMedia) },
    tracks: state.tracks.map(track => ({ index: track.index, language: track.language, label: track.label })),
    catalog: state.catalog.map(track => ({ url: track.url, language: track.language, label: track.label, index: track.index, ...(track.preparedId ? { preparedId: track.preparedId } : {}) })),
    selectedIndex: state.selectedIndex,
    subtitlesEnabled: state.subtitlesEnabled,
  };
}
async function selectMsxSubtitle(index, enabled) {
  const tv = state.philips;
  if (state.target !== 'philips' || !tv.connected || !tv.playback?.active || state.busy || state.handoff) return;
  const source = tv.activeMedia?.url || tv.playback.sourceUrl;
  if (!source) return;
  message(enabled ? 'Loading the selected language…' : 'Turning subtitles off…');
  await command(async () => {
    const result = await rpc('msx.subtitle', { index, enabled, expectedSourceUrl: source }, { timeoutMs: 60000 });
    msxState(result);
    if (result?.accepted !== true || !Number.isSafeInteger(result.id) || result.id <= 0 || result.lastCommand?.id !== result.id || result.lastCommand?.method !== 'subtitle' || result.lastCommand?.ok !== true) throw fixedError('MSX_REJECTED');
    if (state.philips.playback?.sourceUrl !== source) throw fixedError('SOURCE_CHANGED');
    if (state.philips.subtitlesEnabled !== enabled || enabled && state.philips.selectedIndex !== index) throw fixedError('MSX_SUBTITLE');
    rememberCaption(tv.activeMedia, enabled ? safeText(tv.catalog.find(track => track.index === index)?.language) : '');
    renderTracks();
  }, {}, enabled ? 'Subtitle selected. Check the TV.' : 'Subtitles turned off.');
}
async function selectSubtitle(track) {
  if (state.busy || state.handoff) return;
  if (state.target === 'philips') {
    await selectMsxSubtitle(track.index, true);
    return;
  }
  if (!track.url) {
    void command('receiver.setSubtitle', remoteParams({ index: track.index }), 'Subtitle selected. Check the projector.', () => { state.selectedIndex = track.index; state.subtitlesEnabled = true; renderTracks(); });
    return;
  }
  const media = state.activeMedia;
  if (!media) return;
  const catalog = state.catalog.map(item => ({ ...item }));
  message(Number.isInteger(track.index) ? 'Selecting subtitles…' : 'Loading the selected language…');
  state.subtitleFlow = true;
  state.subtitleError = null;
  try {
    await command(async () => {
      try {
        return await coordinator.selectSubtitle({ ...media, tracks: catalog }, track.url, {
          knownCatalog: catalog,
          isCurrentSource: () => state.activeMedia === media && state.configured,
        });
      } catch (error) { throw state.subtitleError || error; }
    }, {}, 'Subtitle selected. Check the projector.', async result => {
      if (state.activeMedia !== media) throw fixedError('SOURCE_CHANGED');
      state.tracks = result.tracks;
      state.catalog = result.catalog;
      state.selectedIndex = result.selectedIndex;
      state.subtitlesEnabled = result.selectedIndex !== null;
      if (state.subtitlesEnabled) rememberCaption(media, safeText(track.language));
      renderTracks();
      await rpc('app.rememberRemote', remoteParams({ remote: remoteDescriptor() }));
    });
  } finally { state.subtitleFlow = false; state.subtitleError = null; }
}

async function browse(navigate = false) {
  closeSheet();
  page('browse');
  try {
    if (navigate) {
      const value = $('#browser-url').value.trim() || DEFAULT_SITE;
      const url = new URL(value);
      if (url.protocol !== 'https:' || url.username || url.password) throw fixedError('INVALID_INPUT');
      await rpc('app.browse', { url: url.href });
    } else await rpc('app.browse', {});
  } catch (error) { message(error.message || errors.INVALID_INPUT, true); }
}
$('#tab-browse').addEventListener('click', () => browse());
$('#tab-remote').addEventListener('click', () => { closeSheet(); page('remote'); });
$('#tab-devices').addEventListener('click', () => { closeSheet(); page('devices'); });
for (const target of ['luma', 'philips']) $(`#target-${target}`).addEventListener('click', () => void selectTarget(target));
$('#show-tv-setup').addEventListener('click', () => { closeSheet(); page('devices'); });
for (const mode of ['remote', 'video']) $(`#tv-setup-${mode}`).addEventListener('click', () => { state.tvSetup = mode; renderControls(); });
async function pairMatter(setupCode) {
  const trimmed = setupCode.trim();
  const code = trimmed.startsWith('MT:') ? trimmed : trimmed.replace(/[\s-]/gu, '');
  if (!/^(?:\d{11}|\d{21}|MT:[A-Z0-9.\-]{10,240})$/u.test(code)) { message(errors.MATTER_CODE, true); return; }
  $('#matter-code').value = '';
  state.matter.pairing = true;
  delete state.matter.errorCode;
  message('Pairing with your TV…');
  try {
    if (await command(async () => {
      matterState(await rpc('matter.pair', { setupCode: code }, { timeoutMs: 30000 }));
      const deadline = performance.now() + 150000;
      while (!state.matter.paired || !state.matter.connected) {
        if (state.matter.errorCode) throw fixedError(state.matter.errorCode);
        if (!state.matter.pairing && !state.matter.paired || performance.now() >= deadline) throw fixedError('MATTER_PAIRING');
        await new Promise(resolve => setTimeout(resolve, 500));
        matterState(await rpc('matter.status', {}, { timeoutMs: 5000 }));
      }
      return state.matter;
    }, {}, 'TV paired. Your remote is ready.', result => matterState(result))) page('remote');
  } finally { state.matter.pairing = false; renderControls(); }
}
$('#matter-form').addEventListener('submit', event => { event.preventDefault(); void pairMatter($('#matter-code').value); });
$('#matter-scan').addEventListener('click', async () => {
  let setupCode;
  await command(() => rpc('app.scanMatterCode', {}, { timeoutMs: 120000 }), {}, '', result => { if (typeof result?.setupCode === 'string') setupCode = result.setupCode; });
  if (setupCode) await pairMatter(setupCode);
  else $('#matter-code').focus();
});
$('#matter-reconnect').addEventListener('click', () => command('matter.status', { reconnect: true }, '', matterState));
$('#matter-share').addEventListener('click', () => command('matter.openWindow', {}, '', result => {
  const code = typeof result?.manualCode === 'string' ? result.manualCode.replace(/[\s-]/gu, '') : '';
  if (!/^(?:\d{11}|\d{21})$/u.test(code) || !Number.isFinite(result.expiresAt) || result.expiresAt <= Date.now()) throw fixedError('MATTER_REJECTED');
  sheet('matter-share');
  $('#matter-share-code').textContent = code;
  if (typeof result.qrPng === 'string' && /^data:image\/png;base64,[A-Za-z0-9+/=]+$/u.test(result.qrPng) && result.qrPng.length < 300000) { $('#matter-qr').src = result.qrPng; $('#matter-qr').hidden = false; }
  $('#matter-share-expiry').textContent = 'This code expires in a few minutes. Keep it private.';
  state.matterShareTimer = setTimeout(() => { if (state.sheet === 'matter-share') { closeSheet(); message('Pairing code expired. Open Add another phone to create a new one.'); } }, Math.min(900000, result.expiresAt - Date.now()));
}));
$('#show-matter-setup').addEventListener('click', () => { state.tvSetup = 'remote'; closeSheet(); page('devices'); renderControls(); });
$('#show-tv-inputs').addEventListener('click', () => {
  $('#tv-input-list').replaceChildren(...state.matter.inputs.map(input => listButton(input.name + (input.index === state.matter.currentInput ? ' · selected' : ''), () => command('matter.input', { index: input.index }, 'TV input selected.', matterState))));
  sheet('inputs');
});
for (const entry of ['browser', 'msx']) $(`#tv-entry-${entry}`).addEventListener('click', () => {
  if (state.busy || state.handoff) return;
  state.tvEntry = entry;
  state.philips.typingNotice = '';
  state.philips.typingNoticeError = false;
  renderControls();
});
$('#browser-manual').addEventListener('click', () => {
  if (state.busy || state.handoff) return;
  state.tvBrowserManual = true;
  state.philips.typingNotice = '';
  renderControls();
});
async function openTvReceiver() {
  if (state.target !== 'philips' || state.busy || state.handoff) return;
  state.tvEntry = 'browser';
  state.tvSetup = 'video';
  await tvTextCommand('msx.launchBrowser', {}, async result => {
    if (result?.permissionRequired) {
      closeSheet(); page('devices');
      state.philips.typingNotice = 'Enable TV browser control with the TV’s PIN, then tap Open TV receiver again.';
      message('Enable TV browser control once to continue.');
      return;
    }
    if (result?.reused === true) {
      closeSheet(); page('remote');
      message('TV receiver is connected. If it is not visible, open its saved LUMA bookmark on the TV.');
      return;
    }
    if (result?.accepted !== true || result.pageLoadVerified !== false) throw fixedError('TEXT_REJECTED');
    msxState(result);
    closeSheet(); page('devices');
    state.philips.textOperation = null;
    state.philips.typingNotice = TV_BROWSER_WAIT;
    message(TV_BROWSER_WAIT); renderControls();
    const deadline = performance.now() + 12000;
    while (!state.philips.connected && !state.philips.awaitingApproval && performance.now() < deadline) {
      await new Promise(resolve => setTimeout(resolve, 500));
      const observed = await rpc('msx.status', {}, { timeoutMs: 3000 }).catch(() => null);
      if (observed) msxState(observed);
    }
    state.philips.typingNotice = state.philips.connected || state.philips.awaitingApproval ? '' : TV_BROWSER_WAIT;
    if (state.philips.connected) { closeSheet(); page('remote'); message('TV receiver connected. Your remote is ready.'); }
    else if (state.philips.awaitingApproval) message('Your TV requested a connection. Allow it below.');
    else {
      state.philips.typingNotice = TV_BROWSER_BOOKMARK;
      message(TV_BROWSER_BOOKMARK);
    }
  });
}
$('#browser-launch').addEventListener('click', () => { if (state.tvEntry === 'browser') void openTvReceiver(); });
$('#open-tv-receiver').addEventListener('click', () => void openTvReceiver());
$('#turn-on-tv').addEventListener('click', () => {
  if (state.target !== 'philips' || state.busy || state.handoff || !state.matter.paired || state.matter.capabilities.powerOn !== true) return;
  void command(async () => {
    try {
      if (state.matter.paused) matterState(await rpc('matter.status', { reconnect: true }, { timeoutMs: 15000 }));
      if (!state.matter.paired || state.matter.capabilities.powerOn !== true) throw fixedError('MATTER_UNSUPPORTED');
      const result = await rpc('matter.powerOn', {}, { timeoutMs: 20000 });
      if (result?.accepted !== true) throw fixedError('MATTER_REJECTED');
      return result;
    } catch (error) {
      if (['MATTER_NOT_CONNECTED', 'MATTER_TIMEOUT'].includes(error.code)) {
        const failure = fixedError(error.code);
        failure.message = 'TV could not be reached. Check that “Ligue com LAN” / network standby is enabled on the TV.';
        throw failure;
      }
      throw error;
    }
  }, {}, 'TV accepted the turn-on request. Check its screen.', matterState);
});
$('#msx-start').addEventListener('click', () => command('msx.start', {}, state.tvEntry === 'browser' ? 'Prepare the TV Internet browser address bar, then tap Type browser link.' : 'Prepare the address field in Media Station X setup, then tap Enter TV address.', msxState));
$('#msx-refresh').addEventListener('click', () => command('msx.status', {}, '', msxState));
function tvTextCommand(method, params, consume) {
  if (state.target !== 'philips' || state.busy || state.handoff) return Promise.resolve(false);
  const remote = state.philips;
  remote.textOperation = method;
  remote.typingNotice = '';
  remote.typingNoticeError = false;
  return command(async () => {
    try {
      if (method === 'msx.launchBrowser') {
        if (remote.connected) {
          msxState(await rpc('msx.status', {}, { timeoutMs: 5000 }));
          if (remote.connected) return { reused: true };
        }
        if (!remote.startupUrl || remote.configured === false && remote.pairingOpen === false) msxState(await rpc('msx.start', {}, { timeoutMs: 15000 }));
        if (!remote.textPermissionSaved) return { permissionRequired: true };
        if (!remote.typingAvailable) throw fixedError('TEXT_ADDRESS_UNAVAILABLE');
      }
      return await rpc(method, params, { timeoutMs: method === 'msx.typeBrowserAddress' ? 120000 : method === 'msx.typeAddress' ? 90000 : 60000 });
    }
    catch (error) {
      const code = Object.hasOwn(TV_TEXT_ERRORS, error.code) ? error.code : error.code === 'TIMEOUT' ? 'TEXT_TIMEOUT' : 'TEXT_REJECTED';
      const observed = await rpc('msx.status', {}, { timeoutMs: 5000 }).catch(() => null);
      if (observed) msxState(observed);
      const notice = method === 'msx.launchBrowser' && code === 'TEXT_TIMEOUT' ? 'The TV did not confirm the launch request. Check its screen before trying again.' : method === 'msx.launchBrowser' && code === 'TEXT_REJECTED' ? 'The TV did not accept the browser launch. Use Enter link manually.' : method === 'msx.launchBrowser' && code === 'TEXT_CANCELLED' ? 'TV launch stopped. Check its screen before trying again.' : method === 'msx.textPairGrant' && ['TEXT_REJECTED', 'TEXT_INVALID_PIN'].includes(code) ? 'The TV did not accept this PIN. Check the code on the TV and try again.' : !['msx.typeAddress', 'msx.typeBrowserAddress'].includes(method) && code === 'TEXT_TIMEOUT' ? 'The TV has not confirmed permission. Check the TV, then refresh before trying again.' : TV_TEXT_ERRORS[code];
      remote.typingNotice = notice;
      remote.typingNoticeError = true;
      if (code === 'TEXT_BROWSER_UNSUPPORTED') state.tvBrowserManual = true;
      const failure = fixedError(code); failure.message = notice;
      throw failure;
    }
  }, {}, '', consume).finally(() => { remote.textOperation = null; renderControls(); });
}
$('#msx-text-pair').addEventListener('click', () => {
  if (!state.philips.startupUrl || state.philips.textPermissionSaved || state.philips.textPairing) return;
  $('#msx-text-pin').value = '';
  void tvTextCommand('msx.textPairRequest', {}, result => {
    if (result?.pairing !== true) throw fixedError('TEXT_REJECTED');
    state.philips.textPairing = true;
    state.philips.textPairingExpiresInSeconds = result.expiresInSeconds;
    state.philips.typingNotice = '';
    renderControls(); $('#msx-text-pin').focus();
    message('Enter the four-digit PIN shown on the TV.');
  });
});
$('#msx-text-form').addEventListener('submit', event => {
  event.preventDefault();
  if (!state.philips.textPairing) return;
  const pin = $('#msx-text-pin').value;
  if (!/^[0-9]{4}$/u.test(pin)) { message(TV_TEXT_ERRORS.TEXT_INVALID_PIN, true); return; }
  $('#msx-text-pin').value = '';
  void tvTextCommand('msx.textPairGrant', { pin }, result => {
    msxState(result);
    state.philips.typingNotice = state.philips.textPermissionSaved ? state.tvEntry === 'browser' ? 'Permission saved. Tap Open TV receiver to request the TV page.' : 'Permission saved. Return to MSX Setup and select and clear its address field.' : TV_TEXT_ERRORS[result?.textError] || TV_TEXT_ERRORS.TEXT_UNSUPPORTED;
    state.philips.typingNoticeError = !state.philips.textPermissionSaved;
    message(state.philips.textPermissionSaved ? state.tvEntry === 'browser' ? 'TV permission saved. Tap Open TV receiver next.' : 'TV permission saved. Prepare its setup address field next.' : state.philips.typingNotice, !state.philips.textPermissionSaved);
  });
});
$('#msx-type-address').addEventListener('click', () => {
  const tv = state.philips;
  if (!tv.typingAvailable || !tv.startupUrl || tv.typingBusy || tv.configured === false && tv.pairingOpen === false) return;
  const browserEntry = state.tvEntry === 'browser';
  if (browserEntry && !tv.browserSetupUrl) return;
  const expectedChars = browserEntry ? tv.browserSetupUrl.length : new URL(tv.startupUrl).host.length;
  tv.typingNotice = '';
  void tvTextCommand(browserEntry ? 'msx.typeBrowserAddress' : 'msx.typeAddress', {}, result => {
    if (result?.accepted !== true || result.acknowledgedChars !== expectedChars || browserEntry && (result.textEntryMode !== 'browser' || result.totalChars !== expectedChars)) throw fixedError('TEXT_REJECTED');
    tv.typingNotice = browserEntry ? TV_BROWSER_ACK : TV_TEXT_ACK;
    tv.typingNoticeError = false;
    message('Input acknowledged. Check the TV address field.');
  });
});
$('#msx-developer').addEventListener('toggle', renderControls);
for (const [id, method, receiptMethod, notice] of [
  ['msx-debug-ping', 'msx.debugPing', 'ping', 'TV ping acknowledged.'],
  ['msx-debug-label', 'msx.debugLabel', 'set-label', 'Test message acknowledged by the TV.'],
  ['msx-debug-reload', 'msx.reloadReceiver', 'reload', 'Reload acknowledged. Waiting for the TV to reconnect…'],
]) $(`#${id}`).addEventListener('click', () => {
  const tv = state.philips, debug = tv.playback?.receiverDebug;
  if (state.target !== 'philips' || !state.native || state.busy || state.handoff || !tv.connected || tv.pendingCommand) return;
  if (method === 'msx.reloadReceiver' ? debug?.remoteReload !== true || tv.playback?.active !== false : debug?.remoteCommands !== true) return;
  tv.debugReloadHelloBaseline = null;
  tv.debugNotice = 'Waiting for the TV acknowledgement…'; tv.debugNoticeError = false;
  void command(async () => {
    try {
      const result = await rpc(method, {}, { timeoutMs: 20000 });
      if (result?.accepted !== true || !Number.isSafeInteger(result.id) || result.id <= 0 || result.lastCommand?.id !== result.id || result.lastCommand?.method !== receiptMethod || result.lastCommand?.ok !== true) throw fixedError('MSX_REJECTED');
      msxState(result);
      if (method === 'msx.reloadReceiver' && Number.isSafeInteger(result.diagnostics?.helloAccepted) && result.diagnostics.helloAccepted >= 0) tv.debugReloadHelloBaseline = result.diagnostics.helloAccepted;
      tv.debugNotice = notice; message(notice); return result;
    } catch (error) {
      const uncertain = ['TIMEOUT', 'MSX_TIMEOUT', 'CANCELLED'].includes(error.code);
      tv.debugNotice = uncertain ? 'TV command outcome is unknown. Check the TV before trying again.' : 'The TV did not confirm this developer command. Check its receiver status.';
      tv.debugNoticeError = true;
      const failure = fixedError(error.code || 'MSX_REJECTED'); failure.message = tv.debugNotice; throw failure;
    }
  }, {}, '');
});
$('#msx-sample').addEventListener('click', async () => {
  const tv = state.philips;
  if (state.target !== 'philips' || !state.native || state.busy || state.handoff || !tv.connected || tv.pendingCommand || tv.openUncertain) return;
  clearPendingVolume();
  const afterReceipt = tv.lastReceiptId || 0;
  state.busy = true;
  renderControls();
  message('Starting the sample on Philips TV…');
  try {
    // Native owns the fixed public sample and its separate authorization.
    // No captured URL or fabricated capture token is supplied by the UI.
    const result = await rpc('msx.sample', {}, { timeoutMs: 60000 });
    msxState(result);
    if (result?.accepted !== true || !Number.isSafeInteger(result.id) || result.lastCommand?.id !== result.id || result.lastCommand?.ok !== true || result.playback?.active !== true) throw fixedError('MSX_REJECTED');
    page('remote');
    message('Sample is playing on Philips TV. Your remote is ready.');
  } catch (error) {
    const uncertain = ['TIMEOUT', 'MSX_TIMEOUT', 'CANCELLED'].includes(error.code);
    if (uncertain) tv.openUncertain = true;
    // A JS bridge deadline is not proof that native finished. An old status
    // snapshot cannot make this sample safe to send a second time.
    if (error.code === 'TIMEOUT' || error.code === 'CANCELLED') { tv.sampleBridgeUncertain = true; tv.sampleAfterReceipt = afterReceipt; }
    const observed = await rpc('msx.status', {}, { timeoutMs: 5000 }).catch(() => null);
    if (observed) msxState(observed);
    const reason = observed?.lastCommand?.error || observed?.playback?.error;
    if (['PLAYBACK_REJECTED', 'PLAYBACK_TIMEOUT'].includes(reason)) message('Press “Start playback” on the TV, then refresh playback here.', true);
    else if (uncertain) message('The TV has not confirmed the sample yet. Check the TV and refresh playback before retrying.', true);
    else message(errors[error.code] || 'The sample could not start on the TV. Check playback and try again.', true);
  } finally {
    state.busy = false;
    applyDeferredTarget();
    renderControls();
    if (state.autoStart) void startCasting();
  }
});
$('#msx-confirm').addEventListener('click', async () => {
  const host = state.philips.pendingHost;
  if (!host) return;
  if (await command('msx.confirm', { host }, 'TV allowed. Waiting for the TV receiver…', msxState)) {
    if (state.philips.connected) { page('remote'); if (state.pendingMedia) void startCasting(); }
  }
});
$('#show-settings').addEventListener('click', () => sheet('settings'));
$('#edit-receiver').addEventListener('click', () => { closeSheet(); page('devices'); deviceStep('receiver'); });
$('#edit-pairing').addEventListener('click', () => { closeSheet(); page('devices'); deviceStep('pair'); });
$('#show-reset').addEventListener('click', () => {
  if (state.target === 'philips') { sheet('reset'); return; }
  sheet('reset');
});
$('#forget-matter-local').addEventListener('click', async () => {
  if (state.target !== 'philips' || state.sheet !== 'reset' || !state.matter.paired || state.busy) return;
  clearPendingVolume();
  if (await command('matter.forget', { confirmed: true, localOnly: true }, 'Pairing forgotten on this phone. The TV may still list this phone until removed in its settings.', matterState)) { closeSheet(); page('devices'); }
});
for (const button of document.querySelectorAll('[data-reset]')) button.addEventListener('click', async () => {
  clearPendingVolume();
  if (state.target === 'philips') {
    if (state.matter.paired && state.tvSetup === 'remote') {
      if (await command('matter.forget', { confirmed: true }, 'TV pairing removed from this phone.', matterState)) { closeSheet(); page('devices'); }
      return;
    }
    if (await command('msx.reset', {}, 'TV pairing forgotten. Set up Media Station X again.', result => {
      state.philips = { connected: false, host: '', name: 'Philips TV', startupUrl: '', awaitingApproval: false, pendingHost: '', playback: null, activeMedia: null, tracks: [], catalog: [], selectedIndex: null, subtitlesEnabled: false, volume: null, subtitlePage: 0 };
      msxState(result);
    })) { closeSheet(); page('devices'); }
    return;
  }
  const scope = button.dataset.reset;
  const success = await command('app.settings.reset', { scope }, 'Saved settings forgotten. A playing video stays on the projector.', result => {
    if (scope !== 'receiver') {
      state.configured = false;
      state.settings.hasPairing = false;
      state.pairingId = null;
      $('#pair-code').value = '';
      $('#pair-form').hidden = true;
    }
    if (scope !== 'pairing') {
      state.connected = false;
      state.nativeConnected = false;
      state.receiverConnected = false;
      state.configured = false;
      state.settings.hasReceiver = false;
      state.host = '';
      state.name = '';
      state.volume = null;
      $('#receiver-host').value = '';
      $('#receiver-name').textContent = '';
      $('#volume-level').textContent = '—';
    }
    connectionState(result);
  });
  if (success) { closeSheet(); page('devices'); deviceStep(scope === 'pairing' ? 'pair' : 'receiver'); }
});
$('#choose-video').addEventListener('click', () => state.target === 'philips' && !state.philips.connected ? openTvReceiver() : browse());
function libraryIdentity(value) {
  return value && ['movie', 'tv'].includes(value.kind) && typeof value.id === 'string' && /^[1-9][0-9]{0,9}$/.test(value.id) ? {kind:value.kind,id:value.id} : null;
}
function savedProgress(value) {
  if (!value || typeof value !== 'object') return null;
  for (const key of ['positionSeconds','durationSeconds','resumeSeconds']) if (!Number.isFinite(value[key]) || value[key] < 0 || value[key] > 17280000) return null;
  return {positionSeconds:value.positionSeconds,durationSeconds:value.durationSeconds,resumeSeconds:value.completed === true ? 0 : value.resumeSeconds,completed:value.completed === true,lastReceiver:['luma','philips'].includes(value.lastReceiver) ? value.lastReceiver : null};
}
function libraryProgressText(progress) {
  if (!progress) return 'Not watched yet';
  const duration = progress.durationSeconds > 0 ? ` / ${clock(progress.durationSeconds)}` : '';
  return `${progress.completed ? 'Watched' : 'Saved at'} · ${clock(progress.positionSeconds)}${duration}${progress.lastReceiver ? ` · Last on ${progress.lastReceiver === 'philips' ? 'Philips TV' : 'Kodak LUMA'}` : ''}`;
}
function episodeSelection(identity, season, episode, progress = null) {
  if (!identity) return null;
  if (identity.kind === 'movie') return {...identity,progress};
  return Number.isInteger(season) && season >= 0 && season <= 9999 && Number.isInteger(episode) && episode > 0 && episode <= 9999 ? {...identity,season,episode,progress} : null;
}
function nextEpisodeSelection(value, media) {
  const current = contentDescriptor(media?.content);
  if (!current || current.kind !== 'tv' || !value || value.kind !== 'tv' || value.id !== current.contentId) return null;
  const next = episodeSelection({kind:'tv',id:value.id}, value.season, value.episode);
  return next && (next.season > current.season || next.season === current.season && next.episode > current.episode) ? next : null;
}
function selectionLabel(selection) { return selection?.kind === 'tv' ? `S${selection.season} E${selection.episode}` : 'Movie'; }
function renderLibraryControls() {
  const free = state.native && !state.busy && !state.handoff;
  for (const control of $('#panel-history').querySelectorAll('button,input,select')) control.disabled = !free;
  $('#history-more').disabled = !free || libraryLoading;
  $('#history-resume').disabled = !free || libraryLoading || !librarySelected;
  $('#history-restart').disabled = !free || libraryLoading || !librarySelected;
  $('#history-next').disabled = !free || libraryLoading || !libraryNext;
  $('#history-search').disabled = !free || libraryLoading;
  $('#history-target').textContent = `Selected device: ${state.target === 'philips' ? 'Philips TV' : 'Kodak LUMA'}.`;
}
function selectLibraryEpisode(selection) {
  librarySelected = selection;
  $('#history-progress').textContent = selection ? `${selectionLabel(selection)} · ${libraryProgressText(selection.progress)}` : 'Choose an episode below.';
  const action = selection?.progress?.completed ? 'Watch again' : (selection?.progress?.resumeSeconds || 0) >= 1 ? 'Resume' : 'Play';
  $('#history-resume').textContent = `${action}${selection?.kind === 'tv' ? ` ${selectionLabel(selection)}` : ''}`;
  for (const button of $('#history-episodes').querySelectorAll('button')) button.setAttribute('aria-pressed', String(selection?.kind === 'tv' && Number(button.dataset.season) === selection.season && Number(button.dataset.episode) === selection.episode));
  renderLibraryControls();
}
function renderLibraryEpisodes(seasonNumber) {
  $('#history-episodes').textContent = '';
  const identity = libraryIdentity(libraryDetail), season = libraryDetail?.seasons.find(item => item.number === seasonNumber);
  if (!season || !Array.isArray(season.episodes) || season.episodes.length > 1000) return;
  for (const episode of season.episodes) {
    const selection = episodeSelection(identity, season.number, episode?.number, savedProgress(episode?.progress));
    if (!selection) continue;
    const button = document.createElement('button'); button.type = 'button'; button.dataset.season = String(season.number); button.dataset.episode = String(episode.number);
    button.textContent = `${selectionLabel(selection)} · ${safeText(episode.title, 200) || `Episode ${episode.number}`} · ${libraryProgressText(selection.progress)}`;
    button.addEventListener('click', () => { if (!state.busy && !state.handoff) selectLibraryEpisode(selection); });
    $('#history-episodes').append(button);
  }
  selectLibraryEpisode(librarySelected);
}
async function showLibraryDetails(row) {
  const identity = libraryIdentity(row);
  if (!identity || state.busy || state.handoff) return;
  const epoch = ++historySelection; libraryLoading = true; librarySelected = libraryNext = null;
  $('#history-list-view').hidden = true; $('#history-detail').hidden = false;
  $('#history-title').textContent = title(row.title); $('#history-year').textContent = ''; $('#history-overview').textContent = '';
  $('#history-progress').textContent = 'Loading saved details…'; $('#history-detail-status').textContent = '';
  $('#history-episodes').textContent = ''; $('#history-seasons').hidden = true; $('#history-next').hidden = true;
  renderLibraryControls();
  try {
    const detail = await rpc('watch.libraryDetails', identity, {timeoutMs:5000});
    if (epoch !== historySelection || state.sheet !== 'history') return;
    if (detail?.version !== 1 || detail.kind !== identity.kind || detail.id !== identity.id || !Array.isArray(detail.seasons) || detail.seasons.length > 1001) throw fixedError('INVALID_RESPONSE');
    libraryDetail = detail; libraryLoading = false;
    $('#history-title').textContent = title(detail.title); $('#history-year').textContent = safeText(detail.year, 12);
    $('#history-overview').textContent = safeText(detail.overview, 2000) || 'No description has been saved yet.';
    $('#history-detail-status').textContent = detail.storageAvailable === false ? 'Library storage is unavailable on this phone.' : detail.metadataAvailable === true ? 'Saved details are available offline.' : 'Saved progress is available offline. More title details have not been saved yet.';
    librarySelected = episodeSelection(identity, detail.latest?.season, detail.latest?.episode, savedProgress(identity.kind === 'movie' ? detail.movieProgress || detail.latest : detail.latest));
    libraryNext = detail.nextEpisode?.kind === 'tv' && detail.nextEpisode.id === identity.id ? episodeSelection(identity,detail.nextEpisode.season,detail.nextEpisode.episode) : null;
    $('#history-next').hidden = !libraryNext;
    if (libraryNext) $('#history-next').textContent = `Play next episode · ${selectionLabel(libraryNext)}`;
    $('#history-seasons').hidden = identity.kind !== 'tv' || !detail.seasons.length;
    $('#history-season').textContent = '';
    for (const season of detail.seasons) {
      if (!Number.isInteger(season?.number) || season.number < 0 || season.number > 9999) continue;
      const option = document.createElement('option'); option.value = String(season.number); option.textContent = safeText(season.name, 100) || `Season ${season.number}`; $('#history-season').append(option);
    }
    if (identity.kind === 'tv' && $('#history-season').options.length) {
      $('#history-season').value = String(librarySelected?.season ?? $('#history-season').options[0].value);
      if (!$('#history-season').value) $('#history-season').selectedIndex = 0;
      renderLibraryEpisodes(Number($('#history-season').value));
    }
    selectLibraryEpisode(librarySelected);
    $('#history-resume').focus();
  } catch {
    if (epoch !== historySelection) return;
    libraryLoading = false; $('#history-progress').textContent = 'Saved details could not load. Return to Library and try again.'; renderLibraryControls();
  }
}
async function loadLibrary(more = false) {
  if (libraryLoading || state.busy || state.handoff) return;
  const epoch = ++historySelection; libraryLoading = true;
  if (!more) { libraryItems = []; libraryOffset = 0; libraryQuery = $('#history-search').value.trim().slice(0,120); $('#history-list').textContent = ''; }
  $('#history-status').textContent = 'Loading your saved library…'; renderLibraryControls();
  const params = more ? {offset:libraryOffset + libraryLimit,limit:50,query:libraryQuery} : libraryQuery ? {query:libraryQuery} : {};
  try {
    const saved = await rpc('watch.library', params, {timeoutMs:5000});
    if (epoch !== historySelection || state.sheet !== 'history') return;
    if (saved?.version !== 1 || !Array.isArray(saved.items) || saved.items.length > 100 || !Number.isSafeInteger(saved.offset) || saved.offset < 0 || !Number.isInteger(saved.limit) || saved.limit < 1 || saved.limit > 100 || !Number.isSafeInteger(saved.total) || saved.total < 0 || saved.query !== libraryQuery) throw fixedError('INVALID_RESPONSE');
    libraryOffset = saved.offset; libraryLimit = saved.limit; libraryHasMore = saved.hasMore === true;
    const seen = new Set(libraryItems.map(row => `${row.kind}:${row.id}`));
    for (const row of saved.items) {
      const identity = libraryIdentity(row); if (!identity || seen.has(`${identity.kind}:${identity.id}`)) continue;
      seen.add(`${identity.kind}:${identity.id}`); libraryItems.push(row);
      const button = document.createElement('button'); button.type = 'button';
      const latest = episodeSelection(identity,row.latest?.season,row.latest?.episode,savedProgress(row.latest));
      button.textContent = `${title(row.title)}${identity.kind === 'tv' ? latest ? ` · ${selectionLabel(latest)}` : ' · Series' : ' · Movie'} · ${libraryProgressText(latest?.progress)}`;
      button.addEventListener('click', () => void showLibraryDetails(row)); $('#history-list').append(button);
    }
    $('#history-status').textContent = saved.storageAvailable === false ? 'Library storage is unavailable on this phone.' : saved.total ? `${libraryItems.length} of ${saved.total} saved titles${libraryQuery ? ' found' : ''}.` : libraryQuery ? 'No saved titles match this search.' : 'Movies and series will appear here after you watch them.';
    $('#history-more').hidden = !libraryHasMore;
  } catch { if (epoch === historySelection) $('#history-status').textContent = 'Your saved library could not load. Try opening Library again.'; }
  finally { if (epoch === historySelection) { libraryLoading = false; renderLibraryControls(); } }
}
function openLibrary() {
  if (!state.native || state.busy || state.handoff) return;
  sheet('history'); historySelection++; libraryLoading = false;
  $('#history-list-view').hidden = false; $('#history-detail').hidden = true; $('#history-more').hidden = true;
  void loadLibrary();
}
async function playLibrarySelection(selection, resume) {
  if (!selection || state.busy || state.handoff) return;
  const epoch = ++historySelection, target = state.target; historyController = new AbortController();
  state.busy = true; renderControls();
  $('#history-detail-status').textContent = 'Finding this video… Close to cancel. Current playback continues.';
  try {
    const params = {kind:selection.kind,id:selection.id,resume,...(selection.kind === 'tv' ? {season:selection.season,episode:selection.episode} : {})};
    const media = await rpc('app.chooseCatalog', params, {signal:historyController.signal,timeoutMs:150000});
    if (epoch !== historySelection || state.target !== target || state.sheet !== 'history') return;
    if (!media || typeof media.url !== 'string' || typeof media.sourceToken !== 'string') throw fixedError('INVALID_MEDIA');
    historyController = null; state.busy = false; closeSheet(); window.LumaApp.onCapturedMedia(media);
  } catch (error) {
    if (epoch !== historySelection) return;
    historyController = null; state.busy = false; $('#history-detail-status').textContent = errors[error.code] || 'This title could not load. Try another title.'; renderControls();
  }
}
async function playNextEpisode() {
  const remote = receiver(), selection = remote.nextEpisode;
  if (!selection || state.busy || state.handoff) {
    if (!selection) message('No catalog-confirmed next episode is available yet.', true);
    return;
  }
  const target = state.target, source = remote.activeMedia?.url;
  const contentKey = contentDescriptor(remote.activeMedia?.content)?.key || null;
  const nextKey = `${selection.id}:${selection.season}:${selection.episode}`;
  state.busy = true; renderControls(); message(`Finding ${selectionLabel(selection)}…`);
  try {
    const media = await rpc('app.chooseCatalog', {kind:'tv',id:selection.id,season:selection.season,episode:selection.episode,resume:true}, {timeoutMs:150000});
    const current = receiver(), currentNext = current.nextEpisode;
    if (state.target !== target || current.activeMedia?.url !== source
        || (contentDescriptor(current.activeMedia?.content)?.key || null) !== contentKey
        || !currentNext || `${currentNext.id}:${currentNext.season}:${currentNext.episode}` !== nextKey) throw fixedError('SOURCE_CHANGED');
    if (!media || typeof media.url !== 'string' || typeof media.sourceToken !== 'string') throw fixedError('INVALID_MEDIA');
    state.busy = false; remote.nextEpisode = null; window.LumaApp.onCapturedMedia(media);
  } catch (error) {
    state.busy = false; message(errors[error.code] || 'The next episode could not load. Current playback is unchanged.', true); renderControls();
  }
}
// ---- Two screens: move playback across, or keep both playing in step ----
function otherScreen(target = state.target) { return target === 'philips' ? 'luma' : 'philips'; }
function screenReady(target) { return target === 'philips' ? state.philips.connected : state.connected && state.configured; }
function screenContent(target) { const remote = receiver(target); return contentDescriptor(remote.activeMedia?.content) || contentDescriptor(remote.playback?.content); }
function screenPlaying(target) { const playback = receiver(target).playback; return !!playback?.active && (target === 'philips' ? playback.paused !== true : playback.playing !== false); }
function screenParams(target, extra = {}) { return target === 'philips' ? msxParams(extra) : remoteParams(extra); }
function setScreenPlaying(target, play) {
  return target === 'philips' ? rpc('msx.playPause', msxParams({ play })).then(msxState)
    : rpc(play ? 'receiver.resume' : 'receiver.pause', remoteParams());
}
function seekScreen(target, seconds) {
  return target === 'philips' ? rpc('msx.seek', msxParams({ seconds })).then(msxState) : rpc('receiver.seek', remoteParams({ position: seconds }));
}
function rateScreen(target, value) { return rpc(target === 'philips' ? 'msx.rate' : 'receiver.rate', screenParams(target, { value }), { timeoutMs: 4000 }); }
/** One timed sample: LUMA at the request midpoint, Philips at its last state report. */
async function sampleScreen(target) {
  const started = performance.now();
  if (target === 'philips') {
    const status = await rpc('msx.status', {}, { timeoutMs: 3000 });
    const done = performance.now(); msxState(status);
    const reported = status?.playback, age = status?.diagnostics?.lastStateMsAgo;
    if (!reported || !Number.isFinite(age) || age < 0) throw fixedError('SYNC_UNAVAILABLE');
    return { at: done - age - 20, position: reported.positionSeconds, playing: !!reported.active && reported.paused !== true, rate: reported.rate || 1, active: !!reported.active, ended: !!reported.ended };
  }
  const status = await rpc('receiver.status', remoteParams(), { timeoutMs: 3000 });
  const done = performance.now(); playback(status);
  if (done - started > 600) throw fixedError('SYNC_SLOW');
  return { at: (started + done) / 2, position: status.positionSeconds, playing: status.playing === true, rate: status.rate || 1, active: !!status.active, ended: !!status.ended };
}
async function castTitleTo(target, content, position) {
  const media = await rpc('app.chooseCatalog', { kind: content.kind, id: content.contentId, resume: true, ...(content.kind === 'tv' ? { season: content.season, episode: content.episode } : {}) }, { timeoutMs: 150000 });
  if (!media || typeof media.url !== 'string' || typeof media.sourceToken !== 'string') throw fixedError('INVALID_MEDIA');
  if (state.target !== target) await selectTarget(target);
  if (state.target !== target) throw fixedError('SOURCE_CHANGED');
  state.sourceGeneration++;
  state.pendingMedia = { ...media, ...mediaMetadata({ ...media, resumeSeconds: Math.max(0, position) }), captureToken: media.sourceToken, title: title(media.title) };
  state.pendingTarget = target; state.autoStart = true;
  await startCasting();
  return receiver(target).activeMedia?.url === media.url && !state.pendingMedia;
}
async function movePlayback() {
  const from = state.target, to = otherScreen(from), content = screenContent(from), playbackNow = receiver(from).playback;
  if (!content || !playbackNow?.active) { message('Only titles from Library or frameXTV can move between screens.', true); return; }
  if (!screenReady(to)) { message(`Connect ${SCREEN_NAMES[to]} first.`, true); return; }
  closeSheet(); stopSync();
  const position = Math.max(0, playbackNow.positionSeconds || 0);
  message(`Moving to ${SCREEN_NAMES[to]} at ${clock(position)}…`);
  await setScreenPlaying(from, false).catch(() => {});
  try {
    if (!await castTitleTo(to, content, position)) throw fixedError('SOURCE_CHANGED');
    await rpc(from === 'philips' ? 'msx.stop' : 'receiver.stop', screenParams(from)).catch(() => {});
    message(`Moved to ${SCREEN_NAMES[to]}. ${SCREEN_NAMES[from]} has stopped.`);
  } catch (error) {
    message(`Could not move this video. It is paused on ${SCREEN_NAMES[from]}.`, true);
  }
}
async function startSync() {
  const from = state.target, to = otherScreen(from), content = screenContent(from), playbackNow = receiver(from).playback;
  if (!content || !playbackNow?.active) { message('Only titles from Library or frameXTV can play on both screens.', true); return; }
  if (!screenReady(to)) { message(`Connect ${SCREEN_NAMES[to]} first.`, true); return; }
  closeSheet();
  if (screenContent(to)?.key !== content.key || !receiver(to).playback?.active) {
    message(`Starting on ${SCREEN_NAMES[to]} too…`);
    try { if (!await castTitleTo(to, content, (playbackNow.positionSeconds || 0) + 3)) throw fixedError('SOURCE_CHANGED'); }
    catch { message(`Could not start ${SCREEN_NAMES[to]}. ${SCREEN_NAMES[from]} keeps playing.`, true); return; }
  }
  Object.assign(sync, { on: true, rate: 1, settleUntil: 0, offset: null });
  message('Both screens are playing. Keeping them in step…');
  renderControls(); scheduleSync(0);
}
function stopSync(reason) {
  if (!sync.on) return;
  sync.on = false; clearTimeout(sync.timer); sync.timer = null;
  if (sync.rate !== 1) void rateScreen(sync.follower, 1).catch(() => {});
  sync.rate = 1; sync.offset = null;
  if (reason) message(reason);
  renderControls(); renderRemote();
}
function scheduleSync(delay = SYNC_TICK_MS) { clearTimeout(sync.timer); if (sync.on) sync.timer = setTimeout(() => void syncTick(), delay); }
async function syncTick() {
  if (!sync.on || sync.ticking) return;
  sync.ticking = true;
  try {
    const leaderContent = screenContent(sync.leader), followerContent = screenContent(sync.follower);
    if (!leaderContent || leaderContent.key !== followerContent?.key) { stopSync('The screens are playing different videos now, so sync stopped.'); return; }
    const [leader, follower] = await Promise.all([sampleScreen(sync.leader), sampleScreen(sync.follower)]);
    if (!sync.on) return;
    if (leader.ended || follower.ended || !leader.active && !leader.ended) { stopSync('Playback finished on one screen, so sync stopped.'); return; }
    const now = performance.now();
    if (now < sync.settleUntil) return;
    const step = syncStep(leader, follower, now, { currentRate: sync.rate, seekLatencySeconds: 0.6 });
    sync.offset = step.offset;
    if (step.action === 'play' || step.action === 'pause') await setScreenPlaying(sync.follower, step.action === 'play');
    else if (step.action === 'seek') {
      if (sync.rate !== 1) { await rateScreen(sync.follower, 1); sync.rate = 1; }
      await seekScreen(sync.follower, step.position); sync.settleUntil = performance.now() + SYNC_SETTLE_MS;
    } else if (step.action === 'rate') { await rateScreen(sync.follower, step.rate); sync.rate = step.rate; }
  } catch { /* the next tick samples again */ }
  finally { sync.ticking = false; scheduleSync(); renderRemote(); }
}
/** While both screens play together, a person's play/pause/seek applies to both at once. */
function mirrorToPartner(action, value) {
  if (!sync.on) return;
  const partner = otherScreen();
  if (action === 'seek') { sync.settleUntil = performance.now() + SYNC_SETTLE_MS; void seekScreen(partner, value).catch(() => {}); }
  else if (action === 'play' || action === 'pause') void setScreenPlaying(partner, action === 'play').catch(() => {});
}
$('#move-playback').addEventListener('click', () => void movePlayback());
$('#sync-playback').addEventListener('click', () => sync.on ? stopSync('Stopped keeping the screens in step. Both keep playing.') : void startSync());
$('#show-history').addEventListener('click', openLibrary);
$('#open-library').addEventListener('click', openLibrary);
$('#history-search-form').addEventListener('submit', event => { event.preventDefault(); void loadLibrary(); });
$('#history-more').addEventListener('click', () => void loadLibrary(true));
$('#history-back').addEventListener('click', () => { if (state.busy || state.handoff) return; historySelection++; libraryLoading = false; $('#history-detail').hidden = true; $('#history-list-view').hidden = false; renderLibraryControls(); });
$('#history-season').addEventListener('change', () => renderLibraryEpisodes(Number($('#history-season').value)));
$('#history-resume').addEventListener('click', () => void playLibrarySelection(librarySelected,true));
$('#history-restart').addEventListener('click', () => void playLibrarySelection(librarySelected,false));
$('#history-next').addEventListener('click', () => void playLibrarySelection(libraryNext,true));
$('#browser-form').addEventListener('submit', event => { event.preventDefault(); void browse(true); });
$('#capture-video').addEventListener('click', () => command('app.capture', {}, '', result => {
  const media = result?.media || (result?.url ? result : null);
  if (media && media.sourceToken !== (state.pendingMedia?.captureToken || state.pendingMedia?.sourceToken)) window.LumaApp.onCapturedMedia(media);
}));
$('#cast-selected').addEventListener('click', startCasting);
$('#receiver-step').addEventListener('click', () => deviceStep('receiver'));
$('#pair-step').addEventListener('click', () => deviceStep('pair'));
$('#receiver-form').addEventListener('submit', async event => {
  event.preventDefault();
  const host = $('#receiver-host').value.trim();
  if (!privateHost(host)) { message('Enter the projector’s local IP address.', true); return; }
  const success = await command('device.connect', { host }, 'Projector connected.', result => {
    if (result?.connected !== true) throw fixedError('CONNECTION_FAILED');
    if (state.host && state.host !== host) {
      state.configured = false;
      state.activeMedia = null;
      state.tracks = [];
      state.catalog = [];
      state.nextEpisode = null;
      state.playback = null;
      $('#media-title').textContent = 'Ready to cast';
      $('#playback-status').textContent = 'Choose a video in Browse.';
    }
    state.connected = true;
    state.nativeConnected = result.nativeConnected !== false;
    state.receiverConnected = result.receiverConnected === true;
    state.settings.hasReceiver = true;
    state.host = host;
    state.name = safeText(result.device?.name || result.name) || 'LUMA projector';
    $('#receiver-name').textContent = state.name;
    $('#device-results').replaceChildren();
  });
  if (success) { if (state.configured) { page('remote'); void startCasting(); } else deviceStep('pair'); }
});
function foundDevices(result) {
  const devices = (Array.isArray(result?.devices) ? result.devices : []).filter(device => typeof device?.host === 'string' && privateHost(device.host)).slice(0, 3);
  $('#device-results').replaceChildren();
  if (devices.length === 1) {
    $('#receiver-host').value = devices[0].host;
    message('Projector found. Choose Connect.');
  } else if (devices.length > 1) {
    devices.forEach(device => $('#device-results').append(listButton(safeText(device.name) || device.host, () => { $('#receiver-host').value = device.host; message('Projector selected. Choose Connect.'); })));
    message('Choose a projector, then connect.');
  } else message('No projector replied yet. Retry when it is ready, or enter its IP address.');
}
$('#discover').addEventListener('click', () => { message('Searching for up to 3 seconds…'); void command('device.discover', {}, '', foundDevices); });
$('#rescan-projectors').addEventListener('click', async () => {
  closeSheet(); page('devices'); deviceStep('receiver');
  message('Searching for up to 3 seconds…');
  await command('app.settings.rescan', {}, '', result => {
    connectionState(result);
    if (result?.connected === true) {
      state.connected = true;
      state.nativeConnected = result.nativeConnected !== false;
      state.receiverConnected = result.receiverConnected === true;
      state.name = safeText(result.device?.name || result.name) || state.name;
      const found = result.devices?.find(device => privateHost(device?.host || ''));
      if (found) { connectionState({ connection: { host: found.host } }); $('#receiver-host').value = found.host; }
      state.settings.hasReceiver = true;
      $('#receiver-name').textContent = state.name;
      if (state.configured) page('remote'); else deviceStep('pair');
      message(state.configured ? 'Projector connected. Your remote is ready.' : 'Projector connected. Pair LUMA Receiver to finish setup.');
    } else foundDevices(result);
  });
});
function disconnect() {
  clearPendingVolume();
  if (state.target === 'philips') return command(async () => {
    if (state.matter.paired) matterState(await rpc('matter.disconnect', {}));
    return rpc('msx.disconnect', {});
  }, {}, 'TV controls disconnected. A playing video stays on the TV.', result => { state.philips.connected = false; msxState(result); });
  return command('device.disconnect', {}, 'Disconnected. A playing video stays on the projector.', () => {
    state.connected = false; state.nativeConnected = false; state.receiverConnected = false; state.configured = false;
    state.apps = []; state.volume = null; $('#volume-level').textContent = '—'; renderApps();
  });
}
$('#disconnect-device').addEventListener('click', disconnect);
$('#settings-disconnect').addEventListener('click', async () => { if (await disconnect()) { closeSheet(); page('remote'); } });

function waitSetup(ms, signal) {
  return new Promise((resolve, reject) => {
    if (signal.aborted) { reject(fixedError('CANCELLED')); return; }
    const abort = () => { clearTimeout(timer); reject(fixedError('CANCELLED')); };
    const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, ms);
    signal.addEventListener('abort', abort, { once: true });
  });
}
// Pairing is two steps: the projector shows a code (launching LUMA Receiver over
// EShare if it does not answer), then the user types that code here.
async function pairingFlow(work) {
  if (state.busy || state.handoff || !state.connected) return;
  state.setupFlow = true;
  state.handoff = true;
  const controller = new AbortController();
  state.flowController = controller;
  const deadline = performance.now() + 30000;
  const options = () => ({ signal: controller.signal, timeoutMs: Math.max(1, Math.min(8000, deadline - performance.now())) });
  const progress = (stage, text, value) => { reportStatus(stage); $('#handoff-label').textContent = text; $('#handoff-progress').value = value; message(text); };
  sheet('handoff');
  let success = false;
  try {
    success = await work({ controller, deadline, options, progress });
  } catch (error) {
    closeSheet();
    page('devices'); deviceStep('pair');
    message(error.code === 'CANCELLED' ? 'Pairing stopped. No video was sent.' : error.message, error.code !== 'CANCELLED');
    reportStatus(error.code === 'CANCELLED' ? 'cancelled' : 'error', error.code);
  } finally {
    state.setupFlow = false;
    state.handoff = false;
    state.flowController = null;
    applyDeferredTarget();
    renderControls();
    // The code input is disabled while the flow is busy; focus it only after renderControls re-enables it.
    if (success === 'code') $('#pair-code').focus();
    if (success === 'paired' && state.pendingMedia) void startCasting();
  }
}
function showPairingCode() {
  return pairingFlow(async ({ controller, deadline, options, progress }) => {
    progress('setup', 'Asking LUMA Receiver for a pairing code…', 1);
    let launched = false;
    while (true) {
      if (controller.signal.aborted) throw fixedError('CANCELLED');
      if (performance.now() >= deadline) throw fixedError('SETUP_TIMEOUT');
      try {
        const begun = await rpc('receiver.pairBegin', {}, options());
        state.pairingId = begun?.pairingId ?? null;
        break;
      } catch (error) {
        if (!['RECEIVER_UNAVAILABLE', 'TIMEOUT', 'CONNECTION_FAILED'].includes(error.code)) throw error;
        if (!launched) {
          launched = true;
          progress('launching', 'Opening LUMA Receiver on the projector…', 2);
          try { await rpc('luma.launchReceiver', {}, options()); }
          catch (failure) {
            if (failure.code === 'APP_NOT_AVAILABLE') throw Object.assign(new Error('Install LUMA Receiver on the projector first, then try again.'), { code: 'RECEIVER_NOT_INSTALLED' });
            throw failure;
          }
          progress('waiting', 'Waiting for LUMA Receiver…', 3);
        }
        await waitSetup(Math.min(700, Math.max(1, deadline - performance.now())), controller.signal);
      }
    }
    closeSheet();
    page('devices'); deviceStep('pair');
    $('#pair-form').hidden = false;
    $('#pair-code').value = '';
    message('Enter the 6-digit code shown on the projector.');
    reportStatus('ready');
    return 'code';
  });
}
function completePairing() {
  const code = $('#pair-code').value.replace(/\s/g, '');
  if (!state.pairingId) { void showPairingCode(); return; }
  return pairingFlow(async ({ options, progress }) => {
    progress('setup', 'Pairing with LUMA Receiver…', 2);
    await rpc('receiver.pairComplete', { pairingId: state.pairingId, code }, options());
    state.pairingId = null;
    progress('waiting', 'Checking the connection…', 3);
    const result = await rpc('receiver.connect', {}, options());
    if (result?.ready !== true) throw fixedError('RECEIVER_UNAVAILABLE');
    state.configured = true;
    state.settings.hasPairing = true;
    $('#pair-code').value = '';
    $('#pair-form').hidden = true;
    await rpc('luma.status', {}, options()).then(volume).catch(() => {});
    state.skipNextLaunch = Boolean(state.pendingMedia);
    closeSheet();
    page('remote');
    message('Paired. Choose a video in Browse, then tap Cast.');
    reportStatus('ready');
    return 'paired';
  });
}
$('#pair-show').addEventListener('click', () => void showPairingCode());
$('#pair-form').addEventListener('submit', event => {
  event.preventDefault();
  void completePairing();
});
$('#play-toggle').addEventListener('click', () => {
  if (state.target === 'philips') {
    const play = state.philips.playback?.playing === false;
    mirrorToPartner(play ? 'play' : 'pause');
    void command('msx.playPause', msxParams({ play }), play ? 'Resume requested.' : 'Pause requested.', msxState);
    return;
  }
  const action = state.playback?.playing === false ? 'resume' : 'pause';
  mirrorToPartner(action === 'resume' ? 'play' : 'pause');
  void command(`receiver.${action}`, remoteParams(), action === 'pause' ? 'Pause requested.' : 'Resume requested.', async () => playback(await rpc('receiver.status', remoteParams())));
});
async function seekTo(position) {
  const remote = receiver(), duration = remote.playback?.durationSeconds;
  if (!Number.isFinite(position) || !Number.isFinite(duration) || duration <= 0) return false;
  const seconds = Math.max(0, Math.min(Math.floor(duration), Math.floor(position)));
  scrubPosition = seconds; renderRemote();
  mirrorToPartner('seek', seconds);
  const accepted = state.target === 'philips'
    ? await command('msx.seek', msxParams({ seconds }), 'Seek requested. Check playback on the TV.', msxState)
    : await command('receiver.seek', remoteParams({ position: seconds }), 'Seek requested. Check playback on the projector.', async () => playback(await rpc('receiver.status', remoteParams())));
  scrubPosition = null;
  renderControls();
  return accepted;
}
$('#back-30').addEventListener('click', () => void seekTo(Math.max(0, (receiver().playback?.positionSeconds || 0) - 30)));
$('#next-episode').addEventListener('click', () => {
  if (state.target === 'philips') { void playNextEpisode(); return; }
  const key = state.playback?.content?.key;
  void command('receiver.next', key ? { expectedContentKey: key } : {}, 'Loading the next episode on the projector…');
});
// The switch moves at once; the receiver is told afterwards without disabling
// any control, and status reports older than the tap cannot move it back.
$('#auto-next').addEventListener('change', () => {
  const autoNext = $('#auto-next').checked, target = state.target;
  autoNextWanted = { target, value: autoNext, confirmed: false };
  rpc(target === 'philips' ? 'msx.settings' : 'receiver.settings', { autoNext }).then(() => {
    if (autoNextWanted?.value === autoNext && autoNextWanted.target === target) autoNextWanted.confirmed = true;
    message(autoNext ? 'Next episodes will play automatically.' : 'Auto-play is off.');
  }, () => {
    if (autoNextWanted?.value !== autoNext || autoNextWanted.target !== target) return;
    autoNextWanted = null; $('#auto-next').checked = !autoNext;
    message('Auto-play could not be changed. Try again.', true);
  });
});
$('#refresh-volume').addEventListener('click', () => state.target === 'philips' ? state.matter.connected ? command('matter.status', {}, 'Volume refreshed.', matterState) : command('msx.status', {}, 'Volume refreshed.', msxState) : command('luma.status', {}, 'Volume refreshed.', volume));
function setVolume(value) {
  const currentVolume = remoteVolume();
  const context = volumeContext();
  if (!state.native || state.busy || state.handoff || !context || !currentVolume || !Number.isInteger(value) || value < (currentVolume.min || 0) || value > currentVolume.max) return;
  const queued = { ...context, volume: { ...currentVolume, current: value }, due: performance.now() + VOLUME_COOLDOWN_MS,
    params: context.route === 'msx' ? msxParams({ value }) : { value } };
  pendingVolume = queued;
  renderControls();
  if (context.route === 'luma') {
    // The service owns this cooldown so it continues with the screen locked,
    // and notification taps combine with taps made here.
    void rpc('luma.queueVolume', { value }).then(result => {
      if (pendingVolume !== queued) return;
      volume(result);
      clearPendingVolume();
      renderControls();
    }).catch(error => volumeFailed(queued, error));
    return;
  }
  clearTimeout(volumeTimer);
  volumeTimer = setTimeout(flushVolume, VOLUME_COOLDOWN_MS);
}
function volumeFailed(queued, error) {
  if (pendingVolume !== queued) return;
  clearPendingVolume();
  message(error.message, true);
  renderControls();
}
async function flushVolume() {
  volumeTimer = null;
  const queued = pendingVolume;
  if (!queued || queued.route === 'luma' || volumeFlight) return;
  if (volumeContext()?.key !== queued.key) { clearPendingVolume(); return; }
  volumeFlight = true;
  try {
    const result = await rpc(queued.route === 'matter' ? 'matter.volume' : 'msx.volume', queued.params);
    if (pendingVolume === queued && volumeContext()?.key === queued.key) {
      if (queued.route === 'matter') matterState(result); else msxState(result);
      clearPendingVolume();
    }
  } catch (error) { volumeFailed(queued, error); }
  finally {
    volumeFlight = false;
    renderControls();
    if (pendingVolume && pendingVolume !== queued && pendingVolume.route !== 'luma') {
      clearTimeout(volumeTimer);
      volumeTimer = setTimeout(flushVolume, Math.max(0, pendingVolume.due - performance.now()));
    }
  }
}
$('#volume-range').addEventListener('input', () => setVolume(Number($('#volume-range').value)));
$('#volume-range').addEventListener('change', () => { if (Number($('#volume-range').value) !== remoteVolume()?.current) setVolume(Number($('#volume-range').value)); });
$('#volume-down').addEventListener('click', () => { const value = remoteVolume(); if (value) setVolume(value.current - 1); });
$('#volume-up').addEventListener('click', () => { const value = remoteVolume(); if (value) setVolume(value.current + 1); });
function key(name) {
  const now = performance.now();
  if (!navigationReady() || now - lastKeyTime < 100) return;
  lastKeyTime = now;
  if (state.target === 'philips') { void command('matter.key', { key: name }, 'TV command sent.', matterState); return; }
  void command('luma.key', { key: name }, 'Remote command sent.', () => {
    if (['volume-up', 'volume-down', 'mute'].includes(name)) $('#volume-level').textContent = '—';
  });
}
document.querySelectorAll('[data-key]').forEach(button => button.addEventListener('click', () => key(button.dataset.key)));
$('#show-more').addEventListener('click', () => {
  sheet('more');
  $('#picture-brightness').hidden = state.target === 'philips' ? !state.philips.connected : !state.configured;
  readBrightness();
});
document.querySelectorAll('[data-panel]').forEach(button => button.addEventListener('click', () => {
  sheet(button.dataset.panel);
  if (button.dataset.panel === 'apps') void command('luma.apps', {}, 'Apps refreshed.', result => { state.apps = Array.isArray(result?.apps) ? result.apps.slice(0, 200) : []; state.appPage = 0; renderApps(); });
}));
// Caption size lives on the projector (persisted by LUMA Receiver) so every phone sees the same value.
function renderSubtitleSize(display) {
  const scale = Number(display?.subtitleScale);
  if (!Number.isFinite(scale)) return;
  state.subtitleScale = Math.round(scale * 10) / 10;
  $('#subtitle-size-value').textContent = `Size ${Math.round(state.subtitleScale * 100)}%`;
  $('#subtitle-smaller').disabled = state.subtitleScale <= 0.5;
  $('#subtitle-larger').disabled = state.subtitleScale >= 2.5;
}
function stepSubtitleSize(direction) {
  const next = Math.max(0.5, Math.min(2.5, Math.round(((state.subtitleScale ?? 1) + direction * 0.1) * 10) / 10));
  renderSubtitleSize({ subtitleScale: next });
  // Both receivers keep the size themselves; Philips reports it back in its playback state.
  if (state.target === 'philips') void rpc('msx.display', { subtitleScale: next }, { timeoutMs: 8000 }).then(result => { msxState(result); renderSubtitleSize(state.philips.playback?.display); }).catch(error => message(error.message, true));
  else void rpc('receiver.display', { subtitleScale: next }, { timeoutMs: 4000 }).then(renderSubtitleSize).catch(error => message(error.message, true));
}
// Picture brightness is a per-movie/per-series preference. LUMA takes an offset
// and Philips a CSS factor around 1; both present the same −30…+30% control.
const BRIGHTNESS_MIN = -0.3, BRIGHTNESS_MAX = 0.3, BRIGHTNESS_STEP = 0.02;
let brightnessLevel = 0;
function normalizedBrightness(level) {
  if (!Number.isFinite(level)) return null;
  return Math.max(BRIGHTNESS_MIN, Math.min(BRIGHTNESS_MAX, Math.round(level / BRIGHTNESS_STEP) * BRIGHTNESS_STEP));
}
function renderBrightness(level) {
  const normalized = normalizedBrightness(level);
  if (normalized == null) return;
  brightnessLevel = normalized;
  $('#brightness-value').textContent = `Brightness ${brightnessLevel > 0 ? '+' : ''}${Math.round(brightnessLevel * 100)}%`;
  $('#brightness-down').disabled = brightnessLevel <= BRIGHTNESS_MIN;
  $('#brightness-up').disabled = brightnessLevel >= BRIGHTNESS_MAX;
}
function readBrightness() {
  if (state.target === 'philips') { renderBrightness((state.philips.playback?.display?.brightness ?? 1) - 1); return; }
  if (state.configured) void rpc('receiver.display', {}, { timeoutMs: 4000 }).then(display => renderBrightness(Number(display?.brightness))).catch(() => {});
}
function stepBrightness(direction) {
  const target = state.target, remote = receiver(target);
  const media = remote.activeMedia || (remote.playback?.content ? { content: remote.playback.content } : null);
  const next = normalizedBrightness(brightnessLevel + direction * BRIGHTNESS_STEP);
  if (next == null) return;
  renderBrightness(next);
  const failed = error => message(error.message || 'Brightness could not be changed.', true);
  if (target === 'philips') void rpc('msx.display', screenParams(target, { brightness: Math.round((1 + next) * 100) / 100 }), { timeoutMs: 8000 }).then(result => {
    msxState(result); rememberBrightness(media, next); if (state.target === target) renderBrightness(next);
  }).catch(failed);
  else void rpc('receiver.display', screenParams(target, { brightness: next }), { timeoutMs: 4000 }).then(display => {
    const confirmed = normalizedBrightness(Number(display?.brightness)) ?? next;
    rememberBrightness(media, confirmed); if (state.target === target) renderBrightness(confirmed);
  }).catch(failed);
}
$('#brightness-down').addEventListener('click', () => stepBrightness(-1));
$('#brightness-up').addEventListener('click', () => stepBrightness(1));
$('#subtitle-smaller').addEventListener('click', () => stepSubtitleSize(-1));
$('#subtitle-larger').addEventListener('click', () => stepSubtitleSize(1));
$('#show-subtitles').addEventListener('click', () => {
  sheet('subtitles');
  renderTracks();
  $('#subtitle-size').hidden = state.target === 'philips' ? !state.philips.connected : !state.configured;
  if (state.target === 'philips') { renderSubtitleSize(state.philips.playback?.display); void command('msx.status', {}, '', msxState); return; }
  if (state.configured) void rpc('receiver.display', {}, { timeoutMs: 4000 }).then(renderSubtitleSize).catch(() => {});
  void command('receiver.getSubtitles', remoteParams(), '', result => {
    state.subtitlesEnabled = result.enabled === true;
    state.selectedIndex = result.currentIndex ?? null;
    if (!state.tracks.length) state.tracks = (result.tracks || []).map(track => ({ index: track.index, language: safeText(track.language), label: safeText(track.label || track.name) }));
    renderTracks();
  });
});
$('#subtitles-off').addEventListener('click', () => state.target === 'philips' ? selectMsxSubtitle(state.philips.selectedIndex ?? 0, false) : command('receiver.setSubtitle', remoteParams({ index: 'off' }), 'Subtitles turned off.', async () => {
  state.subtitlesEnabled = false; state.selectedIndex = null; rememberCaption(state.activeMedia, ''); renderTracks();
  if (state.activeMedia && state.catalog.length) await rpc('app.rememberRemote', remoteParams({ remote: remoteDescriptor() }));
}));
$('#open-receiver').addEventListener('click', () => command('luma.launchReceiver', {}, 'LUMA Receiver launch sent. Check the projector.'));
$('#stop-video').addEventListener('click', () => (stopSync(), state.target) === 'philips' ? command('msx.stop', msxParams(), 'Stop requested.', result => { msxState(result); closeSheet(); }) : command('receiver.stop', remoteParams(), 'Stop requested.', result => { playback(result?.active === false ? result : { active: false }); closeSheet(); }));
$('#remote-text').addEventListener('input', renderControls);
$('#clear-text').addEventListener('click', () => { $('#remote-text').value = ''; renderControls(); $('#remote-text').focus(); });
$('#text-form').addEventListener('submit', async event => {
  event.preventDefault();
  const text = $('#remote-text').value;
  if (!text.trim()) return;
  if (text.length > 1000 || new TextEncoder().encode(text).length > 4096 || /[\u0000-\u001f\u007f]/u.test(text)) { message('Use a single line of up to 1000 characters.', true); return; }
  if (await command('luma.text', { text }, 'Text sent. Check the selected field on the projector.')) $('#remote-text').value = '';
  renderControls();
});
// Variable-speed scrubbing: fast movement is 1:1 with the bar so the whole film
// is reachable at any time; slower movement progressively shrinks each step
// (down to 1/20) for precise seeking around the current spot.
// Seek checkpoints: each scrub records where the viewer was. Undo jumps back
// and remembers where it jumped from, so Redo can return (and an accidental
// Undo is recoverable). Kept per video across app restarts (native store).
function seekHistoryKey() {
  const media = receiver().activeMedia;
  return media?.content?.key || media?.url || null;
}
// The remote WebView has no localStorage; checkpoints live here and are written
// through to the app's native store; the saved copy arrives with app.ready.
let seekHistoryStore = {};
function loadSeekHistory(saved) {
  if (saved && typeof saved === 'object' && !Array.isArray(saved)) { seekHistoryStore = { ...saved, ...seekHistoryStore }; renderSeekHistory(); }
}
function readSeekHistory() {
  const key = seekHistoryKey();
  const all = seekHistoryStore;
  const entry = key && all[key];
  const clean = list => Array.isArray(list) ? list.filter(Number.isFinite).slice(-SEEK_HISTORY_LIMIT) : [];
  return { key, all, back: clean(entry?.back), forward: clean(entry?.forward) };
}
function writeSeekHistory(history) {
  if (!history.key) return;
  history.all[history.key] = { back: history.back.slice(-SEEK_HISTORY_LIMIT), forward: history.forward.slice(-SEEK_HISTORY_LIMIT), at: Date.now() };
  // Keep only the 30 most recent videos.
  const keys = Object.keys(history.all).sort((a, b) => (history.all[b].at || 0) - (history.all[a].at || 0));
  for (const stale of keys.slice(30)) delete history.all[stale];
  seekHistoryStore = history.all;
  void rpc('app.seekHistory', { value: history.all }, { timeoutMs: 3000 }).catch(() => { /* history is a convenience */ });
  renderSeekHistory(history);
}
// Picture and subtitle preferences use the title identity: every episode in a
// series shares one key, while each movie has its own.
function titlePreferenceKey(media) {
  const content = contentDescriptor(media?.content);
  return content ? `framextv:${content.kind}:${content.contentId}` : null;
}
function loadPicturePreferences(saved) {
  if (saved && typeof saved === 'object' && !Array.isArray(saved)) picturePreferences = { ...saved, ...picturePreferences };
}
function rememberedBrightness(media) {
  const key = titlePreferenceKey(media);
  if (!key) return null;
  return normalizedBrightness(Number(picturePreferences[key]?.brightness)) ?? 0;
}
function rememberBrightness(media, brightness) {
  const key = titlePreferenceKey(media), value = normalizedBrightness(brightness);
  if (!key || value == null) return;
  picturePreferences[key] = { brightness: value, at: Date.now() };
  const keys = Object.keys(picturePreferences).sort((a, b) => (picturePreferences[b].at || 0) - (picturePreferences[a].at || 0));
  for (const stale of keys.slice(300)) delete picturePreferences[stale];
  void rpc('app.picturePreferences', { value: picturePreferences }, { timeoutMs: 3000 }).catch(() => { /* a convenience only */ });
}
/** Restores 0 for a new title so it cannot inherit the previously watched title. */
async function restoreBrightness(target, media) {
  const content = contentDescriptor(media?.content), brightness = rememberedBrightness(media);
  if (!content || brightness == null || screenContent(target)?.key !== content.key) return;
  try {
    if (target === 'philips') {
      const result = await rpc('msx.display', { brightness: Math.round((1 + brightness) * 100) / 100, expectedSourceUrl: media.url }, { timeoutMs: 8000 });
      msxState(result);
    } else {
      const display = await rpc('receiver.display', { brightness, expectedSourceUrl: media.url, expectedContentKey: content.key }, { timeoutMs: 4000 });
      if (state.target === target) renderBrightness(Number(display?.brightness));
    }
  } catch { /* Playback succeeded; More can retry a presentation-only preference. */ }
}
// Subtitle language per movie or series ('' = turned off), restored after each
// cast on either receiver. Stored natively like seek checkpoints.
function loadCaptionPreferences(saved) {
  if (saved && typeof saved === 'object' && !Array.isArray(saved)) captionPreferences = { ...saved, ...captionPreferences };
}
function captionTitleKey(media) {
  return titlePreferenceKey(media);
}
function rememberCaption(media, language) {
  const key = captionTitleKey(media);
  if (!key || typeof language !== 'string') return;
  captionPreferences[key] = { language: language.slice(0, 40), at: Date.now() };
  const keys = Object.keys(captionPreferences).sort((a, b) => (captionPreferences[b].at || 0) - (captionPreferences[a].at || 0));
  for (const stale of keys.slice(300)) delete captionPreferences[stale];
  void rpc('app.captionPreferences', { value: captionPreferences }, { timeoutMs: 3000 }).catch(() => { /* a convenience only */ });
}
function rememberedCaption(media, catalog) {
  const wanted = safeText(captionPreferences[captionTitleKey(media)]?.language).toLowerCase().replaceAll('_', '-');
  if (!wanted) return null;
  const base = wanted.split('-')[0], language = track => safeText(track.language).toLowerCase().replaceAll('_', '-');
  return catalog.find(track => language(track) === wanted) || catalog.find(track => language(track).split('-')[0] === base) || null;
}
/** After a cast: turn on the language last used for this title, through the normal Subtitles path. */
async function restoreCaption(target, media) {
  const remote = receiver(target);
  const track = state.target === target ? rememberedCaption(media, remote.catalog || []) : null;
  if (!track || remote.subtitlesEnabled) return;
  await selectSubtitle(track);
  if (receiver(target).subtitlesEnabled) message(`Subtitles on: ${safeText(track.label) || safeText(track.language)} (last used for this title).`);
}
function renderSeekHistory(history = readSeekHistory()) {
  const usable = !$('#seek-range').disabled && !seekHistoryBusy;
  $('#seek-undo').disabled = !usable || !history.back.length;
  $('#seek-redo').disabled = !usable || !history.forward.length;
}
function recordSeekCheckpoint(from) {
  const history = readSeekHistory();
  history.back.push(Math.max(0, Math.floor(from)));
  history.forward = [];
  writeSeekHistory(history);
}
function currentPosition() {
  const position = receiver().playback?.positionSeconds;
  return Number.isFinite(position) ? position : Number($('#seek-range').value);
}
let seekHistoryBusy = false;
async function stepSeekHistory(direction) {
  const peek = readSeekHistory(), pending = direction < 0 ? peek.back : peek.forward;
  if (!pending.length || seekHistoryBusy) return;
  // One step at a time: the arrows grey out until this jump settles.
  seekHistoryBusy = true;
  renderSeekHistory(peek);
  try { await stepSeekHistoryNow(direction, pending); } finally { seekHistoryBusy = false; renderSeekHistory(); }
}
async function stepSeekHistoryNow(direction, pending) {
  const target = pending[pending.length - 1], here = Math.max(0, Math.floor(currentPosition()));
  // The scrub's own seek may still be running; a dropped tap must not rewrite history.
  for (let waited = 0; (state.busy || state.handoff) && waited < 4000; waited += 100) await new Promise(resolve => setTimeout(resolve, 100));
  if (!await seekTo(target)) { message('Could not jump there. Try again.', true); return; }
  const history = readSeekHistory();
  const [from, to] = direction < 0 ? [history.back, history.forward] : [history.forward, history.back];
  if (from.length && from[from.length - 1] === target) from.pop();
  to.push(here);
  writeSeekHistory(history);
}
$('#seek-undo').addEventListener('click', () => stepSeekHistory(-1));
$('#seek-redo').addEventListener('click', () => stepSeekHistory(1));
function scrubTo(value) {
  const range = $('#seek-range'), max = Number(range.max) || 0;
  fineScrub.value = Math.max(0, Math.min(max, value));
  range.value = String(Math.round(fineScrub.value));
  scrubPosition = fineScrub.value;
  renderRemote();
  requestScrubPreview(scrubPosition);
}
$('#seek-track').addEventListener('pointerdown', event => {
  const range = $('#seek-range');
  if (range.disabled || fineScrub.active) return;
  event.preventDefault();
  try { $('#seek-track').setPointerCapture(event.pointerId); } catch { /* capture is best-effort */ }
  const rect = range.getBoundingClientRect(), max = Number(range.max) || 0, current = Number(range.value);
  const touched = Math.max(0, Math.min(1, (event.clientX - rect.left) / Math.max(1, rect.width))) * max;
  const thumbX = rect.left + (max > 0 ? current / max : 0) * rect.width;
  const observed = receiver().playback;
  Object.assign(fineScrub, { active: true, pointer: event.pointerId, lastX: event.clientX, lastTime: event.timeStamp, lastFast: event.timeStamp, gain: 1,
    from: Number.isFinite(observed?.positionSeconds) ? observed.positionSeconds : current });
  // Grabbing the thumb keeps its place; touching elsewhere jumps there.
  scrubTo(Math.abs(event.clientX - thumbX) <= 28 ? current : touched);
  warmScrubFrames(fineScrub.value);
});
$('#seek-track').addEventListener('pointermove', event => {
  if (!fineScrub.active || event.pointerId !== fineScrub.pointer) return;
  const range = $('#seek-range'), rect = range.getBoundingClientRect(), max = Number(range.max) || 0;
  const dx = event.clientX - fineScrub.lastX, dt = Math.max(1, event.timeStamp - fineScrub.lastTime);
  fineScrub.lastX = event.clientX; fineScrub.lastTime = event.timeStamp;
  if (!dx) return;
  // 1:1 by default. Only after the finger has been slow or still for a moment does
  // precision increase (1/3, then 1/10); any quick movement returns to 1:1 at once.
  const speed = Math.abs(dx) / dt; // px per ms
  if (speed > 0.35) fineScrub.lastFast = event.timeStamp;
  const held = event.timeStamp - fineScrub.lastFast;
  fineScrub.gain = held > 1600 ? 0.1 : held > 700 ? 0.33 : 1;
  scrubTo(fineScrub.value + dx * (max / Math.max(1, rect.width)) * fineScrub.gain);
});
function endFineScrub(event, commit) {
  if (!fineScrub.active || event.pointerId !== fineScrub.pointer) return;
  fineScrub.active = false; fineScrub.pointer = null;
  const position = fineScrub.value;
  scrubPosition = null;
  clearScrubPreview();
  if (!commit) { renderRemote(); return; }
  if (Number.isFinite(fineScrub.from) && Math.abs(position - fineScrub.from) > 5) recordSeekCheckpoint(fineScrub.from);
  void seekTo(position);
}
$('#seek-track').addEventListener('pointerup', event => endFineScrub(event, true));
$('#seek-track').addEventListener('pointercancel', event => endFineScrub(event, false));
$('#seek-range').addEventListener('input', () => {
  scrubPosition = Number($('#seek-range').value);
  renderRemote();
  requestScrubPreview(scrubPosition);
});
$('#seek-range').addEventListener('change', () => {
  const position = Number($('#seek-range').value);
  clearScrubPreview();
  void seekTo(position);
});
for (const event of ['pointerup', 'pointercancel', 'blur']) $('#seek-range').addEventListener(event, clearScrubPreview);
for (const kind of ['apps', 'subtitles']) {
  $(`#${kind === 'apps' ? 'app' : 'subtitle'}-search`).addEventListener('input', () => { (kind === 'apps' ? state : receiver())[kind === 'apps' ? 'appPage' : 'subtitlePage'] = 0; kind === 'apps' ? renderApps() : renderTracks(); });
  for (const [direction, delta] of [['previous', -1], ['next', 1]]) $(`#${kind}-${direction}`).addEventListener('click', () => { (kind === 'apps' ? state : receiver())[kind === 'apps' ? 'appPage' : 'subtitlePage'] += delta; kind === 'apps' ? renderApps() : renderTracks(); });
}
$('#close-sheet').addEventListener('click', closeSheet);
$('#show-power').addEventListener('click', () => sheet('power'));
$('#check-updates').addEventListener('click', () => command('app.checkUpdates', {}, 'Update check requested.'));
$('#cancel-power').addEventListener('click', closeSheet);
$('#power-backdrop').addEventListener('click', closeSheet);
$('#confirm-power').addEventListener('click', async () => {
  clearPendingVolume();
  if (state.target === 'philips') {
    if (state.sheet !== 'power' || !state.matter.connected || !state.matter.capabilities.power || state.busy || state.handoff) return;
    if (await command('matter.powerOff', { confirmed: true }, 'TV power command sent.', matterState)) closeSheet();
    return;
  }
  if (state.target !== 'luma' || state.sheet !== 'power' || !state.connected || state.busy || state.handoff) return;
  if (await command('luma.powerOff', { confirmed: true }, 'Power command sent.')) closeSheet();
});
$('#cancel-handoff').addEventListener('click', () => { if (state.setupFlow) state.autoStart = false; state.flowController?.abort(); coordinator.cancel(); cancelNative(state.jobToken); });
$('#retry-handoff').addEventListener('click', startCasting);
document.addEventListener('keydown', event => {
  if (state.sheet) {
    if (event.key === 'Escape') { event.preventDefault(); closeSheet(); }
    if (event.key === 'Tab') {
      const controls = [...$('#sheet').querySelectorAll('button, input, select')].filter(element => !element.disabled && element.offsetParent !== null);
      const first = controls[0]; const last = controls.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
    return;
  }
  if (event.isComposing || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey || event.target.closest('input, textarea, select, [contenteditable]')) return;
  if (event.key === 'Enter' && event.target.closest('button, a, summary')) return;
  const value = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right', Enter: 'enter', Escape: 'back', Home: 'home' }[event.key];
  if (value && state.connected) { event.preventDefault(); key(value); }
});
window.addEventListener('error', () => message('The remote could not finish that action. Try again.', true));
window.addEventListener('unhandledrejection', event => { event.preventDefault(); message('The remote could not finish that action. Try again.', true); });

setInterval(() => {
  if (!state.native || !state.philips.startupUrl || msxPolling || state.busy || state.handoff || document.visibilityState === 'hidden') return;
  msxPolling = true;
  void rpc('msx.status', {}, { timeoutMs: 2500 }).then(msxState).catch(() => {
    state.philips.connected = false;
    renderControls();
  }).finally(() => { msxPolling = false; });
}, 3000);

renderApps();
renderTracks();
renderControls();
void (async () => {
  try {
    const result = await rpc('app.ready');
    state.native = true;
    $('#browser-url').value = typeof result?.browserUrl === 'string' ? result.browserUrl : DEFAULT_SITE;
    message('Connect your projector to get started.');
    connectionState(result, { initial: true });
    loadSeekHistory(result?.seekHistory);
    loadCaptionPreferences(result?.captionPreferences);
    loadPicturePreferences(result?.picturePreferences);
    scheduleScrubWarmup();
    if (result?.source && !result?.remote) window.LumaApp.onCapturedMedia(result.source);
  } catch (error) { message(error.message, true); }
})();
