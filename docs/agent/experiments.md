# Experiments

## E01 — Public target reachability

- **Timestamp/environment:** 2026-10-18 07:02 America/Sao_Paulo; macOS shell,
  curl; initial network sandbox, followed by permitted network access.
- **Hypothesis:** The requested page is publicly reachable without a session.
- **Method:** HTTPS HEAD with 15-second connection/30-second total deadline and
  no followed redirects; compare with a public HTTPS control.
- **Observed:** Sandboxed target request exited 6 (DNS). Permitted target and
  control requests returned HTTP/2 200. Target response type was HTML.
- **Evidence:** Commands `curl --proto '=https' --connect-timeout 15 --max-time
  30 --max-redirs 0 -sS -I https://framextv.xyz/watch/284833/1/1`; control origin
  `https://example.com/`. Raw response headers were not committed.
- **Confidence:** High for reachability at this timestamp.
- **Conclusion:** Initial DNS failure was environmental; it was not site failure.
- **Next:** Inspect delivered HTML/static assets; record browser behavior separately.

## E02 — Passive frontend/playback/Cast mapping

- **Timestamp/environment:** 2026-10-18 approximately 07:03–07:05
  America/Sao_Paulo; curl, Python 3, local UTF-8 static inspection.
- **Hypothesis:** The player is dynamically loaded and its existing controls/source
  paths can support integration without runtime anti-debugging changes.
- **Method:** GET public HTML and five frontend chunks into ignored `.local/recon/`.
  Inspect bounded source-marker contexts and SHA-256. No APIs/media requested.
- **Observed:** React/Vite SPA; same-origin watch/embed nesting; HTML5 video with
  URL-bearing child source; hls.js 1.6.16; existing Cast SDK/context/control and
  remote load/session logic; enabled disable-devtool library initialization.
- **Evidence:** Hash/size inventory in [investigation](investigation.md); source
  offsets and markers in [architecture](site-architecture.md),
  [playback](playback-analysis.md), [anti-debugging](anti-debugging.md).
- **Confidence:** High for delivered code paths; no claim of actual episode
  playback or device acceptance. A broad regex over minified code ran too slowly
  and was interrupted; bounded marker searches replaced it.
- **Conclusion:** Reuse ordinary source DOM in supported same-origin frames and
  preserve existing Cast UI. Live receiver feasibility is still unknown.
- **Next:** Clean-profile browser baseline and deterministic extension fixtures.

## E03 — Reusable sanitized HTTP inventory

- **Timestamp/environment:** 2026-10-18 07:04:21 America/Sao_Paulo;
  Node.js, permitted network access.
- **Hypothesis:** Architecture evidence can be refreshed without persisting
  session data or accidentally downloading media.
- **Method:** `node --check scripts/recon-http.mjs`, `node scripts/recon-http.mjs
  --help`, and `node scripts/recon-http.mjs --assets` against the public page.
- **Observed:** Syntax/help checks passed; the live script reported status 200
  and no error. Metadata and only HTML-linked JS/CSS were written under ignored
  `.local/recon-http/`.
- **Evidence:** `scripts/recon-http.mjs`; `.local/recon-http/report.json`.
- **Confidence:** High for this bounded run. This script is not a media or
  receiver test and does not recursively inspect lazy chunks.
- **Conclusion:** Static recon is reproducible with metadata sanitization on by
  default. Raw assets remain local-only.
- **Next:** Keep live browser/CDP findings separate from static findings.

## E04 — Clean automated Chromium baseline

- **Timestamp/environment:** 2026-10-18 07:04:52 America/Sao_Paulo;
  Chromium 156.0.8078.4, Playwright clean profile, headless, DevTools UI false,
  runtime overrides false.
- **Hypothesis:** An unmodified fresh visit can reveal source/player requests and
  whether the reported disruption occurs under browser automation.
- **Method:** Root agent ran `scripts/recon-browser.mjs` against the public page;
  recorded only sanitized response/navigation metadata and console type counts.
- **Observed:** Watch/embed/assets and stream API loaded with 200 responses.
  Worker origin returned HLS/MP4-typed responses; VTT/SRT types also appeared.
  Final top-level document was `about:blank`, with no remaining video or SDK.
  Console counts: clear 69, log 70, table 19, warning 3, info 2.
- **Evidence:** `.local/recon/browser-metadata.json` (local-only); browser method
  in `scripts/recon-browser.mjs`. No bodies or raw HAR retained.
- **Confidence:** High for captured requests, counts and final navigation;
  moderate for attribution to the statically identified library; triggering
  detector unknown. Automation/CDP itself may affect console-based checks.
- **Conclusion:** Console clearing and blank-page disruption reproduce under
  this automated environment. Opening actual DevTools or detecting Anti Anti
  Debug was not tested. HTTP 200/media MIME is not decoded playback or Cast success.
- **Next:** Independent stable headed-Chrome baseline; capture a DOM snapshot
  before navigation if possible without changing target runtime behavior.

## E05 — Independent headed Chrome baseline

- **Timestamp/environment:** 2026-10-18 07:05:53 America/Sao_Paulo;
  Chrome 155.0.8059.39, Playwright clean profile, headed, DevTools UI false,
  runtime overrides false.
- **Hypothesis:** The first disruption may depend on the headless/development
  Chromium build rather than the site's behavior under another browser mode.
- **Method:** Root agent repeated the same sanitized browser investigation with
  installed stable Chrome and a fresh profile; no target modifications.
- **Observed:** Watch/embed and stream API returned 200; final top-level page was
  again `about:blank`. Console counts: clear 43, log 44, table 14, warning 6,
  info 2. Final DOM contained neither video nor Cast SDK.
- **Evidence:** `.local/recon/browser-chrome-headed.json` (local-only);
  `scripts/recon-browser.mjs`.
- **Confidence:** High for repeated disruption in these two automated modes;
  triggering detector and the role of CDP remain unisolated.
- **Conclusion:** Switching to headed stable Chrome did not resolve automation
  disruption. No inference about manual DevTools or extension fingerprinting.
- **Next:** Validate extension behavior on deterministic fixtures and perform an
  uninstrumented user/physical-receiver acceptance session separately.
