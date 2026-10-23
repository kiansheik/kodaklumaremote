#!/usr/bin/env node
// Developer client for the LUMA Receiver APK (android-receiver/).
//
//   node scripts/receiver-dev.mjs info [--host IP]
//   node scripts/receiver-dev.mjs pair [--host IP]            (prompts for the on-screen code)
//   node scripts/receiver-dev.mjs pair-begin [--host IP]       (non-interactive: prints pairingId)
//   node scripts/receiver-dev.mjs pair-complete <pairingId> <code> [--host IP]
//   node scripts/receiver-dev.mjs call <method> [json]
//   node scripts/receiver-dev.mjs open <url> [--sub URL[@lang]] [--title T] [--content JSON]
//   node scripts/receiver-dev.mjs status|pause|resume|stop|subtitles
//   node scripts/receiver-dev.mjs seek <seconds>
//   node scripts/receiver-dev.mjs deploy [--no-build] [--confirm KEYS] [--apk PATH]
//   node scripts/receiver-dev.mjs keys right,enter        (EShare key presses, names or keycodes)
//
// The paired token lives in .local/receiver-dev.json (mode 0600, Git-ignored).
// `deploy` builds the signed release APK, serves it once on this Mac's LAN
// address, asks the receiver to install it, then presses the Android install
// confirmation over EShare (default keys: see DEFAULT_CONFIRM) and waits for
// the updated receiver to come back.
import http from 'node:http';
import { createHash, randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, chmodSync, mkdirSync, statSync, createReadStream } from 'node:fs';
import { networkInterfaces } from 'node:os';
import path from 'node:path';
import readline from 'node:readline/promises';
import { fileURLToPath } from 'node:url';
import { LumaConnection } from './luma-protocol.mjs';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const CONFIG = path.join(ROOT, '.local/receiver-dev.json');
const PORT = 4191;
// Kodak LUMA (Android 9) PackageInstaller: Down reaches the button row on Cancel; Install is to its right.
const DEFAULT_CONFIRM = 'down,right,enter';
const KEYCODES = { up: 19, down: 20, left: 21, right: 22, enter: 23, back: 4, home: 3, tab: 61 };

function args(argv) {
  const positional = [], flags = {};
  for (let i = 0; i < argv.length; i++) {
    if (argv[i].startsWith('--')) {
      const name = argv[i].slice(2);
      if (i + 1 < argv.length && !argv[i + 1].startsWith('--')) flags[name] = argv[++i]; else flags[name] = true;
    } else positional.push(argv[i]);
  }
  return { positional, flags };
}

function loadConfig() { return existsSync(CONFIG) ? JSON.parse(readFileSync(CONFIG, 'utf8')) : {}; }
function saveConfig(config) {
  mkdirSync(path.dirname(CONFIG), { recursive: true });
  writeFileSync(CONFIG, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 });
  chmodSync(CONFIG, 0o600);
}

async function request(host, method, params, { token, timeoutMs = 10000 } = {}) {
  const get = method === 'info';
  const response = await fetch(`http://${host}:${PORT}/api/v1/${method}`, {
    method: get ? 'GET' : 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: get ? undefined : JSON.stringify(params ?? {}),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const reply = await response.json();
  if (!reply.ok) { const error = new Error(reply.error?.code ?? `HTTP_${response.status}`); error.code = reply.error?.code; throw error; }
  return reply.result;
}

function hostFrom(flags, config) {
  const host = flags.host ?? config.host;
  if (!host) throw new Error('No receiver host. Pass --host <projector IP> (saved after pairing).');
  return host;
}

function lanAddress(target) {
  const prefix = target.split('.').slice(0, 3).join('.') + '.';
  for (const list of Object.values(networkInterfaces())) {
    for (const entry of list ?? []) if (entry.family === 'IPv4' && !entry.internal && entry.address.startsWith(prefix)) return entry.address;
  }
  throw new Error(`No local IPv4 address on ${prefix}0/24`);
}

async function eshareKeys(host, names) {
  const luma = new LumaConnection({ host });
  await luma.connect();
  try {
    for (const name of names) {
      const code = KEYCODES[name] ?? (/^\d{1,3}$/.test(name) ? Number(name) : null);
      if (code === null) throw new Error(`Unknown key ${name}`);
      // Dev tool only: raw keycodes beyond the browser bridge's allowlist.
      await luma._enqueue(async () => { luma._assertConnected(); await luma._write(`KEYEVENT\r\n${code}\r\n\r\n`); });
      await new Promise(resolve => setTimeout(resolve, 500));
    }
  } finally { luma.close(); }
}

function releaseEnv() {
  const file = path.join(ROOT, '.luma-release.json');
  if (!existsSync(file)) throw new Error('Missing .luma-release.json (run make configure-release once).');
  const saved = JSON.parse(readFileSync(file, 'utf8'));
  const env = { ...process.env };
  for (const key of ['JAVA_HOME', 'ANDROID_HOME', 'LUMA_KEYSTORE_PATH', 'LUMA_KEYSTORE_PASSWORD', 'LUMA_KEY_ALIAS', 'LUMA_KEY_PASSWORD']) env[key] ??= saved[key];
  return { env, gradle: saved.LUMA_GRADLE_BIN ?? 'gradle' };
}

function build() {
  const { env, gradle } = releaseEnv();
  const result = spawnSync(gradle, ['-p', path.join(ROOT, 'android-receiver'), ':app:assembleRelease', '-q'], { env, stdio: ['ignore', 'inherit', 'inherit'] });
  if (result.status !== 0) throw new Error('Gradle release build failed');
  return path.join(ROOT, 'android-receiver/app/build/outputs/apk/release/app-release.apk');
}

async function serveOnce(apk, bindHost) {
  const secret = randomBytes(12).toString('hex');
  const server = http.createServer((req, res) => {
    if (req.method !== 'GET' || req.url !== `/${secret}/receiver.apk`) { res.statusCode = 404; res.end(); return; }
    res.setHeader('Content-Type', 'application/vnd.android.package-archive');
    res.setHeader('Content-Length', statSync(apk).size);
    createReadStream(apk).pipe(res);
  });
  await new Promise(resolve => server.listen(0, bindHost, resolve));
  return { server, url: `http://${bindHost}:${server.address().port}/${secret}/receiver.apk` };
}

async function waitFor(check, { timeoutMs, everyMs = 1000 }) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try { const value = await check(); if (value) return value; } catch { /* receiver restarting */ }
    await new Promise(resolve => setTimeout(resolve, everyMs));
  }
  return null;
}

async function deploy(host, token, flags) {
  const apk = flags.apk ?? (flags['no-build'] ? path.join(ROOT, 'android-receiver/app/build/outputs/apk/release/app-release.apk') : build());
  const bytes = readFileSync(apk);
  const sha256 = createHash('sha256').update(bytes).digest('hex');
  const before = await request(host, 'info');
  console.log(`receiver ${before.versionName} (${before.versionCode}); deploying ${path.basename(apk)} ${bytes.length} bytes sha256 ${sha256.slice(0, 12)}…`);
  const { server, url } = await serveOnce(apk, lanAddress(host));
  try {
    await request(host, 'update.installFrom', { url, sha256, sizeBytes: bytes.length }, { token });
    const state = await waitFor(async () => {
      const status = await request(host, 'update.status', {}, { token });
      return ['awaitingConfirmation', 'needsPermission', 'failed'].includes(status.state) ? status : null;
    }, { timeoutMs: 60000, everyMs: 500 });
    if (!state) throw new Error('Receiver never reached the install confirmation');
    if (state.state === 'needsPermission') {
      console.log('The projector opened "Install unknown apps" for LUMA Receiver. Enable it once, return, then rerun deploy.');
      return;
    }
    if (state.state === 'failed') throw new Error(`Receiver update failed: ${state.error}`);
    await new Promise(resolve => setTimeout(resolve, 2500));
    const keys = String(flags.confirm ?? DEFAULT_CONFIRM).split(',').filter(Boolean);
    if (keys.length) { console.log(`confirming install with EShare keys: ${keys.join(' ')}`); await eshareKeys(host, keys); }
    const after = await waitFor(async () => {
      const info = await request(host, 'info', undefined, { timeoutMs: 2000 });
      return info.installedAt !== before.installedAt ? info : null;
    }, { timeoutMs: 90000 });
    if (!after) throw new Error('Receiver did not come back with a new install (confirmation may need different --confirm keys)');
    console.log(`installed ${after.versionName} (${after.versionCode}) at ${new Date(after.installedAt).toISOString()}`);
  } finally { server.close(); }
}

async function main() {
  const [command, ...rest] = process.argv.slice(2);
  const { positional, flags } = args(rest);
  const config = loadConfig();
  if (!command || command === 'help') { console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('\n').slice(1, 19).join('\n')); return; }
  if (command === 'info') { console.log(JSON.stringify(await request(hostFrom(flags, config), 'info'), null, 2)); return; }
  if (command === 'pair' || command === 'pair-begin') {
    const host = hostFrom(flags, config);
    const begun = await request(host, 'pair.begin', { clientName: flags.name ?? 'Mac dev tool' });
    if (command === 'pair-begin') { saveConfig({ ...config, host }); console.log(begun.pairingId); return; }
    const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
    const code = (await rl.question('Code shown on the projector: ')).replace(/\s/g, '');
    rl.close();
    const done = await request(host, 'pair.complete', { pairingId: begun.pairingId, code });
    saveConfig({ ...config, host, token: done.token, clientId: done.clientId });
    console.log('paired');
    return;
  }
  if (command === 'pair-complete') {
    const host = hostFrom(flags, config);
    const done = await request(host, 'pair.complete', { pairingId: positional[0], code: String(positional[1]).replace(/\s/g, '') });
    saveConfig({ ...config, host, token: done.token, clientId: done.clientId });
    console.log('paired');
    return;
  }
  const host = hostFrom(flags, config), token = config.token;
  if (!token) throw new Error('Not paired. Run: node scripts/receiver-dev.mjs pair --host <IP>');
  const call = async (method, params) => console.log(JSON.stringify(await request(host, method, params, { token, timeoutMs: 30000 }), null, 2));
  switch (command) {
    case 'call': return call(positional[0], positional[1] ? JSON.parse(positional[1]) : {});
    case 'status': return call('player.status', {});
    case 'subtitles': return call('player.subtitles', {});
    case 'pause': case 'resume': case 'stop': return call(`player.${command}`, {});
    case 'seek': return call('player.seek', { position: Number(positional[0]) });
    case 'open': {
      const subtitles = [];
      for (const value of [flags.sub].flat().filter(v => typeof v === 'string')) {
        const [url, language] = value.split('@');
        subtitles.push({ url, ...(language ? { language } : {}), select: true });
      }
      const media = { url: positional[0], ...(flags.title ? { title: flags.title } : {}), ...(subtitles.length ? { subtitles } : {}),
        ...(flags.content ? { content: JSON.parse(flags.content) } : {}) };
      return call('player.open', { media });
    }
    case 'deploy': return deploy(host, token, flags);
    case 'keys': return eshareKeys(host, String(positional[0] ?? '').split(',').filter(Boolean));
    default: throw new Error(`Unknown command ${command}`);
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1; });
