# Backup stream cast assessment

## Goal

Determine whether the public player's ad/redirect-heavy Backup server can be
cast directly to LUMA with receiver controls, scrubbing and subtitles.

## Files inspected

- Current public `EmbedPage` and `Player` assets
- `src/mobile/capture.js`
- `android/app/src/main/java/dev/framextv/lumaremote/WebContentResolver.java`
- `android/app/src/main/java/dev/framextv/lumaremote/MainActivity.java`
- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/{MediaRequest,PlaybackController}.java`
- Relevant receiver/capture tests and playback/site architecture notes

## Files changed

- `docs/agent/current-state.md`
- `docs/agent/open-questions.md`
- `docs/agent/log.md`
- This handoff

No application source changed.

## Commands run

- Bounded static inspection of the current public backup integration
- Clean Chromium desktop and mobile-profile Backup selection
- Sanitized response/resource/frame/DOM classification without media-body,
  cookie, credential or token retention
- Targeted source and test searches for capture, validation and receiver format
  handling

## What worked

- The current site contract is clear: Backup constructs a cross-origin web
  iframe and skips the ordinary direct-source player branch. Messages expose
  play/time/ended state but no media URL or MIME type.
- Mobile-profile Backup loaded HTML and supporting scripts, but no standard
  media manifest, file, segments, Cast control or source-bearing message was
  observed. Desktop returned 403/frame blocking.
- Receiver audit confirms direct HLS, DASH, MP4, WebM and MKV support already
  exists. CORS is not a receiver limitation; JavaScript/iframe/ad execution is.
- Existing same-origin capture correctly refuses to enter a cross-origin frame,
  preserving the browsing/native security boundary.

## What failed

- No stable authorized direct media descriptor was exposed, so there is nothing
  safe for direct LUMA handoff in the observed Backup session.
- Treating the extensionless iframe URL as undeclared media can fall through to
  the receiver's MP4 inference and yield a misleading parser/format error.
- Ad redirects, cookies, authentication, DRM or provider challenges were not
  bypassed or copied; doing so is not an acceptable implementation path.

## Remaining questions

- Should the next release label a detected Backup iframe as web-only and return
  a specific message instead of the generic unsupported-source result?
- Should receiver validation reject undeclared extensionless inputs, requiring
  signed extensionless progressive media to declare `video/mp4` explicitly?
- Does the user want a separate screen-mirroring mode, accepting that it mirrors
  the web player/ads and cannot provide normal LUMA scrub thumbnails/subtitles?
- Will the provider/site later publish a documented stable HLS/DASH/MP4 handoff?

## Suggested next prompt

Implement honest Backup web-only detection plus extensionless-HTML rejection,
or scope a separate screen-mirroring prototype if direct-source failover is not
enough.
