# Philips receiver peer rebind

## Goal

Remove the remaining `REQUEST_REJECTED` response after the launcher is given the
phone's correct `192.168.68…` address.

## Files inspected

- `android/app/src/main/java/dev/framextv/lumaremote/MsxServer.java`
- `android/app/src/main/java/dev/framextv/lumaremote/ConnectionService.java`
- `android/app/src/androidTest/java/dev/framextv/lumaremote/MsxInstrumentation.java`
- `site/tv.html`
- `docs/agent/current-state.md`
- `docs/agent/open-questions.md`

## Files changed

- `MsxServer.java`: safely persist a saved receiver approval against the current
  private Matter TV peer while preserving receiver identity.
- `ConnectionService.java`: perform that rebind immediately before native
  phone-IP autofill.
- `MsxInstrumentation.java`: cover public-address rejection, identity retention,
  session clearing, persistence and actual connection-peer migration.
- `src/mobile/index.html` and `src/mobile/app.js`: add a dedicated Philips
  **Speed dial** browser-activity action.
- `release-notes/0.4.16.txt`, `release-notes/0.4.18.txt` and agent documentation.

## Commands run

- Android app and AndroidTest Java compilation with offline Gradle.
- Focused Philips receiver and Speed dial browser tests.
- Guarded 0.4.18 release/deployment: Node 247/247, browser 162/162.
- Wireless ADB in-place installation, package-version verification and cold launch.

## What worked

- The phone listener was independently reachable on `192.168.68…`; the HTTP403
  body proved the white page was receiver admission rejection, not a dead
  listener.
- Android app and instrumentation sources compile successfully.
- Live phone logcat identified every current failure as `PEER_REJECTED`.
- A bounded explicit migration now adopts the actual private browser peer, and
  a dedicated Speed dial UI action has focused browser coverage.
- 0.4.18/code 28 was published from `8a84bbf`; the signed 9,821,602-byte APK
  (`276c2793…bf37`) was installed and cold-launched on the Samsung.

## What failed

- The earlier launcher suggested the wrong `192.168.1…` subnet.
- Even with the correct phone address, the saved receiver approval was pinned to
  the TV's former network address and returned `REQUEST_REJECTED`.
- Rebinding only to Matter's resolved address was insufficient because the TV
  browser's observed private peer differed from it.
- The 0.4.18 timed migration also failed physically with `PEER_REJECTED`; its
  logs did not expose the incoming/saved addresses, so the prior iterations did
  not establish the exact mismatch before changing behavior.

## Current correction

- `/tv/connect.html` creates a 60-second private candidate without changing the
  approved peer.
- Only `hello` carrying the exact saved TV browser client ID commits migration.
- Logs include incoming, saved and migrated private addresses for live evidence.

## Remaining questions

- Does the real Philips now load the receiver without another **Allow TV** tap?
- Can the TV's browser reliably focus and submit the launcher field?
- Does the 0.4.17 four-pixel no-pointer adapter turn each physical Philips
  directional stroke into exactly one focus step?

## Suggested next prompt

Retest the sole speed dial, use **Autofill this phone on LUMA page**, and report
whether the receiver waiting screen appears or whether a JSON error remains.
