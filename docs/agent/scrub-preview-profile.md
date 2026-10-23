# Scrub-preview provider profile

## 2026-10-22 read-only sample

The live sample covered four current movies and Breaking Bad S1E1. No raw media
URLs, playlist bodies, session data or credentials were retained in Git.

The five `/api/stream` responses exposed provider, sources, subtitles and
success at the top level. Their source entries exposed only quality, server,
skip-proxy, type and URL. None advertised preview images, storyboards or a
preview manifest.

One sampled HLS source was a direct VOD media playlist with ordinary version,
target-duration, media-sequence and end-list tags. It had no I-frame playlist or
image-playlist declaration. One sampled DASH manifest exposed audio and video
adaptation sets only; it had no image adaptation set or trick-play descriptor.
This is a bounded sample, not proof that every provider title lacks a preview
asset, but it gives the app no provider-backed preview path for these titles.

## Phone-side implementation

The Android service therefore treats previews as a best-effort phone feature.
For the currently verified HTTPS source and canonical content identity only, it
accepts a bounded direct, unencrypted VOD HLS media playlist on Android 8.1/API
27 or newer. It rejects master/live/encrypted/byte-range playlists, DASH,
progressive sources and oversized responses. Every playlist, initialization
segment and media-segment redirect is independently validated; DNS answers must
all be public, and the socket is pinned to an approved address while preserving
TLS SNI and hostname verification. The platform decoder never receives the
remote URL: `MediaMetadataRetriever` reads only the bounded bytes already held
in memory and requests a scaled 256 x 144 sync frame. Frames are JPEG quality 55
and returned to the WebView as bounded data URIs; they are never sent to Kodi
and never alter playback.

Extraction is on demand after a short scrub-input debounce rather than a full
video preload. Positions are grouped into an adaptive interval computed from at
most about 240 buckets, rounded to five-second steps with a ten-second minimum.
Successful frames accumulate in a memory-only LRU cache capped at 12 MiB and
240 frames. The cache, queued request and extractor are revoked when the active
source or content changes and are discarded when the service stops. Nothing is
written to disk.

## Acceptance boundary

`MediaMetadataRetriever` support varies by Android build and segment/container.
Successful HLS playback by Kodi does not prove that the phone can decode the
selected segment. Sources requiring credentials, custom headers or unsupported
playlist features intentionally return no image. The UI keeps the timestamp and
ordinary scrub action usable when no frame is returned, and only the latest
outstanding request is relevant while the slider moves.

Parser, network pinning, cancellation, cache and extractor behavior have seven
emulator fixtures, but visual, data-use and latency acceptance still require the
physical phone with representative supported HLS titles. DASH and progressive
sources are known unsupported, rather than silently handed to a network-aware
platform decoder. A provider-native storyboard, if one is later discovered and
validated, could broaden coverage and avoid repeated media decoding; the current
live sample did not expose one.
