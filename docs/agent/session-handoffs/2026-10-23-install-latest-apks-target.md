# Guarded dual-device APK install target

## Goal

Provide one simple `make install-latest-apks` command to install the current
Gradle release APKs on the phone and projector.

## Files inspected

- `Makefile`, Android release APK outputs and release build/signing scripts
- physical phone package metadata and projector info/player status
- `scripts/receiver-dev.mjs`, `docs/releasing.md`, and required agent docs

## Files changed

- `Makefile`
- `scripts/build-latest-apks.mjs`
- `docs/releasing.md`
- agent index, repo map, current state, log and this handoff

## Commands run

- `make -n install-latest-apks`
- `aapt2 dump badging` on both previous Gradle APK outputs
- physical phone `dumpsys package`; receiver `info` and `status`
- `git diff --check`

## What worked

- The target uses the stable Gradle output paths rather than embedding versioned
  filenames, preserves phone data with `adb install -r`, and reuses the existing
  receiver deployment path.
- It now rebuilds both signed APKs from current source first, without release
  manifests, so a successful old build cannot silently be reinstalled.

## What failed

- The first implementation incorrectly required `.local/releases/...` update
  manifests, so the user's direct invocation failed before installing anything.
  That dependency and its helper script were removed.
- The simplified target then exposed a second issue: the two existing Gradle
  outputs were stale 0.4.23/0.3.3 packages. The user's installation therefore
  changed no feature behavior. Physical version reads and `aapt2` confirmed it.

## Remaining questions

- The command has not yet been run against the physical Samsung and projector.
- Rebuild/install and physical post-install version/feature acceptance remain to
  be completed.

## Suggested next prompt

`The release build finished and both devices are available; run make install-latest-apks.`
