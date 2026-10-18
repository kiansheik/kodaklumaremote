import { createConnection, isIP } from 'node:net';

const CONNECT_TIMEOUT_MS = 2500;
const REPLY_TIMEOUT_MS = 2500;
const TEXT_IDLE_MS = 100;
const MAX_TEXT_REPLY = 16 * 1024;
const MAX_APP_FRAME = 16 * 1024 * 1024;
const MAX_APPS = 200;
const MAX_PENDING = 32;
const PACKAGE_NAME = /^[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+$/;
const UTF8 = new TextDecoder('utf-8', { fatal: true });
const KEYS = Object.freeze({
  up: 19, down: 20, left: 21, right: 22, enter: 23, back: 4, home: 3,
  menu: 82, 'volume-up': 24, 'volume-down': 25, mute: 164,
});
const MESSAGES = Object.freeze({
  CONNECTION_FAILED: 'Could not connect to the projector. Check its network connection.',
  TIMEOUT: 'The projector did not reply in time. Reconnect and try again.',
  INVALID_RESPONSE: 'The projector returned an unsupported response. Reconnect and try again.',
  NOT_CONNECTED: 'Connect to the projector first.',
  PAIRING_REQUIRED: 'The projector requires pairing. Password pairing is not supported by this proof of concept.',
  PAIRING_UNKNOWN: 'The projector pairing requirement could not be verified. No control commands were sent.',
  INVALID_INPUT: 'The requested projector command is not valid.',
  INVALID_TEXT: 'Send a single line of up to 1,000 characters.',
  APP_NOT_AVAILABLE: 'That app was not found in the projector’s current app list.',
  QUEUE_FULL: 'Too many projector commands are pending. Wait and try again.',
});

export class LumaProtocolError extends Error {
  constructor(code) {
    const safeCode = Object.hasOwn(MESSAGES, code) ? code : 'INVALID_RESPONSE';
    super(MESSAGES[safeCode]);
    this.name = 'LumaProtocolError';
    this.code = safeCode;
  }
}

const error = code => new LumaProtocolError(code);

function decodeText(bytes) {
  let text;
  try { text = UTF8.decode(bytes); } catch { throw error('INVALID_RESPONSE'); }
  if (/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(text)) throw error('INVALID_RESPONSE');
  return text.trim();
}

function displayName(value) {
  if (typeof value !== 'string') throw error('INVALID_RESPONSE');
  const clean = value.replace(/[\u0000-\u001f\u007f]/gu, ' ').trim();
  if (!clean || clean.length > 200) throw error('INVALID_RESPONSE');
  return clean;
}

function featureName(text) {
  if (!text.startsWith('server_features:')) throw error('INVALID_RESPONSE');
  const payload = text.slice('server_features:'.length).trim();
  if (!payload) throw error('INVALID_RESPONSE');
  const fields = [...payload.matchAll(/(?:^|\s)([A-Za-z][A-Za-z0-9_]*)=/gu)];
  const values = new Map(fields.map((field, index) => [
    field[1], payload.slice(field.index + field[0].length, fields[index + 1]?.index).trim(),
  ]));
  return displayName(values.get('ap') || values.get('m') || 'EShare projector');
}

function parseApps(bytes) {
  if (bytes.length < 4 || bytes.length > MAX_APP_FRAME || bytes.readUInt32LE(0) !== bytes.length) {
    throw error('INVALID_RESPONSE');
  }
  const apps = [];
  const packages = new Set();
  let offset = 4;
  const field = () => {
    if (offset + 4 > bytes.length) throw error('INVALID_RESPONSE');
    const length = bytes.readUInt32LE(offset);
    offset += 4;
    if (length > bytes.length - offset) throw error('INVALID_RESPONSE');
    const value = bytes.subarray(offset, offset + length);
    offset += length;
    return value;
  };
  while (offset < bytes.length) {
    if (apps.length >= MAX_APPS) throw error('INVALID_RESPONSE');
    const nameBytes = field();
    const packageBytes = field();
    field(); // Version text, not used by the remote.
    field(); // Version-code bytes, not used by the remote.
    field(); // Icon bytes stay opaque and are never served to the browser.
    if (nameBytes.length > 1024 || packageBytes.length > 255) throw error('INVALID_RESPONSE');
    const label = displayName(decodeText(nameBytes));
    let packageName;
    try { packageName = UTF8.decode(packageBytes); } catch { throw error('INVALID_RESPONSE'); }
    if (!PACKAGE_NAME.test(packageName) || packages.has(packageName)) throw error('INVALID_RESPONSE');
    packages.add(packageName);
    apps.push({ packageName, label });
  }
  return apps;
}

function parseVolume(text) {
  if (/-\s*\d/u.test(text)) return undefined;
  const values = text.match(/\d+/gu);
  if (!values || values.length !== 2) return undefined;
  const [current, max] = values.map(Number);
  if (!Number.isSafeInteger(current) || !Number.isSafeInteger(max) || max < 1 || max > 1000000 || current > max) return undefined;
  return { current, max };
}

/** The host HTTP server owns the private-network allowlist. Numeric IPv4 here
 * also prevents a direct caller from triggering a DNS lookup. No media server,
 * file sharing, authentication advertisement, or password guessing is used. */
export class LumaConnection {
  constructor({ host, port = 8121, connectImpl = createConnection } = {}) {
    if (typeof host !== 'string' || isIP(host) !== 4 || !Number.isInteger(port) || port < 1 || port > 65535 || typeof connectImpl !== 'function') {
      throw error('INVALID_INPUT');
    }
    this.host = host;
    this.port = port;
    this._connectImpl = connectImpl;
    this._socket = null;
    this._connected = false;
    this._device = null;
    this._volume = undefined;
    this._apps = null;
    this._reader = null;
    this._opening = null;
    this._connecting = null;
    this._tail = Promise.resolve();
    this._pending = 0;
  }

  _enqueue(operation) {
    if (this._pending >= MAX_PENDING) return Promise.reject(error('QUEUE_FULL'));
    this._pending += 1;
    const result = this._tail.then(operation);
    this._tail = result.catch(() => {}).finally(() => { this._pending -= 1; });
    return result;
  }

  _snapshot() {
    return {
      connected: true,
      device: { ...this._device },
      ...(this._volume ? { volume: { ...this._volume } } : {}),
    };
  }

  _assertConnected() {
    if (!this._connected || !this._socket || this._socket.destroyed) throw error('NOT_CONNECTED');
  }

  _disconnect(code = 'CONNECTION_FAILED') {
    this._connected = false;
    this._apps = null;
    this._volume = undefined;
    this._opening?.(error(code));
    this._reader?.fail(error(code));
    const socket = this._socket;
    this._socket = null;
    socket?.destroy();
  }

  async _open() {
    await new Promise((resolve, reject) => {
      let socket;
      let done = false;
      const timer = setTimeout(() => finish(error('TIMEOUT')), CONNECT_TIMEOUT_MS);
      const finish = failure => {
        if (done) return;
        done = true;
        clearTimeout(timer);
        this._opening = null;
        if (failure) reject(failure); else resolve();
      };
      this._opening = finish;
      try {
        socket = this._connectImpl({ host: this.host, port: this.port });
        this._socket = socket;
        socket.setNoDelay(true);
        socket.on('data', bytes => { if (this._socket === socket) this._reader?.data(bytes); });
        socket.on('error', () => { if (this._socket === socket) this._disconnect('CONNECTION_FAILED'); });
        socket.on('end', () => {
          if (this._socket !== socket) return;
          this._reader?.end();
          this._disconnect('CONNECTION_FAILED');
        });
        socket.on('close', () => { if (this._socket === socket) this._disconnect('CONNECTION_FAILED'); });
        socket.once('connect', () => finish());
      } catch { finish(error('CONNECTION_FAILED')); }
    });
  }

  _write(command) {
    return new Promise((resolve, reject) => {
      const socket = this._socket;
      if (!socket || socket.destroyed) { reject(error('NOT_CONNECTED')); return; }
      const timer = setTimeout(() => {
        this._disconnect('TIMEOUT');
        reject(error('TIMEOUT'));
      }, REPLY_TIMEOUT_MS);
      try {
        socket.write(command, 'utf8', failure => {
          clearTimeout(timer);
          if (failure) { this._disconnect(); reject(error('CONNECTION_FAILED')); }
          else resolve();
        });
      } catch {
        clearTimeout(timer);
        this._disconnect();
        reject(error('CONNECTION_FAILED'));
      }
    });
  }

  _request(command, binary = false) {
    return new Promise((resolve, reject) => {
      const chunks = [];
      let length = 0;
      let expected;
      let idle;
      let settled = false;
      const deadline = setTimeout(() => fail(error('TIMEOUT')), REPLY_TIMEOUT_MS);
      const finish = value => {
        if (settled) return;
        settled = true;
        clearTimeout(deadline);
        clearTimeout(idle);
        this._reader = null;
        resolve(value);
      };
      const fail = failure => {
        if (settled) return;
        settled = true;
        clearTimeout(deadline);
        clearTimeout(idle);
        this._reader = null;
        this._disconnect(failure.code);
        reject(failure);
      };
      const completeText = () => {
        try { finish(decodeText(Buffer.concat(chunks, length))); }
        catch { fail(error('INVALID_RESPONSE')); }
      };
      this._reader = {
        fail,
        end: () => {
          if (!binary && length) completeText();
          else fail(error('INVALID_RESPONSE'));
        },
        data: bytes => {
          if (settled) return;
          const limit = binary ? MAX_APP_FRAME : MAX_TEXT_REPLY;
          if (!Buffer.isBuffer(bytes) || bytes.length > limit - length) { fail(error('INVALID_RESPONSE')); return; }
          chunks.push(bytes);
          length += bytes.length;
          if (binary) {
            if (expected === undefined && length >= 4) {
              expected = Buffer.concat(chunks, length).readUInt32LE(0);
              if (expected < 4 || expected > MAX_APP_FRAME) { fail(error('INVALID_RESPONSE')); return; }
            }
            if (expected !== undefined && length > expected) { fail(error('INVALID_RESPONSE')); return; }
            if (expected === length) finish(Buffer.concat(chunks, length));
          } else {
            clearTimeout(idle);
            // The APK uses a single read for unlength-prefixed text replies.
            // Wait for a short quiet interval to preserve ordinary fragmentation.
            idle = setTimeout(completeText, TEXT_IDLE_MS);
          }
        },
      };
      this._write(command).catch(fail);
    });
  }

  connect() {
    if (this._connected) return Promise.resolve(this._snapshot());
    if (this._connecting) return this._connecting;
    this._connecting = this._enqueue(async () => {
      try {
        await this._open();
        const features = await this._request('getFeatures\r\nLUMA Remote\r\n\r\n');
        const name = featureName(features);
        let pairing;
        try { pairing = await this._request('GETPASSWORDCONFIG\r\neshare\r\n\r\n'); }
        catch { throw error('PAIRING_UNKNOWN'); }
        if (!pairing) throw error('PAIRING_UNKNOWN');
        if (/password/iu.test(pairing)) throw error('PAIRING_REQUIRED');
        if (!/^configed\s+none$/iu.test(pairing.trim())) throw error('PAIRING_UNKNOWN');
        // getServerInfo is optional in the original app and is unsupported on
        // the tested firmware. Features identify the protocol/model without it.
        this._device = { name };
        if (!this._socket || this._socket.destroyed) throw error('CONNECTION_FAILED');
        this._connected = true;
        return this._snapshot();
      } catch (failure) {
        const safe = failure instanceof LumaProtocolError ? failure : error('CONNECTION_FAILED');
        this._disconnect(safe.code);
        throw safe;
      }
    }).finally(() => { this._connecting = null; });
    return this._connecting;
  }

  async _getApps() {
    this._assertConnected();
    this._apps = null;
    try {
      const apps = parseApps(await this._request('GETAPPINFOSEVNET\r\n1\r\n\r\n', true));
      this._apps = new Map(apps.map(app => [app.packageName, app]));
      return apps;
    } catch (failure) {
      this._disconnect();
      throw failure instanceof LumaProtocolError ? failure : error('INVALID_RESPONSE');
    }
  }

  getApps() { return this._enqueue(() => this._getApps()); }

  key(name) {
    if (typeof name !== 'string' || !Object.hasOwn(KEYS, name)) return Promise.reject(error('INVALID_INPUT'));
    return this._enqueue(async () => {
      this._assertConnected();
      await this._write(`KEYEVENT\r\n${KEYS[name]}\r\n\r\n`);
      return { accepted: true };
    });
  }

  sendText(text) {
    if (typeof text !== 'string' || !text || text.length > 1000 || Buffer.byteLength(text, 'utf8') > 4096 || /[\u0000-\u001f\u007f]/u.test(text)) {
      return Promise.reject(error('INVALID_TEXT'));
    }
    return this._enqueue(async () => {
      this._assertConnected();
      await this._write(`CONTENT\r\n1\r\n0 ${text}\r\n\r\n`);
      return { accepted: true };
    });
  }

  launchApp(packageName) {
    if (typeof packageName !== 'string' || packageName.length > 255 || !PACKAGE_NAME.test(packageName)) {
      return Promise.reject(error('INVALID_INPUT'));
    }
    return this._enqueue(async () => {
      this._assertConnected();
      if (this._apps === null) await this._getApps();
      if (!this._apps.has(packageName)) throw error('APP_NOT_AVAILABLE');
      await this._write(`STARTAPPEVNET\r\n${packageName}\r\n\r\n`);
      return { accepted: true };
    });
  }

  status() {
    return this._enqueue(async () => {
      this._assertConnected();
      this._volume = parseVolume(await this._request('MediaControl\r\ngetVolume\r\n\r\n'));
      return this._snapshot();
    });
  }

  close() { this._disconnect('NOT_CONNECTED'); }
}
