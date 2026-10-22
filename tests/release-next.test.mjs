import assert from 'node:assert/strict';
import { test } from 'node:test';
import { androidVersion, nextPatch, releasePlan, releaseRelevant, untrackedReleaseDisposition } from '../scripts/release-next-android.mjs';

test('next Android release increments only the patch and version code', () => {
  assert.equal(nextPatch('0.4.6'), '0.4.7');
  assert.throws(() => nextPatch('0.4.9007199254740991'), /cannot be incremented safely/);
  assert.deepEqual(releasePlan({ current: {name:'0.4.6',code:16}, latest: {versionName:'0.4.6',versionCode:16}, hasReleaseChanges:true }),
    {kind:'release',name:'0.4.7',code:17,bump:true});
  assert.deepEqual(androidVersion("versionCode 17\nversionName '0.4.7'"), {name:'0.4.7',code:17});
  assert.throws(() => releasePlan({current:{name:'1.0.0',code:2147483647},latest:{versionName:'1.0.0',versionCode:2147483647},hasReleaseChanges:true}), /cannot be incremented safely/);
});

test('prepared release retries keep their version and an unchanged release only redeploys Pages', () => {
  const latest = {versionName:'0.4.6',versionCode:16};
  assert.deepEqual(releasePlan({current:{name:'0.4.7',code:17},latest,hasReleaseChanges:true}),
    {kind:'release',name:'0.4.7',code:17,bump:false});
  assert.deepEqual(releasePlan({current:{name:'0.4.6',code:16},latest,hasReleaseChanges:false}),
    {kind:'page',name:'0.4.6',code:16});
  assert.throws(() => releasePlan({current:{name:'0.4.8',code:17},latest,hasReleaseChanges:true}), /must be 0\.4\.7\/code 17/);
  assert.throws(() => releasePlan({current:{name:'0.4.7',code:18},latest,hasReleaseChanges:true}), /must be 0\.4\.7\/code 17/);
});

test('agent-only release records do not create another APK version', () => {
  assert.equal(releaseRelevant('docs/agent/log.md'), false);
  assert.equal(releaseRelevant('docs/agent/session-handoffs/release.md'), false);
  assert.equal(releaseRelevant('docs/releasing.md'), false);
  assert.equal(releaseRelevant('release-notes/0.4.7.txt'), false);
  for (const file of ['src/mobile/app.js','android/app/build.gradle','Makefile']) assert.equal(releaseRelevant(file), true);
});

test('new reviewed text files auto-stage while unrelated and sensitive files do not', () => {
  for (const file of [
    'docs/agent/session-handoffs/release.md',
    'scripts/release-config.mjs',
    'tests/release-next.test.mjs',
    'release-notes/0.4.7.txt',
    'android/app/src/main/java/example/Feature.java',
  ]) assert.equal(untrackedReleaseDisposition(file), 'stage');
  assert.equal(untrackedReleaseDisposition('headers.txt'), 'ignore');
  for (const file of [
    '.env',
    'docs/private-credentials.md',
    'android/app/release.keystore',
    'android/local.properties',
    'scripts/generated.bin',
    '../outside.mjs',
  ]) assert.equal(untrackedReleaseDisposition(file), 'reject');
});
