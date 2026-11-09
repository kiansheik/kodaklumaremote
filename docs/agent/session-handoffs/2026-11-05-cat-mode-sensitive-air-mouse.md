# Cat Mode and more-sensitive Air Mouse

## Goal

Make the accepted calibrated Air Mouse require less arm travel, then add a
projector-native cat-enrichment mode that moves autonomously and yields to the
phone pointer until Air Mouse is disabled.

## Files inspected

- Phone sensor/filter/controller, native receiver bridge and Activity RPC code.
- Mobile Remote state, More-sheet layout and browser fixtures.
- Receiver HTTP authentication/dispatch, Activity lifecycle/layout and player.
- Release/install scripts and live phone/projector package state.
- Primary cat-vision, hearing, visual-enrichment and environmental-needs papers.

## Files changed

- Phone `AirMouseFilter`, `AirMouseController`, `NativeBackend`,
  `ConnectionService`, `MainActivity`, version and native/browser tests.
- Mobile `index.html` and `app.js` Cat Mode toggle/status messages.
- Receiver `CatModeState`, `CatMotion`, `CatToyView`, `CatSoundPlayer`,
  authenticated API routes, Activity/layout integration, version and tests.
- Android backend fixture plus current-state, map, open-question and domain docs.

## Commands run

- Host Java 17 compile/run for Air Mouse and native-boundary tests: passed.
- `./node_modules/.bin/playwright test tests/browser/mobile.spec.js`: 68 passed.
- Receiver `testDebugUnitTest`/debug assembly: 48 unit tests, all passed.
- Phone main plus AndroidTest Java compilation: passed.
- Signed phone and receiver release assembly: passed.
- `make install-latest-apks`: phone and projector installs passed.
- Live receiver `cat.start`, pointer, pounce, release and stop: passed.
- Package/version, capability, phone-process and startup-log reads: passed.
- Signed receiver-only 0.3.16 deployment after first live audio feedback: passed.
- Signed receiver 0.3.17 build/metadata/signature checks and eventual live
  deployment: passed after the user restored projector services.
- `git diff --check`: passed before documentation finalization and must be
  rerun after this handoff.

## What worked

- High sensitivity reduces full-scale rotation from Normal's 26°/16° to
  20°/12° while preserving the existing calibration/filter behavior.
- Cat Mode uses a dark field with cyan/yellow-green generated moth/mouse/bug
  shapes, bounded nonperiodic darts, quivers, hides and rests. Receiver 0.3.16
  makes a two-part attention call immediately, then brief generated calls every
  4–10 seconds through the media stream. No copyrighted recordings or network
  assets are involved.
- Sessions end after 12 minutes. Back stops immediately. Manual control is a
  1.5-second lease, so a killed phone or dropped Wi-Fi resumes autonomy.
- Cat API coordinates are strict integers `0..1000` end to end. Malformed
  receiver status fails closed. A Cat packet failure no longer stops Air Mouse.
- The production-signed phone is live as 0.4.34/code44 and the receiver as
  0.3.16/code30. Live API state proved autonomous → manual → pounce → autonomous
  → stopped, and the projector advertises `cat.mode`. The louder build was then
  started for user acceptance at an unmuted media level of 8/15.

## What failed or remains unverified

- The first Cat/Philips browser fixture incorrectly made the whole More menu
  unavailable; the fixture was corrected to a configured but offline receiver.
- The user could not hear 0.3.15: it waited 6–18 seconds before its first chirp
  and combined 0.14 PCM peak with 0.30 track gain, roughly -27.5 dBFS before
  projector volume. 0.3.16 starts immediately, raises the bounded PCM/gain by
  about 12–14 dB and binds hardware volume keys to the media stream. The user
  confirmed the replacement still emitted exactly zero sound at unmuted 8/15
  media volume. Prepared 0.3.17 replaces the likely unsupported 24 kHz static
  track with checked 48 kHz streaming PCM, native minimum buffering,
  playback-head diagnostics and a system media-tone fallback.
- A receiver coordinate mismatch (`0..1` versus phone `0..1000`) and a Java
  checked-exception compile failure were found before deployment and corrected.
- Full receiver debug lint still reports 21 pre-existing min-SDK/API findings in
  unrelated code; Cat Mode introduced no reported lint finding.
- No emulator was connected, so the newly extended 16-case native wire
  instrumentation was compiled but not executed. Host boundaries, receiver unit
  tests, browser tests, release builds and the live receiver API did execute.
- Automation cannot establish whether the real motion looks compelling, the
  projector volume is comfortable, or either cat engages. Phone UI accessibility
  dump returned no useful WebView tree. Projector-Back/session-expiry toggle
  reconciliation can wait for the existing long poll.
- Receiver 0.3.17 could not be installed: the deploy's initial receiver read
  timed out, then both known projector TCP ports 4191 and 8121 consistently
  refused connections. The same known address still answered two ICMP probes;
  wireless ADB 5555 was closed. This requires waking/reopening LUMA Receiver,
  not another code change or blind retry. The user restored it; the subsequent
  install succeeded, so this is resolved.
- No public release, commit or push was performed.

## Remaining questions

- Is High sensitivity comfortable across the screen while held still?
- Installed 0.3.17 reports `pcm-stream`, 48 kHz, 10,560 samples written and
  10,560 playback-head frames. Is that output now audible at normal viewing
  distance, and do the cats respond?
- Does Air Mouse takeover, OK pounce, Air off and Back feel immediate on both
  devices? Does the automatic 12-minute stop behave well?

## Suggested next prompt

Open **More → Start cat mode** and watch one short session with the projector at
normal volume. Turn on Air Mouse, steer the prey, press OK, turn Air Mouse off,
then stop with Back. Report whether sensitivity, prey size/speed or sound volume
needs one more tuning pass.
