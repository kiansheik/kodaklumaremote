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

Latest validation: build, 33 Node unit/diagnostic tests, and 16 loaded-MV3 Chromium
fixture tests passed. The full check passed 30/16 before three independent
discovery-tool tests were added. Cast is mocked in fixtures. Production manifest and separate
loopback test manifest are generated from checked-in source configuration.
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

No generic receiver/media LOAD, target codec/CORS/expiry, mobile app or phone-off
acceptance is established. TV-side generic Cast receiver/app availability is
unknown. System Settings computer access was unavailable, so macOS local-network
permission was not inspected or changed. Chrome debug internals were disabled
and were not enabled. No anti-debugging/auth/DRM bypass was performed.
