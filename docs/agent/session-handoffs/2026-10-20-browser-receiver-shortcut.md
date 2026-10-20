# Direct LAN browser receiver shortcut — 2026-10-20

## Goal

Make the existing Android app open the Philips TV's browser receiver as the
default, after the user rejected further MSX setup. Preserve the phone's saved
pairing and independent LUMA controls. Keep manual browser-link entry as fallback
and distinguish request acceptance from an actual TV page load. Latest user
steering removes the public redirect dependency: use only the current phone LAN
origin plus `/msx/connect.html`.

## Files inspected

Required agent index/current-state/repo-map/open-questions; receiver and
JointSPACE domain docs; `PhilipsTextController.java`, `ConnectionService.java`,
`MsxServer.java`, mobile UI, standalone receiver and Node/browser/native fixtures.
Known Python browser payload and retained TV advertised activities were compared
with the native route. Private final build/test receipts and APK identity were
read from `.local/tv-hello/android-player/`.

## Files changed

- `PhilipsTextController.java`, `ConnectionService.java`: empty-argument
  `msx.launchBrowser` and `msx.typeBrowserAddress`; native-derived
  `browserSetupUrl`; fixed URL/transport guards, saved PIN/certificate reuse,
  feature/deadline/cancellation gates and honest launch/typing receipts.
- `PhilipsTextInstrumentation.java`, `MultiReceiverInstrumentation.java`: fixed
  encoding/port changes, saved permission, rejected caller input, capability
  lookup, unsupported/cancelled/failed launch, partial typing and stopped-URL
  coverage. No real TV endpoint in these fixtures.
- `src/mobile/{app.js,index.html}`, mobile receiver browser fixtures: default
  **Open TV receiver**, first-use permission guidance, actual receiver-state
  waiting, manual entry fallback and explicit legacy MSX choice.
- `src/msx/receiver.js` and receiver Node tests: standalone browser execution
  skips the external MSX SDK; embedded MSX retains its optional bridge.
- Receiver/repo-map domain docs, current-state, log, index and this handoff.
  Other dirty work, installed app data, private listeners and retained captures
  were preserved. No generated APK or private evidence added to Git.

## Commands run

- Targeted `rg`, `sed`, `git diff --check` and artifact `stat`/`shasum` checks.
- Private `build-local.py`: existing signer verification, offline Gradle
  `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:lintDebug`,
  `:app:assembleRelease`; final wording rebuild/lint passed.
- `node --test tests/msx-receiver.test.mjs`: 27/27 passed.
- `npx playwright test tests/browser/mobile-receivers.spec.js`: 25/25 passed.
- Final generic-label wording rerun: receiver Node 27/27 and four focused mobile
  receiver cases 4/4 passed before the successful final rebuild.
- Direct LAN revision: rebuild/lint,25/25 receiver browser cases and native
  `philips-text` five groups/multi-receiver four groups passed. Retained logs are
  `.local/tv-hello/android-player/browser-direct-lan.log`,
  `browser-direct-native-text.log` and `browser-direct-native-multi.log`.
- Isolated emulator instrumentation mode `philips-text`: five groups,
  failures 0; `scripts/test-android-multi-receiver.mjs`: four groups, failures 0.
- Wireless ADB was restored through its automatic device alias. `adb install -r`
  of the final signed APK returned Success on the actual Samsung; saved data and
  pairing were preserved. Existing receiver status remains reachable.
- Pressed **Open TV receiver** once, then passive Refresh. The request was
  accepted, but the user saw Home and no receiver pending/connected appeared.
  After the user opened Internet and confirmed its address bar selected,
  pressed **Type browser link** once; the earlier redirect request completed
  with key acknowledgements. Expected TV peer approval succeeded and the user
  confirmed the LUMA page visible; visible text/causal navigation were not
  separately confirmed, and the page may have remained resident.
- Latest direct LAN APK: `adb install -r` returned Success; packaged UI verifies
  the exact current-origin guard and no httpbin, and bundled receiver skips the
  MSX SDK when standalone. Phone app reopened to setup with permission saved.
  No direct LAN URL navigation on TV yet. An initial artifact assertion assumed
  single quotes in esbuild output; correcting the assertion passed without a
  product-code change.
- This documentation pass used targeted source/log/receipt reads and
  `git diff --check`; no new builds, tests or device calls were run by its editor.

## What worked

The native service derives the browser link only from its current private phone
server origin: exact `startupUrl + '/msx/connect.html'`. It exposes
`browserSetupUrl` only while the server runs. HTTP/private IPv4/port1024–65535 and
fixed-path checks exclude credentials, query, fragment and alternate destinations;
the UI requires exact equality with its current native startup origin/path.
No public redirect service is involved. No caller can provide a URL/host/text,
and the URL contains no receiver capability. Saved PIN permission and per-TV
certificate binding are reused. The preceding httpbin link is historical only.

`msx.launchBrowser` reads fresh advertised browser support, then sends the fixed
`activities/browser` payload within a 30-second native budget. It returns
`accepted:true,pageLoadVerified:false`; receiver hello/approval/heartbeat state
must establish actual loading/connection. Unsupported browsers receive no launch
POST. Failure does not trigger an automatic launch retry. Independent source
review found no blocker.

Manual `msx.typeBrowserAddress` types the same fixed URL with a 90-second native
budget; legacy `msx.typeAddress` retains bare authority/65 seconds. Both pace one
codepoint every 150ms, never clear/submit a field, and preserve cancellation and
uncertain partial outcomes. Receipts include `textEntryMode`, `totalChars`,
acknowledged count and `textAppliedVerified:false`. The UI allows 120 seconds for
browser typing, 90 seconds for legacy typing and 60 seconds for launch.

The final checks above pass. Installed artifact:

- Path: `.local/tv-hello/android-player/luma-remote-0.4.6-local.apk`
- Version: 0.4.6/code15. Read current byte count/SHA256 from ignored
  `.local/tv-hello/android-player/browser-shortcut-build-receipt.json`, then
  verify that receipt when distinguishing this direct LAN APK from older
  same-version builds. Its `browserEntry` records direct LAN/no public redirect,
  and `installedOnPhone:true` records the successful in-place installation.
- Receiver assets bundled; signed local APK installed in place, not published.

## What failed / limits

Wireless ADB initially disconnected before installation. The user was asked to
enable Wireless Debugging; an automatic alias later became available and the
final APK installed successfully. Saved data remain intact and the receiver is reachable.
The older build had already saved the TV PIN and acknowledged typing, but the TV
was on the waiting page without a selected address field. Those observations do
not validate this new shortcut or visible typing.

The installed app showed “Launch requested. Waiting for the TV to connect…” after
one shortcut request. Passive Refresh showed no receiver pending/connected, and
the user confirmed the TV stayed at Home. The advertised browser API accepted
the request without opening the visible receiver; shortcut launch is not proven.
Earlier Mac `activities/browser` acceptance without a page remains consistent
historical evidence. The prior redirect typing request was acknowledged; expected
TV peer approval and user-confirmed LUMA page visibility followed. This does not
prove visible insertion or navigation caused by that request: Go/OK was not
separately confirmed and a retained page is possible. Fresh playback-state
connection remains false; the sample was not attempted. The user reports the
exact line **“Approve this TV in LUMA Remote on your phone.”** despite earlier
phone approval, so the handshake remains incomplete. The current direct LAN URL
has not yet been navigated on TV. The user deferred the requested Internet
address-bar setup until later; all device/navigation work stopped.

Native `connected` requires approval, a live session and valid state POST within
12 seconds. Successful hello/poll/state requests are not individually logged;
bootstrap200/no rejection lines do not establish missing state POSTs. Source
review found no state-flow defect: approved polls post state and an independent
two-second heartbeat does too; current idle state matches the validator. An APK
update does not replace JavaScript already resident on TV.
Sample decoding, actual media/subtitles, reconnect and phone-off continuation
remain unverified. Changed
TV IP/certificate recovery still needs an explicit identity-preserving re-pair
flow. No DNS/native-firmware/vendor-account changes or publication occurred.

## Remaining questions

When the user resumes, can direct LAN navigation/reload and a fresh approved
playback-state report be established before attempting the sample? The installed
APK is ready, but the TV still prompts for approval and its direct LAN URL has
not been navigated. The user deferred that step; do not continue device work
until they resume. The earlier automatic shortcut did not visibly launch; do
not infer navigation from acceptance, repeat PIN pairing or send typing without
a selected empty field.

## Suggested next prompt

“Resume the direct LAN browser setup I deferred. The installed app has saved
permission, but the TV last said Approve this TV and fresh state was absent.
Confirm the current screen before any navigation/typing, preserve saved pairing
and verify a fresh approved connection before the unattempted sample. Do not
infer a page load from ACKs, clear app data, change DNS or publish.”
