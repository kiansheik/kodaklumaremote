# LUMA Receiver (Kodi replacement)

Source: [`android-receiver/`](../../android-receiver/README.md) (API table, dev
loop). Phone side: `NativeBackend` receiver section, `ConnectionService`
pairing/sync, `SettingsStore` pairing token. Background and the rejected
browser path: [LUMA web receiver](luma-web-receiver.md).

## Why

Browser playback on the projector (Chrome 66 WebView) was choppy even with
native HLS. A native APK with ExoPlayer on a SurfaceView was user-confirmed on
2026-10-22 as "the best image I have ever seen in the projector" on the same
mux HLS test stream. Kodi also lacked atomic compare-and-open, needed a
metadata add-on for canonical identity and kept no history of its own.

## Verified on the physical projector (2026-10-22)

- Device: Android 9/API 28, Droidlogic "Smart Projector", Amlogic,
  ABIs `armeabi-v7a, armeabi` only (32-bit userspace).
- First install: TV Bro downloaded the APK (Back opens its menu; address bar is
  7× Right then OK; EShare `CONTENT` text arrives with a leading `q`, removed
  with KEYCODE 122 then 112; submit with KEYCODE 66). The user granted storage
  and installed. Debug→release signing required one uninstall.
- Release-signed receiver 0.1.0 installed; PIN pairing from the Mac worked;
  `canInstall` was already true.
- ExoPlayer HLS: 1080p, 70 s buffered, smooth; pause/seek/resume, `SOURCE_CHANGED`
  guard, sideloaded SRT fetched from the Mac and selected (re-prepare cost ≈5 s
  when added mid-playback; none when passed in `player.open`).
- Caption style iterations user-accepted ("That's the style").
- Dev channel: `receiver-dev.mjs deploy` installed 0.1.1→0.2.0 unattended;
  installer confirmation on this projector is **Down, Right, OK**; the app
  restarts itself via `MY_PACKAGE_REPLACED`.
- Projector-side progress recorded and returned by `library.pull`; a stale
  `library.push` was rejected (LWW).
- Gamma/brightness via Media3 effects: **no visible difference** when changed
  mid-playback (cause not investigated; effects list applied via
  `setVideoEffects`).

## Brightness compatibility (2026-10-23)

Two follow-up colour-matrix implementations moved adjusted playback to a
TextureView. Both `View.setLayerType(HARDWARE, paint)` and
`TextureView.setLayerPaint` made every nonzero brightness value black on the
physical Android 9/Amlogic projector. The receiver now always leaves decoding on
its single compatible SurfaceView and places a non-interactive black/white tint
in `PlayerView.getOverlayFrameLayout()`; negative values dim and positive values
blend toward white.

An API 36 emulator decoded and paused a real H.264 frame while centre-crop luma
moved monotonically at −60%, −10%, 0%, +10% and +60%, with the frame visible in
all five captures. That validates app composition but not the projector vendor
compositor. Signed receiver 0.3.2/code 16 containing the overlay was installed
on the physical projector and returned healthy/idle with brightness 0; the final
active-video physical check remains outstanding.

The user subsequently confirmed the overlay result is "perfect" on active
projector video. The control is now bounded to −30…+30% in 2% steps. The phone's
native `app.picturePreferences` store keys movies by movie ID and series by
series ID, restores the value after guarded casts to either LUMA or Philips, and
uses neutral brightness for a title with no saved value. The signed receiver
0.3.2/code 16 containing the ±30% validation was installed at
2026-10-23T13:03:09.817Z and returned healthy with brightness 0.

## Phone migration contract

`kodi.*` → `receiver.*`, `luma.launchKodi` → `luma.launchReceiver`,
`KODI_*` → `RECEIVER_*`. Result shapes are unchanged, so WatchHistory,
adoption, system Next and scrub previews are untouched. Credentials are replaced
by `receiver.pairBegin` / `receiver.pairComplete` (token persisted encrypted,
stripped before reaching the WebView). Legacy Kodi credentials and their
Keystore alias are deleted when `SettingsStore` is constructed. Source guards are
forwarded and enforced atomically by the receiver. Polling is 2 s; Library sync
runs every 60 s (pull with per-receiver cursor, push with a watermark).
Coordinator connects first and only launches the receiver over EShare when it
does not answer. Phone scrubs are mirrored with latest-wins `receiver.scrub`.

## Boot and direct readiness (unreleased after 0.4.10)

The receiver side already has the early path needed for cold boot:
`StartupReceiver` handles `BOOT_COMPLETED`, `ReceiverService` immediately starts
the port-4191 control server as a `START_STICKY` foreground service, and an
accepted `player.open` brings `MainActivity` to the foreground. This path does
not depend on EShare/navigation port 8121. `ReceiverApp.onCreate` also starts the
service whenever Android creates the process.

The missing dependency was on the phone. Saved auto-connect previously opened
8121 before it supplied the saved host/token to the receiver client. It now
tries port 4191 directly and represents the two transports separately:

- `receiverConnected`: paired receiver HTTP is authenticated; casting and
  receiver playback controls are available.
- `nativeConnected`: EShare/navigation TCP is available; Home/Back/D-pad, apps,
  projector volume and power are available.
- `connected`: either transport is currently usable, retained for the existing
  service/UI contract.

After receiver-only readiness, foreground auto-connect retries native readiness
at 15-second intervals. Receiver status polling does not postpone that timer.
If the native socket later drops, the service clears projector volume and its
hardware actions but retains direct playback polling and media controls.

Emulator fixtures prove direct saved-IP/token connection without a native
socket and loss of native TCP during active receiver playback. Browser fixtures
prove a direct-only snapshot can cast without `luma.launchReceiver` and enables
Home only after native readiness. Physical cold-boot timing remains unverified:
power-cycle the exact projector and measure first UI, first port-4191 response,
first accepted cast and first port-8121 response. The unchanged receiver
0.2.3/code 10 was subsequently rebuilt and locally reinstalled on the physical
projector (1,185,598 bytes, SHA-256 prefix `fadc21c1efd7`); it returned healthy
and idle. The 0.4.11 receiver-first behavior is phone-side and still requires a
phone APK install.

## Release

Each phone release now carries `luma-receiver-<v>.apk` and
`receiver-update.json` (URL bound to the phone release tag); Pages serves
`receiver-update.json` for the receiver's updater and the install site links the
projector download. The Kodi add-on, its packager and the Mac Kodi controller
were removed (Git history keeps them; `tests/manual-kodi.md` stays as evidence).

## 2026-10-23 follow-up (0.4.10 / receiver 0.2.3)

User acceptance of 0.4.9: EN/PT/BG captions "great"; size buttons good (default
stays 100%). Fixed after their report:

- **Captions without rebuffer.** Sideloaded ExoPlayer subtitles re-prepared the
  stream on every add and re-fetched earlier files from the phone (16-request
  cap), so later languages failed. The receiver now fetches catalog URLs itself
  (`ExternalCaptions`), parses VTT/SRT/ASS/TTML in any common encoding
  (`CaptionParser`), and draws them in `SubtitleOverlay`; external tracks use
  stable indices 512+. A 63-track Midnight Mass S1E5 catalog: all 63 parse (old
  phone normalizer: 62). Playback stayed `ready` across four track switches on
  the projector. Pre-reversed RTL punctuation is repaired per file; `{\an8}`
  overrides are stripped. Emulator snapshots (`app.snapshot`) show correct
  Hebrew/Arabic RTL rendering. The phone no longer prepares/hosts LUMA subtitles.
- **Catalog URLs with spaces** were silently dropped (29/63 tracks); now encoded.
- **Scrub crash:** a timed-out preview decode released `MediaMetadataRetriever`
  during native decode (use-after-free). Release now waits for decode return;
  timeout 1.8 s → 5 s and no permanent per-video failure.
- **Auto-reconnect:** a failed first auto-connect deleted the saved projector.
- **Projector freeze** (system hang with looping audio during plain playback on
  0.2.2, cause unknown). Buffer capped at 30 s/24 MB; `Diagnostics` keeps a
  15 s rolling log across reboots (`app.diagnostics`). Post-fix 1080p check:
  ~356 MB free, 0 dropped frames.

## 2026-10-23 scrub, auto-next and setup (unreleased 0.4.12 / receiver 0.3.0)

- Scrub crash root cause (phone logcat): `NetworkOnMainThreadException` when
  `ScrubPreviewManager.request()` cancelled an in-flight HTTPS fetch on the main
  thread; the HLS-only loader also never produced frames for MP4 sources
  (Midnight Mass S1E5 is a 176 MB range-capable MP4 with moov first).
- `ThumbnailStrip` (phone): background generation from the cast, bisection order,
  coarse ~200 then refinement to 3 s cells (paced), memory LRU + 48 h disk cache
  keyed by content, MP4 via one range-reading `MediaMetadataRetriever`, HLS via the
  old loader on the worker thread. Frames shared through the receiver
  (`thumbs.list/get/put`, 48 h, 96 MB); receiver draws scrub frames from its own
  store, phone mirrors positions only on a dedicated coalesced connection.
  Page pulls frames in bulk (`preview.batch`) and renders nearest locally.
- Variable-speed scrub (1:1 until ~0.7 s slow/still, then 1/3, 1/10), undo/redo
  arrows with checkpoints persisted through native `app.seekHistory` (remote
  WebView has DOM storage off, which broke the first undo).
- Resume fixed: coordinator passes `resumeSeconds` to Open; receiver accepts seeks
  before media is loaded.
- Receiver `Catalog` port (season/details/stream/subtitles) enables `player.next`,
  auto-next with 10 s countdown (Back cancels), stop after 3 unattended episodes,
  `lastInteraction` + `autoplayed` in status and progress records, ended state
  kept in status. Verified live: Midnight Mass S1E5→E6, E7→none; Breaking Bad
  S1E7→S2E1.
- Phone: status published before background syncs (cold-start controls ready
  sooner); Remote tab shows the connect view until LUMA is connected+paired.
- Checks: browser 107, Node suites, emulator backend 15/service 18/preview 9/
  subtitles 9, receiver unit tests. Physical: user confirmed resume and smooth
  phone scrubbing; auto-next/ended/connect-swap not yet physically tested.
