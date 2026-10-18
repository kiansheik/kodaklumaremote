# System volume, confirmed power and Kodi rejection recovery

## Goal

Add notification/lock-screen volume controls and clearer presentation after the
user confirmed0.2.2 Play/Pause works. Add a main-remote Power off button requiring
confirmation. Investigate the reported S1E3 Kodi exit and missing subtitles
without duplicating uncertain commands or disturbing unrelated playback.

## Files inspected

Required agent index/current-state/repo-map/open-questions and previous phone
handoffs; mobile UI/coordinator, NativeBackend/ConnectionService/SettingsStore,
MainActivity and native/browser fixtures. Static archived Kodak1.0.116 power
dialog, callback, worker and EShare key serializer through existing REA/JADX.
Own-app fixed phone diagnostics and settings-presence metadata only.

Primary Kodi21.2 references:
[AddSubtitle/Open](https://raw.githubusercontent.com/xbmc/xbmc/21.2-Omega/xbmc/interfaces/json-rpc/PlayerOperations.cpp),
[error constants](https://raw.githubusercontent.com/xbmc/xbmc/21.2-Omega/xbmc/interfaces/json-rpc/JSONRPCUtils.h),
[subtitle content sniffing](https://raw.githubusercontent.com/xbmc/xbmc/21.2-Omega/xbmc/cores/VideoPlayer/DVDSubtitles/DVDFactorySubtitle.cpp),
[VFS access restrictions](https://raw.githubusercontent.com/xbmc/xbmc/21.2-Omega/xbmc/network/httprequesthandler/HTTPVfsHandler.cpp).
The SRT body/VTT label mismatch alone is not evidence of a parse failure.

## Files changed

- `ConnectionService.java`: guarded ordinary Volume −/Play-Pause/Volume +;
  volume custom actions before secondary Stop/Disconnect; immediate observed
  volume readback/card metadata; confirmed-power receipt completes before local
  disconnect/demotion/paused reconnect, preserving saved setup.
- Android manifest, version0.2.3/code5, colors/vector/adaptive/themed icons.
- `NativeBackend.java`: literal-confirmation-only power action using KEYEVENT26;
  safe fixed/numeric diagnostics; full-body/matching-envelope JSON-RPC checks;
  known pre-dispatch error receipts distinguished from unknown outcomes.
- `src/mobile/{index.html,app.js}`: visible main-remote power icon, Cancel-focused
  confirmation with dismissal sending nothing, exact one confirmed request and
  fixed rejection feedback. Long titles truncate within the responsive row.
- `src/handoff/coordinator.mjs`: verified rejection permits explicit later Retry;
  timeouts/internal/unknown replies retain uncertainty and cannot duplicate writes.
- Node/browser/native backend/service/live-control fixtures and runners:
  rejection semantics, responsive power flow, volume readback/stale guards,
  power teardown, original-volume restoration in opt-in live mode.
- README/Android README and agent index/state/map/questions/progress/decisions/
  log/protocol/phone documentation and this handoff.

## Commands run

- Targeted `rg`, `sed`, `git status`, `git diff --check`; XML/script syntax checks.
- `npm test`:130/130 passed with loopback-fixture permission. The initial
  sandbox run could not bind local fixtures and was interrupted; no source
  failure was inferred from that attempt.
- `npm run test:browser`:73/74 passed. A long restored title overflowed after
  adding the power button. After the narrow CSS repair, full mobile31/31 passed,
  including long titles at360×640 and320×592. The other43 browser cases passed
  in the full run. Coordinator40/40 also passed in the focused agent run.
- Cached JDK21/Gradle9.6.1 offline build of `:app:assembleDebug` and
  `:app:assembleDebugAndroidTest` passed; final asset rebuild passed after CSS fix.
- `node scripts/test-android-backend.mjs`:9/9 and wire assertions passed.
- `node scripts/test-android-service.mjs`:5/5 and wire assertions passed.
- `node scripts/test-android-ui.mjs`:final5/5 passed;320×592, no clipped controls
  or document scrolling. These scripts use the emulator only.
- Paired Samsung: installed both APKs with `install -r`, then the separately
  confirmed `live-controls` mode. It exited LIVE_SAVED_SETUP_REQUIRED with0
  control actions, before opening/binding or modifying playback. Read only
  settings-key presence: receiver cache absent, encrypted Kodi entry present.
  Opened updated MainActivity; user confirmed projector OFF. No receiver power,
  Open/Add/seek/Stop/Disconnect was sent by actual-phone tests this turn.
- Receiver's documented `Files.PrepareDownload` returned prepared descriptors
  for Kodi logs, but bounded authenticated VFS downloads failed/timed out. No
  log contents obtained, media sources added or receiver access policy changed.
- Final ignored APK SHA-256:
  `8fe2da8f51d47069c0376c353477338207c4da9da0a1615f5eaad05225318994`.

## What worked

- User explicitly confirms shade and lock-screen Play/Pause on0.2.2, corroborating
  earlier actual-phone framework checks. New UI/source/native fixture checks
  validate volume actions, their observed readback, revision/source guards and
  the confirmed-power lifecycle. Actual new volume/power remains untested.
- Original Kodak power flow is statically established: dialog101 and its
  positive callback → MainActivity.d(26) → worker → EShare KEYEVENT serializer.
  Exact ignored receipts are `.local/luma-analysis/protocol-power-{dialog,
  confirm,dispatch,worker}.json` and existing `protocol-key.json`.
- A valid same-ID error with a string message, no result and known schema/
  method/permission code may prove no Open/Add dispatch. AddSubtitle's-32100
  also precedes queuing in Kodi21.2. Open-32100, internal/unknown codes, missing
  error message, trailing JSON garbage and wrong IDs remain uncertain.
- Mobile regression tests prove verified rejection fails promptly and explicit
  Retry can proceed without duplicate accepted writes. Existing unknown-write
  tests still forbid blind replay. No timeout or launch-behavior expansion.

## What failed

- S1E3 physical failure is unresolved. First attempt logs entered subtitles,
  then GetItem and subsequent API reads became unavailable; Retry eventually
  reached AddSubtitle KODI_RESPONSE. This does not identify the receiver crash
  mechanism or the precise old generic response error. New numeric diagnostics
  require a fresh cast to classify it. No subtitle rendering fix is claimed.
- Actual-phone volume checks could not proceed with projector OFF. The absent
  receiver entry matches the existing cached-connect failure path that clears
  only the receiver cache; encrypted Kodi credentials remain. No settings were
  reset or rewritten to force the test through.
- Static KEYEVENT26 cannot establish standby versus hard shutdown, wake behavior
  or the exact projector firmware. No automated real power test was attempted.

## Remaining questions

1. With LUMA on, do new shade/lock-screen volume −/+ work and show observed level?
2. Does confirmed Power off produce the expected projector behavior? Cancel
   must leave playback alone. Saved Kodi login should remain after reconnect.
3. What numeric native error occurs on a fresh S1E3 cast? Does Kodi exit before
   AddSubtitle is written, and do PT-BR subtitles register/render after Retry?
4. Receiver crash evidence, HLS seek stability and full phone-off playback
   acceptance remain open. No Mac relay or source expiry solution was added.

## Suggested next prompt

With the projector on, leave a video playing and verify0.2.3 system volume with
exact original-volume restoration. Then try S1E3 once and inspect only fixed
own-app numeric diagnostics. Confirm picture/sound/PT-BR on the projector.
Try Power off last through its confirmation and distinguish standby/shutdown.
