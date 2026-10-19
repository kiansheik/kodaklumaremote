# frameXTV capture domain hotfix

## Goal

Fix Cast refusing the default episode with “Video capture currently supports
frameXTV HTTPS pages” after the user installed0.4.3. Preserve the video-first,
manual-subtitle and coalesced-volume behavior.

## Files inspected

Required agent docs, phone-app, Android README and release instructions;
MainActivity browser navigation/capture/bridge, capture.js and shared extraction,
mobile app/bundler, Android manifest/version, UI instrumentation/runner and mobile
capture/browser fixtures. APK capture assets and the connected phone's visible
address bar were inspected; full UI output stays ignored locally.

## Files changed

- MainActivity: exact HTTPS `.cc` plus legacy `.xyz`, canonical starting page;
  origin errors separated from asset/evaluation failures.
- Android manifest and mobile default: `.cc` VIEW links and starting URL.
- UiSmokeInstrumentation and native runner:7 checks including actual packaged
  capture at `.cc` after an intercepted `.xyz` page changes location, exact-origin
  boundaries, declared tracks, native bridge isolation and navigation revocation.
- Android version0.4.4/code13, release notes and agent/Android documentation.

## Commands run

- Targeted `rg`/`sed`, git status/diff/whitespace, JavaScript syntax check.
- Bounded HTTPS HEAD with redirects: original episode link ends at HTTPS `.cc`,
  status200. APK ZIP inspection: capture.js is identical9,395bytes in0.4.2/0.4.3.
- ADB device discovery and read-only UI dump: connected Samsung shows HTTPS
  `.cc` on the default episode with Cast visible. No projector command sent.
- Focused mobile capture/browsing/startup Playwright fixtures:8 passed.
- Isolated fixture Gradle `assembleDebug assembleDebugAndroidTest lintDebug`
  passed. Native packaged UI7 passed, including actual production capture,
  intercepted `.xyz`→`.cc` page change, both direct domains, source tokens,
  declared tracks, bridge isolation and no-scroll320×592 layout.
-97 local documentation links and whitespace checks passed.
- Committed-source release build through the ignored local signing loader;
  production certificate, lintVital and16KB ZIP alignment passed. Source/tag
  target`743b890e4d657b2154b1461541ebb488c3be99c7`.
- `git push origin main`; `npm run publish:android -- --directory
  .local/releases/v0.4.4`: publicv0.4.4/code13. Pages commit
  `6587626102ac3f9d4226e2958c2954a5e99c74db` built/no error.
- Anonymous HTTPS feed/APK GET200, served metadata equals prepared, downloaded
  APK9,648,904bytes, SHA-256
  `2b8ed48a21d05d170c99075f66f5f505b80a1df1ba2758735ef923d38f380c42`,
  production signer matches. Chrome unavailable for optional page-render check.
- Explicit connected-Samsung `adb install -r` plus launch; package inspection
  reports0.4.4/code13. Read local screenshot, tapped the visible native Cast
  button once on the default page, then inspected screenshot/sanitized native
  log. It captured22 HLS tracks and reached launching→waiting→opening→remote→ready;
  service accepted metadata and Remote showed Kodi-reported Playing/22:35.
  No extra subtitle, volume, navigation, power or Stop commands were sent.

## What worked

The failure is before media extraction: the previous exact-host guard accepted
only `.xyz`, while the live site redirects to `.cc`. Both remain HTTPS. Capture
now accepts those two exact origins with default/443 ports and no credentials;
unrelated hosts and arbitrary subdomains remain rejected. Script loading and
evaluation errors no longer claim the website failed an HTTPS requirement.
The signed update is installed on the actual Samsung, with saved LUMA setup
preserved. The default live page now passes capture and completes the cast.
This is native/phone/Kodi-state evidence; physical projector output was not read.

## What failed

Earlier UI smoke checks injected capture metadata and missed the native hostname
gate. The first phone dump to `/dev/tty` returned only a status line; a normal
read-only hierarchy dump confirmed the actual address. A later hierarchy had no
text, so a current screenshot supplied control coordinates and visible state.
Chrome was unavailable for install-page rendering; public feed and APK were
verified via normal HTTPS without trust changes. This issue does not
diagnose the earlier physical Kodi startup crash or subtitle rendering.

## Remaining questions

Live default-episode capture is verified on the connected phone. Cold-start Kodi
stability and visible manual subtitle rendering still need separate
physical acceptance; this hotfix does not change those playback paths.

## Suggested next prompt

Update Julia's app to0.4.4 through Settings → Check for updates. The Samsung is
already updated and left in Remote after a successful cast. Test manual subtitle
selection once receiver playback starts; report any different failure with the
address-bar hostname and exact episode.
