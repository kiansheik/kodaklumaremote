# Episode-aware anime Sub/Dub toggle

## Goal

Add a Sub/Dub selector to the phone's subtitle menu when the alternate audio
edition is available, and switch the necessary stream while preserving playback.

## Files inspected

- `docs/agent/index.md`, `current-state.md`, `repo-map.md`, `open-questions.md`
- `src/mobile/index.html`, `src/mobile/app.js`
- `AnimeCatalogClient.java`, `ConnectionService.java`, `MainActivity.java`
- Android catalog instrumentation and browser mobile fixtures
- release/build scripts and current release configuration

## Files changed

- `src/mobile/index.html`, `src/mobile/app.js`
- `android/app/src/main/java/dev/framextv/lumaremote/AnimeCatalogClient.java`
- `android/app/src/main/java/dev/framextv/lumaremote/ConnectionService.java`
- `android/app/src/main/java/dev/framextv/lumaremote/MainActivity.java`
- `android/app/src/androidTest/java/dev/framextv/lumaremote/CatalogInstrumentation.java`
- `tests/browser/mobile.spec.js`, `scripts/test-android-catalog-history.mjs`
- `android/app/build.gradle`, `release-notes/0.4.27.txt`
- `docs/agent/current-state.md`, `index.md`, `repo-map.md`, `open-questions.md`, `log.md`
- this handoff

## Commands run

- focused and complete Playwright mobile/browser suites
- complete Node test suite
- Android and AndroidTest Java compilation
- isolated emulator fixture build and catalog/history/Library/TV catalog/web instrumentation
- Android debug lint
- signed phone and receiver release builds
- APK package/signature inspection and `git diff --check`

## What worked

- The sheet requests alternate availability only for canonical anime playback.
  A false/unavailable result leaves the audio control hidden.
- A successful Sub/Dub switch validates the returned content key, AniList ID and
  audio edition before handing it to the existing receiver flow. The new stream
  seeks to the observed current position and uses its own subtitle catalog.
- Missing anime stream 404/410 responses now try all three fixed anime hosts and
  become `SOURCE_UNAVAILABLE`, allowing the UI to distinguish absence from a
  malformed or mismatched response.
- Focused toggle tests pass 2/2. Complete Node 252/252 and browser 169/169 pass.
  Emulator groups pass 11/11 catalog, 7/7 history, 9/9 Library, 5/5 TV catalog
  and 4/4 Web content. Android/AndroidTest compilation passes.
- Signed release builds pass. `aapt2` confirms phone 0.4.27/code37 and receiver
  0.3.5/code19; the phone APK signature verifies against the configured signer.

## What failed

- Initial sandboxed Playwright/ADB attempts could not bind loopback ports; scoped
  external retries passed.
- Full `lintDebug` still fails on six API-level findings in the earlier
  `ResolvedSources` and `ThumbnailStrip` file-reading code (`File.toPath` and
  `Files.readAllBytes` require API 26 while minSdk is 24). This change introduced
  no new lint finding, and the release build's lint-vital task passes.

## Remaining questions

- Physical acceptance remains: verify a real episode with both editions swaps
  at the same position with matching audio/subtitles on LUMA and Philips.
- Verify a known single-edition episode does not display the selector when the
  provider returns unavailable.

## Suggested next prompt

`Run make install-latest-apks, then I will cast one anime with both editions and test the Sub/Dub switch at a known timestamp.`
