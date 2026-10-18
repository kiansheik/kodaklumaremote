import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const run = promisify(execFile);
const sdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
const adb = process.env.ADB || resolve(sdk, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
const applicationId = 'dev.framextv.lumaremote.receiverfixture';
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Use the isolated Android emulator.');
for (const [file, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', applicationId], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${applicationId}.test`]]) {
  const apk = resolve(file);
  const { stdout } = await run(resolve(sdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', apk], { timeout: 10000 });
  assert.equal(stdout.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build with -PreceiverFixture=true before running.');
  await run(adb, ['-s', serial, 'install', '-r', apk], { timeout: 60000 });
}
const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'matter', `${applicationId}.test/dev.framextv.lumaremote.BackendInstrumentation`], { timeout: 90000, maxBuffer: 128 * 1024 });
process.stdout.write(stdout);
if (stderr) process.stderr.write(stderr);
assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u);
assert.match(stdout, /INSTRUMENTATION_RESULT: passed=7/u);
assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
console.log('Matter native: 7 isolated JNI, trust, encrypted persistence, QR, cancellation and offline-recovery checks passed. No TV was paired or controlled.');
