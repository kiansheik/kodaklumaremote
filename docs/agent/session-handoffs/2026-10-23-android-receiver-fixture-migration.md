# Android fixtures migrated from Kodi to LUMA Receiver (2026-10-23)

## Goal

Move the phone's emulator instrumentation and Node fixture runners from Kodi
JSON-RPC to the [LUMA Receiver](../luma-receiver.md) LAN API that the phone
source (`NativeBackend` receiver section, `ConnectionService`, `SettingsStore`,
`LibraryStore`) now targets, and get them passing.

## Changed

- `android/app/src/androidTest/.../BackendInstrumentation.java`: 14 checks —
  pairing (wrong/malformed/expired code, issued token), `luma.launchReceiver`,
  open guards (local `expectedSourceUrl`/`expectedContentKey`; forwarded
  `onlyIfInactive`, `expectedCurrent*`, `resumeSeconds`), boundaries, subtitles,
  receipt mapping (4xx→`RECEIVER_REJECTED`, 5xx/malformed/missing result→
  `RECEIVER_RESPONSE`, `INVALID_CONTENT`→`INVALID_MEDIA`, `CANNOT_SEEK`),
  `UNAUTHORIZED`→`RECEIVER_AUTH` clearing the token, snapshot readiness,
  malformed status/identity, shared 5 s budget, pause/resume/seek/stop, close.
- `ServiceInstrumentation.java`: pairing replaces credential save (token
  persisted only by `pairComplete`, stripped from replies); add-on probe race
  replaced by a receiver-side `expectedCurrent*` rejection; new two-way Library
  sync check (pull lands in Library; forced round pushes newer progress).
  The test now republishes after its reflective system-Next scope cleanup.
- `SettingsInstrumentation.java` (6 checks incl. legacy Kodi deletion),
  `SigningMigrationInstrumentation.java`, `LiveControlsInstrumentation.java`,
  `MultiReceiverInstrumentation.java`, `SubtitleInstrumentation.java`,
  `WatchHistoryInstrumentation.java`/`MatterInstrumentation.java` (naming only).
- `scripts/test-android-{backend,service,subtitles,multi-receiver,settings,signing-migration}.mjs`:
  receiver fixtures and wire assertions. The service fixture listens on 4191.
- Source fix: `ConnectionService` `SIDE_CHANNEL` no longer contains
  `receiver.status`, so failed status reads mark playback stale / clear it like
  `kodi.status` did; status is still exempt from the extra 250 ms re-poll.

## Commands

```sh
gradle -p android -PreceiverFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
export ANDROID_TEST_PACKAGE=dev.framextv.lumaremote.receiverfixture
caffeinate -i -u node scripts/test-android-{backend,settings,subtitles,multi-receiver,service}.mjs
```

Emulator: AVD `LanguageMemory_AnalysisTest` (API 36, arm64) started with
`-read-only -no-snapshot -no-window` on port 5554.

## Results

Backend 14/14, settings 6/6, subtitles 9/9, multi-receiver 5/5, service 18/18
(three consecutive passes after the fixes). Unchanged neighbours: msx 8, matter
7, catalog-history 6/6/6/5/4, scrub-preview 7 pass.

## Not run / failures

- Signing migration: needs production-signed bootstrap/public APKs; the AVD also
  has a differently signed `dev.framextv.lumaremote` 0.2.4, so seed mode was not
  attempted (no uninstall). Compiles.
- Live controls: physical-phone only. Compiles.
- `test-android-ui.mjs`: check 7 fails deterministically with "Packaged WebView
  did not become ready" (browser capture/navigation, not receiver code); not
  investigated. `test-android-updates.mjs` targets the non-fixture package and
  hit the AVD's old app (`UPDATE_FIXTURE_FORBIDDEN`).
- The Mac idle-slept on battery mid-run, freezing the emulator; that produced
  timeouts unrelated to code until runs were wrapped in `caffeinate -i -u`.

## Open questions

- With a receiver that honours `resumeSeconds` at open, service Next still sends
  a redundant guarded seek (`resumeApplied` is decided from the request, not the
  observed position). Harmless; the fixture deliberately ignores resume.

## Suggested next prompt

"Investigate the deterministic UI smoke failure (check 7, packaged WebView not
ready) against the current `src/mobile` changes, then run the full Android
fixture sweep under `caffeinate -i -u`."
