# LAN sample playback — 2026-10-20

Subsequent acceptance: the user requested an uninterrupted replay and confirmed
picture and sound. See the [acceptance handoff](2026-10-20-tv-picture-sound-acceptance.md).

## Goal

Get a controlled clip working on the owned Philips through the existing Android
app, preserve the working debug channel, and distinguish delivery failure from
player capability without another manual TV refresh.

## Files inspected

Required agent docs; `scripts/build-mobile.mjs`, `android/app/build.gradle`,
`MsxServer.java`, native MSX fixtures, mobile UI/browser fixtures, private
installed receipt and `lan-sample-physical-proof.json` plus physical UI captures.

## Files changed

- Build script/Gradle asset input: optional `LUMA_DIAGNOSTIC_SAMPLE`, regular
  file/exact4372373-byte/SHA verification; default builds omit generated media.
- Native server/fixtures: fixed approved-peer/capability LAN sample route with
  range support and separate fixed sample authorization. No arbitrary media
  proxy/private URL admission or media checked into Git.
- `src/mobile/app.js` and browser fixtures: accept/floor bounded fractional
  uptime, preserve strict integer pings, and clear reload waiting only on a
  later connected report with valid revision and hello count above the receipt
  baseline. Counter reset cannot supply that proof.
- Agent current-state/log/index/domain/map/questions and handoffs: final outcome.

## Commands run

- Root's optional/default/wrong-file packaging checks verified exact bytes,
  default omission and rejection. Fixture SHA-256:
  `b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`.
- Existing debug/test/release build, signing and lint procedure: passed; native
  MSX8/multi-receiver4 groups passed. Receiver38 Node/one real Chromium checks
  remain applicable; focused3/full29 mobile browser checks pass.
- Signed `adb install -r`: Success, saved data preserved. Installed SHA-256:
  `e0ddaf939cc648e2ad5e7c3b6f54e06a5469ceeeaa53ebc01808ddc4fc9410a4`.
- Actual app sample, Pause, Resume, then idle Reload through the approved
  channel. This documentation pass ran targeted reads and `git diff --check`;
  it made no code/device changes or builds.

## What worked

The combined local0.4.6/code15 APK and both phone feedback repairs are installed.
The already loaded TV JS stays at matching Loaded/Server d66f118d4c. Current
phone process BOOTSTRAP/HELLO14:33:56 and POLL/STATE14:33:57 reconnected without a
user refresh. At14:34:18 the TV requested the LAN clip with GET206. A valid Open
receipt yielded “Sample is playing” and Playing0:03/0:52. Pause returned
Paused0:19/0:52; a later report stayed there. Resume returned Playing0:24/0:52.
These are actual TV-reported playback and stable control results.

Proof remains ignored under `.local/tv-hello/android-player/`:
`lan-sample-physical-proof.json` and
`lan-sample-{playing,paused,paused-stable,resumed}-ui.xml`.
After playback was idle, numeric uptime705s, one app Reload and fresh uptime1s
with “TV page reconnected.”/same d66 revision physically verified both feedback
repairs. See `debug-final-reconnected-ui.xml`. The phone is left on normal Remote,
“No video playing.” No further manual TV refresh is requested.

## What failed

The public W3C source failed at14:26:41 with MEDIA_FAILED, media code4,
networkState3/readyState0 before metadata. The identical clip bytes work over
LAN, isolating a delivery-path difference. This clip's codecs are not a general
blocker; the exact external transport/TLS/policy cause is unknown. A local Python
download's CA-store error was not a TV certificate finding; system curl verified
TLS and obtained the exact bytes.

## Remaining questions

- Picture and sound were subsequently confirmed by the user after replay.
  Full-length completion was not captured.
- Real streams, subtitles and phone-off operation remain unverified. This fixed
  phone-hosted diagnostic does not establish those product requirements.
- DevView native permissions/catalog route and native-process Hello World remain
  open; the owner-local routing harness has only loopback proof.

## Suggested next prompt

Choose one authorized real stream for a controlled delivery comparison, using
the accepted LAN clip as the baseline. Keep the proven app debug/
reload channel, avoid another manual refresh loop, and preserve the distinction
between this fixed fixture, general media delivery and native TV execution.
