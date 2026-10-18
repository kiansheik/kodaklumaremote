# Handoff — saved setup and background phone controls

## Goal

User confirmed baseline phone connection/control and requested background/system
controls, saved login/cached IP, automatic single-projector connection, compact
status, corner Home/Back, a real volume slider and seamless video/subtitle Cast.

## Files inspected

Required agent notes; prior phone/LUMA/Kodi evidence; actual UI/native/tests;
official Android foreground service/MediaSession/Keystore documentation; REA
volume setter/wrapper/constant/getter/SeekBar static evidence. Phone inspection
was limited to own app, Android version and fixed diagnostic tags.

## Files changed

- `ConnectionService.java`, native manifest/Gradle: foreground connection,
  MediaSession/notification, observed state/heartbeat, immediate disconnect,
  generation guards, automatic connection and version0.2.0/code2.
- `SettingsStore.java`: app-private cached receiver plus Android Keystore
  AES-256-GCM encrypted login, saved after authentication; scoped reset.
- `MainActivity.java`: service binding/resume, foreground automatic retries,
  per-Activity UUID tokens, notification-open and stage-only diagnostics.
- `NativeBackend.java`: state/auth clearing, actual absolute-volume protocol,
  observed-range normalization/readback and source-bound controls.
- Mobile HTML/JS/tests: one-time validated setup, saved/secondary Settings,
  status dot, corner icons, five-button D-pad, slider/±, pending-Cast continuation.
- Android service/UI/settings fixtures and runners; strict desktop pairing
  gate/tests; README and agent state/map/log/decisions/handoff documentation.

## Commands run

- `npm run check`: build, **109/109 Node** and **65/65 browser** checks passed,
  including22 mobile cases. Receiver transports are mocked in browser fixtures.
- Offline Gradle9.6.1/AGP9.3.1 app/test assembly passed; JDK21, SDK35/Tools36.
  Generated web assets are bundled automatically before APK assembly.
- Native backend: **8/8** checks plus exact wire assertions passed on unchanged
  final backend code, including raw7/max15→setVolume14 and actual6/15 readback.
- Final exact APK: **4/4 service** checks passed (authenticated saving, failed
  candidate preservation, cached restore/no replay, source/epoch guards,
  absolute/system volume, stalled-request Disconnect in30ms without Stop).
- Final exact APK: **5/5 packaged UI** checks passed,320×592 with zero clipped
  controls/scrolling, isolated bridge and Activity UUID/navigation cancellation.
- `node scripts/test-android-settings.mjs`: **5/5** real Android Keystore,
  new-store persistence, encrypted/tamper protection, validation and scoped reset
  checks passed. All native fixtures install only on emulator5554, not the phone.
- APK0.2.0/code2,1005572bytes,
  SHA-256 `28f566f36e175af21d58467b779e0c4c16daaf74a02bfbd26f7fda4a7cd3e651`.
  `adb install -r` successfully updated the paired Samsung. Live Cast/system
  control acceptance was requested and is still pending.
- Final whitespace/local-link/private-runtime-value scans passed. Source,
  documentation and reproducible tests committed locally; no push/deployment.

## What worked

Baseline APK installed on Samsung Android16; user confirmed projector control.
Its Cast entered setup/opened Kodi but did not complete media/subtitles. Setup
now configures candidate credentials, opens Kodi, waits/authenticates, saves and
continues the latest captured Cast. Updated0.2.0 is installed; physical
source/subtitle/system-control acceptance is pending.

The service keeps native I/O beyond Activity destruction, restores state without
media replay and publishes source-bound system controls. Disconnect immediately
revokes old work, closes I/O, rejects queued/in-flight callbacks and removes the
notification without Player.Stop. Old notification actions cannot retarget a
renewed handoff, including one with the same URL.

User explicitly authorized saving credentials. Only validated login is saved,
encrypted; web snapshots expose presence flags. Failed candidate auth preserves
previous saved credentials. Automatic connection reuses the cached private IP,
falls back to bounded discovery and chooses only a unique compatible receiver.
Explicit disconnect pauses retries until another foreground visit/manual action.

REA confirmed `MediaControl\r\nsetVolume N\r\n\r\n`, normalized0..30. Native
setter fresh-reads the raw range, validates the requested integer, normalizes the
wire command and returns actual readback; UI never assumes the requested level.

## What failed

Wireless ADB needed explicit user pairing; local codes/endpoints stay outside
Git. One fixture approval review timed out with no safety rejection; its allowed
retry passed. Review found reusable per-Activity counters, delayed queued
disconnect, stale auth state and permissive unknown pairing replies; UUID/session
epochs, immediate close, auth reset and known `configed none` matching fix these.
The cold-service fixture initially rebound before the old Activity/service had
actually been destroyed; waiting for real teardown corrected the fixture race.

## Remaining questions

Test updated phone Cast → actual picture/sound/all languages/PT-BR, then app
switching, notification/lock-screen actions, cached reconnection and full phone
power-off. Use only fixed `LumaHandoff` stage/count/error logs, without URLs or
passwords. Process death may lose live source/alias state; saved setup survives.
LUMA model/Android, HLS seek/expiry, physical absolute volume, touchpad, Kodi boot
and generic receiver support remain separate work. No push/deployment.

## Suggested next prompt

“Test installed0.2.0: finish setup once, Cast the frameXTV episode/all subtitles/
PT-BR, switch apps, use lock-screen controls, reopen without reconnecting, then
power the phone off. Fix observed failures with sanitized stage-only diagnostics.”
