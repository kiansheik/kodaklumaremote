# All-format disk cache and high-quality playback

## Goal
Extend pause-and-keep-loading beyond local files to all receiver-supported media.
User explicitly prefers high quality over automatic bitrate reduction.

## Files inspected / changed
Read agent index/current-state/repo-map/open-questions and memory registry (no
relevant registry hit). Inspected PlaybackDiskCache, PlaybackController,
MediaRequest, ControlServer, receiver Gradle/tests, phone cache display, fixture
instrumentation conventions and pinned Media3 APIs. Changed PlaybackDiskCache,
PlaybackController, receiver build.gradle and PlaybackDiskCacheTest. Added
CacheInstrumentation and scripts/test-receiver-cache.mjs. Updated current-state,
log, repo-map, open-questions and this handoff. Preserved prior dirty work.

## Implementation
All accepted direct/progressive, HLS and DASH requests are cache eligible.
Progressive reuses bounded CacheWriter ranges, now also for remote URLs without
local media IDs. Adaptive playback writes fetched segments through a session-
hashed CacheKeyFactory; playback manifests bypass disk to stay fresh. Media3
DownloadHelper chooses decoder-aware highest-supported-bitrate tracks using the
same parameters applied to ExoPlayer. Media3 downloaders fetch60-second windows
including their audio/init/key dependencies; they yield to player loading.
Unknown/live helper failures retain playback-segment caching (limited state);
unpublished live future cannot be prefetched. No unsupported DRM was added.

Shared LRU bounds committed cache spans under the existing free-space reserve,
8GiB ceiling and roomiest app-cache selection. Near80% budget, pause prefetch
while enough future is present; resume as it is consumed. Seek cancels/restarts
at a nearby60s boundary; source replacement/stop clears generation-scoped cache.
Transient download failures retry with backoff, never lowering quality. Complete
windows are validated against disk spans before reporting seconds ahead; display
can increase in60-second steps. Complete means coverage of the whole title, not
only the remaining tail. Pixel dimensions/bitrate now appear in player status.

## Commands / what worked
- Inspected pinned Media3 classes with javap and primary Android/Media3 source:
  DownloadHelper.Factory, DownloadRequest time ranges, SegmentDownloader.
- Gradle offline receiver testDebugUnitTest/assembleRelease:140 tests pass.
- Debug + androidTest builds pass. Custom instrumentation ran on isolated
  emulator-5582 with generated150s1080p/AAC MP4, two-quality HLS and DASH fixtures.
  All3 formats passed paused preload,1080p selection,seek to90s with upstream
  media deliberately blocked (beyond20s RAM),advancing cached playback and cleanup.
- Reusable scripts/test-receiver-cache.mjs reproduces fixtures/install/instrument;
  requires ffmpeg, ADB and prebuilt debug/androidTest APKs. Defaults only to
  emulator-5582 and rejects physical-device serials; instrumentation also guards
  against physical receivers. Harness syntax checked; the equivalent temporary
  fixture server/test invocation produced the accepted three-format results.
- Signed receiver0.3.33/code47 installed at21:32:13.790Z. Phone remains0.4.37.
- Before install, paused/stopped S4E2 preserving position522.025; restored the
  exact source and identity at that position, paused. Real status:1080p,AAC,
  zero dropped frames, cache grows0.6→25.7MB, disk ahead137.975s while RAM23.52s.
  Still filling; left paused intentionally for user to resume when ready.
- git diff --check passes. No commit, push, public feed or phone reinstall needed.

## What failed / fixed
First compile caught an accidental duplicate diagnostic variable; fixed before
successful builds. First local fixture server needed normal network escalation.
First restore call omitted player.open's media envelope and was rejected before
mutation; corrected invocation succeeded. No fixture playback/cache tests failed.

## Limits / remaining questions
Long-duration storage-bound rolling refill, source changes during active network
reads, externally changed audio selections and live published-segment behavior
have not received physical device acceptance. One-minute prefetch windows can
fetch a small amount behind the initial resume point. Cache persists only for
the active source/session, not across restart/title changes. Paused disk loading
is proven on the real episode; high-quality playback over a slow upstream may
still require waiting for a larger buffer. No cold-boot or Home-hold power test.

## Suggested next prompt
If needed, observe the paused disk buffer and resume the1080p episode; verify
smooth playback after preloading. Test cold-boot readiness/power behavior only
when the user is ready to interrupt the projector.
