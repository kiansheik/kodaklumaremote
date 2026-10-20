# Live probe control and privileged-interface investigation

2026-10-20. The user authorizes continued offline firmware analysis with REA
and runtime investigation toward a TV-side server/custom remote, with Android
still excluded. This follow-up builds on the
[firmware implementation report](philips-firmware-implementation.md).

## GUI-free command handlers verified on TV

The current TV page receives two fixed commands from our peer-pinned Mac server:
`ping` and `set-label`. Both produced matching command-ID receipts on the actual
TV without GUI navigation. A later label command additionally read back the
DOM text before acknowledging success. This proves the loaded page's handler
and DOM state, not independently observed pixels, native process control or
playback. The local Chrome fixture separately verified the displayed label.

```sh
node scripts/tv-hello-server.mjs ping
node scripts/tv-hello-server.mjs label
node scripts/tv-hello-server.mjs status
```

`label` always sets the fixed text `Controlled remotely`; it accepts no user text.
The initial CLI response says queued/completed:false. Completion requires a
matching `lastCommandResult` ID/method/ok receipt in status. One command may be
pending; another is rejected until it completes. The page remembers a bounded
set of handled IDs, acknowledges duplicates without reapplying them, and sends
receipts only on ready/heartbeat reports. Unknown/stale IDs are rejected.
After our server restarts, an unknown retained receipt is dropped after403;
it is not re-executed. The two commands are harmless/idempotent proof operations,
not a general media command service or a claim of exactly-once delivery across
page/process restarts.

The probe announces `remoteCommands:true` with its source revision on ready and
heartbeat. This restores capability after a listener restart while the document
remains open. Commands are withheld during a reload response. The probe now
polls every2s; the standalone Hello World still uses10s heartbeats. Source-hash
refresh and the original private receiver URL remain operational. Loopback
control URLs get fresh capabilities on restart; peer/origin/Host/body checks and
the16KiB report limit remain enforced.

The architecture is still TV-originated requests to the Mac, with commands in
responses. The Mac must run for later controls; the loaded page must survive.
This proves GUI-free control does not require a new incoming TV TCP listener.
Phone integration, media control, autostart and background lifetime remain
separate work; Android and the published app were not changed.

## Privileged TCP diagnostics

The actual current Internet page reports:

- `TCPServerSocket` descriptor absent; fixed constructor attempt marked
  `not-exposed`, with no permission or argument exception because no callable
  interface existed.
- `direct-sockets` policy query `unsupported`: the query API/recognized feature
  did not establish a policy decision. This is not a measured permission denial.
- Global `chrome` and `opera` objects missing. The separate `opera_omi` object
  exists. Bounded names are partial inventories, not exhaustive absence proofs.
- Standard `window.external`, read once after source review, returned the usual
  `AddSearchProvider`/`IsSearchProviderInstalled` surface. Neither method ran.

If the TCP constructor exists in another context, the fixed diagnostic uses
only the invalid literal `not-an-ip` and empty options. The pinned upstream
implementation rejects it before opening a socket, after context checks. The
actual TV did not reach this branch. Local fixtures verify the TypeError path
without native sockets. TypeError alone would not identify a vendor code path.
The reference's `close()` and failed-open handling are unfinished, so this
investigation did not create a successful listener assuming it could close it.

The reference IDL/runtime/policy gates and isolation rules remain in the
[browser internals report](philips-browser-internals.md). Headers, WASM, a worker
or an arbitrary capability name do not supply isolated-app/embedder authority.
No documented public Titan raw-socket bridge was found. App Control is an
allowlisted app-launch API; DevView remains the supported next app context to
compare after the user's developer approval.

## Newly observed vendor registry

A focused descriptor scan discovered these actual objects:

| Object | Bounded observed surface |
| --- | --- |
| `NetTV_Connection` | EPG_BC and EPG_IP values; no sampled socket method |
| `NetTV_DeviceUI` | USERPOINTERMODE_AUTO/OFF values |
| `NetTV_ContentManager` | Activity/favorite/change constants in the bounded sample |
| `opera_omi` | sendPlatformMessage, addPlatformEventListener and a constructors getter |

Only the `constructors` getter was deliberately read once per new page sample.
It yielded a registry accepted by the standard built-in Map iterator. The probe
uses `Map.prototype.entries.call`/`get.call`, never the registry's own methods,
and samples at most128 entries, returning at most32 safe names/types within the
64-name total descriptor budget. Network/media-related names are prioritized;
this is not a complete class inventory. No platform-message method is invoked.
Only CSManager receives the fixed construction attempt described below.

Registered function names include NetworkInterface, RemoteManagement,
DownloadManager, MediaSwitcher, MediaSynchroniser, ApplicationManager and
CSManager. Registration alone is not an authorization or constructibility test.
Other manufacturers' examples of `opera_omi.sendPlatformMessage` do not establish
Philips message semantics; no guessed IPC messages were sent.

`Function.prototype.toString` on only the registered CSManager returned38
characters with a native-code marker; its sampled prototype showed constructor
entries only. That is representation metadata, not C++ source or a binary address.
The bounded raw string stays private; normal CLI status projects source length,
completion/native marker and safe prototype names, never the raw source.

## Standard HbbTV relay lead

The [official object factory reference](https://developer.hbbtv.org/references/object-factory-api/methods)
defines `oipfObjectFactory.isObjectSupported('application/hbbtvCSManager')`,
`createCSManager()` and the typed DOM object route. Direct `new` on the internal
registry class is not documented by that contract. A final exploratory test made
one zero-argument construction attempt: the actual TV reported attempted:true,
constructed:false and errorName:TypeError, with no instance or relay methods.
This proves failed construction with those arguments, not the cause of the error.
No opaque arguments or other registered constructors were tried.

The [ETSI HbbTV specification](https://www.etsi.org/deliver/etsi_ts/102700_102799/102796/01.07.01_60/ts_102796v010701p.pdf),
§§11.7/14.5 and AnnexA.2.7, describes getApp2AppLocalBaseURL and
getApp2AppRemoteBaseURL. The local getter can activate the TV's WebSocket service
until application exit. TV and LAN companion use their returned bases plus the
same app suffix, wait for `pairingcompleted` and then relay messages. Random URL
components must be obtained through the API rather than guessed or published.
The guarantee is for HbbTV applications; an ordinary Internet page is not
established as one merely by its MIME/object/header choices.

The probe therefore creates one hidden typed object, waits1.5s for binding,
and reports only fixed factory/support and method-presence fields. Its CSP
explicitly permits self-origin objects; no external object URL is loaded. The
service-activating URL getters are not called by this presence check. The
actual TV reported factoryAvailable:false, objectSupported:null,
elementCreated:true, localMethod:false, remoteMethod:false and errorName:null.
Neither relay method appeared in this1.5s Internet-page sample; no service was
activated and no relay URL was obtained. This does not exclude a working HbbTV
or DevView application context.

## Media Station X context comparison

The user opened installed MSX and confirmed the saved Mac startup parameter.
The prior standalone Mac receiver did not supply `/msx/start.json`; it was stopped
only after verifying its saved PID and command. A separate MSX diagnostic now
uses that same port while the ordinary probe remains on its original listener.
No Android change is involved.

The [MSX plugin contract](https://www.msx.benzac.de/wiki/index.php?title=Video%2FAudio_Plugin)
loads custom HTML in an iframe with `video:plugin:<URL>`; no video or SDK is
required for this API comparison. The new peer-pinned bootstrap serves an
[official start object](https://www.msx.benzac.de/wiki/index.php?title=Start_Object)
pointing to a single diagnostic tile. Bootstrap/home CORS permits only configured
exact origins; opaque Origin is denied by default and can be explicitly enabled
for bootstrap only. The framed probe alone has exact configured frame ancestors;
normal probe responses retain frame-ancestors none. Report origins, capability
URLs and loopback controls remain separate and unchanged.

First MSX sampling is passive even if active diagnostics are requested: no TCP
or registered constructor calls, factory support call or typed HbbTV object.
`probeContext` records only version, embedded boolean and activeDiagnostics
boolean. Local Chrome verified a genuinely framed passive page and a remote
label receipt with zero native constructor/object calls. The child frame does
not automatically inherit MSX parent globals or permissions.

Actual first retry reproduced the exact user-visible error `Start parameter
error: Server responded with status: 0.` Two connections came from the expected
TV, but no HTTP/bootstrap/plugin request reached the listener. Later client-error
count was2, with0 TLS-prefix errors; these initial counters alone do not establish
whether the empty connections timed out. The final listener now counts active,
closed, zero-byte and idle-timeout connections separately, plus capped aggregate
bytes. A real loopback fixture verified the5s empty-connection timeout. A
controlled retry subsequently tested both padlock settings; no raw request/packet
data is logged. Final expected-TV totals:4 completed connections,2 zero-byte
idle timeouts,2 TLS-prefix parser errors,1034 aggregate bytes,0 HTTP requests
and0 plugin/ready. HTTPS was attempted against this HTTP-only port; which
padlock setting caused each connection was not individually correlated.
That LAN attempt had no plugin/ready or API comparison; the later HTTPS
success is recorded below. This error is not evidence that the server
rejected an Origin, nor proof of a certificate or CORS cause.

[MSX setup documentation](https://www.msx.benzac.de/wiki/index.php?title=Setup_Start_Parameter)
says HTTPS-loaded MSX upgrades JSON/plugin URLs to HTTPS. Its security padlock
controls bootstrap selection; installed-app scheme remains unknown; both lock selections produced status0. A trusted
HTTPS entry or a permitted local HTTP context may still be required, but neither
is yet established as this failure's cause. No guessed IPC, cross-origin parent
access or app privilege claim follows from the startup error.

### Temporary trusted HTTPS and actual MSX request shape

A dedicated temporary HTTPS adapter exposes only start/home JSON, passive probe
HTML and its bounded report route. It uses a separately minted receiver capability;
status, reload and command controls stay loopback-only. Cloudflared2026.10.2 was
downloaded from its official release, with both archive and extracted binary
hashes independently matched to publisher values. No system install, account or
TV trust-store change. Hostname, tokens, source captures and logs stay ignored.

The first real MSX web-app check reproduced status0 and browser CORS failure:
MSX appends `v=<numeric-version>&t=<timestamp>` to JSON URLs, so our exact URL
route rejected the request. `msxCachePath` now accepts only bounded version/time
fields, each once, on bootstrap/home. Unknown, duplicate or malformed fields and
queries on reports/pages/controls remain rejected. The private HTTPS adapter
uses the same validator. This fixes a measured web-app request mismatch; it does
not retroactively explain the TV's earlier no-HTTP connections.

After that fix, the real MSX web app loaded the menu, opened the plugin with its
remote-style Enter key and acknowledged a label command: embedded:true,
activeDiagnostics:false,0 browser errors and0 failed requests. An initial pointer
click fixture was blocked by MSX's remote overlay; keyboard activation worked.
This is desktop MSX evidence. The backend was reset to a fresh capability/counts
before the TV attempt, keeping the HTTPS hostname. The user cleared MSX input
and requested remote hostname entry. All42 characters were acknowledged; the
user confirmed entry into the diagnostic. Fresh TV snapshot: pageGET1,ready1,
heartbeat13,rejected0, embedded:true/activeDiagnostics:false. Subsequent ping and
label each acknowledged their exact ID, with DOM label readback and matching
source revision. Viewport is1920×1080 instead of Internet's1920×945; every other
compatibility-profile field is identical. This is the first physical MSX plugin
execution and GUI-free control acceptance.

The TV's MSX bootstrap Origin is `http://msx.benzac.de`, while the plugin is
HTTPS. It reports secureContext:false/crossOriginIsolated:false. The pinned
[Chromium112 ancestor check](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/execution_context/security_context.cc#L95-L106)
marks a context insecure when an ancestor is insecure; this is consistent with
the measurement, not a failed TLS connection or proof of the exact vendor branch.

MSX still exposes no TCPServerSocket/TCPSocket/UDPSocket and no public HbbTV
factory. Its bounded OMI registry and CSManager native representation match
Internet. No registered constructor or typed HbbTV object was invoked in this
passive MSX sample; object binding/native construction in MSX remains untested.
WebSocket client and existing outbound command transport remain available.

A useful remaining LAN comparison is public bootstrap followed by a private
HTTP `video:plugin:` iframe. In the pinned upstream112 reference,
[private subresource blocking is enabled while navigation blocking defaults off](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/content/public/common/content_features.cc#L150-L194).
The [subframe navigation path](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/content/browser/renderer_host/navigation_request.cc#L4592-L4605)
uses warning policy for an insecure parent with that navigation flag disabled.
Thus a LAN iframe could work even when MSX's JSON fetch does not. This remains a
reference-guided experiment, not a verified OEM flag/policy or tested LAN result.

Local networking itself is already verified by the Internet page. MSX local
startup remains unresolved. The working path uses public trusted HTTPS; private
network policy and an MSX app-origin restriction remain hypotheses. CORS/cache
query compatibility is now fixed. A fully LAN MSX startup or a private-URL plugin
navigation has not been established. Current MSX startup depends on this Mac and
the temporary HTTPS tunnel; no permanent hosting/autostart service was installed.

Public-tunnel requests arrive from cloudflared on loopback, so that hop does not
prove original client identity. Correlate the user's TV navigation and measured
TV browser profile; preserve the separate desktop snapshot. The temporary public
route contains no firmware, media, private controls or arbitrary file access.
Its adapter fixture also covers CORS preflight,16KiB input limit, origin/Host
rewrite, ping receipts and private-route denial.

## Firmware/REA continuation

REA6.7.0 successfully inspected the complete authentic current ZIP and
independently confirmed its one608,642,544-byte Autorun.upg and retained hashes.
Archive evidence is `ev_33572564a50a716841c326a888a0aff6c6539068dad19d1bccb570132648e7c5`;
embedded inventory evidence is
`ev_d1427522d481a5cfe7a6e5b2dfe25f537833a849a17da318788c9b6733b6d0d0`.
Container-entry coverage is complete, but the UPG is a generic file with unknown
architecture. ZIP encrypted:false describes the ZIP layer only.

Current and older payloads split into117 and111 segments respectively. Their
footer lengths are exactly48bytes per segment. MD5/SHA1/SHA256/SHA384 of every
raw segment, an alternate file-aligned segmentation and each entire payload
matched none of the footer bytes. The48-byte record schema and authentication
remain unknown; these negatives do not prove encryption or key/algorithm choices.
Six bounded raw components retain exact original offsets, lengths and hashes.

REA refused raw-header native admission and reported missing Linux firmware/
layout prerequisites; native providers are unconfigured. Its initial Swift-cache
failure was resolved using an owned cache without changing the toolchain/config.
No readable filesystem/browser/native executable was recovered, so native
function disassembly is not yet available. Existing public-default-key sample
results remain unchanged; no crypto brute force or firmware execution ran.

## Validation and private evidence

Eighteen Node protocol fixtures cover capability/queue/receipt/reload/restart,
concurrent duplicate acknowledgements, metadata roles/shapes/bounds and the CSP.
The private HTTPS adapter adds one loopback fixture (19 total with the18 server
cases). Actual MSX web-app and isolated framed Chrome checks pass.
The integrated local Chrome fixture covers actual handlers/DOM readback, mock
CSManager successful/error construction branches, zero
opaque-method/nested-getter calls, fixed one-time getter
reads, fake invalid-TCP argument handling, mock HbbTV method presence without
activation, no external requests and a maximum measured report of4485bytes
(limit16KiB). Those mocks are
not device results. Routine diagnostics now default to passive mode: no native
constructor or typed HbbTV object creation. The captured earlier active results
remain private evidence. Method-presence checks now use data descriptors, so
returned-object accessors are not evaluated.

Private `.local/tv-hello/firmware-deep/` contains the live command receipts,
round2 status snapshots, CSManager capture and final constructor/object results.
The final constructor revision also acknowledged fresh ping and label commands
with matching IDs, matching source revision and zero rejected reports.
`rea-round2/` retains REA envelopes, component provenance, comparison scripts and
round2-report.json; `privileged-round2/` and browser/opera-omi-search.json retain
primary-source context/registry research. No private URLs/addresses, raw program
captures or firmware files enter Git. See the
[session handoff](session-handoffs/2026-10-20-probe-control-round2.md).
