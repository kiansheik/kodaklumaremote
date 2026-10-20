# Android receiver, native address entry and navigation — 2026-10-20

## Goal

Continue in the existing Android LUMA Remote after the user explicitly revoked
the earlier Android exclusion. Integrate the TV player/control path, replace old
MSX manual-address instructions with a typing button and move Home/Back beside
Up. Continue native TV sideload discovery separately; no second phone browser
remote, publication, vendor enrollment mutation or DNS change.

## Files inspected

Required agent index/current-state/repo-map/open-questions, MSX/Matter/JointSPACE
docs and current source. Native ConnectionService, MsxServer, Matter adapter,
MainActivity forwarding, native tests; mobile app and MSX receiver/assets/tests;
Mac proof server, Android build/signing configuration and private device state.
Public DevView/SDK packaging and model documentation are cited in the linked
[sideload report](../philips-sideload-followup.md).

## Files changed

- Native `MsxServer.java`, `ConnectionService.java`, `MatterControllerAdapter.java`;
  new `PhilipsTextController.java`; native MSX/multireceiver/text instrumentation
  and dispatcher; local version0.4.6/code15 in Android build configuration.
- Existing `src/mobile/{app.js,index.html}`: sample button, PIN/address-entry
  setup, partial/cancelled text handling, Home–Up–Back D-pad row.
- `src/msx/{receiver-core.js,receiver.js,receiver.html,receiver.css,README.md}`:
  explicit autoplay recovery and bootstrap/session renewal without replaying
  media. Mac proof bootstrap compatibility and corresponding Node/browser tests.
- Agent current-state, log, index, repo-map, open-questions, receiver details,
  new sideload report and this handoff. Earlier dirty discovery/protocol files
  and unrelated empty `headers.txt` preserved.
- Private ignored build/UI/test evidence under `.local/tv-hello/android-player/`.
  No capabilities, credentials, active media sources or firmware added to Git.

## Commands run

- Targeted rg/sed/git status/diff reads; no broad repository load.
- Private `build-local.py`: signer fingerprint guard, process-only existing
  signer credentials, offline Gradle debug/test/release builds and lint.
- `node --test tests/msx-receiver.test.mjs`:25 passed.
- `node --test tests/msx-mac-proof.test.mjs`:14 passed with loopback execution.
- `npx playwright test tests/browser/mobile-receivers.spec.js tests/browser/mobile.spec.js`:
  54/55 passed. One assertion still required old corner buttons; updated to the
  requested row, focused existing-case rerun1/1 passed. No production change for
  that failure. Earlier focused setup/deadline/layout fixtures also passed.
- Emulator-only `scripts/test-android-msx.mjs`8 groups;
  `scripts/test-android-multi-receiver.mjs`4 groups;
  `am instrument -e mode philips-text`4 groups: all failures0 after final build.
- ADB signed local upgrade on the connected physical phone; no data clear or
  uninstall. App-only accessibility snapshots and sanitized receiver log.
- Read-only exact-TV system identity plus authenticated context using retained
  Mac credentials and current verified host. Old saved host is stale; context
  labels remain unreliable for GUI focus. No global hint rewrite.
- `git diff --check` passes. No commit, push, public release or vendor message.

## What worked

The TV fetched the phone's actual receiver after a temporary redirect through
the already working diagnostic. Original diagnostic HTML was restored byte for
byte in `finally`; the user confirmed **LUMA Remote waiting screen**. This proves
phone asset delivery, not sample playback or normal saved MSX startup.

The final update retains Matter pairing and installs on the phone. Address entry
resolves that already-paired TV's private address, then uses separate normal
JointSPACE PIN permission. The physical TV accepted the text-pair request and
the phone displayed the PIN form; the user was asked to save the PIN directly
on the phone. No Mac credentials were imported. Typed content is derived inside
native code from the active phone listener, never caller-supplied free text.

Native fixtures cover Digest/HMAC vectors, pending/expiry/peer/certificate gates,
encrypted storage, pacing/deadlines, partial and uncertain results, cancellation
and no retry/Enter. Scoped TLS does not alter global trust. Receiver fixtures
cover playback receipts, autoplay recovery, independent heartbeats, expired
bootstrap renewal and explicit phone approval. Independent source review found
no blocker in the retained-page recovery admission.

## What failed / limits

The first phone approval expired before confirmation. Reopening setup exposed
a retained-page recovery deadlock; both native and new-page bootstrap paths are
now fixed and tested. The old waiting TV has not yet requested approval again
in the final build's window. Do not claim physical receiver connection/playback.
One accessibility snapshot was killed137; a fresh snapshot succeeded. Earlier
phone setup sometimes restored Remote, so inspect the current screen before taps.

Normal text pairing still needs the user's PIN. Visible typing, TV Go/OK,
sample video/time progression, background controls and phone-off playback remain
unverified. Typing ACK counts alone do not prove visible text. Do not clear or
submit the TV field automatically, and do not retry uncertain keys. Current code
accepts only a resolved private IPv4; other Matter address families may need a
separate identity-preserving solution. Changed TV address/certificate recovery
still lacks explicit forget/re-pair UI.

The ordinary saved MSX startup still uses the earlier Mac/tunnel route; a fresh
normal phone-only startup requires saving the phone parameter. The Mac listener
and tunnel remain intact. The TV's encrypted UPG is still not decoded into a
native executable; Ghidra is available, decoded input remains the blocker.
DevView's public HTTP response makes a content-routing experiment plausible,
but actual catalog scheme, native binding grants and native app installation
remain unknown. Local experiment/template prepared only; no DNS change applied.

## Remaining questions

Can the user complete the phone PIN grant and visibly type the phone address
into MSX Setup? Does a fresh receiver page reach approval and play the fixed
sample with progressing time? If the retained page stays idle, use observed user
focus or a deliberate reload instead of blind navigation. Then test phone
background/reconnect, original media and subtitles. Can a supported app context
expose native bindings without partner approval? No answer established yet.

## Suggested next prompt

“Continue from the local0.4.6 phone/MSX handoff. First check whether I saved the
TV PIN, then help me focus and clear the MSX startup field, use Enter TV address
and verify it before I submit. Approve the actual TV and test sample playback.
Keep native DevView/sideload research separate and don't change DNS or publish.”
