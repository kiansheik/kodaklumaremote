# Receiver controller calibration — 2026-11-09

## Goal

Make the paired Nintendo Pro Controller useful throughout the receiver, with
both sticks, visible feedback for every control, guided physical calibration,
saved hardware profiles and separate NES assignments. Add remembered player
slots for future multiplayer. Continue the existing phone ROM import/sync and
projector-local NES work without disturbing earlier launcher/Cat/Air Mouse work.

This handoff starts from phone **0.4.35/code45** and receiver **0.3.18/code32**
already installed on the physical devices. The ROM synchronized to the phone,
ran on the Android 9/ARM32 projector and paused with a stable frame count. The
user paired the full-size Pro Controller and accepted basic receiver navigation,
then reported the missing right stick. Those observations do not establish
acceptance of the new **0.3.19/code33** calibration candidate.

## Files inspected

- Required `docs/agent/{index,current-state,repo-map,open-questions}.md`,
  `games.md` and the earlier projector NES handoff.
- Receiver `ControllerInput`, `ControllerState`, `MainActivity`, `GamesActivity`,
  `ReceiverApp`, `ControlServer`, manifest, Gradle configuration and unit tests.
- New calibration/profile/player classes and their tests, setup Activity and
  controller diagram. Targeted reads of the receiver developer and NES test
  scripts; ignored test receipts/screenshots stayed private.
- Official Android input-device and controller-light API documentation linked
  below. Code and test results remain authoritative over these notes.

## Files changed

All Java files below are in
`android-receiver/app/src/main/java/dev/framextv/lumareceiver/` unless stated.

- New `ControllerProfile` and `ControllerProfileStore`: strict, versioned
  hardware mappings and independent NES actions; explicit save, stable device
  identity and model fallback, with profile reload after setup.
- New `ControllerCalibration`, `ControllerSetupActivity` and
  `ControllerDiagramView`: full Pro Controller diagram, live diagnostics,
  22-step wizard, review/save/cancel, NES assignment editor and shortcut help.
- New `ControllerPlayers` and `ControllerPlayerStore`: separate persisted
  player assignments, four slots, swaps, connection inventory and bounded
  remembered entries. Snapshots omit raw device descriptors.
- `ControllerInput`, `ControllerState`, `MainActivity` and `GamesActivity`:
  profile-aware navigation/gameplay, both sticks, release/neutral gates,
  callback generation guards, logical media controls and public touch-mode
  focus recovery. Current NES gameplay accepts only Player 1; all players can
  navigate the receiver.
- `ReceiverApp`, `ControlServer`, manifest and receiver Gradle version:
  setup lifecycle, authenticated `controllers.show/status/snapshot/players`
  methods, Activity registration and candidate version 0.3.19/code33.
- Receiver tests: `ControllerCalibrationTest`, `ControllerProfileTest`,
  `ControllerProfileAdversarialTest`, `ControllerPlayersTest` and
  `ControllerStateTest`. Documentation integration is owned by the parent task;
  this handoff was prepared separately to avoid concurrent edits.

## Behavior and boundaries

The wizard first captures right/down movement for each stick, then the four
face buttons, L/R/ZL/ZR, Plus/Minus, both stick clicks, four D-pad directions
and optional Home/Capture. It locks capture to the selected controller, rejects
ambiguous or duplicate gestures, waits for release/neutral between steps and
requires fresh axis evidence after focus loss. Unknown key codes and invalid
axis spans produce a retry/skip prompt. Cancel preserves the saved profile;
draft mappings become active only after Save. Diagnostics consume raw controller
input; a Plus/Minus hold exits the test. Review seeds its held-button state so
releasing that chord cannot accidentally select Save.

Hardware calibration is separate from NES actions. Either stick can navigate
the receiver; the right stick is opt-in for NES directions. Menu is a remappable
NES action, so assigning a shoulder to A/B is honored. Unmapped controller keys
are consumed instead of falling through to Android's default Back/Menu/focus
behavior. Phone/projector remote navigation remains available during calibration.
These controls apply within the receiver; third-party apps keep their own input
handling. Android continues to own Bluetooth pairing.

Player slots 1–4 persist across disconnects. Selecting an occupied slot swaps
the occupants; an unassigned controller taking a slot leaves its old occupant
unassigned. Records are bounded at 64 and only unassigned records are evicted.
This is assignment state for future multiplayer, not four-player NES support.
Synthetic test input uses Player 1 without reserving a hardware slot.

The Android 9 projector has no supported public controller-light API equivalent
to [`InputDevice.getLightsManager()`](https://developer.android.com/reference/android/view/InputDevice#getLightsManager()),
which was added in API 31. Android also added
[`Light.LIGHT_TYPE_PLAYER_ID`](https://developer.android.com/reference/android/hardware/lights/Light#LIGHT_TYPE_PLAYER_ID)
in API 31; it still depends on lights exposed by the device driver. The app
therefore shows player assignments on-screen and reports LED management as
Android-owned. No claim is made that it changes the Pro Controller's physical
player LEDs. Vendor/hidden Bluetooth HID output remains unverified.

## Commands run

- Targeted `rg`/`sed`, `git status --short` and `git diff --check`.
- Host `javac` and JUnit runs for independent calibration/profile regressions
  (17 tests) and player assignment state (6 tests).
- Receiver Gradle `:app:testDebugUnitTest` and signed release build. Final
  receiver test XML reports **94 tests, zero failures/errors**; the signed build
  passed. Signing material and generated artifacts remain outside Git.
- Final receiver artifact: **0.3.19/code33**, **3,195,478 bytes**, SHA-256
  `47bb794b63fa74d365746d38c3850fc73b23af8abb7ca039d2feb341ea67707b`.
- Isolated-emulator NES regression through
  `scripts/test-receiver-games.mjs` with a private ROM path: native gameplay,
  pause/checkpoints, automatic resume and process restart passed in the initial
  regression run. This is distinct from the later controller/media smoke below.
- Authenticated receiver developer calls for Games/controller state and private
  UI captures; physical installation/pairing/library-sync checks belong to the
  parent task. No ROM bytes, credentials, device addresses or active media URLs
  are included in this handoff.

## What worked

- Unit coverage checks exact profile parsing, independent emulator assignments,
  arbitrary key codes, axis inversion/drift, alternate key/hat releases,
  opposite-direction neutralization, selected-device capture, duplicate inputs,
  held/repeated buttons, focus loss, skipped OS-owned controls, cancel isolation,
  bounded player records and assignment swaps.
- Review fixed unknown-key/short-axis-range crashes, stale axis capture after a
  cancel/focus transition, hidden unmapped-key navigation, and accidental Save
  after leaving diagnostics with the exit chord still held.
- The earlier physical phone/projector install, ROM sync, native advancement
  and Pro Controller pairing/basic navigation were observed before this build.

## What failed or remains unverified

- The initial controller focus smoke did not move/select reliably while the
  window remained in touch mode. The cause was focus handling for synthesized
  logical navigation, not proof of a bad Bluetooth mapping. The final code uses
  public `requestFocusFromTouch()` at initial focus points and
  `ControllerInput.prepareNavigation()` before logical key dispatch; it does not
  use hidden APIs. An intermediate touch-mode-focusable approach was reverted
  because a first tap only focused its button instead of activating it.
- The final isolated game smoke passed Plus confirmation, remappable Menu,
  unmapped Back isolation and Home return. The media Plus/pause assertion also
  passed. The first seek assertion exceeded the 52.208-second test fixture;
  corrected 5 → 35 → 25-second seeks pass. The volume assertion first used the
  wrong response field, then exposed a real issue: Activity-level synthetic
  keys do not reach system volume handling. `ControllerInput` now adjusts the
  media AudioManager directly on logical trigger edges. The final smoke passes
  both seeks, both volume triggers, play/pause/resume and Home. Fresh game-row
  tests explicitly establish focus before Plus; native key timing is distinct
  from programmatic focus requests during layout.
- Physical 0.3.19 installation is verified by version/code and changed install
  timestamp. Completed 22-step calibration, saved-profile reload, both-stick
  navigation and NES mapping acceptance remain pending. Projector picture/audio/frame pacing and controller disconnect
  release require physical acceptance; emulator frames do not prove these.

## Remaining questions

- Which raw keys/axes does this projector expose for every Pro Controller
  control, especially Home/Capture and the right stick?
- Does the saved physical mapping survive reconnect and Activity/process
  restart, while a cancelled calibration leaves it unchanged?
- Do both-stick navigation, media controls, simultaneous NES direction/button
  input, remapped Menu and disconnect release behave correctly on the projector?
- Does the phone's actual file manager offer the intended Open-with import?
  Picker import remains the fallback. SNES/GBA, additional NES controller ports
  and phone synchronization of save states remain future work.

## Suggested next prompt

“Finish the physical Pro Controller calibration on installed receiver 0.3.19.
Verify saved-profile reload, both sticks, media controls, Player 1 NES gameplay,
remapped Menu and disconnect release. Record visible picture/audio acceptance,
then update this handoff and the current-state/log with the exact final build
hash and observed results.”

## Live checkpoint

Receiver **0.3.19/code33** installed at `2026-11-09T18:04:38.340Z`, preserving
data. `controllers.players` independently shows Player 1 occupied by the Pro
Controller and Players 2–4 empty. An initial setup snapshot returned `NO_UI`
despite active lifecycle status. After returning through native Home and opening
setup again, the native screenshot succeeded and was visually checked on the
actual projector. Final status shows a saved profile and live left-stick input;
the Android axes include X/Y and RX/RY. These are device observations, not human
acceptance of every calibrated button. The final screen remains available for
calibration/testing. Private screenshot: `.local/nes-live/controller-projector.png`.
