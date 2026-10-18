# Progress

2026-10-18 America/Sao_Paulo. See [experiments](experiments.md) for evidence.

| Phase | Status | Evidence / remaining work |
| --- | --- | --- |
| 1 Reconnaissance | Complete for observable bounded scope | Public HTML/chunks mapped; hashes recorded; two clean automated baselines. Manual DevTools/fingerprint trigger not isolated. |
| 2 Playback analysis | Actual HLS and Portuguese subtitles accepted by Kodi | Declared source copied from ordinary player; user confirmed picture/sound and matching Portuguese captions. Long-term expiry and alternate receivers remain open. |
| 3 Cast proof | Sender implementation and live SDK initialization checked | Public HTTPS demo uses shared controller; HEAD200 MP4/CORS/ranges. No selected/physical receiver, so direct-playback demonstration pending. |
| 4 Site integration | Cast fallback plus source/subtitle copy implemented | Page-owned CAF reuse, same-origin frames, preserve native UI. Copy actions worked on actual source; generic Cast target acceptance pending. |
| 5 Reliability | Automated checks passed | Source/nav/removal cancellation, errors, commands, subtitles, cleanup and simulated BFCache. Real device/fullscreen/audio-language limitations documented. |
| 6 Handoff | Prepared | README, experiment log, manual acceptance checklist, source map and session handoff. |

Follow-up: Philips43PFG6910/78 was discovered but disabled by native Chrome for
frameXTV/general tab mode (“Available for specific video sites”). YouTube's own
receiver path worked and the user confirmed TV picture/sound. This does not
establish arbitrary-source Cast. Improved session/media error classification was
built, tested and reloaded in the installed extension. Latest full check at that
point passed30 unit/16 browser tests; separate discovery-tool checks follow.

The final product is an Android/Samsung-phone controller whose receiver keeps
retrieving media after phone power-off. See [mobile target](mobile-target.md).
Kodi now accepts the actual target source; generic Google Cast acceptance is
still open. No custom receiver or relay is implemented. Manual Chrome tab casting is documented
separately as browser-dependent mirroring.

LUMA follow-up: manufacturer DLNA support claims and existing Android receiver
apps were researched. Kodi HTTP JSON-RPC provides a documented URL handoff;
VLC Android 3.6+ has authenticated Remote access, with URL playback supported by
released 3.6.5 source. The user reports native apps and a restricted app store,
but exact model/Android remain unknown. The later Kodi installation succeeded.
See [receiver interfaces](receiver-apps.md) and [LUMA options](luma-receiver-options.md).

Subsequent Kodi increment: user configured Kodi, identified over authenticated
HTTP as 21.2. Public Big Buck Bunny playback has user-confirmed picture/sound;
pause/resume and queued absolute seek were checked. Playback advanced with Mac
controller stopped. `npm run kodi` supplies a loopback public-sample controller
with no media relay or saved credentials. 44 Node tests (11 new Kodi) and three
Kodi browser tests passed; prior MV3 browser results unchanged. This completes
the initial LUMA receiver experiment, not the phone sender or intended-source
acceptance at that point. See [manual evidence](../../tests/manual-kodi.md).

Actual-source follow-up: frameXTV HLS played on Kodi with user-confirmed picture
and sound. All 19 declared subtitle tracks are registered; Portuguese (Brazil)
is selected and user-confirmed visible/matching dialogue. Copy actions and
source/subtitle controls are implemented; all-track loading/metadata binding
used the local API, not a batch UI. One track renders at a time. A rapid HLS
pause/seek/resume stopped playback; restart without seeking stayed active beyond
11 minutes. HLS seek reliability, full phone shutdown and URL renewal remain
open. Latest automated coverage is 60 Node / 32 browser tests; see the
[source/subtitle handoff](session-handoffs/2026-10-18-framextv-kodi-subtitles.md).

LUMA replacement remote: REA static analysis recovered the official client’s
native protocol. Browser+Mac bridge now connects to the actual receiver,
enumerates23 apps and launches Kodi; user confirmed launch and navigation.
Versioned discovery reply support makes the short normal browser search find
the ready receiver. Cold-boot delay, volume/text acceptance, touchpad/reverse
view and Kodi autostart remain. See [remote evidence](luma-remote.md).

Phone increment: packaged Android sender and native LUMA/Kodi bridge implement
Browse → launch/readiness → video → all declared subtitles → PT-BR/English →
no-scroll combined Remote. Separate primary/secondary panels, bounded discovery,
native cancellation and conservative subtitle retries are fixture-tested.
A 220-review public sample informs specific reliability priorities without
claiming usage telemetry or repaired firmware. Debug APK and final automated
results: [phone handoff](session-handoffs/2026-10-18-phone-handoff.md). Actual
Samsung and full phone-off acceptance remain required.

Phone baseline now installed on Samsung Android16 and user-confirmed controlling
LUMA. New0.2.0 implements background/system controls, encrypted validated setup,
cached-IP automatic connection, secondary Settings/reset, corner Home/Back and
observed-volume slider. First setup must authenticate and then continue the
selected Cast. Final checks and live acceptance:
[background/settings handoff](session-handoffs/2026-10-18-phone-background-settings.md).

Phone follow-up: the user confirmed picture, sound and Portuguese subtitles
from the actual Samsung sender, and reported a cold Kodi start stall and absent
notification/lock-screen controls. The repair adds a bounded idle-only Open
retry, preferred-first/lazy subtitle loading and system-control presentation
fixes. See the [control follow-up](session-handoffs/2026-10-18-phone-system-controls.md)
for final checks, installed artifact and remaining physical acceptance.
