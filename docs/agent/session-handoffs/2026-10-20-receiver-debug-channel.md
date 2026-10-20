# Receiver debug channel — 2026-10-20

Latest outcome: [LAN sample playback handoff](2026-10-20-lan-sample-playback.md)
supersedes the pending-build/install checkpoints below. Combined APK e0dd…10a4
is installed; actual TV reports Playing0:03, stable Pause0:19 and resumed
Playing0:24. Picture/audio confirmation is pending, with no full-length completion
claim. Both phone feedback repairs are physically verified: idle app Reload
produced uptime705→1s and “TV page reconnected.” on unchanged d66 revision.

## Goal

Carry the earlier probe's useful debug controls into the phone-hosted TV
receiver, so future inspection and reload use the existing approved channel.
Keep media playback independent and report exact deployment boundaries. The user
explicitly requested this work; Android remains in scope.
The current follow-up compares the failed public W3C sample with the same bytes
served by the phone, using the already proven debug/control channel.

## Files inspected

Required agent index/current-state/repo-map/open-questions; the preceding
receiver-handshake handoff; `src/msx/{receiver-core.js,receiver.js}`;
`scripts/build-mobile.mjs`; `MsxServer.java`, `ConnectionService.java`, both
native receiver fixtures; mobile Developer tools/fixtures and private build,
test and generated-asset evidence. The optional sample packaging/build input and
candidate native fixed route were also inspected. Raw session data and URLs
remain ignored.

## Files changed

- `src/msx/{receiver-core.js,receiver.js}` and Node fixtures: fixed `ping`,
  `set-label` and `reload`, guarded against media side effects and action replay.
  The label is exactly **LUMA debug channel ready**. Reload waits for successful
  state and explicit `{ok:true}` ACK, stops polling/timers, then navigates once.
- `MsxServer.java`, `ConnectionService.java` and native fixtures: empty
  `msx.debugPing`, `msx.debugLabel`, `msx.reloadReceiver` RPCs require Philips
  selected. Reload rejects active playback/pending work; old pages without the
  advertised capability return `CONTROL_UNAVAILABLE`. Existing session/peer/
  origin/receipt guards remain. Ping/label do not mutate media authorization.
- `scripts/build-mobile.mjs`: hashes five named receiver template assets with
  NUL boundaries, injects one64-hex stamp into generated `receiver.js`, and writes
  `msx/revision.json`. This is the normalized template-set identity, not the raw
  digest of the already injected script. No generated asset was manually edited.
- Mobile UI/fixtures: collapsed Developer tools compare loaded/server revisions,
  bounded uptime/ping counts and observed capabilities. No automatic retry or
  reload is added. The narrow-layout fixture now checks actual visibility and
  summary bounds instead of counting hidden closed-details descendants; this
  test correction did not change product bytes. A subsequent full run found two
  disconnected/manual-setup layout cases. The final visibility guard hides tools
  during PIN entry or disconnection without observed debug state;29/29 now pass.
  Phone-only follow-ups accept fractional uptime and clear reload waiting only
  after a later connected report with a valid loaded revision and greater hello
  count than the valid receipt baseline. Counter reset invalidates that proof;
  stale heartbeats/revision changes alone do not clear waiting. Focused3/full29
  pass; these feedback changes are not yet installed.
- Candidate `scripts/build-mobile.mjs`, Android asset task and `MsxServer.java`:
  explicit opt-in same-byte W3C fixture, fixed capability/approved-peer route
  and isolated native sample authorization. Candidate native fixtures/build/
  install are pending. No media is checked in or general proxy introduced.
- `current-state.md`, `log.md`, domain/repo-map/open-questions and this handoff:
  latest checkpoint, closing stale pending-Refresh claims.
  Earlier history and unrelated dirty work are preserved.

## Commands run

- Targeted `rg`, source/receipt reads and `git diff --check`.
- Narrow `javac --release 17` source/fixture compilation against Android35,
  existing dependency classes/Matter jars and generated `R.jar`: passed. The
  first manual compile omitted `R.jar`; correcting that classpath passed without
  a product change.
- `node --test tests/msx-receiver.test.mjs`:38 passed.
- `npx playwright test tests/browser/msx-receiver.spec.js`: one real Chromium
  regression passed.
- Isolated native MSX and multi-receiver instrumentation:8 and4 groups passed,
  including fixed routing, caller-input/target rejection, idle/active/pending
  reload guards and bounded debug-state validation.
- Existing local build/sign procedure produced the signed debug candidate.
  Packaged bytes were verified. Independent normalization of generated assets
  matched `revision.json`, with one injected stamp and no remaining placeholder.
- The29-case mobile receiver browser run initially failed because its helper
  counted hidden descendants in closed Developer tools. Corrected visibility/
  summary bounds pass the focused narrow-phone recheck. After the full rerun
  found two disconnected/manual-setup cases, the product visibility guard was
  fixed; final29/29 pass in10.5s. The final UI APK was rebuilt and installed with
  Success, preserving saved data. Native/core checks remain applicable. Private log:
  `.local/tv-hello/android-player/browser-developer-tools.log`.
- Receiver template revision:
  `d66f118d4c4ec962cb937f693e60b36dab32267e6d561236c2c19d8f1a40dcbf`.
  The ignored installation receipt records `remoteReceiverDebugControls` and
  check results. The final phone-only feedback build/install is pending, so its
  final artifact hash is not recorded yet. No public release is implied.
- Actual app controls: Ping ACK/counter0→1, fixed-label ACK, then Reload ACK and
  later fresh state with counter1→0/matching revision and controls enabled.
  Private evidence: `.local/tv-hello/android-player/debug-reload-reconnected-ui.xml`.
- Fractional uptime formatter: focused3/3 and full29/29 pass. It accepts finite
  seconds0..315360000 and floors display, keeping ping count strictly integral.
- Reload reconnect feedback: focused3/3 and full29/29 pass in2.3s/10.7s, including
  unchanged heartbeat, changed/missing revision, disconnected report and counter
  reset negatives. Private logs:
  `browser-developer-reconnect-{focused,full}.log` under Android-player. These
  phone-only repairs are not yet installed; receiver revision is unchanged.
- Actual TV sample14:26:41: MEDIA_FAILED, media error code4/networkState3/
  readyState0, before metadata. No successful playback or specific cause follows.
- Root's candidate packaging checks: ordinary build omits generated clip, wrong
  input fails, and opt-in output exactly matches4372373 bytes with SHA-256
  `b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`.
  `LUMA_DIAGNOSTIC_SAMPLE` requires an explicit regular-file path; this does not
  admit caller media URLs. Native fixtures/combined build/install remain pending.
- This documentation pass made no device requests, source changes or builds.

## What worked

The approved command channel now carries the debug controls and retains an
uncertain receipt instead of executing an action twice. Ping/label bypass the
media command path. Reload cannot interrupt reported active playback or pending
native work, and requires its own successful ACK before page exit. Native
snapshots preserve validated `playback.receiverDebug`: revision=null/64-hex,
uptime0..315360000, integer ping count0..1000000000 and two capability booleans.
Unknown fields are dropped; legacy absence remains compatible. Packaged
revision reads are bounded; missing/malformed metadata reports null.

The final debug APK is installed and has the proven TV hello/poll/state
handshake. The user confirmed the TV page was already reloaded and Up had opened
the URL bar; one Down returned focus. Actual Developer tools then showed
matching Loaded/Server d66f118d4c, ping count0, capabilities true and all three
controls enabled. A real app Ping ACK advanced the count0→1. The fixed label
was acknowledged, without a separate visible-text confirmation. Actual Reload
ACK was followed by fresh state on the same revision, count1→0 and controls
enabled again. Remote JavaScript reload is complete on the actual TV.
The installed debug APK is identified by61fb673f…ccde005 in the ignored current
receipt; do not confuse the next generated artifact with an installed build.

Earlier the old resident page correctly reported no debug capabilities and
disabled controls. Options/Confirm had reached an “Adicione”/OK item; Back
cancelled it. That menu sequence was not proof of selecting a saved LUMA tile.
The user's later confirmed page reload plus matching reported revision supplies
the actual new-code evidence. Installing a phone APK alone did not establish it.

## What failed

The initial mobile narrow-layout failure was a fixture visibility error; after its correction,
the full run found two genuine disconnected/manual-setup cases (27/29 passed).
The small tools visibility fix now passes29/29 and is installed in the final APK.
Physical testing exposed two phone feedback issues: uptime appeared unavailable
because the valid native value was fractional, and reload waiting remained
stale after real reconnection. Both source repairs pass29 mobile cases as above;
combined phone-only build/install is pending. No TV JS refresh is needed.

Google's original fixed sample returned403. The installed W3C Sintel sample
returned `ACK_FAILED method=open code=MEDIA_FAILED`. The later actual sample at
14:26:41 supplied media code4/networkState3/readyState0 before metadata. That
tuple and Mac Chromium playback do not identify the TV failure. Picture, sound,
progressing time and successful TV playback remain unverified. The native-only
same-byte LAN comparison is being prepared; no candidate runtime/install/TV
success is claimed. Do not infer codec, TLS, network or autoplay cause yet.

Local DevView DNS/HTTP routing is prepared with ten loopback-only checks, not
applied to the TV/network. It does not establish a native permission, accountless
authorization or native-process Hello World. No DNS/account/firmware mutation
or vendor enrollment was performed.

## Remaining questions

- Do the combined native sample fixtures/build/install pass and preserve the
  working TV handshake, debug controls and media/source authorization?
- Does the final phone-only feedback build/install show uptime and clear waiting
  only after a later connected hello? Receiver JS/revision need not change.
- Is the fixed label visibly rendered? Its actual ACK is confirmed, but visible
  text was not independently reported.
- Does the exact same W3C fixture play over the approved phone LAN route, and
  what request/range and bounded media state accompany that controlled trial?
  The known public-source result is code4/network3/ready0, not a root cause.

## Suggested next prompt

Finish/check the opt-in exact-byte LAN sample candidate and combined phone UI
fixes; install with saved data preserved without another TV reload. Loaded/server
revision and actual ping/label ACKs plus remote reload are already established.
Run one authorized controlled sample comparison and collect request/range,
bounded media state and visible result. Keep built, installed and physical
playback results distinct; retain DevView/native-process gaps separately.
