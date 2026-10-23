# Receiver-first cold-boot casting (2026-10-23)

## Goal

Make LUMA Receiver available as early as possible after projector power-on so a
saved phone can cast before the projector's native EShare/navigation protocol
appears, while letting an accepted cast foreground the receiver UI.

## Files inspected

- Required agent pages: `index.md`, `current-state.md`, `repo-map.md`,
  `open-questions.md`.
- Receiver startup and foregrounding: `android-receiver` manifest,
  `ReceiverApp.java`, `StartupReceiver.java`, `ReceiverService.java`,
  `PlaybackController.java`, `MainActivity.java`.
- Phone connection/UI paths: `NativeBackend.java`, `ConnectionService.java`,
  phone `MainActivity.java`, `src/mobile/app.js` and their focused fixtures.

## Files changed

- `NativeBackend.java`: receiver endpoint configuration can use a validated
  saved private host without a native socket; snapshots expose overall,
  receiver and native readiness independently.
- `ConnectionService.java`: saved auto-connect tries receiver port 4191 first,
  attaches native port 8121 later, preserves direct playback after native loss,
  and gates volume/notification hardware actions on native readiness.
- Phone `MainActivity.java`: receiver-only state schedules a 15 s native retry;
  two-second receiver status publications cannot postpone the queued retry.
- `src/mobile/app.js`: receiver-only state is visibly ready for casting while
  native navigation, app, volume and power controls remain disabled.
- Backend/service/browser fixtures and the backend runner count.
- `release-notes/0.4.11.txt`: user-facing notes for the next guarded release.
- Agent current state, receiver notes, repo map, open question, log and this
  handoff.

The receiver APK itself did not need a source change: it already declares
`RECEIVE_BOOT_COMPLETED`, starts a `START_STICKY` foreground service/control
server, and brings its Activity forward from `player.open`.

## Commands run

```sh
git diff --check
/Users/kian/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline -PreceiverFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
ANDROID_TEST_PACKAGE=dev.framextv.lumaremote.receiverfixture ANDROID_TEST_SERIAL=emulator-5554 node scripts/test-android-backend.mjs
ANDROID_TEST_PACKAGE=dev.framextv.lumaremote.receiverfixture ANDROID_TEST_SERIAL=emulator-5554 node scripts/test-android-service.mjs
node --test tests/handoff.test.mjs
npm run build
npm run test:browser
npm test
```

## What worked

- Android backend: 15/15, including direct saved host/token readiness with no
  native projector socket.
- Android service: 18/18, including direct-first restore, later native attach,
  and active playback/polling after native TCP loss with hardware actions gone.
- Focused handoff tests: 80/80.
- Browser: 158/158, including casting from a direct-only boot snapshot without
  `luma.launchReceiver`, then enabling Home after native readiness.
- Debug and fixture Android app/test APKs compile successfully.

## What failed

- The first service rerun exposed stale hardware volume actions after native TCP
  loss. Readiness gating was corrected; the next complete run passed 18/18.
- The aggregate `npm test` run printed passing tests, then remained silent with
  ten files still active. It was interrupted rather than claimed as a pass.
  The relevant handoff file was rerun alone and passed 80/80.
- An initial service invocation used the normal package and correctly refused to
  install it as the isolated `.receiverfixture`; rebuilding with
  `-PreceiverFixture=true` fixed the invocation.

## Remaining questions

- Physical projector behavior is not verified. Power-cycle it and measure first
  visible UI, first port-4191 response, first accepted cast and first port-8121
  response. Confirm the foreground service survives the vendor's boot policy.
- No phone or projector APK was installed, no release was built/published, and
  no live media was changed. Release 0.4.11 notes are prepared so the guarded
  release command will not stop at its notes gate.
- If the aggregate Node silence recurs, isolate the ten unfinished test files;
  it is not evidence of a product failure, but the aggregate check is incomplete.

## Suggested next prompt

"Build the next signed phone candidate, install it without changing current
projector playback, then power-cycle the projector and measure LUMA Receiver
port 4191/cast readiness against native port 8121 readiness."
