import {execFile} from 'node:child_process';
import {promisify} from 'node:util';
import {homedir} from 'node:os';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

// Isolated emulator fixtures. Live source capture is a separate explicit opt-in;
// no mode sends TV controls or changes real viewing history.
const run = promisify(execFile);
const sdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
const adb = process.env.ADB || resolve(sdk, 'platform-tools/adb');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
const packageName = 'dev.framextv.lumaremote.receiverfixture';
if (!/^emulator-\d+$/u.test(serial)) throw new Error('Use the isolated emulator.');
for (const [file, expected] of [['android/app/build/outputs/apk/debug/app-debug.apk', packageName], ['android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk', `${packageName}.test`]]) {
  const apk = resolve(file);
  const {stdout} = await run(resolve(sdk, 'build-tools/36.0.0/aapt2'), ['dump', 'badging', apk], {timeout: 10000});
  assert.equal(stdout.match(/^package: name='([^']+)'/mu)?.[1], expected);
  await run(adb, ['-s', serial, 'install', '-r', apk], {timeout: 60000});
}
const modes = [['catalog', 5], ['watch-history', 6], ['library', 6], ['tv-catalog', 5], ['web-content', 4]];
if (process.env.LUMA_LIVE_WEB_CONTENT === '1') modes.push(['web-content-live', 1]);
for (const [mode, count] of modes) {
  const {stdout, stderr} = await run(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'mode', mode, `${packageName}.test/dev.framextv.lumaremote.BackendInstrumentation`], {timeout: 90000, maxBuffer: 128 * 1024});
  process.stdout.write(stdout);
  if (stderr) process.stderr.write(stderr);
  assert.match(stdout, /INSTRUMENTATION_RESULT: failures=0/u);
  assert.match(stdout, new RegExp(`INSTRUMENTATION_RESULT: passed=${count}\\b`, 'u'));
  assert.match(stdout, /INSTRUMENTATION_CODE: -1/u);
}
console.log(`Catalog/history/offline Library/TV catalog/resolver native groups passed${process.env.LUMA_LIVE_WEB_CONTENT === '1' ? ', including explicit fixed-title live source capture' : ' without live network'}. No TV actions sent.`);
