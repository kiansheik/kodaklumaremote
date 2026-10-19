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

0.2.1 physical follow-up failed: user saw no buttons/lock controls. The phone
had a posted ordinary notification with only Disconnect and an inactive/NONE
media session while Kodi continued playing: the APK restart lost its RAM remote.
User also reported repeated starts/no subtitles on S1E2. Own logs showed native
errors in the subtitle phase and repeated SUBTITLE_TIMEOUT, with Kodi reporting
zero tracks. The old3-second window was shorter than a native multi-RPC read.

0.2.2/code4 now installed: fresh active public playback is adopted without Open/
Add if no remembered source or active handoff exists. Ordinary playback controls
and the media card are separate; latest-state notification posts coalesce at
most once/second. Subtitle windows30seconds, bounded transient read retries,
fresh registration windows and uncertain-Add reconciliation preserve single
writes.128 Node and the full70 browser cases passed. Updated Service4 and
packaged UI5 passed after fixing posted-card
timing in fixtures. On actual Samsung,2 confirmed live checks passed ordinary
notification Pause/Play and MediaSession Pause/Play, preserving the identical
source and leaving it playing. The no-confirmation gate rejected on emulator.

S1E2's public PT-BR file returned200 quickly,268 cues, SRT body despite VTT API
label (same known mismatch as S1E1). This alone does not establish a parse cause.
A single public stream did not match Kodi's current source exactly, so no Mac
subtitle mutation was sent. Phone opened the exact S1E2 link; user asked to Cast
once and check visual notification/lock controls. Those results remain pending.
[Current handoff](session-handoffs/2026-10-18-phone-reattachment-readiness.md).

0.2.2 user acceptance: notification-shade and lock-screen Play/Pause are visible
and functional. S1E3 failed physically: first attempt exited Kodi to LUMA Home;
Retry played video without subtitles. Own logs show Kodi becoming unreachable
during subtitle preparation, then AddSubtitle KODI_RESPONSE on Retry. The crash
cause is unconfirmed; no successful new subtitle rendering is claimed.

0.2.3/code5 is built, installed and open on Samsung. Ordinary controls now
prioritize Volume − / Play-Pause / Volume +; MediaSession volume custom actions
come first, with Stop/Disconnect secondary. Relative volume reads the receiver
back immediately; distinct projector/action vectors and observed volume improve
the cards. Main Remote has a confirmed Power off icon using the archived Kodak
app's KEYEVENT26 flow. Cancel/Escape/backdrop send nothing; accepted send tears
down local controls, pauses reconnect and retains saved setup. Physical
shutdown versus standby remains untested.

Verified known JSON-RPC rejections now permit explicit Retry without preserving
an impossible pending Open/Add. Unknown/malformed/mismatched/internal response
outcomes remain protected against duplicate writes. Safe numeric HTTP/RPC
diagnostics support the next live investigation; this is not a crash fix claim.
130 Node passed. Full browser run73/74 exposed a long-title layout overflow;
after its narrow fix all31 mobile cases passed, covering all74 current browser
cases across runs. Final APK build and native Backend9/Service5/packaged UI5
checks passed. The live phone suite sent zero controls: cached receiver was
absent while encrypted Kodi settings remained; user confirmed projector OFF.
Actual new volume/power and S1E3 retest remain pending. See
[handoff](session-handoffs/2026-10-18-phone-volume-power.md).

Distribution follow-up: user explicitly authorized publishing to their empty
public `kiansheik/kodaklumaremote` repository, GitHub Pages install/update metadata
and signed APK releases using their existing local Android key.0.2.4/code6 adds
optional foreground update offers (five-minute throttle; Later24h), manual
Settings checks, verified consent-only downloads and Android installer handoff.
Site sources, strict derived release manifests, local signing/publishing helpers
and a scoped Pages workflow are implemented. Signing material stays local;
only the public certificate fingerprint is tracked. Source history and new
publication files are reviewed before push. No projector changes were made.

Local checks:134 Node,77 browser, updater6 and packaged UI5 passed. Release
build/lint passed after fetching the missing official lint dependency; an
initial Intent chaining compile error was fixed. Emulator signing proof passed
debug → private API33+ lineage bootstrap → ordinary production signer-only APK,
preserving encrypted credentials and a private marker without uninstall/reset.
Final publication and actual-phone migration evidence are recorded in the
[distribution handoff](session-handoffs/2026-10-18-github-release-updates.md).

Public0.2.4 released from6e4b7df; APK740,817bytes and SHA-256
`e41a99e4e9120bb1abdeb0217615e920859328b56b4ff280a8f98a6010360adf`.
Real GitHub/CDN download returned200 and matched hash/size. Account Pages uses
`https://kiansheik.io/kodaklumaremote/`; HTTPS page and update.json returned200
with exact release metadata. The original custom workflow deployed after adding
the required release-tag environment rule. Actual Samsung then passed read-only
signer/decryption checks before bootstrap, after bootstrap and after installing
the exact public APK; encrypted Kodi login survived with no uninstall/reset.
User follow-up prefers `make deploy-gh-pages` and direct static branch hosting;
that replaces the custom Actions deployment while retaining the release/feed.
The real Make target passed; Pages now has `build_type=legacy`, source
`gh-pages:/`, HTTPS enforced and a successful branch build. Seven focused
release/deploy checks passed. Public files are the five site assets,
`update.json` and `.nojekyll`; no APK/key/wiki is pushed to that branch.

Dual receiver follow-up: user opened **Media Station X** on the Philips and
requested a compact LUMA/Philips switcher with both connected simultaneously.
Version0.3.0/code7 adds independent target state/transports, private saved target
selection and selected-target notification/MediaSession controls. LUMA retains
its projector/Kodi controls. Philips provides playback, seek, subtitles and
player volume, with unsupported hardware controls hidden.

The phone hosts a private-interface MSX asset/control listener, normally4178;
MSX Start Parameter setup uses the displayed IP:port. The user opens Connect
this TV, then approves the pinned TV on the phone. Pairing/capability/port are
saved privately. TV media and subtitles are fetched directly from their public
origins. A TV-owned SRT/WebVTT renderer loads PT-BR first, English fallback and
other languages on demand. Command receipts and independent heartbeat avoid
replaying unknown Opens; failed subtitles preserve correctly playing media.

Checks so far:154 Node, full84 browser plus final41 mobile (including9 receiver
cases) passed; the final added setup instruction also passed the narrow-layout
case. Final native regression checks passed35 (backend9, service5, settings5,
packaged UI5, MSX7, dual-service4). APK/debug lint passed
after replacing an API24-only Map call for API23 compatibility. Isolated
`.receiverfixture` builds preserve the emulator's existing production-signed
app. Final publication/install and complete native regression results are in
the [dual-receiver handoff](session-handoffs/2026-10-18-dual-receiver-msx.md).
Signed0.3.0 is installed/open on Samsung. The existing read-only signer and
credential-decryption check passed before/after; encrypted Kodi login survived.
Published from72a600e; public APK/download digest matches the963,544-byte signed
file. Pages build590e0b2 completed; HTTPS install page/feed returned200 and the
feed exactly matches0.3.0 metadata. Production signing material remains local.
Actual Philips playback, subtitle appearance, HTTP/mixed-content behavior and
phone-off continuation remain unconfirmed. [Domain notes](msx-receiver.md).

MSX physical follow-up failed: the TV reports **Server responded with status0**.
The actual phone listener is reachable, but0.3.0 rejected browser OPTIONS.
Version0.3.1/code8 adds restricted CORS preflights, safe TLS/bootstrap diagnostics
and fixed setup hints with explicit HTTP padlock instructions. Preflights cannot
claim a pending TV or return a capability. Actual TV failure cause remains
unconfirmed until a diagnostic retry; forced HTTPS is still a possible cause.

Final checks:22 focused Node,43 mobile browser,8 MSX native and4 dual-service
native cases passed; isolated debug/test APK+lint and signed release+lintVital
passed. Signed0.3.1 is installed/open on Samsung; read-only signer/decryption
checks passed before/after, preserving Kodi login. Publication and the actual TV
retry are tracked in the [setup handoff](session-handoffs/2026-10-18-msx-setup-matter.md).
Source056b6c9 and signed releasev0.3.1 are published; APK965,037bytes with
SHA-25689630f78e8fb41194aa9a3fb82513297e350a0589fad15f769b2e4b300342cec.
Pages deploymentce82fa2 is built. HTTPS install page/feed returned200 with exact
0.3.1 metadata; public APK returned200 and matched size/SHA-256.
The user retried0.3.1 and still saw status0. A safe phone log has one generic400
without origin; this can also be an abandoned TCP connection, so it does not
establish a parser failure. A temporary Mac bootstrap diagnostic also failed on
the TV and saw a zero-byte TCP timeout, no HTTP/TLS. The user declined further
manual browser tests because the TV is outdoors in the sun and explicitly
redirected work to Matter automation. Both temporary Mac listeners were stopped.

The user redirected work to Matter automation to avoid repeated outdoor TV
steps. A separately pinned Matter.js0.17.9 Mac controller commissioned the
Philips and read three endpoints: Root, Basic Video Player and Speaker.
KeypadInput/MediaInput/OnOff and speaker LevelControl are present; no
ContentLauncher/ApplicationLauncher/ApplicationBasic server appeared. Matter
therefore supplies a hardware remote path, without an established arbitrary
website-video/app-launch/external-subtitle path. [Inventory](philips-matter.md).

Actual Mac checks: Home returned status0; speaker volume was freshly observed
1→2→1, restoring the starting level in `finally`, with both writes status0 and
verified readback. A Right attempt failed before invoking its key; Left was not
sent. A confirmed private IPv4/advertised-port hint succeeded after a bounded
operational mDNS query returned no results; a subsequent CASE/descriptors/speaker
read took4.4seconds.
The Mac also successfully opened an enhanced commissioning window, so phone
pairing did not require another visit to TV settings. Private codes/fabric and
network details remain ignored. Mac commissioning used the library's default
attestation policy and does not prove production PAA trust.

Version0.4.0/code9 now implements the native Android Matter controller and is
installed on Samsung. Read-only signer/decryption checks passed before/after,
preserving existing Kodi login. Manual code/private camera-QR pairing saves an
AES-GCM/Keystore-protected fabric; each phone enrolls separately. Capabilities
drive navigation, speaker volume, inputs and confirmed Off. Add another phone
requests a temporary QR/manual-code window; codes leave the UI on close/expiry.
LUMA/Kodi remains independent, and Philips combines Matter hardware controls
with MSX video controls. Actual phone manual-code pairing completed through the
Mac-opened window with production trust enabled, without another TV setup step.
Phone Volume+1→2 and Volume−2→1 were each independently read back from the Mac;
starting volume is restored. Actual phone **Add another phone** opened a
TV-accepted enhanced window and displayed a fresh manual code/QR/expiry notice;
dismiss/reopen left no code or QR. No second phone was enrolled. Navigation/Power
controls are enabled; phone key/Off/input effects, camera scan and second-phone
enrollment remain pending.

The app now requires Android API24+/ARM64. Official CHIP SDK source is pinned
to3584c8c8552f4a0d471f2aad24a15b478959c230 with Kotlin2.1.10; locally built
JAR/JNI/trust artifacts are verified against97 hashes, with16KB ELF/ZIP alignment,
eight JARs/two libraries/72 production PAA/five CD roots. Progress/detail SDK
logging is disabled and checked to prevent pairing-code leakage. The upstream
issuer's default test IPK remains documented in the [build path](matter-android-path.md).

Final review adds an explicit **TV unavailable? Forget on this phone** recovery
button in the Forget confirmation. It removes only the saved TV node/cache,
retains the encrypted controller CA and LUMA setup, sends no TV command and
reports local-only recovery; the TV may still retain this phone's controller
entry. There is no automatic reset. The Matter-only Settings Disconnect button
is also fixed. Async commissioning-state changes reschedule service polling and
update foreground lifetime; per-attempt `stopDevicePairing(pairingNode)`
preserves the shared I/O factory. Explicit null volume/input state clears old
cache. The UI uses only a connected speaker-capable Matter volume or connected
MSX player volume, so re-pairing to a no-speaker TV cannot show stale40%.

Final source checks passed:54 mobile browser,22 focused Node and7 QR JVM;
18 final focused native checks (Matter7/service6/packaged UI5), debug/test APK
and lint. Earlier unaffected native26 (backend9/settings5/MSX8/dual receiver4)
also passed:44 native checks across runs, not a fresh full44-case rerun. These
fixtures are separate from the actual-phone pairing/speaker proof above.

Release0.4.0/code9 is published from source
4d487cf2fdd3613b1d098838cfa5a23134871b0b. Final signed build/lintVital and
ZIP16KB alignment passed. APK9,631,440bytes, SHA-256
`a3f03b098f739d7e4253a0716771cbe7e8a5d7cd1fcf4aa6be73e6d6d93766fa`.
That exact signed APK was installed on Samsung; signer and saved Kodi Keystore
decryption passed before/after, with receiver/Kodi setup retained. Own-process
service logs returned to foreground=true/connected=true on the saved Philips
target without a new setup code. `verified=false` is expected with no verified
MSX media. The phone's secure PIN blocked foreground UI inspection after this
upgrade; at that point only service-level reconnection was established. No
further lock-screen dismissal attempts were made. The user later confirmed the
signed phone app immediately recognizes the TV and navigation visibly works
after switching the TV off/on. This establishes actual saved-connection and
navigation acceptance; it does not establish phone Off/input or media playback.

GitHubv0.4.0 is public. Pagesc542b9089cb27708d34478690de1b0625b314dc0 is built;
HTTPS install page returned200 with ARM64 compatibility text. HTTPS update.json
returned200 and exactly matched the signed0.4.0 manifest (code9/minSdk24).
Public GitHub APK download returned200 and matched the installed APK's size/hash.
See the [native phone handoff](session-handoffs/2026-10-18-native-matter-phone.md).

The user next explicitly authorized using the existing Matter connection to
set up TV playback without more outdoor manual navigation. A fresh Mac Home
command returned status0 (CASE57ms, descriptors14ms); it does not prove which
screen has focus. Exact-TV SSDP still supplied no services, and the documented
debug/screenshot routes on9222/7001 were unavailable in bounded checks.

A separate Philips JointSPACE route is now observed: HTTP1925 `/1/system`
and HTTPS1926 `/6/system` returned200 and identify API6.1/Linux. Advertised
features include key/unicode input, text-entry context and browser activities,
with Digest pairing/secured transport. Protected HTTP activity returned403;
HTTPS context/activity/screen-state returned401 with a Digest challenge. This
is distinct from Matter authentication. The first30-second PIN window expired;
a fresh explicit request and hidden-stdin PIN grant then returned SUCCESS.
Credentials are saved privately with mode0600; the PIN is not persisted.
Authenticated context returned200, but its Skype/Options/NA labels disagreed
with the user's visible Home screen, so it is not reliable focus evidence.
Browser POST returned200 (`pageLoadVerified:false`), while the user still saw
Home and our receiver recorded no TV TCP/assets/hello. Application enumeration
was rejected; no undocumented application-launch payload was attempted.

`scripts/philips-controller.py` passed15 offline fixtures. Its new stdin/hidden
Unicode command follows maintained upstream
`input/key` semantics and reports acceptance without claiming text was applied.
Advertised text-entry context flags do not establish a field-replacement API.
A separate Mac
direct-browser proof (`scripts/msx-mac-proof.mjs`) reuses the existing receiver
assets/channel with explicit private interface/TV peer, secret URL and separate
loopback-only authenticated control. It passed13 loopback fixtures plus syntax
checks. It serves no media/subtitle proxy and makes no source fetches. Its tests
did not contact devices. Root subsequently started the temporary actual LAN
listener; sanitized status is running=true/connected=false, assets0/hello0.
The private launch configuration remains prepared. After the TV power cycle,
one explicit saved-URL browser retry again returned
`accepted:true/pageLoadVerified:false`; immediate TCP/assets/hello counts
remained0. The user confirmed that retry did nothing and Home remained visible.
A later context read reported `contextObserved:true,focus:{}`. The user
identified a recent Netflix tile at the left edge of Home and said no photo
was needed. Continue from that known starting tile using text feedback, while
API context remains unreliable. Root sent one Matter Right from Netflix;
endpoint1 returned SendKey status0 (CASE62ms/descriptors17ms). The user confirms
this reached the regular Netflix tile; no Select or media was sent.
The proposed browser route could avoid MSX
startup setup; actual browser
GET/hello, autoplay, video and PT-BR remain untested.
These Mac tools are not integrated into the published0.4.0 APK.

A subsequent exact-TV DIAL check found a separate direct app-launch route.
Unicast search returned0; bounded standard multicast for DIAL/MediaRenderer,
filtering the paired TV address, found one43PFG6910 descriptor with an
`Application-URL` response header. The old discovery path discarded this
header; empty XML services did not exclude DIAL. At the advertised base,
YouTube GET403 and Netflix GET200/stopped preceded exactly one empty Netflix
POST:201, then GET200/running. The user confirmed Netflix visibly opened.
No Select/navigation accompanied this launch,
and no arbitrary URL/media/subtitle delivery is established. The private
advertised base remains under ignored mode0600 evidence. The read-only discovery
script now preserves only a validated `dialAdvertised` boolean, requiring the
descriptor's numeric private peer and no credentials/query/fragment. Eight
discovery fixtures, syntax and whitespace checks passed. Root's live
`node scripts/discover-tv.mjs --no-save` then returned one exact-model target,
`dialAdvertised:true`, empty serviceTypes/AVTransport and zero errors, without
app requests. No APK or phone feature changed.

The user rearranged favorites so the browser is one Right and MSX two Rights
from the initial Home tile. A Home→Right→Select batch unexpectedly reached
HDMI1, as the user confirmed; its cause is unproven. Root then sent Home alone
and waited: the user confirmed real Home with the first tile highlighted.
Only then did Right→Select run (both status0, CASE49/55ms), showing “Editar speed
Dial”. That screen remains ambiguous. One Back
and another accepted JointSPACE browser request then returned to HDMI1, with
receiver counters still0. The user then confirmed JointSPACE named Home
visibly returned HDMI1→Home. JointSPACE Right, a700ms pause and Confirm were
accepted, but the resulting screen is unknown because the user left before
replying. All device work/questions stopped at that boundary; saved pairings
and the temporary listener were preserved. No browser/MSX launch is established,
and the cause of earlier outcomes remains unproven. A key acknowledgement alone
cannot prove focus. No new phone feature or release was made.

The saved S1E2 source returned403 to a bounded HEAD request. An initial egress
approval was rejected; after offline provider inspection, the same-provider
request was approved and its403 confirmed, without auth/redirect workarounds.
Ordinary Chrome again blanked the episode page to about:blank; direct public
configuration GET also returned403. No source bypass was attempted and a fresh
usable source is unresolved. Two private Open-command fixtures are prepared
offline for public and S1E2 playback (16 declared tracks, PT-BR preferred); this
preparation made no requests and does not establish source reachability.
No new TV playback/subtitle success is claimed.
See the [browser setup handoff](session-handoffs/2026-10-18-philips-browser-setup.md).
The latest [power-cycle and browser retry follow-up](session-handoffs/2026-10-18-philips-power-cycle-follow-up.md)
records the new phone acceptance without a new app release.

Release preparation: the user requested building/deploying the latest source.
Android version is now0.4.1/code10, a maintenance build of the existing phone
features. New Philips/DIAL work is desktop tooling, not phone integration.
Focused release/deploy/discovery/receiver Node checks passed28/28 and Philips
Python fixtures15/15. Initial loopback sandbox denials were resolved by running
the fixtures with loopback permission. The first signing attempt selected an
older Gradle8.11.1 cache; the project uses cached Gradle9.6.1. The existing
production certificate matches. Final committed-source build and publication
are tracked in the [maintenance release handoff](session-handoffs/2026-10-18-release-0.4.1.md).

Release0.4.1/code10 is now public from source
`a84dbb1ef360d1351861e076f68e00dcc784bb82`. Signed committed-source build and
lintVital passed; production certificate and16KB ZIP alignment verified.
APK9,631,440bytes, SHA-256
`a650dd4d44c8413bcd01b38c7c8d68f13ce758fdccc222222e0f36b43bc008e7`.
GitHub release assets are uploaded/public. Pages commit
`f38fa65de56ee6a59045a45021fd4a88b4008b3a` is built; Chrome rendered0.4.1 and
the exact download link. Anonymous HTTPS feed/APK returned200; served metadata
and downloaded bytes/signature match the prepared release. The Python HTTPS
client lacked a trusted CA bundle; system curl and Chrome verified successfully
without trust changes. No installation, device commands or new phone features
were part of this maintenance deployment.

Motorola follow-up: Julia's phone needed about three attempts to start a movie
on LUMA/Kodi, kept showing startup after playback began, never loaded subtitles,
and lost its lock-screen card after an ineffective Pause. The user identified
the title as Bly Manor. The Motorola is unavailable for debugging and the exact
episode/source is unknown; this report does not establish a device-specific cause.

Version0.4.2/code11 now prepares startup, subtitle and background-control fixes.
The coordinator observes exact-source playback while Open is unresolved or its
response is unknown, without replaying uncertain writes. Kodi's null
`currentsubtitle` now means its subtitle player is not ready; an empty object is
ready without a selection. Initial subtitle failures preserve verified playback,
the full captured catalog and a subtitle-only retry. English fallback requires
explicit proof that the preferred Add was not applied.

Native Kodi operations share one five-second budget across their RPCs, require
exact mutation acknowledgements and reject mixed-source observations. The
service retains prepared catalog metadata before handoff completion, preserves
a matching stale media card on transient HTTP failures and separates Kodi HTTP
control from projector TCP availability. Every system media action gets a
bounded30-second CPU wake lock and fresh source/revision checks before writes.
Per-operation JS abort no longer revokes the whole cast job; explicit user
Cancel and source changes still revoke it.

Final Node184 checks (including coordinator52), mobile browser56,
debug/AndroidTest builds and lintDebug passed. Isolated `receiverFixture`
checks passed: Service10, Backend12, packaged UI5, settings5 and dual receiver4.
The service test used a genuinely screen-off/keyguard-locked emulator after its
Activity closed, verified Pause/Resume and bounded CPU-lock release, and restored
the original emulator lock setting. Final review also caught and fixed a
same-URL recast blocked by an earlier unknown response: only an actual native
completion receipt plus prior matching playback can release that cached attempt.
Late bridge receipts are bounded and never justify overlapping unknown writes.
Release0.4.2/code11 is public from source
`976a1c3b643193ca0bf5f92ecdef8fcd1a4d96e7`. Committed-source signed build,
lintVital, production certificate and16KB ZIP alignment passed. The APK is
9,632,212bytes, SHA-256
`886e1711953a88257f8dbd0fdbc5c2dd85d859cc11c2cf469ee8eb7609a9032f`.
Pages commit`f87edf802420f0a71e0bf55342dcc6e691f28378` is built/no error.
Anonymous feed/APK returned200; served metadata and downloaded bytes/signature
match the prepared release. Chrome rendered0.4.2 with the exact APK link. No
physical phone/projector/TV actions were sent during this repair. Motorola
locked-screen controls, the exact Bly Manor episode and visible subtitles still
need physical acceptance. See the
[recovery handoff](session-handoffs/2026-10-18-motorola-kodi-recovery.md).

2026-10-19 follow-up: the user still reports first-start Kodi crashes, hangs and
missing subtitles. They requested video-first playback with manual language
selection, one-second command spacing and coalesced volume.0.4.3/code12 now
removes all startup subtitle reads/adds/selections; the full captured catalog
remains available after exact-source playback opens Remote. Launch settles for
one second before readiness, and subsequent playback/subtitle mutations are
paced with cancellation and fresh source checks. MSX also receives an explicit
manual-subtitle startup policy; older clients retain their existing default.

The main volume slider and ± buttons show the requested value immediately.
LUMA's service shares one two-second trailing cooldown with notification and
lock-screen ±, then sends a single absolute volume change. Projected state
survives old reads; receiver/source changes revoke unsent writes. Philips UI
volume is independently debounced and explicit switch/disconnect/cast/reset/
power actions cancel its unsent timer. Node181 checks pass (coordinator46 and
MSX20 included), as do all57 mobile browser checks and the isolated debug/test
build plus lintDebug. Native40 checks pass (service14/backend12/UI5/settings5/
dual receiver4). Final service14 and packaged UI5 were rebuilt/retested after
the callback-delivery projection fix; the deterministic delayed-reply regression
passed. The Activity was closed and emulator screen/keyguard locked for system
actions, including mixed UI/system volume bursts, in-flight new input and target
cancellation. Signing/publication follows in the
[video-first handoff](session-handoffs/2026-10-19-video-first-volume.md).
No physical crash diagnosis or new household-device success is established.
