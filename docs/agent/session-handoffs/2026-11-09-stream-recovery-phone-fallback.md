# Failed stream recovery and phone assistance

## Goal
Fix Watch History replay of an unresponsive Gilded Age S4E2 source; prefer
projector-only recovery, with a connected-phone page handoff when necessary.

## Inspected / changed
Inspected Catalog, HistoryActivity/HistoryIndex, PlaybackController/PlaybackDiskCache,
MediaRequest, ControlServer, NativeBackend, ConnectionService, MainActivity,
mobile controls/tests, receiver-dev tooling and agent docs. Changed Catalog,
HistoryActivity, PlaybackController, ReceiverApp, ControlServer; added StreamProbe
and StreamRecovery. Changed phone NativeBackend/ConnectionService, mobile app.js/
index.html, receiver CatalogTest/new StreamRecoveryTest and mobile browser test.
Bumped receiver0.3.32/code46 and phone0.4.37/code47. Updated current-state, log,
repo-map and open-questions. Existing unrelated dirty work retained.

## Behavior
Uncached bounded source probes inspect HLS playlists plus first media bytes;
empty/error responses fail. Catalog tries available alternate providers within
its lookup budget. Initial player errors/no-format startup stalls trigger one
resolution excluding the failed URL; zero duration alone is not a failure.
Failures after playback has begun are outside this retry path.
Canonical phone requests expire after five minutes and clear on cancel/new play.
Phone controls revalidate the request before opening the exact page; notification
also links the canonical page. User chooses a working source/Play/Cast in existing
phone browser. This is guided handoff, not automatic background capture.

## Commands / validation
- Receiver Gradle testDebugUnitTest + assembleRelease, phone assembleRelease,
  offline with private release env: pass. Receiver139 tests, zero failures/errors.
- npm run build; Playwright mobile grep failed projector stream|direct receiver
  remote: passed (test-results/.last-run.json). Tests cover exact episode handoff
  and rejecting an expired request, plus existing direct-remote behavior.
- Signed phone adb install -r: Success; installed version0.4.37/code47 verified.
- User authorized saving Mario and installation. Game had closed during prompt;
  reopened auto-save, pause-menu Save checkpoint, clean games.stop before deploy.
  Five retained checkpoints reported. No profiles overwritten.
- receiver-dev deploy --no-build: installed0.3.32/code46 at21:18:29.616Z.
- Private /tmp/luma-live-recovery.mjs used history.show/input.key to choose Gilded
  Age S4E2. Resolution returned in8.166 seconds. Its immediate assertion failed
  because buffering had not acquired duration yet; follow-up status showed
  duration3093.007, position24.829, AAC audio, no recovery request. User physically
  confirms correct picture and audio. Playback left running.
- git diff --check: pass before final documentation check.

## Findings / limits
Earlier diagnostics showed about90 seconds buffering without audio/video, disk
cache off. History stores progress, not stream URLs. Direct fresh API source
fetches timed out; several other providers reported no usable stream. This
supports upstream source failure, not durable stale cache. The new live attempt
found usable media on the projector; no phone assistance needed. It does not
prove which alternate provider changed availability, or guarantee every source.
Phone fallback is built and browser-tested, but actual failure-to-phone-to-Cast
has not been physically accepted. No public release/feed/commit/push. Cold boot
and long-Home power tests remain deferred; projector was not powered off.

## Remaining questions / next prompt
If a later source fails, verify Open episode appears on the connected phone and
Play/Cast returns a fresh working stream. Separately measure cold-boot receiver
readiness and test the authorized Home-hold shutdown when user is ready.
Suggested prompt: Test phone-assisted recovery on an unavailable stream without
interrupting working playback; then verify cold-boot readiness when convenient.
