# Capture checkpoint binding — 2026-11-09

## Goal

Expose Capture in the NES assignment menu and bind the user's Capture button to
Load latest checkpoint, preserving every other controller setting.

## Files inspected

Agent index/current-state/repo-map/open-questions, ControllerSetupActivity,
ControlServer controller APIs, receiver build config and receiver-dev helper.
Live controller profile confirmed Capture was already calibrated to key 106.

## Files changed

- ControllerSetupActivity: add CAPTURE to ASSIGNABLE and label it Capture.
- Receiver build version 0.3.24/code38.
- Agent current-state/log/Games/open-questions and this handoff.
- Private device state: explicitly saved only NES Capture action = 2048.

## Commands run

- Targeted rg/sed; git diff --check.
- Private release loader + offline Gradle testDebugUnitTest/assembleRelease.
- Authenticated controllers.status/show, games.status/stop/play, receiver-dev
  deploy --no-build, and native EShare keys for assignment navigation/save.
- Private before-profile capture and deep comparison with the persisted result.

## What worked

- 121/121 receiver tests and signed release build pass. Installed at
  2026-11-09T19:52:30.364Z, app data preserved. APK 3,195,554 bytes, SHA-256
  `6c3b90ff426bacad2224daa23705000915be9cd9c2fbbec84d3903aad31d15ea`.
- Real UI focus readback showed Capture → Unused, then Capture → Load latest
  checkpoint, then Save NES assignments. Saving returned to overview.
- Persisted profile readback and deep comparison proved Capture = 2048 and
  every other hardware binding, NES assignment and stick option unchanged.
- Mario reopened and was left paused: checkpointCount=1, checkpointLoads=0,
  error=null. The user subsequently confirmed: "Yes, Capture loads the checkpoint."
- Phone unchanged; no public release, commit or push.

## What failed / limits

- An initial profile-snapshot attempt found setup no longer active; its guard
  stopped before writing. Reopened setup after install and captured the profile.
- The assignment screen initially had no focused button; native Down focused
  the first row. Navigation then reached Capture and Save successfully.
- No new runtime logic or storage changes. Existing 0.3.23 history validation
  remains applicable. Physical Capture acceptance is independently user-confirmed.

## Remaining questions

None for this fix: the requested physical Capture quick load is accepted.

## Suggested next prompt

“Continue with the next requested feature; preserve the accepted Capture mapping.”
