# Projector NES Games — 2026-11-09

## Goal

Play the user's private NES ROM directly on the Kodak projector, use a paired
Nintendo controller throughout the receiver, and import/synchronize ROMs from
the phone. Complete local implementation and validation before the user turns
on either physical device. Preserve all earlier dirty launcher/Cat/Air Mouse work.

## Files inspected

- Required `docs/agent/{index,current-state,repo-map,open-questions}.md`, receiver
  and phone READMEs, build/release scripts and Android configs/manifests.
- Receiver `MainActivity`, `ReceiverApp`, `ControlServer`, `HttpMessage`,
  `Pairing`, `PlaybackController`, `Updater`, layout and launcher drawable.
- Phone `MainActivity`, `ConnectionService`, `NativeBackend`, mobile HTML/JS and
  native/browser fixtures. Upstream QuickNES engine and individual license notices.
- Supplied ROM header and runtime output privately; no ROM added to source/assets.

## Files changed

- Receiver: new `GameLibrary`, `GamesActivity`, `ControllerInput`,
  `ControllerState`, `NesGameView`, `NesNative`; native `NesEngine`/JNI, NDK
  build files, pinned upstream `nes_emu` tree, source hashes and notices.
- Receiver integration: `MainActivity`, `ReceiverApp`, `ControlServer`, `Updater`,
  manifest/layout, Gradle version/native ABIs, ProGuard JNI retention and license
  asset. New controller/storage tests. Root and receiver ignores protect ROMs
  and generated native build outputs.
- Phone: new `GamesLibrary`/`GamesManager`; activity import/picker/intents,
  connection-service sync/lifecycle, manifest, version, mobile Games sheet and
  Android README. New storage/sync tests and two browser tests.
- `scripts/test-{nes-core.sh,phone-games.mjs,receiver-games.mjs}` and original
  synthetic `tests/native/nes-core-smoke.cpp`.
- `docs/agent/{games,current-state,repo-map,open-questions,log}.md` and this handoff.

## Commands run

- Targeted `rg`, `sed`, `git status --short`, `git diff --check`; no commit/push.
- Native NDK build for armeabi-v7a, arm64-v8a and x86_64; native symbol/alignment
  inspection; `scripts/test-nes-core.sh` with synthetic ROM and supplied private
  ROM, plus AddressSanitizer build/run.
- Receiver Gradle `:app:testDebugUnitTest :app:assembleRelease :app:assembleDebug`
  using existing signing configuration without printing secrets. Final XML-only
  button-height change was rebuilt and visually checked; native code unchanged.
- `node scripts/test-phone-games.mjs`; phone Gradle release/debug/AndroidTest
  builds; `npx playwright test tests/browser/mobile.spec.js` (70 tests) and focused
  Games rerun (2 tests) after CSS-only compact-row correction.
- `node --test tests/receiver-manifest.test.mjs`; APK signer, ABI/license/content
  inspection and `shasum -a 256`.
- Isolated Android 36 ARM64 AVD created in ignored `.local/android-games/`,
  launched read-only without snapshots/audio/window on `emulator-5582`.
  Only this explicit emulator serial received APK installs/ADB input.
- `node scripts/test-receiver-games.mjs --rom /private/path/game.nes` with
  release APK: PIN pairing, auth denial, upload/download, runtime/menu/saves and
  restart. Private screen captures inspected with image viewer.
- Phone signed APK: actual document picker → Downloads → ROM → offline library;
  upgraded in place to final CSS build and retained imported game. Paired phone
  to actual receiver APK, then observed **Phone + projector** / both saved.
  A loopback-only EShare control fixture enabled the existing projector-connect
  step; ROM sync and pairing used the real receiver API. This is not real EShare,
  Wi-Fi/Bluetooth or projector acceptance. The isolated emulator and control
  fixture were stopped and API forwarding removed after validation.

## What worked

- Receiver 65/65 JVM tests; phone 70/70 browser tests plus focused 2/2 after CSS;
  phone storage and real HTTP loopback transfer tests all pass. Receiver manifest
  check and final diff check pass. Both signed APKs build with existing identity.
- Supplied Mario Tupi ROM: 3,600 native frames, 2,875,898 audio samples with
  nonzero peak, changing video and deterministic save-state replay; ASAN clean.
- Optimized/R8 Android receiver: byte-identical transfer, native advancement,
  held gamepad Start reaching level 1, pause with stable frame count, checkpoint
  save/load, automatic resume and process restart. Screenshots inspected.
- Phone actual SAF import, private-copy persistence across APK update, visible
  paired sync/dedup result. Independent transport tests cover missing games in
  both directions, multi-chunk files, corruption/repair, auth and reconnect.
- Incomplete uploads stay hidden; bounded staging, hash/format checks, atomic
  publication, no deletion propagation. ROMs synchronize; projector saves do not.
- Final Home Games/Controllers buttons and phone compact game row visually
  checked. ROM bytes and all captures/session details remain ignored/private.

## What failed / limits

- Initial sandbox socket restrictions required reviewed local-build/emulator
  escalation. No automatic approval rejection remained unresolved.
- Existing Android 30 AVD referenced a missing image; used a fresh Android 36
  isolated AVD. That does not establish Android 9 projector behavior.
- First test PIN regex missed the displayed space; fixed the harness. First
  runtime wait encountered Android's fullscreen tutorial, which correctly paused
  the game on focus loss; dismissed the tutorial and reran successfully.
- Project-local Gradle cache lacked an offline lint artifact; shared existing
  cache passed without disabling lint or changing dependencies.
- Initial phone test could not complete projector connection without EShare;
  loopback control fixture supplied that unrelated service for real API sync.
- No physical phone/projector contacted or installed. Bluetooth pairing, actual
  Nintendo face-button mapping, simultaneous inputs, audible sound, projector
  frame pacing/latency and phone-provider Open-with behavior remain unverified.
  Final APKs have the last cosmetic changes; runtime suite passed immediately
  before the receiver's XML-only row-height adjustment, which was visually checked.

## Final artifacts

| App | Local APK | SHA-256 |
| --- | --- | --- |
| Phone 0.4.35/code45 | `android/app/build/outputs/apk/release/app-release.apk` | `563355fc0fda7d09255cbd5893163b3956cfa35ae075e4f9cbab72f5bd160869` |
| Receiver 0.3.18/code32 | `android-receiver/app/build/outputs/apk/release/app-release.apk` | `1046d557fd7f98cd1c39f5995b07e2bd36808eb432ee61597ea9448f29b03701` |

No public feeds updated. Physical installed versions remain the previous
checkpoint until verified during the next device session.

## Remaining questions

1. Is the user's hardware a Switch Pro Controller or Joy-Con halves, and how does
   the projector's Android Bluetooth/input stack map it? The helper supports both
   ordinary gamepads and multiple input devices, but this needs a physical test.
2. Does the projector sustain native picture/audio without stutter? Test Start,
   run+jump, menu, saves and controller disconnect before declaring acceptance.
3. Verify phone import from its file manager, automatic ROM upload, phone-off
   gameplay and receiver-to-second-phone synchronization. Check duplicate import.
4. SNES/GBA, PAL timing, expanded NES2 features, save synchronization, remapping
   UI and a phone touchscreen gamepad are not implemented in this NES slice.

## Suggested next prompt

“The projector and phone are on. Install the local 0.3.18 receiver and 0.4.35
phone candidates without clearing data, import/sync Mario Tupi, then help pair
my Nintendo controller and verify navigation, gameplay, sound and saves.”
