# Philips Hello World discovery — 2026-10-20

## Goal

Explore REA and the owned Philips TV's app/protocol routes; load a minimal
Hello World if possible. The user explicitly limits this to standalone proof,
protocols and documentation. Do not touch Android until separately requested.

## Files inspected

Required `AGENTS.md` and agent index/current-state/repo-map/open-questions;
Philips TV, JointSPACE, Matter/MSX notes and prior browser/power-cycle handoffs;
`scripts/{philips-controller.py,discover-tv.mjs,msx-mac-proof.mjs}`;
`tools/matter-controller/README.md`, package/Playwright configuration and ignore
rules. Targeted local REA source/package/help and LUMA provenance were checked.
Existing private identity/credential/network evidence was read without outputting
secrets. Official sources and precise links are in
[development evidence](../philips-development.md).

## Files changed

- `src/tv-hello/index.html`, `src/tv-hello/README.md`: self-contained browser
  proof and bounded ready/key/heartbeat report protocol.
- `scripts/tv-hello-server.mjs`: private TV-peer listener, random capability
  path, loopback-only status, exclusive0600 session and graceful cleanup.
- `docs/agent/{philips-development,current-state,index,repo-map,open-questions,log}.md`
  and this handoff.

Ignored `.local/tv-hello/` holds current discovery/system evidence, private
interface config, an isolated credential copy, hints and the running session.
`.local/philips-poc-discover.mjs` and `.local/tv-hello-smoke.mjs` retain the
session's one-off recon/browser commands. No raw addresses, identities or URLs
are committed. Original pairings, Android/release source and existing untracked
`headers.txt` remain untouched. No commit/push/deploy/release occurred.

## Commands run

- Targeted `rg`, reads, `git status --short`, `git remote -v`, `git diff --check`.
- Cached REA CLI `--help`, source/version/provenance inspection. No tool install.
- `python3 scripts/philips-controller.py info`: cached HTTPS unreachable, even
  with network permission. Bounded metadata-only old-address checks did not
  establish the current target. No credential/write went to an unverified peer.
- `node scripts/discover-tv.mjs --no-save`: one exact-model Philips,
  DIAL advertised, empty services/AVTransport, no discovery/description errors.
- `node .local/philips-poc-discover.mjs`: same bounded SSDP/descriptor flow,
  private current-address capture; the target differs from saved hints.
- Bounded GETs at current HTTP1925 `/1/system`, HTTPS1926 `/6/system`, and
  documented CDP9222/7001 `/json/version`. Both system endpoints200; encrypted
  serial/model/device ID match prior evidence; both debug ports refuse.
  At the advertised DIAL base, Netflix GET200/stopped; no launch sent.
- Private-only preparation of isolated credential/hints/config files after
  identity comparison. Existing client with `--state-dir
  .local/tv-hello/philips-controller --address-hints
  .local/tv-hello/address-hints.json context`: authenticated success, empty focus.
- `node --check scripts/tv-hello-server.mjs`; isolated ES5/browser checks;
  `node .local/tv-hello-smoke.mjs`: integrated loopback fixture in desktop Chrome.
- `node scripts/tv-hello-server.mjs`: actual private-interface TV listener;
  `node scripts/tv-hello-server.mjs status`: sanitized counters.
- Existing Philips CLI with the isolated paths and `open-url`: exactly one
  actual Hello World browser POST, accepted with `pageLoadVerified:false`.

## What worked

Confirmed exact TV and matching prior identity at its current address; existing
Digest pairing still works through an isolated copy. Official TITAN docs supply
a newly identified DevView hosted-app/testing/debug route. REA6.7.0 cached CLI
works; no new package or configuration was needed.

The real Hello World server/page passed integrated desktop Chrome at1280×720:
ready1, Right→Down→OK selected Local, key3, heartbeat1; no overflow/JS errors.
Back remains available. Invalid-origin and extra-field reports were rejected.
Separate isolated1920×1080 and Back461 checks also passed. Final screenshot
`/private/tmp/philips-hello-smoke.png` was visually inspected.

## What failed / validation limits

Initial sandbox networking/browser startup required scoped escalation; allowed
retries succeeded. Initial isolated720p overflow was fixed and retested.
The old HTTPS address was unusable. Current browser POST acceptance again
produced GET0/ready0/key0/heartbeat0; it is not a page-load or TV-rendering proof.
Context is not reliable focus evidence. Debug mode remains disabled/unverified.
No DevView account enrollment or on-TV DevView availability was observed.

Android/release suites were not run because those paths did not change. No
new media, remote-key, volume, power, firmware or app-install command was sent.
Firmware inspection, launcher replacement, app removal and permanent sideloading
are not established. REA tooling does not by itself supply those capabilities.

## Remaining questions

- What is actually visible on the TV? The user has been asked to open Internet
  and focus its address field, or report DevView availability.
- Does direct URL entry load the proof, with GET and ready reports?
- Is DevView present, can portal access be obtained, and does its sandbox accept
  the local proof URL? No account action or outbound support message was sent.
- Does enabling its documented debug toggle expose9222 or7001?
- Only after real execution: do physical directional/OK input and visible output
  match the desktop proof? Richer player work follows separately.

## Suggested next prompt

“Resume the Philips standalone Hello World. Do not touch Android. Check the
existing private Hello World session/counters, preserve pairings, and use my
confirmed Internet address-field focus to type its URL once. Require GET/ready
and my visible confirmation. Alternatively inspect DevView in Apps → Lifestyle
and follow the official sandbox/debug flow without firmware changes.”
