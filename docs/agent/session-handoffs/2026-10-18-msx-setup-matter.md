# MSX setup compatibility and Philips Matter assessment

## Goal

Diagnose the physical TV's MSX status0 failure, install a narrowly scoped repair
with useful diagnostics, and assess Matter as a Philips remote-control path.

## Files inspected

Agent index/current-state/repo-map/open-questions, MSX/phone/release domain notes,
native MsxServer/instrumentation, mobile setup UI/browser fixtures, build/test/
publication helpers and Android version config. Official Philips datasheet,
MSX setup/error docs and Matter TV/controller/cluster sources. Existing signing
configuration was read privately; no credentials were printed or added to source.

## Files changed

- MsxServer.java and MsxInstrumentation.java: restricted bootstrap/home OPTIONS,
  safe error CORS, TLS/fixed-reason diagnostics and actual WebView preflight proof.
- src/mobile/{app.js,index.html}, mobile-receivers.spec.js: allowlisted setup
  hints, padlock instruction, privacy and320×592 no-scroll coverage.
- Android0.3.1/code8, MSX test runner8-count and release-notes/0.3.1.txt.
- Agent MSX/Matter domain notes, index/map/questions, current-state/log and this
  handoff. Original untracked headers.txt remains untouched and excluded.

## Commands run

- Targeted rg/read/git diff and memory registry lookup (no relevant hits).
- Read-only ADB service/listener checks and curl root/OPTIONS on the actual phone.
  Root404 established reachability; old OPTIONS400 reproduced the compatibility
  gap. Mac did not GET bootstrap, avoiding a pending-peer claim before the TV.
- Isolated Gradle assembleDebug/assembleDebugAndroidTest/lintDebug: passed.
- Native MSX runner:8/8, including real WebView CORS and no preflight peer claim;
  dual-receiver service runner:4/4.
- node --test tests/msx-receiver.test.mjs tests/release-manifest.test.mjs:22/22.
- Playwright mobile.spec.js+mobile-receivers.spec.js:43/43.
- Production signing helper with existing local release key: release/lintVital
  passed.0.3.1 APK965,037bytes, SHA-256
  89630f78e8fb41194aa9a3fb82513297e350a0589fad15f769b2e4b300342cec.
- Actual-phone read-only migration-check before/after adb install -r: passed,
  production signer correct and existing Kodi login decryptable. Opened0.3.1.
- Bounded read-only DNS-SD Matter browse/resolve: observed PHILIPS TV with
  DT40/0x0028. Private discovery details remain ignored local evidence.

## What worked

Browser preflight support is verified in an Android WebView. It returns no setup
capability and cannot claim or approve a TV. Fixed diagnostics distinguish TLS,
origin rejection, pairing expiry and accepted bootstrap without logging request
targets, credentials or media. Existing dual-service/mobile regressions passed.
The signed update preserved actual-phone settings.

The exact Philips model advertises Matter support and a Basic Video Player was
observed locally. This supports investigating a native hardware remote alongside
the existing MSX media/subtitle receiver.

## What failed

The physical0.3.0 MSX setup returned status0. A reproduced OPTIONS rejection is
fixed, but it is not proven to be the TV's request. HTTPS upgrade/TLS mismatch or
another bootstrap rejection still needs the0.3.1 diagnostics. No actual Philips
playback/subtitles or Matter commands have passed. No failed tests remain in
the focused validation above.

## Remaining questions

- Retry TV setup on0.3.1; inspect safe MsxReceiver logs/fixed phone hint. Is the
  padlock open and does a request arrive as HTTP? HTTPS-only MSX needs another
  entry path; do not suggest secure=0 as an HTTPS-upgrade bypass.
- Actual Connect this TV/approval, source playback, PT-BR captions and phone-off
  continuation remain unverified. Original Kodi cold-start/subtitle issue remains.
- Matter requires a controller/commissioning path before requesting an expiring
  setup code. Then inspect Descriptor/FeatureMap/AcceptedCommandList and show
  supported controls only. External subtitle URL loading is not established.

## Suggested next prompt

“Retry Philips → Restart TV setup using the displayed address and an open MSX
padlock. Report the TV result and phone hint so we can inspect the safe native
diagnostic. Next, prepare a Matter commissioner and read the paired TV's actual
capabilities before implementing hardware controls.”
