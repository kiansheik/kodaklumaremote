import { constants } from 'node:fs';
import { access, chmod, lstat, readdir, rename, stat, unlink, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import path from 'node:path';
import { createInterface } from 'node:readline/promises';
import { Writable } from 'node:stream';
import { pathToFileURL } from 'node:url';
import {
  parseReleaseConfig,
  readReleaseConfig,
  RELEASE_CONFIG_FILE,
  ReleaseConfigError,
} from './release-config.mjs';

async function available(file, kind = 'file') {
  try {
    const info = await stat(file);
    if (kind === 'directory') return info.isDirectory();
    if (!info.isFile()) return false;
    await access(file, kind === 'executable' ? constants.X_OK : constants.R_OK);
    return true;
  } catch { return false; }
}

async function detectedGradle() {
  const base = path.join(homedir(), '.gradle/wrapper/dists/gradle-9.6.1-bin');
  let folders = [];
  try { folders = await readdir(base, { withFileTypes: true }); } catch {}
  for (const folder of folders.filter(entry => entry.isDirectory())) {
    const candidate = path.join(base, folder.name, 'gradle-9.6.1/bin/gradle');
    if (await available(candidate, 'executable')) return candidate;
  }
  return '';
}

async function ask(label, current = '') {
  const suffix = current ? ` [${current}]` : '';
  const prompt = createInterface({ input: process.stdin, output: process.stdout });
  try { return (await prompt.question(`${label}${suffix}: `)).trim() || current; }
  finally { prompt.close(); }
}

async function askSecret(label, current = '') {
  process.stdout.write(`${label}${current ? ' [leave blank to keep saved value]' : ''}: `);
  const muted = new Writable({ write(_chunk, _encoding, done) { done(); } });
  const prompt = createInterface({ input: process.stdin, output: muted, terminal: true });
  try { return await prompt.question('') || current; }
  finally { prompt.close(); process.stdout.write('\n'); }
}

async function requirePath(label, value, kind = 'file') {
  if (!path.isAbsolute(value) || !await available(value, kind)) throw new ReleaseConfigError(`${label} must be an existing absolute ${kind} path.`);
}

export async function configureAndroidRelease() {
  if (!process.stdin.isTTY || !process.stdout.isTTY) throw new ReleaseConfigError('Run make configure-release in an interactive terminal.');
  let existing = {};
  try { existing = await readReleaseConfig() || {}; }
  catch (error) { throw new ReleaseConfigError(`${error.message} Fix or remove it before reconfiguring.`); }

  console.log('Configure the local Android release signer. Values are saved only in ignored .luma-release.json.');
  const androidDefault = existing.ANDROID_HOME || process.env.ANDROID_HOME || path.join(homedir(), 'Library/Android/sdk');
  const javaCandidate = '/Applications/Android Studio.app/Contents/jbr/Contents/Home';
  const javaDefault = existing.JAVA_HOME || process.env.JAVA_HOME || (await available(path.join(javaCandidate, 'bin/java')) ? javaCandidate : '');
  const gradleDefault = existing.LUMA_GRADLE_BIN || process.env.LUMA_GRADLE_BIN || await detectedGradle();
  const config = {
    ANDROID_HOME: await ask('Android SDK directory', androidDefault),
    JAVA_HOME: await ask('JDK home directory', javaDefault),
    LUMA_GRADLE_BIN: await ask('Gradle 9.6.1 executable', gradleDefault),
    LUMA_KEYSTORE_PATH: await ask('Production keystore path', existing.LUMA_KEYSTORE_PATH || process.env.LUMA_KEYSTORE_PATH || ''),
    LUMA_KEY_ALIAS: await ask('Signing key alias', existing.LUMA_KEY_ALIAS || process.env.LUMA_KEY_ALIAS || ''),
    LUMA_KEYSTORE_PASSWORD: await askSecret('Keystore password', existing.LUMA_KEYSTORE_PASSWORD || process.env.LUMA_KEYSTORE_PASSWORD || ''),
    LUMA_KEY_PASSWORD: await askSecret('Signing key password', existing.LUMA_KEY_PASSWORD || process.env.LUMA_KEY_PASSWORD || ''),
  };
  parseReleaseConfig(JSON.stringify(config));
  await requirePath('ANDROID_HOME', config.ANDROID_HOME, 'directory');
  await requirePath('JAVA_HOME', config.JAVA_HOME, 'directory');
  await requirePath('LUMA_GRADLE_BIN', config.LUMA_GRADLE_BIN, 'executable');
  await requirePath('LUMA_KEYSTORE_PATH', config.LUMA_KEYSTORE_PATH);
  if (!await available(path.join(config.JAVA_HOME, 'bin/java'), 'executable')) throw new ReleaseConfigError('JAVA_HOME does not contain an executable bin/java.');

  try {
    const current = await lstat(RELEASE_CONFIG_FILE);
    if (!current.isFile() || current.isSymbolicLink()) throw new ReleaseConfigError('Refusing to replace a non-regular local release config.');
  } catch (error) {
    if (error?.code !== 'ENOENT') throw error;
  }
  const temporary = `${RELEASE_CONFIG_FILE}.tmp-${process.pid}`;
  try {
    await writeFile(temporary, `${JSON.stringify(config, null, 2)}\n`, { encoding: 'utf8', mode: 0o600, flag: 'wx' });
    await chmod(temporary, 0o600);
    await rename(temporary, RELEASE_CONFIG_FILE);
  } finally { await unlink(temporary).catch(() => {}); }
  console.log('Saved ignored .luma-release.json with mode 0600. Future make deploy-gh-pages runs will load it automatically.');
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  configureAndroidRelease().catch(error => { console.error(error instanceof Error ? error.message : 'Local release configuration failed.'); process.exitCode = 1; });
}
