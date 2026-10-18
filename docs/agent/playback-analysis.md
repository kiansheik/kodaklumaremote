# Playback analysis

Static code findings on 2026-10-18. Evidence filenames/hashes and offset convention
are in [investigation](investigation.md).

## Native player flow

WatchPage constructs a same-origin embed iframe; EmbedPage obtains source
metadata and passes a selected source to Player. Player's normalizer chooses the
already-delivered URL or the website's existing backend proxy URL. HLS playback
uses **hls.js 1.6.16** when supported, with native HLS and MP4 branches also
present. Source loading is around Player offsets ~602000–607800.

The rendered HTML5 video retains a `source[src]` with the normalized URL
(~624800). Thus a browser-visible MSE `blob:` currentSrc is not enough to conclude
the original URL is unavailable: inspect ordinary child sources first. Treat
`blob:`/`data:` as ineligible for receiver loading. Cross-origin backup iframes
have their own player and are not covered by same-origin DOM extraction.

## Existing Cast behavior

Player's `ld` hook (~513700–522600) already initializes the page Cast context,
requests a session and creates a Default Media Receiver load request from the
normalized source URL. It carries playhead, metadata and VTT subtitle tracks;
it retries without subtitle tracks after a load error. Remote controller code
supports pause/resume, seek, volume/mute and session status. The player toolbar
Cast button is discovery-gated.

The website guesses fMP4 settings from URL/provider names and patches some Cast
load calls (~591000–593400). These heuristics are evidence of implementation,
not proof of actual segment/container compatibility. A wrapper should avoid
repeating provider-name guesses as a compatibility verdict.

## Receiver-readiness inventory

| Property | Current evidence |
| --- | --- |
| HLS/MP4 support paths | Verified in frontend; clean browser metadata observed HLS and MP4-typed 200 responses, without decoding/manifest validation. |
| DASH | No native DASH player established in inspected player code. |
| Codecs/container | Unknown; no manifest/segments inspected. |
| Subtitles | External text-track and HLS-track UI/Cast paths exist; VTT/SRT response types observed; actual languages unknown. |
| Audio languages | Multi-audio UI exists; actual tracks unknown. |
| Expiration | Code refreshes sources after repeated errors/403/stalls; no TTL measured. |
| Authorization/DRM | No receiver access or DRM status established; never infer absence from DOM URL. |
| Independent receiver access | Unknown until an authorized device successfully loads that URL. |
| Repeat visits | Cache/preferences/failover/fallback branches exist; runtime variability unmeasured. |

The Cast load request is a useful implemented path. Success still requires the
receiver to reach every necessary resource independently, without browser-only
credentials/headers, and decode its actual media. No physical-device result is
claimed by this investigation.
