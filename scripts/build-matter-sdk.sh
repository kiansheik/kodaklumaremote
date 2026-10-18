#!/usr/bin/env bash
# Pinned, local-only Matter Android SDK/JNI build. No app install or pairing.
set -e
repository="$(cd "$(dirname "$0")/.." && pwd)"
revision=3584c8c8552f4a0d471f2aad24a15b478959c230
export MATTER_SDK_ROOT="${MATTER_SDK_ROOT:-$repository/.local/matter-sdk}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory.}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to NDK 28 or newer.}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK17 or JDK21, compatible with upstream Gradle8.7.}"
export PATH="$JAVA_HOME/bin:$PATH"
mkdir -p "$MATTER_SDK_ROOT"
export MATTER_SDK_ROOT="$(cd "$MATTER_SDK_ROOT" && pwd)"
source_dir="$MATTER_SDK_ROOT/source"
if [ ! -d "$source_dir/.git" ]; then
  git clone --filter=blob:none --depth 1 --no-checkout https://github.com/project-chip/connectedhomeip.git "$source_dir"
  git -C "$source_dir" fetch --depth 1 origin "$revision"
  git -C "$source_dir" checkout --detach "$revision"
fi
if [ "$(git -C "$source_dir" rev-parse HEAD)" != "$revision" ]; then
  echo 'SDK checkout revision differs; choose another MATTER_SDK_ROOT rather than changing an existing checkout.' >&2
  exit 1
fi
cd "$source_dir"
python3 scripts/checkout_submodules.py --platform android --shallow --jobs 8
python3 - <<'PY'
import json
from pathlib import Path
config = {
    'cipd_package_files': ['scripts/setup/zap.json'],
    'virtualenv': {
        'gn_root': '.', 'gn_targets': [':python_packages.install'],
        'requirements': ['scripts/setup/requirements.build.txt'],
        'constraints': ['scripts/setup/constraints.txt'],
        'gn_args': ['chip_crypto="boringssl"'],
    },
    'required_submodules': ['third_party/pigweed/repo'],
    'gni_file': 'build_overrides/pigweed_environment.gni',
}
Path('scripts/setup/environment.android-local.json').write_text(json.dumps(config, indent=2) + '\n')
# Only Android build-host setup changes. Never patch native controller code.
p = Path('scripts/setup/gen_pigweed_cipd_json.py')
s = p.read_text()
anchor = "    'infra/3pp/tools/renode',\n"
for package in ['fuchsia/third_party/qemu/', 'infra/3pp/tools/openocd/', 'fuchsia/third_party/3pp/bloaty/']:
    if repr(package) not in s:
        assert anchor in s
        s = s.replace(anchor, anchor + '    ' + repr(package) + ',\n', 1)
p.write_text(s)
p = Path('third_party/java_deps/set_up_java_deps.sh')
s = p.read_text()
line = 'download_jar "dist.wso2.org" "com/google/common/google-collect/1.0-rc1/" "google-collect-1.0-rc1.jar"'
if '\n' + line in s:
    assert 'google-collect' not in Path('third_party/java_deps/BUILD.gn').read_text()
    s = s.replace('\n' + line, '\n# Unused obsolete test download (HTTP403); not referenced by any BUILD target.\n# ' + line)
p.write_text(s)
PY
export CIPD_CACHE_DIR="$MATTER_SDK_ROOT/cipd-cache"
export PIP_CACHE_DIR="$MATTER_SDK_ROOT/pip-cache"
export PW_PACKAGE_ROOT="$MATTER_SDK_ROOT/packages"
export PW_CONFIG_FILE=scripts/setup/environment.android-local.json
# Match the pinned upstream Android sample's compiler/runtime. Newer Kotlin
# versions add diagnostics that fail its unchanged -Werror onboarding target.
kotlin_archive="$MATTER_SDK_ROOT/kotlin-compiler-2.1.10.zip"
if [ ! -s "$kotlin_archive" ]; then
  curl --fail --location --silent --show-error \
    https://github.com/JetBrains/kotlin/releases/download/v2.1.10/kotlin-compiler-2.1.10.zip \
    --output "$kotlin_archive"
fi
python3 - <<'PY'
import hashlib, os
from pathlib import Path
from zipfile import ZipFile
root=Path(os.environ['MATTER_SDK_ROOT'])
archive=root/'kotlin-compiler-2.1.10.zip'
# Matches JetBrains' published kotlin-compiler-2.1.10.zip.sha256.
assert hashlib.sha256(archive.read_bytes()).hexdigest() == 'c6e9e2636889828e19c8811d5ab890862538c89dc2a3101956dfee3c2a8ba6b1', 'Kotlin compiler checksum mismatch'
if not (root/'kotlin-2.1.10/kotlinc/bin/kotlinc').exists():
    with ZipFile(archive) as zipped: zipped.extractall(root/'kotlin-2.1.10')
    for script in (root/'kotlin-2.1.10/kotlinc/bin').iterdir(): script.chmod(script.stat().st_mode | 0o111)
PY
# Use normal TLS verification. A custom trusted CA can be supplied through
# SSL_CERT_FILE/REQUESTS_CA_BUNDLE when required by the build environment.
if [ ! -s .environment/activate.sh ]; then source scripts/bootstrap.sh -p none; fi
source scripts/activate.sh -p none
export PATH="$MATTER_SDK_ROOT/kotlin-2.1.10/kotlinc/bin:$JAVA_HOME/bin:$PATH"
if [ ! -f "$ANDROID_HOME/platforms/android-34/android.jar" ]; then
  "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-34'
fi
python3 scripts/build/build_examples.py --target android-arm64-chip-tool --build-profile release --ninja-jobs "${MATTER_BUILD_JOBS:-8}" gen
python3 - <<'PY'
from pathlib import Path
p=Path('out/android-arm64-chip-tool-release/args.gn')
s=p.read_text()
for key,value in [('android_sdk_version','35'),('chip_build_tests','false'),
                  ('chip_progress_logging','false'),('chip_detail_logging','false')]:
    lines=[line for line in s.splitlines() if not line.strip().startswith(key + ' =')]
    s='\n'.join(lines) + '\n' + key + ' = ' + value + '\n'
p.write_text(s)
PY
output=out/android-arm64-chip-tool-release
gn gen "$output" --check --fail-on-unused-args
ninja -C "$output" -j"${MATTER_BUILD_JOBS:-8}" \
  lib/src/controller/java/CHIPController.jar lib/src/controller/java/CHIPInteractionModel.jar \
  lib/src/controller/java/OnboardingPayload.jar lib/src/controller/java/CHIPClusters.jar \
  lib/src/controller/java/CHIPClusterID.jar lib/src/controller/java/libMatterJson.jar \
  lib/src/controller/java/libMatterTlv.jar lib/src/platform/android/AndroidPlatform.jar \
  lib/jni/arm64-v8a/libCHIPController.so lib/jni/arm64-v8a/libc++_shared.so
cd "$repository"
node scripts/package-matter-sdk.mjs
