import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify } from 'node:util';

// Offline, pure JVM checks. All captions are synthetic and no server/device is contacted.
const run = promisify(execFile);
const java = (name) => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', name) : name;
const output = await mkdtemp(join(tmpdir(), 'luma-subtitle-normalizer-'));
try {
  await run(java('javac'), ['--release', '17', '-d', output,
    resolve('android/app/src/main/java/dev/framextv/lumaremote/SubtitleNormalizer.java'),
    resolve('tests/java/SubtitleNormalizerTest.java')], { timeout: 30000, maxBuffer: 32768 });
  const { stdout } = await run(java('java'), ['-cp', output, 'dev.framextv.lumaremote.SubtitleNormalizerTest'], { timeout: 30000 });
  process.stdout.write(stdout);
  assert.match(stdout, /Subtitle normalizer: \d+ checks passed\./u);
} finally { await rm(output, { recursive: true, force: true }); }
