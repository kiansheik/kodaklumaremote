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
  `setVideoEffects`). Treat picture adjustment as unverified.

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

## Release

Each phone release now carries `luma-receiver-<v>.apk` and
`receiver-update.json` (URL bound to the phone release tag); Pages serves
`receiver-update.json` for the receiver's updater and the install site links the
projector download. The Kodi add-on, its packager and the Mac Kodi controller
were removed (Git history keeps them; `tests/manual-kodi.md` stays as evidence).
