# Per-title brightness memory and gradual bounds (2026-10-23)

## Goal

After the user confirmed the SurfaceView brightness overlay is physically
perfect, remember brightness per movie or complete series, restore it on return,
and make the control gradual with −30%/+30% limits.

## Files inspected

- Required agent index/current-state/repo-map/open-questions pages.
- Mobile cast, title identity, caption-preference and brightness paths in
  `src/mobile/app.js` and both mobile browser fixtures.
- Native WebView storage bridge in phone `MainActivity.java`.
- LUMA display validation/forwarding in `DisplaySettings.java`,
  `ControlServer.java` and phone `NativeBackend.java`.
- Philips display validation/state in `MsxServer.java` and
  `src/msx/receiver.js`; receiver documentation and 0.4.12 notes.

## Files changed

- `src/mobile/app.js`: native-backed `picturePreferences`, movie/series title
  keys, 2% stepping, ±30% bounds, neutral default, and guarded restore after
  casts to either screen.
- Phone `MainActivity.java`: load/save bounded `app.picturePreferences` beside
  caption preferences and seek history.
- Receiver display path: enforce ±30% and atomically check optional source and
  content guards before a write.
- Philips display path: enforce factor 0.7…1.3 and admit the existing source
  guard on display commands.
- Browser regression coverage, receiver README, release notes and agent pages.

## Commands run

```sh
node --check src/mobile/app.js
node --check src/msx/receiver.js
./node_modules/.bin/playwright test tests/browser/mobile.spec.js -g 'brightness uses'
./node_modules/.bin/playwright test tests/browser/mobile.spec.js tests/browser/mobile-receivers.spec.js
./node_modules/.bin/playwright test tests/browser/mobile-receivers.spec.js -g 'Philips brightness'
npm test
/Users/kian/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline -p android-receiver :app:testDebugUnitTest :app:assembleDebug
/Users/kian/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline -p android :app:assembleDebug :app:assembleDebugAndroidTest
adb -s RQGL105J70A install -r android/app/build/outputs/apk/release/app-release.apk
node scripts/receiver-dev.mjs deploy
node scripts/receiver-dev.mjs info
node scripts/receiver-dev.mjs call display.get '{}'
git diff --check
```

## What worked

- Focused LUMA regression proves +24% restoration, 2% stepping, both ±30%
  stops, the same saved value on another episode, and neutral on an unseen movie.
- Focused Philips regression proves the same series key and a guarded 1.02
  display factor on the next episode.
- Combined mobile browser suites passed 97/97 before adding the extra focused
  Philips case; that new case passes independently. Node passed 247/247.
- Receiver unit/debug assembly and phone debug/AndroidTest assembly passed.
- Production-signed phone 0.4.11/code 21 (9,822,418 bytes, SHA-256
  `38f02fdf08d65c68e899a46d8efa5a8a27a30240926fa377cde6aa43f150eb56`)
  installed successfully over USB with app data retained.
- Production-signed receiver 0.3.2/code 16 (1,134,598 bytes, SHA-256
  `b45d3e642c988d8eac256e8813fa1613308e50fd0f7577c4929cde7869ac2fdb`)
  installed at 2026-10-23T13:03:09.817Z and returned healthy with brightness 0.
- No commit, push or public release was performed. The installed phone package
  retains the source version 0.4.11/code 21; these notes remain prepared for the
  next 0.4.12 release workflow.

## What failed

- The first sandboxed Playwright attempt could not bind loopback port 4173;
  rerunning with the required local-process permission passed.

## Remaining questions

- Verify a value chosen on one episode returns on a different episode of that
  series and a new movie starts at 0% on the installed devices.

## Suggested next prompt

"I set one episode to +15%, opened another episode and a different movie; record
whether the series restored +15% and the movie started at 0%."
