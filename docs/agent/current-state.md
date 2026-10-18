# Current state

2026-10-18: New repository; original empty `headers.txt` preserved. Public frontend
inspection and two clean automated browser baselines are complete. The site is a
React SPA with same-origin `/embed/` HTML5/hls.js playback and **existing native
Cast code**. `disable-devtool` clears the console and can navigate to
`about:blank`; both baselines reproduced clearing/blanking, but the detector and
manual DevTools/extension-fingerprinting reports remain unisolated.

Implemented: bundled MAIN-world MV3 fallback using
only the page's existing CAF SDK; same-origin frames; declared HTTP media sources;
remote controls; navigation/load cancellation/cleanup. Preserve visible native
Cast UI. A separate ordinary HTTPS demo uses the same controller with a public
Big Buck Bunny MP4. No SDK injection, relay, auth/DRM bypass, telemetry, or media
bundling is part of the extension.

Latest validation: build and 60 Node tests passed; browser coverage is 25
loaded-MV3 extension tests plus seven Kodi UI tests. Full check passed at 55 Node
and 32 browser tests; subsequent Node and narrow Kodi runs cover the final
media-scoped label registry and subtitle sorting. A final build and native-Cast
coexistence browser case passed after keeping copy buttons visible beside native
Cast. Cast/Kodi transports are
mocked in fixtures. Production and loopback-only test manifests are generated
from checked-in source configuration.
Live SDK smoke: Chrome 155.0.8059.39, 2026-10-18 07:15:24 America/Sao_Paulo,
HTTPS secure context, SDK ready, zero media requests; no receiver selected.
Current Google Cast sample MP4 HEAD:200, video/mp4, byte ranges, CORS `*`.
Live extension probe also confirmed execution in watch and same-origin embed with
CAF present; page blanked before any video/control appeared. It verifies actual
origin/frame injection, not live extraction or physical playback.

Live follow-up in the user's ordinary Chrome: the target player and extension
control were visible. Installed unpacked path verified as `dist/extension`.
Improved error classification was built, reloaded in Chrome and verified live;
the failure is at session establishment, not an observed media LOAD rejection.
After the user brought the TV online, native Chrome discovered **43PFG6910/78**
but disabled it for frameXTV with “Available for specific video sites”. YouTube's
own Play on TV enabled that same receiver; browser reported connected/playing
and user confirmed actual Big Buck Bunny picture and sound on TV. Pause/seek/
resume changed sender remote state; own YouTube test was stopped and returned to
local paused playback. This is app-specific playback, not generic Cast acceptance.

User's final mobile requirement: Samsung phone starts authorized media, then can
switch **fully off** while the receiver continues fetching it. Mac/extension is
only a stepping stone. User reports Samsung works in Philips “modo de
espelhamento”; Miracast is a strong hypothesis, not a captured protocol. Miracast
alone cannot meet phone-off receiver retrieval. A real Cast receiver or supported
UPnP MediaRenderer can in principle. A bounded probe with TV confirmed in normal
Home/YouTube mode found the exact target and read its SSDP device description,
but that description exposed no recognized service entries/AVTransport actions.
This does not prove the absence of DLNA in all firmware/modes, and it does not
establish a direct playback route. No media or control commands were sent.
See [TV evidence](philips-tv.md) and [mobile target](mobile-target.md).

Android receiver follow-up: user also owns an older Kodak LUMA with an app store
that blocks some apps; native YouTube/Crunchyroll/Amazon run, and YouTube is
discoverable only while open. Exact model, Android/CPU and store are unknown.
Current Kodak 350/400/450 pages advertise DLNA, which needs device confirmation.
Kodi's documented HTTP JSON-RPC `Player.Open` is the preferred programmable
receiver experiment if installable; VLC Android 3.6+ Remote access is another
candidate, with network-URL launch confirmed in released 3.6.5 source only.
AirPin and other commercial DLNA receivers are alternatives. An installable
thin receiver APK could receive LAN commands and own playback or launch a local
player. These were researched routes; Kodi now has actual target-media playback below.
See [LUMA options](luma-receiver-options.md) and [receiver interfaces](receiver-apps.md).

User subsequently installed/configured Kodi. A bounded Bonjour query discovered
its HTTP service and resolved a private address/port matching the user's report.
Authenticated connection identified Kodi 21.2. `Player.Open` of the public MP4
started receiver playback; user confirmed projector picture and sound. Pause/
resume succeeded, and later position confirmed an absolute seek to 90 seconds.
Playback advanced with the Mac controller process stopped (28-second bounded
check). `npm run kodi` serves a loopback-only UI for credentials, public sample,
direct video links and subtitle selection/loading. It sends only Kodi JSON-RPC,
with no media fetch/relay or saved credentials. Native Chrome's password
notification initially obstructed clicks, not an established app-code failure.

Actual frameXTV HLS now played on Kodi 21.2: ordinary native Chrome showed the
watch/embed player, and the extension's **Copy video link** supplied its declared
source to the controller. User confirmed episode picture and sound. A rapid
pause → seek60 → resume sequence was followed by playback stopping by itself;
the cause is unresolved. Restarted without seeking at 08:24:09, it remained
active through 08:35:28 (paused at 531/1355 seconds). Playback progressed while
the Mac controller server was absent; this is still not a full phone-off test.

**Copy subtitle list** captures every declared validated track, including the
site's hidden/custom-caption tracks. All 19 were registered in Kodi using
sequential `Player.AddSubtitle` calls and observed track-index polling. Portuguese
(Brazil) was selected/enabled; user explicitly confirmed visible subtitles
matching dialogue. Other tracks were registered, not individually render-tested.
The Mac dropdown sorts Portuguese first, English second, then other languages;
one track is visible at a time. Kodi reports generic external names, so an
in-memory alias registry associates copied metadata with receiver + SHA-256 of
the verified current source. Aliases vanish on server restart and do not rename
Kodi's own menu. Batch import is currently an API-driven test procedure, not a
controller UI feature. See [Kodi evidence](../../tests/manual-kodi.md) and the
[latest handoff](session-handoffs/2026-10-18-framextv-kodi-subtitles.md).

No generic Google Cast target-source LOAD, long-term source expiry, mobile app
or full phone-off acceptance is established. TV-side generic Cast app availability is
unknown. System Settings computer access was unavailable, so macOS local-network
permission was not inspected or changed. Chrome debug internals were disabled
and were not enabled. No anti-debugging/auth/DRM bypass was performed.
