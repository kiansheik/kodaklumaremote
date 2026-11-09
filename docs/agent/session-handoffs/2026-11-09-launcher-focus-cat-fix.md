# Launcher first focus and Cat tile repair — 2026-11-09

## Goal

Fix initial joystick focus landing at the bottom of the launcher and Cat Mode
doing nothing when selected. User explicitly deferred the power test until Cat
works; do not trigger power-off while validating this change.

## Files inspected

Required agent index/current-state/repo-map/open-questions; MainActivity/layout,
ControllerNavigation/Input/SystemService, CatToyView/CatModeState,
PlaybackController and LauncherCatalog; build/test results and live APIs.

## Files changed

- MainActivity: reset to first app/scroll top on launcher resume and Home;
  activate the focused clickable idle control without a hardcoded button list;
  share Cat movement for native/translated directional keys; restore first-app
  focus after Cat exit.
- Receiver build version 0.3.27/code41; current-state/log/open-questions/Games
  docs and this handoff. Private live test script remains ignored in .local.

## Commands run

- Targeted rg/sed, git diff --check.
- Live receiver-dev cat.status/controllers.navigation/controllers.system and
  native Home → Right → OK to reproduce the failure before editing.
- Private release config + offline Gradle testDebugUnitTest/assembleRelease.
- Verified no active game, receiver-dev deploy --no-build.
- node .local/nes-live/launcher-cat-check.mjs (bounded native-key/API assertions).

## What worked

- Before fix: focus was Cat Mode; OK left cat.active=false and moved focus to
  Open App Store. Source confirmed the idle OK whitelist omitted cat_open.
- 126/126 tests and signed release pass. Installed at
  2026-11-09T20:18:23.099Z, data preserved. APK 3,197,516 bytes, SHA-256
  `944e58b19f344e3d0080a994cc16468f3608e2a4ed94ee9039b508e9e03e6579`.
- On the real projector, Home focused the first apps.list entry; after three
  Down presses, Home restored it. Navigation reached Cat Mode; OK made it active,
  Right made manual=true, OK kept it active, Back stopped it and returned focus
  to the first app. The confirm check establishes routing/session continuity,
  not a visual proof of the pounce animation.
- Existing across-app mappings, Capture, checkpoints and Home hold are unchanged.
  No power action, public release, commit or push.

## What failed / limits

- Earlier unit/build checks did not cover Cat tile activation in the idle OK
  handler; the physical failure is now reproduced and checked after repair.
- Focus reset is verified with actual native keys and API readback. Exact first
  physical joystick movement and Cat picture/pounce still need user confirmation.

## Remaining questions

User test pending: launcher starts at first app; Cat opens and responds to
stick/A/B. Only after acceptance should long-Home power testing resume.

## Suggested next prompt

“Confirm launcher focus and Cat Mode with the controller; keep power testing
deferred until those are accepted.”
