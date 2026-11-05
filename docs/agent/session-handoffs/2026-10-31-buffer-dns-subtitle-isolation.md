# Buffer UI, DNS and subtitle isolation handoff

## Goal

Show real disk loading alongside RAM buffering, determine the Wi-Fi-only watch
page failure, and prevent subtitles from another episode being reused.

## Files inspected

- Receiver playback/cache/catalog/control and caption classes plus unit tests.
- Phone backend/service/catalog cache, generated mobile UI and instrumentation.
- Laptop local-media remote and its browser fixture.
- Active macOS DNS/network state and fixed FramexTV/API resolver behavior.

## Files changed

- Receiver: `PlaybackController`, `PlaybackDiskCache`, `ExternalCaptions`,
  `ControlServer`; focused cache/ahead/Next tests; version 0.3.12/code26.
- Phone: `NativeBackend`, `ResolvedSources`, catalog/backend instrumentation,
  mobile buffer label/UI tests; version 0.4.30/code40.
- Laptop: local remote buffer rendering and browser coverage.
- Release note and agent state/map/log/open-question records.

## Commands run

- Bounded system/public DNS queries and HTTPS probes with and without DNS
  resolution; no router or device network settings were changed.
- Receiver `:app:testDebugUnitTest` (33 tests).
- `npm run check` (Node 262, browser 174).
- Production release builds for phone and receiver, followed by `aapt2` metadata
  and `apksigner` certificate/scheme verification.
- `adb devices`, wireless ADB discovery/connect, and receiver status probes.

## What worked

- Disk-ahead now measures contiguous cached bytes at the current playhead; phone
  and laptop display RAM and disk lifecycle/progress separately.
- Caption cache identity includes canonical item/local ID, URL and effective
  headers, uses collision-safe temp files, and expires after six hours.
- Resolved media plus subtitle catalogs require exact canonical episode/anime
  edition and expire after six hours.
- Slow/overlapping Next jobs cannot cross a source generation or content key.
- Public DNS and same-egress HTTPS establish that the watch-site failure is at
  the ISP DNS resolver, not the client's public IP or Cloudflare edge.

## What failed

- The receiver was offline, so its prior caption cache could not be inspected or
  the new receiver APK installed.
- Wireless ADB advertised the phone but refused connection, so phone installation
  and runtime instrumentation were unavailable.
- Public update feeds were not changed.

## Remaining questions

- After router DNS is changed, do the phone WebView and projector browser both
  open watch pages on Wi-Fi?
- During physical local playback, do disk bytes grow while paused and actual
  disk-ahead reset/grow after a far seek?
- Does the next real episode retain only its own subtitle cues?

## Suggested next prompt

“The phone and projector are online again; install 0.4.30/0.3.12, verify DNS,
disk growth while paused and a far seek, then test one Next transition.”
