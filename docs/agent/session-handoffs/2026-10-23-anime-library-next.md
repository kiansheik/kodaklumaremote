# Anime identity, sub/dub, Library and Next audit

## Goal

Explain and fix why **Next** labeled an episode of *The World's Strongest
Witch* but played an unrelated anime with unrelated subtitles, and audit enough
subbed, dubbed and multi-season anime to model the site correctly.

## Files inspected

- `docs/agent/index.md`, `current-state.md`, `repo-map.md`, `open-questions.md`
- `src/mobile/capture.js`, `src/mobile/app.js`, shared media helpers
- phone capture, cache, history, Library, catalog, resolver and notification code
- receiver media validation, catalog and playback code
- current public frameXTV watch/anime JavaScript and fixed API responses
- live receiver status and bounded diagnostics
- Android, receiver, browser and release tests/build configuration

## Files changed

- `src/mobile/capture.js`, `src/mobile/app.js`
- `android/app/build.gradle`
- phone `AnimeCatalogClient.java`, `ConnectionService.java`,
  `ResolvedSources.java` and the preceding Library/history integration changes
- Android catalog, Library and watch-history instrumentation
- `tests/browser/mobile-capture.spec.js`, `tests/browser/mobile.spec.js`
- `release-notes/0.4.25.txt`
- this handoff, `docs/agent/current-state.md`, `index.md` and `log.md`

## Structure observed

- frameXTV first exposes animated TV as
  `/watch/{tmdb}/{season}/{episode}`, asynchronously maps it through AniZip, then
  replaces the SPA route with `/watch/anime/{anilist}/{animeEpisode}`.
- Its player iframe can already be
  `/embed/anime?id={anilist}&episode={animeEpisode}&sub_type={sub|dub}` while the
  outer page still has the canonical TMDB route. The sub/dub switch changes the
  iframe query, not the outer route.
- AniZip maps an anime episode to canonical `seasonNumber`/`episodeNumber`.
  Later seasons may have separate AniList IDs; the trusted anime-details season
  relationship is required where a related mapping omits its TMDB ID.
- Live samples:
  - *The World's Strongest Witch* AniList 211778 / TMDB 321518: E1 sub and dub
    differ; E2 sub exists while the audited E2 dub returned 404.
  - *Frieren* AniList 154587 / TMDB 209867: sub and dub use different provider
    families and subtitle sets; later seasons use AniList 182255 and 209939.
  - *Solo Leveling* AniList 151807 / TMDB 127532: sub and dub have distinct
    source fingerprints even within the same provider family.
  - *Demon Slayer* AniList 101922 / TMDB 85937: dub uses its dub provider and
    later seasons use AniList 129874, 142329, 145139 and 166240.

## Root causes and fixes

- The failing physical state had title/content `S1E2`, but its source URL named
  *Arifureta* and both `animeId` and `animeAudio` were absent. Next therefore
  used the ordinary TV resolver and mislabeled its unrelated result.
- Capture now reads AniList ID, anime episode and sub/dub from the selected
  same-origin iframe even during the outer-page redirect race.
- Native identification verifies exact TMDB/season/episode mapping. Resolution
  switches only to a verified related AniList season, then requires the stream
  response to repeat the requested AniList ID and audio edition.
- Cache keys distinguish ordinary TV, AniList season and sub/dub. An ordinary
  or opposite-edition cached stream cannot satisfy an anime request.
- Next preserves anime provenance; failed optional anime identification casts
  the current captured source without inventing canonical Next controls.
- Library details sends only its valid `{kind,id}` native schema. Anime fields
  remain local replay provenance, fixing `LIBRARY_INVALID_INPUT` and the empty
  episode panel.

## Commands run

- live receiver status and diagnostics
- fixed-host `curl` checks of AniZip mappings, anime details, sub/dub streams
  and the public site JavaScript
- Android release and AndroidTest Java compilation
- fixture builds and isolated emulator instrumentation
- focused and complete Playwright suites; Node and receiver tests from the
  preceding implementation pass
- signed local release build, APK metadata inspection and `git diff --check`
- `adb mdns services`, `adb connect` and `adb devices -l`

## What worked

- Focused browser tests pass 63/63, including the canonical-route/anime-iframe
  race and Library detail schema.
- Emulator groups pass: catalog 9/9, history 7/7, Library 7/7, TV catalog 5/5,
  Web content 4/4. They cover cross-season AniList switching, sub/dub cache
  separation and rejection of wrong-series or wrong-audio stream responses.
- Phone and AndroidTest source compilation passes. The complete browser suite
  passes 166/166; signed release build passes and `aapt2` confirms phone
  0.4.25/code35 and receiver 0.3.4/code18.

## What failed

- Sandboxed Gradle could not load its native macOS library; the scoped external
  retry passed.
- The Samsung advertised `192.168.68.54:33963` through ADB mDNS but refused the
  connection. Only the isolated emulator was connected, so phone 0.4.25 was not
  physically installed and no real playback success is claimed.

## Remaining questions

- After 0.4.25 is installed, does a fresh anime cast show the exact episode and
  edition in Library, and does Next play the verified following episode on the
  physical projector?
- The currently wrong active playback/history entry is not proof of the new
  path; recast the desired episode after installing rather than trusting it.

## Suggested next prompt

`Wireless debugging is accepting connections again. Install phone 0.4.25, then I will recast one sub and one dub episode and test Library and Next.`
