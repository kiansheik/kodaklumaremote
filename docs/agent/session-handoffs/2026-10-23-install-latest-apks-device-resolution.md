# Self-resolving phone selection for dual-device installation

## Goal

Make `make install-latest-apks` handle duplicate wireless ADB advertisements for
the same Samsung automatically without guessing between different phones.

## Files inspected

- `Makefile`, `scripts/build-latest-apks.mjs`, `scripts/receiver-dev.mjs`
- Current `adb devices -l` output and read-only installed phone package metadata
- Projector receiver `info`, release instructions and prior install handoff

## Files changed

- `Makefile`
- `scripts/install-latest-apks.mjs`
- `tests/install-latest-apks.test.mjs`
- `docs/releasing.md` and agent documentation

## Commands run

- `adb devices -l`
- Read-only `dumpsys package dev.framextv.lumaremote` for both phone transports
- `node --test tests/install-latest-apks.test.mjs`
- Live read-only `resolvePhoneTransport` invocation
- `npm test`
- `make -n install-latest-apks`
- `node --check scripts/install-latest-apks.mjs`
- `git diff --check`

## What worked

- Both advertised Samsung transports reported the same installed 0.4.24/code34
  package and resolve to one hardware identity. The new resolver selected the
  stable transport without the Bonjour `(2)` suffix and reported two transports
  for one physical phone.
- Selection prefers the physical device already containing LUMA Remote, excludes
  emulators and a device containing only LUMA Receiver, and preserves an exact
  `ANDROID_SERIAL` override.
- Focused tests pass 5/5 and the complete Node suite passes 252/252.

## What failed

- The former target called bare `adb install -r`. With both Bonjour aliases
  online, ADB refused with multiple-device ambiguity and Make never reached the
  projector deployment line.
- The first full Node run was started inside the restricted sandbox and its
  loopback fixtures stalled. It was interrupted and passed outside that network
  restriction; this was unrelated to the selector.

## Remaining questions

- Run the corrected Make target physically and verify phone 0.4.26/code36 and
  receiver 0.3.5/code19 afterward.
- If two different phones are intentionally connected, the operator must set
  `ANDROID_SERIAL`; the target deliberately does not choose arbitrarily.

## Suggested next prompt

Run `make install-latest-apks`, then read both installed versions and confirm the
phone/provider and receiver/Library fixes are active.
