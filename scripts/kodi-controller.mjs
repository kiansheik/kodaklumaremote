import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { isIP } from 'node:net';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const PUBLIC_SAMPLE_URL = 'https://storage.googleapis.com/cpe-sample-media/content/big_buck_bunny/prog/big_buck_bunny_prog.mp4';
const MAX_BODY = 16 * 1024;
const MAX_REPLY = 64 * 1024;
const ACTIONS = new Set(['connect', 'play', 'status', 'pause', 'resume', 'seek', 'stop']);

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

export async function performAction(payload, { fetchImpl = globalThis.fetch } = {}) {
  if (!payload || !ACTIONS.has(payload.action)) throw new ControllerError('Unknown controller action.');
  if (payload.action === 'seek' && (!Number.isFinite(payload.position) || payload.position < 0 || payload.position > 86399)) {
    throw new ControllerError('Enter a seek position between 0 and 86399 seconds.');
  }
  const receiver = validateReceiver(payload.receiver);
  const endpoint = `http://${receiver.host}:${receiver.port}/jsonrpc`;
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

  if (payload.action === 'connect') {
    if (await rpc('JSONRPC.Ping') !== 'pong') throw new ControllerError('The selected server did not identify as Kodi.', 502);
    const info = await rpc('Application.GetProperties', { properties: ['name', 'version'] });
    if (info?.name !== 'Kodi' || !Number.isInteger(info?.version?.major) || !Number.isInteger(info?.version?.minor)) {
      throw new ControllerError('The selected server did not identify as Kodi.', 502);
    }
    return { name: 'Kodi', version: `${info.version.major}.${info.version.minor}` };
  }
  if (payload.action === 'play') {
    await rpc('Player.Open', { item: { file: PUBLIC_SAMPLE_URL } });
    return { accepted: true };
  }
  const playerid = await videoPlayer();
  if (payload.action === 'status') return status(playerid);
  if (playerid === undefined) throw new ControllerError('Kodi has no active video. Start the public sample first.');
  if (payload.action === 'stop') {
    await rpc('Player.Stop', { playerid });
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
      send(200, { ok: true, result: await performAction(payload, { fetchImpl }) });
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
