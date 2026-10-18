# MSX setup compatibility and Philips Matter assessment

Historical0.3.1 setup record. Subsequent Matter commissioning, hardware proof
and native0.4.0 implementation are in the
[native phone handoff](2026-10-18-native-matter-phone.md). MSX status0 remains unresolved.

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
- Source056b6c94a1c605a3d19d92ba0932d16ceb9ee5e4 committed/pushed.
  npm run publish:android -- --directory .local/releases/v0.3.1 published the
  signed APK/update.json and deployed Pages commitce82fa2159fc56ae31a09469b430ce2a477f9003.
  Release API digest/965,037-byte asset match the prepared APK. First HTTPS feed
  read still served0.3.0 while Pages was building; after build completion the
  HTTPS install page/feed returned200, feed matched exact0.3.1 metadata, and
  public APK/CDN returned200 with matching965,037bytes and SHA-256.
- Safe own-process MsxReceiver log read after install had no setup events yet.
  User was asked to retry Restart TV setup with the displayed address/open lock;
  user reports still status0. Safe log then had one REQUEST_REJECTED400 with no
  origin. This can be an empty TCP probe and cannot establish TV HTTP arrival.
- Temporary private-LAN Mac diagnostic listener served only nonsecret MSX
  start/home connection-check JSON. User also reports status0; it recorded one
  zero-byte TCP read timeout and no HTTP/TLS. No raw headers or media were saved.
  Separate temporary HTTP browser-check page prepared; user asked to open it in
  the TV's Internet/Web Browser app. User declined further physical steps because
  the TV is outdoors in sun and explicitly redirected work to Matter automation.
  Stopped both own diagnostic listener processes. No direct-browser result.
- Read-only refresh of the known Matter service still observed commissioning
  mode1/DT40. A separate pinned Mac Matter controller is being prepared before
  requesting the TV's setup code. Pairing/control had not occurred at this
  diagnostic stage; subsequent actual Mac pairing, Home/volume readback and
  additional-controller window succeeded, as recorded in the newer handoff.

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

The physical0.3.0 and0.3.1 MSX setups returned status0. A reproduced OPTIONS
rejection is fixed, but is not proven to be the TV's request. Neither diagnostic
has established a TV HTTP request. MSX/TV policy or connectivity needs a direct
browser check; do not treat the generic400 as proof to loosen parsing. No actual Philips
playback/subtitles have passed. Matter commands subsequently passed separately;
they do not resolve MSX. No failed tests remain in the focused validation above.

## Remaining questions

- Honor the user's request to automate TV work through Matter and avoid repeated
  outdoor manual tests. Direct browser access remains untested. If top-level
  HTTP is later established, a standalone receiver might avoid MSX restrictions;
  this remains a hypothesis. Do not suggest secure=0 as an HTTPS-upgrade bypass.
- Actual Connect this TV/approval, source playback, PT-BR captions and phone-off
  continuation remain unverified. Original Kodi cold-start/subtitle issue remains.
- Mac Matter commissioning/inventory and supported Home/volume commands now
  passed. Native0.4.0 phone pairing/speaker changes and its TV-accepted sharing
  window also passed. Second-phone enrollment, navigation/camera and power/input
  acceptance remain pending.
  External subtitle URL loading is not established by the TV's Matter inventory.

## Suggested next prompt

“Complete native phone Matter acceptance using the already-opened additional
controller window, preserve both receivers/settings and avoid repeated outdoor
MSX setup steps. Continue media receiver work separately from hardware controls.”
