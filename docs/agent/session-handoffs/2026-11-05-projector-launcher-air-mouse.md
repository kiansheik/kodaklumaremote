# Projector launcher and phone air mouse

## Goal

Make every launchable projector app easy to reach from Home and the remotes,
then add an explicitly calibrated phone-orientation pointer whose normal OK
button clicks the cursor.

## Files inspected

- Receiver `MainActivity`, `ReceiverApp`, `ControlServer` and launcher resources.
- Phone `MainActivity`, `ConnectionService`, `NativeBackend` and sensor code.
- `scripts/luma-controller.mjs`, `scripts/test-android-backend.mjs`.
- `src/luma/remote.html`, `src/mobile/index.html`, `src/mobile/app.js`.
- Launcher/backend/browser tests and archived Kodak APK methods for app
  inventory, display bounds and `GYROSCOPEEVENT`.

## Files changed

- Receiver launcher/catalog/API, layout, manifest, version and unit tests.
- Laptop app catalog/icon UI, controller route and browser/Node tests.
- `AirMouseFilter.java`, `AirMouseController.java`, phone Activity/backend RPCs,
  mobile controls and backend/browser tests.
- Current-state, repository-map, open-question and implementation docs.

## Commands run

- Host Java 17 compile/run for `AirMouseFilterTest`: passed.
- Host Java 17 compile/run for `NativeBackendValidationTest`: passed.
- `./node_modules/.bin/playwright test tests/browser/mobile.spec.js`: 66 passed.
- Focused Air-mouse browser tests: 2 passed.
- Launcher controller/manifest Node tests: 10 passed outside the sandbox.
- Gradle debug main/AndroidTest compilation and signed release assembly: passed.
- `adb install -r` of the phone-only production-signed APK: passed.
- `git diff --check`: passed.
- Launcher catalog/controller/browser checks passed during that slice; receiver
  0.3.14/code28 was then installed and queried live.

## What worked

- Live inventory contains 28 launchable apps, including Crunchyroll. The Home
  logo grid and authenticated API use the complete Android catalog; the laptop
  remote renders icons and launches packages from it.
- Static APK analysis established display-bound lookup and exact hover/down/
  drag/up frames. The installed phone calibrates a stable neutral pose, filters
  relative fused orientation, coalesces hover sends and routes OK to down/up.
- Lifecycle invalidation stops the sensor on pause, Browse, rotation, target
  changes, native disconnect and teardown, with no implicit resume.
- Pointer traffic stays serialized and one-in-flight without publishing full UI
  state per frame. OK down/up is one atomic socket write.
- Phone package manager confirms installed 0.4.33/code43. The signing certificate
  matches the checked-in production fingerprint, and the Samsung reports Game
  Rotation Vector, Rotation Vector, gyro and accelerometer sensors.

## What failed or remains unverified

- Two broad Node-suite attempts were stopped after duplicate processes plus a
  sandbox-blocked localhost fixture left `luma-controller` waiting. Its focused
  tests passed 10/10 when rerun with localhost access; no new complete Node-suite
  claim is made.
- The phone was locked during the post-install UI check, so the package/process
  and sensor inventory are verified but the new controls and physical movement
  were not exercised on the live screen. No public release was published.
- Static protocol evidence and automated tests do not establish physical axis
  direction, jitter, sensitivity, edge travel or app-specific click behavior.
- Default-Home selection and cold-boot return need another projector power-cycle
  check. Universal Google Cast receiver behavior is not supplied by this
  sideloaded launcher.

## Remaining questions

- Does the Samsung's natural portrait grip need either axis inverted or another
  sensitivity preset?
- Can one calibration traverse every projector edge without losing fine control,
  and does OK click in both the launcher and Crunchyroll?
- Do pause, Browse, display rotation, disconnect and target switching stop all
  pointer traffic on the live devices?

## Suggested next prompt

Unlock the installed phone, connect it to the projector, then run a short
calibration, edge-travel, OK-click and lifecycle test on the Samsung and Kodak
projector. Report whether either axis or the sensitivity needs tuning.
