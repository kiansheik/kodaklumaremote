# Receiver buffer tuning

## Goal

Reduce excessive receiver buffering and repeated buffer/play cycles for local
high-bitrate media without returning to the projector's old ~70-second prefetch.

## Files inspected

- `docs/agent/{index,current-state,repo-map,open-questions}.md`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/PlaybackController.java`
- `android-receiver/app/build.gradle`, receiver tests and LUMA receiver notes
- `scripts/stream-local-media.mjs`, `tests/local-media-stream.test.mjs`

## Files changed

- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/PlaybackController.java`
- `android-receiver/app/src/test/java/dev/framextv/lumareceiver/PlaybackBufferPolicyTest.java`
- `scripts/stream-local-media.mjs`
- `tests/local-media-stream.test.mjs`
- `docs/agent/{current-state,log,luma-receiver}.md`
- This handoff

## Commands run

- `git diff --check`
- `node --test tests/local-media-stream.test.mjs`
- Cached Gradle 9.6.1 offline receiver `:app:testDebugUnitTest`
- Exact compatibility ffmpeg arguments against a generated seven-second
  audio/video fixture, followed by playlist duration/independence checks

## What worked

- Media3 compiles and its receiver unit suite passes with an 8-20 second
  time-prioritized window, 750 ms startup and 2 second rebuffer threshold.
- The 24 MiB allocation target remains, but no longer stops a high-bitrate file
  before the eight-second time floor unless Media3 detects insufficient heap
  headroom. This directly addresses the prior case where 24 MiB could represent
  only a few seconds without discarding the library's memory-pressure guard.
- Compatibility HLS forces a keyframe every two seconds and requests two-second
  independent segments. Real ffmpeg output was 2/2/2/1 seconds and advertised
  independent segments.
- Focused local-media tests pass 6/6; `git diff --check` passes.

## What failed

- The first Node run expected the old video-argument slice and was updated to
  account for the new keyframe option; the rerun passed.
- `gradle` was absent from PATH and the repository has no wrapper. The cached
  binary could not load its native macOS library in the sandbox; the approved
  offline run outside the sandbox passed.
- No APK was built or installed and no physical projector playback was run.

## Remaining questions

- Does the exact projector now sustain a high-bitrate direct MP4/MKV for several
  minutes without READY/BUFFERING oscillation or a system freeze?
- If stalls remain, does Diagnostics show the buffer draining with zero dropped
  frames (source/Wi-Fi throughput) or dropped frames rising while media remains
  buffered (decoder/rendering pressure)?
- Is eight seconds enough for the household Wi-Fi's worst jitter, or should a
  tested device-specific setting expose a 12-second stable profile?

## Suggested next prompt

“Build and install the receiver candidate, play one high-bitrate direct MP4 and
one laptop compatibility MKV for at least five minutes each, seek repeatedly,
and compare Diagnostics buffer/dropped-frame evidence before changing the policy
again; do not publish a release.”
