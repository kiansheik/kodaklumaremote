# Receiver-first cold start and disconnected casting

## Goal

Make the project's receiver usable immediately after projector startup and let
a saved phone Library cast reconnect to it without waiting for the native LUMA
remote/navigation service.

## Files inspected

- `docs/agent/index.md`, `current-state.md`, `repo-map.md`, `open-questions.md`
- `src/mobile/app.js`
- `android/app/src/main/java/dev/framextv/lumaremote/ConnectionService.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/StartupReceiver.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/Updater.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/MainActivity.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/ControlServer.java`
- relevant browser, receiver-unit and Android service instrumentation tests
- release/build/deploy scripts and the saved ignored receiver-dev configuration

## Files changed

- `src/mobile/app.js`
- `android/app/src/main/java/dev/framextv/lumaremote/ConnectionService.java`
- `android/app/src/androidTest/java/dev/framextv/lumaremote/ServiceInstrumentation.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/StartupReceiver.java`
- `android-receiver/app/src/test/java/dev/framextv/lumareceiver/StartupReceiverTest.java`
- `android-receiver/app/build.gradle`
- `android/app/build.gradle`
- `tests/browser/mobile.spec.js`
- `scripts/test-android-service.mjs`
- `release-notes/0.4.23.txt`
- this handoff, `docs/agent/current-state.md`, `docs/agent/log.md`, and
  `docs/agent/index.md`

## Commands run

- `node scripts/receiver-dev.mjs status|info|call ...|keys ...`
- focused Playwright tests for booted and saved-offline receiver casting
- receiver `:app:testDebugUnitTest`
- phone app/AndroidTest Java compilation
- isolated `LanguageMemory_AnalysisTest` AVD service instrumentation
- `git diff --check` and `node --check src/mobile/app.js`
- `make deploy-gh-pages`
- `adb devices -l`, `adb mdns services`, and
  `adb connect 192.168.68.55:5555`
- APK `stat` and SHA-256 checks

## What worked

- The physical projector answered authenticated receiver requests on port 4191
  while native navigation was unavailable. This separated receiver readiness
  from the phone's old `connected` UI gate.
- A configured or saved LUMA target remains available on Remote and in Library.
  Casting now reaches the coordinator, tolerates `NOT_CONNECTED`,
  `CONNECTION_FAILED`, or `TIMEOUT` from the optional native wake hint, and
  waits for direct receiver readiness.
- A recreated Android phone service hydrates the saved receiver and encrypted
  token before a direct `receiver.connect`, so it does not depend on the
  foreground auto-connect loop.
- Receiver boot and package replacement both start the service and foreground
  the activity. Unit coverage verifies the action filter.
- Focused browser tests passed 2/2; receiver unit tests passed; the complete
  service instrumentation passed 19/19; guarded release passed all 164 browser
  tests and both signed release builds.
- Release `v0.4.23` was published from commit
  `224a878b7a17c96dd97c0547d3e44c0953899dcb`:
  - phone 0.4.23/code33, 9,822,118 bytes,
    SHA-256 `6fcb3c7ad7ff9297c545a27d4eba572d92b0511daefa2247272b69aaf35d0758`
  - receiver 0.3.3/code17, 1,134,601 bytes,
    SHA-256 `3250643ef08f221c6176b3b0775f57bd3caef9ac6cc7b626f0c57c11f26bcafb`
- The physical receiver downloaded and verified 0.3.3 from the public updater.
  `update.install` reached Android's confirmation; the known EShare
  `down,right,enter` sequence completed it. Version 0.3.3/code17 then returned
  healthy on port 4191, authenticated status was idle, and `app.snapshot`
  succeeded, which requires the foreground activity's UI callback to be bound.

## What failed

- Sandboxed ADB could not bind its local smart socket; the unrestricted retry
  worked but found no devices or mDNS endpoints.
- Projector ADB at `192.168.68.55:5555` refused the connection. The signed
  receiver updater was used instead.
- The first unprivileged receiver control call was network-blocked; the scoped
  LAN retry succeeded.
- Two full emulator service runs hit existing timing-sensitive assertions in
  unrelated canonical-transition/back-30 sections. A clean rebuild and third
  isolated run passed 19/19, including the new direct-cast restoration check.
- The phone APK could not be installed because the Samsung exposed no USB or
  wireless ADB endpoint.

## Remaining questions

- Does a true projector power-off/power-on show Receiver 0.3.3 and answer port
  4191 without any manual launch? Package replacement exercised the same
  foreground helper, but is not a cold-boot acceptance test.
- After the phone accepts its normal in-app 0.4.23 update, does selecting a
  Library item during the native LUMA delay open and play exactly once on the
  real projector?

## Suggested next prompt

`The phone is updated to 0.4.23. Power-cycle the projector and verify the first receiver screen, direct cast, and later native-control connection without changing pairing.`
