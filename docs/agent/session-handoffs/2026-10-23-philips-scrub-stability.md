# Philips scrub stability and restart reconnect

## Goal

Stop the phone remote from blinking/strobing when the user scrubs backward in a
video, preserve the hidden-cursor Philips receiver behavior, publish the fix,
and install it on the physical Samsung.

## Files inspected

- `src/mobile/app.js`
- `tests/browser/mobile-receivers.spec.js`
- `android/app/src/main/java/dev/framextv/lumaremote/MsxServer.java`
- `android/app/src/androidTest/java/dev/framextv/lumaremote/MsxInstrumentation.java`
- `docs/agent/current-state.md`
- `docs/agent/msx-receiver.md`

## Files changed

- `src/mobile/app.js`: replaced generic globally-busy seek dispatch with an
  optimistic, source-guarded, latest-wins Philips seek queue.
- `tests/browser/mobile-receivers.spec.js`: added a held-response regression
  proving controls remain enabled and multiple drags coalesce.
- `android/app/src/main/java/dev/framextv/lumaremote/MsxServer.java`: allow the
  exact saved approved TV to reload bootstrap after a process restart.
- `android/app/src/androidTest/java/dev/framextv/lumaremote/MsxInstrumentation.java`:
  removed a stale assertion that contradicted the intentional private-peer
  candidate bootstrap; later identity/approval assertions remain.
- `release-notes/0.4.21.txt`, `release-notes/0.4.22.txt`
- `docs/agent/current-state.md`, `docs/agent/log.md`, and this handoff.

## Commands run

- Physical Samsung `adb` process, package, service, window and log inspection.
- Physical Samsung six-second screen recording and contact-sheet inspection on
  0.4.20 before the fix.
- Focused Playwright Philips receiver tests: 39/39 passed.
- Full mobile browser tests: 55/55 passed.
- `npm run check`: Node 247/247; first browser aggregate had one unrelated
  responsive-layout timing failure, whose exact test passed immediately alone.
- Gradle offline receiver fixture build followed by
  `ANDROID_TEST_PACKAGE=dev.framextv.lumaremote.receiverfixture
  ANDROID_TEST_SERIAL=emulator-5554 node scripts/test-android-msx.mjs`: 8/8
  passed after aligning the stale candidate-bootstrap assertion.
- `make deploy-gh-pages`: Node 247/247, browser 163/163, Android release build,
  signing and GitHub publication all passed.
- `adb install -r .local/releases/v0.4.22/luma-remote-0.4.22.apk`: succeeded.

## What worked

- The live capture and source inspection isolated whole-app busy redraws during
  the slow seek acknowledgement rather than a player crash.
- The new browser regression proves the seek bar keeps its optimistic value,
  controls stay enabled, and only the in-flight plus latest queued seek go out.
- The restart-specific receiver fixture proves an approved browser can reload,
  reconnect and report state after server recreation; explicit reset still
  revokes the old capability.
- LUMA Remote 0.4.22/code 32 is published from
  `6ee4a17803e234050f15820f2378a3f3ca79804e` and installed on the Samsung.
  APK: 9,822,082 bytes; SHA-256
  `d21683525250e34cd83b7f96d82f4d303e0ab0aee34b424c8cf490fde769007b`.

## What failed

- The first full browser aggregate had one responsive-layout timing flake; the
  exact test passed alone and the guarded release rerun passed all 163 tests.
- 0.4.21 revealed that exact saved-peer bootstrap incorrectly depended on the
  temporary pairing window after app restart. This is fixed in 0.4.22.
- After installing 0.4.22 the Samsung remained on NotificationShade with the
  screen off. The app process and foreground `ConnectionService` run, but no
  fresh `MsxReceiver` request arrived from the TV during the observation window,
  so a post-fix screen recording and physical reconnect proof were unavailable.

## Remaining questions

- After unlocking the phone, does one backward scrub remain visually stable on
  the real Samsung while the Philips receiver acknowledges it?
- Does the still-open TV receiver retry on its own, or does this particular
  browser suspend its retry loop while the phone listener is unavailable and
  require a TV-page reload once?

## Suggested next prompt

Unlock the Samsung, leave the Philips receiver visible, and say: “Unlocked; test
the backward scrub and reconnect now.”
