# Receiver handshake resume — 2026-10-20

## Goal

Continue until something works on the Philips after the user's explicit
“don't stop” steering; diagnose the existing phone-hosted receiver handshake
without another blind address-entry loop. Research local DevView in parallel.
The earlier navigation deferral is historical, not the current task state.

## Files inspected

Required agent index/current-state/repo-map/open-questions; `MsxServer.java`,
`src/msx/{receiver.js,receiver-core.js,receiver.html}`, mobile stage rendering,
native diagnostic fixture/receipt, browser/native test logs and private installed
artifact/physical receipts. DevView's refreshed public entry/bundle/docs/gateway,
exact source excerpts, isolated fixtures and focused REA envelope were also read.

## Files changed

- `MsxServer.java`, native MSX fixtures and mobile UI/fixtures: installed stage
  diagnostics distinguish accepted hello/poll/state counts, current session/
  approval and time since successful state. No raw URL/client/session identifier
  is added to user-facing diagnostics.
- `docs/agent/philips-sideload-followup.md`, `philips-devview-protocol.md`: fresh
  local-mode findings and historical Android-scope correction.
- `docs/agent/current-state.md`, `log.md`, `index.md` and this handoff: current
  resume checkpoint. Unrelated dirty work/private evidence preserved.
- `src/msx/{receiver.js,receiver-core.js}` and receiver fixtures: synchronous
  failure handling in tick/loop/heartbeat, isolated callbacks, fixed first-error
  report independent of player.state, preserved uncertain receipts without replay
  and Ready only after successful state POST.
- `MsxServer.java` and native fixtures: first HELLO/POLL/STATE logs plus the
  six-enum capability/peer/origin-bound GET diagnostic route. Diagnostic reports
  never confer approval, freshness or connected. These changes are installed;
  the TV has now refreshed them and completed the real handshake.
- Both player/channel default timers now delegate global calls instead of
  invoking copied browser-host methods with the wrong receiver. Scheduling is
  guarded; a new real Chromium regression covers the browser-only failure.

## Commands run

- Targeted `rg`/source/JSON reads; diagnostic source/fixture `javac` compilation.
- Diagnostic build/sign/install through the existing local procedure; actual
  `adb install -r` returned Success, preserving saved app data/pairing.
- `npx playwright test tests/browser/mobile-receivers.spec.js`:26/26 passed.
- Native MSX runner: eight groups passed, failures0. Receipts remain ignored at
  `.local/tv-hello/android-player/receiver-stage-full-browser.log` and
  `receiver-stage-native-msx.log`.
- Final combined fix:31 receiver Node fixtures passed, including throwing video
  getter, throwing status/error callbacks, recovery and failed-state receipt
  retention without replay. Eight native diagnostic/lifecycle groups passed;
  debug/test/release builds and lint passed. Signed `adb install -r` returned
  Success with saved data preserved. The current private APK receipt records
  `exceptionSafeReceiver:true`; no fixed hash is copied into this handoff.
- Final host-timer fix:32 receiver Node fixtures and
  `tests/browser/msx-receiver.spec.js`1/1 passed in real Chromium. Independent
  Chromium execution produced hello1/poll1/state1 with no page errors. Native
  Java is unchanged from the eight passing diagnostic/lifecycle groups. Final
  offline build/lint and signed in-place installation passed; private artifact
  receipt records `browserHostTimerFix:true`.
- W3C replacement/logging build: final signed build/lint, native MSX8/8 and
  multi-receiver4/4 pass. Installation returned Success and saved data remained
  intact. Real Mac Chromium video/core playback succeeds with duration52.208s;
  private receipt is `sample-check/chromium-real-video.json` under the Android
  player evidence directory. This is a Mac result, not TV playback acceptance.
- Final numeric-media diagnostic build:33 receiver Node checks, one real-browser
  regression, signed build/lint and native nullable/bounded-tuple MSX8 groups pass.
  Installation returned Success. The private receipt now records mediaDiagnostics
  and persistentPlaybackError. One TV Refresh has been requested for its changed
  JavaScript; the user's reply and diagnostic result remain pending.
- Read-only exact-TV identity check; documented inspector `/json/list` checks
  on9222 and7001 returned `URLError`; GET `activities/browser` was rejected.
- Two Mac loopback observations over34.9s showed unchanged counters. This is
  not a capture of phone receiver traffic.
- Anonymous bounded public source GETs and focused REA; nine isolated DevView
  switch fixtures and two owned-page presence fixtures passed. No complete
  vendor bundle bootstrap, real native call, catalog request or DNS change.
- This documentation editor ran targeted reads and `git diff --check`; it made
  no device requests, builds or code changes in this pass.

## What worked

The combined diagnostic/exception-safe APK is installed. Use ignored
`.local/tv-hello/android-player/browser-shortcut-build-receipt.json` for current
artifact/installation fields rather than copying a fixed hash across same-version
builds. Its `exceptionSafeReceiver:true` and `browserHostTimerFix:true` distinguish
the timer behavior; current mediaDiagnostics/persistentPlaybackError flags also
record the latest installed diagnostic build. The app remains local0.4.6/code15; no publication is
implied. The user confirmed refreshing the current TV page: current-process
BOOTSTRAP_READY/HELLO_READY13:49:32 and POLL_READY/STATE_READY13:49:34 all returned200.
Phone Refresh shows ready to cast, active connection and no playing media.
This establishes the actual phone/TV handshake. An earlier sample helper stopped
at the native website overlay without sending an RPC. A later grounded ADB tap
did reach Play sample, visibly showed Starting and sent the command; it returned
generic rejection. The user reported no TV error and no playback. The Google
sample returned403 in a source check. W3C Sintel's reachable52-second trailer is
verified and installed in the signed replacement/error-logging build. The
existing TV page automatically reconnected to the new process with BOOTSTRAP/
HELLO14:03:49 and POLL/STATE14:03:50 all200. A grounded raw W3C sample tap then
failed with `ACK_FAILED open MEDIA_FAILED` at14:04:35. Picture/sound/progress and
a successful TV playback receipt remain unverified. The latest numeric diagnostic
APK has now installed successfully; the already loaded TV page automatically
reconnected with current-process BOOTSTRAP/HELLO14:08:03 and POLL/STATE14:08:04 all200.
Those requests do not establish that new JS loaded. The single requested TV
Refresh/user reply remains pending; do not replay the sample before that reply.

Before the timer fix, the refreshed TV reached HELLO_READY200 but no poll/state
or client diagnostic. Root reproduced the source fault in real Chromium:
calling native timers as methods of a copied timer object throws TypeError.
Both default timer objects now delegate through global calls. The new real
browser regression and subsequent physical handshake recovery support this
cause. Synchronous getter/callback guards remain useful but were not established
as the TV's original failure.

Before this fix, the first direct LAN browser-launch request was accepted. No new session was
observed from that request, and its visible navigation was not separately
confirmed. The user confirmed they were already viewing the LUMA approval page.
At the agent's request they refreshed the current page once. The phone then
displayed **“TV allowed. Waiting for receiver status.”** Current diagnostics show
`sessionApproved:true` and `lastStateMsAgo:-1`: a fresh page contacted the phone,
saved approval was recognized, and no valid state report exists for that session.
A new bootstrap200/no400 was observed. Sample playback was not attempted. One
Confirm-key ACK subsequently left it waiting for state; it does not prove focus
or that an input gesture was required.

Fresh local DevView evidence is retained privately in
`.local/tv-hello/devview-local-revisit/summary.json` and `NEXT_CONTEXT_TEST.md`.
Public DevView/gateway hashes remain unchanged; public HTTP/HTTPS DevView return200.
`localhost`/SDK-source switches do not establish an accountless local backend.
Official App Control still requires app authorization. Focused REA parsed1,735
bytes/410 AST nodes with0 parse failures; coverage unknown. An owned no-network,
presence-only page is prepared, not deployed in a DevView context.
[Sources and boundaries](../philips-sideload-followup.md#resumed-local-devview-review).

## What failed

Before the timer fix there was no fresh playback-state connection or media
proof. A current approved session narrowed the problem beyond initial delivery,
but does not prove which hello/poll/state step stalls. No new400 does not identify
a client exception or prove that no state POST was attempted. The new source
diagnostics are intended to distinguish those cases, not to assume the cause.
The timer fix has now recovered the physical handshake. The actual first sample
RPC was rejected; media playback and captions remain unverified. Source403 for
the installed Google sample is evidence about that source, while the generic
receipt does not independently identify the TV-side failure. The installed W3C
replacement also failed with MEDIA_FAILED; it plays in real Mac Chromium, which
does not identify the TV cause. Nullable bounded numeric media diagnostics and
preserving the TV error across heartbeats are installed, with33 Node/one browser,
native MSX8 and signed build/lint checks passed. Post-refresh physical diagnostic
values remain pending. Plain HTTP W3C returns301 to the
same HTTPS target, so that URL does not establish a separate HTTP transport test.

Neither documented inspector was reachable, and the read-only browser route did
not reveal the loaded URL. A launch ACK does not establish navigation. Installing
an APK does not replace JavaScript already resident in the TV page.

The actual DevView catalog URL/scheme and native binding policy remain unknown.
Public HTTP availability is not proof of a catalog HTTP launch. No vendor
account/server grant, DNS/router mutation, firmware change or native process
execution was established or attempted by the local-mode research.

## Remaining questions

What bounded media error/network/ready-state values accompany the installed
W3C sample's MEDIA_FAILED after the requested Refresh loads the installed diagnostic
JavaScript? Wait for that user reply before another sample. Can the TV then produce
visible picture/sound and progressing time? Handshake works; Google and W3C
sample RPCs failed. Caption rendering and phone-off continuation remain
later acceptance checks. Actual DevView-context/native presence needs a separate
bounded test.

## Suggested next prompt

“Continue from the actual restored TV handshake. The user refreshed the timer
fix and current hello/poll/state succeeded. The actual first Google sample RPC
was rejected, and the installed W3C sample also returned MEDIA_FAILED. Wait for
the user's requested single TV Refresh before replaying the sample: numeric media
diagnostics/error-preserving display are installed, but the user's refresh reply
is pending. Preserve saved pairing and use the next receipt/TV evidence to
identify a concrete media cause.
Keep local DevView's catalog/native-grant uncertainties separate from browser
playback. Do not repeat PIN pairing, clear app data, change DNS or publish.”
