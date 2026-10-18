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

Cast/Kodi validation: build and 60 Node tests passed; browser coverage is 25
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

No generic Google Cast target-source LOAD, long-term source expiry, physical
mobile playback or full phone-off acceptance is established. TV-side generic Cast app availability is
unknown. System Settings computer access was unavailable, so macOS local-network
permission was not inspected or changed. Chrome debug internals were disabled
and were not enabled. No anti-debugging/auth/DRM bypass was performed.

LUMA remote follow-up: user requested a replacement for the slow Kodak phone
remote and eventual Kodi startup. Static REA6.7.0/JADX analysis of archived
`com.caglobal.kodakluma` v1.0.116 recovered raw TCP8121 controls, UDP48689
discovery, key/text/app-list/launch frames. New `npm run luma` serves a responsive
browser remote at127.0.0.1:4176 with a loopback-only socket bridge; an ordinary
website cannot directly use these sockets. The archived third-party APK was not
installed/executed and Codex configuration was not changed. See
[protocol/evidence](luma-remote.md).

Physical acceptance: feature/pairing replies identified Kodak Luma and no
configured password. Firmware ignores optional `getServerInfo`, which is now
omitted. Actual app inventory contained23 entries including Kodi. Browser
**Open Kodi** was user-confirmed; Kodi HTTP was refused before launch and
identified21.2 afterward. Right/Left navigation was also explicitly confirmed.
Volume status returned6/30; volume changes and text rendering are not physically
confirmed. Discovery initially discarded the versioned `ECloudBox516` signature;
after correcting the parser, the normal browser **Find projector** window found
the receiver. Search is bounded2.5seconds once its service is available; cold
boot/service startup delay has not been eliminated or benchmarked.

Controls do not change startup/default launcher.
Transport/HTTP/discovery and browser tests are recorded in the
[remote handoff](session-handoffs/2026-10-18-luma-remote.md).

Android phone sender now implemented: separate browser/local-control WebViews,
native LUMA/Kodi adapter, user-triggered frameXTV source capture, Kodi launch/
readiness, sequential all-subtitle registration, PT-BR then English selection
and combined Remote. Phone UI is no-scroll with primary playback/volume/D-pad
and separate Browse/Devices, subtitle/typing/app panels. Native per-attempt
tokens revoke queued mutations on Cancel/source change; retry distinguishes
proved-unsent subtitle adds from uncertain receiver outcomes.

Final build passed,109 Node tests and54 browser tests passed. Native Android
fixtures passed8/8 and packaged UI smoke passed4/4, including no-scroll320×592
and native token lifecycle/bridge isolation. The debug APK is built locally;
emulator fixture validation is recorded in
the [initial phone handoff](session-handoffs/2026-10-18-phone-handoff.md). The
baseline APK was subsequently installed on Samsung Android16; the user confirmed
projector connection/control. Phone media/subtitles and full phone-off remain
unaccepted.
The [phone app](phone-app.md) and [Android README](../../android/README.md) explain
build/setup and remaining tests. Touchpad, reverse-screen and automatic boot
remain unimplemented. A bounded 220-review Google Play sample is documented in
[review findings](luma-review-findings.md); complaint counts are not usage data.

Subsequent phone request: foreground ConnectionService owns native I/O and
MediaSession notification/lock-screen controls. User explicitly authorized
saved login: SettingsStore encrypts validated Kodi credentials with Android
Keystore/AES-GCM and caches the verified private receiver address. Visible-app
retries use cache then bounded discovery; secondary Settings provides rescan and
scoped wipe. UI now has corner Home/Back, compact red/green status, volume slider
and ±. First setup opens/authenticates Kodi before saving and continuing Cast.
Current checks/phone boundary:
[background/settings handoff](session-handoffs/2026-10-18-phone-background-settings.md).
Final0.2.0 installed on phone. Build,109 Node/65 browser checks,8 native backend,
4 service,5 packaged UI and5 Keystore settings checks passed. Live updated phone
Cast/Portuguese, lock-screen controls and full phone-off remain pending.

Live updated-phone follow-up: fixed app diagnostics reported captured HLS with19
tracks, authenticated setup and launching/waiting/subtitles/selecting/remote/ready.
A read-only Kodi query then reported playing at04:51/22:35,19 available subtitle
tracks, enabled and index6 selected. This establishes phone handoff completion
and receiver state; actual projector picture/PT-BR rendering, lock controls and
phone-off still await explicit user confirmation.

Subsequent user confirmation: Samsung casting has picture, sound and working
Portuguese subtitles. Reported0.2.0 failures: cold Kodi launch could stall until
Cancel/Retry, and system controls were not visible. Read-only phone diagnostics
found permission granted, an unblocked posted foreground notification and a
fresh active playback session, but its session launch target was null.

Version0.2.1/code3 repairs the launch target, uses an ordinary notification
before verified media, requests immediate foreground display and publishes
Stop/Disconnect as modern Android playback custom actions. It also loads only
PT-BR initially (English fallback), keeps all website languages in service RAM
for on-demand selection and adds one guarded retry after acknowledged Open plus
confirmed idle Kodi. Unknown Open outcomes never overlap another Open; explicit
late prewrite proof permits retry. Current coverage:121 Node,69 browser and22
native Android checks; final mobile and packaged UI checks were rerun after the
last web-only receipt changes. Installed/open on Samsung; saved setup connected
automatically, ordinary public notification posted and session launch target
nonnull. Physical system-control display/actions and repaired cold start remain
pending. [Latest handoff](session-handoffs/2026-10-18-phone-system-controls.md).
