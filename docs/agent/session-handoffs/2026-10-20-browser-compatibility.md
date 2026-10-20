# Philips browser compatibility — 2026-10-20

## Goal

Follow the user's clarification: understand this Philips browser's implementation,
version and URL-app compatibility through REA/source inspection and direct runtime
measurements. This is standalone proof/protocol/documentation work. Android remains
excluded until explicitly requested; no vulnerability work is in progress.

## Files inspected

Required agent index/current-state/repo-map/open-questions; existing probe,
server, controller/tests and prior handoffs. Public Titan SDK and exact selected
source excerpts, REA results and official Titan, Chromium, W3C and TiVo/Vewd docs.
Private live status and prior fixed/automatic reload snapshots under `.local/`.

## Files changed

- `src/tv-bridge-probe/index.html`: fixed numeric browser/vendor token parsing,
  dimensions/context, 13 API-presence flags and 12 fixed media declarations;
  display and ready report preserve existing revision/reload behavior.
- `scripts/tv-hello-server.mjs`: optional exact ready-only compatibility schema,
  bounded fields, ordered fixed media IDs and sanitized status output.
- `tests/tv-hello-server.test.mjs`: six durable protocol tests, including valid
  compatibility acceptance and malformed/private/heartbeat metadata rejection.
- `src/tv-hello/README.md`: schema and fixed remote-reload documentation.
- Agent current-state/index/repo-map/open-questions/log, historical development/
  execution pages and prior live-control handoff; new browser domain page and
  this handoff. Isolated browser/candidate/evidence files remain ignored.

Earlier changes to per-character Philips typing and its Python fixtures are
retained from the same uncommitted session. Original credentials/fabrics,
Android/releases/firmware and unrelated `headers.txt` are untouched.

## Commands run

- Existing cached REA6.7.0 `analyze-javascript-application`, focused public SDK
  declarations, 30s/768MiB bounds; exact command/ranges/hash retained in ignored
  `.local/tvhello/browser-analysis/report.json`.
- `node --check scripts/tv-hello-server.mjs`.
- `node --test tests/tv-hello-server.test.mjs`: six passed using loopback fixtures.
- `node .local/tv-compatibility-smoke.mjs`: final HTML + real isolated server +
  Chrome passed payload acceptance, 12 MIME checks, no external requests or
  vendor method/getter calls, and rejection of an extra rawUserAgent field.
- Prior `node .local/tv-remote-reload-smoke.mjs`: fixed reload, source refresh and
  same-live-document server-restart recovery passed in local Chrome.
- Verified our listener PID/command, SIGINT cleanup, restart using the same
  persisted receiver token; source revision then automatically refreshed on TV.
- Bounded loopback-status polling saved whitelisted actual-TV results privately;
  no raw UA, private URL or address was printed or checked into Git.
- `git diff --check` and targeted relative-document-link checks.

## What worked

The TV actually fetched and ran the compatibility probe without further user
clicks. Final vendor-token capture after our restart: GET1/ready1/heartbeat1,
rejected0, remoteReload true and matching source/loaded revisions. Prior fixed
reload and harmless source-edit refresh also produced new GET/ready reports.

Reported tokens: Chrome112.0.4147.62, OMI4.24, Opera46.0.2207.0, TitanOS3.0,
NETTV4.6.0.7, SmartTvA5.0.0, AppleWebKit537.36. Viewport1920×945; screen1920×1080,
pixel ratio1. Navigator platform Linux x86_64 is not CPU/ABI proof.

Fetch/Promise, WebAssembly, WebSocket, MediaSource, WebGL/WebGL2 constructors and
storage-name exposure were present. Ten tested codec types declared probably/
MSE true; HLS/DASH manifest types returned maybe/MSE false. Manifest MIME false
for MSE does not rule out a player using supported encoded segments. No media
was fetched or decoded, storage accessed, GPU/decoder initialized or DRM requested.

Focused REA parsed six SDK declarations without parse errors, 1008 AST nodes,
424 semantic nodes, 232 relations, 161 unknowns; semantic coverage unknown.
Source fallback HLS/DASH/DRM flags are hardcoded defaults, not TV measurements.

## What failed / remains uncertain

The exact engine build is unresolved: Titan's TPM256L table says Chromium122;
the actual Chrome token says112; Vewd's generic4.24 revision table refers to114.
These references cannot authenticate this firmware's browser binary or patch set.
AppleWebKit/Safari compatibility tokens do not establish a Safari/WebKit engine.

The LAN HTTP page is not a secure context. EME/VideoDecoder/serviceWorker/
SubtleCrypto were absent here, without settling HTTPS or catalog-app behavior.
Successful playback/audio/subtitles, resource limits, website-specific login/DRM,
TLS/CORS and long-running receiver behavior still require separate tests.

SmartTvA_API sampling found only exit/hasCapability; no vendor method/accessor
was invoked. Native-process Hello World, Home-launcher installation and DevView
enrollment remain unproved. The user awaits developer verification. No Android
or full-repository suites were run because those components were unchanged.

## Suggested next prompt

“Use the existing TV auto-update channel to implement a minimal remote-friendly
HTML5 player proof. Validate a small authorized H.264/AAC MP4, then native HLS;
report actual fetch/decode/audio/subtitle evidence separately. Keep Android
unchanged and preserve private session details.”
