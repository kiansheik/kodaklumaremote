import { copyFile, lstat, mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { MAX_MANIFEST_BYTES, RELEASE_REPOSITORY, validateUpdateManifest } from '../site/update-manifest.js';

const root = fileURLToPath(new URL('../', import.meta.url));
const pageFiles = ['index.html', 'styles.css', 'app.js', 'update-manifest.js', 'projector.svg'];

export function validateRelease(manifest, release) {
  if (!release || release.draft !== false || release.prerelease !== false || release.tag_name !== `v${manifest.versionName}` || release.html_url !== `https://github.com/${RELEASE_REPOSITORY}/releases/tag/v${manifest.versionName}` || !Array.isArray(release.assets)) throw new Error('A matching published stable release is required.');
  const assets = release.assets.filter(asset => asset.name === `luma-remote-${manifest.versionName}.apk`);
  if (assets.length !== 1 || assets[0].browser_download_url !== manifest.apkUrl || assets[0].size !== manifest.sizeBytes || assets[0].state !== 'uploaded') throw new Error('Release APK does not match the manifest.');
  if (assets[0].digest != null && assets[0].digest !== `sha256:${manifest.sha256}`) throw new Error('Release APK digest does not match the manifest.');
}

export async function buildPages({ manifest: input, release, out, siteDirectory = path.join(root, 'site') }) {
  const manifest = validateUpdateManifest(input);
  validateRelease(manifest, release);
  const output = path.resolve(out);
  await mkdir(output, { recursive: true });
  if ((await lstat(output)).isSymbolicLink()) throw new Error('Pages output must be a real directory.');
  const allowed = new Set([...pageFiles, 'update.json', '.nojekyll']);
  for (const name of await readdir(output)) {
    if (!allowed.has(name) || !(await lstat(path.join(output, name))).isFile()) throw new Error('Pages output contains an unexpected file. Use an empty output directory.');
  }
  for (const name of pageFiles) {
    if (!(await lstat(path.join(siteDirectory, name))).isFile()) throw new Error('Pages sources must be regular files.');
    await copyFile(path.join(siteDirectory, name), path.join(output, name));
  }
  await writeFile(path.join(output, 'update.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  await writeFile(path.join(output, '.nojekyll'), '');
  return { directory: output, versionName: manifest.versionName };
}

async function main() {
  const { values } = parseArgs({ options: { manifest: { type: 'string' }, release: { type: 'string' }, out: { type: 'string' } } });
  if (!values.manifest || !values.release || !values.out) throw new Error('--manifest, --release and --out are required.');
  const manifestFile = await readFile(values.manifest);
  if (manifestFile.length > MAX_MANIFEST_BYTES) throw new Error('Release manifest is too large.');
  const result = await buildPages({ manifest: JSON.parse(manifestFile), release: JSON.parse(await readFile(values.release, 'utf8')), out: values.out });
  console.log(`Prepared Pages install site for ${result.versionName}.`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(error => { console.error(error.message); process.exitCode = 1; });
