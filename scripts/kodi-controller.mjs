import http from 'node:http';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { isIP } from 'node:net';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const PUBLIC_SAMPLE_URL = 'https://storage.googleapis.com/cpe-sample-media/content/big_buck_bunny/prog/big_buck_bunny_prog.mp4';
const MAX_BODY = 16 * 1024;
const MAX_REPLY = 64 * 1024;
const ACTIONS = new Set(['connect', 'play', 'play-source', 'status', 'pause', 'resume', 'seek', 'stop', 'subtitles', 'set-subtitle', 'add-subtitle', 'label-subtitles']);
const MEDIA_TYPES = new Set(['video/mp4', 'video/webm', 'application/vnd.apple.mpegurl', 'application/x-mpegurl', 'application/dash+xml']);

class ControllerError extends Error {
  constructor(message, status = 400) {
    super(message);
    this.status = status;
  }
}

export function validateReceiver(receiver) {
  const { host, port, username, password } = receiver || {};
  const octets = typeof host === 'string' ? host.split('.').map(Number) : [];
  if (isIP(typeof host === 'string' ? host : '') !== 4 || !(
    octets[0] === 10 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) ||
    (octets[0] === 192 && octets[1] === 168)
  )) throw new ControllerError('Enter the projector’s private IPv4 address.');
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new ControllerError('Enter Kodi’s HTTP port (usually 8080).');
  }
  if (typeof username !== 'string' || !username || username.length > 256 || /[:\r\n]/.test(username) ||
      typeof password !== 'string' || !password || password.length > 256 || /[\r\n]/.test(password)) {
    throw new ControllerError('Enter the username and password configured in Kodi.');
  }
  return { host, port, username, password };
}

function publicMediaHost(hostname) {
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (isIP(host) === 4) {
    const [a, b, c] = host.split('.').map(Number);
    return !(a === 0 || a === 10 || a === 127 || a >= 224 ||
      (a === 100 && b >= 64 && b <= 127) || (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && ((b === 0 && (c === 0 || c === 2)) || b === 168 || (b === 88 && c === 99))) ||
      (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100))) ||
      (a === 203 && b === 0 && c === 113));
  }
  if (isIP(host) === 6) {
    // Global unicast only; exclude documentation and special-use 2001::/23.
    const [first, second = '0'] = host.split(':');
    return parseInt(first, 16) >= 0x2000 && parseInt(first, 16) < 0x4000 &&
      ![0x2002, 0x3fff].includes(parseInt(first, 16)) &&
      !(parseInt(first, 16) === 0x2001 && (parseInt(second || '0', 16) < 0x200 || parseInt(second, 16) === 0xdb8));
  }
  const domain = host.replace(/\.$/, '');
  return domain.length <= 253 && domain.includes('.') && !/^[\d.]+$/.test(domain) &&
    !/(^|\.)(localhost|local|localdomain|lan|home|internal|arpa)$/.test(domain) &&
    domain.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label));
}

function validatePublicURL(value, message) {
  const invalid = () => new ControllerError(message);
  if (typeof value !== 'string' || !value || value.length > 8192 || /[\u0000-\u0020\u007f|\\#]/.test(value)) throw invalid();
  let url;
  try { url = new URL(value); } catch { throw invalid(); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || !publicMediaHost(url.hostname)) throw invalid();
  return url.href;
}

/** Validate a page-declared source without retrieving it or forwarding browser state. */
export function validateMedia(media) {
  const message = 'Enter a supported public HTTP media source.';
  const url = validatePublicURL(media?.url, message);
  const contentType = typeof media.contentType === 'string' ? media.contentType.split(';', 1)[0].trim().toLowerCase() : '';
  if (!MEDIA_TYPES.has(contentType)) throw new ControllerError(message);
  return { url, contentType };
}

function subtitleText(value, limit) {
  return typeof value === 'string' ? value.replace(/[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/g, '')
    .replace(/(?:[a-z][a-z\d+.-]*:\/\/|www\.)\S+/gi, '').trim().slice(0, limit) : '';
}

function validateSubtitleLabels(payload) {
  const invalid = () => new ControllerError('Provide valid subtitle labels for the current video.');
  if (typeof payload.mediaKey !== 'string' || !/^[a-f0-9]{64}$/.test(payload.mediaKey) || !Array.isArray(payload.tracks) || !payload.tracks.length || payload.tracks.length > 128) throw invalid();
  const labels = new Map();
  for (const track of payload.tracks) {
    const name = track?.name ?? track?.label ?? '';
    const language = track?.language ?? '';
    if (!Number.isInteger(track?.index) || track.index < 0 || track.index > 1024 || labels.has(track.index) ||
        typeof name !== 'string' || name.length > 1024 || typeof language !== 'string' || language.length > 128) throw invalid();
    const normalized = { name: subtitleText(name, 200), language: subtitleText(language, 64) };
    if (!normalized.name && !normalized.language) throw invalid();
    labels.set(track.index, normalized);
  }
  return labels;
}

function seconds(time) {
  if (!time || !['hours', 'minutes', 'seconds'].every(key => Number.isInteger(time[key]) && time[key] >= 0)) {
    throw new ControllerError('Kodi returned an unexpected playback status.', 502);
  }
  return time.hours * 3600 + time.minutes * 60 + time.seconds;
}

async function readJson(response) {
  if (Number(response.headers?.get('content-length')) > MAX_REPLY) {
    throw new ControllerError('Kodi returned an unexpected response.', 502);
  }
  const reader = response.body?.getReader();
  if (!reader) throw new ControllerError('Kodi returned an unexpected response.', 502);
  let size = 0;
  const chunks = [];
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_REPLY) {
        await reader.cancel();
        throw new ControllerError('Kodi returned an unexpected response.', 502);
      }
      chunks.push(Buffer.from(value));
    }
    return JSON.parse(Buffer.concat(chunks).toString('utf8'));
  } catch (error) {
    if (error instanceof ControllerError) throw error;
    throw new ControllerError('Kodi returned an unexpected response.', 502);
  } finally {
    reader.releaseLock();
  }
}

export async function performAction(payload, { fetchImpl = globalThis.fetch, subtitleLabels = new Map() } = {}) {
  if (!payload || !ACTIONS.has(payload.action)) throw new ControllerError('Unknown controller action.');
  const media = payload.action === 'play-source' ? validateMedia(payload.media) : undefined;
  const subtitleURL = payload.action === 'add-subtitle'
    ? validatePublicURL(payload.subtitleUrl, 'Enter a public HTTP subtitle link.') : undefined;
  const labels = payload.action === 'label-subtitles' ? validateSubtitleLabels(payload) : undefined;
  if (payload.action === 'set-subtitle' && payload.subtitle !== 'off' &&
      (!Number.isInteger(payload.subtitle) || payload.subtitle < 0 || payload.subtitle > 1024)) {
    throw new ControllerError('Choose an available subtitle track or turn subtitles off.');
  }
  if (payload.action === 'seek' && (!Number.isFinite(payload.position) || payload.position < 0 || payload.position > 86399)) {
    throw new ControllerError('Enter a seek position between 0 and 86399 seconds.');
  }
  const receiver = validateReceiver(payload.receiver);
  const endpoint = `http://${receiver.host}:${receiver.port}/jsonrpc`;
  const labelPrefix = `${receiver.host}:${receiver.port}:`;
  function clearSubtitleLabels() {
    for (const key of subtitleLabels.keys()) if (key.startsWith(labelPrefix)) subtitleLabels.delete(key);
  }
  const authorization = `Basic ${Buffer.from(`${receiver.username}:${receiver.password}`, 'utf8').toString('base64')}`;
  let nextId = 0;
  async function rpc(method, params = {}) {
    const id = ++nextId;
    let response;
    try {
      response = await fetchImpl(endpoint, {
        method: 'POST', redirect: 'error', signal: AbortSignal.timeout(5000),
        headers: { 'Content-Type': 'application/json', Authorization: authorization },
        body: JSON.stringify({ jsonrpc: '2.0', id, method, params })
      });
    } catch {
      throw new ControllerError('Could not reach Kodi. Check its address, HTTP control and home network.', 502);
    }
    if (response.status === 401 || response.status === 403) {
      await response.body?.cancel();
      throw new ControllerError('Kodi rejected the username or password.', 502);
    }
    if (!response.ok) {
      await response.body?.cancel();
      throw new ControllerError('Kodi’s HTTP server did not accept the request.', 502);
    }
    const reply = await readJson(response);
    if (reply?.jsonrpc !== '2.0' || reply.id !== id || !Object.hasOwn(reply, 'result') || Object.hasOwn(reply, 'error')) {
      throw new ControllerError('Kodi did not accept this control command.', 502);
    }
    return reply.result;
  }

  async function videoPlayer() {
    const players = await rpc('Player.GetActivePlayers');
    if (!Array.isArray(players)) throw new ControllerError('Kodi returned an unexpected playback status.', 502);
    return players.find(player => player?.type === 'video' && Number.isInteger(player.playerid) && player.playerid >= 0)?.playerid;
  }
  async function currentMediaKey(playerid) {
    const item = await rpc('Player.GetItem', { playerid, properties: ['file'] });
    if (typeof item?.item?.file !== 'string' || !item.item.file || item.item.file.length > 8192) {
      throw new ControllerError('Kodi could not identify the current video.', 502);
    }
    return createHash('sha256').update(item.item.file).digest('hex');
  }
  async function status(playerid) {
    if (playerid === undefined) return { active: false, playing: false };
    const properties = await rpc('Player.GetProperties', { playerid, properties: ['speed', 'time', 'totaltime', 'canseek'] });
    if (!properties || typeof properties.speed !== 'number' || !Number.isFinite(properties.speed) || typeof properties.canseek !== 'boolean') {
      throw new ControllerError('Kodi returned an unexpected playback status.', 502);
    }
    return {
      active: true, playing: properties.speed !== 0,
      positionSeconds: seconds(properties.time), durationSeconds: seconds(properties.totaltime),
      canSeek: properties.canseek
    };
  }
  async function subtitles(playerid) {
    if (playerid === undefined) return { active: false, enabled: false, currentIndex: null, tracks: [] };
    const registered = subtitleLabels.size ? subtitleLabels.get(labelPrefix + await currentMediaKey(playerid)) : undefined;
    const properties = await rpc('Player.GetProperties', { playerid, properties: ['subtitles', 'currentsubtitle', 'subtitleenabled'] });
    if (!Array.isArray(properties?.subtitles) || typeof properties.subtitleenabled !== 'boolean') {
      throw new ControllerError('Kodi returned an unexpected subtitle status.', 502);
    }
    const tracks = [];
    const indices = new Set();
    for (const track of properties.subtitles) {
      if (tracks.length === 128) break;
      if (!Number.isInteger(track?.index) || track.index < 0 || track.index > 1024 || indices.has(track.index)) continue;
      indices.add(track.index);
      const normalized = { index: track.index, name: subtitleText(track.name, 200), language: subtitleText(track.language, 64) };
      const label = registered?.get(track.index);
      if (label) {
        normalized.name = label.name || normalized.name;
        normalized.language = label.language || normalized.language;
      }
      for (const flag of ['forced', 'impaired']) {
        const value = track[flag] ?? track[`is${flag}`];
        if (typeof value === 'boolean') normalized[flag] = value;
      }
      tracks.push(normalized);
    }
    return {
      active: true, enabled: properties.subtitleenabled,
      currentIndex: indices.has(properties.currentsubtitle?.index) ? properties.currentsubtitle.index : null,
      tracks
    };
  }

  if (payload.action === 'connect') {
    if (await rpc('JSONRPC.Ping') !== 'pong') throw new ControllerError('The selected server did not identify as Kodi.', 502);
    const info = await rpc('Application.GetProperties', { properties: ['name', 'version'] });
    if (info?.name !== 'Kodi' || !Number.isInteger(info?.version?.major) || !Number.isInteger(info?.version?.minor)) {
      throw new ControllerError('The selected server did not identify as Kodi.', 502);
    }
    return { name: 'Kodi', version: `${info.version.major}.${info.version.minor}` };
  }
  if (payload.action === 'play' || payload.action === 'play-source') {
    await rpc('Player.Open', { item: { file: media?.url || PUBLIC_SAMPLE_URL } });
    clearSubtitleLabels();
    return { accepted: true };
  }
  const playerid = await videoPlayer();
  if (payload.action === 'status') return status(playerid);
  if (payload.action === 'subtitles') return subtitles(playerid);
  if (playerid === undefined) throw new ControllerError('Kodi has no active video. Start a video first.');
  if (payload.action === 'label-subtitles') {
    if (await currentMediaKey(playerid) !== payload.mediaKey) {
      throw new ControllerError('Subtitle labels do not match the video currently playing.');
    }
    const key = labelPrefix + payload.mediaKey;
    subtitleLabels.delete(key);
    subtitleLabels.set(key, labels);
    while (subtitleLabels.size > 8) subtitleLabels.delete(subtitleLabels.keys().next().value);
    return subtitles(playerid);
  }
  if (payload.action === 'add-subtitle') {
    await rpc('Player.AddSubtitle', { playerid, subtitle: subtitleURL });
    return { accepted: true };
  }
  if (payload.action === 'set-subtitle') {
    const available = await subtitles(playerid);
    if (payload.subtitle !== 'off' && !available.tracks.some(track => track.index === payload.subtitle)) {
      throw new ControllerError('Choose an available subtitle track or turn subtitles off.');
    }
    await rpc('Player.SetSubtitle', {
      playerid, subtitle: payload.subtitle, ...(payload.subtitle === 'off' ? {} : { enable: true })
    });
    return subtitles(playerid);
  }
  if (payload.action === 'stop') {
    await rpc('Player.Stop', { playerid });
    clearSubtitleLabels();
    return { active: false, playing: false };
  }
  if (payload.action === 'pause' || payload.action === 'resume') {
    await rpc('Player.PlayPause', { playerid, play: payload.action === 'resume' });
  } else if (payload.action === 'seek') {
    const current = await status(playerid);
    if (!current.canSeek) throw new ControllerError('Kodi reports that this video cannot seek.');
    const position = Math.floor(Math.min(payload.position, current.durationSeconds || payload.position));
    await rpc('Player.Seek', { playerid, value: { time: {
      hours: Math.floor(position / 3600), minutes: Math.floor(position % 3600 / 60), seconds: position % 60, milliseconds: 0
    } } });
  }
  return status(playerid);
}

export function createControllerServer({ fetchImpl = globalThis.fetch } = {}) {
  const subtitleLabels = new Map();
  const server = http.createServer(async (request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    response.setHeader('Referrer-Policy', 'no-referrer');
    response.setHeader('Content-Security-Policy', "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
    function send(status, body) {
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
      response.end(JSON.stringify(body));
    }
    try {
      const host = `127.0.0.1:${server.address()?.port}`;
      if (request.headers.host !== host) throw new ControllerError('Open the controller using its 127.0.0.1 address.', 403);
      if (request.method === 'GET' && request.url === '/') {
        const html = await readFile(new URL('../src/kodi/controller.html', import.meta.url));
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        response.end(html);
        return;
      }
      if (request.method !== 'POST' || request.url !== '/api/kodi') throw new ControllerError('Not found.', 404);
      if (request.headers.origin !== `http://${host}`) throw new ControllerError('Use the local controller page for commands.', 403);
      if (request.headers['content-type']?.split(';')[0].trim() !== 'application/json') {
        throw new ControllerError('Expected a JSON controller command.', 415);
      }
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > MAX_BODY) throw new ControllerError('Controller command is too large.', 413);
        chunks.push(chunk);
      }
      let payload;
      try { payload = JSON.parse(Buffer.concat(chunks).toString('utf8')); }
      catch { throw new ControllerError('Invalid controller command.'); }
      send(200, { ok: true, result: await performAction(payload, { fetchImpl, subtitleLabels }) });
    } catch (error) {
      if (!response.headersSent) send(error instanceof ControllerError ? error.status : 500, {
        ok: false, error: error instanceof ControllerError ? error.message : 'The controller could not complete the request.'
      });
      else response.end();
    }
  });
  server.requestTimeout = 15000;
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  const port = Number(process.env.KODI_CONTROLLER_PORT || 4175);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    console.error('KODI_CONTROLLER_PORT must be between 1 and 65535.');
    process.exitCode = 1;
  } else {
    const server = createControllerServer();
    server.on('error', error => {
      console.error(error.code === 'EADDRINUSE'
        ? 'Could not start the local Kodi controller: its port is already in use.'
        : 'Could not start the local Kodi controller: local network listening is unavailable.');
      process.exitCode = 1;
    });
    server.listen(port, '127.0.0.1', () => console.log(`Kodi controller: http://127.0.0.1:${port}`));
  }
}
