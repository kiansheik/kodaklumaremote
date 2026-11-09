# Projector Watch History and Accessibility shortcut

## Goal
Open the correct Accessibility menu from Home, record accepted Cat control,
verify refreshed service permission, add controller-operated watch history with
resume/next/episode browsing, and reinstall the latest phone APK.

## Inspected
Agent index/current-state/repo-map/open-questions; ControllerSystemService,
ControllerSetupActivity, MainActivity/layout, receiver manifest/build, installed
settings component metadata; LibraryStore/SyncRecord/MediaRequest/Catalog/
PlaybackController and phone LibraryStore/WatchHistory/CatalogClient sync shapes.
Memory registry had no relevant hit. Read Android9 AOSP MainSettings source:
https://android.googlesource.com/platform/packages/apps/TvSettings/+/refs/tags/android-9.0.0_r1/Settings/src/com/android/tv/settings/MainSettings.java
It creates MainFragment directly, not a caller-selected Accessibility fragment.
The actual installed APK class inventory confirmed AccessibilityFragment exists.

## Changed
New AccessibilityPanel, HistoryActivity, HistoryIndex and HistoryIndexTest;
receiver ControllerSystemService/ControllerSetupActivity/MainActivity,
ReceiverApp/ControlServer/LibraryStore/Catalog/CatalogTest/PlaybackController,
main layout, manifest and build version. Agent current-state/log/repo-map/
open-questions and this handoff. Private .local/nes-live/history-check.mjs for
live smoke. No phone source change; no commits/public release. Preserve prior
extensive dirty work and private runtime evidence.

## Commands
- Offline release-config Gradle receiver testDebugUnitTest/assembleRelease.
- receiver-dev controllers.settingsPanels/system/accessibility, games.status,
  history.status, input.key and deploy --no-build.
- adb -s adb-RQGL105J70A-0ArFoP._adb-tls-connect._tcp install -r signed phone APK.
- node .local/nes-live/history-check.mjs; git diff --check.
- Public AOSP source via curl after Python TLS and web opens failed.

## Worked
- Cat Mode user accepted. Accessibility directNavigation:true after user toggled
  off/on, preserved through subsequent installs.
- Installed firmware has com.android.tv.settings.MainSettings exported/enabled
  without launcher visibility. Separate Droid Settings has no Accessibility.
  Shortcut targets full Android TV menu; user selects Accessibility inside it.
  Component discovery no longer assumes all settings apps have launcher entries.
- Phone latest0.4.36/code46 reinstalled successfully preserving data.
- Receiver135/135 tests pass, including canonical movie/TV source requests,
  grouping/recent ordering and resume bounds. Signed0.3.31/code45 installed at
  2026-11-09T20:57:49.277Z; no game running before update.
- Live projector direct-key smoke: Home Watch History tile,10 saved titles,
  series actions, seasons,9 episodes in selected season, episode actions,
  Back at all levels. No arbitrary video was started by the smoke check.

## Failures / corrections
- Standard Accessibility intent/direct activity absent; initial fallback wrongly
  selected Droid Settings. Firmware diagnosis found hidden full Android TV app.
  Initial DexFile try-with-resources did not compile; used explicit close for
  diagnosis, then removed runtime class enumeration after finding the target.
- First UI smoke assumed newest title was a series; it is a movie. Updated the
  private test to explicitly select a series; rerun passes.

## Remaining
Physical Resume/Play next result pending (async question sent; History left open).
Fresh provider source availability is distinct from saved-history availability.
Known offline fallback shows saved seasons/episodes explicitly. Library screen
reads at most10000 recent nondeleted progress rows; database is never truncated.
Cancel/Back/onPause generation and playback source-generation guards prevent stale
opens. Cold boot and long-Home shutdown remain pending; avoid disrupting playback.
Phone power/text/system Air Mouse and unknown-IP discovery remain vendor-dependent
as documented in the prior handoff. Cross-app direct permission is verified;
actual pre-EShare navigation and cold-boot timing are not yet verified.

## Suggested next prompt
Check controller history playback acceptance; diagnose any provider/runtime failure
without changing saved history. Then arrange a user-controlled cold boot to measure
our listener readiness independently of Kodak8121.
