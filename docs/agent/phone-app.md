# Android phone sender and combined remote

Implemented on 2026-10-18 after actual Mac-controlled frameXTV playback and
Portuguese subtitles were confirmed on LUMA Kodi 21.2. A baseline debug APK was
installed on the Samsung phone running Android 16. The user confirmed live
phone casting with picture, sound and Portuguese subtitles. Background/system
controls on the notification shade and lock screen were user-confirmed on0.2.2.
Cold-start/subtitle regressions and phone-off acceptance remain open.
The latest [Motorola recovery](session-handoffs/2026-10-18-motorola-kodi-recovery.md)
implements source-verified startup/subtitle and locked-screen fixes; physical
Motorola/Bly Manor acceptance is pending.

## Runtime path

Phone browser → exposed media/track descriptors → native LUMA launch → bounded
Kodi readiness polling → Kodi `Player.Open` → preferred subtitle registration
with observed index binding → Portuguese (Brazil), then English → Remote.
The receiver fetches source URLs directly. No Mac, media proxy, cookie export or
phone-hosted server participates in this path.

An ordinary website cannot open LUMA's raw TCP/UDP sockets. The Android shell
packages the web UI and adds native networking. Its browsing WebView has no
JavaScript bridge; the trusted local asset WebView alone has the narrow RPC
interface. Packaged UI requests cannot navigate to a remote site. Captures are
limited to HTTPS `framextv.xyz` and readable same-origin documents, using the
same declared-media validation as the extension. Shared HTTPS episode links can
open in the app. Capture is user-triggered; no anti-debugging or DRM bypass.

## Interface

The no-scroll phone Remote prioritizes playback, projector volume, a central
five-button D-pad, Subtitles and More. Home is at the upper left and Back at the
upper right. The volume slider sends one committed value, then reads the
observed current/max back; ± controls use that same observed range. A small
red/green indicator shows connection state. Browse is a separate bottom tab.
After valid setup, configuration moves behind Settings instead of remaining a
primary Devices tab.
More holds text entry, installed apps, seek, explicit Stop and Kodi launch.
The main Remote has a Power icon beside Choose video. Its confirmation focuses
Cancel; Cancel, Escape, backdrop dismissal and connection loss send nothing.
Turn off sends one dedicated `luma.powerOff` request with a literal confirmation.
The native adapter uses the original Kodak client's confirmed KEYEVENT26 flow,
then tears down local controls and pauses reconnect while retaining saved setup.
This acknowledges sending a power command; standby versus shutdown still needs
physical acceptance on this firmware.
Language/app lists use search and three-item pages. Keyboard-sized layouts keep
the active form usable; drafts survive recoverable errors. These control
priorities follow the household's workflow. Review complaint counts are not
measurements of control usage; see [review findings](luma-review-findings.md).

## State and cancellation

Active media, subtitle aliases and handoff state live in memory. A captured page identity is separate from a
fresh token for each casting attempt. Cancel/source navigation revokes that
attempt; native mutation guards recheck authorization just before the write.
An old cancel cannot revoke a newer attempt. Already accepted receiver commands
cannot be undone. Manual controls remain bound to the active receiver source,
so browsing a new page after a successful cast leaves the video controllable.

The coordinator waits for actual Kodi state instead of treating command
acknowledgement as playback/subtitle selection. It loads Brazilian Portuguese
first, falling back to English, and retains the full website subtitle catalog
in memory on the phone. Selecting another language loads it on demand and maps
its observed receiver index. Unknown existing external tracks are never assigned
guessed language labels. One subtitle displays at a time. Catalog URLs and
loaded aliases survive Activity recreation through service memory, not storage.

A cold Kodi start may acknowledge the first Open before video playback becomes
ready. One bounded retry is allowed only after that request settles and fresh
observations confirm Kodi is idle; a native prewrite guard rejects the retry if
another video has started. Unknown command outcomes are not blindly repeated.
Exact-source playback is also observed while Open's response remains unresolved
or fails, allowing the same attempt to continue into subtitles without replay.
Observation releases the Open wait without aborting its native cast job.

Subtitle readiness/registration reads have bounded30-second windows, sized for
native operations that can contain multiple HTTP requests. Each native Kodi
operation now has one five-second budget across those requests. Transient reads
retry; authentication, configuration and source errors fail promptly. Kodi's
null `currentsubtitle` means its subtitle player is not ready; an empty object
means ready without a selection. An uncertain
Add response is reconciled against actual indices without resending the Add.
An exact, validated Kodi JSON-RPC rejection known to precede Open/Add dispatch
now clears the uncertain-write receipt and permits an explicit later Retry.
Malformed, mismatched-ID, internal/unknown errors and transport failures keep
their uncertain outcome and cannot authorize duplicate writes. Native diagnostics
include only fixed methods/categories and numeric HTTP/RPC codes.
Initial subtitle-only failure opens the remote only after fresh exact-source
playback confirmation, retaining every captured language for subtitle-only retry.
An English Add follows rejected PT-BR only with explicit non-application proof;
uncertain Add outcomes never authorize another Add. Captured metadata is prepared
in service memory before opening, so it survives an interrupted completion step.
Per-operation bridge abort does not revoke the entire cast job; user Cancel and
source changes still do.

## Saved first setup

First use connects a projector, accepts the configured Kodi HTTP login, launches
Kodi and polls its authenticated API for up to 30 seconds. Only successful
authentication saves the candidate login; a failed login/save retains the form
draft and reports an actionable error. The latest pending user Cast resumes
after validation, reusing that Kodi launch. **Cancel waiting** stops automatic
casting; an already completed authentication may still have saved the explicitly
submitted settings. It does not send a video.

`SettingsStore` caches one receiver address/name in app-private preferences and
encrypts Kodi username/password/port with AES-256-GCM using an Android Keystore
key. Saving was explicitly authorized by the user. Native code restores the
login; the web UI receives only setup-presence metadata, never saved secrets.
Later app opens try the cached receiver and fall back to bounded discovery.
Settings offers editing, a fresh scan and selective forgetting of the receiver,
Kodi login or all stored setup. Rescan can connect a unique discovered receiver;
otherwise manual selection/address entry remains available.

Fixed casting-stage diagnostics identify capture, setup, launch/readiness,
media opening, subtitles and completion/failure without logging media URLs or
credentials. Native service/persistence and Keystore fixture checks passed;
actual-device automatic reconnect acceptance remains pending.

## Background connection and system controls

`ConnectionService` now owns the native backend independently of the Activity.
After a successful projector connection it runs as a `connectedDevice`
foreground service. Returning to the app or recreating the Activity rebinds to
the living service and restores its observed connection, playback and subtitle
aliases without reconnecting, re-entering credentials or replaying the video.
Resume events preserve text/address drafts and an active handoff; opening the
notification explicitly selects Remote.

Android documents network-connected external devices under its
[`connectedDevice` foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
The manifest declares the corresponding foreground-service permissions and
`CHANGE_WIFI_MULTICAST_STATE`. The app requests notification permission on
Connect where required.

An ongoing notification provides **Volume − / Play-Pause / Volume +** when the
remembered source matches observed Kodi playback. A
[`MediaSession`](https://developer.android.com/reference/android/media/session/MediaSession)
publishes observed playback state for Android’s
[system media-control surfaces](https://developer.android.com/media/implement/surfaces/mobile),
including the lock screen. The session and notifications share a Remote launch
target. The ordinary foreground notification keeps source-bound playback buttons
when verified; a separate MediaStyle card feeds system media/lock-screen surfaces.
An ordinary connection notification remains available before casting. Android 12+
requests immediate foreground notification presentation. Presentation depends on
Android/Samsung settings; the user confirmed both surfaces display working
Play/Pause on0.2.2. The first two MediaSession custom slots now provide volume
−/+, with Stop/Disconnect following on expanded media surfaces and in the app.
Distinct projector/playback/volume icons and observed volume in the subtitle
improve recognition. Volume changes read the receiver back immediately.
Source/revision guards prevent an old notification from controlling
a different video. Notification updates coalesce the latest snapshot/revision,
post at most once per second and skip unchanged visible content; media-session
state updates immediately.
Transient HTTP failure marks matching playback stale and retains its card instead
of inferring that video stopped. System actions first read fresh source-bound
playback; they do not replay failed writes. Each user media action holds a CPU
wake lock for at most30seconds and releases it on completion/cancellation. Kodi
HTTP controls can remain usable when the independent projector TCP link drops;
hardware volume still requires that hardware transport. These paths are covered
by isolated Android fixtures, not yet confirmed on Julia's Motorola.

Read-only diagnostics on the installed0.2.0 found notification permission
granted, an unblocked active foreground notification and an active playing media
session. Its session launch target was null. The missing target is repaired in
the follow-up; these diagnostics alone do not establish Samsung visual behavior.

**Disconnect** closes the live controller session and retains saved setup;
it does not send Kodi Stop. **Stop video** explicitly ends playback. Live
connection state and subtitle aliases remain in memory; cached receiver details
and the encrypted login survive separately. Force-stop, reboot or process death
do not guarantee live-session restoration. Reopening the app can use saved setup
and attach to fresh observed public HTTP playback with generic metadata, without
replaying media or reconstructing lost subtitle aliases. Adoption requires no
remembered remote and no authorized casting attempt; a remembered source is
never silently retargeted. The complete website language catalog returns after
another capture/handoff; its URLs remain memory-only.
Receiver playback remains independent of this control service and uses no Mac.

Validation includes first authentication,
saved setup, reset/rescan, volume commits, cancelled setup preventing auto-Cast,
restoration without replay, preserved drafts and notification-open navigation.
The0.2.2 actual-phone checks exercised ordinary-notification and MediaSession
Pause/Play, preserved the identical source and left it playing; user visual
acceptance now corroborates those checks. The user subsequently reported Kodi
exiting on the first S1E3 attempt and missing subtitles after Retry. Logs show
Kodi becoming unavailable during subtitle preparation and AddSubtitle receiving
a response error on Retry. Its precise cause is unconfirmed. Current0.2.3
checks and artifact are recorded in the
[volume/power handoff](session-handoffs/2026-10-18-phone-volume-power.md).

## Files and reproduction

- `src/mobile/{index.html,app.js}`: responsive local UI and native RPC adapters.
- `src/mobile/capture.js`: user-triggered DOM extraction and source-matched local
  pause after receiver readiness.
- `src/handoff/coordinator.mjs`: transport-independent asynchronous handoff.
- `android/app/src/main/java/dev/framextv/lumaremote/`: isolated browser/control
  WebViews, service-owned native protocol, foreground notification/MediaSession,
  Keystore-backed settings, credentials/source validation and lifecycle cleanup.
- `scripts/build-mobile.mjs`: ignored generated APK assets; Gradle bundles them.
- `tests/handoff.test.mjs`, `tests/browser/mobile*.spec.js`: source/handoff/UI
  fixtures. Native fixtures are under `android/app/src/androidTest/` and
  `scripts/test-android-backend.mjs`.

Build/setup commands and limitations: [Android README](../../android/README.md).
Recorded final checks: [session handoff](session-handoffs/2026-10-18-phone-handoff.md).

## Remaining physical acceptance

The updated APK is installed on Samsung Android 16. Test first setup with a wrong login,
then correct login and persistence; reopen without re-entering it. Verify the
cached-projector route, rescan and selective reset. Test cold start with Wi-Fi
unavailable, discovery/manual connect, source capture from the live episode,
Kodi closed → launch/readiness → picture/sound → preferred subtitle registration →
visible matching Brazilian Portuguese → combined remote. Switch to another app
and return without reconnecting; recreate the Activity and verify the existing
video and language names return. Lock the screen and test notification/media
Play/Pause, Stop and reopening Remote. Confirm Disconnect leaves the video
playing. Check actual volume, typing, keyboard and interrupted-network recovery.
Then power the phone fully
off while the receiver remains on ordinary Wi-Fi, observe playback across
several segment/buffer boundaries and reconnect without restarting media.

The phone runs Android 16; its exact WebView and LUMA model/Android remain
unverified. Generic Cast,
Miracast, PIN pairing, touchpad/scroll gestures, screen preview, firmware/store
repair and automatic Kodi boot are not implemented here. The previously seen
rapid HLS pause/seek/resume stop still needs a settled-command device test.
