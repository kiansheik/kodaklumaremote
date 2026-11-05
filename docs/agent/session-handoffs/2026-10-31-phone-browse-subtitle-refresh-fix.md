# Phone Browse and subtitle refresh fix

## Goal

Diagnose the live phone `net::ERR_FAILED`, stop the Philips refresh loop that
made subtitle selection unusable, restore reliable post-cast caption delivery,
and install the prepared receiver update.

## Files inspected

- `docs/agent/{index,current-state,repo-map,open-questions}.md`
- `docs/agent/anti-debugging.md`, `docs/releasing.md`
- `android/app/src/main/java/dev/framextv/lumaremote/{MainActivity,ConnectionService}.java`
- `android/app/src/androidTest/java/dev/framextv/lumaremote/UiSmokeInstrumentation.java`
- `src/mobile/app.js`, `src/handoff/coordinator.mjs`
- Relevant mobile/handoff browser and Node tests
- Current public main bundle and live receiver/catalog state (no active media or
  subtitle URLs retained here)

## Files changed

- `android/app/build.gradle`
- `android/app/src/main/java/dev/framextv/lumaremote/MainActivity.java`
- `android/app/src/androidTest/java/dev/framextv/lumaremote/UiSmokeInstrumentation.java`
- `src/mobile/app.js`
- `src/handoff/coordinator.mjs`
- `tests/handoff.test.mjs`
- `tests/browser/mobile.spec.js`
- `tests/browser/mobile-receivers.spec.js`
- `release-notes/0.4.31.txt`
- `docs/agent/{current-state,open-questions,log}.md`
- This handoff

## Commands run

- HTTPS HEAD probes for the public page, watch route and API
- `node scripts/recon-browser.mjs` against the public watch flow
- Live receiver info/status/subtitle catalog and caption parser checks
- `node --check src/mobile/app.js`
- `node --check src/handoff/coordinator.mjs`
- `npm run build`
- `node --test tests/handoff.test.mjs`
- Focused Playwright regressions for local/public LUMA and Philips subtitles
- `node scripts/build-latest-apks.mjs`
- `aapt2 dump badging` and `apksigner verify --print-certs` for both APKs
- `node scripts/receiver-dev.mjs deploy --no-build --apk ... --host ...`
- `node scripts/receiver-dev.mjs info --host ...`
- Wireless `adb pair`, `adb install -r`, package-manager version inspection and
  a post-install physical screen capture

## What worked

- The live Browser failure was reproduced after successful network loads. The
  shipped site bundle configured both anti-debug detection actions to navigate
  to `about:blank`, separating this issue from the already repaired DNS problem.
- The phone guard is narrow: exact top-level blank replacement after HTTPS is
  stopped; initial/child blank and supported HTTPS navigation are unchanged.
- Repeated equivalent service snapshots preserve subtitle button identity and a
  tap emits one command. The redundant Philips poll no longer runs.
- Public captions take the phone preparation/delivery path again, while exact
  same-origin phone-local subtitles retain direct LAN delivery.
- Focused handoff tests passed 82/82, focused browser regressions passed 4/4,
  the site built, both signed APKs verified, and receiver 0.3.12/code26 installed
  successfully on the physical projector.
- Wireless ADB was re-paired and phone 0.4.31/code41 installed successfully with
  app data preserved. Package-manager inspection confirms the version and the
  foreground LUMA Library renders normally after installation. The temporary
  private-LAN APK server was stopped.

## What failed

- A broader Node run hung for four minutes in `luma-controller` server teardown
  and was stopped; do not infer a full-suite result from the focused passes.
- The public auto-update feed was not changed at this checkpoint.

## Remaining questions

- Does installed phone 0.4.31 keep the exact watch page loaded and allow a
  normal Cast?
- Does a public subtitle selected after cast render on the physical projector?
- Does the Philips sheet remain responsive through repeated service snapshots?
- Does the new disk cache grow while paused and clear on Stop in a fresh local
  playback session?

## Suggested next prompt

Live-test the same Browse route on installed phone 0.4.31, then select one public
subtitle after cast and confirm it remains responsive and visible on projector
0.3.12.
