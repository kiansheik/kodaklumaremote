# NES duplicate-button retrigger — 2026-11-09

## Goal

Allow holding one NES B binding to run while tapping another B binding to fire.
Each fresh overlapping press must become a frame-visible release/repress, and
releasing one physical binding must preserve all remaining holds.

## Files inspected

- Required agent index, current state, repo map and open questions; Games docs.
- Receiver ControllerInput/Profile/State, GamesActivity, NesGameView, native
  frame entry point and existing controller tests.
- Release build configuration and receiver-dev installation/API commands.

## Files changed

- `ControllerProfile.java`: detect newly pressed logical controls before their
  NES assignments are merged; exclude menu/volume action bits.
- `ControllerInput.java`: deliver held mask plus fresh edges even when the mask
  is unchanged, include new stick directions, cancel pulses on lifecycle changes.
- `NesFrameInput.java` and `NesFrameInputTest.java`: synchronized frame scheduler,
  bounded queues, aggregate holds, lifecycle cancellation and diagnostic counter.
- `NesGameView.java`: sample scheduler immediately before native frame execution.
- `GamesActivity.java`: connect input callbacks and expose `retriggeredPresses`.
- `ControllerProfileTest.java`: three profile-to-frame tests for duplicate B,
  Android repeats/axis aliases, and exclusion of menu/volume.
- Receiver build version 0.3.22/code36; current-state, log and Games docs.

## Commands run

- Targeted `rg`/`sed`, `git status --short`, `git diff --check`.
- Load private release config, then offline Gradle
  `:app:testDebugUnitTest :app:assembleRelease`; inspect test XML and APK badging.
- `node scripts/receiver-dev.mjs call games.status` / `games.show`.
- `node scripts/receiver-dev.mjs deploy --no-build --apk android-receiver/app/build/outputs/apk/release/app-release.apk`.
- `games.play` for the existing private Mario library entry, then `games.status`.

## What worked

- All 114 receiver unit tests pass, including 13 new scheduler tests and three
  profile integration tests. Quick trigger press/release between frames remains
  visible while B is held; repeated taps alternate released/held frames;
  releasing the final contributor clears pending input. Independent review
  found no blocking wiring issue for the requested held-B plus trigger case.
- Signed release build and diff whitespace check pass. APK 3,195,554 bytes,
  SHA-256 `fea39d2f2eddc556c6932d3d1c775e53f5fc737f3f86d8dc2f46459fa264d93c`.
- Installed 0.3.22/code36 at 2026-11-09T19:18:44.645Z, preserving app data.
  Mario reopened and subsequently reached 3,687 native frames with error=null.
  The new counter advanced from zero to 45 consumed retriggered presses during
  physical use. The user then explicitly confirmed: "Yes, firing and running
  work together" when asked to hold B and tap the duplicate B trigger.
  Phone remains 0.4.35.
- Existing mappings, trigger-volume option and accepted steady-player LED path
  were preserved. No public release, commit or push was performed.

## What failed / limits

- The pre-install `games.show` request was accepted, but a subsequent status
  still showed running frames. Do not claim that request proved a stable pause
  or the exact saved position. The installer invokes Activity pause/focus loss,
  whose existing implementation writes the automatic save; reopening succeeded.
- A process check needed sandbox escalation; an initial guessed aapt version
  was absent, then the installed build-tools 36.0.0 binary verified the APK.
- The scheduler guarantees one released emulated frame, not that every ROM
  polls every frame. Multiple same-action logical rises in one Android event
  coalesce into one pulse; separate events queue independently (up to eight).
- Physical fireball/run acceptance is now user-confirmed, independently of the
  unit results and native-frame diagnostics.

## Remaining questions

- The requested held-B plus trigger behavior is accepted; no follow-up is
  required for this fix.
- Future systems can choose different sampling behavior; SNES/GBA remain absent.

## Suggested next prompt

“Continue with the next requested feature; preserve the user-accepted
duplicate-binding retrigger behavior.”
