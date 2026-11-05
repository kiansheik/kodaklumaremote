#!/usr/bin/env node
// Stream a laptop file directly to the paired LUMA Receiver.
//
//   npm run stream:local -- /path/movie.mkv --host 192.168.1.50
//   npm run stream:local -- /path/movie.mkv --subtitle /path/movie.pt-BR.srt@pt-BR
//   npm run stream:local -- /path/movie.mkv --mode compat --title "Movie night"
//
// `auto` preserves compatible H.264/AAC media exactly. Other codecs are
// converted with ffmpeg to high-quality 1080p H.264/AAC HLS for older Android
// projectors. Same-basename .srt/.vtt/.ass/.ssa/.ttml sidecars are discovered.
import http from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import {
  chmodSync, createReadStream, existsSync, mkdirSync, mkdtempSync, readFileSync,
  readdirSync, renameSync, rmSync, statSync, writeFileSync,
} from 'node:fs';
import { networkInterfaces, tmpdir } from 'node:os';
import path from 'node:path';
import readline from 'node:readline/promises';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const CONFIG = path.join(ROOT, '.local/receiver-dev.json');
const RECEIVER_PORT = 4191;
const SUBTITLE_EXTENSIONS = new Set(['.srt', '.vtt', '.ass', '.ssa', '.ttml']);

export function parseCli(argv) {
  const options = { mode: 'auto', subtitles: [] };
  const positional = [];
  for (let index = 0; index < argv.length; index++) {
    const value = argv[index];
    if (value === '--help' || value === '-h') options.help = true;
    else if (value === '--ui') options.ui = true;
    else if (value === '--no-open') options.open = false;
    else if (value.startsWith('--')) {
      const name = value.slice(2);
      if (!['host', 'mode', 'title', 'subtitle', 'root'].includes(name)) throw new Error(`Unknown option --${name}`);
      const argument = argv[++index];
      if (!argument || argument.startsWith('--')) throw new Error(`--${name} needs a value`);
      if (name === 'subtitle') options.subtitles.push(argument); else options[name] = argument;
    } else positional.push(value);
  }
  if (!['auto', 'direct', 'compat'].includes(options.mode)) throw new Error('--mode must be auto, direct or compat');
  if (positional.length > 1) throw new Error('Pass exactly one media file');
  options.file = positional[0];
  return options;
}

function ipv4Number(value) {
  const parts = String(value).split('.');
  if (parts.length !== 4 || parts.some(part => !/^\d{1,3}$/.test(part) || Number(part) > 255)) return null;
  return parts.reduce((result, part) => (result * 256 + Number(part)) >>> 0, 0);
}

function privateIpv4(value) {
  const number = ipv4Number(value);
  if (number === null) return false;
  return number >>> 24 === 10 || number >>> 20 === 0xac1 || number >>> 16 === 0xc0a8 || number >>> 16 === 0xa9fe;
}

export function lanAddress(target, interfaces = networkInterfaces()) {
  const targetNumber = ipv4Number(target);
  if (targetNumber === null || !privateIpv4(target)) throw new Error('Receiver host must be a private IPv4 address');
  for (const list of Object.values(interfaces)) for (const entry of list ?? []) {
    if (entry.family !== 'IPv4' || entry.internal) continue;
    const address = ipv4Number(entry.address), mask = ipv4Number(entry.netmask);
    if (address !== null && mask !== null && (address & mask) === (targetNumber & mask)) return entry.address;
  }
  throw new Error(`No active laptop network interface can reach ${target}`);
}

export function parseByteRange(value, size) {
  if (value === undefined) return undefined;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2]) || size <= 0) return null;
  let start, end;
  if (!match[1]) {
    const suffix = Number(match[2]);
    if (!Number.isSafeInteger(suffix) || suffix <= 0) return null;
    start = Math.max(0, size - suffix); end = size - 1;
  } else {
    start = Number(match[1]); end = match[2] ? Number(match[2]) : size - 1;
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start >= size || end < start) return null;
    end = Math.min(end, size - 1);
  }
  return { start, end };
}

function mediaType(file) {
  const extension = path.extname(file).toLowerCase();
  return extension === '.mkv' ? 'video/x-matroska' : extension === '.webm' ? 'video/webm' : 'video/mp4';
}

function subtitleType(file) {
  return ({ '.srt': 'application/x-subrip', '.vtt': 'text/vtt', '.ass': 'text/x-ssa', '.ssa': 'text/x-ssa', '.ttml': 'application/ttml+xml' })[path.extname(file).toLowerCase()];
}

export function directCompatible(probe, file) {
  const extension = path.extname(file).toLowerCase();
  if (!['.mp4', '.m4v', '.mov', '.mkv'].includes(extension)) return false;
  const streams = Array.isArray(probe?.streams) ? probe.streams : [];
  const video = streams.find(stream => stream.codec_type === 'video');
  const audios = streams.filter(stream => stream.codec_type === 'audio');
  return video?.codec_name === 'h264' && audios.every(stream => ['aac', 'mp3', 'ac3', 'eac3'].includes(stream.codec_name));
}

/** Pick the source's preferred audio track when the receiver needs a small AAC
 * companion. The original video remains byte-for-byte direct and seekable. */
export function audioCompanionStream(probe) {
  const audios = (Array.isArray(probe?.streams) ? probe.streams : [])
    .filter(stream => stream?.codec_type === 'audio' && Number.isInteger(stream.index));
  if (!audios.length || audios.some(stream => ['aac', 'mp3'].includes(stream.codec_name))) return null;
  return audios.find(stream => stream.disposition?.default) ?? audios[0];
}

function probeMedia(file) {
  const result = spawnSync('ffprobe', ['-v', 'error', '-show_entries',
    'stream=index,codec_type,codec_name:stream_tags=language,title:stream_disposition=default,forced,hearing_impaired:format=duration', '-of', 'json', file], {
    encoding: 'utf8', maxBuffer: 1024 * 1024,
  });
  if (result.error?.code === 'ENOENT') return null;
  if (result.status !== 0) throw new Error(`ffprobe could not read ${path.basename(file)}`);
  try { return JSON.parse(result.stdout); } catch { throw new Error('ffprobe returned invalid media details'); }
}

/** Text subtitle streams ffmpeg can copy/convert without OCR, ordered so a
 * complete dialogue track is selected before forced-only or SDH variants. */
export function embeddedSubtitleStreams(probe) {
  const supported = new Set(['subrip', 'ass', 'ssa', 'webvtt', 'mov_text']);
  return (Array.isArray(probe?.streams) ? probe.streams : [])
    .filter(stream => stream?.codec_type === 'subtitle' && Number.isInteger(stream.index) && supported.has(stream.codec_name))
    .map(stream => {
      const language = /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$/.test(stream.tags?.language ?? '') ? stream.tags.language : '';
      const label = String(stream.tags?.title ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 200)
        || `Embedded subtitle ${stream.index}`;
      const rank = stream.disposition?.default ? 0 : stream.disposition?.forced ? 2 : stream.disposition?.hearing_impaired ? 3 : 1;
      return { index: stream.index, language, label, rank };
    })
    .sort((left, right) => left.rank - right.rank || left.index - right.index);
}

function extractEmbeddedSubtitles(file, probe, directory, limit) {
  const extracted = [];
  for (const track of embeddedSubtitleStreams(probe).slice(0, limit)) {
    const output = path.join(directory, `embedded-${String(track.index).padStart(3, '0')}.srt`);
    const result = spawnSync('ffmpeg', ['-nostdin', '-hide_banner', '-loglevel', 'error', '-i', file,
      '-map', `0:${track.index}`, '-c:s', 'srt', output], { encoding: 'utf8', maxBuffer: 1024 * 1024 });
    if (result.status === 0 && existsSync(output) && statSync(output).size > 0) extracted.push({ file: output, language: track.language, label: track.label });
    else console.error(`Embedded subtitle track ${track.index} could not be prepared; playback will continue without that extracted track.`);
  }
  return extracted;
}

function createCompatibleAudio(file, probe) {
  const track = audioCompanionStream(probe);
  if (!track) return null;
  const cacheDirectory = path.join(ROOT, '.local/media-cache');
  mkdirSync(cacheDirectory, { recursive: true });
  const output = path.join(cacheDirectory, `${mediaId(file).slice('local:'.length)}.m4a`);
  if (existsSync(output) && statSync(output).size > 64 * 1024) return output;
  const partial = path.join(cacheDirectory, `${path.basename(output, '.m4a')}.${process.pid}.${randomBytes(4).toString('hex')}.partial.m4a`);
  const result = spawnSync('ffmpeg', ['-y', '-nostdin', '-hide_banner', '-loglevel', 'error', '-i', file,
    '-map', `0:${track.index}`, '-vn', '-sn', '-map_metadata', '-1',
    '-c:a', 'aac', '-b:a', '320k', '-ac', '2', '-ar', '48000', '-movflags', '+faststart', partial], {
    encoding: 'utf8', maxBuffer: 1024 * 1024,
  });
  if (result.error?.code === 'ENOENT') { rmSync(partial, { force: true }); throw new Error('This movie needs ffmpeg to make projector-compatible audio. Install ffmpeg and try again.'); }
  if (result.status !== 0 || !existsSync(partial) || statSync(partial).size === 0) {
    rmSync(partial, { force: true });
    throw new Error(`Could not prepare projector-compatible audio${result.stderr?.trim() ? `: ${result.stderr.trim()}` : ''}`);
  }
  renameSync(partial, output);
  return output;
}

function mediaId(file) {
  const info = statSync(file);
  return `local:${createHash('sha256').update(path.resolve(file)).update('\0').update(String(info.size)).update('\0').update(String(info.mtimeMs)).digest('hex').slice(0, 40)}`;
}

function parseSubtitle(value) {
  const match = /^(.*)@([A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*)$/.exec(value);
  const file = path.resolve(match ? match[1] : value);
  return { file, language: match?.[2] ?? languageFromName(file) };
}

function languageFromName(file) {
  const beforeExtension = path.basename(file, path.extname(file));
  const candidate = beforeExtension.split('.').at(-1);
  return /^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{1,8})*$/.test(candidate) ? candidate : '';
}

function subtitlesFor(file, supplied) {
  const selected = supplied.map(parseSubtitle);
  const directory = path.dirname(file), stem = path.basename(file, path.extname(file));
  for (const name of readdirSync(directory)) {
    const extension = path.extname(name).toLowerCase();
    if (!SUBTITLE_EXTENSIONS.has(extension) || !(name === `${stem}${extension}` || name.startsWith(`${stem}.`))) continue;
    const candidate = path.join(directory, name);
    if (!selected.some(item => item.file === candidate)) selected.push({ file: candidate, language: languageFromName(candidate) });
  }
  if (selected.length > 16) throw new Error('At most 16 subtitle files can be streamed');
  for (const item of selected) {
    if (!existsSync(item.file) || !statSync(item.file).isFile() || !subtitleType(item.file)) throw new Error(`Unsupported subtitle file: ${item.file}`);
    if (statSync(item.file).size > 8 * 1024 * 1024) throw new Error(`Subtitle file is too large: ${item.file}`);
  }
  return selected;
}

function remoteAddress(request) {
  return String(request.socket.remoteAddress ?? '').replace(/^::ffff:/, '');
}

function sendFile(request, response, file, type) {
  const size = statSync(file).size, range = parseByteRange(request.headers.range, size);
  response.setHeader('Accept-Ranges', 'bytes');
  response.setHeader('Content-Type', type);
  response.setHeader('Cache-Control', 'no-store');
  if (range === null) {
    response.writeHead(416, { 'Content-Range': `bytes */${size}`, 'Content-Length': '0' }); response.end(); return;
  }
  const start = range?.start ?? 0, end = range?.end ?? size - 1;
  if (range) response.writeHead(206, { 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': String(end - start + 1) });
  else response.writeHead(200, { 'Content-Length': String(size) });
  if (request.method === 'HEAD') { response.end(); return; }
  const stream = createReadStream(file, { start, end });
  stream.on('error', () => response.destroy());
  stream.pipe(response);
}

function createServer({ bindHost, receiverHost, secret, mediaFile, audioFile, direct, hlsDirectory, subtitles }) {
  const prefix = `/local/${secret}`;
  return http.createServer((request, response) => {
    try {
      if (remoteAddress(request) !== receiverHost || !['GET', 'HEAD'].includes(request.method)) {
        response.writeHead(404, { 'Content-Length': '0' }); response.end(); return;
      }
      const url = new URL(request.url, 'http://local.invalid');
      if (url.search || url.hash) { response.writeHead(404, { 'Content-Length': '0' }); response.end(); return; }
      if (direct && url.pathname === `${prefix}/video`) return sendFile(request, response, mediaFile, mediaType(mediaFile));
      if (audioFile && url.pathname === `${prefix}/audio`) return sendFile(request, response, audioFile, 'audio/mp4');
      const subtitle = new RegExp(`^${prefix}/subtitle/(0|[1-9][0-9]?)$`).exec(url.pathname);
      if (subtitle && Number(subtitle[1]) < subtitles.length) {
        const file = subtitles[Number(subtitle[1])].file;
        return sendFile(request, response, file, subtitleType(file));
      }
      if (!direct && url.pathname === `${prefix}/stream.m3u8`) return sendFile(request, response, path.join(hlsDirectory, 'stream.m3u8'), 'application/vnd.apple.mpegurl');
      const segment = !direct && new RegExp(`^${prefix}/(segment-[0-9]{6}\\.ts)$`).exec(url.pathname);
      if (segment) {
        const file = path.join(hlsDirectory, segment[1]);
        if (existsSync(file)) return sendFile(request, response, file, 'video/mp2t');
      }
    } catch { /* Return an opaque miss without leaking local paths. */ }
    response.writeHead(404, { 'Content-Length': '0' }); response.end();
  });
}

export function ffmpegArgs(file, directory) {
  return ['-nostdin', '-hide_banner', '-loglevel', 'warning', '-readrate', '1.5', '-i', file,
    '-map', '0:v:0', '-map', '0:a:0?', '-map_metadata', '-1',
    '-vf', "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2:flags=lanczos,format=yuv420p",
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '16', '-profile:v', 'high', '-level:v', '4.1',
    '-force_key_frames', 'expr:gte(t,n_forced*2)',
    '-c:a', 'aac', '-b:a', '320k', '-ac', '2', '-ar', '48000', '-max_muxing_queue_size', '2048',
    '-f', 'hls', '-hls_time', '2', '-hls_list_size', '0', '-hls_playlist_type', 'event',
    '-hls_flags', 'independent_segments+temp_file', '-hls_segment_filename', path.join(directory, 'segment-%06d.ts'),
    path.join(directory, 'stream.m3u8')];
}

async function startTranscode(file, directory) {
  const process = spawn('ffmpeg', ffmpegArgs(file, directory), { stdio: ['ignore', 'ignore', 'pipe'] });
  let details = '', spawnFailure = null;
  process.stderr.setEncoding('utf8');
  process.stderr.on('data', chunk => { details = (details + chunk).slice(-16_000); });
  process.once('error', error => { spawnFailure = error; });
  let exit = null;
  process.once('exit', (code, signal) => { exit = { code, signal }; });
  const playlist = path.join(directory, 'stream.m3u8'), deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    if (spawnFailure) throw new Error(spawnFailure.code === 'ENOENT' ? 'Compatibility mode needs ffmpeg. Install ffmpeg and try again.' : `Could not start ffmpeg: ${spawnFailure.message}`);
    if (existsSync(playlist) && readFileSync(playlist, 'utf8').includes('.ts')) return { process, failure: () => details };
    if (exit) throw new Error(`ffmpeg stopped before streaming (${exit.signal ?? exit.code})${details.trim() ? `: ${details.trim()}` : ''}`);
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  process.kill('SIGTERM');
  throw new Error('ffmpeg did not produce a playable stream within 90 seconds');
}

/** Spread the earliest previews over the whole film, then progressively fill
 * the 30-second gaps. That makes an arbitrary scrub useful within seconds. */
export function thumbnailPositions(durationSeconds, interval = 30) {
  const count = Math.max(0, Math.floor(Number(durationSeconds) / interval) + 1);
  if (!Number.isFinite(count) || count === 0) return [];
  const indices = [], seen = new Set(), queue = [[0, count - 1]];
  const add = index => { if (index >= 0 && index < count && !seen.has(index)) { seen.add(index); indices.push(index); } };
  add(0); add(count - 1);
  while (queue.length) {
    const [low, high] = queue.shift();
    if (low > high) continue;
    const middle = Math.floor((low + high) / 2); add(middle);
    if (low < middle) queue.push([low, middle - 1]);
    if (middle < high) queue.push([middle + 1, high]);
  }
  return indices.map(index => index * interval);
}

function startThumbnailUpload(file, directory, host, token, contentKey, durationSeconds) {
  const output = path.join(directory, 'thumbnails'); mkdirSync(output);
  const positions = thumbnailPositions(durationSeconds), running = new Set();
  let next = 0, stopped = false, reportedFailure = false;
  const makeFrame = position => new Promise((resolve, reject) => {
    const target = path.join(output, `frame-${String(position).padStart(6, '0')}.jpg`);
    const child = spawn('ffmpeg', ['-y', '-nostdin', '-hide_banner', '-loglevel', 'error', '-ss', String(position), '-i', file,
      '-map', '0:v:0', '-frames:v', '1', '-an', '-sn', '-vf', 'scale=256:-2:flags=lanczos', '-q:v', '4', '-threads', '1', target],
    { stdio: ['ignore', 'ignore', 'pipe'] });
    running.add(child); let details = '', settled = false;
    child.stderr.setEncoding('utf8'); child.stderr.on('data', chunk => { details = (details + chunk).slice(-2000); });
    const finish = (error, code) => {
      if (settled) return; settled = true; running.delete(child);
      if (error) reject(error); else if (code !== 0) reject(new Error(details.trim() || `ffmpeg exit ${code}`)); else resolve(target);
    };
    child.once('error', error => finish(error)); child.once('exit', code => finish(null, code));
  });
  const worker = async () => {
    while (!stopped && next < positions.length) {
      const position = positions[next++];
      try {
        const target = await makeFrame(position);
        if (stopped) return;
        const jpeg = readFileSync(target); rmSync(target, { force: true });
        if (jpeg.length <= 96 * 1024) await receiverRequest(host, 'thumbs.put', {
          contentKey, frames: [{ position, image: jpeg.toString('base64') }],
        }, token, 30_000);
      } catch (error) {
        if (!stopped && !reportedFailure) {
          reportedFailure = true;
          console.error(error.code === 'ENOENT' ? 'Thumbnail previews need ffmpeg; playback will continue without them.' : `Thumbnail sharing stopped: ${error.message}`);
        }
        stopped = true;
      }
    }
  };
  const task = Promise.all(Array.from({ length: Math.min(3, positions.length) }, worker));
  return { task, stop() { stopped = true; for (const child of running) if (child.exitCode === null) child.kill('SIGTERM'); } };
}

export function loadConfig() { return existsSync(CONFIG) ? JSON.parse(readFileSync(CONFIG, 'utf8')) : {}; }
export function saveConfig(config) {
  mkdirSync(path.dirname(CONFIG), { recursive: true });
  writeFileSync(CONFIG, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 }); chmodSync(CONFIG, 0o600);
}

export async function receiverRequest(host, method, params, token, timeoutMs = 15_000) {
  const response = await fetch(`http://${host}:${RECEIVER_PORT}/api/v1/${method}`, {
    method: method === 'info' ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: method === 'info' ? undefined : JSON.stringify(params ?? {}), signal: AbortSignal.timeout(timeoutMs),
  });
  const reply = await response.json();
  if (!reply.ok) { const error = new Error(reply.error?.code ?? `HTTP_${response.status}`); error.code = reply.error?.code; throw error; }
  return reply.result;
}

async function pairedToken(host, config) {
  if (config.host === host && config.token) {
    try { await receiverRequest(host, 'player.status', {}, config.token); return config.token; }
    catch (error) { if (error.code !== 'UNAUTHORIZED') throw error; }
  }
  const begun = await receiverRequest(host, 'pair.begin', { clientName: 'Laptop local-media streamer' });
  const prompt = readline.createInterface({ input: process.stdin, output: process.stdout });
  const code = (await prompt.question('Code shown on the projector: ')).replace(/\s/g, ''); prompt.close();
  const paired = await receiverRequest(host, 'pair.complete', { pairingId: begun.pairingId, code });
  saveConfig({ ...config, host, token: paired.token, clientId: paired.clientId });
  return paired.token;
}

function usage() {
  return `Usage: npm run stream:local -- <media-file> [options]

Options:
  --host <IP>              Projector IP (saved after the first pairing)
  --subtitle <file[@lang]> External subtitle; repeat for more languages
  --mode <auto|direct|compat>
                           Auto preserves compatible files and converts others
  --title <text>           Title shown on the projector
  --ui                     Open the laptop browser remote
  --root <directory>       Media folder shown by the browser remote
  --no-open                Print the remote URL without opening a browser

With no media file, the browser remote opens automatically. Auto mode needs
ffprobe and compatibility mode needs ffmpeg. Press Ctrl-C to stop.`;
}

async function main() {
  const options = parseCli(process.argv.slice(2));
  if (options.help) { console.log(usage()); return; }
  if (options.ui || !options.file) {
    const { startLocalMediaRemote } = await import('./local-media-remote.mjs');
    await startLocalMediaRemote({ root: options.root ?? (options.file ? path.dirname(path.resolve(options.file)) : process.cwd()), host: options.host, open: options.open !== false });
    return;
  }
  const file = path.resolve(options.file);
  if (!existsSync(file) || !statSync(file).isFile()) throw new Error(`Media file not found: ${file}`);
  if (options.mode === 'direct' && !['.mp4', '.m4v', '.mov', '.mkv', '.webm'].includes(path.extname(file).toLowerCase())) {
    throw new Error('Direct mode supports MP4, MOV, MKV and WebM containers; use --mode compat for other formats.');
  }
  const config = loadConfig(), host = options.host ?? config.host;
  if (!host) throw new Error('Pass --host <projector IP> the first time');
  const bindHost = lanAddress(host), subtitles = subtitlesFor(file, options.subtitles);
  const probe = probeMedia(file);
  if (!probe && options.mode === 'auto') throw new Error('Auto mode needs ffprobe. Install ffmpeg, or use --mode direct for a known-compatible file.');
  const direct = options.mode === 'direct' || options.mode === 'auto' && directCompatible(probe, file);
  const token = await pairedToken(host, config);
  const directory = mkdtempSync(path.join(tmpdir(), 'luma-local-media-'));
  if (probe) subtitles.push(...extractEmbeddedSubtitles(file, probe, directory, Math.max(0, 16 - subtitles.length)));
  const audioFile = direct && probe ? createCompatibleAudio(file, probe) : null;
  let transcoder = null, thumbnailJob = null, server = null, stopping = false;
  const stop = async (exitCode = 0) => {
    if (stopping) return; stopping = true;
    if (transcoder && transcoder.exitCode === null) transcoder.kill('SIGTERM');
    if (thumbnailJob) thumbnailJob.stop();
    if (server) await new Promise(resolve => server.close(resolve));
    if (thumbnailJob) await Promise.race([thumbnailJob.task, new Promise(resolve => setTimeout(resolve, 2000))]);
    rmSync(directory, { recursive: true, force: true });
    process.exitCode = exitCode;
  };
  try {
    if (!direct) {
      const started = await startTranscode(file, directory); transcoder = started.process;
      transcoder.once('exit', (code, signal) => {
        if (!stopping && code !== 0) { console.error(`ffmpeg stopped (${signal ?? code}): ${started.failure().trim()}`); void stop(1); }
      });
    }
    const secret = randomBytes(32).toString('hex');
    server = createServer({ bindHost, receiverHost: host, secret, mediaFile: file, audioFile, direct, hlsDirectory: directory, subtitles });
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, bindHost, resolve); });
    const base = `http://${bindHost}:${server.address().port}/local/${secret}`;
    const contentKey = mediaId(file);
    const media = {
      url: `${base}/${direct ? 'video' : 'stream.m3u8'}`,
      contentType: direct ? mediaType(file) : 'application/vnd.apple.mpegurl',
      title: options.title ?? path.basename(file), mediaId: contentKey,
      ...(audioFile ? { audioUrl: `${base}/audio` } : {}),
      subtitles: subtitles.map((item, index) => ({
        url: `${base}/subtitle/${index}`, mimeType: subtitleType(item.file),
        ...(item.language ? { language: item.language } : {}), label: item.label || path.basename(item.file), select: index === 0,
      })),
    };
    await receiverRequest(host, 'player.open', { media }, token, 30_000);
    thumbnailJob = startThumbnailUpload(file, directory, host, token, contentKey, Number(probe?.format?.duration));
    console.log(`${direct ? 'Streaming original video' : 'Streaming high-quality compatibility conversion'} to ${host}${audioFile ? ' with projector-compatible AAC audio' : ''}.`);
    console.log(`${subtitles.length} subtitle file${subtitles.length === 1 ? '' : 's'} available. Press Ctrl-C to stop.`);
    process.once('SIGINT', () => void stop()); process.once('SIGTERM', () => void stop());
    await new Promise(resolve => server.once('close', resolve));
  } catch (error) { await stop(1); throw error; }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
