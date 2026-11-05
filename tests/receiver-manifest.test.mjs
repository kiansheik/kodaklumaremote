import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';

const manifest = await readFile(new URL('../android-receiver/app/src/main/AndroidManifest.xml', import.meta.url), 'utf8');

test('receiver remains launchable and is an explicit Android Home candidate', () => {
  const activity = manifest.match(/<activity android:name="\.MainActivity"[\s\S]*?<\/activity>/u)?.[0] || '';
  const filters = [...activity.matchAll(/<intent-filter>([\s\S]*?)<\/intent-filter>/gu)].map(match => match[1]);
  assert.equal(filters.length, 2);
  assert.ok(filters.some(filter => filter.includes('android.intent.category.LAUNCHER') && filter.includes('android.intent.category.LEANBACK_LAUNCHER')));
  assert.ok(filters.some(filter => filter.includes('android.intent.category.HOME') && filter.includes('android.intent.category.DEFAULT')));
});
