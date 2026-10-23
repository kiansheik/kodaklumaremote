import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { buildPages, validateRelease } from '../scripts/build-pages.mjs';
import { apkMetadata, generateUpdate } from '../scripts/generate-update.mjs';
import { releaseAssetPaths, releaseCreateArguments } from '../scripts/publish-android-release.mjs';
import { MAX_APK_BYTES, validateReceiverManifest, validateUpdateManifest } from '../site/update-manifest.js';

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

const receiverManifest = () => ({ schemaVersion: 1, packageName: 'dev.framextv.lumareceiver', versionName: '0.1.4', versionCode: 6, minSdk: 23,
  apkUrl: 'https://github.com/kiansheik/kodaklumaremote/releases/download/v0.2.4/luma-receiver-0.1.4.apk',
  sha256: 'b'.repeat(64), sizeBytes: 1179435, releasedAt: '2026-10-18T18:00:00Z', notes: 'Receiver.' });
const releaseWithReceiver = () => {
  const value = release();
  value.assets.push({ name: 'luma-receiver-0.1.4.apk', browser_download_url: receiverManifest().apkUrl, size: receiverManifest().sizeBytes, state: 'uploaded', digest: `sha256:${'b'.repeat(64)}` });
  return value;
};

test('LUMA Receiver feed is tied to the phone release tag and its own package', () => {
  assert.deepEqual(validateReceiverManifest(receiverManifest(), 'v0.2.4'), receiverManifest());
  for (const change of [{ packageName: 'dev.framextv.lumaremote' }, { apkUrl: receiverManifest().apkUrl.replace('v0.2.4', 'v0.2.5') },
    { apkUrl: receiverManifest().apkUrl.replace('luma-receiver-0.1.4', 'luma-receiver-0.1.3') }, { minSdk: 20 }])
    assert.throws(() => validateReceiverManifest({ ...receiverManifest(), ...change }, 'v0.2.4'), /Invalid release manifest/);
  assert.throws(() => validateReceiverManifest(receiverManifest(), '0.2.4'), /Invalid release manifest/);
  assert.throws(() => validateUpdateManifest(receiverManifest()), /Invalid release manifest/);
});

test('receiver manifest generation requires the receiver package and the release tag URL', async () => {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'luma-receiver-update-'));
  try {
    const apk = path.join(folder, 'receiver.apk');
    await writeFile(apk, Buffer.from('receiver fixture'));
    const certificate = 'c'.repeat(64);
    const run = name => async command => ({ stdout: command === 'signer' ? `Signer #1 certificate SHA-256 digest: ${certificate}\n` : `package: name='${name}' versionCode='6' versionName='0.1.4'\nsdkVersion:'23'\n` });
    const settings = { apk, aapt2: 'badging', apksigner: 'signer', releasedAt: receiverManifest().releasedAt, expectedCertSha256: certificate, receiverTag: 'v0.2.4' };
    const actual = await generateUpdate(settings, run('dev.framextv.lumareceiver'));
    assert.equal(actual.apkUrl, receiverManifest().apkUrl);
    await assert.rejects(generateUpdate(settings, run('dev.framextv.lumaremote')), /does not match LUMA Receiver/);
  } finally { await rm(folder, { recursive: true, force: true }); }
});

test('Pages publishes receiver-update.json only when the release carries a matching receiver APK', async () => {
  const folder = await mkdtemp(path.join(os.tmpdir(), 'luma-pages-receiver-'));
  try {
    const out = path.join(folder, 'pages');
    const built = await buildPages({ manifest: manifest(), receiverManifest: receiverManifest(), release: releaseWithReceiver(), out });
    assert.equal(built.receiverVersionName, '0.1.4');
    assert.deepEqual(JSON.parse(await readFile(path.join(out, 'receiver-update.json'), 'utf8')), receiverManifest());
    const wrongSize = releaseWithReceiver(); wrongSize.assets[1].size = 1;
    await assert.rejects(buildPages({ manifest: manifest(), receiverManifest: receiverManifest(), release: wrongSize, out: path.join(folder, 'other') }), /receiver APK does not match/);
  } finally { await rm(folder, { recursive: true, force: true }); }
});

test('publication uploads the phone and receiver APKs with both manifests', () => {
  const assets = releaseAssetPaths('/tmp/release', { versionName: '0.2.4' }, { versionName: '0.1.4' });
  assert.deepEqual(assets.map(file => path.basename(file)), ['luma-remote-0.2.4.apk', 'update.json', 'luma-receiver-0.1.4.apk', 'receiver-update.json']);
  assert.throws(() => releaseCreateArguments({ tag: 'v0.2.4', assets: assets.slice(0, 3), head: 'h', versionName: '0.2.4', notesFile: 'n' }), /must upload/);
  assert.ok(releaseCreateArguments({ tag: 'v0.2.4', assets, head: 'h', versionName: '0.2.4', notesFile: 'n' }).includes(assets[2]));
});
