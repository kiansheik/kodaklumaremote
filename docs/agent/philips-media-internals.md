# Philips media internals and proposed receiver contract

Research date: 2026-10-20. This is static source research, not a new TV playback result.
The [measured browser](philips-browser-compatibility.md) exposes MediaSource and
declares codec support, but no source, caption or DRM session was opened here.
No Android change or native method invocation is part of this work.

The main [firmware/server report](philips-firmware-implementation.md) records
the newly acquired current-version container and why native backend extraction
is still needed.

## Reference identity and the vendor boundary

The fixed upstream reference is Chromium tag `112.0.5615.49`, resolved through
the [official mirror's tag API](https://api.github.com/repos/chromium/chromium/git/ref/tags/112.0.5615.49)
to commit `8e1a5127113793e25c8192936365df9c2b0fa496`.
The actual TV reports `Chrome/112.0.4147.62` and OMI 4.24. Those tokens do not
identify this commit. Generic Vewd and Titan version tables differ from the
observed token. Every Chromium function below is therefore an upstream
implementation reference, **not a recovered firmware function or address**.

[TiVo's Core guide](https://developers.tivo.com/smart-tv/core-developer-guide#platform-dependent-features)
describes an embeddable Chromium-based engine, with codecs depending on chipset
and licenses, and DRM integrated with the platform. It documents native adaptive
streaming, MSE segment formats and out-of-band text tracks. The generic guide's
HLS version must not override this firmware's unknown implementation.
[Titan's media specification](https://docs.titanos.tv/media-specifications)
lists native HLS/DASH, MP4/WebM MSE combinations and out-of-band captions,
including MSE and progressive playback. Its native HLS description is version 3;
the current TiVo guide describes newer HLS revisions. These are different
generic documents, not exact-firmware guarantees.
[Titan's platform tables](https://docs.titanos.tv/media) are additional candidates
for model-specific testing, not runtime CDM or decoder inventories.

Evidence is under ignored `.local/tv-hello/firmware-deep/media/`:
`source-manifest.json` records requested paths, success/failure, bytes and hashes;
`source/` contains the downloaded upstream files; `vendor-manifest.json` hashes
the public documentation snapshots. Failed guessed paths are retained as failed
lookups, not evidence that a firmware component is absent.

## Why the two capability declarations can disagree

The exact reference call chains are:

| Browser call | Upstream functions and source | Meaning |
| --- | --- | --- |
| `video.canPlayType(type)` | [HTMLMediaElement::canPlayType / GetSupportsType](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/media/html_media_element.cc#L386), then [MIMETypeRegistry::SupportsMediaMIMEType](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/network/mime/mime_type_registry.cc#L133), `media::IsSupportedMediaFormat`, `MimeUtil::IsSupportedMediaFormat` | Normalizes the MIME type, preserves codec-string case, rejects empty/octet-stream types and maps support to empty, `maybe` or `probably`. |
| `MediaSource.isTypeSupported(type)` | [MediaSource::IsTypeSupportedInternal](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/modules/mediasource/media_source.cc#L494), then `GetSupportsType`, [MIMETypeRegistry::SupportsMediaSourceMIMEType](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/network/mime/mime_type_registry.cc#L143), [StreamParserFactory::IsTypeSupported / CheckTypeAndCodecs](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/filters/stream_parser_factory.cc#L510) | Requires general element support plus a supported MSE byte-stream/container/codec combination; returns true only for definitive support. |
| General codec decision | [MimeUtil::IsSupportedMediaFormat / AreSupportedCodecs / IsCodecSupported](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/base/mime_util_internal.cc#L530) and [supported_types.cc](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/base/supported_types.cc) | Parses codec strings and checks configured/platform support; ambiguity can downgrade support. Build flags and platform capability hooks matter. |

An MSE true result does not initialize a decoder, allocate a SourceBuffer or
append a frame. `addSourceBuffer()` can still fail, and later append/decode can
fail. The reference also has Dolby Vision build/feature exceptions and a
codec-specificity distinction between proactive queries and buffer creation.
It is not a universal proof that an entire stream is playable.

The TV's HLS/DASH `maybe` alongside MSE false is structurally plausible:
`application/vnd.apple.mpegurl` and `application/dash+xml` describe manifests;
MSE accepts encoded byte streams such as codec-qualified MP4/WebM. A JavaScript
ABR player parses manifests itself, fetches segments and appends suitable bytes.
False for a manifest does not test those segment types. Conversely, a segment
true does not test manifest parsing, segment continuity or the content provider.
The TV's broad codec declarations do not establish hardware decode, supported
bitrate/profile/level, smoothness or actual sound.

## URL playback and MSE are different input routes

```text
Native URL route:
video.src / <source> -> HTMLMediaElement::LoadResource
  -> embedder creates WebMediaPlayer -> URL data source / vendor streaming route
  -> demuxer -> selected renderer and decoders -> audio output / video compositor

MSE route:
app fetches manifest + segments -> MediaSource + SourceBuffer.appendBuffer
  -> segment parser / ChunkDemuxer -> selected renderer and decoders
  -> audio output / video compositor
```

In the upstream reference, `WebMediaPlayerImpl::DoLoad` creates a
`MultiBufferDataSource` for ordinary network URLs; MediaSource can start its
pipeline immediately. `DemuxerManager::CreateDemuxer` selects an override,
FFmpeg for supported ordinary URL builds, or ChunkDemuxer for MSE. Its
MediaUrlDemuxer/native-platform branch is Android-gated in this reference.
**That branch cannot explain the Linux TV's native HLS/DASH implementation by
itself.** A vendor replacement/extension is plausible; its actual code and
selection rules have not been obtained.

The decisive extension boundary is
`MediaFactory::CreateRendererFactorySelector` calling the content embedder's
`GetBaseRendererFactory`. `WebMediaPlayerImpl::CreateRenderer` subsequently uses
the selected factory. The reference's `DefaultDecoderFactory` can supply an
external accelerated decoder and configured software decoders. None of this
identifies a MediaTek decoder API, process, driver, firmware blob, secure video
plane or Vewd integration on this TV.

## Function map for a player shell

All source links below are pinned to the same reference commit.

| App responsibility | Exact implementation references | Boundary for our shell |
| --- | --- | --- |
| Load/replace media | [HTMLMediaElement::LoadResource / StartPlayerLoad](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/media/html_media_element.cc#L1326); [WebMediaPlayerImpl::DoLoad / StartPipeline](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L792); [DemuxerManager::CreateDemuxer](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/filters/demuxer_manager.cc#L312) | One active source generation; detach the old player, requests and captions before installing another. |
| Create/append MSE buffers | [MediaSource::addSourceBuffer](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/modules/mediasource/media_source.cc#L160); [SourceBuffer::AppendBufferInternal / AppendBufferAsyncPart_Locked](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/modules/mediasource/source_buffer.cc#L1941); [WebMediaSourceImpl::AddSourceBuffer](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_source_impl.cc#L33); [WebSourceBufferImpl::RunSegmentParserLoop](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_source_buffer_impl.cc#L137) | Serialize append/remove while `updating`; handle quota, eviction, timestamp offsets, discontinuities and `updateend`/error. A manifest is not an appendable segment. |
| Start/pause | [HTMLMediaElement::playForBindings / Play / pause / PauseInternal](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/media/html_media_element.cc#L2740); [WebMediaPlayerImpl::Play / Pause](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L899); [PipelineController::SetPlaybackRate](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/filters/pipeline_controller.cc#L374) | Handle rejected play promises/autoplay policy; pause is an explicit idempotent operation. Calling play does not itself prove visible playback. |
| Seek | [HTMLMediaElement::Seek / FinishSeek / setCurrentTime](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/media/html_media_element.cc#L2355); [WebMediaPlayerImpl::DoSeek](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L1006); [PipelineImpl::Seek](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/base/pipeline_impl.cc#L1333) | Clamp to observed seekable ranges, including a moving live window; complete from `seeked` plus readback, not assignment alone. |
| Buffering/status | [HTMLMediaElement::SetReadyState / buffered / seekable / UpdatePlayState](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/media/html_media_element.cc#L2023); [WebMediaPlayerImpl::OnBufferingStateChangeInternal](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L2115); [RendererImpl::OnBufferingStateChange](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/renderers/renderer_impl.cc#L703) | Observe `waiting`, `playing`, `stalled`, `progress`, `timeupdate`, `ended`, `error`; network stall and stopped clock are separate observations. |
| Audio track choice | [HTMLMediaElement::AudioTrackChanged / AudioTracksTimerFired](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/media/html_media_element.cc#L3170); [WebMediaPlayerImpl::EnabledAudioTracksChanged](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L1188) | Feature-detect actual `audioTracks` exposure; otherwise use the chosen ABR library's track API. Never infer a selector from manifest metadata alone. |
| Volume/mute | [WebMediaPlayerImpl::SetVolume](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L1083) | Element gain/mute is separate from physical TV speaker volume or an AVR. Current hardware remote paths remain separate. |
| Subtitle load/parse | [HTMLTrackElement::ScheduleLoad / LoadTimerFired](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/track/html_track_element.cc#L138); [TextTrackLoader::Load / DataReceived](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/loader/text_track_loader.cc#L114); [VTTParser::ParseBytes / HasRequiredFileIdentifier](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/track/vtt/vtt_parser.cc#L117) | Track request CORS and parser success are separate from video success. Upstream's loader uses WebVTT; a provider's SRT body is not converted by naming it `.vtt`. |
| Subtitle timing/display | [TextTrack::setMode](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/track/text_track.cc); [CueTimeline::TimeMarchesOn / SetCueEventTimer / OnPause](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/html/track/cue_timeline.cc#L152) | Use media time/active cues; avoid an independent wall clock that drifts across pauses, buffering and seeks. |
| Renderer/decoder selection | [MediaFactory::CreateRendererFactorySelector](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/content/renderer/media/media_factory.cc#L537); [WebMediaPlayerImpl::CreateRenderer](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L2751); [DefaultDecoderFactory::CreateVideoDecoders](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/media/renderers/default_decoder_factory.cc#L86) | Native/vendor acceleration sits below the web API. WebGL presence does not identify the video decoder or grant its private interface. |
| Fullscreen and lifecycle | [Fullscreen::RequestFullscreen / ExitFullscreen](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/core/fullscreen/fullscreen.cc#L667); [WebMediaPlayerImpl::SetSuspendState / PauseVideoIfNeeded](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/web_media_player_impl.cc#L3009) | Request fullscreen on the wrapper so caption/control DOM can remain together. Capability, activation and vendor overlay composition still need TV checks. Hidden/suspended app policy can stop the receiver. |

## Captions and DRM need distinct acceptance gates

For external WebVTT, use a `<track kind="subtitles">` and observe track load/error,
then select one track using `mode="showing"`; other choices are disabled. The
reference `TextTrackLoader::Load` uses same-origin mode without the media
crossorigin attribute, or the corresponding CORS mode when it is supplied.
Changing `video.crossOrigin` can also change the video's own request behavior;
do not enable it indiscriminately for a source that only plays without CORS.
An alternative is an allowed JS caption fetch followed by validated cues in
`addTextTrack()`/`VTTCue`. That fetch still needs valid same-origin/CORS access.

`CueTimeline::TimeMarchesOn` obtains `media.currentTime()`, determines active
cue intervals and distinguishes seeks from ordinary progression. Its timers
stop while paused or playbackRate is zero. Native captions or a DOM renderer
driven by `cuechange`/`activeCues` can therefore follow the media timeline.
Subtitle offsets must shift cue times once or adjust the renderer's media-time
lookup; retain original cue timing so changes do not accumulate rounding/drift.
SRT/ASS/TTML handling requires an explicit converter/renderer or a verified
vendor route. The phone's existing SRT-for-Kodi normalization does not establish
an HTML `<track>` route. Caption load and selected mode do not prove Portuguese
glyphs, safe layout, exact episode alignment or fullscreen composition.

The upstream [Navigator EME IDL](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/modules/encryptedmedia/navigator_request_media_key_system_access.idl)
marks `requestMediaKeySystemAccess` as secure-context-only. Exposure, key-system
selection, CDM creation, licensing and successful protected decoding are separate
steps. [KeySystemConfigSelector::SelectConfig](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/platform/media/key_system_config_selector.cc#L991)
checks key-system/configuration support, including codec, robustness, encryption
and persistent-state constraints.
[MediaKeySystemAccess::createMediaKeys](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/modules/encryptedmedia/media_key_system_access.cc#L205)
asynchronously creates a CDM; [HTMLMediaElementEncryptedMedia::setMediaKeys](https://github.com/chromium/chromium/blob/8e1a5127113793e25c8192936365df9c2b0fa496/third_party/blink/renderer/modules/encryptedmedia/html_media_element_encrypted_media.cc#L364)
attaches it to playback. A trusted HTTPS page could expose EME where the current
HTTP probe does not, without making any particular CDM/license/content work.
No protected-content experiment or DRM workaround is proposed here.

## Proposed receiver API: player operations, not an existing Kodi endpoint

This is a design contract, not implemented behavior. It could power a
Kodi-like library/player/remote UI using a single TV-owned media element.

| Operation | Proposed parameters and completion |
| --- | --- |
| `loadSource` | Authorized source URL, explicit route (`url`, `mse-hls`, `mse-dash`), MIME/codec hints, optional start position and subtitle catalog. Return a new source revision; complete at metadata/adapter readiness, not at visible playback. |
| `play`, `pause` | Explicit desired state. Play handles its promise plus `playing`/clock observations; pause reads `paused`. No toggle in retryable network messages. |
| `seek` | Absolute media seconds with current source revision. Clamp to a fresh seekable range and report observed final position after `seeked`. One active seek; coalesce unsent scrubs. |
| `setSubtitle` | Stable catalog ID or null for Off, plus optional offset. Fetch/parse selected captions without reopening the video; complete with loaded/selected state and expose a separate rendering-unverified flag. |
| `selectAudio` | Stable ID scoped to the source/adapter. Expose only when the element or ABR adapter can enumerate and change tracks; return unsupported otherwise. |
| `setVolume`, `setMuted` | Element gain 0–1 and boolean mute. Keep hardware volume independent. |
| `stop` | Revoke the source generation, cancel fetch/append/caption work, pause, detach source, destroy the ABR adapter and release object URLs. Repeated stop is harmless. |
| `getStatus`, `subscribe` | Source revision, phase, intent, paused/seeking/ended/error, currentTime, finite duration or explicit live/unknown duration, seekable/buffered ranges, track choices, element volume, visibility and control-channel state. Never log active URLs/tokens. |
| `requestFullscreen`, `exitFullscreen` | User-gesture/capability-dependent UI operations with observed `fullscreenchange` completion. A remote network message does not itself create user activation. |

Suggested status transitions preserve intent separately from observed phase:

| Trigger/observation | Transition or retained state |
| --- | --- |
| `loadSource` accepted | `idle/ended/error -> loading`, increment source revision and invalidate all old callbacks/receipts. |
| `loadedmetadata` / ABR readiness | `loading -> ready`; publish tracks/duration/seekable observations. `canplay` remains a readiness hint. |
| Explicit Play | Set intent `playing`; enter `starting`, then `playing` on event/clock evidence; policy rejection returns a typed error and leaves ready/paused state. |
| `waiting` with playing intent and insufficient data | `playing -> buffering`; retain playing intent. `stalled` alone is a network indicator, not proof the playback clock stopped. |
| `playing` / progress resumes | `buffering/starting -> playing`; record the observation without claiming physical picture/sound. |
| Explicit Pause | Set intent `paused`; after readback enter `paused`. Do not let a queued `playing` callback from an old source undo it. |
| Seek begins/completes | Enter `seeking`; after `seeked`, return to paused/starting/playing according to retained intent and fresh observations. |
| `ended`, `error`, Stop | Enter `ended`, `error`, or `idle`; preserve a sanitized reason and invalidate pending work as appropriate. No automatic reopen after an unknown write outcome. |
| Channel disconnect | Set control `offline`; do not reload/stop an otherwise playing source. Reconnect with a state snapshot, not a replay of unknown commands. |
| `visibilitychange`, `pagehide`, resume | Record visibility/suspension; release obsolete work on exit and resynchronize on return. No guarantee the vendor keeps a hidden tab alive. |

## TV-owned playback versus a TV-hosted listener

These are independent properties. The TV could own playback and retrieve the
source directly while a phone is completely off, provided its page remains
alive and media/auth/caption dependencies do not rely on that phone. This is
an architectural possibility; sustained playback and phone-off acceptance
have not been demonstrated by these declaration probes.

A page receiving commands over an outbound channel does not establish a
listening server on the TV. The standard
[WebSocket interface](https://websockets.spec.whatwg.org/#the-websocket-interface)
opens a connection to a server and exposes messaging, not a bind/listen API.
This TV's WebSocket constructor was detected but a socket handshake was not
tested here. Existing same-origin report polling establishes outbound HTTP;
a command queue carried in poll replies is a feasible next bounded experiment.
The latest bounded presence-only probe reported `TCPServerSocket`, `TCPSocket`,
`UDPSocket` and `WebTransport` absent, and `WebSocket`/`RTCPeerConnection` present
in the HTTP context; it called no constructor. This narrows the observed page
surface but neither enumerates hidden platform services nor tests an actual
WebSocket/WebRTC connection. Inbound TV TCP/HTTP serving remains unproved and
requires its own authorized platform capability. WASM computation and WebGL
graphics do not add that capability to a page. See the separate
[browser/API internals](philips-browser-internals.md) for the privileged,
experimental upstream Direct Sockets exception and its context gates; this
page's absent constructors do not rule out every privileged vendor app context.

```text
Controller UI -- authenticated commands --> independently running broker
TV page ------ outbound WebSocket/poll --> broker queue and state receipts
TV player ---- direct media/caption fetch --> authorized content servers
```

The controller can disappear after handoff. The broker must remain available
for later network control, and the app host must remain available for a reload
or uncached asset. Playback can continue through broker loss only if the player
and all continuing media/auth dependencies are independent of it. If the
phone hosts captions, segments, a proxy or the only broker, phone-off changes
those conditions. Hosting control on an independent Mac/LAN server could meet
the control-plane requirement while leaving the TV-listener requirement open.

Use a small versioned protocol rather than claim Kodi JSON-RPC compatibility:
command `{v:1,id,instanceId,sourceRevision,method,params}`; receipt
`{v:1,id,accepted,completed,sourceRevision,error}`; state
`{v:1,instanceId,stateSequence,sourceRevision,status}`. An allowlist, bounded
messages/queues and scoped authentication should cover only the player methods
above, with no eval, shell, generic fetch or arbitrary native-call operation.
Require source revisions for mutations; cache bounded command IDs/receipts to
avoid duplicate application. A receipt lost during disconnect is unknown until
reconciled with the same instance/revision; reconnect or broker restart must not
silently replay `loadSource`. A new page instance invalidates prior commands.

## What remains unknown and the next useful proof

The exact vendor branch, build flags and patches; URL/adaptive streaming
implementation; decoder/driver/process/secure-plane selection; practical codec
limits; usable audio-track API; real WebVTT/fullscreen layout; secure-context CDM
availability; and background lifetime all remain unknown. Upstream source and
generic vendor documentation cannot close those gaps.

A future bounded proof should separately observe: authorized H.264/AAC MP4
picture/sound and clock progress; pause and one settled seek; selected PT-BR
WebVTT before/after pause/seek; native HLS; an MSE segment route only when needed;
and control reconnection without reopening media. Test the phone fully off only
after listing every remaining phone-hosted dependency. Test a TV-hosted listener
as a separate platform milestone. No such media or server experiment ran during
this source-research assignment.
