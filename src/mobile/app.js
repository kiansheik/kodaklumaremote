import { createHandoffCoordinator } from '../handoff/coordinator.mjs';

const $ = selector => document.querySelector(selector);
const DEFAULT_SITE = 'https://framextv.xyz/watch/284833/1/1';
const MSX_SETUP_HINTS = Object.freeze({
  HTTPS_REQUEST: 'The TV is trying a secure connection. In Media Station X setup, leave the padlock open.',
  ORIGIN_REJECTED: 'The TV reached this phone, but its app connection was rejected. Share the error shown on the TV.',
  PAIRING_EXPIRED: 'Tap Restart TV setup, then try the setup again on the TV.',
  BOOTSTRAP_READY: 'The TV loaded the setup. Open “Connect this TV” on the TV next.',
});
const errors = {
  NATIVE_UNAVAILABLE: 'Open this page in the LUMA app to use the remote.',
  TIMEOUT: 'The projector did not reply. Check its connection and try again.',
  CONNECTION_FAILED: 'Could not connect to the projector. Check its power and Wi-Fi.',
  NOT_CONNECTED: 'Connect to your projector first.',
  UNAUTHORIZED: 'Kodi did not accept these connection details.',
  PAIRING_REQUIRED: 'This projector requires pairing that this app does not support yet.',
  PAIRING_UNKNOWN: 'The projector pairing setting could not be verified.',
  APP_NOT_AVAILABLE: 'That app is not in the projector’s current app list.',
  INVALID_INPUT: 'Check the entered details and try again.',
  INVALID_HOST: 'Enter the projector’s local IP address.',
  INVALID_RESPONSE: 'The projector returned an unsupported response. Reconnect and try again.',
  INVALID_TEXT: 'Use a single line of up to 1000 characters.',
  KODI_CONFIG: 'Enter the username, password and HTTP port configured in Kodi.',
  KODI_AUTH: 'Kodi did not accept the username or password. Check the Kodi connection details.',
  KODI_UNAVAILABLE: 'Could not reach Kodi. Open Kodi and check its HTTP control setting.',
  KODI_RESPONSE: 'Kodi did not accept this control command. Try refreshing playback.',
  KODI_REJECTED: 'Kodi rejected this request before it could be applied. Try casting again.',
  NO_VIDEO: 'Kodi has no active video. Choose a video in Browse first.',
  INVALID_MEDIA: 'The page did not expose a supported video. Open its player and try Cast again.',
  INVALID_SUBTITLE: 'Choose an available subtitle track or turn subtitles off.',
  CANNOT_SEEK: 'Kodi reports that this video cannot seek.',
  BUSY: 'The remote is busy. Wait a moment and try again.',
  CANCELLED: 'Casting was cancelled.',
  SOURCE_UNAVAILABLE: 'No supported video was found. Open its player and try Cast again.',
  INVALID_REQUEST: 'That app action is unavailable. Try again.',
  SOURCE_CHANGED: 'The selected video changed. Choose it again in Browse.',
  STORAGE_UNAVAILABLE: 'Settings could not be saved on this phone. Try setup again.',
  SETUP_TIMEOUT: 'Kodi did not become ready. Check its HTTP control setting and try again.',
  MSX_NOT_CONNECTED: 'Open Media Station X on the Philips TV and connect it in TV setup.',
  MSX_TIMEOUT: 'The TV did not finish this request. Refresh its connection and check the TV screen.',
  MSX_PLAYBACK: 'The TV could not play this video. Check the TV screen and try another source.',
  MSX_SUBTITLE: 'The TV could not load the selected subtitles. Choose another language and try again.',
  MSX_NETWORK: 'Could not start the TV connection. Check this phone’s Wi-Fi and try TV setup again.',
  MSX_PAIRING: 'Allow your TV in TV connection setup before using its remote.',
  MSX_UNAVAILABLE: 'Media Station X is not connected. Open it on the TV and refresh the connection.',
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
  native: false, busy: false, handoff: false, configured: false, connected: false,
  host: '', name: '', playback: null, activeMedia: null, pendingMedia: null,
  tracks: [], catalog: [], selectedIndex: null, subtitlesEnabled: false, apps: [],
  appPage: 0, subtitlePage: 0, sheet: null, sourceGeneration: 0, sourceChanged: false, autoStart: false, setupError: null,
  jobToken: null, flowController: null, setupFlow: false, volume: null, skipNextLaunch: false, subtitleFlow: false, subtitleError: null,
  settings: { hasReceiver: false, hasKodi: false, autoConnect: true },
  target: 'luma', pendingTarget: 'luma',
  tvSetup: 'remote', matterShareTimer: null,
  matter: { available: false, paired: false, connected: false, pairing: false, capabilities: {}, volume: null, inputs: [] },
  philips: { connected: false, host: '', name: 'Philips TV', startupUrl: '', awaitingApproval: false, pendingHost: '', playback: null, activeMedia: null, tracks: [], catalog: [], selectedIndex: null, subtitlesEnabled: false, volume: null, subtitlePage: 0 },
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
const VOLUME_COOLDOWN_MS = 2000;

function receiver(target = state.target) { return target === 'philips' ? state.philips : state; }
function tvConnected() { return state.philips.connected || state.matter.connected; }
function volumeContext() {
  if (state.target !== 'philips') return state.connected ? { route: 'luma', key: `luma:${state.host}` } : null;
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
function navigationReady() { return state.native && !state.busy && !state.handoff && (state.target === 'philips' ? state.matter.connected && state.matter.capabilities.navigation : state.connected); }
function matterState(value) {
  if (!value || typeof value !== 'object') return;
  const remote = state.matter;
  for (const key of ['available', 'paired', 'connected', 'pairing', 'paused']) if (typeof value[key] === 'boolean') remote[key] = value[key];
  if (value.errorCode === null || value.errorCode === '') delete remote.errorCode;
  else if (typeof value.errorCode === 'string' && Object.hasOwn(errors, value.errorCode)) remote.errorCode = value.errorCode;
  if (value.capabilities && typeof value.capabilities === 'object') {
    const capabilities = value.capabilities;
    remote.capabilities = {};
    for (const key of ['navigation', 'power', 'volume', 'inputs', 'openWindow']) remote.capabilities[key] = capabilities[key] === true;
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
  $('#media-title').textContent = remote.activeMedia ? title(remote.activeMedia.title) : remote.playback?.active ? 'Now playing' : 'Ready to cast';
  const snapshot = remote.playback;
  if (!snapshot) $('#playback-status').textContent = 'Choose a video in Browse.';
  else if (!snapshot.active) $('#playback-status').textContent = 'No video playing.';
  else {
    const label = typeof snapshot.playing === 'boolean' ? (snapshot.playing ? 'Playing' : 'Paused') : 'Video active';
    const timing = Number.isFinite(snapshot.positionSeconds) && Number.isFinite(snapshot.durationSeconds)
      ? ` · ${clock(snapshot.positionSeconds)} / ${clock(snapshot.durationSeconds)}` : '';
    $('#playback-status').textContent = label + timing;
  }
  $('#play-toggle').textContent = snapshot?.playing === false ? 'Resume' : 'Pause';
  const value = remoteVolume();
  $('#volume-level').textContent = value ? state.target === 'philips' ? `${Math.round(value.current / value.max * 100)}%` : `${value.current} / ${value.max}` : '—';
  $('#volume-range').max = String(value?.max || 1);
  $('#volume-range').min = String(value?.min || 0);
  $('#volume-range').value = String(value?.current || 0);
  $('#volume-range').setAttribute('aria-valuetext', value ? `${value.current} of ${value.max}` : 'Unavailable');
}
function msxState(value) {
  if (!value || typeof value !== 'object') return;
  const remote = state.philips;
  if (value.matter) matterState(value.matter);
  const wasConnected = remote.connected;
  if (value.diagnostics && typeof value.diagnostics === 'object') {
    // Native diagnostics are reduced to fixed reasons and counts. Never copy
    // request origins, URLs, capability tokens or server messages into the UI.
    remote.diagnostics = {};
    for (const key of ['connections', 'tlsRequests', 'bootstrapRequests', 'bootstrapAccepted', 'lastStatus', 'lastRequestMsAgo']) {
      const count = value.diagnostics[key];
      if (Number.isSafeInteger(count) && count >= 0) remote.diagnostics[key] = count;
    }
    const reason = value.diagnostics.lastReason;
    if (Object.hasOwn(MSX_SETUP_HINTS, reason)) remote.diagnostics.lastReason = reason;
  }
  if (typeof value.connected === 'boolean') remote.connected = value.connected;
  if (typeof value.host === 'string' && privateHost(value.host)) remote.host = value.host;
  if (typeof value.name === 'string') remote.name = safeText(value.name) || 'Philips TV';
  if (typeof value.awaitingApproval === 'boolean') remote.awaitingApproval = value.awaitingApproval;
  remote.pendingCommand = value.pendingCommand || null;
  if (remote.openUncertain && !remote.pendingCommand && (value.lastCommand || Object.hasOwn(value, 'pendingCommand'))) remote.openUncertain = false;
  remote.pendingHost = privateHost(value.pendingHost || '') ? value.pendingHost : '';
  if (typeof value.startupUrl === 'string') {
    if (!value.startupUrl) remote.startupUrl = '';
    try {
      const url = new URL(value.startupUrl);
      if (url.protocol === 'http:' && privateHost(url.hostname) && !url.username && !url.password) remote.startupUrl = url.origin;
    } catch { /* Only show the native listener's local address. */ }
  }
  const snapshot = value.playback;
  if (snapshot && typeof snapshot.active === 'boolean') {
    const sourceUrl = snapshot.sourceUrl || snapshot.source;
    if (remote.activeMedia && snapshot.active && sourceUrl && sourceUrl !== remote.activeMedia.url) {
      remote.activeMedia = null; remote.catalog = []; remote.tracks = [];
    }
    remote.playback = { ...snapshot, sourceUrl, playing: typeof snapshot.playing === 'boolean' ? snapshot.playing : snapshot.paused !== true };
    if (snapshot.active && !remote.activeMedia && typeof sourceUrl === 'string') remote.activeMedia = { url: sourceUrl, title: title(snapshot.title) };
    if (Array.isArray(snapshot.tracks)) {
      remote.tracks = snapshot.tracks.slice(0, 128).filter(track => Number.isInteger(track?.index)).map(track => ({ index: track.index, language: safeText(track.language, 64), label: safeText(track.label, 200) }));
      if (!remote.catalog.length) remote.catalog = remote.tracks;
    }
    if (typeof snapshot.subtitlesEnabled === 'boolean') remote.subtitlesEnabled = snapshot.subtitlesEnabled;
    if (snapshot.selectedIndex === null || Number.isInteger(snapshot.selectedIndex)) remote.selectedIndex = snapshot.selectedIndex;
    volume(snapshot, 'philips');
  }
  volume(value, 'philips');
  if (state.target === 'philips') renderTracks();
  renderControls();
  if (!wasConnected && remote.connected && state.target === 'philips' && $('#page-devices').hidden === false) {
    page('remote');
    message('Philips TV connected. Your remote is ready.');
    if (state.pendingMedia && state.pendingTarget === 'philips') {
      state.autoStart = true;
      if (!state.busy && !state.handoff) queueMicrotask(() => void startCasting());
    }
  }
}

async function selectTarget(target) {
  if (!['luma', 'philips'].includes(target) || target === state.target || state.busy || state.handoff) return;
  clearPendingVolume();
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
  state.target = target;
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
  const mutationReceipt = method === 'kodi.addSubtitle' || method === 'kodi.open';
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
      reject: code => { if (mutationReceipt && ['SOURCE_CHANGED', 'KODI_REJECTED'].includes(code)) notSent(); settled(); remove(); reject(fixedError(code)); },
    };
    pending.set(id, item);
    signal?.addEventListener('abort', abort, { once: true });
    try { window.LumaNative.postMessage(JSON.stringify({ id, method, params })); }
    catch { remove(); reject(fixedError('NATIVE_UNAVAILABLE')); }
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

function page(name) {
  for (const item of ['browse', 'remote', 'devices']) {
    $(`#page-${item}`).hidden = item !== name;
    $(`#tab-${item}`).setAttribute('aria-selected', String(item === name));
  }
}
function deviceStep(name) {
  $('#receiver-panel').hidden = name !== 'receiver';
  $('#kodi-panel').hidden = name !== 'kodi';
  $('#receiver-step').toggleAttribute('aria-current', name === 'receiver');
  $('#kodi-step').toggleAttribute('aria-current', name === 'kodi');
  if (name === 'receiver') $('#receiver-step').setAttribute('aria-current', 'step');
  if (name === 'kodi') $('#kodi-step').setAttribute('aria-current', 'step');
}
function closeSheet() {
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
  $('#sheet-title').textContent = { settings: 'Configuration', reset: 'Forget saved setup', power: state.target === 'philips' ? 'Turn off the TV?' : 'Turn off the projector?', more: 'More controls', typing: 'Type on the projector', apps: 'Your apps', subtitles: 'Subtitles', seek: 'Go to time', inputs: 'TV inputs', 'matter-share': 'Add another phone', handoff: state.setupFlow ? 'Set up Kodi' : 'Start casting' }[name];
  $('#close-sheet').focus();
  if (name === 'power') $('#cancel-power').focus();
  if (name === 'typing') $('#remote-text').focus();
  if (name === 'seek') {
    $('#seek-range').max = String(Math.max(1, receiver().playback?.durationSeconds || 1));
    $('#seek-range').value = String(receiver().playback?.positionSeconds || 0);
    $('#seek-time').textContent = clock($('#seek-range').value);
  }
  renderControls();
}
function renderControls() {
  if (pendingVolume && pendingVolume.key !== volumeContext()?.key) clearPendingVolume();
  const free = state.native && !state.busy && !state.handoff;
  const tv = state.target === 'philips';
  const remote = receiver();
  const matter = state.matter;
  const tvRemote = free && tv && matter.connected;
  const navigation = navigationReady();
  const luma = free && !tv && state.connected;
  const kodi = free && (tv ? remote.connected : state.configured);
  const active = kodi && remote.playback?.active === true;
  const saved = tv ? remote.connected || matter.paired : state.settings.hasReceiver && state.settings.hasKodi;
  renderRemote();
  for (const target of ['luma', 'philips']) {
    $(`#target-${target}`).disabled = !free;
    $(`#target-${target}`).setAttribute('aria-pressed', String(state.target === target));
    $(`#target-${target}`).dataset.connected = String(target === 'philips' ? tvConnected() : state.connected);
  }
  $('#tab-devices').hidden = saved;
  $('#show-settings').hidden = !saved;
  $('#show-settings').disabled = !free;
  $('#device-badge').textContent = tv ? (matter.connected ? 'TV remote connected' : remote.connected ? 'Media Station X connected' : 'TV not connected') : state.connected ? (state.name || 'Projector connected') : 'No projector connected';
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
  $('#kodi-panel').hidden ||= tv;
  if (!tv && $('#receiver-panel').hidden && $('#kodi-panel').hidden) deviceStep('receiver');
  $('#msx-startup').hidden = !remote.startupUrl;
  $('#msx-startup').textContent = remote.startupUrl ? new URL(remote.startupUrl).host : '';
  $('#msx-pairing').textContent = remote.awaitingApproval && remote.pendingHost ? `Allow the TV at ${remote.pendingHost} if it is yours.` : remote.connected ? 'TV connected. You can choose a video in Browse.' : remote.startupUrl ? 'Enter this address on the TV, then allow the connection here.' : 'Use the same Wi-Fi network on both devices.';
  const setupHint = !remote.connected && !remote.awaitingApproval ? MSX_SETUP_HINTS[remote.diagnostics?.lastReason] : '';
  $('#msx-diagnostic').textContent = setupHint || '';
  $('#msx-diagnostic').hidden = !setupHint;
  $('#msx-panel').dataset.diagnostic = String(Boolean(setupHint));
  $('#msx-start').disabled = !free;
  $('#msx-start').textContent = remote.startupUrl ? 'Restart TV setup' : 'Set up TV';
  $('#msx-refresh').hidden = !remote.startupUrl;
  $('#msx-refresh').disabled = !free;
  $('#msx-confirm').hidden = !remote.awaitingApproval || !remote.pendingHost;
  $('#msx-confirm').disabled = !free;
  $('.dpad').hidden = tv && !matter.capabilities.navigation;
  $('.dpad').setAttribute('aria-label', tv ? 'TV navigation' : 'Projector navigation');
  $('#tv-remote').hidden = !tv || matter.capabilities.navigation === true;
  $('#tv-remote-hint').textContent = remote.connected ? 'Media Station X is ready. Playback controls apply to this TV.' : 'Open Media Station X on the TV and complete TV setup.';
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
  $('#configure-kodi').disabled = !luma;
  for (const input of $('#kodi-form').querySelectorAll('input')) input.disabled = !free;
  for (const button of document.querySelectorAll('[data-key]')) { button.disabled = !navigation; if (button.closest('.topline')) button.style.visibility = tv && !matter.capabilities.navigation ? 'hidden' : ''; }
  $('#refresh-volume').disabled = !(tv ? kodi || tvRemote : luma);
  const displayedVolume = remoteVolume();
  for (const id of ['volume-range', 'volume-down', 'volume-up']) $(`#${id}`).disabled = !(tv ? hardwareVolume || kodi : luma) || !displayedVolume;
  if (displayedVolume) {
    $('#volume-down').disabled ||= displayedVolume.current <= (displayedVolume.min || 0);
    $('#volume-up').disabled ||= displayedVolume.current >= displayedVolume.max;
  }
  $('#settings-disconnect').disabled = !(tv ? kodi || tvRemote : luma);
  $('#show-power').hidden = tv && !matter.capabilities.power;
  $('#show-power').setAttribute('aria-label', tv ? 'Power off TV' : 'Power off projector');
  $('#show-power').disabled = !(tv ? tvRemote && matter.capabilities.power : luma);
  $('#confirm-power').disabled = $('#show-power').disabled;
  for (const button of $('#panel-settings').querySelectorAll('button:not(#settings-disconnect)')) button.disabled = !free;
  $('#settings-hint').textContent = tv ? 'TV setup and LUMA settings are kept separately. Switching devices leaves both connected.' : 'Your projector and verified Kodi login are saved on this phone. Opening the app reconnects automatically.';
  $('#edit-receiver').textContent = tv ? 'TV connection' : 'Projector connection';
  $('#edit-kodi').hidden = tv;
  $('#rescan-projectors').hidden = tv;
  $('#show-reset').textContent = tv ? 'Forget this TV' : 'Forget saved setup';
  $('#reset-hint').textContent = tv ? 'Forget the Philips TV pairing on this phone? LUMA setup stays saved.' : 'Choose what to forget on this phone. A playing video stays on the projector.';
  $('#forget-matter-local').hidden = !(tv && matter.paired && state.tvSetup === 'remote');
  $('#forget-matter-local').disabled = !free;
  $('#matter-local-hint').hidden = $('#forget-matter-local').hidden;
  for (const button of document.querySelectorAll('[data-reset]')) button.disabled = !free;
  for (const button of document.querySelectorAll('[data-reset]')) {
    button.hidden = tv && button.dataset.reset !== 'all';
    button.textContent = tv ? 'Forget Philips TV pairing' : { kodi: 'Forget Kodi login', receiver: 'Forget projector', all: 'Forget all saved settings' }[button.dataset.reset];
  }
  $('#play-toggle').disabled = !active;
  $('#refresh-playback').disabled = !kodi;
  $('#show-subtitles').disabled = !active;
  $('#show-more').disabled = !free || (tv ? !tvConnected() : !state.connected && !state.configured);
  $('#show-tv-inputs').hidden = !tv || !matter.capabilities.inputs;
  $('#show-tv-inputs').disabled = !tvRemote;
  $('#show-matter-setup').hidden = !tv || !matter.available;
  $('#open-kodi').disabled = !luma;
  $('#stop-video').disabled = !active;
  $('#seek-video').disabled = !active || !remote.playback?.durationSeconds || remote.playback?.canSeek === false;
  $('#seek-range').disabled = $('#seek-video').disabled;
  $('#remote-text').disabled = !luma;
  $('#send-text').disabled = !luma || !$('#remote-text').value.trim();
  $('#clear-text').disabled = !free || !$('#remote-text').value;
  $('#subtitles-off').disabled = !active;
  for (const button of $('#app-list').querySelectorAll('button')) button.disabled = !luma;
  for (const button of $('#subtitle-list').querySelectorAll('button')) button.disabled = !active;
  for (const button of document.querySelectorAll('button[data-panel]')) { button.disabled = !free; button.hidden = tv && ['typing', 'apps'].includes(button.dataset.panel); }
  $('#open-kodi').hidden = tv;
  $('#menu-key').hidden = tv;
  $('#capture-video').disabled = !free;
  $('#choose-video').disabled = !state.native;
  $('#cast-selected').hidden = !state.pendingMedia;
  $('#cast-selected').disabled = !free || state.pendingTarget !== state.target || (tv && remote.openUncertain === true);
  $('#cancel-handoff').hidden = !state.handoff;
  $('#cancel-handoff').textContent = state.setupFlow ? 'Cancel waiting' : 'Cancel casting';
  $('#retry-handoff').hidden = state.handoff || !state.pendingMedia || state.setupFlow;
}
function playback(snapshot, target = 'luma') {
  if (!snapshot || typeof snapshot.active !== 'boolean') return;
  const remote = receiver(target);
  if (remote.activeMedia && snapshot.active && snapshot.sourceUrl && snapshot.sourceUrl !== remote.activeMedia.url) {
    remote.activeMedia = null;
    remote.tracks = [];
    remote.catalog = [];
  }
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
  return state.activeMedia ? { ...extra, expectedSourceUrl: state.activeMedia.url } : extra;
}
function restoredRemote(value, observedPlayback) {
  if (!value || typeof value !== 'object' || typeof value.media?.url !== 'string' || !Array.isArray(value.tracks) || value.tracks.length > 128) return null;
  let url;
  try {
    url = new URL(value.media.url);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.hash) return null;
  } catch { return null; }
  if (observedPlayback?.active === true && observedPlayback.sourceUrl !== url.href) return null;
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
      catalog.push({ url: trackUrl.href, contentType: 'text/vtt', language: safeText(track.language, 64), label: safeText(track.label, 200), index: track.index });
    }
  }
  return {
    // A service-restored remote is not a newly captured source. Never restore
    // its capture/job tokens or feed it to the handoff coordinator.
    media: { url: url.href, contentType: safeText(value.media.contentType, 100), title: title(value.media.title) },
    tracks,
    catalog,
    selectedIndex: Number.isInteger(value.selectedIndex) && indices.has(value.selectedIndex) ? value.selectedIndex : null,
    subtitlesEnabled: value.subtitlesEnabled === true,
  };
}
function connectionState(snapshot, { initial = false } = {}) {
  if (!snapshot || typeof snapshot !== 'object') return;
  if (initial && ['luma', 'philips'].includes(snapshot.selectedTarget)) state.target = snapshot.selectedTarget;
  if (snapshot.receivers?.philips) msxState(snapshot.receivers.philips);
  if (snapshot.settings && typeof snapshot.settings === 'object') {
    for (const key of ['hasReceiver', 'hasKodi', 'autoConnect']) if (typeof snapshot.settings[key] === 'boolean') state.settings[key] = snapshot.settings[key];
  }
  const connection = snapshot.connection;
  if (connection && typeof connection === 'object') {
    if (typeof connection.connected === 'boolean') state.connected = connection.connected;
    if (typeof connection.configured === 'boolean') state.configured = connection.configured;
    if (typeof connection.host === 'string' && privateHost(connection.host)) {
      if (state.host && state.host !== connection.host) {
        state.activeMedia = null; state.tracks = []; state.catalog = []; state.selectedIndex = null;
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
  if (state.connected && $('#message').textContent === 'Connecting to your saved projector…') message('Projector connected. Your remote is ready.');
  if (!state.handoff) {
    const remote = state.subtitleFlow ? null : restoredRemote(snapshot.remote, snapshot.playback);
    if (remote) {
      state.activeMedia = state.activeMedia?.url === remote.media.url ? Object.assign(state.activeMedia, remote.media) : remote.media;
      state.tracks = remote.tracks;
      state.catalog = remote.catalog;
      state.selectedIndex = remote.selectedIndex;
      state.subtitlesEnabled = remote.subtitlesEnabled;
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
  } else if (initial && (state.connected || (state.settings.hasReceiver && state.settings.hasKodi))) {
    page('remote');
    message(state.connected ? 'Projector connected. Your remote is ready.' : 'Connecting to your saved projector…');
  } else if (initial && state.settings.hasReceiver) {
    deviceStep('kodi');
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
      state.connected = false;
      state.apps = [];
      state.volume = null;
      $('#volume-level').textContent = '—';
      renderApps();
    }
    if (typeof method === 'string' && method.startsWith('msx.') && ['MSX_NOT_CONNECTED', 'NOT_CONNECTED', 'CONNECTION_FAILED'].includes(error.code)) state.philips.connected = false;
    if (['KODI_AUTH', 'KODI_CONFIG'].includes(error.code)) {
      state.configured = false;
      closeSheet();
      page('devices');
      deviceStep('kodi');
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
  return { ...extra, ...(options.phase === 'remote' ? {} : { sourceToken: state.jobToken }), expectedSourceUrl: options.expectedSourceUrl };
}
async function handoffRpc(method, params, options) {
  try { return await rpc(method, params, options); }
  catch (error) {
    if (['KODI_AUTH', 'KODI_CONFIG'].includes(error.code)) {
      if (options.phase === 'remote') { state.subtitleError = error; throw error; }
      state.setupError = error;
      coordinator.cancel();
    }
    throw error;
  }
}
const coordinator = createHandoffCoordinator({
  receiver: { launchKodi: options => {
    if (state.skipNextLaunch) { state.skipNextLaunch = false; return Promise.resolve({ accepted: true }); }
    return rpc('luma.launchKodi', handoffParams(options), options);
  } },
  kodi: {
    connect: options => handoffRpc('kodi.connect', handoffParams(options), options),
    open: (media, options) => handoffRpc('kodi.open', handoffParams(options, { media, ...(options.onlyIfInactive === true ? { onlyIfInactive: true } : {}) }), options),
    status: async options => { const result = await handoffRpc('kodi.status', handoffParams(options), options); playback(result); return result; },
    getSubtitles: options => handoffRpc('kodi.getSubtitles', handoffParams(options), options),
    addSubtitle: (track, options) => handoffRpc('kodi.addSubtitle', handoffParams(options, { track }), options),
    setSubtitle: (index, options) => handoffRpc('kodi.setSubtitle', handoffParams(options, { index }), options),
  },
  remote: { async ready(result) {
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
      media: { url: state.activeMedia.url, contentType: state.activeMedia.contentType, title: state.activeMedia.title },
      tracks: result.tracks.map(track => ({ index: track.index, language: track.language, label: track.label })),
      catalog: state.catalog.map(track => ({ url: track.url, language: track.language, label: track.label, index: track.index })),
      selectedIndex: result.selectedIndex,
      subtitlesEnabled: result.selectedIndex !== null,
    };
    // Give the service a bounded chance to remember aliases before reporting
    // completion. Receiver playback remains successful if local pausing fails.
    await rpc('app.handoffReady', { sourceToken: state.jobToken, remote }, { timeoutMs: 2000 }).catch(() => {});
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
    deviceStep(state.connected ? 'kodi' : 'receiver');
    message('Video selected. Finish connecting to start casting.');
    return;
  }
  const selected = state.pendingMedia;
  const generation = state.sourceGeneration;
  const id = ++flowId;
  state.sourceChanged = false;
  state.setupError = null;
  state.handoff = true;
  const controller = new AbortController();
  state.flowController = controller;
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
      message('Video is playing. Choose a language in Subtitles.');
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
      deviceStep('kodi');
    }
    message(text, error.code !== 'CANCELLED');
    $('#handoff-label').textContent = text;
  } finally { if (id === flowId) { state.handoff = false; state.flowController = null; applyDeferredTarget(); renderControls(); } }
}
async function startMsxCasting() {
  if (state.target !== 'philips') { message('Select Philips to continue casting this video.', true); return; }
  if (state.philips.openUncertain) { message('Refresh playback while the TV finishes the previous request.'); return; }
  if (!state.philips.connected) { page('devices'); message('Connect Media Station X to start this video.'); return; }
  const selected = state.pendingMedia;
  const generation = state.sourceGeneration;
  const id = ++flowId;
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
    state.philips.activeMedia = { url: selected.url, contentType: selected.contentType, title: title(selected.title) };
    state.philips.catalog = (selected.tracks || []).map((track, index) => ({ ...track, index }));
    msxState(result);
    state.pendingMedia = null;
    await rpc('app.handoffReady', { target: 'philips', sourceToken: jobToken }, { timeoutMs: 2000 }).catch(() => {});
    closeSheet(); page('remote');
    message('Ready on Philips TV. You can use the remote.');
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
      state.philips.activeMedia = { url: selected.url, contentType: selected.contentType, title: title(selected.title) };
      state.philips.catalog = (selected.tracks || []).map((track, index) => ({ ...track, index }));
      state.pendingMedia = null;
      await rpc('app.handoffReady', { target: 'philips', sourceToken: jobToken }, { timeoutMs: 2000 }).catch(() => {});
      closeSheet(); page('remote');
      message(state.philips.subtitlesEnabled ? 'Video is playing on Philips TV.' : 'Video is playing. Choose subtitles in the remote to retry them.');
    } else {
      // A delivered command may finish after the phone's timeout. Do not offer a
      // second Open while its result is unknown; refresh observes the first one.
      state.philips.openUncertain = uncertain && (!result || Boolean(result.pendingCommand));
      const text = state.philips.openUncertain ? 'The TV is still checking this video. Refresh playback before trying again.' : error.message;
      message(text, error.code !== 'CANCELLED');
      $('#handoff-label').textContent = text;
      if (state.philips.openUncertain) { closeSheet(); page('remote'); }
    }
  } finally {
    if (id === flowId) { state.handoff = false; state.flowController = null; applyDeferredTarget(); renderControls(); }
  }
}
window.LumaApp = Object.freeze({
  onConnectionState(snapshot) { connectionState(snapshot); },
  onOpenRemote() {
    if (!state.handoff) closeSheet();
    page('remote');
  },
  onCapturedMedia(media) {
    if (!media || typeof media.url !== 'string') { message('No supported video was found. Open its player and try Cast again.', true); return; }
    reportStatus('captured');
    if (state.setupFlow) {
      state.sourceGeneration++;
      state.pendingMedia = { ...media, captureToken: media.sourceToken, title: title(media.title) };
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
    state.pendingMedia = { ...media, captureToken: media.sourceToken, title: title(media.title) };
    state.pendingTarget = state.target;
    state.autoStart = true;
    void startCasting();
    if (interruptedMsx) void rpc('msx.status', {}, { timeoutMs: 5000 }).then(msxState).catch(() => {});
  },
  onSourceChanged() {
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
    media: { url: state.activeMedia.url, contentType: state.activeMedia.contentType, title: state.activeMedia.title },
    tracks: state.tracks.map(track => ({ index: track.index, language: track.language, label: track.label })),
    catalog: state.catalog.map(track => ({ url: track.url, language: track.language, label: track.label, index: track.index })),
    selectedIndex: state.selectedIndex,
    subtitlesEnabled: state.subtitlesEnabled,
  };
}
async function selectSubtitle(track) {
  if (state.busy || state.handoff) return;
  if (state.target === 'philips') {
    await command('msx.subtitle', msxParams({ index: track.index, enabled: true }), 'Subtitle selected. Check the TV.', result => { state.philips.selectedIndex = track.index; state.philips.subtitlesEnabled = true; msxState(result); renderTracks(); });
    return;
  }
  if (!track.url) {
    void command('kodi.setSubtitle', remoteParams({ index: track.index }), 'Subtitle selected. Check the projector.', () => { state.selectedIndex = track.index; state.subtitlesEnabled = true; renderTracks(); });
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
          isCurrentSource: () => state.activeMedia === media && state.connected,
        });
      } catch (error) { throw state.subtitleError || error; }
    }, {}, 'Subtitle selected. Check the projector.', async result => {
      if (state.activeMedia !== media) throw fixedError('SOURCE_CHANGED');
      state.tracks = result.tracks;
      state.catalog = result.catalog;
      state.selectedIndex = result.selectedIndex;
      state.subtitlesEnabled = result.selectedIndex !== null;
      renderTracks();
      await rpc('app.rememberRemote', { expectedSourceUrl: media.url, remote: remoteDescriptor() });
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
$('#msx-start').addEventListener('click', () => command('msx.start', {}, 'Enter this phone’s address in Media Station X on the TV.', msxState));
$('#msx-refresh').addEventListener('click', () => command('msx.status', {}, '', msxState));
$('#msx-confirm').addEventListener('click', async () => {
  const host = state.philips.pendingHost;
  if (!host) return;
  if (await command('msx.confirm', { host }, 'TV allowed. Waiting for Media Station X…', msxState)) {
    if (state.philips.connected) { page('remote'); if (state.pendingMedia) void startCasting(); }
  }
});
$('#show-settings').addEventListener('click', () => sheet('settings'));
$('#edit-receiver').addEventListener('click', () => { closeSheet(); page('devices'); deviceStep('receiver'); });
$('#edit-kodi').addEventListener('click', () => { closeSheet(); page('devices'); deviceStep('kodi'); });
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
      state.settings.hasKodi = false;
      $('#kodi-username').value = '';
      $('#kodi-password').value = '';
    }
    if (scope !== 'kodi') {
      state.connected = false;
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
  if (success) { closeSheet(); page('devices'); deviceStep(scope === 'kodi' ? 'kodi' : 'receiver'); }
});
$('#choose-video').addEventListener('click', () => browse());
$('#browser-form').addEventListener('submit', event => { event.preventDefault(); void browse(true); });
$('#capture-video').addEventListener('click', () => command('app.capture', {}, '', result => {
  const media = result?.media || (result?.url ? result : null);
  if (media && media.sourceToken !== (state.pendingMedia?.captureToken || state.pendingMedia?.sourceToken)) window.LumaApp.onCapturedMedia(media);
}));
$('#cast-selected').addEventListener('click', startCasting);
$('#receiver-step').addEventListener('click', () => deviceStep('receiver'));
$('#kodi-step').addEventListener('click', () => deviceStep('kodi'));
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
      state.playback = null;
      $('#media-title').textContent = 'Ready to cast';
      $('#playback-status').textContent = 'Choose a video in Browse.';
    }
    state.connected = true;
    state.settings.hasReceiver = true;
    state.host = host;
    state.name = safeText(result.device?.name || result.name) || 'LUMA projector';
    $('#receiver-name').textContent = state.name;
    $('#device-results').replaceChildren();
  });
  if (success) { if (state.configured) { page('remote'); void startCasting(); } else deviceStep('kodi'); }
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
      state.name = safeText(result.device?.name || result.name) || state.name;
      const found = result.devices?.find(device => privateHost(device?.host || ''));
      if (found) { connectionState({ connection: { host: found.host } }); $('#receiver-host').value = found.host; }
      state.settings.hasReceiver = true;
      $('#receiver-name').textContent = state.name;
      if (state.configured) page('remote'); else deviceStep('kodi');
      message(state.configured ? 'Projector connected. Your remote is ready.' : 'Projector connected. Check the Kodi login to finish setup.');
    } else foundDevices(result);
  });
});
function disconnect() {
  clearPendingVolume();
  if (state.target === 'philips') return command(async () => {
    if (state.matter.paired) matterState(await rpc('matter.disconnect', {}));
    return rpc('msx.disconnect', {});
  }, {}, 'TV controls disconnected. A playing video stays on the TV.', result => { state.philips.connected = false; msxState(result); });
  return command('device.disconnect', {}, 'Disconnected. A playing video stays on the projector.', () => { state.connected = false; state.configured = false; state.apps = []; state.volume = null; $('#volume-level').textContent = '—'; renderApps(); });
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
async function configureKodi() {
  if (state.busy || state.handoff || !state.connected) return;
  const credentials = { username: $('#kodi-username').value, password: $('#kodi-password').value, port: Number($('#kodi-port').value) };
  state.setupFlow = true;
  state.handoff = true;
  state.configured = false;
  const controller = new AbortController();
  state.flowController = controller;
  const deadline = performance.now() + 30000;
  const options = () => ({ signal: controller.signal, timeoutMs: Math.max(1, Math.min(8000, deadline - performance.now())) });
  const progress = (stage, text, value) => { reportStatus(stage); $('#handoff-label').textContent = text; $('#handoff-progress').value = value; message(text); };
  sheet('handoff');
  let success = false;
  try {
    progress('setup', 'Checking your Kodi setup…', 1);
    await rpc('kodi.configure', credentials, options());
    progress('launching', 'Opening Kodi on the projector…', 2);
    await rpc('luma.launchKodi', {}, options());
    progress('waiting', 'Waiting for Kodi and checking its login…', 3);
    await waitSetup(1000, controller.signal);
    while (true) {
      if (controller.signal.aborted) throw fixedError('CANCELLED');
      if (performance.now() >= deadline) throw fixedError('SETUP_TIMEOUT');
      try {
        const result = await rpc('kodi.connect', {}, options());
        if (result?.ready !== true) throw fixedError('KODI_UNAVAILABLE');
        break;
      } catch (error) {
        if (!['KODI_UNAVAILABLE', 'TIMEOUT', 'CONNECTION_FAILED'].includes(error.code)) throw error;
        if (performance.now() >= deadline) throw fixedError('SETUP_TIMEOUT');
        await waitSetup(Math.min(500, deadline - performance.now()), controller.signal);
      }
    }
    state.configured = true;
    state.settings.hasKodi = true;
    $('#kodi-username').value = '';
    $('#kodi-password').value = '';
    await rpc('luma.status', {}, options()).then(volume).catch(() => {});
    success = true;
    state.skipNextLaunch = Boolean(state.pendingMedia);
    closeSheet();
    page('remote');
    message('Setup saved on this phone. Choose a video in Browse, then tap Cast.');
    reportStatus('ready');
  } catch (error) {
    state.configured = false;
    closeSheet();
    page('devices'); deviceStep('kodi');
    message(error.code === 'CANCELLED' ? 'Checking stopped. Validated settings may already be saved. No video was sent.' : error.message, error.code !== 'CANCELLED');
    reportStatus(error.code === 'CANCELLED' ? 'cancelled' : 'error', error.code);
  } finally {
    state.setupFlow = false;
    state.handoff = false;
    state.flowController = null;
    applyDeferredTarget();
    renderControls();
    if (success && state.pendingMedia) void startCasting();
  }
}
$('#kodi-form').addEventListener('submit', async event => {
  event.preventDefault();
  void configureKodi();
});
$('#play-toggle').addEventListener('click', () => {
  if (state.target === 'philips') {
    const play = state.philips.playback?.playing === false;
    void command('msx.playPause', msxParams({ play }), play ? 'Resume requested.' : 'Pause requested.', msxState);
    return;
  }
  const action = state.playback?.playing === false ? 'resume' : 'pause';
  void command(`kodi.${action}`, remoteParams(), action === 'pause' ? 'Pause requested.' : 'Resume requested.', async () => playback(await rpc('kodi.status', remoteParams())));
});
$('#refresh-playback').addEventListener('click', () => state.target === 'philips' ? command('msx.status', {}, 'Playback refreshed.', msxState) : command('kodi.status', remoteParams(), 'Playback refreshed.', playback));
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
$('#show-more').addEventListener('click', () => sheet('more'));
document.querySelectorAll('[data-panel]').forEach(button => button.addEventListener('click', () => {
  sheet(button.dataset.panel);
  if (button.dataset.panel === 'apps') void command('luma.apps', {}, 'Apps refreshed.', result => { state.apps = Array.isArray(result?.apps) ? result.apps.slice(0, 200) : []; state.appPage = 0; renderApps(); });
}));
$('#show-subtitles').addEventListener('click', () => {
  sheet('subtitles');
  renderTracks();
  if (state.target === 'philips') { void command('msx.status', {}, '', msxState); return; }
  void command('kodi.getSubtitles', remoteParams(), '', result => {
    state.subtitlesEnabled = result.enabled === true;
    state.selectedIndex = result.currentIndex ?? null;
    if (!state.tracks.length) state.tracks = (result.tracks || []).map(track => ({ index: track.index, language: safeText(track.language), label: safeText(track.label || track.name) }));
    renderTracks();
  });
});
$('#subtitles-off').addEventListener('click', () => state.target === 'philips' ? command('msx.subtitle', msxParams({ index: state.philips.selectedIndex ?? 0, enabled: false }), 'Subtitles turned off.', result => { state.philips.subtitlesEnabled = false; state.philips.selectedIndex = null; msxState(result); renderTracks(); }) : command('kodi.setSubtitle', remoteParams({ index: 'off' }), 'Subtitles turned off.', async () => {
  state.subtitlesEnabled = false; state.selectedIndex = null; renderTracks();
  if (state.activeMedia && state.catalog.length) await rpc('app.rememberRemote', { expectedSourceUrl: state.activeMedia.url, remote: remoteDescriptor() });
}));
$('#open-kodi').addEventListener('click', () => command('luma.launchKodi', {}, 'Kodi launch sent. Check the projector.'));
$('#stop-video').addEventListener('click', () => state.target === 'philips' ? command('msx.stop', msxParams(), 'Stop requested.', result => { msxState(result); closeSheet(); }) : command('kodi.stop', remoteParams(), 'Stop requested.', result => { playback(result?.active === false ? result : { active: false }); closeSheet(); }));
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
$('#seek-range').addEventListener('input', () => { $('#seek-time').textContent = clock($('#seek-range').value); });
$('#seek-video').addEventListener('click', () => state.target === 'philips' ? command('msx.seek', msxParams({ seconds: Number($('#seek-range').value) }), 'Seek requested. Check playback on the TV.', msxState) : command('kodi.seek', remoteParams({ position: Number($('#seek-range').value) }), 'Seek requested. Check playback on the projector.', async () => playback(await rpc('kodi.status', remoteParams()))));
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
      const controls = [...$('#sheet').querySelectorAll('button, input')].filter(element => !element.disabled && element.offsetParent !== null);
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
    if (result?.source && !result?.remote) window.LumaApp.onCapturedMedia(result.source);
  } catch (error) { message(error.message, true); }
})();
