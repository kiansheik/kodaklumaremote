#!/usr/bin/env node
// Emulator-only integration test. Never discovers or touches physical receivers.
// node scripts/test-receiver-games.mjs --rom /private/game.nes
import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { basename, resolve } from 'node:path';
import { homedir } from 'node:os';

const run = promisify(execFile);
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5582';
assert.match(serial, /^emulator-\d+$/u, 'Select an emulator, never a physical device.');
const adb = process.env.ADB || resolve(homedir(), 'Library/Android/sdk/platform-tools/adb');
const args = process.argv.slice(2);
const romPath = args[args.indexOf('--rom') + 1];
assert.ok(args.includes('--rom') && romPath, 'Provide --rom /path/game.nes (stays private).');
const rom = await readFile(romPath);
assert.ok(rom.length >= 16 && rom.length <= 8 * 1024 * 1024);
const id = createHash('sha256').update(rom).digest('hex');
const output = resolve('.local/nes-tests/android'); await mkdir(output, { recursive: true });
const command = (...args) => run(adb, ['-s', serial, ...args], { maxBuffer: 8 * 1024 * 1024 });
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let token;
async function rpc(method, body = {}, authorized = true) {
  const response = await fetch(`http://127.0.0.1:14191/api/v1/${method}`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', ...(authorized && token ? { Authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body), signal: AbortSignal.timeout(10000),
  });
  const value = await response.json();
  if (!value.ok) throw new Error(value.error?.code || `HTTP_${response.status}`);
  return value.result;
}
async function waitFor(work, label, seconds = 15) {
  const until = Date.now() + seconds * 1000; let last;
  while (Date.now() < until) {
    try { const result = await work(); if (result) return result; } catch (error) { last = error; }
    await delay(250);
  }
  throw new Error(`${label}: ${last?.message || 'timed out'}`);
}
async function ui() {
  await command('shell', 'uiautomator', 'dump', '/sdcard/luma-games-test.xml');
  return (await command('shell', 'cat', '/sdcard/luma-games-test.xml')).stdout;
}
async function screenshot(name) {
  const { stdout } = await run(adb, ['-s', serial, 'exec-out', 'screencap', '-p'], { encoding: 'buffer', maxBuffer: 12 * 1024 * 1024 });
  await writeFile(resolve(output, `${name}.png`), stdout);
}
async function tap(label) {
  const xml = await ui();
  const node = xml.match(/<node\b[^>]*>/gu)?.find(node => node.includes(`text="${label}"`));
  assert.ok(node, `Missing UI button ${label}`);
  const bounds = /bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/u.exec(node).slice(1).map(Number);
  await command('shell', 'input', 'tap', String(Math.floor((bounds[0] + bounds[2]) / 2)), String(Math.floor((bounds[1] + bounds[3]) / 2)));
}
await command('forward', 'tcp:14191', 'tcp:4191');
try {
  await command('shell', 'am', 'start', '-n', 'dev.framextv.lumareceiver/.MainActivity');
  await waitFor(async () => (await fetch('http://127.0.0.1:14191/api/v1/info')).ok, 'Receiver startup');
  await assert.rejects(rpc('games.list', {}, false), /UNAUTHORIZED/u);
  const pairing = await rpc('pair.begin', { clientName: 'Local emulator games test' }, false);
  const xml = await ui();
  const codeNode = xml.match(/<node\b[^>]*resource-id="dev\.framextv\.lumareceiver:id\/pairing_code"[^>]*>/u)?.[0];
  const code = /text="([\d ]{6,7})"/u.exec(codeNode || '')?.[1]?.replaceAll(' ', ''); assert.ok(code?.length === 6, 'Pairing code visible');
  token = (await rpc('pair.complete', { pairingId: pairing.pairingId, code }, false)).token;
  await rpc('update.setPolicy', { policy: 'off' });
  const chunkSize = 131072;
  for (let offset = 0; offset < rom.length; offset += chunkSize) {
    const result = await rpc('games.put', { id, name: basename(romPath), offset, totalSize: rom.length, data: rom.subarray(offset, offset + chunkSize).toString('base64') });
    assert.ok(result.received > offset); if (result.complete) break;
  }
  const listed = (await rpc('games.list')).games.find(game => game.id === id);
  assert.equal(listed.sizeBytes, rom.length);
  const chunks = [];
  for (let offset = 0; offset < rom.length; offset += chunkSize) {
    const result = await rpc('games.get', { id, offset, length: chunkSize });
    assert.equal(result.offset, offset); chunks.push(Buffer.from(result.data, 'base64'));
  }
  assert.deepEqual(Buffer.concat(chunks), rom, 'Download is byte-identical');
  await rpc('games.show'); await delay(500);
  if ((await ui()).includes('text="Got it"')) await tap('Got it');
  await screenshot('library');
  await rpc('games.play', { id });
  await waitFor(async () => { const state = await rpc('games.status'); assert.equal(state.error, null); return state.active && !state.paused && state.frames > 20 && state; }, 'NES runtime');
  await waitFor(async () => (await rpc('games.status')).frames > 120, 'Game reaches title input');
  await command('shell', 'input', 'gamepad', 'keyevent', '--longpress', 'KEYCODE_BUTTON_START');
  await delay(2500); await screenshot('game');
  await command('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  const paused = await waitFor(async () => { const state = await rpc('games.status'); return state.paused && state; }, 'Pause menu');
  await delay(300); assert.equal((await rpc('games.status')).frames, paused.frames, 'Paused frames remain stable');
  for (let i = 0; i < 6; i++) await tap('Save checkpoint');
  assert.equal((await rpc('games.status')).checkpointCount, 5, 'Only five checkpoints are retained');
  await tap('Checkpoint history');
  const history = await ui();
  // Android omits offscreen rows from UIAutomator; navigate the scroll list.
  for (let i = 0; i < 4; i++) await command('shell', 'input', 'keyevent', 'KEYCODE_DPAD_DOWN');
  const scrolledHistory = await ui();
  const labels = [...new Set([...(`${history}${scrolledHistory}`).matchAll(/text="((?:Latest|Previous [1-4]) ·[^"]+)"/gu)].map(match => match[1]))];
  assert.equal(labels.length, 5, 'History exposes all five checkpoints through navigation');
  const oldest = labels.find(label => label.startsWith('Previous 4 ·'));
  await screenshot('checkpoint-history');
  const loadsBefore = (await rpc('games.status')).checkpointLoads;
  await tap(oldest);
  assert.equal((await rpc('games.status')).checkpointLoads, loadsBefore + 1, 'Older checkpoint loads');
  assert.equal((await rpc('games.status')).checkpointCount, 5, 'Loading preserves history');
  await command('shell', 'input', 'keyevent', 'KEYCODE_BACK');
  await tap('Load checkpoint');
  assert.equal((await rpc('games.status')).checkpointLoads, loadsBefore + 2, 'Latest checkpoint loads');
  await waitFor(async () => (await rpc('games.status')).frames > paused.frames, 'Checkpoint resumes');
  await command('shell', 'input', 'keyevent', 'KEYCODE_BACK'); await screenshot('pause');
  await rpc('games.stop'); assert.equal((await rpc('games.status')).active, false);
  await rpc('games.play', { id });
  await waitFor(async () => (await rpc('games.status')).frames > 20, 'Auto-save reopens');
  await rpc('games.stop');
  await command('shell', 'am', 'force-stop', 'dev.framextv.lumareceiver');
  await command('shell', 'am', 'start', '-n', 'dev.framextv.lumareceiver/.MainActivity');
  await waitFor(async () => (await rpc('games.list')).games.some(game => game.id === id), 'Library survives process restart');
  await rpc('games.play', { id });
  await waitFor(async () => (await rpc('games.status')).frames > 20, 'Runtime survives restart');
  await rpc('games.stop');
  console.log('PASS: authentication, ROM upload/download/hash, library, native frames, gamepad Start, pause, checkpoint, auto-save and process restart.');
  console.log('Screenshots retained privately under .local/nes-tests/android/. Hardware pairing and projector performance remain untested.');
} finally { await command('forward', '--remove', 'tcp:14191'); }
