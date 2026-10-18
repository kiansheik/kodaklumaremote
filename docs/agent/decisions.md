# Engineering decisions

2026-10-18. These are implementation decisions, not evidence of a working TV
session. [Cast feasibility](chromecast-feasibility.md) records primary sources.

## D1: Keep all extension executable code packaged

Do not inject or evaluate the remotely hosted Google sender SDK from extension
contexts or by adding a script tag to the host page. Do not weaken the CSP, use
`eval`, use debugger permissions, or fetch scripts as text to evade that limit.
Chrome's [RHC guidance](https://developer.chrome.com/docs/extensions/develop/migrate/remote-hosted-code)
and [CSP reference](https://developer.chrome.com/docs/extensions/reference/manifest/content-security-policy)
support this decision.

## D2: Reuse the site's existing Cast framework

A packaged page adapter calls the already-present compatible CAF SDK without
loading another one or silently overwriting the site's receiver configuration.
Static reconnaissance verified both SDK initialization in public HTML and
native Cast button/session code in the player bundle. Inspect the actual native
control first and preserve it; add a narrow wrapper only where useful. Report
missing/not-ready SDK, cancelled picker, and unavailable receiver explicitly.

Remote Playback and a separately hosted HTTPS sender remain possible future
alternatives, not current implementation requirements. An ordinary local
`/cast-demo` page can load the official SDK outside the extension to isolate
receiver/media testing. The loopback extension-test manifest is separate from
production; the demo is an ordinary webpage, not an extension page.

The [official sender setup](https://developers.google.com/cast/docs/web_sender)
describes CAF. Runtime visibility and receiver success remain unverified.

## D3: Narrow origin scope and extract media data only

Match the target origin and explicitly observed player origins. Do not use
`<all_urls>`, cookies, webRequest, debugger, broad storage reads, or telemetry.
Do not broaden iframe access merely because the parent embeds another origin.
Validate every URL, MIME, track descriptor, and command boundary. Treat all
page-provided data as untrusted; never interpret it as executable code or HTML.

## D4: Preserve source playback until remote success

Reject `blob:`, `data:`, and unsupported candidates for the direct-URL CAF path.
Do not pause local playback merely because the device picker opened. Only
transition after a successful receiver load or browser remote connection; handle
errors without breaking ordinary playback. Dispose listeners and invalidate
pending source state on navigation, source replacement, and disconnect.

## D5: No unproven relay or custom receiver

Start with existing compatible sender/receiver APIs. A custom receiver needs a
concrete supported requirement. A relay needs evidence of necessity and
provider-permitted use. Neither is an authorization or DRM workaround. Keep tab
mirroring explicitly labelled browser-dependent and separate from direct play.

## D6: Separate automated evidence from hardware acceptance

Fixtures may prove player detection, insertion, validation, state transitions, cleanup,
and command wiring. They cannot prove LAN discovery, receiver fetch/decode,
real subtitles, source expiry, or physical remote control. Follow-up YouTube
hardware picture/sound was user-confirmed; generic arbitrary-media Cast remains
unverified. Do not promote that app-specific result into generic acceptance.

## D7: Mobile acceptance is phone power-off, not just a remote-looking UI

User clarified that the final Samsung-phone controller must be able to power
fully off while the receiver retrieves media. Desktop Chrome is a stepping
stone. Standard Cast and an actually supported UPnP MediaRenderer are suitable
candidate transports; a Miracast-only sink is not. A native helper does not make
mirroring into independent retrieval. Do not build a phone proxy/hotspot path as
if it met this requirement. See [mobile target](mobile-target.md) and
[Philips hardware evidence](philips-tv.md). The first Android LUMA/Kodi sender
is implemented; physical Samsung and full phone-off acceptance remain pending.

## D8: Explicit source handoff and media-scoped subtitle metadata

Use trusted-click **Copy video link** and **Copy subtitle list** actions on the
ordinary player. They reuse validated declared-source extraction, require no
new extension permissions and do not copy browser cookies/headers. Preserve the
site's Cast button while leaving these independent copy actions available.
Kodi receives the URL through its documented JSON-RPC interface and owns media
retrieval; no Mac media relay is needed for the observed HLS source.

External Kodi subtitle names can be generic. Keep human-readable aliases only
in bounded controller memory, keyed by receiver and SHA-256 of Kodi's verified
current file. Do not persist raw links or attach old labels to another video.
Registration/command acceptance is distinct from rendering: only Portuguese
captions received explicit physical dialogue-match confirmation in this test.
The original all-track hardware proof used the local API. The Android sender
now implements automatic batch handoff, with phone hardware acceptance pending.

## D9: Reuse native LUMA wire controls behind a bounded local bridge

The inspected Android client uses raw sockets for projector controls. Keep a
reusable browser interface over a small loopback HTTP-to-TCP adapter; an ordinary
website cannot directly implement this transport. Do not expose the bridge to
the LAN or run the original phone filesystem-sharing server. Verified controls
worked without its storage-root `auth` advertisement. Require observed pairing
configuration and reject password/unknown cases until implemented explicitly.
Launch only packages from the parsed receiver app inventory. Do not treat a
write acknowledgement as a physical success: Kodi launch/navigation have user
confirmation, while text/volume changes still need device acceptance.

## D10: Phone web UI inside a narrow native shell

Use native Android sockets for LUMA and HTTP for Kodi; package the no-scroll UI
as local assets. Keep arbitrary browsing in a different WebView without any
native JavaScript bridge. Capture only readable declared sources on the supported
origin. Neither the Mac nor phone relays media. Scope native mutations to a
fresh casting-attempt token and recheck before writes; cancellation cannot undo
commands already accepted by the receiver. Reissue subtitle adds only with
explicit pre-write negative proof, otherwise reconcile observed indices.

Prioritize playback, volume and navigation for the household's bedtime workflow;
put subtitles, typing and app lists in separate panels. The bounded public
[review sample](luma-review-findings.md) informs discovery/recovery/input work,
but its complaint counts are not control-usage measurements or diagnosed causes
of official-app crashes. Native fixture success is separate from Samsung and
phone-off hardware acceptance.

## D11: Authorized saved setup and foreground ownership

The user explicitly requested saved login/cached IP and system controls. Save
only the verified receiver address/name and authenticated Kodi credentials,
encrypting credentials with Android Keystore/AES-GCM. Active media URLs and
subtitle sources remain memory-only. Reset scopes are explicit; Disconnect keeps
saved setup and leaves video playing. Reuse cached IP until failure/rescan and
automatically choose only a unique compatible discovery result.

A connected-device foreground service owns native I/O and a remote MediaSession;
the Activity binds to it. Visible-app retries pause on explicit disconnect.
Native generations reject queued/stale work and permit immediate disconnect.
First setup authenticates Kodi before saving and continuing the pending video.

## D12: Preferred subtitles first and guarded cold-start recovery

Following phone acceptance, the user requested lower startup latency: load
Brazilian Portuguese, otherwise English, and retain every declared language's
URL in service RAM for on-demand loading. Restore explicit URL/index bindings;
never infer a language from an unknown external Kodi track. Catalog updates
verify the active source and preserve the existing media-control revision.

Kodi's HTTP API may become ready before its first accepted Open starts video.
Allow one automatic retry after fresh, sustained inactivity, with a native
prewrite idle check. Pending/unknown Open outcomes must not overlap another
Open. Cancel/retry can proceed after explicit native proof that a command was
never written. Successful command acknowledgement still requires observed
playback/subtitle state before opening Remote.

Publish a shared session/notification launch target and playback custom actions
for modern Android system controls. A connection without verified playback uses
an ordinary service notification. An active notification record is not proof
that Samsung displays the controls; retain physical acceptance separately.

## D13: Recover observed playback and separate notification surfaces

An APK/process restart loses RAM-only media descriptors while Kodi continues
playing. When connected with saved setup, adopt a fresh active public HTTP
source only if no remembered remote or authorized handoff exists. Use generic
metadata, rotate the control revision and send no Open/Add command. Keep existing
remembered sources scoped; do not retarget their old controls to another video.

Keep an ordinary foreground notification with source-bound playback buttons
separate from the verified MediaStyle card. This allows the normal notification
panel and Android's media/lock-screen surfaces to work independently. Use a
confirmed, exact-source live instrumentation mode to test the phone's actual
notification and MediaSession Pause/Play actions, preserving setup and playback.

Metadata reads can span multiple native HTTP requests. Give subtitle readiness
and registration bounded30-second windows and propagate their deadlines to the
bridge. Retry transient reads, not authentication/validation errors. An uncertain
Add outcome is reconciled against observed indices without sending another Add.

## D14: Explicit power confirmation and verified rejection receipts

Use the archived Kodak client's confirmed KEYEVENT26 flow for a dedicated
Power off control. Require a literal native confirmation as well as a phone
dialog with Cancel focused. A send receipt disconnects/demotes local controls
and pauses automatic reconnect, retaining saved setup. Do not infer physical
shutdown or send power during automated actual-device checks.

Only validated same-ID JSON-RPC errors known to precede dispatch may clear an
Open/Add uncertainty receipt. Kodi21.2 schema/method/permission rejection codes
qualify; AddSubtitle's FailedToExecute also precedes queuing. Open's generic
FailedToExecute, internal errors, malformed responses and network failures stay
uncertain. Recovery requires explicit Retry and never blindly duplicates a write.
Fixed method/category and numeric status diagnostics expose no raw RPC messages.

The first three ordinary/system media buttons prioritize Volume −, Play/Pause,
Volume +. Read observed volume after relative keys and preserve exact source and
control revision guards. Stop/Disconnect remain secondary media/app controls.

## D15: User-owned GitHub distribution and optional Android updates

Use the explicitly requested `kiansheik/kodaklumaremote` repository and GitHub
Pages. A release publishes a locally signed APK and derived `update.json`;
Pages deploys only reviewed site files plus validated stable-release metadata.
Keep the user's existing production signing key local; check its public
fingerprint. Upload assets to a draft before publishing to prevent deployment
racing incomplete uploads. No signing key or password is uploaded to Actions.

Check the fixed HTTPS update feed on foreground opens, throttled five minutes;
manual Settings checking bypasses that limit. Later suppresses the same version
for24hours. Download only after Update, verify bounded bytes/hash/package/version/
current signer set and use a temporary read-only content grant. Android owns
unknown-source permission and installation confirmation. No silent install.

The existing owner's debug APK requires a private API33+ signing-lineage
bootstrap to preserve data. Public fresh installs and future ordinary updates
use the production signer only. Validate encrypted-settings preservation across
bootstrap and ordinary production replacement on an emulator before the phone.
