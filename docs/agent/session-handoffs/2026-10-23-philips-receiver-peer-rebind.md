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
  session clearing and persistence across server restart.
- `release-notes/0.4.16.txt` and agent documentation.

## Commands run

- Android app and AndroidTest Java compilation with offline Gradle.
- Guarded release/deployment and physical phone installation are the next steps.

## What worked

- The phone listener was independently reachable on `192.168.68…`; the HTTP403
  body proved the white page was receiver admission rejection, not a dead
  listener.
- Android app and instrumentation sources compile successfully.

## What failed

- The earlier launcher suggested the wrong `192.168.1…` subnet.
- Even with the correct phone address, the saved receiver approval was pinned to
  the TV's former network address and returned `REQUEST_REJECTED`.

## Remaining questions

- Does the real Philips now load the receiver without another **Allow TV** tap?
- Can the TV's browser reliably focus and submit the launcher field?
- Does the 0.4.17 four-pixel no-pointer adapter turn each physical Philips
  directional stroke into exactly one focus step?

## Suggested next prompt

Retest the sole speed dial, use **Autofill this phone on LUMA page**, and report
whether the receiver waiting screen appears or whether a JSON error remains.
