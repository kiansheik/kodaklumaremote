import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { homedir, tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

// Offline JVM tests. Uses the same pinned ZXing core as the Android dependency;
// never opens a camera, pairs a device or downloads an artifact.
const run = promisify(execFile);
let jar = process.env.ZXING_JAR;
if (!jar) {
  const module = join(homedir(), '.gradle/caches/modules-2/files-2.1/com.google.zxing/core/3.5.4');
  for (const hash of await readdir(module).catch(() => [])) {
    const candidate = join(module, hash, 'core-3.5.4.jar');
    if (existsSync(candidate)) { jar = candidate; break; }
  }
}
assert.ok(jar && existsSync(jar), 'Build Android once to cache ZXing 3.5.4, or set ZXING_JAR to its local core JAR.');
const java = (name) => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', name) : name;
const output = await mkdtemp(join(tmpdir(), 'luma-matter-qr-'));
try {
  await run(java('javac'), ['--release', '17', '-cp', resolve(jar), '-d', output,
    resolve('android/app/src/main/java/dev/framextv/lumaremote/MatterQrDecoder.java'),
    resolve('tests/java/MatterQrDecoderTest.java')], { timeout: 30000, maxBuffer: 32768 });
  const { stdout } = await run(java('java'), ['-cp', `${output}:${resolve(jar)}`, 'dev.framextv.lumaremote.MatterQrDecoderTest'], { timeout: 30000 });
  process.stdout.write(stdout);
  assert.match(stdout, /Matter QR decoder: 7 checks passed\./u);
} finally { await rm(output, { recursive: true, force: true }); }
