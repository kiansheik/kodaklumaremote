# Philips and LUMA receiver selection

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

**Philips → TV connection → Set up TV** starts a listener on the phone's private
Wi-Fi/Ethernet IPv4 address, normally port4178. In MSX, Settings → Start Parameter
→ Setup takes the displayed `IP:port` with its security padlock open for HTTP.
MSX reads `/msx/start.json`, opens the
generated home page, and the user selects **Connect this TV**. The phone displays
the requesting TV address and requires **Allow TV** before controls are accepted.
Setup expires after five minutes. The server pins the approved peer/client,
keeps a random capability in app-private settings and serves allowlisted assets.
The saved receiver starts again when the visible app opens. Reset/disconnect
do not send Stop. Changing network addresses can require setting up MSX again.

The TV receiver owns a video element, uses native HLS when supported or bundled
HLS.js1.7.3 otherwise, and fetches media directly from its original public URL.
The complete subtitle catalog stays in receiver memory; PT-BR then English is
selected initially, and other languages load on demand. SRT/WebVTT are parsed
and rendered on the TV. The phone hosts control/assets, never media or subtitles.
Phone disconnection does not intentionally stop an already loaded player; the
actual firmware still needs a phone-off test across buffering boundaries.

Philips controls cover play/pause, stop, seek, subtitles and **player volume**.
They do not control hardware speaker volume, TV power, navigation or app launch.
LUMA keeps its existing projector controls. Notification/lock-screen actions
follow the selected receiver, with revision/source guards against stale actions.

Open acknowledgements wait for actual playback plus the preferred subtitle
attempt. If subtitles fail while video plays, the phone adopts that playback and
offers language retry instead of reopening the video. Delivered unknown commands
are not automatically resent; ACK receipts survive transient control failures.
An independent heartbeat keeps the connection fresh during slow video startup.

## Evidence and limits

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

See the [session handoff](session-handoffs/2026-10-18-dual-receiver-msx.md) for exact
build/test/publication and device results. Code/fixtures establish behavior only
within those checks. Philips codec support, source CORS, MSX HTTP/mixed-content
handling, subtitle appearance and phone-off continuation require the physical TV.
The official MSX SDK loads from its upstream HTTPS URL; it is not redistributed
under an assumed license. A failed SDK load leaves phone controls available but
does not establish native MSX-overlay compatibility.

Files: `src/msx/`, `MsxServer.java`, `ConnectionService.java`, `MainActivity.java`,
`src/mobile/`, `tests/msx-receiver.test.mjs`, mobile receiver browser fixtures and
emulator MSX fixtures. [Receiver implementation and references](../../src/msx/README.md).
