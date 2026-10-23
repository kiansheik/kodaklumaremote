# Cross-phone Kodi metadata and scrub previews

## Goal

Add Back 30 and exact Next episode to notification/lock-screen controls, provide
phone-only scrub previews, and let a second phone recover canonical metadata and
current progress from an episode another phone started in Kodi. Harden selected
subtitle downloads against DNS validation/connection races.

## Files inspected

- `android/app/src/main/java/dev/framextv/lumaremote/{ConnectionService,NativeBackend,CatalogClient,WatchHistory,LibraryStore,SubtitleDelivery,ScrubPreviewManager}.java`
- `src/mobile/{index.html,app.js}`
- `kodi-addon/script.lumaremote.metadata/`
- `scripts/{package-kodi-addon,build-android-release,publish-android-release,release-next-android}.mjs`
- Relevant Android, Node and browser fixtures
- Current homepage/API, one HLS playlist and one DASH manifest, read-only and
  without retaining raw media URLs

## Files changed

- Android service/backend/catalog/delivery code and native/JVM fixtures for
  system rewind, Activity-free system Next, strict Kodi metadata, joining-phone
  hydration/progress, DNS-pinned subtitle fetching and preview extraction
- Mobile remote UI and browser fixtures for previewed scrubbing
- `kodi-addon/script.lumaremote.metadata/` and add-on/package fixtures
- Release scripts/tests so the add-on ZIP accompanies the APK and `update.json`
  while the unrelated local web-receiver scope remains untracked and unstaged
- `docs/agent/{index,current-state,repo-map,open-questions,log,kodi-metadata-sync,scrub-preview-profile}.md`
- This handoff

## Commands run

- `npm test`
- `npm run test:browser`
- `node --test tests/handoff.test.mjs`
- `node scripts/test-android-scrub-preview.mjs`
- `node scripts/test-android-catalog-history.mjs`
- `node scripts/test-android-backend.mjs`
- `node scripts/test-android-service.mjs`
- `node scripts/test-android-subtitles.mjs`
- `node scripts/test-android-multi-receiver.mjs`
- `node scripts/test-subtitle-delivery.mjs`
- `node scripts/test-subtitle-normalizer.mjs`
- Local production-signed `scripts/build-android-release.mjs` invocation through
  the ignored release config
- Gradle `:app:lintRelease`
- Independent APK metadata/signature/manifest and deterministic add-on validation
- Combined release-manifest/add-on auto-staging boundary tests (18/18)
- Gradle debug and Android-test fixture assembly with rerun tasks
- `git diff --check`

## What worked

- Back 30 is projected as a notification action and MediaSession rewind only for
  verified seekable playback; the write remains source/revision bound.
- Notification Next opens the app through a direct user action. MediaSession Next
  remains in the background service, rechecks the exact current source/canonical
  key after catalog resolution and the add-on capability probe, and opened one
  guarded successor without launching an Activity or unlocking the API 36 emulator.
- The add-on applies canonical Kodi item metadata, and `kodi.status` reports it
  only across stable exact-source item reads.
- A joining phone adopts and hydrates the active canonical item without resolving
  or replacing its stream. Kodi polls update that phone's Resume/Library state;
  subtitle and next-episode flows are restored from catalog metadata.
- Same-URL and different-source content transitions do not attribute a new
  timestamp to the old episode; stale hydration/control work is invalidated and
  prepared subtitle capabilities are revoked.
- Public subtitle downloads resolve each redirect once, reject mixed public/
  non-public address sets and connect only within the approved set while
  preserving Host, TLS SNI and certificate hostname verification.
- Phone previews are latest-wins, low-quality and memory-only for direct HTTPS,
  unencrypted VOD HLS on API 27+. Unsupported HLS, DASH and progressive sources
  leave the ordinary timestamp scrubber usable. No sampled provider-native
  storyboard/trick-play asset was available.
- `npm test` passed 269/269; browser passed 155/155 including mobile 45/45;
  handoff passed 78/78; backend 13/13, service 17/17, Android subtitles 9/9,
  preview 7/7 and multi-receiver 5/5 passed; catalog/history/Library/TV-catalog/
  resolver passed 6/6/6/5/4; JVM subtitle delivery passed 13/13 and normalizer
  25/25; package/release checks passed 13/13.
- Production-signed 0.4.8/code-18 release assembly and lint passed. The final APK
  is 9,793,842 bytes with SHA-256
  `09b72de640663539af191a4e4f32305ced99f90d65a148b8eb3bd1d9897d3202`;
  its package, minSdk 24, sole production signer, update-manifest hash and notes
  match. The exact-source five-file add-on ZIP is 8,426 bytes with SHA-256
  `a4aa9fe6513395520d8073c13177e5d44946bd7cd8c17e80540742bc71950b8e`.
- Auto-staging explicitly ignores the existing local web-receiver docs, dev
  script, Android receiver tree and header capture; the final staged index stayed
  empty and those unrelated files remained untracked.

## What failed

- No sampled stream response, HLS playlist or DASH manifest exposed a usable
  provider storyboard or trick-play image track.
- No APK/release was published or installed on a physical phone, and the add-on
  was not installed on physical Kodi; those acceptance results remain unknown.
- Kodi offers no atomic compare-and-open RPC. Identity changes observed by the
  final pre-write check are rejected, but a second controller can still switch in
  the narrow gap between that read and `ExecuteAddon`/`Player.Open`; the emulator
  race fixtures cannot close or physically accept that receiver-side TOCTOU.

## Remaining questions

- Does physical LUMA Kodi preserve the add-on metadata through a long episode and
  real next-episode transition, including when a second phone joins mid-playback?
- During real simultaneous two-phone control, is the residual non-atomic Kodi
  compare/open window acceptable, or does it require receiver-side serialization?
- Does Resume stay acceptably current at the four-second poll interval on both
  phones, and does a selected subtitle work after adoption?
- Which representative direct HTTPS, unencrypted VOD HLS sources can the physical
  phone preview on API 27+, and are latency, data use and cache limits acceptable?
- Can a provider-native storyboard later broaden support to DASH, progressive or
  unsupported HLS without handing a remote URL to the platform decoder?
- Historical viewing that exists only on another phone is not synchronized; is a
  future account/receiver history service desired?
- Kodi exposes no per-Open nonce here; does an identical-source, identical-key
  player recreation exhibit the known cached external-subtitle-index limitation?

## Suggested next prompt

Install the signed candidate and bundled Kodi add-on ZIP on the test phone/LUMA
Kodi, then perform the documented two-phone episode, Resume, subtitle, preview,
notification Back 30 and locked-screen next-episode acceptance test.
