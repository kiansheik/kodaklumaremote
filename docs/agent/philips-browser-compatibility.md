# Philips browser implementation and compatibility

Checked 2026-10-20 on Philips43PFG6910/78, platformTPM256L, firmware
`TPM256L_V251.003.105.021`. Current scope is understanding the browser,
applications and websites it can run. A native-code milestone is not required
for this discovery; no vulnerability investigation is part of it.

## Actual TV browser evidence

The standalone [probe](../../src/tv-bridge-probe/index.html) ran on the TV through
the [local server](../../scripts/tv-hello-server.mjs). Sanitized runtime evidence
is retained in ignored `.local/tv-hello/browser-compatibility.json`; it contains
parsed version fields, fixed capability results and counters, not the full user
agent or private capability URL.

| Observed field | Actual browser report | Interpretation |
| --- | --- | --- |
| Chrome token | `112.0.4147.62` | Vendor-reported compatibility token; exact binary/build provenance unverified |
| AppleWebKit token | `537.36` | Compatibility token; does not establish Safari/WebKit |
| OMI / Opera tokens | `4.24` / `46.0.2207.0` | Vewd Core / Opera compatibility versions |
| TitanOS / NETTV / SmartTvA tokens | `3.0` / `4.6.0.7` / `5.0.0` | Parsed vendor version fields, not app-install privileges |
| Platform | `Linux x86_64` | Browser-reported string, **not** verified CPU/userspace ABI |
| Viewport | 1920×945 CSS pixels | Available page area in this browser context |
| Screen / pixel ratio | 1920×1080 / 1 | Reported screen geometry |
| Secure / isolated context | false / false | This probe uses ordinary LAN HTTP |

The OMI token identifies this as a **Vewd Core4.24-advertising** browser.
[TiVo/Vewd's primary guide](https://developers.tivo.com/smart-tv/core-developer-guide#user-agent-string)
defines OMI as the Vewd Core version and OPR as the Opera Desktop version;
Vewd Core is based on Chromium. The best-supported classification is therefore
a vendor Vewd/Chromium-derived TV browser;
Chromium uses [Blink](https://www.chromium.org/blink/). This is an inference from
platform documentation and browser tokens, not inspection of its executable.
Titan's [TPM256L platform table](https://docs.titanos.tv/platform-versions) lists
Chromium122, while this browser reports Chrome112. Preserve that discrepancy:
the table, vendor token, firmware and browser/app context may differ. Do not
substitute122 for the observed token or map the full token to a stock Chrome
release. Titan itself recommends [parsing needed fields](https://docs.titanos.tv/user-agents-specifications)
rather than treating a complete user agent as an exact identity. Vewd's generic
revision table associates4.24 with Chromium114, adding another generic reference
that does not authenticate this firmware's executable. The exact internal branch,
backports and reason for differing versions remain unknown.

Remote updates are now proved on the actual TV: the browser fetched the changed
page and reported `ready` with matching loaded/served asset revisions. The
same-origin acknowledgement permits only a fixed page reload; it is not an
arbitrary script or native-command channel. The first compatibility capture recorded GET2/ready2/heartbeat1. After another
same-URL listener restart, the five additional vendor-token fields arrived in a
new GET1/ready1/heartbeat1 capture, revision matched and rejected0. Counters are
per listener instance; a newer capture can change them.

The deeper follow-up acquired the complete current-version firmware container
and traced pinned upstream implementations. See the
[firmware/server report](philips-firmware-implementation.md). A later fixed
presence-only sample adds five networking checks: TCPServerSocket, TCPSocket,
UDPSocket and WebTransport absent; RTCPeerConnection present, alongside the
already observed WebSocket. No constructor or connection was invoked. Privileged
vendor app contexts remain unknown.

## Browser and media declarations

The initial13 fixed API checks completed (18 including the later network sample). Fetch, Promise, WebAssembly, WebSocket,
MediaSource, WebGL/WebGL2 constructors, localStorage and IndexedDB were present.
EME's `requestMediaKeySystemAccess`, WebCodecs' `VideoDecoder`, serviceWorker and
SubtleCrypto were absent in this HTTP context. Storage was not read/written;
no graphics context, decoder or DRM access request was created.

All12 fixed media checks completed:

| Type declarations | `canPlayType` | `MediaSource.isTypeSupported` |
| --- | --- | --- |
| H.264 baseline/high, HEVC, VP8, VP9, AV1, AAC, Opus, AC-3, E-AC-3 | probably | true |
| HLS manifest | maybe | false |
| DASH manifest | maybe | false |

Exact MIME/codec strings live in the probe source. These are declarations,
**not decoding or playback proof**. MSE checks manifest MIME types differently
from encoded segment types: false for an HLS/DASH manifest does not itself rule
out a JavaScript player using supported MP4/WebM segments. Codec profiles,
resource use, audio, subtitles and sustained playback still need a real stream.

[Secure-context rules](https://www.w3.org/TR/secure-contexts/#is-origin-trustworthy)
distinguish ordinary private-network HTTP from HTTPS and loopback. EME is
[secure-context-only](https://w3c.github.io/encrypted-media/#navigator-extension-requestmediakeysystemaccess).
Absence here does not establish absence of DRM/CDMs in an HTTPS or catalog-app
context. HTTPS also introduces separate certificate, CORS and
[mixed-content](https://www.w3.org/TR/mixed-content/) constraints.

## Public SDK evidence and its limits

The public [Titan SDK](https://sdk.titanos.tv/sdk/sdk.js) was fetched as text,
138366bytes, SHA-256
`c8c2aa65846491aecad4f2c171bce4f8607ac707174cc2b322b1b10d0f39f789`.
It was never executed or injected into the TV. Ignored
`.local/tvhello/browser-analysis/{artifact,excerpts,rea-focused,report}.json`
retain provenance and original byte ranges.

REA6.7.0 statically inspected six selected declarations (`Q`, `q`, `Y`, `Ke`,
`Xe`, `ri`): one5054-byte input, no parse failure,424 semantic nodes,
232 relations and161 unknowns. Semantic coverage remains **unknown**;
surrounding helpers, enums and base classes were omitted deliberately.
Direct source observations are limited to platform detection, generic fallback
defaults, MIME-based media hints and device-info formatting. In particular,
fallback HLS/DASH/DRM flags are defaults, not TV measurements. SDK platform
classification does not establish privileged bridge access. Its
[documentation](https://docs.titanos.tv/titan-sdk) warns device information can
be incomplete outside DevView.

Actual `SmartTvA_API` sampling found only `exit` and `hasCapability` functions;
neither displayed a `[native code]` marker. The sample is bounded and invokes no
method/accessor. `loadApp`, `enableDevtools` and `oipfObjectFactory` were absent
under the probe's descriptor-only checks. Neither presence, absence nor function
text establishes a native installer or command-execution path.

## Practical applications and next proof

Hosted HTML5 interfaces, remote-friendly dashboards and video-player UIs fit
the [documented Titan application model](https://docs.titanos.tv/introduction).
A URL can run a web application; it does not install an Android APK or Kodi.
Site compatibility depends on actual JS/CSS features, TV input/layout, network
policies, media packaging and any service-specific authentication/DRM rules.

The narrow playback progression is an authorized H.264/AAC progressive MP4,
then native HLS, then an MSE player if needed. Titan's
[media specifications](https://docs.titanos.tv/media-specifications) document
these routes and out-of-band subtitles, providing test candidates rather than
acceptance for this exact browser. Current discovery fetched no media and proves
no fresh video/audio/subtitle rendering, player performance or phone-off playback.

## Later active diagnostics

The [live control follow-up](philips-probe-control.md) supersedes the initial
presence-only boundary: two fixed commands are TV-acknowledged, selected external/
vendor-registry getters are read, registry names and CSManager representation
metadata captured, and the documented HbbTV typed-object route sampled. No opaque
IPC, service-activating relay getter or TCP bind was called. A later one-time
zero-argument registered CSManager construction failed with TypeError/no instance.
The original compatibility/media results above remain declarations, not playback.
