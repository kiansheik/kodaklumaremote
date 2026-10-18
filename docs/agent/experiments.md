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

## E06 — MV3 architecture and deterministic integration

- **Timestamp/environment:** 2026-10-18 approximately 07:02–07:17
  America/Sao_Paulo; macOS, Node 24.15.0, Playwright 1.64.0, Chromium 156.
- **Hypothesis:** Packaged MAIN-world code can reuse the site's existing CAF
  without loading remote executable code or changing normal inactive playback.
- **Method:** Compared official Chrome/Google docs (linked in feasibility);
  built separate production/test manifests; loaded actual MV3 in clean Chromium
  profiles against loopback synthetic DOM and fake CAF. Node unit tests exercise
  URL/track validation and session races. No real media downloaded in fixtures.
- **Observed:** Initial browser run exposed hidden-host CSS, ended-state cleanup
  and source-change race issues; fixed them and added targeted regressions.
  Source mutation must be tracked explicitly: with preload-none/404 fixtures,
  `emptied`/`loadedmetadata` do not reliably fire when the declaration changes.
  Tests cover no duplicate insertion, native-control preservation, frames,
  remote commands/subtitles, late loads, sanitized errors, and cleanup.
- **Evidence:** `src/extension/`, `src/shared/media.js`, `src/cast/session.js`,
  `tests/`, `scripts/build.mjs`; final command/result in handoff.
- **Confidence:** High for tested local DOM/session wiring; fake CAF cannot
  establish actual discovery, network reachability, decode or TV output.
- **Conclusion:** Existing-page SDK reuse is implemented without an extension
  SDK loader; source/navigation cancellation is necessary for correct episodes.
- **Next:** Public actual-SDK initialization, then physical-device checklist.

## E07 — Public Cast SDK readiness over local HTTPS

- **Timestamp/environment:** 2026-10-18 07:15:24 America/Sao_Paulo;
  installed Chrome 155.0.8059.39, managed clean profile, Playwright 1.64.0.
- **Hypothesis:** An ordinary HTTPS sender can initialize the actual Google SDK
  independently of target anti-debugging and extension CSP.
- **Method:** `npm run build`; `node scripts/public-cast-smoke.mjs`; start
  loopback HTTPS demo with generated ignored self-signed development TLS key;
  automation accepts only its local certificate error, waits for SDK readiness,
  then closes. No Cast button clicked or media fetched.
- **Observed:** Final smoke exit0: `sdkReady:true`, `secureContext:true`, zero
  media requests, no device selection. Initial HTTP experiment loaded bootstrap
  but SDK selected HTTP gstatic subresources blocked by CSP/network. Playwright
  defaults disable MediaRouter; excluded the exact pinned disabled-features
  switch while retaining a managed isolated profile. Discarding all defaults
  was rejected after profile/startup problems; stopped only the two owned Node
  smoke processes, returned to managed launch.
- **Evidence:** `scripts/public-cast-smoke.mjs`, `scripts/demo-server.mjs`,
  `tests/fixtures/cast-demo.html`, `src/cast/demo.js` (same shared adapter).
- **Confidence:** High for SDK initialization only. Development HTTPS is not a
  published sender or a receiver acceptance test.
- **Conclusion:** No extension remote-code exception is needed. HTTPS and native
  media-router support matter when evaluating live SDK behavior under automation.
- **Next:** Select physical receiver and verify actual public picture/audio.

## E08 — Public MP4 identity and reachability

- **Timestamp/environment:** 2026-10-18 approximately 07:16 America/Sao_Paulo;
  Node HTTPS HEAD, official Google Cast source catalog and Blender project page.
- **Hypothesis:** A public, openly licensed sample can isolate receiver behavior.
- **Method:** Header-only requests; inspect official
  [CastVideos source root](https://github.com/googlecast/CastVideos-chrome/blob/master/CastVideos.js)
  and [media catalog](https://github.com/googlecast/CastVideos-chrome/blob/master/media.js).
  Film license: [Blender Big Buck Bunny](https://peach.blender.org/about/), CC BY3.0.
- **Observed:** Old gtv-videos-bucket sample and CastVideos MP4 returned403;
  guessed Blender unzipped M4V returned404. A Shaka BBB DASH test returned200
  but was not selected. The **current official Google sample**
  `https://storage.googleapis.com/cpe-sample-media/content/big_buck_bunny/prog/big_buck_bunny_prog.mp4`
  returned200, `video/mp4`, `Accept-Ranges: bytes`, CORS `*`, length158008374.
  No video body saved. Demo updated to that source.
- **Evidence:** `tests/fixtures/cast-demo.html`; official catalog URLs above;
  header-only command recorded in session handoff.
- **Confidence:** High for HTTP availability/MIME/header values at this time;
  does not prove media codec, playable output or receiver reachability.
- **Conclusion:** Do not assume a familiar test URL remains available. Use the
  current source and preserve verification limits.
- **Next:** Physical receiver acceptance; record version/model/source class.

## E09 — Live extension injection with transient DOM sampling

- **Timestamp/environment:** Report captured 2026-10-18 07:18:10
  America/Sao_Paulo; Chromium 156.0.8078.4, headless clean ephemeral persistent
  profile, built site-only MV3 extension enabled, DevTools UI false. Runtime
  changes were the requested extension only; no anti-debugging patches.
- **Hypothesis:** Sampling before the final blank document can verify extension
  loading in the real watch/embed frames and possibly expose a declared source.
- **Method:** Added opt-in `SAMPLE_DOM=1` and `EXTENSION_DIR` support to
  `scripts/recon-browser.mjs`, preserving the existing default baseline flow.
  Ran `SAMPLE_DOM=1 EXTENSION_DIR=dist/extension
  RECON_OUTPUT=.local/recon/browser-extension.json node scripts/recon-browser.mjs`
  with permitted network access. Seventeen per-frame snapshots at nominal
  500-ms intervals covered eight seconds after DOMContentLoaded. Samples retain
  route category, booleans, media source kind/MIME and dimensions, with no raw
  source URL, title, query values or device name.
- **Observed:** Watch-frame extension marker and Cast SDK appeared by ~0.5s;
  same-origin embed marker/SDK appeared by ~1s. The embed became `about:blank`
  by ~1.5s and the top level after ~2.5s. No sampled video/source, native Cast
  button or mounted extension host appeared. This run did not observe a completed
  stream API request. Final console counts: clear27, log28, table10, warning8,
  info3; final top-level page blank. No Cast action was requested.
- **Evidence:** `scripts/recon-browser.mjs` and ignored
  `.local/recon/browser-extension.json`; `node --check scripts/recon-browser.mjs`
  passed and the live probe exited0.
- **Confidence:** High for extension execution in both supported real frames;
  no evidence of live visible-button/media-extraction or receiver success.
  Timing differs from earlier visits and does not establish an extension effect;
  network variation, sampling and automation remain confounders.
- **Conclusion:** Built extension injection works on the actual origin/frame
  topology, but page disruption still prevents real-player acceptance. The
  integration remains dependent on ordinary authorized playback becoming usable.
- **Next:** Uninstrumented Chrome/player acceptance and physical receiver test;
  retain deterministic fixture coverage for controls and extraction meanwhile.

## E10 — User Chrome, Philips discovery and YouTube hardware control

- **Timestamp/environment:** 2026-10-18 approximately07:27–07:46
  America/Sao_Paulo; user's ordinary Chrome profile via native UI, existing
  unpacked extension from `dist/extension`; Philips43PFG6910/78.
- **Hypothesis:** The earlier generic extension error may precede media LOAD;
  native Chrome discovery and a supported public site can isolate the stage.
- **Method:** Read installed path and live player UI. Native Chrome menu Cast,
  then YouTube's Play on TV for Blender's public
  [Big Buck Bunny film](https://www.youtube.com/watch?v=aqz-KE-bpKQ). No DevTools,
  runtime overrides, raw media URL extraction, or TV firmware settings changed.
- **Observed:** Player/extension were visible in ordinary Chrome. Initial native
  picker said “No devices found”; user later reported TV had been offline. After
  reconnect, exact target was discovered but disabled for frameXTV, “Available
  for specific video sites”. YouTube enabled the target, connected and reported
  playing; user explicitly confirmed physical TV picture and sound. Sender
  pause, +5s seek and resume changed remote UI state. Stopped the task's own
  YouTube route and observed local paused playback afterward. Cast tab was the
  selected native source; no screen mirroring was started.
- **Evidence:** Native UI observations and user confirmation in session;
  concise record [TV evidence](philips-tv.md) and manual acceptance table. No raw
  AX/browser screenshots or personal browser content saved to repository.
- **Confidence:** High for discovery/UI and user-confirmed YouTube picture/audio;
  no direct frameXTV, generic Default Media Receiver or phone-off test success.
- **Conclusion:** Site-specific receiver playback works. Generic media routing
  is separately unavailable in the observed mode; Chromecast SDK readiness/media
  codecs should not be blamed from that session failure alone.
- **Next:** Identify any independent general media receiver, preserve final
  phone-power-off requirement, and validate actual URL retrieval only if supported.

## E11 — Sanitized error-stage correction

- **Timestamp/environment:** 2026-10-18 approximately07:35–07:43
  America/Sao_Paulo; Node24/Playwright1.64; existing user Chrome extension.
- **Hypothesis:** Separate connection/discovery from media errors without exposing
  SDK descriptions or source URLs, and retain correct UI wording after rejection.
- **Method:** Read official [error enum](https://developers.google.com/cast/docs/reference/web_sender/chrome.cast#ErrorCode),
  [requestSession](https://developers.google.com/cast/docs/reference/web_sender/cast.framework.CastContext#requestSession),
  and [loadMedia](https://developers.google.com/cast/docs/reference/web_sender/cast.framework.CastSession#loadMedia).
  Update adapter stage mapping/UI fixed-message allowlist; test string/object
  errors, cancellation, unavailable receiver, readiness, timeout, media rejection.
- **Observed:** `npm run check` passed build +30 unit +16 browser tests. Built
  extension reloaded in the user's verified unpacked instance, player refreshed;
  live retry now says it could not connect to a Cast receiver. Generic LOAD/CORS
  claims no longer replace that error. No raw SDK detail shown.
- **Evidence:** `src/cast/session.js`, `src/extension/player.js`, session/browser
  tests and fake CAF regression; native Chrome showed Reloaded and updated error.
- **Confidence:** High for tested classifications and actual updated message;
  exact raw SDK failure was not collected and no media LOAD was established.
- **Conclusion:** Connection failures retain their stage; an unavailable receiver
  no longer implies a stream compatibility failure.
- **Next:** Receiver capabilities before building another sender frontend.

## E12 — Bounded read-only direct-renderer discovery

- **Timestamp/environment:** 2026-10-18 07:48:07 initial probe,07:49:29 final
  America/Sao_Paulo; Node UDP/HTTP on one private physical IPv4 interface. User
  confirmed normal Home/YouTube TV mode before final probe.
- **Hypothesis:** Philips may expose UPnP MediaRenderer/AVTransport for direct
  URL retrieval without a phone-hosted relay or continuing screen stream.
- **Method:** `node scripts/discover-tv.mjs`; three specific SSDP search types,
  final run repeats once after1s within4.5s; no port scan. Only private numeric
  responder description origins accepted, short XML GETs capped256KiB, no redirects
  or credential use; exact-model/manufacturer gate and sanitized output. No SOAP
  playback/control requests and no media requests. Three parser/boundary tests pass.
- **Observed:** Initial permitted probe returned no accepted response. Final
  confirmed-normal-mode run received and read **one exact Philips43PFG6910/78
  device description**, no discovery/description errors. Recognized service list
  and AVTransport action list were empty; no SCPD or media/control fetch followed.
  The protocol type of that SSDP response was not retained by the sanitized report.
- **Evidence:** `scripts/discover-tv.mjs`, `tests/discovery.test.mjs`, ignored
  `.local/tv-discovery.json`. Report contains no addresses, UDNs, serials or URLs.
- **Confidence:** High for received matching description and absent recognized
  service entries in it. Not proof of all firmware/mode/network capabilities.
- **Conclusion:** SSDP reachability is demonstrated; a direct DLNA renderer has
  not been established. Combined with Chrome/YouTube, this is consistent with an
  app-specific receiver, but do not label the protocol definitively DIAL from it.
- **Next:** Check for a supported TV-side generic receiver app/mode, or validate
  against an actual general Cast/DLNA receiver before the mobile implementation.
