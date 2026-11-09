# Receiver navigation and Pro Controller lights — 2026-11-09

## Goal and starting evidence

Fix physical controller navigation throughout the receiver and make a bounded
attempt to set the Pro Controller's steady player indicators over its existing
Bluetooth connection. The user confirms that NES gameplay works, but reports
that even the D-pad cannot navigate Controller Setup or receiver Home on
0.3.19. Emulator/unit success did not establish those physical UI behaviors.
The previously saved player slot was app state only; it did not set the lights.

## Files inspected

- Required `docs/agent/{index,current-state,repo-map,open-questions}.md` and
  the controller-calibration handoff.
- Receiver `ControllerInput`, `MainActivity`, `GamesActivity`,
  `ControllerSetupActivity`, `ControllerNavigation`, `ReceiverApp`,
  `ControlServer`, manifest and Gradle configuration.
- `ControllerLeds`, `HidHostTransport`, `NintendoPlayerLights` and packet tests;
  `ControllerProfile`, its tests and the NES assignment editor.
- [Android 9 ViewRootImpl](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/view/ViewRootImpl.java),
  [Android 9 BluetoothHidHost](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android-9.0.0_r1/core/java/android/bluetooth/BluetoothHidHost.java)
  and the [Linux Nintendo HID driver](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-nintendo.c).

## Diagnosis and changes

Android's `ViewRootImpl` performs automatic focus navigation after an unhandled
key returns from the view hierarchy. Our adapter consumed the original
controller event, then dispatched a translated D-pad key directly into the
Activity. That translated call never reached the framework's later focus step.
NES reads the logical button mask directly, explaining why gameplay worked while
menu navigation failed. This is an application event-routing defect; it does
not itself indicate that a replacement Bluetooth driver is needed.

New `ControllerNavigation.java` explicitly finds and focuses the next view,
scrolls it into view and provides a confirmation fallback. Initial focus prefers
an enabled, visible, clickable target rather than an inert ScrollView. Repeat
confirmation does not click again; key-up is consumed. A bounded snapshot reports
focused label/class, touch mode and the last before/after navigation result.

`MainActivity` now routes translated idle directions through this helper and
uses it as a fallback after existing media semantics. `GamesActivity` and
`ControllerSetupActivity` route translated directions explicitly, retaining
existing Back/Menu/Home and capture-mode handling. Main's recursion guard keeps
translated keyboard events from being reprocessed as physical controller input.
`ReceiverApp` and authenticated `controllers.navigation` expose focus evidence;
setup status also includes navigation diagnostics.

New `ControllerLeds` requests the existing Android 9 HID-host profile and tries
its firmware-provided `sendData` method. `NintendoPlayerLights` builds output
report 0x01/subcommand 0x30 with neutral rumble and steady player masks 1/3/7/15.
This changes neither pairing nor input-report mode and installs no driver. The
manifest adds legacy Bluetooth permissions limited to Android 11 and earlier.
The attempt is limited to Nintendo Pro Controller VID/PID, rejects ambiguous
multiple connected Pro Controllers, times out after three seconds, closes the
profile proxy and reports restrictions/failures without changing input handling.
Saving a player slot requests lights; authenticated `controllers.setLights` and
`controllers.lights` provide explicit request/status paths.

A Bluetooth acceptance result is `sentUnconfirmed`, not proof of steady lights.
The helper always reports `confirmed:false`: controller acknowledgment and human
observation are distinct from the framework accepting a request. Android's lack
of the newer public lights API does not by itself prove the older HID-host output
path unusable; hidden-API/OEM restrictions must be measured on this projector.

Actual 0.3.20 probing returned `hiddenApiUnavailable`: the HID profile/inventory
worked, but method reflection was blocked. Added `HidHostTransport`, limited to
API 28, resolving an exported/enabled system service in `com.android.bluetooth`,
checking its declared permission, binding explicitly and verifying the interface
descriptor. Public Binder calls use the exact
[Android 9 HID AIDL](https://android.googlesource.com/platform/system/bt/+/android-9.0.0_r1/binder/android/bluetooth/IBluetoothHidHost.aidl)
transactions: inventory 3 and sendData 13. Existing Bluetooth permission checks
remain in force. Output runs on a worker, with timeout/unbind cleanup and one
unambiguous connected Pro Controller. Pending assignments use the latest slot.

The user additionally requested in-game trigger volume. `ControllerProfile` now
supports separate Volume Down/Up actions 512/1024. `ControllerInput` handles
their press edges via the media AudioManager, while only low-eight NES bits
reach the emulator. New profiles default ZL/ZR to volume; existing saved values
are preserved. The NES editor offers both actions and a **Use ZL / ZR for volume**
draft preset, followed by explicit **Save NES assignments**.

## Commands and validation

- Targeted `rg`/`sed` reads and `git diff --check`.
- Standalone `javac` of `ControllerNavigation` against the Android 36 SDK passed.
- Receiver Gradle `:app:testDebugUnitTest :app:assembleRelease` passed **98/98**
  tests and the signed optimized build. The two added packet tests cover exact
  steady-player reports, neutral rumble bytes and invalid player/counter bounds.
- Deployment uses the existing authenticated helper:
  `node scripts/receiver-dev.mjs deploy --no-build --apk android-receiver/app/build/outputs/apk/release/app-release.apk`.

Installed: **receiver 0.3.21/code35**, **3,195,554 bytes**.
SHA-256: `80b307265c37383e73a87ade31b3236073adea95337df53e1fdae7222d806f7b`.
Install timestamp: `2026-11-09T18:32:52.444Z`.
No public release, replacement driver, unpairing or system-policy bypass was
performed. ROMs, device credentials and private connection details stay out of Git.

## What worked and what remains unverified

The framework-source diagnosis explains the directional-navigation failure;
helper compilation, unit checks and signed build pass. Prior testing wrongly
used ordinary remote D-pad input to establish focus in some smoke paths: those
events reach the framework fallback and cannot prove translated gamepad focus
navigation. Verify with gamepad-sourced directions and observable before/after
focus changes, including scroll into view and confirmation from a fresh screen.

Physical focus telemetry recorded a controller direction moving from the ZL row
to ZR in NES assignments; the user explicitly confirmed navigation works. The
saved full hardware calibration was preserved (including RX/RY stick axes and
hat-axis D-pad); no remapping reset was needed. In 0.3.21 the Binder route returned
`sentUnconfirmed`, and the user then explicitly answered **Yes, it is steady now**
for Player 1's physical indicator. Thus both navigation and steady LEDs are
human-accepted. In-game volume action configuration is implemented/tested; physical
acceptance after choosing and saving the preset remains separate.

## Remaining questions and next prompt

- Confirm in-game ZL/ZR volume after choosing and saving the preset.
- Confirm saved mapping and steady-light reapplication after reconnect/restart.
- Multiple controllers retain separate slots, but ambiguous identical Bluetooth
  controllers deliberately prevent guessing which controller receives a light
  command; actual multiplayer and a reliable identity association remain future work.

Suggested next prompt: “On installed receiver 0.3.21, check the saved NES trigger
volume preset and controller reconnect behavior, preserving the accepted
navigation and steady-player-light implementation.”
