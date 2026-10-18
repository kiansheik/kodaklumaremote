import {extractMedia} from '../shared/media.js';
import {CastController} from '../cast/session.js';

// All code is bundled locally. This bridge uses only the SDK the site loaded.
// The page is not a trust boundary: there are no privileged extension APIs here.
if (!window.__framextvCastExtension) {
  const cleanup = install(window);
  window.__framextvCastExtension = {cleanup};
}

export function install(win) {
  const doc = win.document;
  let active = null;
  let controller = null;
  let remote = null;
  let casting = false;
  let connecting = false;
  let disposed = false;
  let generation = 0;
  let frame = 0;
  let mediaUrl = null;
  let pendingMediaUrl = null;
  let castingPath = null;
  let lastStatus = 'Cast to a Google Cast device';
  let currentPath = win.location.href;
  let binding = null;

  const host = doc.createElement('div');
  host.dataset.framextvCast = '';
  const shadow = host.attachShadow({mode: 'open'});
  shadow.innerHTML = `
    <style>
      :host {position:fixed; z-index:2147483646; display:block; color:white;
        font:13px system-ui,sans-serif; max-width:calc(100vw - 16px)}
      :host([hidden]) {display:none!important}
      .bar {display:flex; gap:6px; align-items:center; flex-wrap:wrap;
        background:#151820ed; border:1px solid #8888; border-radius:8px; padding:5px}
      button,select {font:inherit; color:white; background:#282d39; border:1px solid #777;
        border-radius:5px; padding:7px; cursor:pointer; min-height:32px}
      button:focus-visible,input:focus-visible,select:focus-visible {outline:2px solid #8dbaff}
      button:disabled {opacity:.6; cursor:wait}
      .remote {display:flex; align-items:center; gap:5px; flex-wrap:wrap}
      [hidden] {display:none!important} input {max-width:100px; width:70px}
      .message {margin:4px 0 0; padding:5px 8px; background:#151820ed;
        border-radius:5px; max-width:310px} svg {vertical-align:middle}
    </style>
    <div class="bar">
      <button class="cast" type="button" aria-label="Cast video" title="Cast video">
        <svg width="20" height="18" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M1 18v3h3a3 3 0 0 0-3-3zm0-4v2a5 5 0 0 1 5 5h2a7 7 0 0 0-7-7zm0-4v2a9 9 0 0 1 9 9h2A11 11 0 0 0 1 10zm2-7a2 2 0 0 0-2 2v3h2V5h18v14h-7v2h7a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2H3z"/></svg>
        <span>Cast</span>
      </button>
      <div class="remote" hidden>
        <button class="play" type="button" aria-label="Pause remote video">Pause</button>
        <label>Seek <input class="seek" aria-label="Remote position" type="range" min="0" max="0" value="0" step="1"></label>
        <label>Volume <input class="volume" aria-label="Remote volume" type="range" min="0" max="1" value="1" step="0.05"></label>
        <select class="subtitles" aria-label="Remote subtitles"><option value="">Subtitles off</option></select>
        <button class="stop" type="button">Disconnect</button>
      </div>
    </div>
    <div class="message" role="status" aria-live="polite" hidden></div>`;
  const $ = selector => shadow.querySelector(selector);
  const button = $('.cast');
  const message = $('.message');
  const sourceSignature = video => JSON.stringify([video.getAttribute('src'),
    [...video.querySelectorAll('source')].map(source => [source.getAttribute('src'),source.getAttribute('type')])]);

  function status(text, show = true) {
    lastStatus = text;
    button.title = text;
    message.textContent = text;
    message.hidden = !show;
  }
  function drawRemote(snapshot) {
    remote = snapshot;
    if (!casting || !snapshot) return;
    $('.play').textContent = snapshot.paused ? 'Resume' : 'Pause';
    $('.play').setAttribute('aria-label', snapshot.paused ? 'Resume remote video' : 'Pause remote video');
    $('.seek').max = String(Number.isFinite(snapshot.duration) ? snapshot.duration : 0);
    $('.seek').value = String(snapshot.currentTime || 0);
    $('.volume').value = String(snapshot.volume ?? 1);
  }
  function restoreLocal() {
    if (!casting) return;
    casting = false;
    $('.remote').hidden = true;
    button.querySelector('span').textContent = 'Cast';
    if (binding?.video === active && active?.isConnected && remote && Number.isFinite(remote.currentTime)) {
      try {
        if (castingPath === win.location.href && sourceSignature(active) === binding.signature && extractMedia(active, {baseURI:doc.baseURI}).url === mediaUrl) {
          active.currentTime = remote.currentTime;
        }
      } catch {}
    }
    // Stay paused on return; the user chooses when local playback resumes.
    mediaUrl = null;
  }
  function ensureController() {
    if (!controller) {
      controller = new CastController({window: win,
        onStatus: result => {
          if (disposed) return;
          status(result.message);
          if (['disconnected', 'ended', 'error'].includes(result.state)) restoreLocal();
        },
        onRemote: snapshot => { if (!disposed) drawRemote(snapshot); }
      });
    }
    return controller;
  }
  function report(error) {
    // Adapter/media errors are sanitized. Never render arbitrary SDK/page errors.
    if (error?.code === 'CANCELED') {status('Device selection canceled.'); return;}
    if (error?.code === 'DRM_UNSUPPORTED') {status('Encrypted playback cannot be sent by this integration.'); return;}
    const castMessages = {
      SDK_UNAVAILABLE: 'This page has not provided the Google Cast sender SDK.',
      SDK_NOT_READY: 'The page’s Cast sender is not ready yet. Wait for the player to finish loading, then try again.',
      RECEIVER_UNAVAILABLE: 'No compatible Cast receiver is available. Check that the TV supports Google Cast and is on the same network.',
      SESSION_TIMEOUT: 'The Cast receiver did not connect in time. Check the TV and try again.',
      SESSION_FAILED: 'Could not connect to a Cast receiver. Check Chrome’s Cast menu and the TV’s network connection.',
      LOAD_FAILED: 'The Cast receiver connected but could not load this media. Check receiver format support and direct access to the source.'
    };
    if (Object.hasOwn(castMessages, error?.code)) {status(castMessages[error.code]); return;}
    status(['SOURCE_UNSUPPORTED', 'SOURCE_UNAVAILABLE'].includes(error?.code)
      ? 'This player does not expose a supported direct media URL. Blob/MSE sources cannot be sent directly.'
      : 'Cast could not start.');
  }
  async function cast() {
    if (!active || connecting || casting) return;
    const video = active;
    const attempt = ++generation;
    connecting = true;
    button.disabled = true;
    try {
      const media = extractMedia(video, {baseURI: doc.baseURI, title: doc.title});
      pendingMediaUrl = media.url;
      const signature = sourceSignature(video);
      binding.signature = signature;
      if (!win.cast?.framework || !win.chrome?.cast?.media) {
        status('Google Cast is not ready on this page. Allow its existing Cast SDK to finish loading, then retry.');
        return;
      }
      status('Select a Google Cast device…');
      const adapter = ensureController();
      await adapter.connect(media);
      let sameSource = false;
      try {sameSource = sourceSignature(video) === signature && extractMedia(video, {baseURI:doc.baseURI}).url === media.url;} catch {}
      if (attempt !== generation || disposed || active !== video || !video.isConnected || !sameSource) {
        await adapter.disconnect();
        return;
      }
      // Receiver LOAD must succeed before local playback is paused.
      casting = true;
      mediaUrl = media.url;
      castingPath = win.location.href;
      video.pause();
      $('.remote').hidden = false;
      button.querySelector('span').textContent = 'Casting';
      const options = [new Option('Subtitles off', '')];
      for (const track of media.tracks) {
        options.push(new Option(track.label || track.language || `Track ${track.id}`, String(track.id)));
      }
      $('.subtitles').replaceChildren(...options);
      $('.subtitles').value = String(media.tracks.find(track => track.active)?.id || '');
      status('Playing directly on the receiver. Use these controls as the remote.');
      drawRemote(remote);
    } catch (error) { if (attempt === generation && !disposed) report(error); }
    finally {
      connecting = false;
      pendingMediaUrl = null;
      button.disabled = false;
    }
  }
  function command(callback) {
    try { const result = callback(); result?.catch?.(() => status('The remote command failed. Check the Cast connection.')); }
    catch { status('The remote command failed. Check the Cast connection.'); }
  }
  button.addEventListener('click', cast);
  $('.play').addEventListener('click', () => command(() => remote?.paused ? controller?.resume() : controller?.pause()));
  $('.seek').addEventListener('change', event => command(() => controller?.seek(Number(event.target.value))));
  $('.volume').addEventListener('change', event => command(() => controller?.setVolume(Number(event.target.value))));
  $('.subtitles').addEventListener('change', event => command(() => controller?.setTracks(event.target.value ? [Number(event.target.value)] : [])));
  $('.stop').addEventListener('click', () => command(() => controller?.disconnect()));

  function releaseVideo() {
    ++generation;
    if (casting || connecting) {
      command(() => controller?.disconnect());
      restoreLocal();
    }
    if (binding) {
      binding.video.removeEventListener('emptied', binding.changed);
      binding.video.removeEventListener('loadedmetadata', binding.changed);
      binding = null;
    }
    controller?.dispose();
    controller = null;
    remote = null;
    active = null;
  }
  function bind(video) {
    releaseVideo();
    active = video;
    if (!video) {host.remove(); return;}
    const changed = () => {
      if (!casting && !connecting) return;
      try {
        if (sourceSignature(video) === binding.signature && extractMedia(video, {baseURI: doc.baseURI}).url === (casting ? mediaUrl : pendingMediaUrl)) return;
      } catch {}
      ++generation;
      command(() => controller?.disconnect());
      restoreLocal();
      status('The episode source changed. Press Cast to load the new video.');
    };
    binding = {video, changed, signature:sourceSignature(video)};
    video.addEventListener('emptied', changed);
    video.addEventListener('loadedmetadata', changed);
    status('Cast to a Google Cast device', false);
    doc.documentElement.append(host);
  }
  function scan() {
    frame = 0;
    if (disposed) return;
    const isWatch = /^\/(?:watch|embed)(?:\/|$)/.test(win.location.pathname);
    if (currentPath !== win.location.href) {
      currentPath = win.location.href;
      releaseVideo();
    }
    const videos = isWatch ? [...doc.querySelectorAll('video')].filter(video => {
      const rect = video.getBoundingClientRect();
      return video.isConnected && rect.width > 80 && rect.height > 45 && win.getComputedStyle(video).visibility !== 'hidden';
    }) : [];
    videos.sort((a,b) => b.clientWidth*b.clientHeight - a.clientWidth*a.clientHeight);
    const chosen = videos[0] || null;
    if (chosen !== active) bind(chosen);
    if (!active) return;
    if (casting || connecting) binding.changed();
    // Preserve an existing site Cast control instead of adding a duplicate.
    const nativeCast = doc.querySelector('google-cast-launcher, [data-framextv-native-cast], [aria-label="Cast to TV / Chromecast"], [aria-label^="Disconnect Cast ("]');
    if (nativeCast && nativeCast.getBoundingClientRect().width > 0) {
      host.hidden = true;
      return;
    }
    host.hidden = false;
    const rect = active.getBoundingClientRect();
    const fullscreen = doc.fullscreenElement;
    // A replaced <video> cannot display children. A wrapper fullscreen can.
    const mount = fullscreen && fullscreen !== active && fullscreen.contains(active) ? fullscreen : doc.documentElement;
    if (host.parentNode !== mount) mount.append(host);
    host.style.left = `${Math.max(8, Math.min(rect.right - (casting ? 450 : 95), win.innerWidth - (casting ? 450 : 95)))}px`;
    host.style.top = `${Math.max(8, Math.min(rect.top + 8, win.innerHeight - host.offsetHeight - 8))}px`;
    button.title = lastStatus;
  }
  function schedule() {if (!disposed && !frame) frame = win.requestAnimationFrame(scan);}
  const observer = new MutationObserver(records => {
    if (records.some(record => !host.contains(record.target))) schedule();
  });
  observer.observe(doc.documentElement, {subtree:true, childList:true, attributes:true, attributeFilter:['src','class','style','hidden']});
  win.addEventListener('resize', schedule);
  win.addEventListener('scroll', schedule, true);
  win.addEventListener('popstate', schedule);
  doc.addEventListener('fullscreenchange', schedule);
  // pushState is not patched; poll only the route, avoiding function-integrity changes.
  const poll = win.setInterval(() => {if (currentPath !== win.location.href) schedule();}, 500);
  schedule();
  function cleanup() {
    if (disposed) return;
    disposed = true;
    releaseVideo();
    observer.disconnect();
    win.clearInterval(poll);
    win.cancelAnimationFrame(frame);
    win.removeEventListener('resize', schedule);
    win.removeEventListener('scroll', schedule, true);
    win.removeEventListener('popstate', schedule);
    doc.removeEventListener('fullscreenchange', schedule);
    win.removeEventListener('pagehide', onHide);
    win.removeEventListener('pageshow', schedule);
    host.remove();
    delete win.__framextvCastExtension;
  }
  function onHide(event) {
    if (event.persisted) {releaseVideo(); host.remove();}
    else cleanup();
  }
  win.addEventListener('pagehide', onHide);
  win.addEventListener('pageshow', schedule);
  return cleanup;
}
