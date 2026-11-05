import test from 'node:test';
import assert from 'node:assert/strict';
import { audioCompanionStream, directCompatible, embeddedSubtitleStreams, ffmpegArgs, lanAddress, parseByteRange, parseCli, thumbnailPositions } from '../scripts/stream-local-media.mjs';
import { localMediaRemotePage } from '../scripts/local-media-remote.mjs';

test('local media CLI keeps repeated subtitle languages and a deliberate mode', () => {
  assert.deepEqual(parseCli([
    '/media/movie.mkv', '--host', '192.168.50.20', '--subtitle', '/media/movie.en.srt@en',
    '--subtitle', '/media/movie.pt-BR.srt@pt-BR', '--mode', 'compat', '--title', 'Movie night',
  ]), {
    mode: 'compat', subtitles: ['/media/movie.en.srt@en', '/media/movie.pt-BR.srt@pt-BR'],
    host: '192.168.50.20', title: 'Movie night', file: '/media/movie.mkv',
  });
  assert.throws(() => parseCli(['movie.mkv', '--mode', 'unsafe']), /auto, direct or compat/);
  assert.deepEqual(parseCli(['--root', '/media', '--no-open']), {
    mode: 'auto', subtitles: [], root: '/media', open: false, file: undefined,
  });
});

test('byte range parser supports seek, open-ended and suffix requests', () => {
  assert.equal(parseByteRange(undefined, 100), undefined);
  assert.deepEqual(parseByteRange('bytes=10-19', 100), { start: 10, end: 19 });
  assert.deepEqual(parseByteRange('bytes=90-', 100), { start: 90, end: 99 });
  assert.deepEqual(parseByteRange('bytes=-8', 100), { start: 92, end: 99 });
  assert.equal(parseByteRange('bytes=100-', 100), null);
  assert.equal(parseByteRange('bytes=1-2,5-6', 100), null);
});

test('auto direct mode is intentionally conservative for older projectors', () => {
  const h264Aac = { streams: [{ codec_type: 'video', codec_name: 'h264' }, { codec_type: 'audio', codec_name: 'aac' }] };
  assert.equal(directCompatible(h264Aac, '/media/movie.mkv'), true);
  assert.equal(directCompatible({ streams: [{ codec_type: 'video', codec_name: 'h264' }, { codec_type: 'audio', codec_name: 'eac3' }] }, '/media/movie.mkv'), true);
  assert.equal(directCompatible(h264Aac, '/media/movie.webm'), false);
  assert.equal(directCompatible({ streams: [{ codec_type: 'video', codec_name: 'hevc' }] }, '/media/movie.mp4'), false);
  assert.equal(directCompatible({ streams: [{ codec_type: 'video', codec_name: 'h264' }, { codec_type: 'audio', codec_name: 'opus' }] }, '/media/movie.mkv'), false);
});

test('direct video gets an AAC companion only when its preferred audio is unsupported', () => {
  const eac3 = { streams: [
    { index: 0, codec_type: 'video', codec_name: 'h264' },
    { index: 1, codec_type: 'audio', codec_name: 'eac3', disposition: { default: 1 } },
    { index: 2, codec_type: 'audio', codec_name: 'eac3', disposition: {} },
  ] };
  assert.equal(audioCompanionStream(eac3)?.index, 1);
  assert.equal(audioCompanionStream({ streams: [{ index: 1, codec_type: 'audio', codec_name: 'aac' }] }), null);
  assert.equal(audioCompanionStream({ streams: [{ index: 1, codec_type: 'audio', codec_name: 'mp3' }] }), null);
  assert.equal(audioCompanionStream({ streams: [] }), null);
});

test('embedded text subtitles prefer full dialogue over forced and SDH tracks', () => {
  const tracks = embeddedSubtitleStreams({ streams: [
    { index: 3, codec_type: 'subtitle', codec_name: 'subrip', tags: { language: 'eng', title: 'English [Forced]' }, disposition: { forced: 1 } },
    { index: 4, codec_type: 'subtitle', codec_name: 'subrip', tags: { language: 'eng', title: 'English' }, disposition: {} },
    { index: 5, codec_type: 'subtitle', codec_name: 'subrip', tags: { language: 'eng', title: 'English [SDH]' }, disposition: { hearing_impaired: 1 } },
    { index: 6, codec_type: 'subtitle', codec_name: 'hdmv_pgs_subtitle', tags: { language: 'eng' }, disposition: {} },
  ] });
  assert.deepEqual(tracks.map(track => [track.index, track.label]), [[4, 'English'], [3, 'English [Forced]'], [5, 'English [SDH]']]);
});

test('thumbnail generation covers the whole timeline before filling gaps', () => {
  const positions = thumbnailPositions(300, 30);
  assert.deepEqual(positions.slice(0, 3), [0, 300, 150]);
  assert.equal(new Set(positions).size, 11);
  assert.deepEqual([...positions].sort((left, right) => left - right), [0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300]);
});

test('compatibility mode is high quality, projector-safe and allowed to prebuffer', () => {
  const args = ffmpegArgs('/media/source.mkv', '/tmp/output');
  assert.deepEqual(args.slice(args.indexOf('-c:v'), args.indexOf('-force_key_frames')), ['-c:v', 'libx264', '-preset', 'slow', '-crf', '16', '-profile:v', 'high', '-level:v', '4.1']);
  assert.match(args[args.indexOf('-vf') + 1], /format=yuv420p/);
  assert.equal(args[args.indexOf('-readrate') + 1], '1.5');
  assert.equal(args[args.indexOf('-b:a') + 1], '320k');
  assert.equal(args[args.indexOf('-hls_time') + 1], '2');
  assert.equal(args[args.indexOf('-force_key_frames') + 1], 'expr:gte(t,n_forced*2)');
  assert.ok(args.includes('independent_segments+temp_file'));
});

test('LAN address selection uses the interface netmask rather than assuming /24', () => {
  const interfaces = {
    en0: [{ family: 'IPv4', internal: false, address: '192.168.32.4', netmask: '255.255.240.0' }],
    utun0: [{ family: 'IPv4', internal: false, address: '10.0.0.2', netmask: '255.255.255.0' }],
  };
  assert.equal(lanAddress('192.168.47.90', interfaces), '192.168.32.4');
  assert.throws(() => lanAddress('8.8.8.8', interfaces), /private IPv4/);
});

test('laptop browser remote renders a self-contained control page with valid JavaScript', () => {
  const html = localMediaRemotePage('/control/test/api/', '/media/<household>');
  assert.match(html, /LUMA laptop remote/);
  assert.match(html, /&lt;household&gt;/);
  const script = html.match(/<script>([\s\S]*)<\/script>/)?.[1];
  assert.ok(script);
  assert.doesNotThrow(() => new Function(script));
});
