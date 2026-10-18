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
[Philips hardware evidence](philips-tv.md). No mobile app is implemented yet.

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
The all-track handoff currently uses the local API; a batch UI remains future work.
