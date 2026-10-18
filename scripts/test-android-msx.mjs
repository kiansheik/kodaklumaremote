import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

// Strict emulator-only native LAN listener fixtures; no TV discovery or controls.
const run = promisify(execFile);
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`, 'platform-tools/adb');
const sdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
const applicationId = process.env.ANDROID_TEST_PACKAGE || 'dev.framextv.lumaremote.receiverfixture';
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
if (!/^dev\.framextv\.lumaremote(?:\.receiverfixture)?$/u.test(applicationId)) throw new Error('Select the scoped receiver fixture package.');
for (const [apk, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', applicationId], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${applicationId}.test`]]) {
  const { stdout: metadata } = await run(resolve(sdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', resolve(apk)], { timeout: 10000 });
  assert.equal(metadata.match(/^package: name='([^']+)'/mu)?.[1], expected, 'Build the intended fixture package before installing.');
  await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
}
const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'msx', `${applicationId}.test/dev.framextv.lumaremote.BackendInstrumentation`], { timeout: 60000, maxBuffer: 128 * 1024 });
process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u);
assert.match(stdout, /INSTRUMENTATION_RESULT: passed=7/u);
assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
console.log('MSX native: 7 private listener, explicit pairing, command acknowledgement, cancellation and persistence checks passed.');
