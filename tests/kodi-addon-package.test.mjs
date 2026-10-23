import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {
  createDeterministicZip,
  inspectDeterministicZip,
  KODI_ADDON_FILES,
  KODI_ADDON_ID,
  kodiAddonManifest,
  packageKodiAddon,
  validateKodiAddonZip,
  validatePreparedKodiAddonAsset,
} from '../scripts/package-kodi-addon.mjs';
import {
  KODI_ADDON_GIT_PATHS,
  releaseAssetPaths,
  releaseCreateArguments,
  verifyCommittedKodiAddon,
} from '../scripts/publish-android-release.mjs';

const sourceRoot = path.resolve('kodi-addon', KODI_ADDON_ID);
const expectedPaths = KODI_ADDON_FILES.map(file => `${KODI_ADDON_ID}/${file}`).sort();

async function sourceZipEntries() {
  return Promise.all(KODI_ADDON_FILES.map(async file => ({
    name: `${KODI_ADDON_ID}/${file}`,
    data: await readFile(path.join(sourceRoot, ...file.split('/'))),
  })));
}

test('Kodi add-on package is deterministic, exact, and ready as a release asset', async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'luma-kodi-package-'));
  try {
    const firstDirectory = path.join(temporary, 'first');
    const secondDirectory = path.join(temporary, 'second');
    const first = await packageKodiAddon({ outputDirectory: firstDirectory });
    const second = await packageKodiAddon({ outputDirectory: secondDirectory });
    assert.equal(first.assetName, 'script.lumaremote.metadata-1.0.0.zip');
    assert.equal(first.sha256, second.sha256);
    assert.deepEqual(first.entries, expectedPaths);
    assert.deepEqual(await readFile(first.path), await readFile(second.path));
    assert.deepEqual(inspectDeterministicZip(await readFile(first.path)).map(entry => entry.name), expectedPaths);
    const sourceEntries = await sourceZipEntries();
    assert.deepEqual(createDeterministicZip(sourceEntries), createDeterministicZip([...sourceEntries].reverse()));

    const prepared = await validatePreparedKodiAddonAsset(firstDirectory);
    assert.equal(prepared.path, first.path);
    assert.deepEqual(releaseAssetPaths(firstDirectory, { versionName: '0.4.8' }, prepared).map(file => path.basename(file)), [
      'luma-remote-0.4.8.apk',
      'update.json',
      'script.lumaremote.metadata-1.0.0.zip',
    ]);
    assert.throws(() => releaseAssetPaths(firstDirectory, { versionName: '0.4.8' }, { ...prepared, path: second.path }), /outside the release directory/u);
    const assets = releaseAssetPaths(firstDirectory, { versionName: '0.4.8' }, prepared);
    assert.deepEqual(releaseCreateArguments({
      tag: 'v0.4.8', assets, head: 'a'.repeat(40), versionName: '0.4.8', notesFile: path.join(firstDirectory, 'notes.txt'),
    }), ['release', 'create', 'v0.4.8', ...assets, '--repo', 'kiansheik/kodaklumaremote', '--target', 'a'.repeat(40),
      '--title', 'LUMA Remote 0.4.8', '--notes-file', path.join(firstDirectory, 'notes.txt'), '--draft']);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('Kodi add-on validator rejects extra, corrupt, unsafe, and symlinked archives', async () => {
  const temporary = await mkdtemp(path.join(os.tmpdir(), 'luma-kodi-invalid-'));
  try {
    const entries = await sourceZipEntries();
    const extra = path.join(temporary, 'extra.zip');
    await writeFile(extra, createDeterministicZip([...entries, {
      name: `${KODI_ADDON_ID}/unexpected.py`, data: Buffer.from('pass\n'),
    }]));
    await assert.rejects(validateKodiAddonZip(extra), /missing or extra files/u);

    const missing = path.join(temporary, 'missing.zip');
    await writeFile(missing, createDeterministicZip(entries.slice(0, -1)));
    await assert.rejects(validateKodiAddonZip(missing), /missing or extra files/u);

    const stale = path.join(temporary, 'stale.zip');
    await writeFile(stale, createDeterministicZip(entries.map((entry, index) => index === 0
      ? { ...entry, data: Buffer.concat([entry.data, Buffer.from('\n')]) } : entry)));
    await assert.rejects(validateKodiAddonZip(stale), /does not exactly match/u);

    const valid = path.join(temporary, 'valid.zip');
    await writeFile(valid, createDeterministicZip(entries));
    const corruptBytes = await readFile(valid);
    const firstNameLength = corruptBytes.readUInt16LE(26);
    corruptBytes[30 + firstNameLength] ^= 1;
    const corrupt = path.join(temporary, 'corrupt.zip');
    await writeFile(corrupt, corruptBytes);
    await assert.rejects(validateKodiAddonZip(corrupt), /checksum/u);

    assert.throws(() => createDeterministicZip([{ name: '../payload.py', data: Buffer.from('x') }]), /unique and relative/u);
    assert.throws(() => createDeterministicZip([
      { name: 'same.py', data: Buffer.from('x') },
      { name: 'same.py', data: Buffer.from('y') },
    ]), /unique and relative/u);

    const linked = path.join(temporary, 'linked.zip');
    await symlink(valid, linked);
    await assert.rejects(validateKodiAddonZip(linked), /bounded regular file/u);
  } finally {
    await rm(temporary, { recursive: true, force: true });
  }
});

test('Kodi manifest parsing cannot be confused by similarly named or duplicate attributes', () => {
  assert.deepEqual(kodiAddonManifest(`<addon provider-id="wrong" id="${KODI_ADDON_ID}" version="2.3.4"></addon>`), {
    id: KODI_ADDON_ID,
    version: '2.3.4',
    assetName: `${KODI_ADDON_ID}-2.3.4.zip`,
  });
  assert.throws(() => kodiAddonManifest(`<addon id="${KODI_ADDON_ID}" id="${KODI_ADDON_ID}" version="1.0.0"></addon>`), /exactly once/u);
  assert.throws(() => kodiAddonManifest(`<addon provider-id="${KODI_ADDON_ID}" version="1.0.0"></addon>`), /declare id exactly once/u);
  assert.throws(() => kodiAddonManifest(`<addon id="${KODI_ADDON_ID}" version="${'9'.repeat(40)}.0.0"></addon>`), /invalid version/u);
});

test('publication proves every packaged Kodi source path is tracked and unchanged', async () => {
  const calls = [];
  await verifyCommittedKodiAddon(async (command, args) => { calls.push([command, args]); return { stdout: '' }; }, '/fixture');
  assert.deepEqual(calls, [
    ['git', ['ls-files', '--error-unmatch', '--', ...KODI_ADDON_GIT_PATHS]],
    ['git', ['diff', '--quiet', 'HEAD', '--', ...KODI_ADDON_GIT_PATHS]],
  ]);
  await assert.rejects(verifyCommittedKodiAddon(async () => { throw new Error('missing'); }), /must be committed/u);
  let invocation = 0;
  await assert.rejects(verifyCommittedKodiAddon(async () => {
    invocation += 1;
    if (invocation === 2) throw new Error('dirty');
    return { stdout: '' };
  }), /exactly match HEAD/u);
});
