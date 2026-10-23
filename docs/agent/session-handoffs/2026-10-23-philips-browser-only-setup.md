# Philips browser-only receiver setup

## Goal

Make Philips receiver setup honest when the receiver is unavailable, provide a
single action that types either the canonical launcher or a direct phone backup
into the selected TV URL field, submits it with the phone controls, simplifies
first-run addressing on the fixed page, and removes Media Station X from the visible app.

## Files inspected

- `docs/agent/{index.md,current-state.md,repo-map.md,open-questions.md,log.md}`
- `src/mobile/{index.html,app.js}`
- `src/msx/{README.md,receiver.js}` and `site/tv.html`
- `android/app/src/main/java/dev/framextv/lumaremote/{ConnectionService.java,MsxServer.java,PhilipsTextController.java}`
- Related browser and Android instrumentation tests

## Files changed

- Mobile setup UI and behavior in `src/mobile/{index.html,app.js}`
- Native fixed-address validation/typing and browser route in
  `PhilipsTextController.java`, `ConnectionService.java`, and `MsxServer.java`
- Stable/direct redirect paths in `site/tv.html` and `src/msx/receiver.js`
- Browser and Android instrumentation coverage
- Receiver README, release notes, and agent documentation

## Commands run

- `node --check src/mobile/app.js` and `node --check src/msx/receiver.js`
- `node --test tests/msx-receiver.test.mjs tests/tv-hello-server.test.mjs`
- `caffeinate -i npm test`
- `caffeinate -i npm run test:browser`
- `caffeinate -i npm run check`
- Gradle offline `:app:compileDebugJavaWithJavac` and
  `:app:compileDebugAndroidTestJavaWithJavac`
- `adb devices -l`
- Signed offline `:app:assembleRelease`, `adb install -r`, package inspection,
  and cold `MainActivity` launch on the USB-connected Samsung
- `caffeinate -i make deploy-gh-pages`, GitHub Pages build status/API checks,
  live custom-domain fetches, then 0.4.13 wireless-ADB install and cold launch
- Targeted `rg`, `sed`, `git diff`, and `git status` inspection

## What worked

- Node passed 247/247; full browser passed 158/158; the new launcher/setup group
  passed 8/8; receiver-focused Node passed 61/61; release/deploy passed 15/15.
- Android app and instrumentation Java sources compile.
- Signed 0.4.12/code 22 APK installed in place successfully and cold-launched.
  It is 9,821,230 bytes with SHA-256
  `aead10c90f1d1a76bb4d29e2e9ff3281804dc16d17238a6cef9631ea817224bf`.
- Guarded release published 0.4.13/code 23 from source `6e4ca1e`; Pages commit
  `6a20f13` built successfully and the live custom domain serves the new TV UI
  and manifest. The signed APK is 9,821,506 bytes with SHA-256
  `4aacc6a636412fc880e67100f526fab5710ed84b01fa7b26ed753bf59343d479`;
  it installed in place over wireless ADB and cold-launched on the Samsung.
- Disconnected action routes to setup. Stable and direct values are owned and
  validated natively; first-use text permission and Matter Enter/Go are part of
  the same action. The stable page infers the local prefix when WebRTC exposes
  it, remembers phones, auto-opens one available phone and shows multiple choices.
- The visible mobile/site UI contains no Media Station X wording.

## What failed

- Sandboxed receiver tests could not bind local sockets and sandboxed Gradle
  could not load its native library; both passed outside that restriction.
- Sandboxed ADB could not start its local server. Outside the sandbox, only the
  physical Samsung phone was connected; no emulator was available.

## Remaining questions

- Confirm on the real Philips that JointSPACE types the complete stable and
  direct addresses into the selected browser field and that Titan loads both.
- Confirm whether connected-state **Open TV receiver** can foreground the TV
  browser on this firmware; prior `activities/browser` acceptance did not visibly
  navigate, and no second network-accessible launch API was found.
- Phone 0.4.13 is installed and Pages/release deployment is live. Real Philips
  browser acceptance remains unverified.

## Suggested next prompt

Walk through stable and direct Philips browser setup on the real TV, including
automatic Matter Enter/Go and first-run subnet inference.
