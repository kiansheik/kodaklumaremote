# Philips sideload and local configuration follow-up

2026-10-20. Read-only research alongside the separate remote/player work. No TV
navigation, DNS/router change, enrollment request, vendor message, firmware
installation or Android edit occurred in this research lane. A native-process
Hello World remains unproved. The existing MSX web execution result remains
separate from app-context/native execution.

## Resumed local DevView review

Fresh public GETs on2026-10-20 match the DevView bundle hash below and the
[gateway source](https://sdk.titanos.tv/gateway/gateway.js) hash recorded in the
[gateway review](philips-ghidra-reassessment.md). Public HTTP/HTTPS DevView entries
both return200/837bytes, with no redirect or HSTS header in those responses.
This does not establish the installed catalog entry's scheme, cached policies or
native binding grants. No supported accountless local-backend mode was found:
`localhost` affects the developer domain label; `scriptSource` selects fixed SDK
sources; the compiled Partner API target remains unchanged.

The current [App Control contract](https://docs.titanos.tv/titan-sdk-app-control)
explicitly requires the calling app to be whitelisted. Its
[device allowlisting guide](https://docs.titanos.tv/device-allowlisting) instead
describes application-side model/UA segmentation, not a firmware authorization
grant. The [documented DevView route](https://docs.titanos.tv/devviewinstall)
still uses approved Sandbox candidates and device pairing.

The exact installed DevView catalog URL remains unavailable. The refreshed
gateway contains no DevView-name literal and obtains launch metadata at runtime;
no catalog/backend request was made. Retained device information supplies no app
inventory, and no readable firmware catalog component was extracted. Prepared
own HTML and concrete test conditions are retained privately in
`.local/tv-hello/devview-local-revisit/NEXT_CONTEXT_TEST.md`: compare presence-only
results at an owned URL with the same payload in an actual DevView app context.
A local content-routing test additionally needs the catalog host/scheme/path,
normal-port delivery and, for HTTPS, trusted service identity. Public HTTP200
alone settles none of those conditions. No DNS/router/device/account change or
listener deployment occurred in this research lane.

Fresh REA6.7.0 parsed eight exact disjoint declarations/1,735 assembled bytes,
410 AST nodes and0 parse failures;226 semantic nodes/157 relations/69 unknowns,
coverage unknown/untruncated. Evidence
`ev_f417c02b33834bcb23bfbc5b855b056be734f1f3d089743163a1b3ec0e24336c`.
Nine isolated switch fixtures and two owned-page presence fixtures pass with
0 real native/getter/network calls. The complete bundle was not executed.
The sandbox cleanup-inspection failure, confirmed exited owned worker and
successful approved rerun are retained with the fresh source/REA receipts in
`.local/tv-hello/devview-local-revisit/summary.json`. These checks do not validate
firmware permission or device behavior.

## Actual DevView configuration switches

Source: [public DevView bundle](https://devview.titanos.tv/assets/index-BQQTuhjP.js),
679,841 bytes, SHA256
`681019982612ca30cb3b89826364519b6af194d7301f6cad8249dbbbe4c332c8`.
These observations apply to this captured public client; its exact revision and
launch URL inside the TV's catalog entry remain unverified.

| Switch/state | Source behavior | Boundary |
| --- | --- | --- |
| Build environment `Ap` | Literally returns `prod01` | Changing the hostname does not change this compiled helper |
| `scriptSource` URL parameter | Accepts only `sdk`, `sdk-dev01`, `npm`, `legacy`, `compat`; otherwise defaults to `sdk` in this build | Selects a fixed SDK delivery source, not an arbitrary script URL or enrollment mode |
| Domain selector `TT`/`ET` | Classifies hostname containing `dev01` or `localhost`; navigates between the two vendor DevView hosts while preserving scheme/path/query | Separate from `Ap`; not a local backend configuration |
| `TITAN_SDK_PARAMS` | Source-selector UI writes `{debug:false}` for modern sources/NPM, or removes the key for legacy/compat | SDK options/logging state, not the firmware debugger switch |
| `debug-storage.debugMode` | Persists the UI's selected boolean | Loading a saved `true` value does not itself call the native switch |
| Debug callback | Calls ambient `window.enableDevtools(0\|1)` if present, then updates UI state | Requires a native binding in that document; its firmware authorization/persistence implementation is unavailable |

The domain/source selector UI is shown for `DEVICE_QA` users. The debug control
checks native function presence separately. The API client still targets fixed
`https://partners.titanos.tv/tv/api` with credentials included. None of these
switches supplies real partner approval, device association or a server session.
Cached UI state and backend authorization remain distinct as documented in the
[pairing report](philips-devview-protocol.md).

The captured Titan SDK also supports `gatewayUrl` and reads `TITAN_SDK_PARAMS`
from the window or localStorage. That can select a gateway document; its
WindowWrapper still consumes globals supplied by its own runtime. It does not
create absent native methods. See the [gateway source review](philips-ghidra-reassessment.md).
[Titan's storage contract](https://docs.titanos.tv/cookies) describes localStorage
as origin-specific; values stored in our MSX/plugin origin are not DevView's
origin storage.

## What a local DNS change would establish

DNS changes address resolution. It does not change the requested URL's scheme,
hostname, web origin or server-side account association. For HTTPS, a server must
present a trusted certificate matching the requested service identity;
address routing alone does not satisfy that check. This follows the primary
[TLS service-identity specification](https://www.rfc-editor.org/rfc/rfc9525.html#section-6),
not a measured Philips certificate-policy test. Certificate pinning, custom trust
configuration and native origin policy on this TV remain unknown.

There is a relevant qualification: public `http://devview.titanos.tv/` currently
returns HTTP200, not a redirect in the captured HEAD response. A public HTTP GET
returned837bytes, SHA256
`535336f08de3442026d3bcf6bc828882df3e2a64c2e1528876c0402a25bd5c7c`,
with relative `/assets/polyfills-BxEv9oQl.js` and `/assets/index-BQQTuhjP.js`.
HTTPS HEAD also returned200. Neither captured HEAD response supplied HSTS.
These Mac-side public checks do not establish which scheme the TV launches or
its cached policies. Therefore TLS alone cannot categorically rule out a DNS
content-routing experiment. If an app really loads over HTTP, changing its
destination could change delivered content; whether that content receives native
bindings is still a separate firmware question. No routing experiment was run,
and no local backend pretending to approve enrollment was built.

## Supported loading routes and another runtime lead

The [official application model](https://docs.titanos.tv/introduction) is a hosted
HTML5 URL. [DevView testing](https://docs.titanos.tv/devviewinstall) uses approved
Partner Portal Sandbox candidates plus device pairing. The normal account route
is still the documented path to a launchable app context. Its [debug guide](https://docs.titanos.tv/debugging)
documents the Philips Settings toggle; no public accountless DNS/config switch
was established by this review.

Jellyfin's term “native shell” should not be read as an ELF installer. At pinned
commit `ba5502baa5587cf19fb004d689454b164dcb9950`, its
[packaging script](https://github.com/jellyfin/jellyfin-titanos/blob/ba5502baa5587cf19fb004d689454b164dcb9950/scripts/package.ts)
copies web/shell assets and injects `jellyfin-titanos.js` into HTML. The
[project README](https://github.com/jellyfin/jellyfin-titanos/blob/ba5502baa5587cf19fb004d689454b164dcb9950/README.md)
describes serving the result over HTTP. No binary sideload route follows from
that naming.

An additional exact-model lead is Ginga: the [43PFG6910 product sheet](https://www.guiadotradetpv.com.br/media/productfile/485/FichaTecnica_Philips_TV_43PFG6910.pdf)
lists support, and the [same model manual](https://www.documents.philips.com/assets/20250325/8209e624f4694ae9a48bb2ab00357e47.pdf),
printed17, documents Home → Configurações → Canais → Ginga. This is a real local
feature toggle, not DevView approval. The [middleware maintainers](https://ginga.org.br/pt/)
identify NCL execution and optional extensions; neither the model documents nor
this bounded search established a local/USB developer launcher, Ginga-J, raw
sockets or a matching firmware implementation. The manual describes USB photo,
audio and video browsing separately. Do not treat enabling Ginga as a demonstrated
own-app loader. A concrete next source question is whether TPV documents an
owner-accessible NCL developer entry for this exact model/build.

The next useful app-context test remains launching the same diagnostic URL
through an authorized DevView candidate and comparing fixed bridge-presence
results with Internet/MSX. The public gateway is another source-backed context
lead, but its initialization invokes native identification/listener methods;
READY alone cannot establish those bindings or authorize an arbitrary loader.

## Validation and retained evidence

Nine isolated VM checks pass on exact selected configuration declarations with
fake URL/storage/native objects: fixed environment/source labels, domain
navigation, SDK parameters, persisted UI state and explicit numeric debug
callback. They make0 network and0 real vendor calls. No complete bundle runs.

Focused REA6.7.0 parses2,210 assembled bytes/410 AST nodes with0 parse failures;
226 semantic nodes,157 relations,69 unknowns, coverage unknown/untruncated.
Evidence `ev_95b8f715224284299cff3e3b2533f69b218accfa96556c489610e02c297f308e`.
Only assignment prefixes/separators were supplied around exact original spans;
function bodies were unchanged. Initial compiler timeout and sandbox process
inspection failures are preserved; the previous worker was confirmed exited,
and the approved process-inspection run succeeded in2.512s. This is partial static
analysis, not firmware authority or a vulnerability finding.

Source ranges/hashes, raw headers/HTTP entry, pinned Jellyfin source, VM fixture
and REA envelopes remain ignored under
`.local/tv-hello/native-sideload-followup/`. No new readable native firmware
component was acquired; existing rejected keys and ciphertext disassembly were
not repeated.
