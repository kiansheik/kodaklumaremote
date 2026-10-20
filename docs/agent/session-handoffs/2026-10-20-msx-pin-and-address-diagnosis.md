# PIN feedback, MSX address source and public redirects — 2026-10-20

## Goal

Diagnose the user's confusing TV PIN submission in the existing phone app.
Steering then rejected another local-address retry and requested REA/source
evidence for the correct MSX format and Cloudflare success. Latest request asks
for a stable public query-parameter redirect service. Preserve existing pairing,
tunnel and unrelated dirty work; do not assume a saved diagnostic tile exists.

## Files inspected

Required agent index/current-state/repo-map/open-questions and preceding phone
handoff; mobile app/forms, native ConnectionService/PhilipsTextController/Matter
resolution and native/browser fixtures; existing probe/server/adapter and
private prior TV network receipts. Captured official MSX0.1.167/index/platform
table and TVX plugin0.0.79; pinned upstream Chromium112 policy/loader sources;
official MSX/Chrome and httpbin/httpbingo documentation.

## Files changed

- `src/mobile/{app.js,index.html}` and mobile-receiver browser fixtures: persistent
  saved permission, pending/checking UI, contextual inline errors/expiry,
  explicit closed-window restart and guards, accurate key-ACK wording.
- `PhilipsTextController.java` and `PhilipsTextInstrumentation.java`:
  `textPermissionSaved` survives temporary address unavailability; expired
  pending PIN derives explicit error without mutating successful grant state.
- `src/tv-bridge-probe/index.html`: fixed parent-only framework query, matching
  source/origin/envelope/correlation, one init/query and8s timeout; version/secure
  onscreen only. No SDK fetch, report protocol or running server configuration
  change. Existing diagnostic behavior is preserved.
- New MSX source report, current-state/log/index/repo-map/MSX details and this
  handoff. Ignored evidence under `.local/tv-hello/msx-status0-source/` and
  `.local/tv-hello/android-player/`. Earlier uncommitted work remains intact.

## Commands run

- Targeted rg/sed/diff reads and lightweight memory-registry search (no relevant
  hit). Own-app accessibility snapshot before update; no new PIN/typing action.
- Existing private signer-guarded local builder: offline Gradle debug/test/
  release/lint passed; signed0.4.6/code15 installed with `adb install -r`.
- Focused PIN/setup Playwright7/7. Final emulator text instrumentation4 groups,
  failures0. Independent read-only review found no blocker. No unrelated suite
  rerun; transport/media source unchanged by the UI fix.
- Public source downloads hashed and exact byte spans retained; focused
  REA6.7.0 parsed9,394bytes/2,930AST nodes, plus independent2,514byte XHR
  analysis831nodes. Both0parse failures; semantic coverage unknown.
- Exact selected declaration VM fixture29 assertions; helper19 and exact
  message-contract10 checks; network/native/system-info0 in offline fixtures.
- Real Chromium probe-frame fixture with simulated MSX parent and test-only
  loopback forwarding: expected secure2 line, ordinary ready accepted/rejected0.
  Does not test the TV or browser LAN policy. Inline JS syntax/diff checks pass.
- Public redirect GETs with fictional private/documentation targets, no follow:
  httpbin302/Location preserved; httpbingo403 with destination allowlist.
- Post-install own-app crash-buffer check found no package match; unrelated raw
  buffer discarded. App was not foreground in later snapshots; no physical
  confirmation of the new screen was obtained.

## What worked

Before the fix, live UI showed enabled Enter TV address and the successful-key
receipt. Native source predicates indicate the PIN grant and credential save
completed. User confirmed the TV still had the LUMA waiting page: no address
field was selected, so ACK did not establish insertion. The UI now distinguishes
saved permission from field entry and makes errors/progress/expiry visible.

Source confirms bare `IP:port` is correct and custom ports1..65535 are permitted.
MSX appends `/msx/start.json` and version/time queries. Padlock, HTTPS page or
secure2/4 select HTTPS. Public platform defaults are0; actual saved/launch
settings remain unknown. The prior two TLS-to-HTTP connections prove that
specific mismatch. Zero HTTP means CORS/query parsing was not reached there.

Cloudflare supplied trusted public HTTPS, then forwarded to our HTTP adapter;
it also changed address space. The earlier adapter's cache-query bug was separate
and fixed before TV success. httpbin's public redirect endpoint accepts private
destination examples, so it is a concrete redirect-only candidate.

## What failed / limits

The prior form could hide failures under the generic PIN prompt, silently fall
back after expiry, and return to the same generic heading on success. The closed
setup window could lack a restart control until a failed request occurred.
These reproduced source/UI issues are fixed and checked.

Initial post-update accessibility snapshots had no own-app nodes; the phone
was not left foreground. Initial Python public-source fetches failed TLS;
system curl succeeded. First frame fixture failed to load the loopback child;
test-only request forwarding fixed the protocol fixture, without claiming a LAN
policy result or changing global browser settings.

The exact cause of two earlier zero-byte TV connections remains unknown.
Upstream112 enables insecure-public→private blocking and can reject during
OnConnected; it is a plausible explanation, not a measured Philips net error.
The native Ajax override and exact installed entry/options are unknown. No
decrypted TV native component is available for Ghidra.

The framework diagnostic is prepared and source/fixture checked, but the user
says they do not have the prior tile/address saved. Do not repeatedly ask to
reopen it or to re-enter the known-failing LAN address. Latest steering favors
a public redirect. A redirect during JSON XHR still encounters the LAN target;
page navigation is different. A public redirect alone supplies neither an MSX
start object nor an acceptable full-query server-field value. No new public
service, short URL, DNS rule, account mutation or actual TV navigation occurred.

## Remaining questions

How can the public redirect be supplied as a page/plugin entry through the
actual MSX UI? Does the current TV permit that navigation to the phone's receiver,
and do its JS assets/channel initialize after it? Need current phone listener
readiness and observed TV focus before typing anything; no automatic submission
or replay. Actual framework secure setting, media playback and native TV app
loading remain unverified. Public testing endpoints have no permanence guarantee.

## Suggested next prompt

“Use the verified httpbin redirect as the next bootstrap lead. Work out the MSX
page/plugin entry from the actual source before asking me to type anything.
Keep my saved PIN permission, and distinguish page navigation from JSON XHR.”
