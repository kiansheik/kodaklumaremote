# Handoff — Android source-to-Kodi and no-scroll remote

Later same-day work installed the baseline on the Samsung and added saved setup
and background controls; see the
[current follow-up](2026-10-18-phone-background-settings.md).

## Goal

Samsung phone → frameXTV player → Cast → LUMA launches Kodi → wait for HTTP →
media plus all subtitles → Brazilian Portuguese, then English → combined remote.
Use specific Kodak Luma reviews to improve discovery/recovery/input. Final
hardware acceptance requires receiver playback after full phone power-off.

## Files inspected

Required agent notes; original mission; media/extension/Kodi/LUMA implementation
and tests; prior physical evidence; REA static protocol evidence; public Google
Play listing/review endpoint; installed SDK/JDK/Gradle metadata; official Android
bridge, local-content and build documentation.

## Files changed

- `src/handoff/coordinator.mjs`, `tests/handoff.test.mjs`: bounded orchestration,
  all-track observed-index bindings, preference selection, cancellation and
  conservative retry with explicit pre-write negative proof.
- `src/mobile/{index.html,app.js,capture.js}` and mobile browser tests: no-scroll
  primary controls, separate Browse/Devices/secondary panels, keyboard/draft/auth
  recovery, native RPC and user-triggered supported-origin source capture.
- `android/`: Gradle app, isolated WebViews, native LUMA/Kodi transport, shared
  links, capture/attempt lifecycle, boundary and instrumentation fixtures.
- `scripts/build-mobile.mjs`, `scripts/test-android-{backend,ui}.mjs`: generated
  local assets and explicit emulator-only fixture runners. Gradle bundles assets.
- Package scripts, README/Android README, phone/review/agent state/map/index/
  questions/decisions/progress/log/handoff notes. Desktop changes are in the
  [parallel remote handoff](2026-10-18-luma-remote.md).

## Commands run

- `npm run build`, `npm run build:mobile`: passed.
- Final `npm test`: **109/109 passed**, including19 handoff checks.
- Final `npm run test:browser`: **54/54 passed**:25 extension,7 Kodi,7 desktop
  LUMA,4 mobile capture and11 mobile UI fixtures. Receiver/CAF are mocked here.
- Existing JDK21, Android SDK35/Build Tools36 and cached Gradle9.6.1/AGP9.3.1:
  `gradle --offline --no-daemon -p android :app:assembleDebug :app:assembleDebugAndroidTest`
  passed. Gradle automatically rebuilds changed UI/capture assets.
- `node scripts/test-android-backend.mjs`: **8/8** real Android TCP/HTTP checks
  plus exact fixture-wire assertions passed; emulator5554 → Mac loopback only.
- `node scripts/test-android-ui.mjs`: **4/4** packaged UI smoke checks passed.
  Viewport320×592, zero overflow/clipped controls, native bridge only in local
  controls, attempt renewal/cancel/retry/old-cancel isolation and history revoke.
- Host-JVM native boundary checks passed against Android35 classes. Final
  whitespace, local Markdown link and sensitive-value scans.
- Public review retrieval deduplicated200 newest EN/US +100 helpful EN/US +15
  PT/BR query entries into220 unique written reviews; provenance and limits are
  in [findings](../luma-review-findings.md). Raw bodies remain ignored.

## What worked

The phone workflow is implemented and passes fixtures: all declared tracks,
observed selected/enabled PT-BR, safe queued-write cancellation and retry,
manual receiver controls while browsing another page. Layout checks cover
360×640,390×844, keyboard-sized360×400 and actual packaged320×592. The APK
runtime needs no Mac or phone-hosted media relay.

Debug APK `android/app/build/outputs/apk/debug/app-debug.apk`:966530bytes,
SHA-256 `771d2204d6e082c2018e42ca9cb271d6af292e11a6b660f56bd860007ae5d280`.
Only the emulator was installed, never a physical phone/projector.
Ignored images: `.local/mobile-ui-smoke.png` (actual emulator startup),
`.local/mobile-remote-preview.png` (390×844 mocked handoff preview),
`.local/luma-remote-connected.png` (actual desktop receiver/volume status).

Review themes include87 startup/crash,31 connection and11 navigation/lag reports.
These overlapping counts are not random-sample estimates, usage telemetry or
diagnosed official-app causes. Implemented bounded discovery/manual fallback,
recoverable state, drafts and input gating do not establish repaired firmware
or official-app crashes. Primary placement follows the household's workflow.

## What failed

Restricted execution could not bind fixture sockets; normal approved local-test
permissions resolved it. A desktop browser test read the previous error because
its error attribute was already true; it now awaits the new bounded message.
Final browser coverage passes.

JS cancellation alone could leave native writes authorized; per-attempt tokens
now revoke queued mutations. A cancelled subtitle Add can be accepted or unsent;
only explicit pre-write proof permits reissue, while unknown outcomes retain
index reconciliation. Regressions pass. AGP retained one configured test runner;
the harness dispatches its UI mode through that registration, without changing
production behavior.

## Remaining questions

Actual Samsung Android/WebView and LUMA model/Android remain unknown. Test live
phone capture, native household-network discovery/reconnect, Kodi closed →
launch/readiness → picture/sound → all-track import → visible matching PT-BR →
combined remote. Then switch the phone fully off for several segment/buffer
boundaries and reconnect without restarting video. Existing Mac-controlled
projector picture/sound/PT-BR evidence is separate.

HLS rapid-seek stop, source expiry, physical volume/text, touchpad/scrolling,
screen preview, PIN pairing, boot/default launcher, generic Cast and other-site
support remain unresolved/unimplemented. See [phone app](../phone-app.md).
No active URLs, credentials, raw reviews, third-party APK/JAR, generated APKs or
unrelated empty `headers.txt` were added to Git. No push/deployment.

## Suggested next prompt

“Install the debug APK on my Samsung, test live frameXTV Cast to LUMA from Kodi
closed through all subtitles and the combined remote, then power the phone off
and verify playback/reconnection. Diagnose actual failures and keep active URLs
and credentials private. Test volume/text and identify LUMA Android before
touchpad or reversible Kodi boot launch.”
