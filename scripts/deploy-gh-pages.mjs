import { execFile as execFileCallback } from 'node:child_process';
import { copyFile, mkdtemp, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { parseArgs, promisify } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { buildPages } from './build-pages.mjs';
import { MAX_MANIFEST_BYTES, RELEASE_REPOSITORY } from '../site/update-manifest.js';

const execFile = promisify(execFileCallback);
const root = fileURLToPath(new URL('../', import.meta.url));
const files = ['.nojekyll', 'index.html', 'styles.css', 'app.js', 'update-manifest.js', 'projector.svg', 'update.json', 'receiver-update.json'];
class DeploymentError extends Error {}

export function repositoryOrigin(value) {
  let remote, ssh = false;
  if (typeof value !== 'string') throw new DeploymentError('The source origin must point to kiansheik/kodaklumaremote on GitHub.');
  const scp = value.match(/^git@github\.com:([^?#]+)$/i);
  if (scp) { remote = `/${scp[1]}`; ssh = true; }
  else {
    let url;
    try { url = new URL(value); } catch { throw new DeploymentError('The source origin must point to kiansheik/kodaklumaremote on GitHub.'); }
    if (url.hostname !== 'github.com' || url.port || url.search || url.hash || url.password || !['https:', 'ssh:'].includes(url.protocol)
        || (url.protocol === 'https:' && url.username) || (url.protocol === 'ssh:' && url.username !== 'git')) throw new DeploymentError('The source origin must point to kiansheik/kodaklumaremote on GitHub.');
    remote = url.pathname; ssh = url.protocol === 'ssh:';
  }
  if (remote.toLowerCase().replace(/\/$/, '').replace(/\.git$/, '') !== `/${RELEASE_REPOSITORY}`) throw new DeploymentError('The source origin must point to kiansheik/kodaklumaremote on GitHub.');
  return ssh ? `git@github.com:${RELEASE_REPOSITORY}.git` : `https://github.com/${RELEASE_REPOSITORY}.git`;
}

function reviewedTree(tree) {
  for (const entry of tree.split('\0').filter(Boolean)) {
    const match = entry.match(/^(\d+) blob [a-f0-9]+\t(.+)$/);
    if (!match || match[1] !== '100644' || !files.includes(match[2])) throw new DeploymentError('The gh-pages branch contains an unexpected file. No files were overwritten.');
  }
}

export async function deployGhPages({ sourceDirectory = root, tag, run = execFile } = {}) {
  if (tag !== undefined && !/^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/.test(tag)) throw new DeploymentError('--tag must be a stable release such as v0.2.4.');
  const options = { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024, timeout: 30000, env: { ...process.env, GIT_TERMINAL_PROMPT: '0' } };
  const tool = async (command, args, message, callOptions = options) => {
    try { return await run(command, args, callOptions); } catch { throw new DeploymentError(message); }
  };
  const sourceGit = args => tool('git', ['-C', sourceDirectory, ...args], 'Could not read the source Git configuration.');
  const [origin, userName, userEmail] = await Promise.all([
    sourceGit(['remote', 'get-url', 'origin']), sourceGit(['config', 'user.name']), sourceGit(['config', 'user.email']),
  ]);
  const remote = repositoryOrigin(origin.stdout.trim());
  if (!userName.stdout.trim() || !userEmail.stdout.trim()) throw new DeploymentError('Configure a Git author name and email before deploying.');
  const endpoint = `repos/${RELEASE_REPOSITORY}/releases/${tag ? `tags/${tag}` : 'latest'}`;
  const response = await tool('gh', ['api', endpoint], 'Could not read the published release. Check gh authentication and the release tag.');
  let release;
  try { release = JSON.parse(response.stdout); } catch { throw new DeploymentError('GitHub returned invalid release metadata.'); }
  if (tag && release.tag_name !== tag) throw new DeploymentError('GitHub returned a different release tag.');
  const manifests = release.assets?.filter(asset => asset.name === 'update.json');
  if (!Array.isArray(manifests) || manifests.length !== 1 || !Number.isSafeInteger(manifests[0].id) || manifests[0].id < 1
      || !Number.isInteger(manifests[0].size) || manifests[0].size < 1 || manifests[0].size > MAX_MANIFEST_BYTES || manifests[0].state !== 'uploaded') throw new DeploymentError('The release needs one uploaded update.json of at most 16 KiB.');
  const downloaded = await tool('gh', ['api', '-H', 'Accept: application/octet-stream', `repos/${RELEASE_REPOSITORY}/releases/assets/${manifests[0].id}`], 'Could not download the release manifest.', { ...options, maxBuffer: MAX_MANIFEST_BYTES });
  if (Buffer.byteLength(downloaded.stdout, 'utf8') > MAX_MANIFEST_BYTES) throw new DeploymentError('The release manifest exceeds 16 KiB.');
  let manifest;
  try { manifest = JSON.parse(downloaded.stdout); } catch { throw new DeploymentError('The release manifest is invalid JSON.'); }
  // Releases from 0.4.9 on also carry the LUMA Receiver feed; older ones do not.
  const receiverAssets = release.assets?.filter(asset => asset.name === 'receiver-update.json') ?? [];
  let receiverManifest;
  if (receiverAssets.length > 1) throw new DeploymentError('The release has more than one receiver-update.json.');
  if (receiverAssets.length === 1) {
    const asset = receiverAssets[0];
    if (!Number.isSafeInteger(asset.id) || asset.id < 1 || !Number.isInteger(asset.size) || asset.size < 1 || asset.size > MAX_MANIFEST_BYTES || asset.state !== 'uploaded') throw new DeploymentError('The release receiver-update.json must be uploaded and at most 16 KiB.');
    const receiverDownload = await tool('gh', ['api', '-H', 'Accept: application/octet-stream', `repos/${RELEASE_REPOSITORY}/releases/assets/${asset.id}`], 'Could not download the receiver manifest.', { ...options, maxBuffer: MAX_MANIFEST_BYTES });
    try { receiverManifest = JSON.parse(receiverDownload.stdout); } catch { throw new DeploymentError('The receiver manifest is invalid JSON.'); }
  }
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'luma-gh-pages-'));
  try {
    const output = path.join(temporary, 'site'), checkout = path.join(temporary, 'checkout');
    let built;
    try { built = await buildPages({ manifest, receiverManifest, release, out: output }); }
    catch { throw new DeploymentError('The release manifest and published APK must match before deploying.'); }
    const git = (args, message = 'The temporary Git checkout could not be prepared.') => tool('git', ['-C', checkout, ...args], message);
    await tool('git', ['init', '--initial-branch=gh-pages', checkout], 'Could not create the temporary Git checkout.');
    await git(['remote', 'add', 'origin', remote]);
    await git(['config', 'user.name', userName.stdout.trim()]);
    await git(['config', 'user.email', userEmail.stdout.trim()]);
    let existing = false;
    try { await run('git', ['-C', checkout, 'ls-remote', '--exit-code', '--heads', 'origin', 'refs/heads/gh-pages'], options); existing = true; }
    catch (error) { if (error.code !== 2) throw new DeploymentError('Could not check the remote gh-pages branch.'); }
    if (existing) {
      await git(['fetch', '--depth=1', 'origin', 'refs/heads/gh-pages'], 'Could not fetch the gh-pages branch.');
      reviewedTree((await git(['ls-tree', '-r', '-z', '--full-tree', 'FETCH_HEAD'])).stdout);
      await git(['checkout', '-B', 'gh-pages', 'FETCH_HEAD']);
    }
    const published = files.filter(file => file !== 'receiver-update.json' || receiverManifest !== undefined);
    for (const file of published) await copyFile(path.join(output, file), path.join(checkout, file));
    const changed = (await git(['status', '--porcelain', '--untracked-files=all'])).stdout.trim() !== '';
    if (changed) {
      await git(['add', '--', ...published]);
      const tree = (await git(['write-tree'])).stdout.trim();
      reviewedTree((await git(['ls-tree', '-r', '-z', '--full-tree', tree])).stdout);
      await git(['commit', '-m', `Deploy Luma Remote ${built.versionName}`], 'Could not create the deployment commit.');
      await git(['push', 'origin', 'HEAD:refs/heads/gh-pages'], 'The gh-pages push failed. No force push was attempted; fetch the current branch and retry.');
    }
    return { changed, versionName: built.versionName, commit: (await git(['rev-parse', 'HEAD'])).stdout.trim() };
  } finally { await rm(temporary, { recursive: true, force: true }); }
}

async function main() {
  const { values } = parseArgs({ options: { tag: { type: 'string' } } });
  const result = await deployGhPages({ tag: values.tag });
  console.log(result.changed ? `Published Luma Remote ${result.versionName} to gh-pages.` : `gh-pages already matches Luma Remote ${result.versionName}.`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(error => {
  console.error(error instanceof DeploymentError ? error.message : 'Deployment failed before completion. Check the local Git and gh setup.'); process.exitCode = 1;
});
