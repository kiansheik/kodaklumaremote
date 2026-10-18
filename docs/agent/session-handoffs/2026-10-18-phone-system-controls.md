# Phone system controls, cold start and lazy subtitles

## Goal

Repair missing Samsung notification/lock-screen controls and the reported cold
Kodi start stall. Load Brazilian Portuguese first, with English fallback, while
retaining every website subtitle language for loading on demand.

## Files inspected

Required agent index/current state/repo map/open questions; original mission;
phone/background handoff and Android README; coordinator/mobile source and
fixtures; MainActivity, ConnectionService, NativeBackend and native fixtures.
Checked official Android MediaSession and system media-control documentation.

## Files changed

- `src/handoff/coordinator.mjs`, `tests/handoff.test.mjs`: preferred-first/lazy
  loading with explicit RAM catalog bindings; bounded cold-start retry; pending,
  cancelled and uncertain Open outcome reconciliation.
- `src/mobile/{app.js,index.html}`, `tests/browser/mobile.spec.js`: complete
  language selector/catalog, on-demand selection and restoration, remote-scoped
  requests, source-checked metadata update and bounded late Open/Add receipts.
- `android/app/src/main/java/dev/framextv/lumaremote/{MainActivity,ConnectionService,NativeBackend}.java`:
  shared session/content PendingIntent, conditional MediaStyle, immediate
  foreground display, Stop/Disconnect playback custom actions, validated
  memory-only catalog updates and native prewrite idle guard.
- Native Backend/Service instrumentation and Android version0.2.1/code3.
- README, Android README and agent index/current-state/repo-map/open-questions/
  phone-app/decisions/progress/log plus this handoff.

## Commands run

- `git status --short`, targeted `rg`/`sed`, `git diff --check`.
- `npm run check`: build,118 Node and69 browser tests passed before the last
  Open receipt refinements. Final `npm test`:121/121; final mobile subset:26/26.
  Other43 browser cases were unchanged by those refinements.
- Offline Gradle `:app:assembleDebug :app:assembleDebugAndroidTest` using cached
  Gradle9.6.1, JDK21, SDK35/build-tools36.0.0. Final build successful.
- Sequential `node scripts/test-android-{backend,service,settings,ui}.mjs`:
  8+4+5+5 checks passed on the emulator only. Final packaged UI run used the
  final APK; backend/service/settings native code was unchanged by the last
  web-only receipt bundle update.
- Paired-phone `adb install -r` and own MainActivity launch succeeded. Read-only
  filtered own-package/service/notification/media-session diagnostics; app-only
  stage logs. No general phone UI automation or OS-setting changes.
- SHA-256 of ignored local `android/app/build/outputs/apk/debug/app-debug.apk`:
  `7621c5a8a90f71dd304d0a80ca2685f359888efe6d7b0c4ba18e903a6af44163`.

## What worked

- User explicitly confirmed actual Samsung casting with picture, sound and
  Portuguese subtitles on0.2.0; this supersedes earlier pending-phone notes.
- Phone0.2.0 diagnostics: notification permission granted, channel unblocked,
  notification posted, foreground service active and a fresh playing session.
  Session launch intent was null. No notification-permission workaround needed.
- Service fixtures verify the shared launch PendingIntent, ordinary pre-cast
  notification, background Play/Pause, source/revision-stale action rejection,
  catalog binding clear/rebind and immediate Disconnect without Kodi Stop.
- Cold-start fixtures verify exactly one conditional retry after acknowledged
  Open and sustained idle state, transient status failures, no overlap with
  unknown Open outcomes, and late prewrite proof permitting a guarded retry.
- Mobile fixtures verify PT-BR-only/English fallback startup, all declared
  languages retained, delayed registration amid an unknown external track,
  observed index selection, loaded-track reuse, service restoration, failure/
  auth recovery and no video replay for language selection.
- Installed0.2.1 diagnostic: correct version/code, permission retained, saved
  setup automatically connected, ordinary public service notification posted,
  and session launch intent present before recasting. Physical acceptance
  question sent after installation; no result recorded yet.

## What failed

- User-observed0.2.0 cold launch stalled at video opening until Cancel/Retry.
  Safe logs showed repeated Kodi unavailability after initial Open, then a
  successful second attempt. Exact Kodi internal startup cause remains unknown.
- User saw no system controls despite Android posting active state. Missing
  launch target and absent modern Stop/Disconnect custom actions were concrete
  defects; Samsung visibility is not established by repairing those alone.
- Review caught uncertain aborted/timeout Open overlap and stale/null catalog
  binding regressions before phone installation; fixed with explicit receipts
  and catalog normalization, covered by the final tests.
- Sandboxed ADB listener startup was unavailable; authorized escalated ADB
  access worked. No safety approval rejection or manual permission bypass.

## Remaining questions

Does the Samsung show working controls in the notification/media panel and lock
screen after recasting? Does fully closed Kodi now finish the first Cast attempt?
Do another language and return-to-app work physically? Full phone-off playback,
HLS seek reliability, network interruption and automatic Kodi boot remain open.
No live media URLs, credentials, private endpoints or APK binaries entered Git.
The unrelated untracked `headers.txt` was preserved.

## Suggested next prompt

“Test installed0.2.1 controls on Samsung: cast with Kodi closed, confirm PT-BR,
switch languages, then use Play/Pause on the notification/media panel and lock
screen. Diagnose any remaining visibility failure with own-app diagnostics and
preserve receiver playback. Finally test phone fully off separately.”
