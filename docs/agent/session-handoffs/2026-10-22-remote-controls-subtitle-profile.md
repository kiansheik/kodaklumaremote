# Remote controls and subtitle provider profile

## Goal

Add next-episode controls to the main remote, notification and lock screen; put
scrubbing and back 30 seconds on the main remote; sample current homepage
subtitle tracks and repair a reproduced loading blocker.

## Files inspected

- Required agent index, current state, repo map and open questions.
- Mobile remote HTML/JavaScript, handoff coordinator and browser fixtures.
- MainActivity, ConnectionService, MediaSession/notification state, LibraryStore,
  WatchHistory, SubtitleNormalizer, SubtitleDelivery and TvSubtitleBridge.
- Native subtitle/library/service fixtures and current homepage/API responses.

## Files changed

- `src/mobile/index.html`, `src/mobile/app.js`: main scrubber, back 30 seconds,
  next-episode button and guarded catalog-resolution flow.
- `ConnectionService.java`, `MainActivity.java`, `LibraryStore.java`: preserve
  validated content identity, resolve the exact cached successor, advertise
  Android skip-next, show a notification action and route it through a
  revision-checked app launch.
- `SubtitleNormalizer.java`, `SubtitleDelivery.java`, `TvSubtitleBridge.java`:
  bounded isolated-cue salvage and discarded-cue accounting.
- JVM/Android/browser tests and agent documentation.

## Commands run

- Read-only current homepage/config/subtitle API sampling; 12 selected EN/PT/PT-BR
  bodies were kept only under `/tmp`.
- `node scripts/test-subtitle-normalizer.mjs` — 25 checks passed.
- `node scripts/test-subtitle-delivery.mjs` — 10 localhost checks passed.
- Temporary runner against the 12 live bodies — all normalized after the repair.
- `npm test` — 243 passed outside the sandbox for localhost fixtures.
- Mobile Playwright files — 81 passed in the final full run.
- `npm run build`, `npm run build:mobile`, `git diff --check` — passed.
- Manual Java 17 compilation of all changed production Java against Android 35,
  existing generated R/classes, Matter JARs and ZXing — passed.

## What worked

- Main remote seek changes only on slider commit; preview input alone sends no
  receiver mutation. Back 30 clamps at zero and uses the same source guard.
- Next episode is exposed only for an exact catalog-proven successor. System
  controls carry the current media revision and cannot target a stale source.
- The current provider sample had 0/42/64/51/72 tracks across five titles. Eleven
  of twelve selected bodies already passed; the remaining track had one reversed
  final cue after 273 valid cues and now passes with one reported discard.
- Phone-hosted, receiver-pinned normalized SRT delivery was already present for
  both Kodi and Philips; no new general proxy was added.

## What failed

- The current homepage blanked after initial automated inspection, consistent
  with the existing anti-debugging behavior; read-only HTTP/API sampling supplied
  the profile instead.
- `gradle` is not installed in the shell, so a Gradle APK/instrumentation build
  was unavailable. Manual compilation passed, but it is not an APK build.
- The first sandboxed browser/Node runs could not bind localhost. The permitted
  localhost reruns passed.

## Remaining questions

- Notification shade, lock-screen next and main scrub/back need physical-phone
  and receiver acceptance, especially on HLS.
- A fresh affected-title subtitle selection must confirm visible, synchronized
  captions and whether any receiver crash remains.
- Provider responses with no tracks or other corruptions remain separate cases;
  this change intentionally salvages only a very small malformed fraction.

## Suggested next prompt

Build/install an unreleased fixture on the phone, cast one sampled episode, then
verify main scrub, back 30, notification next, lock-screen next and one previously
failing English/Portuguese subtitle selection without replaying the video.
