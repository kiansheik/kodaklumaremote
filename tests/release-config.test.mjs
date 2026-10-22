import assert from 'node:assert/strict';
import { chmod, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { parseReleaseConfig, readReleaseConfig } from '../scripts/release-config.mjs';

const valid = {
  ANDROID_HOME: '/opt/android sdk',
  JAVA_HOME: '/opt/jdk',
  LUMA_GRADLE_BIN: '/opt/gradle/bin/gradle',
  LUMA_KEYSTORE_PATH: '/private/keys/release.keystore',
  LUMA_KEYSTORE_PASSWORD: "spaces # $ ' and double-quote \" stay literal",
  LUMA_KEY_ALIAS: 'release',
  LUMA_KEY_PASSWORD: 'another$secret#value',
};

test('local release config preserves literal secrets and requires absolute paths', () => {
  assert.deepEqual(parseReleaseConfig(JSON.stringify(valid)), valid);
  assert.throws(() => parseReleaseConfig(JSON.stringify({...valid, ANDROID_HOME:'relative/sdk'})), /absolute path/);
  assert.throws(() => parseReleaseConfig(JSON.stringify({...valid, EXTRA:'no'})), /unknown setting/);
  const missing = {...valid};
  delete missing.LUMA_KEY_PASSWORD;
  assert.throws(() => parseReleaseConfig(JSON.stringify(missing)), /missing LUMA_KEY_PASSWORD/);
});

test('local release config must be a private regular file', async () => {
  const folder = await mkdtemp(path.join(tmpdir(), 'luma-release-config-'));
  try {
    const file = path.join(folder, 'config.json');
    await writeFile(file, JSON.stringify(valid), { mode: 0o600 });
    assert.deepEqual(await readReleaseConfig(file), valid);
    await chmod(file, 0o644);
    await assert.rejects(() => readReleaseConfig(file), /mode 0600/);
    const link = path.join(folder, 'link.json');
    await symlink(file, link);
    await assert.rejects(() => readReleaseConfig(link), /regular file/);
  } finally { await rm(folder, { recursive:true, force:true }); }
});
