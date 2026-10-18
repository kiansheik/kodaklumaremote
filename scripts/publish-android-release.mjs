import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { validateUpdateManifest } from '../site/update-manifest.js';

const run = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const input = process.argv.slice(2);
if (input.length !== 2 || input[0] !== '--directory') throw new Error('Use --directory <prepared local release directory>.');
const folder = resolve(input[1]);
const metadata = validateUpdateManifest(JSON.parse(await readFile(resolve(folder, 'update.json'), 'utf8')));
const tag = `v${metadata.versionName}`, asset = `luma-remote-${metadata.versionName}.apk`;
const apk = resolve(folder, asset), bytes = await readFile(apk);
if (bytes.length !== metadata.sizeBytes || createHash('sha256').update(bytes).digest('hex') !== metadata.sha256) throw new Error('The prepared APK does not match its update manifest.');
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('Set ANDROID_HOME to verify the signed APK before publication.');
const certificate = (await readFile(resolve(root, 'android/release-certificate.sha256'), 'utf8')).trim();
const { stdout: signature } = await run(resolve(sdk, 'build-tools/36.0.0/apksigner'), ['verify', '--print-certs', apk]);
const signers = [...signature.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$/gmu)].map(match => match[1]);
if (signers.length !== 1 || signers[0] !== certificate || !/^[a-f0-9]{64}$/u.test(certificate)) throw new Error('The APK is not signed with the configured release certificate.');
const invoke = (args, options = {}) => run('gh', args, { cwd: root, maxBuffer: 1024 * 1024, ...options });
const { stdout: status } = await run('git', ['status', '--porcelain', '--untracked-files=no'], { cwd: root });
if (status.trim()) throw new Error('Commit the release source before publishing.');
const { stdout: head } = await run('git', ['rev-parse', 'HEAD'], { cwd: root });
const { stdout: remote } = await invoke(['api', 'repos/kiansheik/kodaklumaremote/commits/main', '--jq', '.sha']);
if (head.trim() !== remote.trim()) throw new Error('Push this exact committed source to main before publishing.');
const { stdout: manifestCommit } = await run('git', ['show', 'HEAD:android/app/build.gradle'], { cwd: root });
if (!manifestCommit.includes(`versionName '${metadata.versionName}'`) || !new RegExp(`versionCode\\s+${metadata.versionCode}\\b`, 'u').test(manifestCommit)) throw new Error('The release version does not match committed Android source.');
await invoke(['release', 'create', tag, apk, resolve(folder, 'update.json'), '--repo', 'kiansheik/kodaklumaremote',
  '--target', head.trim(), '--title', `LUMA Remote ${metadata.versionName}`, '--notes-file', resolve(folder, 'notes.txt'), '--draft']);
// Publish only after both uploads finish, so the Pages event cannot race them.
await invoke(['release', 'edit', tag, '--repo', 'kiansheik/kodaklumaremote', '--draft=false', '--latest']);
console.log(`Published https://github.com/kiansheik/kodaklumaremote/releases/tag/${tag}`);
