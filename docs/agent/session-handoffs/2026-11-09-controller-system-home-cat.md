# Controller across apps, Home hold and Cat Mode — 2026-11-09

## Goal

Correct controller buttons across other apps, retain Android's already-working
stick navigation, add a Cat Mode launcher entry, and make long Home put the
controller to sleep followed by the projector power command.

## Files inspected

Agent index/current-state/repo-map/open-questions; MainActivity/layout,
ControllerInput/Profile/SetupActivity/ProfileStore, HidHostTransport,
ControllerLeds/NintendoPlayerLights, ReceiverApp/ControlServer, CatModeState,
phone NativeBackend and LUMA protocol/docs for existing power/key transport.

## Files changed

- ControllerSystemService + Accessibility XML/manifest/strings: opt-in key
  filtering for calibrated controllers, skip focused receiver Activities and
  calibration Home capture, preserve paired key-up consumption. No screen
  contents or gestures requested. Existing analog input passes through.
- LocalProjectorKeys: bounded serial worker, loopback-only EShare handshake,
  existing no-password configuration check and ordinary KEYEVENT dispatch.
- HomeHoldState + five tests, ControllerHomeHold: short Home on release;
  three-second hold, one fire per hold, early/cancel/disconnect guards, save
  before sleep, generation-guarded bounded HID/power request sequence.
- HidHostTransport: report-specific description; ordinary sleep report 06 00
  uses its existing permission-checked Android 9 HID output path. No shipment
  mode, pairing reset, firmware/driver or hidden-API policy changes.
- ReceiverApp lifecycle ownership; ControllerInput local Home fallback;
  ControllerSetupActivity across-app settings/help; ControlServer diagnostics
  and settings entry point; MainActivity/layout Cat Mode entry and movement.
- Build version 0.3.26/code40; agent current-state/log/open-questions/repo-map/
  Games docs and this handoff.

## Commands run

- Targeted rg/sed, official Android API/AOSP and original protocol research.
- Offline Gradle testDebugUnitTest + assembleRelease via private release config;
  Node receiver-manifest test, XML result count, APK hash, git diff --check.
- receiver-dev games.status, deploy --no-build, controllers.system/accessibility,
  apps.list (settings entries), native Home/navigation and focus diagnostics.
- Read-only adb connect attempt to the known projector port 5555 (refused).

## What worked

- 126/126 receiver tests, manifest test and signed build pass. Gesture tests
  cover short/long, repeated firing, early cancellation, disconnect and another
  device releasing the button; calibration still distinguishes Home/Capture.
- Installed 0.3.26/code40 at 2026-11-09T20:08:51.770Z, data preserved. APK
  3,197,520 bytes; SHA-256
  `22fba00e91b3e443d7f5e259facddbd4453640c5ea0cec12d8773c4b00d48164`.
- Loopback projector input transport reports ready. User enabled LUMA Controller
  via Kodak general settings; service reports enabled=true and four translated
  physical presses at the first check (last output key 23/OK). The user confirms
  A selects, B goes back and the left stick continues working in other apps.
- Existing NES mappings, Capture quick load, player LEDs and phone are unchanged.
  No public release, commit, push or system-driver modification.

## What failed / limits

- Kodak omitted the standard Accessibility settings action and any exported
  direct accessibility panel. Initial 0.3.25 action returned INTERNAL; 0.3.26
  checks available panels and falls back to general settings. The user found
  and enabled the service there. Android debug port is not available.
- Android 9 Accessibility cannot globally intercept analog MotionEvents; those
  hooks start at API34. The user explicitly accepted preserving the working
  Android stick mapping and only correcting buttons.
- Key sends and HID transport acceptance are not hardware acknowledgement.
  Controller sleep and projector off vs standby still require physical testing.
- Global buttons use complete remote key taps; app-specific media-key support
  still depends on the target app. Own focused Activities keep the local path.

## Sources

- Android key filtering (before device policy/apps; modifying copied events
  does not remap them): https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onKeyEvent(android.view.KeyEvent)
- MotionEvent observation API34: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#onMotionEvent(android.view.MotionEvent)
- System key layout files: https://source.android.com/docs/core/interaction/input/key-layout-files
- Original Nintendo protocol notes, subcommand 06 argument00 normal sleep:
  https://github.com/dekuNukem/Nintendo_Switch_Reverse_Engineering/blob/master/bluetooth_hid_subcommands_notes.md
- Projector key26 power behavior is the existing official-app-derived path in
  phone NativeBackend and docs/agent/luma-remote.md; no new shutdown assumption.

## Remaining questions

- Physical Cat Mode launch/stick movement/A pounce/B exit question is pending.
- After Cat test, hold Home three seconds and observe controller lights off,
  then projector power off/standby. Do not report those as accepted prematurely.

## Suggested next prompt

“Finish physical Cat Mode and long-Home power acceptance; preserve accepted
cross-app buttons, Android stick navigation and NES Capture mapping.”
