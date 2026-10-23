# LUMA without Kodi: browser receiver or own APK

Assessed 2026-10-22; device browser test the same evening (below). Nothing was
installed on the projector.

## Verified observations

- Live EShare inventory (`.local/luma-analysis/live-apps-frame.bin`, private)
  lists 23 packages including two browsers, **`com.phlox.tvwebbrowser`** (TV Bro)
  and `org.chromium.webview_shell`, plus `com.droidlogic.FileBrower`,
  `com.android.vending`, `com.aome.store` and `org.xbmc.kodi`. `droidlogic`
  packages indicate an Amlogic platform.
- Read-only discovery found the projector at its private address, EShare port
  8121 open, Kodi HTTP 8080 open, **ADB 5555 closed** (2026-10-22).
- The archived Kodak client (1.0.116) command strings are only CHECKPASSWORD,
  CONTENT, GETAPPINFOSEVNET, GETFBINFO, GETPASSWORDCONFIG, GYROSCOPEEVENT,
  KEYEVENT and STARTAPPEVNET. No install or open-URL command is present; launch
  takes a package only. The server may implement more; nothing else is observed.
- TV Bro upstream manifest ([source](https://github.com/truefedex/tv-bro/blob/master/app/src/main/AndroidManifest.xml))
  declares a `VIEW` http/https filter, `REQUEST_INSTALL_PACKAGES` and
  `WAKE_LOCK`; 2.x offers GeckoView or system WebView engines. The installed
  TV Bro version/engine on this projector is unknown.
- The Philips receiver (`src/msx/`, `MsxServer.java`) is not Philips-specific:
  any approved LAN browser loading `/msx/connect.html` gets the polling channel,
  native-or-HLS.js playback and page-rendered subtitles. Philips coupling lives
  in the phone UI/service routing (`target === 'philips'` in `src/mobile/app.js`,
  `msx.launchBrowser` via JointSPACE).

## Path A: same web receiver in TV Bro (no install)

Launch: EShare `STARTAPPEVNET com.phlox.tvwebbrowser` → focus address bar
(keys) → `CONTENT` text of the phone's `browserSetupUrl` → Enter. This mirrors
Philips "Enter link manually", but EShare text is one write, not per-key.
Phone work: let the existing receiver server/approval serve a LUMA browser and
route LUMA playback controls to `msx.*` when "LUMA browser" mode is chosen.

Unverified risks, each a device check: WebView/Gecko version and MSE/HLS.js
support; autoplay without a gesture (an EShare DPAD_CENTER may supply one);
screen-saver/sleep during playback; address-bar focus key sequence; whether
the page survives Home/app switches. Phone-off playback is impossible in this
path: the phone hosts the page and control channel.

## Path B: own receiver APK

Install routes, best first: (1) phone serves the APK on LAN, TV Bro opens the
URL, downloads and hands off to the package installer; the user grants TV Bro
"install unknown apps" once and confirms install. (2) USB stick + droidlogic
FileBrower. (3) Enable ADB debugging in projector settings, then `adb connect`.
(4) Play internal-testing track if the store on this unit is functional.
Updates afterwards can be self-served by the receiver app once it holds
install permission, or via route (1) again.

What it buys: Media3/ExoPlayer HLS + sideloaded SRT, `FLAG_KEEP_SCREEN_ON`,
launch by package through existing `STARTAPPEVNET` (it appears in inventory if
it has a launcher/leanback activity), optional boot start, and a projector-side
LAN listener so the projector owns playback and the phone can power off —
the stated [mobile target](mobile-target.md). A WebView shell loading the same
receiver page is a cheaper middle step but keeps the phone-hosted dependency.

## Suggested first device check (user present)

Run Path A once against the Mac proof receiver (`scripts/msx-mac-proof.mjs`)
or the phone server, launched via `scripts/luma-protocol.mjs`: record TV Bro
version/engine and `navigator.userAgent`, then the existing LAN Sintel sample.
Pass → Path A as near-term crossover; any media/autoplay/sleep failure that
cannot be configured → Path B.

## Device result 2026-10-22 (user watching)

Driven from the Mac over EShare against a throwaway scratch HTTP server; no
phone involvement, no repo code changed.

- **TV Bro**: launch accepted, screen stayed plain white; typed text, DPAD
  centre and Menu produced no request. Unusable without on-device setup.
- **`org.chromium.webview_shell`**: launch, then `CONTENT` text landed in its URL
  field (user-confirmed). The repo's `enter` key (DPAD_CENTER 23) does not submit;
  raw `KEYEVENT 66` (KEYCODE_ENTER) does. After a load, Up×2 refocuses the URL
  field. Every typed `http://<mac>:4190/` arrived as path **`/q`** (unexplained;
  a catch-all route worked around it). One EShare connect timed out once; retry ok.
- UA: Android 9 `Smart Projector Build/PPR1.180610.011`, system WebView
  **Chrome 66.0.3359.158**. MSE + H.264 MSE yes, native HLS `maybe`, no
  unprefixed Fullscreen, no Wake Lock, 1280×720 (URL bar leaves 1280×647).
- Real `src/msx` scripts and hls.js 1.7.3 load and run (`Hls.isSupported()` true);
  the only error came from the harness lacking receiver DOM.
- Public mux HLS test stream: unmuted autoplay → `NotAllowedError`; muted
  autoplay works. An EShare **Down key reaches the page and counts as user
  activation** (unmute + play succeeded).
- hls.js path: plays, ABR to 1080p, repeated `bufferFullError` (small SourceBuffer
  quota) and brief stalls even with 12s/20MB buffers; **user: picture and sound but
  choppy and laggy**.
- Native HLS (`video.src` = m3u8): steady 1.0× for 30s, no waits, stayed 720p;
  **user: better, still choppy**. Frame counters unavailable on this path.

Interpretation (hypothesis): WebView composites video through the GPU on this
Amlogic SoC; Kodi renders MediaCodec output to a hardware surface. A native
receiver (ExoPlayer + SurfaceView) would use Kodi's path. Browser playback is
functional but not Kodi-quality, which favours Path B for the LUMA.
