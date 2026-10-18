# Progress

2026-10-18 America/Sao_Paulo. See [experiments](experiments.md) for evidence.

| Phase | Status | Evidence / remaining work |
| --- | --- | --- |
| 1 Reconnaissance | Complete for observable bounded scope | Public HTML/chunks mapped; hashes recorded; two clean automated baselines. Manual DevTools/fingerprint trigger not isolated. |
| 2 Playback analysis | Static mapping + network metadata complete; receiver analysis partial | Same-origin embed → stream API → normalized source → HTML5/hls.js. HLS/MP4 MIME observed; codec/expiry/auth/receiver unknown. |
| 3 Cast proof | Sender implementation and live SDK initialization checked | Public HTTPS demo uses shared controller; HEAD200 MP4/CORS/ranges. No selected/physical receiver, so direct-playback demonstration pending. |
| 4 Site integration | Implemented and deterministic MV3 validation | Page-owned CAF reuse, same-origin frames, preserve native UI, declared source extraction. Target hardware acceptance pending. |
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
No native arbitrary-media receiver playback success is claimed. No custom
receiver or relay is implemented. Manual Chrome tab casting is documented
separately as browser-dependent mirroring.

LUMA follow-up: manufacturer DLNA support claims and existing Android receiver
apps were researched. Kodi HTTP JSON-RPC provides a documented URL handoff;
VLC Android 3.6+ has authenticated Remote access, with URL playback supported by
released 3.6.5 source. The user reports native apps and a restricted app store,
but exact model/Android and app installability remain unknown. Test an existing
receiver before implementing a custom APK. See [receiver interfaces](receiver-apps.md)
and [LUMA options](luma-receiver-options.md). No LUMA installation, playback or
phone-off acceptance occurred.

Subsequent Kodi increment: user configured Kodi, identified over authenticated
HTTP as 21.2. Public Big Buck Bunny playback has user-confirmed picture/sound;
pause/resume and queued absolute seek were checked. Playback advanced with Mac
controller stopped. `npm run kodi` supplies a loopback public-sample controller
with no media relay or saved credentials. 44 Node tests (11 new Kodi) and three
Kodi browser tests passed; prior MV3 browser results unchanged. This completes
the initial LUMA receiver experiment, not the phone sender or intended-source
acceptance. See [manual evidence](../../tests/manual-kodi.md).
