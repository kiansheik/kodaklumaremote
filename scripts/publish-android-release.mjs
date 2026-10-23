import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { receiverAssetName, validateReceiverManifest, validateUpdateManifest } from '../site/update-manifest.js';

const run = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export function releaseAssetPaths(folder, metadata, receiver) {
  const directory = resolve(folder);
  const apk = resolve(directory, `luma-remote-${metadata.versionName}.apk`);
  if (!receiver || !/^\d+\.\d+\.\d+$/u.test(receiver.versionName)) throw new Error('The prepared LUMA Receiver manifest is missing.');
  return [apk, resolve(directory, 'update.json'), resolve(directory, receiverAssetName(receiver.versionName)), resolve(directory, 'receiver-update.json')];
}

async function verifySignedApk(apk, manifest, certificate, sdk) {
  const bytes = await readFile(apk);
  if (bytes.length !== manifest.sizeBytes || createHash('sha256').update(bytes).digest('hex') !== manifest.sha256) throw new Error(`${basename(apk)} does not match its update manifest.`);
  const { stdout: signature } = await run(resolve(sdk, 'build-tools/36.0.0/apksigner'), ['verify', '--print-certs', apk]);
  const signers = [...signature.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]+)$/gmu)].map(match => match[1]);
  if (signers.length !== 1 || signers[0] !== certificate || !/^[a-f0-9]{64}$/u.test(certificate)) throw new Error(`${basename(apk)} is not signed with the configured release certificate.`);
}

export function releaseCreateArguments({ tag, assets, head, versionName, notesFile }) {
  if (!Array.isArray(assets) || assets.length !== 4) throw new Error('A release must upload the phone APK, its manifest, the LUMA Receiver APK and its manifest.');
  return ['release', 'create', tag, ...assets, '--repo', 'kiansheik/kodaklumaremote',
    '--target', head, '--title', `LUMA Remote ${versionName}`, '--notes-file', notesFile, '--draft'];
}

export async function publishAndroidRelease(input = process.argv.slice(2)) {
  if (input.length !== 2 || input[0] !== '--directory') throw new Error('Use --directory <prepared local release directory>.');
  const folder = resolve(input[1]);
  const metadata = validateUpdateManifest(JSON.parse(await readFile(resolve(folder, 'update.json'), 'utf8')));
  const tag = `v${metadata.versionName}`;
  const receiver = validateReceiverManifest(JSON.parse(await readFile(resolve(folder, 'receiver-update.json'), 'utf8')), tag);
  const assets = releaseAssetPaths(folder, metadata, receiver);
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  if (!sdk) throw new Error('Set ANDROID_HOME to verify the signed APKs before publication.');
  const certificate = (await readFile(resolve(root, 'android/release-certificate.sha256'), 'utf8')).trim();
  await verifySignedApk(assets[0], metadata, certificate, sdk);
  await verifySignedApk(assets[2], receiver, certificate, sdk);
  const invoke = (args, options = {}) => run('gh', args, { cwd: root, maxBuffer: 1024 * 1024, ...options });
  const { stdout: status } = await run('git', ['status', '--porcelain', '--untracked-files=no'], { cwd: root });
  if (status.trim()) throw new Error('Commit the release source before publishing.');
  const { stdout: head } = await run('git', ['rev-parse', 'HEAD'], { cwd: root });
  const { stdout: remote } = await invoke(['api', 'repos/kiansheik/kodaklumaremote/commits/main', '--jq', '.sha']);
  if (head.trim() !== remote.trim()) throw new Error('Push this exact committed source to main before publishing.');
  const { stdout: manifestCommit } = await run('git', ['show', 'HEAD:android/app/build.gradle'], { cwd: root });
  if (!manifestCommit.includes(`versionName '${metadata.versionName}'`) || !new RegExp(`versionCode\\s+${metadata.versionCode}\\b`, 'u').test(manifestCommit)) throw new Error('The release version does not match committed Android source.');
  const { stdout: receiverCommit } = await run('git', ['show', 'HEAD:android-receiver/app/build.gradle'], { cwd: root });
  if (!receiverCommit.includes(`versionName '${receiver.versionName}'`) || !new RegExp(`versionCode\\s+${receiver.versionCode}\\b`, 'u').test(receiverCommit)) throw new Error('The LUMA Receiver version does not match committed receiver source.');
  await invoke(releaseCreateArguments({ tag, assets, head: head.trim(), versionName: metadata.versionName,
    notesFile: resolve(folder, 'notes.txt') }));
  // Publish only after all uploads finish, so the Pages event cannot race them.
  await invoke(['release', 'edit', tag, '--repo', 'kiansheik/kodaklumaremote', '--draft=false', '--latest']);
  await run(process.execPath, ['scripts/deploy-gh-pages.mjs', '--tag', tag], { cwd: root, maxBuffer: 1024 * 1024 });
  console.log(`Published https://github.com/kiansheik/kodaklumaremote/releases/tag/${tag}`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  publishAndroidRelease().catch(error => { console.error(error instanceof Error ? error.message : 'Android publication failed.'); process.exitCode = 1; });
}
