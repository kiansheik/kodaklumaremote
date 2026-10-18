# Physical Chromecast / Google TV acceptance

Status: **partial app-specific hardware test**, generic Cast still unverified.
Philips43PFG6910/78 was discovered but disabled for frameXTV with “Available for
specific video sites”. YouTube's public Blender film connected; the user
confirmed actual TV picture and sound. Sender pause/seek/resume and stopping the
test were exercised. This does not pass the extension's arbitrary-media path.
Automated tests use a fake CAF SDK and cannot prove
device discovery, network access, decoding, TV output, or live subtitles. Fill
this checklist with observed results; do not turn an untested item into a pass.

## Record the environment

- Date/time and timezone:
- Repository revision or local diff identity:
- OS and Chrome version:
- Receiver model, firmware, and connection type:
- Browser and receiver on the same reachable network:
- Browser local-network permission, if requested:
- Source tested: public demo / authorized frameXTV episode (no signed URL):
- Result: untested / blocked / partial / passed:

Use the user's ordinary authorized playback flow. Do not copy cookies,
authorization headers, temporary source URLs, or raw media into this document.
Record sanitized error codes/messages and content types only.

## 1. Isolate the receiver with the public demo

1. Run `npm ci`, `npm run build`, then `npm run demo` from the repository root.
2. Open `https://localhost:4174/cast-demo` in desktop Chrome. Accept the warning
   for this generated localhost development certificate. The server binds only
   to loopback; the TV receives the public media URL, not a localhost media URL.
3. Confirm the Cast button changes from its SDK-loading state to an enabled
   device-selection button. If it stays loading, record the SDK/network failure
   and stop this test. A page rendering does not prove SDK readiness.
4. Click the button and select the intended device. Confirm the Default Media
   Receiver opens and the TV actually shows the Big Buck Bunny public sample
   with audible sound. A resolved LOAD alone is insufficient.
5. Confirm local browser playback is paused and the TV continues. Verify this is
   media playback on the TV, rather than a mirrored browser page.
6. Pause from the demo, observe the TV pause, resume, and observe it continue.
7. Seek forward and backward. Confirm the TV lands near each requested time and
   the sender's position updates as playback advances.
8. Change volume to a moderate level and confirm the receiver responds.
9. Disconnect. Confirm the receiver session ends and local playback stays
   paused at the returned playhead. Resume locally with the normal player.

This demo loads Google's SDK as an ordinary webpage and uses the same
`CastController` adapter as the extension. Its public MP4 has no configured
subtitle/audio-language selector; mark those items **N/A**, not passed. Passing
this section isolates the general Cast path and says nothing about frameXTV's
provider-specific media access.

## 2. Check the target's existing native Cast control

1. Load the production unpacked build from `dist/extension` using
   `chrome://extensions` → Developer mode → Load unpacked. Reload the page.
2. Open `https://framextv.xyz/watch/284833/1/1` and establish ordinary authorized
   local playback. Use a normal Chrome profile/session before adding diagnostic
   tools. If playback or the page is unavailable, record that boundary.
3. Inspect the player controls. If the site's Cast control is visible, verify
   the extension does not add a duplicate button. Record whether the site's
   existing button discovers the receiver and actually loads the TV.
4. If the native control is hidden/unavailable and the extension fallback
   appears, continue below. Do not hide or alter a working site control to
   manufacture an extension success.

The watch page normally embeds a same-origin `/embed/` player. The extension
can run inside that matching frame. Cross-origin backup embeds are outside the
manifest scope and should not receive an extension control.

## 3. Verify the extension's direct playback and controls

1. Click the extension's Cast button once. Confirm a native device picker opens
   from the click and includes the intended receiver. Cancel once: normal
   playback must remain unchanged and a retry must remain possible.
2. Select the receiver. Confirm picture, sound, and the intended episode on the
   TV. Verify local playback pauses only after successful LOAD. If the receiver
   reports a playback failure, record it as failed/partial rather than success.
3. Pause/resume, seek forward/backward, and change receiver volume with the
   extension controls. Observe each effect on the TV and the returning status.
4. If VTT subtitles are exposed, select each available language and then turn
   subtitles off. Confirm actual captions on the TV. Record language names and
   success/failure without subtitle URLs. No exposed compatible track means
   **N/A**. The extension does not convert SRT or internal HLS tracks to VTT.
5. Record audio-language availability. The extension has no audio-language
   selector; if the source exposes multiple audio languages, report that control
   as unsupported. With no alternate audio tracks, mark it **N/A**.
6. Disconnect from the extension, then repeat using the receiver/Chrome session
   UI. Confirm remote controls reset and local video stays paused at the last
   available remote position. Local playback should resume only on user action.
7. Confirm the ordinary player remains usable after a failed LOAD, cancelled
   picker, and successful disconnect. Do not export its browser-only credentials
   to make the receiver work.

## 4. Check navigation and lifecycle

1. While casting, select another episode. Confirm the old session is cleaned
   up, the new local source is not accidentally paused or seeked to the old
   episode's position, and an explicit new Cast click loads the new episode.
2. Start Cast selection and change episodes before selecting a receiver. Also
   repeat while LOAD is pending if reproducible. Completing the old request must
   not pause the new episode or retain stale remote controls.
3. Exercise a dynamically replaced player and a source change in the same video
   element. Confirm exactly one applicable Cast control and no stale commands.
4. Navigate away, then return with browser Back/Forward. Confirm controls restore
   correctly, including a BFCache return if Chrome uses one.
5. Exercise wrapper fullscreen, narrow window sizing, and keyboard focus. Record
   any limitation when the browser makes the `<video>` itself fullscreen.

## Failures and fallback

An empty picker can reflect browser/device permissions, network isolation, or
device discovery. Successful discovery with failed playback can reflect receiver
access, expired URLs, CORS, codecs, subtitles, or provider policy; record what is
known and leave the cause open without evidence. See
[feasibility](../docs/agent/chromecast-feasibility.md).

If the live page blanks or execution changes under automation/DevTools, record
that observation. Do not bypass anti-debugging, authentication, DRM, or access
controls to complete this checklist. The
[anti-debugging notes](../docs/agent/anti-debugging.md) distinguish observed code
from unproven behavior.

For a usable manual fallback, Chrome's menu → **Cast** → **Sources** → **Cast tab**
may mirror the browser tab. Label any such result **mirroring**. It depends on
the browser remaining active and does not satisfy independent receiver playback.

## Acceptance record

| Check | Result and sanitized evidence |
| --- | --- |
| Public demo SDK ready | Passed isolated smoke 2026-10-18 07:15 America/Sao_Paulo, Chrome 155; no device selected |
| Device discovered and selected | Philips43PFG6910/78 discovered; selected successfully through YouTube only |
| TV retrieved/played public media | Untested |
| Target native Cast control / duplicate handling | Live extension fallback visible without duplicate; TV unavailable for generic frameXTV route |
| Target media picture and audio on TV | Untested |
| Local playback paused after remote success | Untested |
| Pause/resume, seek, volume | YouTube sender pause/seek/resume observed; extension controls and volume untested on hardware |
| Subtitles / alternate audio | Untested; use N/A only after checking |
| Disconnect and local return paused | Own YouTube route stopped; local sender returned paused. Extension path untested |
| Episode change / pending request invalidation | Untested |
| Player replacement / Back-Forward return | Untested |
| Narrow window / fullscreen / keyboard | Untested |
| Phone fully powered off, receiver keeps fetching | Untested; final mobile acceptance requirement |

Document blockers and remaining questions in the
[handoff](../docs/agent/handoff.md). A completed device test must identify the
exact build, receiver, source class, and checks performed.
