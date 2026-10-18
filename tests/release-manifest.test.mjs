import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { buildPages, validateRelease } from '../scripts/build-pages.mjs';
import { apkMetadata, generateUpdate } from '../scripts/generate-update.mjs';
import { MAX_APK_BYTES, validateUpdateManifest } from '../site/update-manifest.js';

const manifest = () => ({ schemaVersion: 1, packageName: 'dev.framextv.lumaremote', versionName: '0.2.4', versionCode: 6, minSdk: 23,
  apkUrl: 'https://github.com/kiansheik/kodaklumaremote/releases/download/v0.2.4/luma-remote-0.2.4.apk',
  sha256: 'a'.repeat(64), sizeBytes: 3456789, releasedAt: '2026-10-18T18:00:00Z', notes: 'Controles e atualização pelo app.' });
const release = () => ({ draft: false, prerelease: false, tag_name: 'v0.2.4', html_url: 'https://github.com/kiansheik/kodaklumaremote/releases/tag/v0.2.4', assets: [{ name: 'luma-remote-0.2.4.apk', browser_download_url: manifest().apkUrl, size: manifest().sizeBytes, state: 'uploaded', digest: `sha256:${manifest().sha256}` }] });

test('update metadata accepts the fixed package/repository and rejects malformed or substituted downloads', () => {
  assert.deepEqual(validateUpdateManifest(manifest()), manifest());
  const invalid = [null, [], { schemaVersion: 2 }, { packageName: 'other.app' }, { versionName: '0.2.4-beta' },
    { versionName: '../0.2.4' }, { versionCode: 0 }, { versionCode: 6.1 }, { minSdk: 22 },
    { apkUrl: manifest().apkUrl.replace('github.com', 'github.com.attacker.example') },
    { apkUrl: `${manifest().apkUrl}?download=1` }, { apkUrl: manifest().apkUrl.replace('v0.2.4/', 'v0.2.5/') },
    { sha256: 'A'.repeat(64) }, { sha256: 'a'.repeat(63) }, { sizeBytes: 0 }, { sizeBytes: MAX_APK_BYTES + 1 },
    { notes: 'x'.repeat(2001) }, { notes: 'bad\u0000note' }, { releasedAt: '2026-02-31T18:00:00Z' }, { releasedAt: 'today' }];
  for (const change of invalid) assert.throws(() => validateUpdateManifest(change === null || Array.isArray(change) ? change : { ...manifest(), ...change }), /Invalid release manifest/);
});

test('Pages requires the published stable release, exact asset size and any GitHub-provided digest', () => {
  validateRelease(manifest(), release());
  for (const patch of [{ draft: true }, { prerelease: true }, { tag_name: 'v0.2.3' }, { html_url: 'https://github.com/other/repo/releases/tag/v0.2.4' }]) assert.throws(() => validateRelease(manifest(), { ...release(), ...patch }));
  for (const patch of [{ size: 2 }, { digest: `sha256:${'b'.repeat(64)}` }, { browser_download_url: 'https://example.org/app.apk' }, { state: 'new' }]) {
    const changed = release(); Object.assign(changed.assets[0], patch); assert.throws(() => validateRelease(manifest(), changed));
  }
  const noDigest = release(); delete noDigest.assets[0].digest;
  validateRelease(manifest(), noDigest);
});

test('manifest generation verifies the signer before deriving actual APK metadata, bytes and hash', async () => {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'luma-update-'));
  try {
    const apk = path.join(folder, 'signed.apk'), contents = Buffer.from('owned static APK fixture');
    await writeFile(apk, contents);
    const calls = [], certificate = 'c'.repeat(64);
    const run = async (command, args) => { calls.push([command, args]); return { stdout: command === 'signer' ? `Verifies\nSigner #1 certificate SHA-256 digest: ${certificate}\n` : "package: name='dev.framextv.lumaremote' versionCode='6' versionName='0.2.4'\nsdkVersion:'23'\n" }; };
    const settings = { apk, aapt2: 'badging', apksigner: 'signer', releasedAt: manifest().releasedAt, notes: 'Nova versão.', expectedCertSha256: certificate };
    const actual = await generateUpdate(settings, run);
    assert.equal(actual.sha256, createHash('sha256').update(contents).digest('hex'));
    assert.equal(actual.sizeBytes, contents.length);
    assert.equal(actual.versionCode, 6);
    assert.deepEqual(calls.map(([command]) => command), ['signer', 'badging']);
    assert.deepEqual(calls[0][1], ['verify', '--verbose', '--print-certs', apk]);
    await assert.rejects(generateUpdate({ ...settings, expectedCertSha256: 'd'.repeat(64) }, run), /signer does not match/);
    const rejectedCalls = [];
    await assert.rejects(generateUpdate(settings, async command => { rejectedCalls.push(command); throw new Error('unsigned'); }), /signature verification failed/);
    assert.deepEqual(rejectedCalls, ['signer']);
    assert.throws(() => apkMetadata("package: name='dev.framextv.lumaremote'"), /metadata is missing/);
  } finally { await rm(folder, { recursive: true, force: true }); }
});

test('APK metadata accepts modern and legacy minimum SDK fields but rejects duplicate or conflicting declarations', () => {
  const header = "package: name='dev.framextv.lumaremote' versionCode='6' versionName='0.2.4'\n";
  const expected = { packageName: 'dev.framextv.lumaremote', versionName: '0.2.4', versionCode: 6, minSdk: 23 };
  assert.deepEqual(apkMetadata(`${header}minSdkVersion:'23'\n`), expected);
  assert.deepEqual(apkMetadata(`${header}sdkVersion:'23'\n`), expected);
  assert.deepEqual(apkMetadata(`${header}minSdkVersion:'23'\r\n`), expected);
  for (const fields of ["minSdkVersion:'23'\nsdkVersion:'24'", "minSdkVersion:'23'\nsdkVersion:'23'", "sdkVersion:'23'\nsdkVersion:'23'", "minSdkVersion:'23'\nminSdkVersion:'23'", "minSdkVersion:'not-a-number'", "minSdkVersion:'23'\nsdkVersion:'not-a-number'"]) {
    assert.throws(() => apkMetadata(`${header}${fields}\n`), /metadata is missing/);
  }
});

test('Pages bundles only the reviewed site files and metadata, refusing unexpected output files', async () => {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'luma-pages-'));
  try {
    const out = path.join(folder, 'pages');
    await buildPages({ manifest: manifest(), release: release(), out });
    assert.deepEqual((await readdir(out)).sort(), ['.nojekyll', 'app.js', 'index.html', 'projector.svg', 'styles.css', 'update-manifest.js', 'update.json'].sort());
    assert.deepEqual(JSON.parse(await readFile(path.join(out, 'update.json'), 'utf8')), manifest());
    await writeFile(path.join(out, 'private-note.txt'), 'must not publish');
    await assert.rejects(buildPages({ manifest: manifest(), release: release(), out }), /unexpected file/);
  } finally { await rm(folder, { recursive: true, force: true }); }
});
