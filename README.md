# frameXTV Native Cast

A minimal Manifest V3 extension that uses frameXTV's **existing** Google Cast
SDK to send an exposed HLS, DASH, or MP4/WebM URL to a receiver. The receiver
retrieves the media; the browser supplies remote controls. Actual frameXTV
playback on a physical Chromecast/Google TV remains **unverified**.

The site already contains native Cast code. This extension preserves its visible
Cast control and adds a small fallback beside the video only when that control
is absent. It supports the target origin's `/watch/` and `/embed/` pages,
including matching same-origin frames. Cross-origin backup players are outside
its permission scope.

## Install locally

Use Node.js with npm, then run:

```sh
npm ci
npm run build
```

Open `chrome://extensions` in Chrome, enable **Developer mode**, choose
**Load unpacked**, and select this repository's **`dist/extension`** directory.
Reload the target page after loading or rebuilding the extension:

```text
https://framextv.xyz/watch/284833/1/1
```

Use the site's native Cast control if it appears. Otherwise, the extension's
Cast button opens the SDK's device picker. Local playback pauses after a
successful receiver LOAD. The fallback supplies pause/resume, seek, volume,
VTT subtitle selection, and disconnect. Returning to local playback leaves the
video paused so the user chooses when to resume.

Load `dist/extension`, not `src/extension` or `dist/test-extension`. The latter
is a separate generated build restricted to local automated fixtures.

## Checks and device testing

```sh
npx playwright install chromium
npm run check
```

Unit tests and actual loaded-MV3 browser fixtures exercise extraction, dynamic
players, same-origin embeds, navigation, Cast wiring, errors, and cleanup. Cast
itself is mocked in those fixtures; they do not establish receiver playback.

For an ordinary web sender using the same Cast adapter and an openly licensed
public MP4, run:

```sh
npm run build
npm run demo
```

Open `https://localhost:4174/cast-demo` in Chrome and accept the warning for this
generated local development certificate. This is a separate demo page, not an
extension context; only that page loads Google's remote sender SDK. SDK readiness
and receiver success must be observed in the browser and on the TV. Follow the
[physical-device checklist](tests/manual-chromecast.md) and record the result.

`npm run smoke:cast` checks live SDK readiness in an isolated installed Chrome
profile. It passed over local HTTPS with zero media requests; no device was
selected. The public sample's HEAD returned 200, `video/mp4`, byte ranges and
CORS `*`. Neither check establishes receiver playback.

## Boundaries

- All extension executable code is packaged locally. It has no SDK loader,
  telemetry, cookie access, broad host permissions, or media relay.
- Missing/not-ready SDK, unsupported sources, encrypted playback, and failed
  device loads produce an error without pausing ordinary local playback.
- A browser `blob:` URL is not sent to the receiver. A declared child
  `<source>` may expose the underlying authorized HTTP(S) manifest instead.
- Source access, CORS, expiry, codecs, subtitles, and receiver reachability remain
  provider/device constraints. Finding a media URL does not prove compatibility.
- Target automation may be blanked or disrupted by anti-debugging. No
  authentication, DRM, or anti-debugging bypass is included.
- Chrome's menu **Cast → Sources → Cast tab** is a manual mirroring fallback.
  It is browser-dependent and is not this extension's direct-playback mode.

See the [handoff](docs/agent/handoff.md), [investigation](docs/agent/investigation.md),
[playback findings](docs/agent/playback-analysis.md), and
[Cast feasibility](docs/agent/chromecast-feasibility.md) for evidence and remaining
questions. Keep live source URLs, cookies, credentials, and media out of Git.
