# Local media and laptop browser remote

## Goal

Stream local media from either the Android phone or a laptop to LUMA Receiver,
support external subtitles and projector-safe compatibility conversion, provide
a simple laptop browser remote, and let every paired phone retain full playback,
scrub and shared-thumbnail control.

## Files inspected

- `docs/agent/{index,current-state,repo-map,open-questions}.md`
- Phone activity/service/backend and mobile handoff/UI sources
- Receiver control, media parsing, playback and thumbnail-store sources
- Existing receiver developer client, browser fixtures and local-media docs

## Files changed

- `android/app/src/main/java/dev/framextv/lumaremote/{LocalMediaServer,MainActivity,ConnectionService,NativeBackend}.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/{MediaRequest,PlaybackController,ControlServer}.java`
- `android-receiver/app/src/test/java/dev/framextv/lumareceiver/MediaRequestTest.java`
- `src/mobile/{index.html,app.js}`, `src/handoff/coordinator.mjs`
- `scripts/{stream-local-media,local-media-remote}.mjs`, `package.json`
- `tests/local-media-stream.test.mjs`, `tests/browser/mobile.spec.js`
- `android-receiver/README.md` and the agent docs linked from this handoff

## Commands run

- `git diff --check`, JavaScript syntax checks, and `npm run stream:local -- --help`
- Cached Gradle 9.6.1 offline phone `:app:compileDebugJavaWithJavac`
- Cached Gradle 9.6.1 offline receiver `:app:testDebugUnitTest`
- `node --test tests/local-media-stream.test.mjs`
- Focused and full `tests/browser/mobile.spec.js` Playwright runs
- Full `npm test` and `npm run build`
- Localhost browser-remote page/state curl smoke
- Exact compatibility ffmpeg arguments against a generated two-second MP4 fixture

## What worked

- Phone-selected files are served from service scope with bounded document
  metadata, random capabilities, private-interface binding and HTTP byte ranges.
- Laptop auto mode preserves conservative compatible inputs; compatibility mode
  builds high-quality H.264/AAC HLS with ffmpeg and serves subtitle sidecars.
- The localhost browser remote pairs, chooses only beneath an explicit media
  root, launches a stream child, and controls playback/subtitles/volume/seek.
- An opaque `local:` media ID survives receiver status. Other paired phones can
  adopt the exact authenticated source and operate it without accepting generic
  private URLs.
- Laptop JPEG frames are uploaded to the receiver thumbnail store; phone scrub
  preview sync and the projector scrub overlay reuse the same key.
- The installed ffmpeg 8.1 accepted the exact HLS compatibility pipeline on a
  generated audio/video fixture.
- Phone compilation, receiver tests, Node 259/259, browser 62/62 and build pass.

## What failed

- Sandboxed Gradle could not load its native macOS library; the approved offline
  command passed outside the sandbox.
- The first phone compile found one static/instance `remoteData` call; it was
  corrected and the rerun passed.
- Sandboxed Playwright could not bind localhost and sandboxed full Node tests
  stalled in socket fixtures; both passed outside the sandbox.
- No real projector, phone or media file was used, so runtime throughput,
  hardware codec behavior, subtitle rendering and cross-phone thumbnails remain
  unverified on devices.

## Remaining questions

- Does the physical projector sustain CRF16 1080p HLS over the household Wi-Fi,
  and should a lower-bandwidth quality preset be added?
- Which direct MKV/WebM codec combinations work on the installed receiver versus
  requiring laptop compatibility mode?
- Does the separate public watch-page Wi-Fi failure come from DNS filtering,
  client isolation/router policy, CDN routing, or site/browser policy? Mobile
  success alone does not establish an ISP/IP block.
- Phone local-file streaming currently targets LUMA Receiver; the Philips browser
  receiver still rejects generic private media URLs by design.

## Suggested next prompt

“Build and install the current phone and receiver APKs, then test one H.264 MP4,
one modern-codec MKV through compatibility mode, Portuguese subtitles, a second
phone attaching to playback, and thumbnail scrubbing; preserve the logs and do
not publish a release.”
