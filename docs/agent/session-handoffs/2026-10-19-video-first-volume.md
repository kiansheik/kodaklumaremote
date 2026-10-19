# Video-first startup, manual subtitles and coalesced volume

## Goal

User reports0.4.2 still crashes Kodi on the first attempt, often hangs and never
loads subtitles. They requested starting video before any subtitle selection,
roughly one-second spacing between commands, and locally combining rapid volume
taps into one final command after a few seconds. Physical crash cause is unknown;
Julia's Motorola is unavailable for debugging. Preserve saved setup and all
source, cancellation and uncertain-write safeguards.

## Files inspected

Required agent index/current-state/repo-map/open-questions, phone-app, previous
Motorola recovery handoff, releasing instructions; coordinator/mobile/MSX source
and fixtures; MainActivity, NativeBackend, ConnectionService, service/native
instrumentation/runners, version config and private local signing loader.

## Files changed

- `src/handoff/coordinator.mjs`, `tests/handoff.test.mjs`: no startup subtitle
  calls; complete manual catalog; one-second launch/mutation settling with fresh
  source checks, preserving uncertain Open/Add and cancellation protections.
- `src/mobile/app.js`, three mobile browser specs: explicit language choice after
  playback; first-setup launch settling; optimistic nonblocking volume and native
  queue integration; separate debounced TV volume, explicit-action cancellation.
- `ConnectionService.java`, `ServiceInstrumentation.java`, service runner:
  shared UI/system two-second volume queue, projected snapshots, revocable write
  tokens, bounded CPU lock and isolated wire-count/race/cancellation regressions.
- `MsxServer.java`, `src/msx/receiver-core.js`, receiver tests: strict optional
  `autoSubtitles:false` preserves all languages but performs no startup fetch.
  Older clients retain their current default.
- Android version0.4.3/code12, release notes, agent index/current-state/repo-map/
  phone-app/log and this handoff. Untracked `headers.txt` remains untouched.

## Commands run

- Targeted `rg`/`sed`, git diff/status/whitespace; JavaScript syntax checks.
- `node --test tests/handoff.test.mjs`:46 passed.
- `node --test tests/msx-receiver.test.mjs`:20 passed.
- Final `npm test`:181 passed.
- Mobile Playwright suite:57 passed, including no-scroll layouts, zero startup
  subtitle requests, missing Portuguese/manual English, stale volume replies,
  in-flight input and delayed receiver-switch cancellation.
- Isolated fixture `assembleDebug assembleDebugAndroidTest lintDebug` passed.
  Service14 passed, including genuine screen-off/keyguard-locked actions and
  exact wire counts (one absolute write per burst, no relative volume keys).
  After the callback-delivery projection refinement, the final debug/test/lint
  build passed and service14/UI5 were rerun successfully. The deterministic
  regression blocks main-thread delivery until newer input is queued, then
  verifies the old callback delivers the newest projection. Original emulator
  lock setting was restored.
-103 local documentation links and whitespace/syntax checks passed.
- Other isolated native suites passed: backend12, settings5 and dual receiver4
  (40 unique native checks total).
- `npm run release:android -- --notes-file release-notes/0.4.3.txt` through the
  ignored local signing loader: committed-source signed build and lintVital
  passed; configured production certificate and16KB ZIP alignment verified.
  Source/tag target`b5604ba05f95bd83dc33229b9f4fdc3097db8417`.
- `git push origin main`; `npm run publish:android -- --directory
  .local/releases/v0.4.3`: publicv0.4.3/code12 and matching install/update page.
  Pages commit`6a7346ab1e0eb023d32bb732b796116762312943` is built/no error.
- Anonymous HTTPS update feed/APK GET200. Served metadata equals prepared;
  downloaded APK9,648,884bytes, SHA-256
  `f3703b75ceae7678a6908ea4aa096c177b7e9f651920dd07c4ebfa3afda6b7a8`,
  and production signer match. Chrome rendered0.4.3 and the exact APK link.
  No physical phone installation or projector/TV commands were sent.

## What worked

Startup no longer depends on subtitle availability or Kodi's subtitle API.
Every captured language remains selectable afterward, sorted PT-BR then English.
Manual selection still binds actual observed indices without guessing labels or
reopening video. Launch settles before the first connect; writes settle for a
second and recheck source after waiting. Readiness polling remains bounded.

LUMA taps/slider/system buttons share a background absolute-volume queue. The
phone shows the pending value immediately; old polls and receipts cannot replace
newer intent. A two-second trailing cooldown produces one write for a burst.
Inputs arriving during a write remain pending for a later final write; target,
source and connection changes invalidate unsent work. TV UI volume uses its own
debounce with source/receiver scope. No media proxy or credential changes.

## What failed

The first Node run overlapped test migration and failed obsolete automatic
subtitle expectations; the completed suite passes. The first browser run passed
54/55; its remaining assertion assumed the uncertain-retry branch even after a
native receipt explicitly proved the cancelled write was not sent. That timing
assertion was removed; source/token/no-overlap checks remain. Final57-case rerun
passes. Peer review caught and fixed stale receiver timers, pending volume lost
on current handoff metadata completion, failed-write connection state, and a
late native volume reply being projected before main-thread delivery.
The real projector crash is not reproducible from available device evidence.

## Remaining questions

Does cold-start casting now work on the actual LUMA without retry? Does explicit
PT-BR/English selection render matching subtitles after playback begins? Do rapid
volume taps settle smoothly from both the app and a locked Motorola? Emulator
fixtures cannot establish any of those physical outcomes.

## Suggested next prompt

Install0.4.3, start with Kodi closed and cast once. After picture/sound begins,
open Subtitles and choose an available language. Check rapid volume taps in the
remote and lock-screen card, including while returning from another app. Report
the exact episode/page if Kodi still exits; collect device logs when available.
