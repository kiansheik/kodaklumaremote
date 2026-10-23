import { execFile as execFileCallback } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, readFile, rename, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { parseArgs, promisify } from 'node:util';
import { pathToFileURL } from 'node:url';
import { MAX_APK_BYTES, PACKAGE_NAME, RECEIVER_PACKAGE_NAME, RELEASE_REPOSITORY, receiverAssetName, validateReceiverManifest, validateUpdateManifest } from '../site/update-manifest.js';

const execFile = promisify(execFileCallback);

export function apkMetadata(badging) {
  const attributes = badging.match(/^package:.*$/m)?.[0];
  const attribute = name => attributes?.match(new RegExp(`(?:^| )${name}='([^']*)'`))?.[1];
  const sdkFields = badging.split(/\r?\n/).filter(line => /^(?:minSdkVersion|sdkVersion):/.test(line));
  const minSdk = sdkFields.length === 1 ? sdkFields[0].match(/^(?:minSdkVersion|sdkVersion):'(\d+)'$/)?.[1] : undefined;
  const versionCode = attribute('versionCode');
  if (!/^\d+$/.test(versionCode || '') || !minSdk) throw new Error('APK version metadata is missing.');
  return { packageName: attribute('name'), versionName: attribute('versionName'), versionCode: Number(versionCode), minSdk: Number(minSdk) };
}

/** Phone manifest by default; pass receiverTag (the phone release tag) for the LUMA Receiver feed. */
export async function generateUpdate({ apk, aapt2, apksigner, releasedAt, notes = '', expectedCertSha256, receiverTag }, run = execFile) {
  if (!apk || !aapt2 || !apksigner) throw new Error('APK, aapt2 and apksigner paths are required.');
  const file = await stat(apk);
  if (!file.isFile() || file.size < 1 || file.size > MAX_APK_BYTES) throw new Error('APK must be a file of at most 64 MiB.');
  let signatures;
  try { signatures = (await run(apksigner, ['verify', '--verbose', '--print-certs', apk], { maxBuffer: 128 * 1024, timeout: 30000 })).stdout; }
  catch { throw new Error('APK signature verification failed.'); }
  const certificates = [...signatures.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})\s*$/gim)].map(match => match[1].toLowerCase());
  if (certificates.length !== 1) throw new Error('A single verified APK signer is required.');
  if (expectedCertSha256 !== undefined && (!/^[a-f0-9]{64}$/i.test(expectedCertSha256) || certificates[0] !== expectedCertSha256.toLowerCase())) throw new Error('APK signer does not match the expected certificate.');
  const metadata = apkMetadata((await run(aapt2, ['dump', 'badging', apk], { maxBuffer: 1024 * 1024, timeout: 30000 })).stdout);
  if (metadata.packageName !== (receiverTag ? RECEIVER_PACKAGE_NAME : PACKAGE_NAME)) throw new Error(receiverTag ? 'APK package does not match LUMA Receiver.' : 'APK package does not match Luma Remote.');
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(apk)) hash.update(chunk);
  const after = await stat(apk);
  if (file.dev !== after.dev || file.ino !== after.ino || file.size !== after.size || file.mtimeMs !== after.mtimeMs) throw new Error('APK changed during release verification.');
  const sha256 = hash.digest('hex');
  if (receiverTag) return validateReceiverManifest({ schemaVersion: 1, ...metadata,
    apkUrl: `https://github.com/${RELEASE_REPOSITORY}/releases/download/${receiverTag}/${receiverAssetName(metadata.versionName)}`,
    sha256, sizeBytes: file.size, releasedAt, notes }, receiverTag);
  return validateUpdateManifest({ schemaVersion: 1, ...metadata,
    apkUrl: `https://github.com/${RELEASE_REPOSITORY}/releases/download/v${metadata.versionName}/luma-remote-${metadata.versionName}.apk`,
    sha256, sizeBytes: file.size, releasedAt, notes });
}

async function main() {
  const { values } = parseArgs({ options: Object.fromEntries(['apk', 'aapt2', 'apksigner', 'released-at', 'notes-file', 'expected-cert-sha256', 'out', 'receiver-tag'].map(key => [key, { type: 'string' }])) });
  if (!values.out || !values['released-at']) throw new Error('--out and --released-at are required.');
  const notes = values['notes-file'] ? await readFile(values['notes-file'], 'utf8') : '';
  const manifest = await generateUpdate({ apk: values.apk, aapt2: values.aapt2, apksigner: values.apksigner,
    releasedAt: values['released-at'], notes, expectedCertSha256: values['expected-cert-sha256'] ?? process.env.LUMA_RELEASE_CERT_SHA256,
    receiverTag: values['receiver-tag'] });
  const output = path.resolve(values.out);
  await mkdir(path.dirname(output), { recursive: true });
  const temporary = `${output}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(manifest, null, 2)}\n`, { flag: 'wx' });
  await rename(temporary, output);
  console.log(`Generated release metadata for ${manifest.versionName} (${manifest.versionCode}).`);
}
if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(error => { console.error(error.message); process.exitCode = 1; });
