# LUMA brightness black-screen fix (2026-10-23)

## Goal

Fix the physical projector turning video completely black for every nonzero
brightness value, validate the replacement with decoded video in an emulator,
and install it locally on the projector.

## Files inspected

- Required agent pages: `index.md`, `current-state.md`, `repo-map.md` and
  `open-questions.md`.
- Receiver rendering path: `DisplaySettings.java`, `MainActivity.java`,
  `PlaybackController.java`, `main.xml` and `android-receiver/app/build.gradle`.
- Phone brightness controls and browser fixture; current 0.4.12 release notes
  and the Philips/two-screen handoff; `docs/agent/luma-receiver.md`.
- Media3 `PlayerView` overlay support and the existing Android emulator/fixture
  tooling.

## Files changed

- `android-receiver/.../DisplaySettings.java`: remove the unused Media3 GL
  effects/picture-adjusted helpers and document the compatible rendering path.
- `android-receiver/.../MainActivity.java`: retain one direct SurfaceView for all
  values and composite a non-interactive white/black brightness tint inside
  `PlayerView.getOverlayFrameLayout()`.
- `release-notes/0.4.12.txt`: describe brightness without promising the failed
  TextureView implementation.
- LUMA receiver domain page, agent current state, log and this handoff.

## Commands run

```sh
/Users/kian/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline -p android-receiver :app:testDebugUnitTest :app:assembleDebug
/Users/kian/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline -p android-receiver :app:lintDebug
# API 36 AVD + receiver API: play/pause the H.264 fixture, set brightness,
# capture five screenshots, and measure centre-crop luma.
node scripts/receiver-dev.mjs deploy
node scripts/receiver-dev.mjs status
node scripts/receiver-dev.mjs info
node scripts/receiver-dev.mjs call display.get '{}'
git diff --check
```

## What worked

- Receiver Java compilation, all receiver unit tests, debug assembly and signed
  release assembly passed.
- On an API 36 ARM64/SwiftShader emulator, receiver 0.3.2 decoded the 52.209 s
  H.264/AAC Sintel fixture and paused at exactly 30.000 s in ready/active state
  with no error. Centre-crop YAVG was 54.255, 101.891, 111.662, 124.214 and
  185.659 at −60%, −10%, 0%, +10% and +60%. Every capture retained the detailed
  video frame instead of going black; brightness was reset to 0 afterwards.
- Local deployment installed receiver 0.3.2/code 16, 1,134,102 bytes, SHA-256
  prefix `4908733a0cd1`, at `2026-10-23T12:45:42.383Z`.
- The physical receiver returned on port 4191 with the expected capabilities,
  healthy idle playback, and persisted brightness 0.

## What failed

- Changing from `View.setLayerType` to `TextureView.setLayerPaint` did not fix
  the projector: the user confirmed both positive and negative nonzero values
  still blacked the picture. This isolated the incompatible TextureView path,
  which the final implementation removes entirely.
- Final `lintDebug` still reports the same 14 errors and 11 warnings already in
  the surrounding unreleased receiver work. The first is `Diagnostics.java`
  using `File.toPath` with minSdk 23; no failure points to the brightness diff.
- Emulator composition cannot reproduce or clear the Android 9/Amlogic vendor
  compositor. The installed projector was idle, so physical active-video proof
  still requires the user-visible check.

## Remaining questions

- Start a video and select +10%, 0%, then −10%. Confirm the picture stays visible
  and moves brighter/baseline/darker on the physical projector.
- The overlay intentionally approximates brightness: negative values dim and
  positive values blend toward white. Contrast and gamma remain persisted API
  fields but are not applied by this safe path and are not exposed in the phone
  UI.

## Suggested next prompt

"A video is playing on LUMA; test brightness at +10%, 0% and −10% and record
whether the physical picture stays visible and changes as expected."
