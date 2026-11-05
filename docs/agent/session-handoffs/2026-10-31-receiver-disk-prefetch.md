# Receiver disk prefetch and full-movie acceptance

## Goal

Record the completed local-movie acceptance, stop the laptop server, and retain
the small receiver RAM window while opportunistically caching as much of the
current progressive local title as safe storage permits—including while paused—
without disk/memory leaks or starving playback.

## Files inspected

- Required agent index/current-state/repo-map/open-questions
- Receiver playback open/stop/error/player lifecycle and Media3 1.11.1 local jars
- Laptop/phone byte-range servers and laptop remote status rendering
- Receiver build, deploy, status and installed storage evidence

## Files changed

- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/PlaybackDiskCache.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/PlaybackController.java`
- `android-receiver/app/src/test/java/dev/framextv/lumareceiver/PlaybackDiskCacheTest.java`
- `android-receiver/app/build.gradle`, `android-receiver/README.md`
- `scripts/local-media-remote.mjs`
- Agent index/current-state/repo-map/open-questions/log and this handoff

## Commands run

- Stopped the owned laptop remote process and confirmed its old loopback port refuses
- Inspected pinned Media3 cache, priority, DataSpec and ExoPlayer APIs with `javap`
- Repeated offline receiver unit/debug compilation while correcting edge cases
- Ran focused local-media Node tests and diff whitespace checks
- Built/deployed signed receiver candidates through the paired projector channel
- Read authenticated receiver version/status and storage telemetry after install

## What worked

- The user watched all 6292.832 seconds of the exact high-bitrate MKV and
  confirmed AAC sound, English subtitles and seeking. Buffering was much better.
- Receiver 0.3.11/code25 is installed and healthy; the laptop server remains off.
- The existing 8–20 second/24 MiB RAM policy is unchanged. ExoPlayer registers
  playback priority while loading; the disk writer uses lower download priority.
- Progressive opaque local files cache completely if they fit. Oversized files
  retain a small back-seek allowance plus a rolling ahead range and refill space
  as playback advances. Pause does not stop disk prefetch.
- Companion audio is cached whole when it fits. Playback is cache-read-only, so
  only the bounded worker writes; cache errors fall back to upstream playback.
- Source change/Stop cancels the generation, clears sources, then removes opaque
  video/audio keys on the single cache executor. Startup sweeps crash residue.
- Status exposes cache state/storage/limit/content/target/window/ahead without
  paths or capability URLs; the laptop remote displays progress.
- Receiver tests/build pass (29 unit tests, including four cache policy tests),
  and focused local-media Node tests pass 9/9.

## What failed or changed the design

- Installed telemetry exposed only 1,568,206,848 bytes usable internally, giving
  a safe 1,031,335,936-byte cache after reserves. The 6.9 GB movie cannot fit, so
  a prefix-only implementation was replaced with a rolling window.
- Android exposed no mounted app-writable removable/SD cache directory. The
  implementation now enumerates standard external cache surfaces and prefers a
  roomier removable surface automatically, but cannot use a card the OS does not
  mount/expose to the app.
- Review caught false-complete reporting after ignored disk errors, failed-player
  prefetch resumption, asynchronous cleanup visibility, low-space audio crowding,
  and seek cancellation. These were corrected before final receiver 0.3.11.
- HLS/DASH whole-item caching was rejected: a CacheWriter over a manifest does
  not recursively and safely download the adaptive presentation.

## Remaining questions

- During the next local stream, do cached bytes grow after the RAM window fills
  and continue growing while paused without increasing rebuffering?
- Does a far seek reposition the rolling window and does ordinary playback then
  refill roughly the next safe gigabyte?
- Does Stop or selecting another title visibly transition `clearing` to `off`?
- Is the physical SD card unmounted, formatted as unsupported storage, or hidden
  by vendor firmware? After mounting/reformatting, does a receiver restart report
  `diskCacheStorage: sd-card` and enough capacity for the whole file?
- The phone remains 0.4.29/code39 and public update feeds remain at older public
  versions; cumulative publication still requires reconciliation.

## Suggested next prompt

“I mounted the SD card. Recheck receiver storage, then start a local file and
verify paused disk growth, a far seek, rolling refill and cleanup on Stop.”
