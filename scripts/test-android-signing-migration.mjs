import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { homedir } from 'node:os';
import { stat } from 'node:fs/promises';

// Explicit owned-emulator fixture only. Never builds, uninstalls, resets, opens
// an Activity or contacts a receiver. Both private bootstrap APKs need lineage.
const options = new Map();
const names = new Set(['serial', 'debug-apk', 'bootstrap-apk', 'public-apk', 'debug-test-apk', 'release-test-apk', 'expected-signer-sha256']);
for (let index = 2; index < process.argv.length; index += 2) {
  const name = process.argv[index]?.replace(/^--/u, '');
  const value = process.argv[index + 1];
  if (!process.argv[index]?.startsWith('--') || !names.has(name) || !value || value.startsWith('--') || options.has(name)) throw new Error('Provide each required migration fixture option exactly once.');
  options.set(name, value);
}
if (options.size !== names.size) throw new Error('Required: --serial --debug-apk --bootstrap-apk --public-apk --debug-test-apk --release-test-apk --expected-signer-sha256.');
const serial = options.get('serial');
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Signing migration seed requires an explicit Android emulator serial.');
const expected = options.get('expected-signer-sha256').replaceAll(':', '').toLowerCase();
if (!/^[a-f0-9]{64}$/u.test(expected)) throw new Error('Provide the expected production certificate SHA-256 fingerprint.');
const apks = Object.fromEntries(await Promise.all([...names].filter(name => name.endsWith('-apk')).map(async name => {
  const path = resolve(options.get(name));
  const info = await stat(path);
  if (!info.isFile() || info.size < 1024 || info.size > 128 * 1024 * 1024 || !path.endsWith('.apk')) throw new Error('Provide bounded existing APK files for the migration fixture.');
  return [name, path];
})));
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`, 'platform-tools/adb');
const run = promisify(execFile);
async function command(args, timeout = 60000) {
  try { return await run(adb, ['-s', serial, ...args], { timeout, maxBuffer: 64 * 1024 }); }
  catch { throw new Error('An owned-emulator migration command failed.'); }
}
const qemu = (await command(['shell', 'getprop', 'ro.kernel.qemu'], 10000)).stdout.trim();
const sdk = Number((await command(['shell', 'getprop', 'ro.build.version.sdk'], 10000)).stdout.trim());
if (qemu !== '1' || !Number.isInteger(sdk) || sdk < 33) throw new Error('Use an Android API33+ emulator for the private signing migration fixture.');
async function install(name) {
  const { stdout } = await command(['install', '-r', '-t', apks[name]]);
  assert.match(stdout, /\bSuccess\b/u, 'APK replacement was not accepted.');
}
async function instrument(mode) {
  const args = ['shell', 'am', 'instrument', '-w', '-e', 'mode', mode];
  if (mode === 'migration-seed') args.push('-e', 'confirmEmulatorSeed', 'true');
  else args.push('-e', 'expectFixture', 'true', '-e', 'expectedSignerSha256', expected);
  args.push('dev.framextv.lumaremote.test/dev.framextv.lumaremote.BackendInstrumentation');
  const { stdout } = await command(args, 30000);
  const field = name => stdout.match(new RegExp(`^INSTRUMENTATION_RESULT: ${name}=(.*)$`, 'mu'))?.[1]?.trim();
  assert.equal(field('failures'), '0', 'Migration instrumentation did not pass.');
  assert.equal(field('passed'), '1', 'Migration instrumentation did not complete.');
  assert.match(stdout, /^INSTRUMENTATION_CODE: -1\s*$/mu, 'Migration instrumentation was not successful.');
  for (const name of ['hasReceiver', 'hasPairing', 'decrypted', 'markerPresent', 'fixtureChecked', 'fixtureMatched']) assert.equal(field(name), 'true', 'Saved migration fixture was not preserved.');
  const current = field('currentSignerSha256');
  assert.match(current || '', /^[a-f0-9]{64}$/u, 'Current signer proof missing.');
  if (mode === 'migration-check') assert.equal(current, expected, 'The expected production signer was not active.');
  return current;
}
let stage = 'debug_seed';
try {
  await install('debug-apk'); await install('debug-test-apk');
  const previous = await instrument('migration-seed');
  assert.notEqual(previous, expected, 'The initial debug signer must differ from production.');
  console.log(JSON.stringify({ stage, preserved: true, currentSignerSha256: previous }));
  stage = 'private_bootstrap';
  await install('bootstrap-apk'); await install('release-test-apk');
  await instrument('migration-check');
  console.log(JSON.stringify({ stage, preserved: true, currentSignerSha256: expected }));
  stage = 'public_release';
  await install('public-apk'); await instrument('migration-check');
  console.log(JSON.stringify({ stage, preserved: true, currentSignerSha256: expected }));
  console.log('Signing migration: encrypted settings and private marker survived both APK replacements.');
} catch { throw new Error(`Signing migration fixture failed at ${stage}. No uninstall or reset was performed.`); }
