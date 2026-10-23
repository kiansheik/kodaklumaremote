import { spawn } from 'node:child_process';
import { copyFile, mkdir, readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { packageKodiAddon } from './package-kodi-addon.mjs';

// Local signing only. Passwords stay in environment variables and are never
// passed as command arguments, copied into the repository, or sent to CI.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const options = new Map();
for (let index = 2; index < process.argv.length; index += 2) {
  const name = process.argv[index], value = process.argv[index + 1];
  if (!['--notes-file', '--released-at'].includes(name) || !value || options.has(name)) throw new Error('Use --notes-file <text> [--released-at <ISO time>].');
  options.set(name, value);
}
if (!options.has('--notes-file')) throw new Error('Provide release notes using --notes-file.');
for (const name of ['LUMA_KEYSTORE_PATH', 'LUMA_KEYSTORE_PASSWORD', 'LUMA_KEY_ALIAS', 'LUMA_KEY_PASSWORD']) {
  if (!process.env[name]) throw new Error(`Set ${name} privately before building a signed release.`);
}
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('Set ANDROID_HOME to the local Android SDK.');
const tools = resolve(sdk, 'build-tools', '36.0.0');
function run(command, args) {
  return new Promise((done, reject) => {
    const child = spawn(command, args, { cwd: root, env: process.env, stdio: 'inherit', shell: false });
    child.once('error', () => reject(new Error('A required local build tool could not start.')));
    child.once('exit', code => code === 0 ? done() : reject(new Error('Release preparation failed; no publication was attempted.')));
  });
}
const gradle = await readFile(resolve(root, 'android/app/build.gradle'), 'utf8');
const certificate = (await readFile(resolve(root, 'android/release-certificate.sha256'), 'utf8')).trim();
if (!/^[a-f0-9]{64}$/u.test(certificate)) throw new Error('The checked-in public signing-certificate fingerprint is invalid.');
const version = gradle.match(/versionName\s+'(\d+\.\d+\.\d+)'/u)?.[1];
if (!version) throw new Error('No release version found in Android configuration.');
const output = resolve(root, '.local', 'releases', `v${version}`);
await mkdir(output, { recursive: true });
const kodiAddon = await packageKodiAddon({ outputDirectory: output });
await run(process.env.LUMA_GRADLE_BIN || 'gradle', ['--offline', '--no-daemon', '-p', 'android', ':app:assembleRelease']);
const apk = resolve(output, `luma-remote-${version}.apk`);
await copyFile(resolve(root, 'android/app/build/outputs/apk/release/app-release.apk'), apk);
await run(process.execPath, ['scripts/generate-update.mjs', '--apk', apk,
  '--aapt2', resolve(tools, 'aapt2'), '--apksigner', resolve(tools, 'apksigner'),
  '--expected-cert-sha256', certificate,
  '--released-at', options.get('--released-at') || new Date().toISOString(),
  '--notes-file', resolve(options.get('--notes-file')), '--out', resolve(output, 'update.json')]);
await copyFile(resolve(options.get('--notes-file')), resolve(output, 'notes.txt'));
console.log(`Signed release and ${kodiAddon.assetName} prepared in .local/releases/v${version}. No files were published.`);
