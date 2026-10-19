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

## What worked

The failure is before media extraction: the previous exact-host guard accepted
only `.xyz`, while the live site redirects to `.cc`. Both remain HTTPS. Capture
now accepts those two exact origins with default/443 ports and no credentials;
unrelated hosts and arbitrary subdomains remain rejected. Script loading and
evaluation errors no longer claim the website failed an HTTPS requirement.

## What failed

Earlier UI smoke checks injected capture metadata and missed the native hostname
gate. The first phone dump to `/dev/tty` returned only a status line; a normal
read-only hierarchy dump confirmed the actual address. This issue does not
diagnose the earlier physical Kodi startup crash or subtitle rendering.

## Remaining questions

Does the signed hotfix capture the live default episode on the connected phone?
First-start Kodi stability and manual subtitle rendering still need separate
physical acceptance; this hotfix does not change those playback paths.

## Suggested next prompt

Update to0.4.4, open the default episode and tap Cast. Confirm it passes capture,
then test manual subtitle selection once receiver playback starts.
