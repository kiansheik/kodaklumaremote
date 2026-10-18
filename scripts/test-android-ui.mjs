import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { resolve } from 'node:path';
import { homedir } from 'node:os';
import { mkdir } from 'node:fs/promises';

// Explicit emulator-only startup/bridge/token checks. No projector is contacted.
const run = promisify(execFile);
const adb = process.env.ADB || resolve(process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Select an Android emulator test target.');
for (const apk of ['android/app/build/outputs/apk/debug/app-debug.apk', 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']) {
  await run(adb, ['-s', serial, 'install', '-r', resolve(apk)], { timeout: 60000 });
}
const { stdout, stderr } = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', 'ui', 'dev.framextv.lumaremote.test/dev.framextv.lumaremote.BackendInstrumentation'], { timeout: 60000, maxBuffer: 128 * 1024 });
process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u);
assert.match(stdout, /INSTRUMENTATION_RESULT: passed=5/u);
assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
if (stdout.includes('INSTRUMENTATION_RESULT: screenshot=')) {
  await mkdir('.local', { recursive: true });
  await run(adb, ['-s', serial, 'pull', '/sdcard/Android/data/dev.framextv.lumaremote/files/mobile-ui-smoke.png', resolve('.local/mobile-ui-smoke.png')], { timeout: 10000 });
  console.log('Saved .local/mobile-ui-smoke.png');
}
console.log('Packaged Android UI: 5 emulator smoke checks passed.');
