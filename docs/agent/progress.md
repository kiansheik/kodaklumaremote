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
