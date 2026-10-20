# MSX local-address and status0 source trace

2026-10-20. The startup field format was already correct. Re-entering the same
local address is not a diagnosis. This review separates address parsing, protocol
selection and transport failure, using the official client plus retained actual
TV observations. No local-address retry, DNS change or vendor account call ran.

## Exact client examined

[Official MSX0.1.167 bundle](https://msx.benzac.de/js/tvx-app.min.js?v=0.1.167):
739,875bytes, SHA256
`2a0d2bcec29683ae2618900db4b6a15505e06a6f67dbac6580d8aabbef7f645a`.
The official [platform table](https://msx.benzac.de/info/?tab=PlatformSupport)
lists TP Vision/Vewd0.1.167. The exact installed launch URL, saved options and
native adapter remain unmeasured; public-client identity is not firmware identity.
Byte ranges below are zero-based, half-open UTF8 ranges of this captured artifact.

| Stage | Source | Behavior |
| --- | --- | --- |
| Startup field validator |375841–376776|IPv4/hostname plus optional numeric port1..65535; no private-IP ban or80/443 restriction. More than one colon, schemes and paths are invalid in this field. IPv6 literals are not supported here. |
| Raw field result |402645–403593|Returns the entered server string; does not strip a scheme or trim it into a new URL. |
| Bootstrap template |337493–337542|`http://{SERVER}/msx/start.json` |
| Bootstrap loader `aY` |522551–525608|Substitutes the server, preserving its port, then applies protocol selection. |
| Cache-query helper `_fc` |519633–520053|Adds numeric `v` and `t` query parameters to JSON requests. |
| XHR wrapper `b` |75682–78196|Optional native Ajax interception first; otherwise browser XHR. Completed status0 with empty response produces the reported error. |

Ordinary examples, using a documentation address rather than the user's address:

| Value entered in MSX | Effective conditions | Request before browser enforcement |
| --- | --- | --- |
| `192.168.0.10:8080` |HTTP MSX, padlock open, secure0/1/3|`http://192.168.0.10:8080/msx/start.json?v=0.1.167&t=…` |
| Same value |Padlock closed, or HTTPS MSX, or secure2/4|`https://192.168.0.10:8080/msx/start.json?v=0.1.167&t=…` |
| `cloud.example.test` |Padlock closed|`https://cloud.example.test/msx/start.json?v=0.1.167&t=…` |
| `http://192.168.0.10:8080` |Any|Rejected by this startup-field validator. |
| `localhost:8080` |HTTP allowed|Accepted, but addresses the TV itself when MSX runs on the TV. |

The [official setup example](https://msx.benzac.de/wiki/index.php?title=Setup_Start_Parameter)
also explicitly uses a private IPv4 with port8080. There is no special local
encoding needed instead of `IP:port` for the existing phone server.

## What selects HTTPS

The bootstrap loader forces HTTPS if the MSX page itself uses HTTPS, its
effective `secure` option is2or4, or its bootstrap padlock is closed. The padlock
can be forced on/disabled by the first two conditions. [Secure settings](https://msx.benzac.de/wiki/index.php?title=URL_Parameters)
also distinguish JSON/plugin updates from media updates; opening the padlock
does not override all application security settings.

Option lookup335464–335947 uses URL parameters, then saved settings, then init
options. Public shared config315994–320675 has14 `secure` assignments and all
are0. The captured ordinary web index supplies no secure override. This review
found no public Philips/TITAN-specific force-HTTPS branch. It would be inaccurate
to say that the installed TV definitely has secure2, or that HTTP Origin proves
secure0: Origin does not include the launch query or saved settings.

## What status0 means

The wrapper accepts HTTP2xx, and also status0 if response text is nonempty.
Otherwise it constructs `Server responded with status: <status>`. This is not
an HTTP0 response from our server, an IP-validation error, or a specific CORS
diagnosis. Browser TLS, network and access-policy failures can collapse to this
same observation. The normal JSON GET has no request body; ordinary preflight
cannot be assumed merely from the fact that its response is JSON.

Before constructing XHR, the client offers the request to optional
`window.TVXAjaxService.executeRequest`. Exact return `true` skips fallback XHR.
Whether the TV's parent app supplies that adapter, and its implementation, remain
unknown. A public JS trace cannot prove the behavior of an unavailable native shim.

## Why Cloudflare worked, and what the old LAN capture proves

| Observed route | Actual retained evidence | Conclusion |
| --- | --- | --- |
| Earlier LAN HTTP listener |Four completed TV connections: two TLS prefixes, two zero-byte idle timeouts, zero HTTP requests.|TLS was sent to an HTTP-only port in two connections. JSON routing and HTTP CORS were not reached. The two empty connections have no identified cause. Padlock-to-connection correlation was not recorded. |
| Trusted HTTPS tunnel |MSX bootstrap Origin was `http://msx.benzac.de`; plugin GET/ready/heartbeat and exact ping/label acknowledgements succeeded.|The public TLS route worked. It supplied trusted HTTPS and changed destination address space. |
| Phone receiver redirect |User saw the phone-served LUMA waiting page.|HTML navigation/frame delivery worked; this does not prove MSX startup JSON XHR or media playback. |

Cloudflare therefore changed more than spelling: it provided a public hostname
with working trusted TLS, then forwarded to our HTTP diagnostic adapter. The
adapter also served the measured CORS/query shape correctly. The first desktop
tunnel attempt exposed a separate bug in our route validator: MSX adds `v`/`t`,
which were initially rejected. That bug was fixed before TV success. It cannot
explain the earlier LAN connections that sent no HTTP at all.

One strong remaining explanation for the HTTP attempt is browser private-network
policy. The measured parent Origin was public HTTP. Upstream Chromium112 source
enables `BlockInsecurePrivateNetworkRequests` by default; for a nonsecure public
context it derives a block policy. Its network checker can reject a more-private
endpoint in `URLLoader::OnConnected`, after connecting but before normal HTTP
completion. This fits a public MSX XHR failing while direct private-page
navigation works. [Chromium feature defaults](https://github.com/chromium/chromium/blob/112.0.5615.49/content/public/common/content_features.cc#L158),
[policy derivation](https://github.com/chromium/chromium/blob/112.0.5615.49/content/browser/renderer_host/private_network_access_util.cc#L64),
[network check](https://github.com/chromium/chromium/blob/112.0.5615.49/services/network/url_loader.cc#L1220).

This is an inference, not a measured TV error. The Philips engine's exact build,
feature flags, origin trials and native interception are unavailable. Google's
[primary explanation](https://developer.chrome.com/blog/private-network-access-update)
describes this distinction and private-origin hosting as an alternative. CORS
headers alone cannot fix a rejection before HTTP. The current Android listener
already handles restricted CORS/PNA OPTIONS; absence of an incoming request is
not evidence that it rejected that preflight.

## Next discriminating observation

The public client exposes the narrower fixed action
`player:commit:info:framework`. Its dispatch returns framework version/settings,
including effective `secure`; it does not need `info:extended` or native system
information getters. A prepared diagnostic can retain only version and secure,
discard all other fields, and authenticate replies against its MSX parent and
per-load request ID. Reopening the already working diagnostic tile is sufficient;
there is no need to re-enter the local address to inspect those settings.

This can settle the effective protocol option, not every browser failure. A
definitive HTTP-policy diagnosis still requires the TV's specific network error
or a tightly correlated observation of its request. No claim of full LAN support
or a unique cause for the zero-byte attempts follows from this source review.

The helper is now in the probe and displays only `MSX framework … | secure=…`.
Nineteen fake-parent helper checks, ten exact-source contract checks and one
real Chromium framed-probe fixture pass. The browser fixture forwards loopback
requests through its harness and uses a simulated MSX parent: it tests DOM,
message correlation and unchanged report acceptance, not browser LAN policy.
Framework version is distinct from the application version. No TV query result
has been collected. The user reports the earlier diagnostic is not saved, so
reopening that tile must not be assumed possible.

## Public redirect alternative

[httpbin](https://httpbin.org/) provides `/redirect-to?url=...`. A live GET with
fictional private target `http://192.168.0.10:8080/msx/connect.html` returned302
with that exact Location. Curl did not follow it or contact that target. A
documentation-address target also returned302. [httpbingo](https://httpbingo.org/)
documents the same endpoint, but its public instance returned403 and listed only
example.com/example.net/example.org/httpbingo.org as allowed destinations.

An encoded URL therefore supplies a public redirect-only candidate without a
new tunnel or a saved short link. These are public testing services with no
permanence guarantee. This does not itself provide the MSX startup JSON or make
a full query URL valid in the server field. For a page/plugin navigation it may
help load the private receiver. For a startup JSON XHR, the redirect still
targets the private network; Chromium re-evaluates the target address space
after redirects. [Pinned redirect handling](https://github.com/chromium/chromium/blob/112.0.5615.49/services/network/cors/cors_url_loader.cc#L579).
Actual TV acceptance of the public redirect has not been tested.

## Verification and private evidence

29 offline assertions exercised exact selected validator/URL/XHR declarations
with fake settings, timers and XHR: network0, native0. REA6.7.0 parsed9,394bytes
of selected declarations into2,930 AST nodes with zero parse failures; semantic
coverage is unknown. An independent2,514byte transport analysis parsed831 nodes,
also with unknown semantic coverage. Neither run is whole-app or TV execution.
Five pinned Chromium reference files are hashed locally; none is extracted
Philips firmware.

Ignored evidence: `.local/tv-hello/msx-status0-source/` contains captured public
sources, byte provenance, fixtures, REA receipts and transport/platform summaries.
Earlier private TV/tunnel captures remain under `firmware-deep/`; no capability,
private routing address, credential or active media URL is included here.
