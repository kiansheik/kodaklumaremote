# Live TV probe and remote updates — 2026-10-20

## Goal

Load the native-bridge diagnostic, then remove repeated TV interaction through
remote page reload/code updates. Our own TV player-shell execution remains the
larger goal; browser JavaScript/native markers do not establish native process
execution. Android remains excluded until explicitly requested.

## Files inspected / changed

Required agent docs; Philips client and tests; probe/server; maintained
JointSPACE `sendUnicode` source; Smart TV Alliance/TITAN API sources. Changed
`scripts/philips-controller.py`, `tests/test_philips_controller.py`,
`scripts/tv-hello-server.mjs`, `src/tv-bridge-probe/index.html` and agent docs.
Original pairings/release/Android files and untracked `headers.txt` preserved.
Private URL, session, evidence and local fixture scripts remain ignored.

## Commands and physical evidence

- Existing hidden-stdin `type` at confirmed selected address: bulk string
  acknowledged, but user saw only `h` and unchanged keyboard selectionT.
- One-off sequential requests for remaining58 characters: all acknowledged,
  user confirmed complete URL. No Confirm was sent because it would selectT.
- User selected visible keyboard Go/Ir. Probe visibly loaded; server
  GET1/ready1, later33 heartbeats before restart. User confirmed flags:
  loadApp=false,enableDevtools=false,SmartTvA_API=true,oipfObjectFactory=false.
  No native method was invoked; object implementation remains unknown.
- Corrected typing CLI: one Unicode codepoint per request,150ms between ACKs,
  one65s wall deadline, remaining request budget, no automatic failed-key retry
  or submit. Added sanitized acknowledged/partial receipts.
- `python3 -m unittest discover -s tests -p test_philips_controller.py -v`:
 21/21 offline fixtures passed. Initial loopback sandbox denial resolved through
  scoped escalation. Python syntax and diff checks passed.
- Descriptor-only SmartTvA_API sampling: bounded48 entries/own+2prototype
  levels, accessors/methods uninvoked; code marker is not native-origin proof.
  VM/browser tripwires0,720p layout inspected.
- `node .local/tv-remote-reload-smoke.mjs`: integrated local Chrome + real
  loopback server passed fixed reload, automatic source-edit refresh, preserved
  URL restart and **same live document** recovery after restart, with native/
  getter calls0. A review caught ready-only capability loss; heartbeats now
  reannounce capability and the regression passes.
- New private server config retains the original random receiver token so the
  exact existing TV URL survives restart. Loopback control gets a fresh token.
  HTML is read on GET/report, hashed and revision-injected; changed revision
  causes a reload directive in the next10s heartbeat ACK. Explicit command:
  `node scripts/tv-hello-server.mjs reload` (queued, not verified completion).
- Old TV status was preserved privately before restarting. One same-URL paired
  `open-url` again returned accepted/pageLoadVerified:false; no GET followed,
  while legacy heartbeats continued. No claim that this API refreshes the page.
- User found refresh difficult, selected/emptied the address and submitted the
  corrected full URL. The current page then reported revision/remoteReload:true.
- Actual fixed server-triggered reload produced GET2/ready2 with uptime reset.
  A harmless visible revision badge edit then produced GET3/ready3 with matching
  new source/loaded hashes. Both occurred without another TV click. Private
  snapshots retain the two confirmations.
- Runtime SmartTvA_API surface contains only exit and hasCapability, both with
  nativeCode:false. No methods/accessors were called.

## What worked / failed

Actual URL entry, visible flags, page retrieval, JavaScript and sustained
heartbeats are established. Per-character typing filled the complete URL;
whole-string typing had only inserted h. The initial bulk-payload fixture did
not prove firmware compatibility, and the desktop controller was corrected.

After user bootstrap, **actual TV fixed remote reload and automatic source-edit
refresh passed**, as did recovery while a document stayed open across our
listener restart. Paired browser-launch acknowledgements still did not produce
navigation; do not use that response as page-load proof.

## Remaining questions

The two sampled SmartTvA_API functions do not establish native execution or a
privileged loader. DevView account verification remains pending. The user's
latest clarification now asks for implementation/version and URL-app
compatibility discovery, not vulnerability work. That continuation is in the
[browser handoff](2026-10-20-browser-compatibility.md). Android remains untouched.

## Suggested next prompt

“Continue from the measured browser compatibility profile. Use the already
working automatic update channel for a minimal TV player proof; distinguish
reported codec support from actual picture/audio/subtitle rendering, and leave
Android unchanged.”
