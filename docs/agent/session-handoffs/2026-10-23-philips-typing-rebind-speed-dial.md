# Philips typing rebind and speed-dial autofill

## Goal

Repair Philips URL typing when saved JointSPACE permission belongs to an older
TV network address, and simplify browser setup around one canonical speed dial
whose phone field the app can fill automatically.

## Files inspected

- Required agent index, current state, repo map and open questions
- Philips text controller, ConnectionService and Android instrumentation fixture
- Mobile receiver setup UI/logic and its browser fixtures
- Fixed `site/tv.html` launcher, launcher tests and receiver README
- Release scripts, current release notes and prior browser-setup handoff

## Files changed

- `PhilipsTextController.java` and `PhilipsTextInstrumentation.java`
- `src/mobile/index.html`, `src/mobile/app.js`, and mobile browser coverage
- `site/tv.html`, its browser coverage, and `src/msx/README.md`
- `release-notes/0.4.14.txt` and agent documentation

## Commands run

- Targeted `rg`, `sed`, `git diff`, `git status`, and `node --check`
- Focused Playwright launcher/setup runs, then `npm test` and
  `npm run test:browser`
- Offline Gradle app and AndroidTest Java compilation
- Read-only ADB package/activity/UI inspection of the installed Samsung app
- Guarded `make deploy-gh-pages`, GitHub release/Pages/live-feed verification,
  wireless `adb install -r`, package inspection and cold Activity launch

## What worked

- A different current Matter-resolved private TV address may request a fresh
  Philips PIN; same-address duplicate pairing is still rejected.
- A failed replacement grant leaves the old credential active. A successful
  grant moves readiness to the new address.
- The fixed launcher focuses its field and accepts the exact direct receiver URL
  typed by the native app, including a non-default valid port, while retaining
  IP-only entry and remembered-phone behavior.
- Node passed 247/247. Focused browser coverage passed 10/10. The pre-release
  full browser run exposed only two obsolete old-wording assertions; both
  updated cases passed, including narrow-phone fit. The guarded release then
  passed Node 247/247 and browser 161/161. Android app and AndroidTest Java
  sources compile.
- Release 0.4.14/code 24 was published from source `aec4bb4`; Pages commit
  `8e1ce499` built successfully, and the live custom-domain launcher and update
  feed expose the new behavior/version. The exact signed phone APK is 9,821,742
  bytes with SHA-256
  `02d15163f0f64128f5b43e35cd32d5619fd41e6eea68ca1e0db3f685b9be9ad1`.
  It installed in place over wireless ADB and cold-launched on the Samsung as
  0.4.14/code 24.

## What failed

- Sandboxed ADB could not start its socket and sandboxed Gradle could not load
  its native platform library; the authorized runs worked.
- No emulator is connected, so the compiled rebind instrumentation did not run.
- The real Philips has not yet established page focus, typed URL submission, or
  automatic speed-dial launch for this release.

## Remaining questions

- Does opening the sole LUMA speed dial leave the launcher's field focused on
  the physical Philips, and does phone autofill plus Matter Enter submit it?
- Can an observed phone-control sequence reliably foreground Internet and select
  the only speed dial? Earlier `activities/browser` acceptance did not prove it.
- Does the new TV PIN rebind complete after the current real address change?

## Suggested next prompt

Open the only LUMA speed dial on the Philips, then use Autofill this phone and
report whether the PIN prompt, complete URL, Go action and receiver page appear.
