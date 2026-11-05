import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { loadReleaseConfig } from './release-config.mjs';

const root = fileURLToPath(new URL('../', import.meta.url));

function run(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: root, env: process.env, stdio: 'inherit', shell: false });
    child.once('error', reject);
    child.once('exit', code => code === 0 ? resolve() : reject(new Error('Android release build failed.')));
  });
}

export async function buildLatestApks() {
  const config = await loadReleaseConfig();
  if (!config) throw new Error('Missing .luma-release.json. Run make configure-release once.');
  const gradle = process.env.LUMA_GRADLE_BIN || 'gradle';
  await run(gradle, ['--offline', '--no-daemon', '-p', 'android', ':app:assembleRelease']);
  await run(gradle, ['--offline', '--no-daemon', '-p', 'android-receiver', ':app:assembleRelease']);
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  buildLatestApks().catch(error => { console.error(error instanceof Error ? error.message : 'Android release build failed.'); process.exitCode = 1; });
}
