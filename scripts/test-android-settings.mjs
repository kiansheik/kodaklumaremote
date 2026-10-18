import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { homedir } from 'node:os';

// The fixture wipes only this app's test settings on an explicit emulator.
const run = promisify(execFile);
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
for (const apk of ['android/app/build/outputs/apk/debug/app-debug.apk', 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
  await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
}
const { stdout } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'settings', 'dev.framextv.lumaremote.test/dev.framextv.lumaremote.BackendInstrumentation'], { timeout: 60000, maxBuffer: 32768 });
process.stdout.write(stdout);
assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u);
assert.match(stdout, /INSTRUMENTATION_RESULT: passed=5/u);
assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
console.log('Saved settings: 5 real Android Keystore, persistence, validation and reset checks passed.');
