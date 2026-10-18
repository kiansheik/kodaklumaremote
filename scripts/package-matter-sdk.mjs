import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import { dirname, join, relative, resolve } from 'node:path';
import { promisify } from 'node:util';

// Generated native libraries and public trust certificates stay in ignored .local.
// Run after scripts/build-matter-sdk.sh. Never copy development credentials.
const run = promisify(execFile);
const revision = '3584c8c8552f4a0d471f2aad24a15b478959c230';
const sdk = resolve(process.env.MATTER_SDK_ROOT || '.local/matter-sdk');
const source = join(sdk, 'source');
const output = join(source, 'out/android-arm64-chip-tool-release');
const artifacts = join(sdk, 'artifacts');
const ndk = process.env.ANDROID_NDK_HOME;
assert.ok(ndk, 'Set ANDROID_NDK_HOME to the NDK used for this build.');
const { stdout: actual } = await run('git', ['-C', source, 'rev-parse', 'HEAD']);
assert.equal(actual.trim(), revision, 'All Matter Java/JNI/certificates must use the reviewed source revision.');
const setupOnlyFiles = new Set(['scripts/setup/gen_pigweed_cipd_json.py', 'third_party/android_deps/BUILD.gn', 'third_party/java_deps/set_up_java_deps.sh']);
const changed = (await run('git', ['-C', source, 'diff', '--name-only', 'HEAD'])).stdout.trim().split('\n').filter(Boolean);
assert.ok(changed.every((file) => setupOnlyFiles.has(file)), 'Pinned native/controller sources must have no local changes.');
const buildConfig = await readFile(join(output, 'gen/include/core/CHIPBuildConfig.h'), 'utf8');
assert.match(buildConfig, /^#define CHIP_PROGRESS_LOGGING 0$/mu, 'Pairing codes must not enter native progress logs.');
assert.match(buildConfig, /^#define CHIP_DETAIL_LOGGING 0$/mu, 'Native detail logging must be disabled.');
assert.match(buildConfig, /^#define CHIP_ERROR_LOGGING 1$/mu, 'Retain native error diagnostics.');
const host = process.platform === 'darwin' ? 'darwin-x86_64' : 'linux-x86_64';
const tools = join(ndk, 'toolchains/llvm/prebuilt', host, 'bin');
const files = [];
async function copy(input, destination) {
  const target = join(artifacts, destination);
  await mkdir(dirname(target), { recursive: true }); await copyFile(input, target); files.push(destination);
}
const jars = ['CHIPController', 'CHIPInteractionModel', 'OnboardingPayload', 'CHIPClusters', 'CHIPClusterID', 'libMatterJson', 'libMatterTlv'];
for (const name of jars) await copy(join(output, 'lib/src/controller/java', `${name}.jar`), `libs/${name}.jar`);
await copy(join(output, 'lib/src/platform/android/AndroidPlatform.jar'), 'libs/AndroidPlatform.jar');
for (const name of ['libCHIPController.so', 'libc++_shared.so']) {
  const destination = `jniLibs/arm64-v8a/${name}`;
  await copy(join(output, 'lib/jni/arm64-v8a', name), destination);
  await run(join(tools, 'llvm-strip'), ['--strip-unneeded', join(artifacts, destination)]);
  if (name === 'libCHIPController.so') {
    const binary = await readFile(join(artifacts, destination));
    for (const label of ['Manual pairing code: [%s]', 'SetupQRCode: [%s]']) {
      assert.ok(!binary.includes(label), 'Native pairing-payload log format must be compiled out.');
    }
  }
  const { stdout } = await run(join(tools, 'llvm-readelf'), ['--program-headers', '--wide', join(artifacts, destination)]);
  const loads = stdout.split('\n').filter((line) => /^\s*LOAD\s/u.test(line));
  assert.ok(loads.length > 0, 'Native ELF must expose load segments.');
  for (const line of loads) assert.ok(Number.parseInt(line.trim().split(/\s+/u).at(-1), 16) >= 16384, `${name} requires 16KB ELF alignment.`);
}
for (const [folder, destination] of [['paa-root-certs', 'paa'], ['cd-certs', 'cd']]) {
  const roots = join(source, 'credentials/production', folder);
  const certificates = (await readdir(roots)).filter((name) => name.endsWith('.der')).sort();
  assert.ok(certificates.length > 0, 'Public production trust store must not be empty.');
  for (const name of certificates) await copy(join(roots, name), `assets/matter/${destination}/${name}`);
}
await copy(join(source, 'LICENSE'), 'assets/matter/LICENSE');
await copy(join(source, 'NOTICE'), 'assets/matter/NOTICE');
const licenses = ['third_party/boringssl/repo/src/LICENSE', 'third_party/mbedtls/repo/LICENSE',
  'third_party/jsoncpp/repo/LICENSE', 'third_party/nlassert/repo/LICENSE', 'third_party/nlio/repo/LICENSE',
  'third_party/pigweed/repo/LICENSE', 'third_party/uriparser/repo/COPYING', 'examples/common/QRCode/repo/LICENSE'];
for (const name of licenses) {
  if (await stat(join(source, name)).then((item) => item.isFile()).catch(() => false)) {
    await copy(join(source, name), `assets/matter/licenses/${name.replaceAll('/', '_')}`);
  }
}
await copy(join(ndk, 'NOTICE'), 'assets/matter/licenses/ANDROID_NDK_NOTICE');
await copy(join(ndk, 'NOTICE.toolchain'), 'assets/matter/licenses/ANDROID_NDK_NOTICE.toolchain');
const hashes = [];
for (const name of files.sort()) {
  const bytes = await readFile(join(artifacts, name));
  hashes.push({ file: name, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
}
const compiler = await run('kotlinc', ['-version']);
const submoduleStatus = (await run('git', ['-C', source, 'submodule', 'status'])).stdout;
assert.ok(!/^\+/mu.test(submoduleStatus), 'Initialized SDK submodules must match the source pin.');
const submodules = submoduleStatus.split('\n').filter((line) => line.startsWith(' ')).map((line) => {
  const [, commit, path] = line.match(/^ (\S+) (\S+)/u); return { path, commit };
});
const provenance = {
  source: { repository: 'https://github.com/project-chip/connectedhomeip', revision },
  kotlin: '2.1.10', abis: ['arm64-v8a'],
  artifacts: Object.fromEntries(hashes.map((item) => [item.file, item.sha256])),
  profile: 'release', minSdk: 24,
  logging: { progress: false, detail: false, errors: true },
  elfPageAlignment: 16384, kotlinCompiler: (compiler.stdout + compiler.stderr).trim(),
  ndk: (await readFile(join(ndk, 'source.properties'), 'utf8')).match(/^Pkg.Revision\s*=\s*(.+)$/mu)?.[1],
  gnArgs: (await readFile(join(output, 'args.gn'), 'utf8')).replaceAll(source, '<sdk-source>').replaceAll(ndk, '<ndk>'),
  setupOnlyChanges: ['Skip unused firmware/CIPD tools for Android host environment.', 'Skip obsolete google-collect test download; no BUILD target references it.', 'Generated AndroidX dependency BUILD.gn.'],
  certificateSource: 'credentials/production/{paa-root-certs,cd-certs}/*.der at the same revision; no development credentials.',
  submodules, files: hashes,
};
await writeFile(join(artifacts, 'SHA256SUMS'), hashes.map((item) => `${item.sha256}  ${item.file}`).join('\n') + '\n');
await writeFile(join(artifacts, 'provenance.json'), JSON.stringify(provenance, null, 2) + '\n');
console.log(`Packaged ${hashes.length} verified files at ${relative(process.cwd(), artifacts)} (ARM64, 16KB ELF, stripped).`);
