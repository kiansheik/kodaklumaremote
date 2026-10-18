# Dual receiver and Philips Media Station X

## Goal

Keep LUMA/Kodi and Philips/MSX connected together, switch their phone remotes
cleanly without scrolling or interrupting playback, and prepare a real Philips
stream/subtitle test. User confirmed the TV app is **Media Station X**.

## Files inspected

Required agent index/current-state/repo-map/open-questions and AGENTS; phone,
Philips, receiver and release notes; mobile UI/coordinator/capture; native
Activity/service/backend/settings and instrumentation; mobile build, release,
Pages and test runners. Official MSX startup/plugin API and Titan catalog.
Local signing configuration was read privately without printing values.

## Files changed

- `src/mobile/{app.js,index.html}`: compact per-device indicators/selector,
  independent remote/catalog state, saved-target restoration, MSX setup/approval
  and guarded handoff/controls. Failed subtitle Opens adopt playing media and
  retry language only. Narrow screens retain no-scroll controls.
- `src/msx/`: TV-owned direct-source native-HLS/HLS.js player, subtitle parsing
  and timed text, lazy catalog, MSX plugin bridge and independent command/state
  channel. Losing the phone does not intentionally unload or pause the player.
- `MsxServer.java`: private-interface/peer/capability-pinned asset and control
  listener, bounded HTTP, explicit approval and persisted pairing, monotonic
  session/command receipts, guarded prewrite cancellation and late ACK handling.
- `ConnectionService.java`, `MainActivity.java`: separate TV executor/heartbeat,
  lifecycle restoration, per-target handoff authorization, selected-system media
  controls, browser Cast label and local player pause after TV readiness.
- Mobile build, pinned HLS.js1.7.3/package lock and license packaging;
  Android0.3.0/code7 and isolated debug fixture application ID.
- Receiver Node/browser and native MSX/dual-service tests, dispatcher and scoped
  runner package validation; Android README, agent domain/map/index/questions,
  current state/log and this handoff; `release-notes/0.3.0.txt`.

## Commands run and results

- `npm install --save-exact hls.js@1.7.3 --cache /tmp/luma-npm-cache`.
- `npm run build`; `npm test`:154 passed after enabling loopback fixture sockets.
- Full `npm run test:browser`:84 passed. Final mobile UI suites:41 passed,
  including9 dual-device cases. Final extra TV instruction: targeted narrow
  setup/approval/remote case passed. Current86 browser cases covered across runs.
- Cached Gradle9.6.1/JDK21/SDK35: isolated debug/test APKs and lintDebug passed.
  `ANDROID_TEST_PACKAGE=dev.framextv.lumaremote.receiverfixture` scopes emulator
  runs. Final35 checks passed: backend9, service5, settings5, packaged UI5,
  MSX7 and dual-service4. The old LUMA service fixture now selects LUMA explicitly
  so a preceding test's saved Philips target cannot redirect its media card.
- Production-signed release build/lintVital and certificate/metadata generation
  passed. APK963,544bytes, SHA-256
  `cb2307a077750314ecfe0776e327aacf38e5cbf5a6c8153126bb023e80b26c12`.
- Actual Samsung: existing read-only signer/decryption instrumentation passed
  before and after `adb install -r`; saved Kodi login remains decryptable. Opened
  installed0.3.0 and asked user to complete MSX setup/approval on the TV.
- `git diff --check`; source review; viewed 320×592 LUMA/Philips screenshots.
- Source committed/pushed as72a600e1529465255cee75bfc1b73cc3cc8cf23d.
  `npm run publish:android -- --directory .local/releases/v0.3.0` published the
  signed APK/update.json and deployed `gh-pages`. Release API reports exact
  artifact size/digest; Pages590e0b29cf14e1d10eb6bd6c63dfd6b6a71a8a4e is built.
  HTTPS page/feed returned200, feed matches the local manifest, and the public
  GitHub/CDN APK returned200 with the same SHA-256/963,544bytes. Python's initial
  standalone CA configuration failed; verification succeeded with the Mac's
  system CA bundle, without disabling certificate verification.

## What worked

Fixtures establish simultaneous native transports, responsive LUMA and read-only
TV status during a pending Open, independent disconnect, target-aware media
controls, stale notification/source protection, pairing approval, single command
delivery/late receipts and restored sessions without replay. Player fixtures
establish PT-BR then English selection, direct TV source/subtitle ownership,
SRT/WebVTT conversion, lazy language selection and retained playback/captions
during phone control failure. Existing Kodi login storage is unchanged.

## What failed and how it was handled

- Initial sandbox Node/browser fixture sockets were denied; scoped local test
  escalation succeeded.
- Lint found Map.getOrDefault unavailable on API23; replaced with null fallback.
- Existing emulator production signer rejected a debug APK. No uninstall/reset
  was done; isolated `.receiverfixture` package preserves that installed app.
- Early fixture assertions were corrected for Android-normalized traversal URLs
  and the existing LUMA pairing frame syntax; application checks then passed.
- A release-signing lookup initially selected the sibling's debug block. It
  failed before APK generation; scoped lookup to the existing release block
  succeeded. Password values were never printed or written into source.
- Review repaired lost-ACK retry, slow-start heartbeat, stale source guards,
  startup foreground race, independent disconnect and target-specific tokens.

## Remaining questions / physical acceptance

The Philips must accept the phone's HTTP MSX startup/plugin URL. HTTPS-launched
MSX can impose mixed-content/protocol-upgrade rules. Actual picture/sound,
source CORS/codecs, PT-BR subtitle rendering, TV remote overlay behavior and
phone fully off beyond buffering boundaries remain unconfirmed. Player volume
controls media gain, not the TV's hardware speaker level. Reset pairing/re-enter
the displayed address when network/phone address changes invalidate TV setup.
The upstream MSX SDK is loaded at runtime, not redistributed under an assumed
license. Existing LUMA cold-Kodi crash/subtitle issue is not claimed fixed.

## Suggested next prompt

“In the updated app select Philips → Set up TV. In Media Station X open
Settings → Start Parameter → Setup, enter the displayed IP:port, open Connect
this TV and allow it on the phone. Test one episode with Portuguese subtitles,
then switch between LUMA and Philips without stopping playback.”
