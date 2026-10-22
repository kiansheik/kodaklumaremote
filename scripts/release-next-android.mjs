import { execFile as execFileCallback, spawn } from 'node:child_process';
import { lstat, readFile, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { promisify } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { repositoryOrigin } from './deploy-gh-pages.mjs';
import { loadReleaseConfig, ReleaseConfigError } from './release-config.mjs';
import { MAX_MANIFEST_BYTES, RELEASE_REPOSITORY, validateUpdateManifest } from '../site/update-manifest.js';

const execFile = promisify(execFileCallback);
const root = fileURLToPath(new URL('../', import.meta.url));
const gradlePath = path.join(root, 'android/app/build.gradle');
const commandOptions = { cwd: root, encoding: 'utf8', maxBuffer: 2 * 1024 * 1024,
  env: { ...process.env, GIT_TERMINAL_PROMPT: '0' } };

export class ReleaseError extends Error {}

export function androidVersion(source) {
  const name = source.match(/versionName\s+'(\d+\.\d+\.\d+)'/u)?.[1];
  const code = Number(source.match(/versionCode\s+(\d+)\b/u)?.[1]);
  if (!name || !Number.isSafeInteger(code) || code < 1 || code > 2147483647) throw new ReleaseError('Android version configuration is invalid.');
  return { name, code };
}

export function nextPatch(version) {
  const match = version.match(/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/u);
  if (!match) throw new ReleaseError('The published version is not a stable semantic version.');
  const patch = Number(match[3]);
  if (!Number.isSafeInteger(patch) || patch === Number.MAX_SAFE_INTEGER) throw new ReleaseError('The published patch version cannot be incremented safely.');
  return `${match[1]}.${match[2]}.${patch + 1}`;
}

export function releasePlan({ current, latest, hasReleaseChanges }) {
  if (current.name === latest.versionName) {
    if (current.code !== latest.versionCode) throw new ReleaseError('The local and published versions disagree.');
    if (!hasReleaseChanges) return { kind: 'page', name: current.name, code: current.code };
    if (current.code === 2147483647) throw new ReleaseError('The Android version code cannot be incremented safely.');
    return { kind: 'release', name: nextPatch(current.name), code: current.code + 1, bump: true };
  }
  if (latest.versionCode === 2147483647) throw new ReleaseError('The Android version code cannot be incremented safely.');
  const expectedName = nextPatch(latest.versionName), expectedCode = latest.versionCode + 1;
  if (current.name !== expectedName || current.code !== expectedCode) {
    throw new ReleaseError(`The prepared Android version must be ${expectedName}/code ${expectedCode}.`);
  }
  return { kind: 'release', name: current.name, code: current.code, bump: false };
}

export function releaseRelevant(file) {
  return typeof file === 'string' && file !== '' && !file.startsWith('docs/') && !file.startsWith('release-notes/');
}

const untrackedExtensions = new Map([
  ['android/', new Set(['.gradle', '.java', '.kt', '.pro', '.properties', '.xml'])],
  ['docs/', new Set(['.md'])],
  ['release-notes/', new Set(['.txt'])],
  ['scripts/', new Set(['.js', '.mjs', '.sh'])],
  ['site/', new Set(['.css', '.html', '.js', '.json', '.svg'])],
  ['src/', new Set(['.css', '.html', '.js', '.json', '.svg'])],
  ['tests/', new Set(['.html', '.java', '.js', '.json', '.md', '.mjs'])],
]);
const rootReleaseFiles = new Set(['Makefile', 'package.json', 'package-lock.json']);
const sensitivePath = /(?:^|\/)(?:\.env(?:[./-]|$)|[^/]*(?:credential|password|secret|keystore|private[-_]?key)[^/]*)/iu;
const sensitiveExtension = /\.(?:jks|key|keystore|p12|pem|pfx)$/iu;
const sensitiveContent = /-----BEGIN (?:EC |OPENSSH |RSA )?PRIVATE KEY-----|\bAKIA[A-Z0-9]{16}\b|\bgithub_pat_[A-Za-z0-9_]{20,}\b|\bghp_[A-Za-z0-9]{20,}\b/u;

export function untrackedReleaseDisposition(file) {
  if (typeof file !== 'string' || !file || path.posix.isAbsolute(file) || file.split('/').some(part => part === '..')) return 'reject';
  if (sensitivePath.test(file) || sensitiveExtension.test(file) || file === 'android/local.properties') return 'reject';
  if (rootReleaseFiles.has(file)) return 'stage';
  const rule = [...untrackedExtensions].find(([prefix]) => file.startsWith(prefix));
  if (!rule) return 'ignore';
  return rule[1].has(path.posix.extname(file).toLowerCase()) ? 'stage' : 'reject';
}

function replaceVersion(source, version) {
  const replaced = source
    .replace(/versionCode\s+\d+\b/u, `versionCode ${version.code}`)
    .replace(/versionName\s+'\d+\.\d+\.\d+'/u, `versionName '${version.name}'`);
  const checked = androidVersion(replaced);
  if (checked.name !== version.name || checked.code !== version.code) throw new ReleaseError('Could not update the Android version safely.');
  return replaced;
}

async function run(command, args, message) {
  try { return await execFile(command, args, commandOptions); }
  catch (error) { throw new ReleaseError(`${message}${error.stderr?.trim() ? `\n${error.stderr.trim()}` : ''}`); }
}

async function visible(command, args, message) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: root, env: process.env, stdio: 'inherit', shell: false });
    child.once('error', () => reject(new ReleaseError(message)));
    child.once('exit', code => code === 0 ? resolve() : reject(new ReleaseError(message)));
  });
}

async function git(args, message = 'Git release preparation failed.') { return run('git', args, message); }

async function publishedRelease() {
  const response = await run('gh', ['api', `repos/${RELEASE_REPOSITORY}/releases/latest`], 'Could not read the latest GitHub release.');
  let release;
  try { release = JSON.parse(response.stdout); } catch { throw new ReleaseError('GitHub returned invalid release metadata.'); }
  if (release.draft !== false || release.prerelease !== false || !/^v\d+\.\d+\.\d+$/u.test(release.tag_name || '')) throw new ReleaseError('The latest GitHub release is not a stable release.');
  const assets = release.assets?.filter(asset => asset.name === 'update.json');
  if (!Array.isArray(assets) || assets.length !== 1 || !Number.isSafeInteger(assets[0].id)) throw new ReleaseError('The latest release needs exactly one update.json asset.');
  const downloaded = await run('gh', ['api', '-H', 'Accept: application/octet-stream',
    `repos/${RELEASE_REPOSITORY}/releases/assets/${assets[0].id}`], 'Could not read the latest release manifest.');
  if (Buffer.byteLength(downloaded.stdout, 'utf8') > MAX_MANIFEST_BYTES) throw new ReleaseError('The latest release manifest is too large.');
  let manifest;
  try { manifest = validateUpdateManifest(JSON.parse(downloaded.stdout)); }
  catch { throw new ReleaseError('The latest release manifest is invalid.'); }
  if (release.tag_name !== `v${manifest.versionName}`) throw new ReleaseError('The latest release tag and manifest disagree.');
  return { release, manifest };
}

async function exists(file) { try { return (await stat(file)).isFile(); } catch { return false; } }

async function automaticUntrackedFiles(files) {
  const selected = [], rejected = [];
  let bytes = 0;
  for (const file of files) {
    const disposition = untrackedReleaseDisposition(file);
    if (disposition === 'ignore') continue;
    if (disposition === 'reject') { rejected.push(file); continue; }
    const absolute = path.join(root, file);
    let info, contents;
    try { info = await lstat(absolute); contents = await readFile(absolute, 'utf8'); }
    catch { rejected.push(file); continue; }
    if (!info.isFile() || info.isSymbolicLink() || info.size > 1024 * 1024 || contents.includes('\u0000') || sensitiveContent.test(contents)) {
      rejected.push(file); continue;
    }
    bytes += info.size;
    selected.push(file);
  }
  if (selected.length > 256 || bytes > 4 * 1024 * 1024) throw new ReleaseError('Too many new release files to stage automatically. Review and stage them explicitly.');
  if (rejected.length) throw new ReleaseError(`Review and stage unsupported or sensitive-looking new files explicitly: ${rejected.join(', ')}`);
  return selected;
}

async function releaseNotes(version) {
  const relative = `release-notes/${version}.txt`, file = path.join(root, relative);
  if (!await exists(file)) {
    const supplied = process.env.RELEASE_NOTES?.trim();
    if (!supplied) throw new ReleaseError(`Create ${relative} or set RELEASE_NOTES before deploying.`);
    await writeFile(file, `LUMA Remote ${version}\n\n${supplied}\n`, { encoding: 'utf8', mode: 0o644, flag: 'wx' });
  }
  const notes = await readFile(file, 'utf8');
  if (!notes.trim() || notes.length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(notes)) throw new ReleaseError('Release notes must be plain text of at most 2,000 characters.');
  return { relative, file };
}

async function signingEnvironment() {
  try { await loadReleaseConfig(); }
  catch (error) { throw new ReleaseError(error instanceof ReleaseConfigError ? error.message : 'The local release config could not be loaded.'); }
  const names = ['ANDROID_HOME', 'LUMA_KEYSTORE_PATH', 'LUMA_KEYSTORE_PASSWORD', 'LUMA_KEY_ALIAS', 'LUMA_KEY_PASSWORD'];
  let missing = names.filter(name => !process.env[name]);
  if (missing.length && process.stdin.isTTY && process.stdout.isTTY) {
    console.log('No complete local signing config was found. Starting one-time setup.');
    await visible(process.execPath, ['scripts/configure-android-release.mjs'], 'Local release configuration failed.');
    try { await loadReleaseConfig(); }
    catch (error) { throw new ReleaseError(error instanceof Error ? error.message : 'The local release config could not be loaded.'); }
    missing = names.filter(name => !process.env[name]);
  }
  if (missing.length) throw new ReleaseError(`Run make configure-release once; missing local release settings: ${missing.join(', ')}.`);
}

export async function releaseNextAndroid() {
  const branch = (await git(['branch', '--show-current'])).stdout.trim();
  if (branch !== 'main') throw new ReleaseError('Android releases must be prepared from the main branch.');
  const origin = (await git(['remote', 'get-url', 'origin'])).stdout.trim();
  try { repositoryOrigin(origin); }
  catch (error) { throw new ReleaseError(error instanceof Error ? error.message : 'The Git origin is not the release repository.'); }
  await git(['fetch', 'origin', 'main', '--tags'], 'Could not refresh origin/main and release tags.');
  try { await git(['merge-base', '--is-ancestor', 'origin/main', 'HEAD']); }
  catch { throw new ReleaseError('Local main is behind or diverged from origin/main. Reconcile it before releasing.'); }

  const { release, manifest } = await publishedRelease();
  const source = await readFile(gradlePath, 'utf8'), current = androidVersion(source);
  const committed = (await git(['diff', '--name-only', `${release.tag_name}..HEAD`])).stdout.split('\n').filter(Boolean);
  const dirty = (await git(['diff', '--name-only', 'HEAD'])).stdout.split('\n').filter(Boolean);
  const untracked = (await git(['ls-files', '--others', '--exclude-standard', '-z'])).stdout.split('\0').filter(Boolean);
  const untrackedRelease = untracked.filter(file => untrackedReleaseDisposition(file) !== 'ignore');
  const plan = releasePlan({ current, latest: manifest, hasReleaseChanges: [...committed, ...dirty, ...untrackedRelease].some(releaseRelevant) });
  if (plan.kind === 'page') {
    await visible(process.execPath, ['scripts/deploy-gh-pages.mjs', '--tag', release.tag_name], 'The page-only deployment failed.');
    return plan;
  }
  await signingEnvironment();

  const notes = await releaseNotes(plan.name);
  const afterNotes = (await git(['ls-files', '--others', '--exclude-standard', '-z'])).stdout.split('\0').filter(Boolean);
  const automatic = await automaticUntrackedFiles(afterNotes);
  if (plan.bump) await writeFile(gradlePath, replaceVersion(source, plan), 'utf8');
  await git(['add', '-u']);
  if (automatic.length) await git(['add', '--', ...automatic]);
  await git(['add', '--', 'android/app/build.gradle', notes.relative]);
  await git(['diff', '--cached', '--check'], 'The staged release source has whitespace errors.');
  await visible('npm', ['run', 'check'], 'Release checks failed; no source was committed or published.');
  await visible('npm', ['run', 'release:android', '--', '--notes-file', notes.relative], 'The signed APK build failed; no source was committed or published.');
  try { await git(['diff', '--quiet']); } catch { throw new ReleaseError('The build changed tracked files after staging. Review them before releasing.'); }

  let committedNow = false;
  try { await git(['diff', '--cached', '--quiet']); }
  catch {
    await git(['commit', '-m', `Release LUMA Remote ${plan.name}`], 'Could not commit the exact release source.');
    committedNow = true;
  }
  const head = (await git(['rev-parse', 'HEAD'])).stdout.trim();
  await git(['push', 'origin', 'HEAD:main'], 'The source push failed; no GitHub release was published.');
  await visible('npm', ['run', 'publish:android', '--', '--directory', `.local/releases/v${plan.name}`], 'Release publication or update-page deployment failed. Inspect any draft release before retrying; the prepared version must not be bumped again.');
  console.log(`LUMA Remote ${plan.name}/code ${plan.code} published from ${head}${committedNow ? '.' : ' (existing commit).'}`);
  return { ...plan, head };
}

async function main() { await releaseNextAndroid(); }
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(error => {
  console.error(error instanceof ReleaseError ? error.message : 'Android release deployment failed.'); process.exitCode = 1;
});
