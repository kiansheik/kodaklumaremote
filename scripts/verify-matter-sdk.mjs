import { createHash } from 'node:crypto';
import { readFile, lstat, readdir } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const artifacts = resolve(root, '.local/matter-sdk/artifacts');
const pin = JSON.parse(await readFile(resolve(root, 'android/matter-sdk.json'), 'utf8'));
let manifest;
try { manifest = JSON.parse(await readFile(resolve(artifacts, 'provenance.json'), 'utf8')); }
catch { throw new Error('Build the pinned Matter SDK first; see docs/agent/matter-android-path.md.'); }
if (manifest.source?.repository !== pin.repository || manifest.source?.revision !== pin.revision || manifest.kotlin !== pin.kotlin
    || JSON.stringify(manifest.abis) !== JSON.stringify(pin.abis)) throw new Error('Matter SDK source/toolchain does not match android/matter-sdk.json.');
if (manifest.logging?.progress !== false || manifest.logging?.detail !== false || manifest.logging?.errors !== true) throw new Error('Build Matter with pairing-payload logging disabled.');
if (!manifest.artifacts || typeof manifest.artifacts !== 'object' || Array.isArray(manifest.artifacts)) throw new Error('Matter SDK artifact hashes are missing.');
const files = new Set();
async function inventory(folder, prefix = '') {
  for (const name of await readdir(folder)) {
    if (!/^[A-Za-z0-9.+_-]+$/u.test(name) || name === '.' || name === '..') throw new Error('Unexpected Matter artifact filename.');
    const relative = prefix + name, path = resolve(folder, name), info = await lstat(path);
    if (info.isSymbolicLink()) throw new Error('Matter SDK artifacts cannot be symlinks.');
    if (info.isDirectory()) await inventory(path, `${relative}/`);
    else if (info.isFile() && !['provenance.json', 'SHA256SUMS'].includes(relative)) files.add(relative);
    else if (!info.isFile()) throw new Error('Unexpected Matter artifact type.');
  }
}
await inventory(artifacts);
for (const [relative, hash] of Object.entries(manifest.artifacts)) {
  if (!files.delete(relative) || !/^[a-f0-9]{64}$/u.test(hash)) throw new Error('Matter SDK manifest contains an invalid artifact.');
  const bytes = await readFile(resolve(artifacts, relative));
  if (createHash('sha256').update(bytes).digest('hex') !== hash) throw new Error('Matter SDK artifact changed after preparation.');
}
if (files.size) throw new Error('Unverified files are present in the Matter SDK artifacts.');
const names = Object.keys(manifest.artifacts);
for (const abi of pin.abis) for (const library of ['libCHIPController.so', 'libc++_shared.so']) {
  if (!names.includes(`jniLibs/${abi}/${library}`)) throw new Error('Required Matter native library is missing.');
}
if (!names.some(name => /^libs\/[^/]+\.jar$/u.test(name)) || !names.some(name => /^assets\/matter\/paa\/[^/]+\.der$/u.test(name))
    || !names.some(name => /^assets\/matter\/cd\/[^/]+\.der$/u.test(name)) || !names.some(name => /^assets\/matter\/LICENSE/u.test(name))) {
  throw new Error('Matter Java libraries, production trust roots or license are missing.');
}
console.log(`Verified pinned Matter SDK ${pin.revision.slice(0, 12)} (${names.length} artifacts).`);
