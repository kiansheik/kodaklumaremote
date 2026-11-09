# Smooth Cat control and early direct receiver API

## Goal
Fix jitter/release jumps in Cat Mode and make paired phone remote controls ready
without waiting for Kodak/EShare8121. User accepted prior focus/activation fix.

## Files inspected
Required agent index/current-state/repo-map/open-questions; receiver MainActivity,
ControllerInput/Profile/Navigation/SystemService, CatModeState/Motion/ToyView,
ReceiverApp/Service/StartupReceiver, ControlServer/LauncherCatalog, accessibility
metadata; phone NativeBackend/ConnectionService, mobile app, backend/service
instrumentation, fixture scripts and browser tests. Memory registry had no hit.

## Files changed
Receiver: new CatControllerMotion and tests, CatModeState/Test, CatToyView,
ControllerInput, MainActivity; new RemoteInput, ControllerSystemService, ReceiverApp,
ReceiverService, StartupReceiver, ControlServer, accessibility XML/description,
version0.3.28/code42. Phone: NativeBackend, ConnectionService, mobile app,
version0.4.36/code46, BackendInstrumentation, test-android-backend fixture,
mobile browser tests. Agent current-state/log/repo-map/open-questions and handoff.
All pre-existing dirty work preserved; no commit/public release.

## Commands run
- Release-config environment + offline Gradle receiver testDebugUnitTest/assembleRelease.
- Offline phone -PreceiverFixture=true assembleDebug/assembleDebugAndroidTest/assembleRelease.
- npm run build; Playwright mobile grep 'booted receiver|direct receiver remote'.
- Isolated LumaGames emulator5582 (read-only/no-snapshot); ANDROID_TEST_SERIAL=emulator-5582
  ANDROID_TEST_PACKAGE=dev.framextv.lumaremote.receiverfixture node scripts/test-android-backend.mjs.
- receiver-dev games.status (inactive), deploy --no-build, info, controllers.system,
  cat.status, input.key {key:home}; phone adb install -r signed APK and start MainActivity.
- git diff --check. Read-only phone UI hierarchy did not expose the targeted readiness labels.

## What worked
130 receiver tests; both signed builds; two focused browser tests. Phone16/16
instrumentation and wire assertions pass, including direct Home, volume and
receiver launch with nativeConnected=false throughout. Projector installed at
2026-11-09T20:32:58.445Z; actual Home receipt route receiver.home. Phone install
succeeded and app opened. Listener ready1,268ms after process start on update.
Game was inactive before deployment; pairing/profiles/checkpoints preserved.

## What failed / corrected
Initial backend command defaulted to absent emulator5554; rerun used explicit
isolated5582/receiverfixture. Receiver existing exact JSON-size test expected4
fields before controller status addition; updated to5. Backend post-instrumentation
wire assertions were stale: permitted display title alongside rejected content
identity and fourth malformed Cat-status case were already implemented. Updated
those exact expectations; final suite passes. Android still reports enabled=true,
directNavigation=false after APK update: cached old screen-access permission.
User asked to switch Accessibility service off/on and accept updated description.

## Remaining questions
- Physical stick tilt smoothness, neutral position hold and A/B response pending.
- Permission refresh and actual cross-app direct actions pending. API explicitly
  reports receiver.ui / accessibility / vendor.fallback; a vendor receipt does
  not prove pre-EShare navigation. Android9 apps without accessible focus/actions
  may still need fallback. Existing physical controller mapping still prefers
  working firmware injection when available; analog Android mapping preserved.
- Cold boot not tested. Accessibility can start the process independently, but
  Android/OEM timing still decides when it runs. Current startup bootBroadcastMs
  is also recorded for package replacement; interpret this installation reading
  as an update broadcast, not BOOT_COMPLETED. Saved-IP direct connect is covered;
  unknown/changed IP discovery, system Air Mouse, text and power remain vendor-dependent.
- Power hold remains deferred by user until Cat is accepted. Do not shut down
  automatically. Once accepted, arrange user restart and measure4191/8121 timing.

## Suggested next prompt
Check the pending Cat/Accessibility reply, verify directNavigation and real input
routes, then measure a user-controlled cold boot. Keep vendor-only limits explicit.
