# Local subtitle hotfix and live install

## Goal

Keep the laptop subtitle selection stable through polling, expose laptop-loaded
tracks on phones joining after cast, install both live-test apps immediately,
then restore sound for the exact E-AC-3-only MKV without losing direct video,
fixed duration or seeking.

## Files inspected

- Laptop local-media browser/server and focused tests
- Phone `ConnectionService`, native receiver adapter and service instrumentation
- Receiver external-caption/status implementation and release/install scripts
- Receiver Media3 source construction/audio selection and exact source probes

## Files changed

- `scripts/local-media-remote.mjs`, `tests/browser/local-media-remote.spec.js`
- `scripts/stream-local-media.mjs`, `tests/local-media-stream.test.mjs`
- `android/app/src/main/java/dev/framextv/lumaremote/ConnectionService.java`
- `android/app/src/androidTest/java/dev/framextv/lumaremote/ServiceInstrumentation.java`
- `scripts/test-android-service.mjs`, phone version configuration and agent docs
- Receiver `MediaRequest`, `PlaybackController`, focused tests and version config

## Commands run

- Focused local-media Node and Playwright tests
- Offline phone and AndroidTest compilation/assembly
- One emulator service attempt, stopped after an older timing assertion failed
- `make install-latest-apks` twice: first exposed the installed version-code
  boundary; second installed phone code39 and receiver code21
- Read-only phone package inspection and Activity launch
- `ffprobe` of the exact MKV, real extraction of its full English stream, and
  authenticated live receiver status after restarting the laptop remote
- Signed receiver 0.3.8/code22 build/deploy, focused local-media Node 9/9,
  exact cached companion probe and authenticated post-open status

## What worked

- Pending laptop selection survives a full polling interval and Apply sends the
  selected external index; focused browser regression passes 1/1.
- Phone service imports receiver-owned track names/languages and selected state
  only for opaque local sessions; Java and AndroidTest sources compile.
- Phone 0.4.29/code39 is installed and foregrounded with data preserved.
- Receiver 0.3.7/code21 is confirmed installed through the paired dev channel.
- Auto direct-streams the exact H.264/E-AC-3 MKV at its fixed 6292.832-second
  duration. Embedded SubRip/ASS/SSA/WebVTT/mov_text tracks are extracted for the
  receiver even when conversion is needed; full dialogue precedes forced/SDH.
- The running receiver reports full English external index512 enabled and the
  source seekable with a fixed duration; local-media Node tests pass 7/7.
- Direct H.264 playback with only unsupported audio now creates and caches a
  320 kb/s stereo AAC companion; the original video remains direct.
- Receiver 0.3.8/code22 is installed. The restarted exact session reports
  ready/playing, `audio/mp4a-latm`, two channels, 6292.832-second duration,
  seeking and full English captions. The cached AAC independently matches that
  duration at 48 kHz stereo.
- Future runs create three fast-seek thumbnail frames concurrently and spread
  early results across the timeline before filling 30-second gaps.

## What failed

- The first install used code34 while the physical phone already had code38 and
  Android correctly rejected the downgrade. The live candidate was advanced to
  code39 instead of uninstalling or clearing data.
- The expanded emulator service run reached an unrelated pre-existing canonical
  timing assertion after five checks. Subtitle polling was then narrowed to local
  sessions, but the user explicitly requested no more tests before live viewing.
- The release was interrupted during browser tests before commit or publication.
- The first 0.3.8 build found that `Tracks.Group.length` is a field in pinned
  Media3, not a method. The correction compiled and installed successfully.

## Remaining questions

- Do Track 1 captions render on the physical projector after restarting the
  laptop remote process? Receiver state says the full English track is enabled,
  but visible text still needs the user's observation.
- Does an arbitrary far seek now resume at its requested position on the exact
  projector, beyond the direct container's reported `canSeek:true` capability?
- Does the already-open phone list and control that track within one poll cycle?
- Is the selected AAC track audible through the physical projector speakers?
  Receiver state proves decoding/selection but cannot prove speaker output.
- On the next laptop launch, is cached-audio startup immediate and are the new
  distributed scrub thumbnails fast enough during a far seek?
- After live acceptance, how should the cumulative public version be reconciled
  with public 0.4.23/code33 and the physical phone's code39 without downgrading?

## Suggested next prompt

“Sound and subtitles are physically confirmed; restart once to accept the cached
audio and distributed thumbnails, then reconcile and publish this exact
phone/receiver candidate to both auto-update feeds without clearing data.”
