# Android releases and install page

Public repository: https://github.com/kiansheik/kodaklumaremote

Install page/update feed: https://kiansheik.io/kodaklumaremote/
and `update.json` beneath that URL. GitHub Releases hosts the APK itself.
Pages deploys only the small `site/` page and validated release metadata, never
the source wiki, private settings, local analysis or signing material.

## Deploy the page

```sh
make deploy-gh-pages
```

This builds the plain HTML/CSS/JavaScript page with the latest published release's
validated `update.json` and pushes only those files to `gh-pages`. It uses your
existing Git/GitHub CLI login, leaves the main checkout alone and never force
pushes. GitHub Pages serves the branch root over HTTPS. No custom Actions
workflow or signing key is needed to update the page.

## Prepare a release locally

Use the existing production signing key. Its public certificate fingerprint is
checked into `android/release-certificate.sha256`. The key/passwords stay local;
no signing key is uploaded to GitHub Actions. Changing the signing certificate
breaks normal Android updates and requires a deliberate migration.

1. Increase Android `versionCode` and `versionName` in `android/app/build.gradle`.
2. Write `release-notes/<version>.txt` (plain text, at most2,000 characters).
3. Run relevant Node/browser/native checks and commit the source.
4. Set local SDK/JDK/Gradle and signing environment variables. Passwords use
   hidden input, not command arguments:

```sh
export JAVA_HOME='/path/to/jdk'
export ANDROID_HOME='/path/to/android/sdk'
export LUMA_GRADLE_BIN='/path/to/gradle'
export LUMA_KEYSTORE_PATH='/absolute/path/to/existing.keystore'
export LUMA_KEY_ALIAS='your-existing-alias'
read -r -s LUMA_KEYSTORE_PASSWORD
read -r -s LUMA_KEY_PASSWORD
export LUMA_KEYSTORE_PASSWORD LUMA_KEY_PASSWORD
npm run release:android -- --notes-file release-notes/0.2.4.txt
unset LUMA_KEYSTORE_PASSWORD LUMA_KEY_PASSWORD
```

The helper uses the cached offline Gradle toolchain and Build Tools36.0.0.
Install dependencies/toolchains first if not cached. It builds a non-debuggable
release and checks its signature against the public fingerprint. APK metadata,
SHA-256 and size are derived from the signed file. Output stays ignored under
`.local/releases/v<version>/`: APK, `update.json` and copied notes.
Do not modify the APK after signing or generating metadata.

## Publish

Push the exact committed source to `main`, then:

```sh
git push origin main
npm run publish:android -- --directory .local/releases/v0.2.4
```

The publisher checks committed version/source, production signature and APK
hash/size, creates a draft with both assets, then publishes it and deploys the
page for that exact version. If a
network failure leaves a draft, inspect that draft before retrying; do not
overwrite a public release's APK or metadata under the same version.

To retry a page deployment or publish page-only edits, use `make deploy-gh-pages`.
The deployer checks the stable release's package/repository/tag/asset/size/digest
metadata before publishing the page and update feed. Verify the page and APK
download after publication.

## Phone update behavior

Opening/resuming the app checks the fixed HTTPS feed, throttled to five minutes.
Automatic network failures stay quiet; Settings → Check for updates retries
immediately. A newer compatible version offers **Later** or **Update**. Later
suppresses that version for24hours. Nothing downloads before Update is chosen.

The app bounds download size/time and verifies SHA-256, package/version and the
currently installed signing certificate. Android24+ also checks the APK's actual
minimum API against metadata; on23 the installer enforces the binary minimum.
Only GitHub's exact project release path and expected HTTPS asset redirects are
accepted. One temporary read-only content URI grants the installer access to
the verified APK. If Android requires permission to install from LUMA, the app
opens that setting and asks to Continue after returning. Android always owns
the final installation confirmation. No silent installation or forced update.

The original local debug install uses another certificate. Its owner's API36
phone can use a privately generated signing-lineage bootstrap, validated on an
emulator without uninstalling or resetting app data. That bootstrap/test APK
is not distributed. Fresh public installs and all ordinary future releases use
only the production certificate.

## Nested Gradle root and source verification

AGP9.3.1's ExtractVersionControlInfoTask looks for .git beneath the Gradle root.
This repository's real Git root is above android/, so its default embedded
version-control-info.textproto honestly reports NO_SUPPORTED_VCS_FOUND. Do not
forge that generated file, disable the metadata to hide the limitation, or assume
an embedded revision was verified. The ordinary publisher still requires clean
tracked source, exact remote-main commit, production certificate and APK hash/size.

For0.4.6 an upload review required stronger proof. A separate clean checkout of
release commit2c177b2 was built with the verified pinned Matter artifacts and the
same production key. Every APK entry and ZIP property, the v2signature block and
all bytes outside the signing block match the prepared release. Full APK hashes
are not identical because AGP hybrid-encrypts SDK dependency metadata differently
per build (block0x504b4453); both underlying dependency reports/protobufs were
compared and match. Keep that distinction explicit. The private receipt is
.local/tv-hello/android-player/release-clean-source-proof.json. The normal publisher
was retried only after those checks resolved the source-mismatch concern.

A future Gradle-root layout change could enable native VCS discovery, but requires
its own build validation. Do not alter the checkout layout merely to bypass an
approval rejection. If independent rebuild payloads differ, investigate before
publishing; a similar version string is not adequate source proof.
