import test from 'node:test';
import assert from 'node:assert/strict';
import { extractMedia } from '../src/shared/media.js';

const element = (attributes = {}, extra = {}) => ({ getAttribute: (name) => attributes[name] ?? null, ...extra });
function video(extra = {}, { sources = [], tracks = [] } = {}) {
  return element({}, {
    currentSrc: '', src: '', currentTime: 23, duration: 120,
    baseURI: 'https://player.example/watch/episode',
    ownerDocument: { title: 'Episode' },
    querySelectorAll: (selector) => selector === 'source' ? sources : tracks,
    ...extra,
  });
}

test('extracts the active direct source and preserves playback position', () => {
  assert.deepEqual(extractMedia(video({ currentSrc: 'https://media.example/movie.mp4?signature=private' })), {
    url: 'https://media.example/movie.mp4?signature=private',
    contentType: 'video/mp4', title: 'Episode', currentTime: 23, duration: 120, tracks: [],
  });
});

test('recognizes HLS, DASH, and WebM extensions without fetching', () => {
  for (const [extension, type] of [['m3u8', 'application/vnd.apple.mpegurl'], ['mpd', 'application/dash+xml'], ['webm', 'video/webm']]) {
    assert.equal(extractMedia(video({ currentSrc: `https://media.example/file.${extension}?token=hidden` })).contentType, type);
  }
});

test('uses a matching source MIME type for an extensionless selected source', () => {
  const sources = [element({ src: '/stream/opaque', type: 'application/vnd.apple.mpegurl; charset=utf-8' })];
  const result = extractMedia(video({ currentSrc: 'https://player.example/stream/opaque' }, { sources }));
  assert.equal(result.contentType, 'application/vnd.apple.mpegurl');
});

test('resolves a declared relative source only against the supplied page base', () => {
  const sources = [element({ src: 'movie.mp4' })];
  assert.equal(extractMedia(video({}, { sources }), { baseURI: 'https://cdn.example/asset/' }).url, 'https://cdn.example/asset/movie.mp4');
});

test('allows hls.js blob playback only when a direct source is explicitly declared', () => {
  const sources = [element({ src: 'https://media.example/manifest', type: 'application/x-mpegURL' })];
  assert.equal(extractMedia(video({ currentSrc: 'blob:https://player.example/id' }, { sources })).url, 'https://media.example/manifest');
  assert.throws(() => extractMedia(video({ currentSrc: 'blob:https://player.example/id' })), { code: 'SOURCE_UNSUPPORTED' });
});

test('does not fall back from a selected data or credential-bearing URL', () => {
  const sources = [element({ src: 'https://media.example/fallback.mp4' })];
  for (const currentSrc of ['data:video/mp4,AAAA', 'https://user:secret@media.example/file.mp4', 'javascript:alert(1)']) {
    assert.throws(() => extractMedia(video({ currentSrc }, { sources })), { code: 'SOURCE_UNSUPPORTED' });
  }
});

test('rejects attached media keys even when a direct URL exists', () => {
  assert.throws(() => extractMedia(video({ currentSrc: 'https://media.example/encrypted.mp4', mediaKeys: {} })), { code: 'DRM_UNSUPPORTED' });
});

test('rejects missing or unknown formats without including the source in errors', () => {
  assert.throws(() => extractMedia(null), { code: 'SOURCE_UNAVAILABLE' });
  assert.throws(() => extractMedia(video({ currentSrc: 'https://private.example/opaque?token=secret' })), (error) => {
    assert.equal(error.code, 'SOURCE_UNAVAILABLE');
    assert.doesNotMatch(error.message, /private|token|secret/);
    return true;
  });
});

test('extracts only HTTP WebVTT subtitle and caption tracks with stable IDs', () => {
  const tracks = [
    element({ src: '/subtitles/pt.vtt', srclang: 'pt-BR', label: 'Português', kind: 'subtitles' }, { track: { mode: 'showing' } }),
    element({ src: 'https://media.example/captions', type: 'text/vtt', srclang: 'en', kind: 'captions' }),
    element({ src: '/thumbnails.vtt', kind: 'metadata' }),
    element({ src: 'blob:https://player.example/subtitle', type: 'text/vtt' }),
    element({ src: '/captions.srt', kind: 'captions' }),
  ];
  const result = extractMedia(video({ currentSrc: 'https://media.example/movie.mp4' }, { tracks }));
  assert.deepEqual(result.tracks, [
    { id: 1, url: 'https://player.example/subtitles/pt.vtt', contentType: 'text/vtt', language: 'pt-BR', label: 'Português', active: true },
    { id: 2, url: 'https://media.example/captions', contentType: 'text/vtt', language: 'en', label: '', active: false },
  ]);
});

test('sanitizes nonfinite times and omits unavailable live duration', () => {
  const result = extractMedia(video({ currentSrc: 'https://media.example/live.m3u8', currentTime: NaN, duration: Infinity }), { title: 'Explicit title' });
  assert.equal(result.currentTime, 0);
  assert.equal(result.title, 'Explicit title');
  assert.equal('duration' in result, false);
});

test('accepts an extensionless HTML subtitle endpoint as declared WebVTT', () => {
  const tracks = [element({src:'/api/subtitles?url=private',kind:'subtitles',srclang:'en'})];
  const result = extractMedia(video({currentSrc:'https://media.example/movie.mp4'},{tracks}));
  assert.equal(result.tracks[0].contentType,'text/vtt');
  assert.equal(result.tracks[0].url,'https://player.example/api/subtitles?url=private');
});
