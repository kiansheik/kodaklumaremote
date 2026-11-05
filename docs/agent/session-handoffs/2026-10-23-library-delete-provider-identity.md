# Library deletion, current-title shortcut and provider identity guard

## Goal

Make bad saved metadata recoverable, open the current title directly in Library,
and prevent a provider's demonstrably different movie from being opened under
the selected catalog identity.

## Files inspected

- `src/mobile/{index.html,app.js,capture.js}` and focused browser fixtures
- Phone `CatalogClient`, `ConnectionService`, `LibraryStore`, `WatchHistory`,
  `WebContentResolver`, `ResolvedSources` and native instrumentation
- Receiver `LibraryStore`, `SyncRecord`, media/playback classes and unit tests
- Public catalog/provider responses and read-only live receiver status; no raw
  media URL or credentials were retained in documentation

## Files changed

- Phone and receiver Library storage/sync code now supports versioned episode
  and whole-title deletion tombstones with stale-write suppression.
- Mobile Library UI now reveals deletion on long press and the Remote title opens
  the exact saved title/episode.
- `CatalogClient` checks positive Films365 path-title evidence, tries bounded
  declared alternate providers, and `ConnectionService` evicts matching bad
  cached sources. `WebContentResolver` applies the same rejection to site capture.
- Tests, release metadata, release notes and agent documentation were updated.

## Commands run

- `npm test`
- `npx playwright test`
- Focused mobile/browser Playwright runs during implementation
- Receiver Gradle unit tests
- Phone/AndroidTest offline Gradle assembly with the isolated receiver fixture
- `node scripts/test-android-catalog-history.mjs`
- `npm run build`
- `node scripts/build-latest-apks.mjs`
- `aapt2 dump badging` and `shasum -a 256` for both release APKs
- `git diff --check`

## What worked

- Node 247/247 and browser 167/167 passed.
- Emulator groups passed: catalog 10/10, history 7/7, Library 9/9, TV catalog
  5/5 and Web content 4/4.
- The exact `Buddy` versus `Buddy Games` nested URL case is rejected; the test
  proves the rejected provider is skipped and an alternate source is returned.
- Existing Library data migrates, and stale phone/receiver progress cannot
  resurrect tested episode or title deletions.
- Both signed release builds passed. Package metadata is phone 0.4.26/code36 and
  receiver 0.3.5/code19; their SHA-256 values are `bf7a5b7e8c21501070f06c3bc7892d03d5033b39314ce7e10fb7e63f5be544c1`
  and `7747a06cef850fe99ab5e91f51a82172ff08cba93a408be790680838ddbfc9b8`.

## What failed

- The first mismatch fixture exposed form-query `+` decoding that made the
  nested provider URL unparsable. The nested URL is now returned to URI form.
- The first Web capture check collapsed `SOURCE_MISMATCH` into the generic
  unsupported error. The resolver now preserves its typed exception.
- No physical-device install or playback was performed in this work; current
  playback was not interrupted.

## Remaining questions

- On the real phone/projector, does recasting the affected movie discard the old
  cached source and select the correct alternate quickly enough?
- Do episode and whole-series tombstones remain deleted after both devices and a
  second phone reconnect?
- Opaque CDN/HLS URLs provide no pre-playback title evidence. A future stronger
  check would require trustworthy provider metadata, not filename heuristics.

## Suggested next prompt

Install the prepared phone 0.4.26 and receiver 0.3.5 APKs, recast Buddy, verify
the actual movie, then exercise episode and whole-series deletion across a
receiver reconnect.
