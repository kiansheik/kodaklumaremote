# Matter on Android: implementation path

Checked 2026-10-18. The pinned official Android controller SDK was built locally;
its Java/JNI artifacts, public trust certificates and provenance stay in ignored
`.local/matter-sdk/artifacts/`. Version0.4.0/code9 is integrated and installed on
Samsung, with minimum API24 and ARM64-only native libraries. Build and isolated
JNI checks passed; actual phone manual-code commissioning completed with
production trust enabled and speaker1→2→1 independently read back. Camera,
phone navigation/input/Off effects and additional-phone enrollment remain
pending. [Current handoff](session-handoffs/2026-10-18-native-matter-phone.md).

## Sequence and boundary

The isolated Mac controller commissioned the actual Philips and read
its endpoints/clusters. A Matter label does not establish app launch, navigation,
power-on, volume or arbitrary-source playback. Prove each advertised capability
before adding its control. The final target is a controller inside the phone app,
with no running Mac or Google Home account required; the Mac is a test stage.
MSX can continue owning playback while Matter supplies verified TV controls.

## Source and build

Use the Apache-2.0 [upstream SDK](https://github.com/project-chip/connectedhomeip),
pinning Java/JNI/platform artifacts and generated clusters to the **same commit**.
Retain applicable license/notice files. Do not mix a newer `.so` with older JARs.

- Stable candidate: `v1.6.1.0`, commit
  `3bcdd56ba54fb88b2afb4bfef575014671df7aa7`. Its official release has no
  downloadable binary assets; its [Android sample](https://github.com/project-chip/connectedhomeip/blob/3bcdd56ba54fb88b2afb4bfef575014671df7aa7/examples/android/CHIPTool/app/build.gradle)
  declares minimum API 24.
- Selected and built no-GMS source snapshot: commit
  `3584c8c8552f4a0d471f2aad24a15b478959c230`. Its [sample Gradle file](https://github.com/project-chip/connectedhomeip/blob/3584c8c8552f4a0d471f2aad24a15b478959c230/examples/android/CHIPTool/app/build.gradle)
  accepts `-PmatterEnableGms=false`, selecting `src/nogms/java` and excluding its
  Play-services Thread dependency. This is a newer source snapshot, not the
  stable release; do not assume that flag exists on the stable pin.

The [official Android build guide](https://github.com/project-chip/connectedhomeip/blob/master/docs/platforms/android/android_building.md)
documents SDK 34, NDK 28.2.13676358, JDK 17, Gradle 8.7/AGP 8.5.1 and Kotlin tooling.
Its script entry point is
`./scripts/build/build_examples.py --target android-arm64-chip-tool build`.
For the source/IDE route, generate the native build with
`scripts/examples/android_app_ide.sh`, configure `matterSdkSourceBuild`,
`matterBuildSrcDir` and ABI filters as documented, then use the no-GMS Gradle
property on the pin that supports it. Our build uses the official release-profile
generator followed by individual JNI/JAR Ninja targets, without building the
sample APK or its Google/Thread integration.

## Local artifacts and reproduction

[`scripts/build-matter-sdk.sh`](../../scripts/build-matter-sdk.sh) clones/checks
the exact revision, initializes Android submodules, prepares the Android host
environment, and builds the controller libraries. Set `JAVA_HOME` to JDK 17/21,
`ANDROID_HOME` to the SDK and `ANDROID_NDK_HOME` to NDK 28+; invoke the script with
`bash`. Local validation used JDK 21, NDK 30.0.16248370, SDK 34 (hardcoded by upstream
Java targets), and SDK 35 for the general GN SDK argument. TLS remains verified;
environments with additional trusted CAs can supply `SSL_CERT_FILE` and
`REQUESTS_CA_BUNDLE`.

The helper pins JetBrains Kotlin 2.1.10 to the upstream sample's declared version
and verifies its published compiler ZIP SHA256
`c6e9e2636889828e19c8811d5ab890862538c89dc2a3101956dfee3c2a8ba6b1`.
Kotlin 2.4.21 failed unchanged upstream onboarding code under `-Werror`; JDK 25
failed upstream Gradle 8.7. Both were resolved by selecting compatible toolchains.
An unused obsolete `google-collect` test download returned HTTP 403 and is skipped;
its JAR is not referenced by any upstream BUILD target. Local bootstrap also
excludes QEMU/OpenOCD/Bloaty/ARM firmware packages. Native SDK code is unchanged.
The GN build disables `chip_progress_logging` and `chip_detail_logging`, retaining
error diagnostics: upstream commissioning-window progress messages include the
actual manual/QR pairing payload. Packaging checks the generated logging macros
and rejects binaries retaining those two sensitive format strings.

[`scripts/package-matter-sdk.mjs`](../../scripts/package-matter-sdk.mjs) copies
only eight required JARs, ARM64 JNI, public production PAA/CD DER certificates
and applicable notices. It strips native binaries, rejects ELF load alignment
below 16KB, and writes `SHA256SUMS` plus `provenance.json`. The manifest contract is
`source.repository/revision`, `kotlin`, `abis`, and an `artifacts` map from relative
path to SHA256; it also records toolchains, GN arguments and submodules.

Observed output: 97 hashed files, 72 PAA roots, 5 CD roots, native controller 4.6MiB
and shared C++ 1.4MiB. Java/JNI and certificates come from the same SDK revision.
No binaries or private fabric material are checked into Git. The packaged JNI
and trust assets initialized in the seven final native Matter fixture checks.
Final debug build/lint and the earlier precommit signed-release build passed.
Both16KB ELF load and APK ZIP alignment were
verified. A dedicated16KB-page runtime test is not established by ordinary APK loading.

Actual phone commissioning completed with the configured production PAA/CD
trust store. The earlier Mac controller's default matter.js commissioning did
not establish production trust-chain verification. The Android adapter must retain
strict attestation and must not bypass a missing trust root to make pairing work.

The pinned [Android operational-credentials issuer](https://github.com/project-chip/connectedhomeip/blob/3584c8c8552f4a0d471f2aad24a15b478959c230/src/controller/java/AndroidOperationalCredentialsIssuer.cpp#L375) uses upstream
`GroupTesting::DefaultIpkValue` for NOC issuance, matching its controller wrapper's
default IPK. Changing only `ControllerParams.setIpk` would mismatch the TV and is
not a valid fix. The app's CA remains random and privately persisted; CASE still
authenticates private keys. Stronger identity/group privacy needs a separately
verified issuer change, which this integration does not make.

## Prebuilt prototype limit

`com.google.matter:matter-android-demo-sdk:1.0` is a genuine Google-published
Maven Central AAR containing controller JNI/JAR artifacts. [Published metadata](https://repo.maven.apache.org/maven2/com/google/matter/matter-android-demo-sdk/maven-metadata.xml)
still lists only 1.0, last updated 2024-02-07. Its [publisher POM](https://repo.maven.apache.org/maven2/com/google/matter/matter-android-demo-sdk/1.0/matter-android-demo-sdk-1.0.pom)
lists Apache 2.0 and explicitly describes development/example use, discouraging
production use. This is not a current production dependency recommendation.
Do not integrate it into the distributed app as a shortcut.

## Android and persistence constraints

The inspected [core library](https://github.com/project-chip/connectedhomeip/blob/3584c8c8552f4a0d471f2aad24a15b478959c230/examples/android/CHIPTool/chip-library/build.gradle)
requires API 24; the newer full sample requires API 26. The app's floor was
intentionally raised from 23 to 24 for the native SDK; runtime gating alone cannot
lower a native/AAR minimum API requirement.
Android 16 also requires checking native compatibility: build and inspect **all**
JNI/C++ libraries for 16 KB ELF/ZIP alignment and test the packaged APK in a 16 KB
environment. NDK 28+ and AGP 8.5.1+ provide the relevant defaults. [Android guidance](https://developer.android.com/guide/practices/page-sizes)

The app owns one controller/platform instance through `ConnectionService`, following the
[upstream initialization](https://github.com/project-chip/connectedhomeip/blob/3584c8c8552f4a0d471f2aad24a15b478959c230/examples/android/CHIPTool/app/src/main/java/com/google/chip/chiptool/ChipClient.kt).
Fabric values use AES-GCM with a non-exportable Android Keystore key, fresh IVs
and storage-key authenticated data; corrupt ciphertext fails closed without
deleting the saved value. An address or setup code alone is not a restorable
controller. Backup is disabled; signed upgrades retain private controller/Kodi
storage. Each phone enrolls as its own administrator through commissioning;
**Add another phone** opens a temporary QR/manual-code window without exporting
fabric keys. UI close/expiry clears that displayed code. Reusing the Mac's node
ID without its credentials does not transfer access. Actual phone **Add another
phone** opened a TV-accepted window and displayed its manual code/QR/expiry;
dismiss/reopen removed the code/QR. Enrollment of a second phone remains untested.

Final recovery/lifecycle validation passed seven native Matter and six service
checks, plus five packaged UI checks. Explicit confirmed local-only forget removes the
saved TV node/cache while retaining the encrypted CA; it returns `LOCAL_ONLY`
with `tvFabricRemoved:false` and sends no TV command. A stale TV fabric entry
may therefore remain on the TV. Async pair-state notifications reschedule
service polling and update foreground lifetime. Pair-attempt cancellation uses
`stopDevicePairing(pairingNode)`: the SDK's `shutdownCommissioning()` closes
its singleton I/O factory, preventing later attempts in the same process.
Published null volume/input fields clear recovery cache instead of retaining
old values. These fixtures do not deliberately reset or orphan the household TV.

Google Home APIs are an alternative ecosystem integration, not the account-free
route: their [permissions flow](https://developers.home.google.com/apis/android/permissions)
uses Google-account authorization/OAuth. Keep the native controller adapter
independent of that API.
