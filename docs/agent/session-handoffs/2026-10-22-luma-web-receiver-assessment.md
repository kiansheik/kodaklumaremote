# 2026-10-22 LUMA web receiver / own-APK assessment

Goal: replace Kodi on the LUMA with the Philips-style phone-hosted web receiver,
or else determine what installing our own APK would take.

Inspected: `docs/agent/{luma-receiver-options,luma-remote,msx-receiver}.md`,
`.local/luma-analysis/live-apps-frame.bin` (package names only), archived
Kodak client dex strings, `src/msx/receiver-core.js`, `src/mobile/app.js`
target routing, `scripts/{luma-discovery,luma-protocol,msx-mac-proof}.mjs`,
TV Bro upstream manifest.

Changed: added `docs/agent/luma-web-receiver.md`; index/log links.
No source code changed.

Commands: dex `strings`; `discoverLuma()` (found projector); `nc -z` on
5555/5037/8121/8080 (only 8121/8080 open). No device launch, typing or install.

Results: browser path is plausible (TV Bro present, receiver generic); APK path
is plausible via TV Bro download + one-time unknown-sources grant. Neither is
device-accepted.

Open: TV Bro version/engine, MSE/HLS, autoplay, sleep during playback,
address-bar key sequence; whether user wants phone-off (→ APK).

Next prompt: "With me watching the projector, launch TV Bro over EShare, type
the receiver URL and play the LAN sample; record userAgent and results."

## Follow-up: device browser test (user authorized)

Commands: scratch `server*.mjs` probe pages on the Mac LAN address; scratch
`drive.mjs`/`rawkey.mjs` using `scripts/luma-protocol.mjs` (launch, text, keys,
raw KEYEVENT66). Ended with Home; probe servers stopped. Results recorded in
`docs/agent/luma-web-receiver.md` (Device result). Failures: TV Bro white screen;
the repo's `enter` key does not submit text fields; unexplained `/q` path;
choppy playback in both hls.js and native HLS.

Next prompt: "Scaffold a minimal LUMA receiver APK (ExoPlayer + SurfaceView,
LAN control matching the msx channel) and serve it from the phone for install
through the WebView shell or USB."
