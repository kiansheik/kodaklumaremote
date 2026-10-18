import assert from 'node:assert/strict';
import http from 'node:http';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { homedir } from 'node:os';

// Explicit emulator-only updater fixtures. No installer or projector commands.
const run = promisify(execFile);
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
const apkPath = resolve('android/app/build/outputs/apk/debug/app-debug.apk');
const apk = await readFile(apkPath);
const sha256 = createHash('sha256').update(apk).digest('hex');
const gradle = await readFile('android/app/build.gradle', 'utf8');
const versionName = gradle.match(/versionName\s+['"]([^'"]+)['"]/u)?.[1];
const versionCode = Number(gradle.match(/versionCode\s+(\d+)/u)?.[1]);
assert.ok(versionName && Number.isInteger(versionCode) && versionCode > 0);
const requests = [];
const server = http.createServer((request, response) => {
  requests.push(request.url);
  if (request.url === '/update.apk') {
    response.writeHead(200, { 'Content-Type': 'application/vnd.android.package-archive', 'Content-Length': apk.length }); response.end(apk); return;
  }
  if (request.url === '/redirect.apk') { response.writeHead(302, { Location: 'http://example.invalid/untrusted.apk' }); response.end(); return; }
  if (request.url === '/oversized.json') { response.writeHead(200); response.end(' '.repeat(32769)); return; }
  if (request.url !== '/update.json') { response.writeHead(404); response.end(); return; }
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify({ schemaVersion: 1, packageName: 'dev.framextv.lumaremote', versionName, versionCode, minSdk: 23,
    apkUrl: `http://10.0.2.2:${server.address().port}/update.apk`, sha256, sizeBytes: apk.length,
    releasedAt: '2026-10-18T12:00:00.000Z', notes: 'Emulator update fixture.' }));
});
await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
try {
  for (const path of [apkPath, resolve('android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')]) {
    await run(adb, ['-s', serial, 'install', '-r', path], { timeout: 60000 });
  }
  const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'updates',
    '-e', 'manifestUrl', `http://10.0.2.2:${server.address().port}/update.json`, '-e', 'sha256', sha256, '-e', 'sizeBytes', String(apk.length),
    'dev.framextv.lumaremote.test/dev.framextv.lumaremote.BackendInstrumentation'], { timeout: 90000, maxBuffer: 65536 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u); assert.match(stdout, /INSTRUMENTATION_RESULT: passed=6/u); assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
  assert.deepEqual(requests, ['/update.json', '/update.apk', '/oversized.json', '/redirect.apk']);
  console.log('Android updater: 6 schema, HTTP, archive/signing, provider and isolation checks passed against emulator-only fixtures. No installer opened.');
} finally {
  server.closeAllConnections?.(); await new Promise(done => server.close(done));
}
