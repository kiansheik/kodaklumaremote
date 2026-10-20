import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { mkdtemp, rm, access } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

// Pure JVM + explicitly loopback-only HTTP fixtures; no ADB or physical receiver.
const run = promisify(execFile);
const bundled = '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin';
const bin = async name => { const candidate = join(bundled, name); try { await access(candidate); return candidate; } catch { return name; } };
const output = await mkdtemp(join(tmpdir(), 'luma-subtitle-delivery-'));
try {
  await run(await bin('javac'), ['--release', '17', '-d', output,
    resolve('android/app/src/main/java/dev/framextv/lumaremote/SubtitleNormalizer.java'),
    resolve('android/app/src/main/java/dev/framextv/lumaremote/SubtitleDelivery.java'),
    resolve('android/backend-tests/SubtitleDeliveryTest.java')], { timeout: 30000 });
  const { stdout, stderr } = await run(await bin('java'), ['-cp', output, 'dev.framextv.lumaremote.SubtitleDeliveryTest'], { timeout: 30000 });
  process.stdout.write(stdout); if (stderr) process.stderr.write(stderr);
} finally { await rm(output, { recursive: true, force: true }); }
