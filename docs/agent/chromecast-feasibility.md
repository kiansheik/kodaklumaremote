# Chromecast feasibility

Research snapshot: 2026-10-18 07:02 America/Sao_Paulo. Environment: desktop
Chrome/MV3 design research; downloaded public HTML/player bundle and official
Chrome, Google Cast, and W3C documentation.
No receiver, stream, or Web Store acceptance is implied by this document.

## Evidence and conclusion

The desired direct-playback architecture is possible in principle when the
receiver can independently fetch and decode an authorized HTTP(S) source. It is
not yet established for frameXTV. A URL accepted by the sender, a browser player
working, or a successful fixture does not establish receiver access.

Static inspection of `.local/recon/watch.html:29-44` verified that the site itself
already loads the official CAF SDK and initializes the Default Media Receiver
(`CC1AD845`) with `ORIGIN_SCOPED` auto-join. Inspection of the locally saved
`Player-Btc_vwEb.js` also found `requestSession`, `loadMedia`,
`RemotePlayerController`, subtitle handling, and a native button labelled
`Cast to TV / Chromecast`. This is an existing native Cast implementation,
not proof that its button appears or that its media plays on a TV. Check the
live control before inserting a second one; preserve a working native button.

Google's supported Web Sender integration loads
`https://www.gstatic.com/cv/js/sender/v1/cast_sender.js?loadCastFramework=1` in an
ordinary web page. The setup guide requires HTTPS. Initialization uses
`__onGCastApiAvailable`, `CastContext.setOptions`, a receiver application ID, and
the `CastSession`/`RemotePlayerController` APIs. See the official
[setup](https://developers.google.com/cast/docs/web_sender) and
[integration guide](https://developers.google.com/cast/docs/web_sender/integrate).

MV3 extension pages cannot add gstatic as an executable script source: Chrome
enforces a minimum extension CSP. A page-world context does not make remotely
loaded extension logic acceptable: Chrome's RHC guidance explicitly identifies
extension-created remote script tags, including first-party Google libraries,
as problematic. A packaged SDK bootstrap that later fetches executable code
has the same issue. See
[extension CSP](https://developer.chrome.com/docs/extensions/reference/manifest/content-security-policy)
and [RHC guidance](https://developer.chrome.com/docs/extensions/develop/migrate/remote-hosted-code).

Packaged MAIN-world code can access a page's existing SDK; isolated content
scripts cannot access the page's globals. MAIN-world scripts use the page CSP,
while isolated content scripts have their own CSP. This is a supported execution
mechanism, not a promise of Cast compatibility or store approval. See
[content scripts](https://developer.chrome.com/docs/extensions/develop/concepts/content-scripts#content-security-policy)
and [execution worlds](https://developer.chrome.com/docs/extensions/reference/api/scripting#type-ExecutionWorld).

## Recommended implementation

1. Detect the active standard video element and direct media candidates without
   executing arbitrary player strings. Accept only validated HTTP(S) media URLs;
   a `blob:` MSE object is not a receiver-fetchable source. Do not export cookies,
   request headers, storage contents, or credentials.
2. When the page already supplies a compatible Cast framework, use a small
   packaged MAIN-world adapter with narrow validated commands. Never cause it to
   download the sender SDK. Preserve the host application's existing receiver
   configuration rather than silently replacing it.
3. If CAF is absent or not ready, give an honest error/retry state. The current
   increment does not need another SDK loader or a companion because the target
   page already supplies CAF. Remote Playback is researched below as an optional
   future fallback; it is not part of the selected implementation.
4. A development-only ordinary `/cast-demo` page outside the extension may load
   the official SDK to test an authorized public source. Do not package its
   external script reference in the extension or confuse the demo with a
   verified target-site source. A separately hosted sender would be another
   architecture with its own explicit click and hosting requirement.
5. Until receiver playback is established, retain local playback. Clear listeners
   and stale media state on source changes, navigation, removal, and disconnect.

For automatic button insertion, restrict declarative content scripts to the
observed site origin. If an embedded player is on another origin, it needs an
explicit separately scoped permission; `all_frames` alone does not match every
origin. An action-based `activeTab` plus `scripting` implementation is a lower
persistent-permission alternative, but requires the user to invoke the extension.
See [frame matching](https://developer.chrome.com/docs/extensions/develop/concepts/content-scripts#specify-frames)
and [activeTab](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab).

## Remote Playback limits

Chrome documented desktop Remote Playback support starting in version 121;
Android support predates that. This makes it a valid browser-native candidate,
not a guaranteed route to a particular Chromecast. See
[Chrome's announcement](https://developer.chrome.com/blog/chrome-121-beta#remote_playback_api_on_desktop).

The W3C API binds to an existing `HTMLMediaElement`, requires transient activation
for `prompt()`, and reports `connecting`, `connected`, or `disconnected` states.
Availability and device protocol are implementation-defined; monitoring may be
unsupported. `disableRemotePlayback`, unsupported sources, unavailable devices,
and cancelled prompts must remain visible failures. The API does not expose the
CAF receiver/application and track-control contract. See
[Remote Playback specification](https://www.w3.org/TR/remote-playback/).

Do not claim a resolved `prompt()` means the TV fetched the URL, or infer direct
streaming from device selection alone. A device test must confirm playback on
the TV, remote controls, seek, disconnect, and continued playback when local
playback stops. Browser tab mirroring is a separately labelled fallback.

## Receiver and media barriers

The Default Media Receiver needs no developer registration and loads a media URL
sent by the sender. A custom receiver adds hosted code and registration, and can
implement provider-supported authorization and track logic. It does not grant
the TV the browser's authorization. See
[receiver choices](https://developers.google.com/cast/docs/web_receiver).

HLS/DASH require CORS, and codecs vary by receiver model. Subtitle resources also
require CORS; adding text tracks can introduce CORS requirements for otherwise
simple MP4 playback. Use the real content type rather than inferring it solely
from an extension. See [supported media](https://developers.google.com/cast/docs/media)
and [sender CORS requirements](https://developers.google.com/cast/docs/web_sender/advanced#cors-requirements).

Authorization, signed-URL expiry, referer/origin checks, LAN reachability, MIME,
codecs, and subtitles remain untested until observed from an authorized live
source and a receiver. A custom receiver or relay must not be presented as a way
to remove these server restrictions. A relay is deferred until evidence shows
it is necessary and the provider permits it; it also needs LAN, range/seek,
manifest-resolution, and resource/security testing.

## Research experiment

- Hypothesis: a remote Google SDK injected into the host page solves MV3 limits.
- Method: compared official SDK setup with Chrome CSP and RHC documentation.
- Result: host-page CSP differs, but RHC restrictions still apply to
  extension-triggered remote executable loading. Rejected this design.
- Confidence: high for the documented restrictions; no store review performed.
- Next experiment: test the packaged existing-SDK adapter using deterministic
  fixtures and a localhost-only development manifest, then test a known
  authorized receiver-compatible URL on physical hardware before attempting a
  frameXTV source. Remote Playback remains optional research.
