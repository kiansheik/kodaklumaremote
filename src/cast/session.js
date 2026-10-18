const error = (code, message) => Object.assign(new Error(message), { code });
const finite = (value, fallback = 0) => Number.isFinite(value) && value >= 0 ? value : fallback;
const canceled = (reason) => ['cancel', 'cancelled', 'canceled'].includes(String(reason?.code ?? reason).toLowerCase());

function validateURL(value) {
  try {
    const url = new URL(value);
    return ['http:', 'https:'].includes(url.protocol) && !url.username && !url.password;
  } catch {
    return false;
  }
}

/** Adapter for a page's already available Google Cast Web Sender SDK. Never loads code. */
export class CastController {
  constructor({ window, onStatus = () => {}, onRemote = () => {} } = {}) {
    this.window = window;
    this.onStatus = onStatus;
    this.onRemote = onRemote;
    this._generation = 0;
    this._disposed = false;
    this._connected = false;
    this._pending = false;
    this._listeners = [];
    this._endedSessions = new WeakSet();
    this._last = { currentTime: 0, duration: 0, paused: false, volume: 1, muted: false, connected: false };
  }

  _status(state, message) {
    if (!this._disposed) this.onStatus({ state, message });
  }

  _listen(target, type, handler) {
    if (type == null || typeof target?.addEventListener !== 'function') return;
    target.addEventListener(type, handler);
    this._listeners.push(() => target.removeEventListener(type, handler));
  }

  _cleanup() {
    for (const remove of this._listeners.splice(0)) remove();
    this._player = null;
    this._remote = null;
    this._session = null;
    this._connected = false;
  }

  _snapshot(connected = this._connected) {
    const player = this._player;
    const saved = !connected ? player?.savedPlayerState : null;
    const media = this._session?.getMediaSession?.();
    const ready = player?.isMediaLoaded;
    const currentTime = !connected
      ? finite(saved?.currentTime, finite(ready ? player.currentTime : undefined, this._last.currentTime))
      : finite(ready ? player.currentTime : media?.currentTime, this._last.currentTime);
    this._last = {
      currentTime,
      duration: finite(ready ? player.duration : connected ? media?.media?.duration : undefined, this._last.duration),
      paused: typeof saved?.isPaused === 'boolean' ? saved.isPaused : typeof player?.isPaused === 'boolean' && ready ? player.isPaused : this._last.paused,
      volume: finite(connected || ready ? player?.volumeLevel : undefined, this._last.volume),
      muted: (connected || ready) && typeof player?.isMuted === 'boolean' ? player.isMuted : this._last.muted,
      connected,
    };
    if (!this._disposed) this.onRemote({ ...this._last });
    return { ...this._last };
  }

  _finish(state, message) {
    const snapshot = this._snapshot(false);
    this._generation += 1;
    this._cleanup();
    this._status(state, message);
    return snapshot;
  }

  async _end(session) {
    if (!session || this._endedSessions.has(session)) return;
    this._endedSessions.add(session);
    await session.endSession(true);
  }

  _initialize() {
    const framework = this.window?.cast?.framework;
    const chromeCast = this.window?.chrome?.cast;
    if (!framework?.CastContext?.getInstance || !framework.RemotePlayer || !framework.RemotePlayerController || !chromeCast?.media?.MediaInfo || !chromeCast.media.LoadRequest) {
      throw error('SDK_UNAVAILABLE', 'This page has not provided the Google Cast sender SDK. Use Chrome’s Cast menu for tab mirroring.');
    }
    this._framework = framework;
    this._chromeCast = chromeCast;
    this._context = framework.CastContext.getInstance();
    this._listen(this._context, framework.CastContextEventType?.SESSION_STATE_CHANGED, (event) => {
      if (!this._session) return;
      const state = event.sessionState;
      if (state === framework.SessionState?.SESSION_ENDED || state === framework.SessionState?.SESSION_START_FAILED) {
        this._finish('ended', 'Cast session ended.');
      }
    });
    // The embedding page owns initialization, receiver selection, and SDK callbacks.
  }

  _request(media) {
    const api = this._chromeCast.media;
    const info = new api.MediaInfo(media.url, media.contentType);
    if (typeof api.GenericMediaMetadata === 'function') {
      info.metadata = new api.GenericMediaMetadata();
      info.metadata.title = String(media.title || 'Video').slice(0, 200);
    }
    if (Number.isFinite(media.duration) && media.duration > 0) info.duration = media.duration;
    const tracks = media.tracks || [];
    this._trackIds = new Set();
    if (tracks.length && typeof api.Track !== 'function') throw error('TRACKS_UNAVAILABLE', 'The sender SDK cannot send subtitle tracks.');
    info.tracks = tracks.map((track) => {
      if (!Number.isSafeInteger(track.id) || track.id <= 0 || this._trackIds.has(track.id) || !validateURL(track.url) || track.contentType !== 'text/vtt') {
        throw error('INVALID_MEDIA', 'The video subtitle information is invalid.');
      }
      this._trackIds.add(track.id);
      const item = new api.Track(track.id, api.TrackType?.TEXT || 'TEXT');
      item.trackContentId = track.url;
      item.trackContentType = 'text/vtt';
      item.subtype = api.TextTrackType?.SUBTITLES || 'SUBTITLES';
      item.name = track.label || track.language || 'Subtitles';
      item.language = track.language || '';
      return item;
    });
    const request = new api.LoadRequest(info);
    request.autoplay = true;
    request.currentTime = finite(media.currentTime);
    request.activeTrackIds = tracks.filter((track) => track.active).map((track) => track.id);
    return request;
  }

  async connect(media) {
    if (this._disposed) throw error('DISPOSED', 'The Cast controller has been disposed.');
    if (this._pending || this._connected) throw error('BUSY', 'A Cast connection is already active.');
    if (!media || !validateURL(media.url) || typeof media.contentType !== 'string' || !/^(video\/[a-z0-9!#$&^_.+-]+|application\/(vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))$/i.test(media.contentType)) {
      throw error('INVALID_MEDIA', 'A supported direct HTTP media source is required.');
    }
    this._pending = true;
    const generation = ++this._generation;
    let session;
    let opened = false;
    let loadAccepted = false;
    this._status('connecting', 'Select a Cast device.');
    try {
      this._cleanup();
      this._initialize();
      session = this._context.getCurrentSession();
      if (!session) {
        opened = true;
        await this._context.requestSession();
        session = this._context.getCurrentSession();
      }
      if (generation !== this._generation || this._disposed) throw error('STALE', 'Cast connection was canceled.');
      if (!session) throw error('SESSION_UNAVAILABLE', 'No Cast session is available.');
      this._session = session;
      const request = this._request(media);
      this._player = new this._framework.RemotePlayer();
      this._remote = new this._framework.RemotePlayerController(this._player);
      const result = await session.loadMedia(request);
      if (result != null) throw error('LOAD_FAILED', 'The receiver could not load this media.');
      loadAccepted = true;
      if (generation !== this._generation || this._disposed) throw error('STALE', 'Cast connection was canceled.');
      this._last = { ...this._last, currentTime: finite(media.currentTime), duration: finite(media.duration), paused: false };
      const events = this._framework.RemotePlayerEventType || {};
      const types = events.ANY_CHANGE ? [events.ANY_CHANGE] : [events.CURRENT_TIME_CHANGED, events.DURATION_CHANGED, events.IS_PAUSED_CHANGED, events.VOLUME_LEVEL_CHANGED, events.IS_MUTED_CHANGED, events.IS_CONNECTED_CHANGED];
      for (const type of new Set(types)) {
        this._listen(this._remote, type, (event) => {
          if (this._connected && event?.field === 'isConnected' && event.value === false) this._finish('ended', 'Cast session ended.');
          else this._snapshot();
        });
      }
      this._connected = true;
      const snapshot = this._snapshot();
      this._status('connected', 'Playing directly on the Cast receiver.');
      return snapshot;
    } catch (reason) {
      const stale = generation !== this._generation || this._disposed;
      if (!stale) this._cleanup();
      if ((opened || (stale && loadAccepted)) && session) {
        try { await this._end(session); } catch { /* Preserve the original sanitized failure. */ }
      }
      if (!stale) {
        const state = reason?.code === 'SDK_UNAVAILABLE' ? 'unavailable' : canceled(reason) ? 'canceled' : 'error';
        this._status(state, state === 'unavailable' ? 'This page has not provided the Google Cast sender SDK. Use Chrome’s Cast menu for tab mirroring.' : state === 'canceled' ? 'Device selection canceled.' : 'Cast could not start. Check receiver format support and direct access to the media.');
      }
      if (stale) throw error('CANCELED', 'Cast connection was canceled.');
      if (canceled(reason)) throw error('CANCELED', 'Device selection canceled.');
      if (reason?.code === 'SDK_UNAVAILABLE') throw error('SDK_UNAVAILABLE', 'This page has not provided the Google Cast sender SDK.');
      throw error('CAST_FAILED', 'Cast could not start. Check receiver format support and direct access to the media.');
    } finally {
      this._pending = false;
    }
  }

  _requireRemote() {
    if (!this._connected || !this._remote || this._disposed) throw error('NOT_CONNECTED', 'No Cast media is connected.');
  }

  _command(action) {
    this._requireRemote();
    try {
      action();
      return this._snapshot();
    } catch {
      throw error('COMMAND_FAILED', 'The Cast receiver could not apply this control.');
    }
  }

  pause() {
    return this._command(() => { if (!this._player.isPaused) this._remote.playOrPause(); });
  }

  resume() {
    return this._command(() => { if (this._player.isPaused) this._remote.playOrPause(); });
  }

  seek(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) throw error('INVALID_VALUE', 'Seek time must be a nonnegative number.');
    return this._command(() => {
      const duration = finite(this._player.duration, this._last.duration);
      this._player.currentTime = duration > 0 ? Math.min(seconds, duration) : seconds;
      this._remote.seek();
    });
  }

  setVolume(level) {
    if (!Number.isFinite(level) || level < 0 || level > 1) throw error('INVALID_VALUE', 'Volume must be between zero and one.');
    return this._command(() => {
      this._player.volumeLevel = level;
      this._remote.setVolumeLevel();
    });
  }

  setMuted(muted) {
    if (typeof muted !== 'boolean') throw error('INVALID_VALUE', 'Muted must be a boolean.');
    return this._command(() => { if (this._player.isMuted !== muted) this._remote.muteOrUnmute(); });
  }

  async setTracks(trackIds) {
    this._requireRemote();
    if (!Array.isArray(trackIds) || trackIds.some((id) => !this._trackIds.has(id))) throw error('INVALID_VALUE', 'Select valid subtitle tracks.');
    const api = this._chromeCast.media;
    const media = this._session.getMediaSession?.();
    if (!media?.editTracksInfo || !api.EditTracksInfoRequest) throw error('TRACKS_UNAVAILABLE', 'Subtitle controls are unavailable for this receiver.');
    try {
      await new Promise((resolve, reject) => media.editTracksInfo(new api.EditTracksInfoRequest([...new Set(trackIds)]), resolve, reject));
      this._requireRemote();
      return this._snapshot();
    } catch {
      throw error('COMMAND_FAILED', 'The Cast receiver could not change subtitles.');
    }
  }

  async disconnect() {
    const session = this._session;
    const snapshot = this._finish('disconnected', 'Cast disconnected.');
    try {
      await this._end(session);
    } catch {
      this._status('error', 'The receiver could not confirm disconnection.');
      throw error('DISCONNECT_FAILED', 'The receiver could not confirm disconnection.');
    }
    return snapshot;
  }

  dispose() {
    if (this._disposed) return;
    this._disposed = true;
    this._generation += 1;
    this._cleanup();
  }
}
