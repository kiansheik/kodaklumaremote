import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { validateUpdateManifest } from '../site/update-manifest.js';
import { KODI_ADDON_FILES, KODI_ADDON_ID, validatePreparedKodiAddonAsset } from './package-kodi-addon.mjs';

const run = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
export const KODI_ADDON_GIT_PATHS = Object.freeze(KODI_ADDON_FILES.map(file => `kodi-addon/${KODI_ADDON_ID}/${file}`));

export function releaseAssetPaths(folder, metadata, kodiAddon) {
  const directory = resolve(folder);
  const apk = resolve(directory, `luma-remote-${metadata.versionName}.apk`);
  if (!kodiAddon || dirname(kodiAddon.path) !== directory || basename(kodiAddon.path) !== kodiAddon.assetName) {
    throw new Error('The prepared Kodi add-on asset is outside the release directory.');
  }
  return [apk, resolve(directory, 'update.json'), kodiAddon.path];
}

export async function verifyCommittedKodiAddon(runCommand = run, cwd = root) {
  try { await runCommand('git', ['ls-files', '--error-unmatch', '--', ...KODI_ADDON_GIT_PATHS], { cwd }); }
  catch { throw new Error('Every packaged Kodi add-on source file must be committed before publication.'); }
  try { await runCommand('git', ['diff', '--quiet', 'HEAD', '--', ...KODI_ADDON_GIT_PATHS], { cwd }); }
  catch { throw new Error('The packaged Kodi add-on source must exactly match HEAD before publication.'); }
}

export function releaseCreateArguments({ tag, assets, head, versionName, notesFile }) {
  if (!Array.isArray(assets) || assets.length !== 3) throw new Error('A release must upload the APK, update manifest, and Kodi add-on.');
  return ['release', 'create', tag, ...assets, '--repo', 'kiansheik/kodaklumaremote',
    '--target', head, '--title', `LUMA Remote ${versionName}`, '--notes-file', notesFile, '--draft'];
}

export async function publishAndroidRelease(input = process.argv.slice(2)) {
  if (input.length !== 2 || input[0] !== '--directory') throw new Error('Use --directory <prepared local release directory>.');
  const folder = resolve(input[1]);
  const metadata = validateUpdateManifest(JSON.parse(await readFile(resolve(folder, 'update.json'), 'utf8')));
  const tag = `v${metadata.versionName}`;
  const kodiAddon = await validatePreparedKodiAddonAsset(folder);
  const assets = releaseAssetPaths(folder, metadata, kodiAddon);
  const apk = assets[0], bytes = await readFile(apk);
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
  await verifyCommittedKodiAddon();
  const { stdout: head } = await run('git', ['rev-parse', 'HEAD'], { cwd: root });
  const { stdout: remote } = await invoke(['api', 'repos/kiansheik/kodaklumaremote/commits/main', '--jq', '.sha']);
  if (head.trim() !== remote.trim()) throw new Error('Push this exact committed source to main before publishing.');
  const { stdout: manifestCommit } = await run('git', ['show', 'HEAD:android/app/build.gradle'], { cwd: root });
  if (!manifestCommit.includes(`versionName '${metadata.versionName}'`) || !new RegExp(`versionCode\\s+${metadata.versionCode}\\b`, 'u').test(manifestCommit)) throw new Error('The release version does not match committed Android source.');
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
