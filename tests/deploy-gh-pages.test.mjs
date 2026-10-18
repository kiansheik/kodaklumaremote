import assert from 'node:assert/strict';
import { execFile as execFileCallback } from 'node:child_process';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import { test } from 'node:test';
import { deployGhPages, repositoryOrigin } from '../scripts/deploy-gh-pages.mjs';

const execFile = promisify(execFileCallback);
const githubOrigin = 'https://github.com/kiansheik/kodaklumaremote.git';

test('deployment origin is limited to this repository over HTTPS or GitHub SSH', () => {
  for (const remote of [githubOrigin, githubOrigin.replace('.git', ''), 'git@github.com:kiansheik/kodaklumaremote.git', 'ssh://git@github.com/kiansheik/kodaklumaremote.git']) assert.match(repositoryOrigin(remote), /kiansheik\/kodaklumaremote\.git$/);
  for (const remote of ['https://github.com/other/repo.git', 'http://github.com/kiansheik/kodaklumaremote.git', `${githubOrigin}?token=x`, 'https://token@github.com/kiansheik/kodaklumaremote.git', '/tmp/local.git']) assert.throws(() => repositoryOrigin(remote));
});

test('deployment creates and fast-forwards only the seven Pages files, preserves the source checkout and refuses unknown branch files', async () => {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'luma-deploy-test-'));
  const git = async (directory, ...args) => (await execFile('git', ['-C', directory, ...args], { encoding: 'utf8' })).stdout.trim();
  try {
    const source = path.join(folder, 'source'), bare = path.join(folder, 'remote.git');
    await execFile('git', ['init', '--initial-branch=main', source]);
    await execFile('git', ['init', '--bare', '--initial-branch=gh-pages', bare]);
    await git(source, 'config', 'user.name', 'Local deployment fixture');
    await git(source, 'config', 'user.email', 'fixture@example.invalid');
    await git(source, 'remote', 'add', 'origin', githubOrigin);
    await writeFile(path.join(source, 'main.txt'), 'main stays intact');
    await git(source, 'add', 'main.txt');
    await git(source, '-c', 'commit.gpgsign=false', 'commit', '-m', 'Owned fixture source');
    await writeFile(path.join(source, 'main.txt'), 'uncommitted user work');
    const before = { head: await git(source, 'rev-parse', 'HEAD'), status: await git(source, 'status', '--porcelain') };
    const manifest = { schemaVersion: 1, packageName: 'dev.framextv.lumaremote', versionName: '0.2.4', versionCode: 6, minSdk: 23,
      apkUrl: 'https://github.com/kiansheik/kodaklumaremote/releases/download/v0.2.4/luma-remote-0.2.4.apk', sha256: 'a'.repeat(64), sizeBytes: 12345, releasedAt: '2026-10-18T18:00:00Z', notes: 'First fixture release.' };
    const release = { draft: false, prerelease: false, tag_name: 'v0.2.4', html_url: 'https://github.com/kiansheik/kodaklumaremote/releases/tag/v0.2.4', assets: [
      { id: 41, name: 'update.json', size: 1000, state: 'uploaded' },
      { name: 'luma-remote-0.2.4.apk', browser_download_url: manifest.apkUrl, size: manifest.sizeBytes, state: 'uploaded', digest: `sha256:${manifest.sha256}` },
    ] };
    const calls = [];
    const run = async (command, args, options) => {
      calls.push([command, args]);
      if (command === 'gh') return { stdout: JSON.stringify(args.at(-1).endsWith('/assets/41') ? manifest : release) };
      const mapped = args.map(arg => arg === githubOrigin ? bare : arg);
      // Keep this test independent of any developer's commit-signing configuration.
      if (mapped.includes('commit')) mapped.splice(mapped.indexOf('commit'), 0, '-c', 'commit.gpgsign=false');
      return execFile(command, mapped, options);
    };
    const initial = await deployGhPages({ sourceDirectory: source, run });
    assert.equal(initial.changed, true);
    const first = await git(bare, 'rev-parse', 'refs/heads/gh-pages');
    assert.deepEqual((await git(bare, 'ls-tree', '-r', '--name-only', first)).split('\n').sort(), ['.nojekyll', 'app.js', 'index.html', 'projector.svg', 'styles.css', 'update-manifest.js', 'update.json'].sort());
    manifest.notes = 'Updated fixture release notes.';
    const next = await deployGhPages({ sourceDirectory: source, tag: 'v0.2.4', run });
    assert.equal(next.changed, true);
    assert.equal(await git(bare, 'rev-parse', `${next.commit}^`), first);
    assert.equal((await deployGhPages({ sourceDirectory: source, run })).changed, false);
    assert.ok(calls.some(([command, args]) => command === 'gh' && args.includes('repos/kiansheik/kodaklumaremote/releases/tags/v0.2.4')));
    assert.ok(calls.filter(([command, args]) => command === 'git' && args.includes('push')).every(([, args]) => !args.some(arg => arg.startsWith('--force') || arg.startsWith('+'))));
    assert.equal(await git(source, 'rev-parse', 'HEAD'), before.head);
    assert.equal(await git(source, 'status', '--porcelain'), before.status);
    assert.equal(await readFile(path.join(source, 'main.txt'), 'utf8'), 'uncommitted user work');
    const seed = path.join(folder, 'seed');
    await execFile('git', ['clone', bare, seed]);
    await git(seed, 'config', 'user.name', 'Local deployment fixture');
    await git(seed, 'config', 'user.email', 'fixture@example.invalid');
    await writeFile(path.join(seed, 'unexpected.txt'), 'must not overwrite');
    await git(seed, 'add', 'unexpected.txt');
    await git(seed, '-c', 'commit.gpgsign=false', 'commit', '-m', 'Unexpected owned fixture file');
    await git(seed, 'push', 'origin', 'gh-pages');
    const protectedHead = await git(bare, 'rev-parse', 'gh-pages');
    await assert.rejects(deployGhPages({ sourceDirectory: source, run }), /unexpected file/);
    assert.equal(await git(bare, 'rev-parse', 'gh-pages'), protectedHead);
  } finally { await rm(folder, { recursive: true, force: true }); }
});
