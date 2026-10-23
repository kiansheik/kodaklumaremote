export const RELEASE_REPOSITORY = 'kiansheik/kodaklumaremote';
export const PACKAGE_NAME = 'dev.framextv.lumaremote';
export const MAX_APK_BYTES = 64 * 1024 * 1024;
export const MAX_MANIFEST_BYTES = 16 * 1024;

export const RECEIVER_PACKAGE_NAME = 'dev.framextv.lumareceiver';
export const receiverAssetName = versionName => `luma-receiver-${versionName}.apk`;
const RELEASE_TAG = /^v(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)$/;

function checkManifest(value, { packageName, minSdkFloor, expectedUrl }) {
  const invalid = () => { throw new Error('Invalid release manifest.'); };
  if (!value || typeof value !== 'object' || Array.isArray(value)) invalid();
  if (value.schemaVersion !== 1 || value.packageName !== packageName) invalid();
  if (typeof value.versionName !== 'string' || value.versionName.length > 32 || !/^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(value.versionName)) invalid();
  if (!Number.isInteger(value.versionCode) || value.versionCode < 1 || value.versionCode > 2147483647) invalid();
  if (!Number.isInteger(value.minSdk) || value.minSdk < minSdkFloor || value.minSdk > 100) invalid();
  if (value.apkUrl !== expectedUrl(value.versionName)) invalid();
  if (typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256)) invalid();
  if (!Number.isInteger(value.sizeBytes) || value.sizeBytes < 1 || value.sizeBytes > MAX_APK_BYTES) invalid();
  if (typeof value.releasedAt !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{3})?Z$/.test(value.releasedAt)) invalid();
  const instant = new Date(value.releasedAt);
  if (!Number.isFinite(instant.getTime())) invalid();
  const canonical = instant.toISOString();
  if (value.releasedAt !== canonical && value.releasedAt !== canonical.replace('.000Z', 'Z')) invalid();
  if (typeof value.notes !== 'string' || value.notes.length > 2000 || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(value.notes)) invalid();
  return Object.fromEntries(['schemaVersion', 'packageName', 'versionName', 'versionCode', 'minSdk', 'apkUrl', 'sha256', 'sizeBytes', 'releasedAt', 'notes'].map(key => [key, value[key]]));
}

export function validateUpdateManifest(value) {
  return checkManifest(value, { packageName: PACKAGE_NAME, minSdkFloor: 23,
    expectedUrl: version => `https://github.com/${RELEASE_REPOSITORY}/releases/download/v${version}/luma-remote-${version}.apk` });
}

/** LUMA Receiver ships inside a phone release, so its APK URL uses that release tag. */
export function validateReceiverManifest(value, releaseTag) {
  if (typeof releaseTag !== 'string' || !RELEASE_TAG.test(releaseTag)) throw new Error('Invalid release manifest.');
  return checkManifest(value, { packageName: RECEIVER_PACKAGE_NAME, minSdkFloor: 21,
    expectedUrl: version => `https://github.com/${RELEASE_REPOSITORY}/releases/download/${releaseTag}/${receiverAssetName(version)}` });
}
