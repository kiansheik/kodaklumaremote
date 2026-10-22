import { lstat, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
export const RELEASE_CONFIG_FILE = path.join(root, '.luma-release.json');
export const RELEASE_CONFIG_KEYS = [
  'ANDROID_HOME',
  'JAVA_HOME',
  'LUMA_GRADLE_BIN',
  'LUMA_KEYSTORE_PATH',
  'LUMA_KEYSTORE_PASSWORD',
  'LUMA_KEY_ALIAS',
  'LUMA_KEY_PASSWORD',
];
export const REQUIRED_RELEASE_CONFIG_KEYS = [
  'ANDROID_HOME',
  'LUMA_KEYSTORE_PATH',
  'LUMA_KEYSTORE_PASSWORD',
  'LUMA_KEY_ALIAS',
  'LUMA_KEY_PASSWORD',
];
const pathKeys = new Set(['ANDROID_HOME', 'JAVA_HOME', 'LUMA_GRADLE_BIN', 'LUMA_KEYSTORE_PATH']);
const maximumBytes = 16 * 1024;

export class ReleaseConfigError extends Error {}

export function parseReleaseConfig(source) {
  if (typeof source !== 'string' || Buffer.byteLength(source, 'utf8') > maximumBytes) {
    throw new ReleaseConfigError('The local release config is invalid or too large.');
  }
  let value;
  try { value = JSON.parse(source); }
  catch { throw new ReleaseConfigError('The local release config is not valid JSON.'); }
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new ReleaseConfigError('The local release config must be a JSON object.');
  const keys = Object.keys(value);
  if (keys.some(key => !RELEASE_CONFIG_KEYS.includes(key))) throw new ReleaseConfigError('The local release config contains an unknown setting.');
  for (const key of REQUIRED_RELEASE_CONFIG_KEYS) {
    if (!keys.includes(key)) throw new ReleaseConfigError(`The local release config is missing ${key}.`);
  }
  for (const [key, setting] of Object.entries(value)) {
    if (typeof setting !== 'string' || !setting || setting.length > 4096 || /[\u0000\r\n]/u.test(setting)) {
      throw new ReleaseConfigError(`The local release config has an invalid ${key}.`);
    }
    if (pathKeys.has(key) && !path.isAbsolute(setting)) throw new ReleaseConfigError(`${key} must be an absolute path in the local release config.`);
  }
  return Object.fromEntries(RELEASE_CONFIG_KEYS.filter(key => keys.includes(key)).map(key => [key, value[key]]));
}

export async function readReleaseConfig(file = RELEASE_CONFIG_FILE) {
  let info;
  try { info = await lstat(file); }
  catch (error) {
    if (error?.code === 'ENOENT') return null;
    throw new ReleaseConfigError('The local release config could not be inspected.');
  }
  if (!info.isFile() || info.isSymbolicLink()) throw new ReleaseConfigError('The local release config must be a regular file, not a symlink.');
  if ((info.mode & 0o077) !== 0) throw new ReleaseConfigError('The local release config must have mode 0600. Run: chmod 600 .luma-release.json');
  let source;
  try { source = await readFile(file, 'utf8'); }
  catch { throw new ReleaseConfigError('The local release config could not be read.'); }
  return parseReleaseConfig(source);
}

export async function loadReleaseConfig(file = RELEASE_CONFIG_FILE) {
  const config = await readReleaseConfig(file);
  if (!config) return null;
  for (const [key, value] of Object.entries(config)) {
    if (!process.env[key]) process.env[key] = value;
  }
  return config;
}
