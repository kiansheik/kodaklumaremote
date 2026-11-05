# Receiver as projector Home

## Goal

Make LUMA Receiver open in the foreground when the projector starts and act as
the default launcher instead of waiting for the vendor LUMA UI/service.

## Files inspected

- `android-receiver/app/src/main/AndroidManifest.xml`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/{StartupReceiver,ReceiverService,MainActivity}.java`
- `android-receiver/app/src/test/java/dev/framextv/lumareceiver/StartupReceiverTest.java`
- `android-receiver/app/build.gradle`
- `android-receiver/README.md`
- `scripts/{build-latest-apks,install-latest-apks,receiver-dev}.mjs`
- `docs/agent/{index,current-state,repo-map,open-questions,log}.md`

## Files changed

- `android-receiver/app/src/main/AndroidManifest.xml`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/MainActivity.java`
- `android-receiver/app/build.gradle`
- `android-receiver/README.md`
- `tests/receiver-manifest.test.mjs`
- `release-notes/0.4.27.txt`
- `docs/agent/{index,current-state,repo-map,open-questions,log}.md`
- This handoff

## Commands run

- `node --test tests/receiver-manifest.test.mjs`
- `node --test --test-reporter=dot tests/*.test.mjs`
- Receiver `:app:testDebugUnitTest :app:assembleDebug`
- Receiver `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`
- `node scripts/build-latest-apks.mjs`
- `aapt2 dump badging` and `aapt2 dump xmltree` on the signed receiver APK

## What worked

- Added a separate `MAIN` + `HOME` + `DEFAULT` filter while retaining ordinary
  launcher and Leanback discovery.
- The idle screen detects whether this exact activity is the current default
  Home. OK opens Home-app settings only while setup is pending, and Menu opens
  it at any time so the stock launcher can be restored.
- The existing boot receiver/sticky service remains responsible for bringing up
  the HTTP receiver independently of Home UI selection.
- Full Node tests pass 253/253. Receiver unit tests and debug assembly pass.
  Signed phone/receiver release assembly and lint-vital pass. Packaged receiver
  metadata is version 0.3.6/code 20 and its manifest contains launcher,
  Leanback, Home and Default categories.

## What failed

- Full receiver debug lint reports 14 existing `NewApi` findings in
  `Diagnostics`, `ExternalCaptions`, `ReceiverApp`, `ThumbnailStore` and
  `Updater`; none is in the new launcher path. Release lint-vital passes.
- The first sandboxed Gradle rerun could not load Gradle's native macOS library;
  the same offline command passed outside that sandbox.

## Remaining questions

- Android requires one explicit user choice; a regular sideloaded APK cannot
  silently make itself the default Home app.
- Install 0.3.6, choose LUMA Receiver in Home-app settings, then hard power-cycle
  the actual projector and measure foreground/UI and port-4191 readiness.
- Confirm this vendor's Android 9 settings activity exposes its Home-app choice
  normally; the code falls back to general Settings if the dedicated page is
  unavailable.

## Suggested next prompt

Install the latest APKs, then walk me through selecting LUMA Receiver as the
projector Home app and test a hard reboot plus immediate cast.
