const EXTENSION_TYPES = new Map([
  ['mp4', 'video/mp4'],
  ['webm', 'video/webm'],
  ['m3u8', 'application/vnd.apple.mpegurl'],
  ['mpd', 'application/dash+xml'],
]);

function failure(code, message) {
  return Object.assign(new Error(message), { code });
}

function httpURL(value, baseURI) {
  if (typeof value !== 'string' || !value.trim()) return null;
  try {
    const url = new URL(value.trim(), baseURI);
    // URL credentials cannot be forwarded safely as receiver metadata.
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
    return url.href;
  } catch {
    return null;
  }
}

function contentType(value, url) {
  const explicit = typeof value === 'string' ? value.split(';', 1)[0].trim().toLowerCase() : '';
  if (/^video\/[a-z0-9!#$&^_.+-]+$/.test(explicit)) return explicit;
  if (['application/vnd.apple.mpegurl', 'application/x-mpegurl', 'application/dash+xml'].includes(explicit)) {
    return explicit;
  }
  const extension = new URL(url).pathname.match(/\.([a-z0-9]+)$/i)?.[1].toLowerCase();
  return EXTENSION_TYPES.get(extension) || null;
}

const attribute = (element, name) => element.getAttribute?.(name) || '';
const finiteTime = (value) => Number.isFinite(value) && value >= 0 ? value : 0;

/** Read only sources explicitly exposed by an ordinary, unencrypted video element. */
export function extractMedia(video, { baseURI, title } = {}) {
  if (!video || typeof video.querySelectorAll !== 'function') {
    throw failure('SOURCE_UNAVAILABLE', 'No video player is available.');
  }
  if (video.mediaKeys) {
    throw failure('DRM_UNSUPPORTED', 'Encrypted playback cannot be sent by this integration.');
  }
  const base = baseURI || video.baseURI || video.ownerDocument?.baseURI;
  const sources = Array.from(video.querySelectorAll('source'));
  const selected = video.currentSrc || attribute(video, 'src') || video.src;
  let url;
  let type;
  if (selected && !selected.startsWith('blob:')) {
    url = httpURL(selected, base);
    if (!url) throw failure('SOURCE_UNSUPPORTED', 'The player does not expose a direct HTTP media source.');
    const matchingSource = sources.find((source) => httpURL(attribute(source, 'src') || source.src, base) === url);
    type = contentType(attribute(matchingSource || video, 'type'), url);
  } else {
    // hls.js can use a blob currentSrc while retaining a declared manifest <source>.
    // Read that declaration only; never infer a URL from network or MSE internals.
    for (const source of sources) {
      const candidate = httpURL(attribute(source, 'src') || source.src, base);
      if (!candidate) continue;
      const candidateType = contentType(attribute(source, 'type') || source.type, candidate);
      if (candidateType) {
        url = candidate;
        type = candidateType;
        break;
      }
    }
  }
  if (!url || !type) {
    throw failure(selected?.startsWith('blob:') ? 'SOURCE_UNSUPPORTED' : 'SOURCE_UNAVAILABLE', 'No supported direct media URL is exposed by the player.');
  }

  const tracks = [];
  for (const element of video.querySelectorAll('track')) {
    const kind = (attribute(element, 'kind') || element.kind || 'subtitles').toLowerCase();
    if (!['subtitles', 'captions'].includes(kind)) continue;
    const trackURL = httpURL(attribute(element, 'src') || element.src, base);
    if (!trackURL) continue;
    const declaredType = (attribute(element, 'type') || '').split(';', 1)[0].trim().toLowerCase();
    const extension = new URL(trackURL).pathname.match(/\.([a-z0-9]+)$/i)?.[1].toLowerCase();
    // HTML subtitle/caption <track> resources are WebVTT by contract. Allow an
    // extensionless declared endpoint, but never relabel an explicit SRT/ASS file.
    if (declaredType && declaredType !== 'text/vtt') continue;
    if (declaredType !== 'text/vtt' && extension && extension !== 'vtt') continue;
    tracks.push({
      id: tracks.length + 1,
      url: trackURL,
      contentType: 'text/vtt',
      language: String(attribute(element, 'srclang') || element.srclang || '').slice(0, 64),
      label: String(attribute(element, 'label') || element.label || '').slice(0, 200),
      active: element.track?.mode === 'showing',
    });
  }

  const result = {
    url,
    contentType: type,
    title: String(title ?? (attribute(video, 'title') || video.ownerDocument?.title || 'Video')).slice(0, 200),
    currentTime: finiteTime(video.currentTime),
    tracks,
  };
  if (Number.isFinite(video.duration) && video.duration > 0) result.duration = video.duration;
  return result;
}
