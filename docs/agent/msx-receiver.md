# Philips and LUMA receiver selection

Latest 2026-10-20: Android is authorized and **TV browser → Open TV receiver** is
the default. Native code requests the fixed receiver page using saved TV-PIN
permission; manual browser-link typing remains a fallback. The debug-channel
APK is installed on the Samsung with saved data/pairing preserved. Its
build/lint,38 receiver Node checks,29 mobile browser cases, one real-browser
regression and native MSX8/multi-receiver4 groups pass. Earlier UI/text checks remain
recorded in the [handoff](session-handoffs/2026-10-20-browser-receiver-shortcut.md).
The current-origin guard, no httpbin dependency and standalone MSX-SDK skip remain.

The actual handshake is established. Loaded/server revision `d66f118d4c` now
matches on the TV; actual Ping ACK advanced0→1, fixed-label ACK arrived, and
Reload ACK was followed by fresh state/ping1→0 on the same revision. This
establishes remote JavaScript reload; label visibility is not independently
confirmed. The old pending-manual-refresh condition is closed.

The actual W3C sample at14:26:41 failed `MEDIA_FAILED`, with media error code4,
networkState3/readyState0 before metadata. Mac Chromium plays the same public
source with duration52.208s; it does not establish TV playback or its failure
cause. No codec/TLS/network/policy conclusion follows from that tuple.
The combined SHA/size-bound LAN fixture and phone feedback fixes are now
installed, with native8/multi4/build/lint and29 browser checks passing. At14:34:18
the actual TV requested the exact same clip bytes with GET206; successful Open
reported Playing0:03/0:52, Pause0:19 stable across two reports, then resumed
Playing0:24. The user then requested an uninterrupted replay and confirmed
picture and sound, saying it looked great. This establishes this clip's physical
playback acceptance; full-length completion remains unproved. Public-versus-LAN
delivery differs, without establishing an exact TLS/cause. After playback was
idle, app Reload yielded “TV page reconnected.” and uptime705→1s, same d66.
No user refresh was needed for that replay; the clip was left uninterrupted.
[Latest LAN handoff](session-handoffs/2026-10-20-lan-sample-playback.md).

Earlier shortcut requests were accepted without establishing visible navigation;
the original public-redirect attempt left the TV at Home. Earlier browser typing
was acknowledged, the expected peer was approved and the LUMA page became
user-confirmed, but visible insertion/causal navigation were not separately proved.
The page may have remained resident. These historical receipts are not a fresh
state POST. The earlier navigation deferral ended when the user resumed this work.

An earlier candidate was installed with saved data preserved. The user entered
the TV PIN, and live phone UI showed saved permission and typing acknowledgements.
The TV remained on the LUMA waiting page without a setup field selected; this
proved permission/key acknowledgements, not visible insertion or playback.
Progress, persistent permission, contextual errors/expiry and setup-window restart
were subsequently repaired. Source tracing confirmed that the earlier bare local
address format was correct; repeat entry alone does not explain status0.
[Exact parser/transport findings](msx-local-address-source.md).

Earlier TV-only discovery excluded Android. Its Mac bootstrap/passive-probe
comparison reproduced status0/TLS-to-HTTP connections, then a trusted-HTTPS route
loaded the actual TV plugin and acknowledged ping/label. Those results remain
separate from the current phone-hosted receiver. [Diagnostic history](philips-probe-control.md).

The user confirmed opening **Media Station X** on their Philips43PFG6910/78.
The exact model runs Titan OS; Android Kodi APK installation does not apply.
The current Titan catalog lists MSX for Brazil, subject to model/region availability.
This establishes app availability on the user's TV, not playback acceptance.

## Runtime

The Android service keeps LUMA TCP/Kodi and Philips control independent. The
compact LUMA/Philips picker chooses the active remote, browsing destination and
system media controls. Switching does not disconnect or stop either receiver.
Each target has separate playback, volume and subtitle state. The selected
target is saved privately; existing encrypted Kodi credentials remain separate.

**Philips → TV connection → Open TV receiver** starts the phone's private
Wi-Fi/Ethernet IPv4 listener, normally port4178, or reopens an expired setup window.
First use requires a separate normal JointSPACE PIN grant on this phone;
subsequent requests reuse the Keystore-encrypted credentials and pinned TV
certificate. Matter resolves the already paired TV's private address. The
argument-free `msx.launchBrowser` checks fresh `system` features for advertised
browser support, then POSTs only `activities/browser` with the fixed URL.
Its 30-second native budget includes a maximum 15-second feature read.

Native snapshot `browserSetupUrl` is present as a URL only while the server runs:
the exact current `startupUrl` plus `/msx/connect.html`. Controller/transport checks
require HTTP, private IPv4, port1024–65535 and that fixed path, with no credentials,
query or fragment. No public redirect service is involved; the earlier httpbin
route is historical. The URL contains no private receiver capability. Callers cannot
supply a URL, host or text. An accepted launch returns `accepted:true` and
`pageLoadVerified:false`. Native `connected` requires approval, a live session and
a valid playback-state POST within12 seconds; hello/poll or page visibility alone
does not establish it. The phone displays
the requesting TV address and requires **Allow TV** before controls are accepted.
Setup expires after five minutes. The server pins the approved peer/client,
keeps a random capability in app-private settings and serves allowlisted assets.
The saved receiver starts again when the visible app opens. Reset/disconnect
do not send Stop. The URL is regenerated from the current native server origin.

Receiver status text distinguishes stages: **Connecting to your phone…** is the
initial HTML; **Approve this TV…** follows unapproved hello/poll; **TV allowed.
Sending receiver status…** follows approval. **Ready. Choose a video…** now follows
only a successful state POST. Approval and page visibility alone do not prove it.
The independent two-second heartbeat and poll loop survive synchronous state-read
or status-render exceptions and continue bounded retries. A command's retained
receipt is retried before polling; a state-read/ACK failure does not replay its
already executed Open. Session rejection still revokes stale receipts.

A real Chromium run reproduced the blocking timer bug: copying browser
`setTimeout`/`clearTimeout` into a helper object and invoking them as its methods
used the wrong receiver and threw `TypeError` after hello, before polling.
Both player and channel defaults now delegate to the browser globals. The
real-browser regression covers poll/heartbeat scheduling, playback timeout and
timer clearing; an independent core run reached hello/poll/state without errors.
The signed fix is installed and the subsequent real TV refresh recovered
hello/poll/state and phone readiness. Earlier approved-without-state/Confirm-ACK
observations predate this recovery and do not establish an input-gesture cause.

Native diagnostics expose accepted hello/poll/state counts, safe session/approval
flags and ages. Counts last for the server object; `lastStateMsAgo` is current
session only and returns to -1 after a new hello. Fixed `HELLO_READY`, `POLL_READY`
and `STATE_READY` logs appear once per process, avoiding heartbeat spam. Client
failures can report one of six fixed codes through capability-relative GET
`diagnostic?code=…`: `STATE_READ_FAILED`, `STATUS_RENDER_FAILED`,
`NETWORK_UNAVAILABLE`, `HTTP_REJECTED`, `INVALID_RESPONSE`, `CHANNEL_FAILED`.
The wrapper reports only its first failure per page load, without exception text
or stack. Native `lastClientError` records only those codes and logs code changes;
the route retains Host/peer/origin/capability guards and cannot establish state
or connection. These fixed client-error codes do not by themselves identify a
physical TV failure; the host-timer bug above was separately reproduced in Chromium.
An APK update does not replace JavaScript already resident in the TV page.

**Enter link manually** is the fallback: select and clear the TV Internet
browser's address bar, then use argument-free `msx.typeBrowserAddress` to type
the same fixed URL. Its native deadline is 90 seconds; the old `msx.typeAddress`
still types only `IP:port` for the explicit legacy MSX setup, with 65 seconds.
Both reuse saved permission, send one Unicode key at a time with 150ms pacing,
and record `textEntryMode`, `totalChars`, acknowledged characters and partial
outcomes. `textAppliedVerified:false` remains explicit. The user submits Go/OK
on the TV; the full browser URL does not belong in the MSX startup field.
There is no automatic clearing, Enter or retry after an uncertain write. Target
change, reset/disconnect and service shutdown cancel typing. Changed TV address/
certificate recovery still needs an explicit forget/re-pair flow.

Legacy MSX setup remains available explicitly: Settings → Start Parameter → Setup
takes bare `IP:port` with its HTTP security padlock open. MSX reads
`/msx/start.json`, opens the home page, then **Connect this TV** loads the receiver.
Its known startup/security failures are historical context, not a prerequisite
for the default standalone browser route.

New receiver pages reacquire the bootstrap before hello. Older loaded pages
holding the current private capability can reclaim an unapproved session after
setup is explicitly reopened or the app restarts. Only a valid singleton-client
GET hello with the permitted origin/private peer/Host can claim that pending
peer; another pending/approved peer is preserved. **Allow TV** is still required.
Explicit Reset rotates the capability, so an old page cannot recover through it.
`/msx/connect.html` supplies a scoped local redirect into the same receiver;
neither bootstrap path launches an installed TV app by itself.

The TV receiver owns a video element, uses native HLS when supported or bundled
HLS.js1.7.3 otherwise, and fetches media directly from its original public URL.
The complete subtitle catalog stays in receiver memory; PT-BR then English is
selected initially, and other languages load on demand. SRT/WebVTT are parsed
and rendered on the TV. The phone hosts control/assets, never media or subtitles.
Phone disconnection does not intentionally stop an already loaded player; the
actual firmware still needs a phone-off test across buffering boundaries.

Philips controls cover play/pause, stop, seek, subtitles and **player volume**.
This MSX transport does not control TV hardware; the separately paired Matter
adapter in0.4.0 supplies supported speaker/navigation/confirmed-Off controls.
LUMA keeps its existing projector controls. Notification/lock-screen actions
follow the selected receiver, with revision/source guards against stale actions.
Home and Back now flank Up in the D-pad's top row; Philips navigation uses the
separate Matter connection. The video-receiver setup and TV-remote setup remain
distinct views.

Open acknowledgements wait for actual playback plus the preferred subtitle
attempt. If subtitles fail while video plays, the phone adopts that playback and
offers language retry instead of reopening the video. Delivered unknown commands
are not automatically resent; ACK receipts survive transient control failures.
An independent heartbeat keeps the connection fresh during slow video startup.

**Play sample on Philips TV** calls argument-free `msx.sample`. Native code owns
the fixed public MP4 and its short-lived authorization, independent
of captured-video tokens. UI success requires the matching successful command
receipt and active playback; an uncertain Open is not automatically repeated.
Healthy receiver state uses JSON `error:null`, now accepted without Android
coercing it to the invalid string `"null"`. The first Google sample attempt was rejected;
the installed W3C replacement also fails with MEDIA_FAILED. The loaded debug
revision and numeric sample tuple are now confirmed as above. The next candidate
serves only the same verified opt-in fixture through a fixed approved-peer,
capability-bound route; it does not proxy media or admit arbitrary private URLs.
The exact-byte candidate is now installed and has the TV-reported playback,
stable pause and resume reports and user-confirmed picture/sound above. Real
streams, subtitles and phone-off use remain open.

## Historical setup failures and limits

The first physical0.3.0 attempt failed with **Server responded with status0**.
The phone listener was reachable from the Mac, but an OPTIONS browser preflight
was rejected.0.3.1 accepts restricted bootstrap/home preflights without claiming
the requesting peer, exposing the capability or approving a TV. A real Android
WebView cross-origin fixture verifies OPTIONS followed by GET. Safe diagnostics
distinguish TLS sent to the HTTP port, rejected origins and expired pairing.
The phone displays only fixed guidance, never request URLs or tokens.
The actual0.3.1 retry still failed with status0. A generic phone400 does not
distinguish an abandoned connection from a parser rejection. A separate Mac
bootstrap diagnostic also failed and recorded only a zero-byte TCP timeout,
with no HTTP or TLS request. Direct TV browser connectivity is the next check.

This fixes a reproduced compatibility gap; it does not establish the TV's exact
failure. [MSX setup documentation](https://www.msx.benzac.de/wiki/index.php?title=Setup_Start_Parameter)
explains that HTTPS-loaded MSX upgrades JSON/plugin requests to HTTPS. The phone
listener currently serves HTTP only. A forced-HTTPS TV context needs a different
entry path; CORS headers cannot fix a TLS mismatch. The current physical failure
has not been attributed to this policy. [Matter hardware control research](philips-matter.md) is separate and
does not establish arbitrary video/subtitle playback.

## Historical direct-browser experiment

The actual TV now advertises browser activities through its separate
[JointSPACEv6 API](philips-jointspace.md). A fresh private Digest PIN grant
succeeded. This authority is separate from the working Matter fabric. Browser
POST returned200, but the user still saw Home and the receiver saw no TV traffic;
the CLI correctly reports `pageLoadVerified:false`. Authenticated context labels
did not match the screen, so they cannot guide blind focus/navigation.

The temporary Mac proof in `scripts/msx-mac-proof.mjs` serves these same receiver
assets/channel directly, potentially avoiding MSX's startup/secure-context
restriction. It is peer/capability pinned, uses authenticated loopback control
and never proxies media/subtitles. Thirteen loopback fixtures pass. Root has
started the temporary listener. One saved-URL browser retry after the TV power
cycle was accepted again, but TCP/asset/hello counts remain0 and the user
confirms it left Home unchanged. A context read supplied empty focus. One
Matter Right from the recent Netflix tile was accepted and reached the regular
Netflix tile, as confirmed by the user.
The user confirms phone reconnection/navigation
and identifies Netflix as the leftmost default Home tile, so text feedback can
guide the next step without requiring a photo. Proven Unicode key input is
available; no text-entry context replacement schema was established.
Separate exact-TV discovery now proves a DIAL application base. One empty
Netflix launch returned201 then running, and the user confirmed visible launch.
This app-specific route does not establish a browser/MSX launch,
arbitrary media URL or subtitles; see [TV evidence](philips-tv.md).
The user's favorites now place browser1Right/MSX2Right from the initial tile.
After one Home batch unexpectedly reached HDMI1, a separate Home with user
feedback restored first-tile focus. Right→Select showed ambiguous “Editar speed
Dial”; one Back and a browser request then reached HDMI1, still receiver0.
Named JointSPACE Home visibly returned HDMI1→Home, as the user confirmed.
Right,700ms pause,Confirm were then accepted, but the screen result is unknown:
the user left and all device work/questions stopped. Pairings/listener were
preserved. No browser/MSX or media launch is claimed; the cause remains unproven.
Browser loading,
autoplay, fresh source, actual video/PT-BR and continuation still need acceptance;
this is a proposed route, not a resolution of MSX status0 or a shipped0.4.0
playback feature. [Current setup handoff](session-handoffs/2026-10-18-philips-browser-setup.md).

See the [session handoff](session-handoffs/2026-10-18-dual-receiver-msx.md) for exact
build/test/publication and device results. Code/fixtures establish behavior only
within those checks. Philips codec support, source CORS, MSX HTTP/mixed-content
handling, subtitle appearance and phone-off continuation require the physical TV.
Standalone browser execution skips the MSX SDK entirely. Only an embedded MSX
receiver loads the official SDK from its upstream HTTPS URL; it is not
redistributed under an assumed license. A failed embedded SDK load leaves phone
controls available but does not establish native MSX-overlay compatibility.

Files: `src/msx/`, `MsxServer.java`, `ConnectionService.java`, `MainActivity.java`,
`src/mobile/`, `tests/msx-receiver.test.mjs`, mobile receiver browser fixtures and
emulator MSX fixtures. [Receiver implementation and references](../../src/msx/README.md).
