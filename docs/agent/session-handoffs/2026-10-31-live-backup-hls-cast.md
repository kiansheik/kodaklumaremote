# Live Backup HLS cast

## Goal

Allow the user to start a cross-origin Backup player normally and use the
explicit native Cast action to discover and cast its standard media stream,
without casting the iframe/page, copying browser credentials or weakening the
existing source/generation guards. Build, install and exercise the current live
phone page against the projector.

## Files inspected

- `android/app/src/main/java/dev/framextv/lumaremote/{MainActivity,NativeBackend,ConnectionService,ScrubPreviewManager,SubtitleDelivery}.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/{MediaRequest,PlaybackController}.java`
- `src/mobile/capture.js`
- `src/handoff/coordinator.mjs`
- `tests/handoff.test.mjs`
- Android manifests/build files and release/install/developer scripts
- Current phone UI, filtered app logs and sanitized authenticated receiver state

## Files changed

- Added phone `LiveMediaObserver.java`, `LiveMediaVerifier.java` and
  `MediaHeaders.java`.
- Updated phone `MainActivity.java`, `NativeBackend.java` and
  `ConnectionService.java` for the guarded fallback and safe-header propagation.
- Updated `src/handoff/coordinator.mjs` and `tests/handoff.test.mjs` so only
  Referer/Origin/User-Agent survive orchestration.
- Hardened receiver `MediaRequest.java` plus its focused test so unknown
  extensionless URLs are not inferred as MP4.
- Bumped phone to 0.4.32/code42 and receiver to 0.3.13/code27; added
  `release-notes/0.4.32.txt`.
- Updated current-state, repo-map, open-questions and chronological log.

## Commands run

- `node --test tests/handoff.test.mjs`
- Configured offline Gradle release assembly for phone and receiver through
  `node scripts/build-latest-apks.mjs`
- Focused receiver `MediaRequestTest`
- APK metadata/hash inspection
- Signed phone APK install over authorized wireless ADB
- Receiver developer deployment to the confirmed current projector address
- Exact Episode 11 VIEW launch, temporary screenshots, one native Cast tap,
  filtered `LumaHandoff` logs and sanitized authenticated `player.status`

## What worked

- Release build/lint/assembly succeeded for both signed APKs.
- Handoff tests passed 83/83; focused receiver media parsing passed.
- Phone 0.4.32 and receiver 0.3.13 installed successfully.
- The live cross-origin Backup page visibly resumed. Native Cast used the new
  fallback, logged verified HLS, and completed all handoff stages through ready.
- Projector status independently reported active/ready playback for the exact
  canonical Episode 11 identity, 1360.818-second duration, advancing position
  and buffer, seekability, stereo AAC and no error. Phone remote displayed the
  same 22:40 duration.
  Receiver subtitle status reported zero tracks and subtitles off; no caption
  success is claimed for this Backup stream.
- A final phone-only rebuild/install added the explicit source-unavailable-only
  gate. Reopening the final app left projector playback active/ready on the same
  episode and duration with its position still advancing.
- No raw stream URL, token, cookie, authorization value or diagnostic screenshot
  remains in checked-in documentation. Temporary phone screenshots were removed.

## What failed

- The first sandboxed Gradle invocation could not load its native library; the
  configured offline build succeeded outside that sandbox.
- The first compile used unavailable `JSONObject.keySet()` from the project's
  older JSON jar; changing to `keys()` fixed it.
- Initial projector deploy used stale developer-config address `.53` and timed
  out. Public receiver info confirmed current address `.56`; deployment there
  succeeded. The ignored developer config was not rewritten because it contains
  a pairing secret.
- One sanitized status probe mistakenly used port 8828 and was refused; retry on
  the receiver's actual port 4191 succeeded.

## Remaining questions

- This proves the current ordinary HLS Backup case, not every provider. Add a
  visible-player playback witness, manifest/segment correlation, duration/ad
  disambiguation, DNS-pinned transport and authenticated receiver-side probe
  before treating arbitrary candidates as generally supported.
- Blob/service-worker, DRM, cookie-dependent, IP-bound and ambiguous multi-stream
  providers remain unsupported. Do not bypass them.
- Backup captions are not scraped. Only tracks declared by a verified media
  structure should be considered later, with exact episode binding.
- Cross-origin sender playback cannot currently be paused after handoff.
- Public auto-update feeds were not published or changed.

## Suggested next prompt

Add a playback witness and projector-side probe ticket for observed Backup media,
then cover ambiguous ad/main manifests and manifest-declared subtitles with local
fixtures before publishing phone 0.4.32 and receiver 0.3.13.
