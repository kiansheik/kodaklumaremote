# Subtitle normalization and SPA address repair

## Goal

Fix non-default-title subtitle failures by validating and normalizing selected
text on the phone before Kodi receives it. Preserve video-first startup and
manual language choice. Follow the actual current page in the Browse toolbar.

## Files inspected

- Required agent index/current-state/repo-map/open-questions and release guide.
- Shared media/capture, handoff coordinator, native RPC adapter and browser fixtures.
- MainActivity, ConnectionService, NativeBackend and existing native fixture runners.
- Current public homepage/config/query assets and subtitle API responses.
- Public Kodi subtitle factory/stream and JSON-RPC source (linked below).

## Files changed

- Native SubtitleNormalizer/SubtitleDelivery: bounded public fetch, body-based
  SRT/VTT/ASS/SSA/media-time TTML conversion, charset handling and UTF-8 SRT output.
- ConnectionService/NativeBackend: separate prepare/status calls, trusted ticket
  resolution, source/catalog/receiver validation, lifecycle cancellation generation.
- Coordinator/app: prepare before Add, observe binding/selection and complete
  response serving, keep original catalog URLs and opaque tickets, preflight
  known bindings, recover explicit expiry without replaying unknown writes.
- Capture/shared extractor: additional text formats through native opt-in only.
- MainActivity: SPA address changes plus focused-draft preservation.
- JVM/Android/Node/browser tests and runners; version/notes and agent wiki.

## Commands run

- Public HTTPS curl for current homepage/assets, subtitle catalog and selected files.
- `npm test` (201); Playwright mobile/capture (41), extension/dual/Matter (48).
- `JAVA_HOME=... node scripts/test-subtitle-normalizer.mjs` (24).
- `JAVA_HOME=... node scripts/test-subtitle-delivery.mjs` (10 localhost-only).
- Temporary ignored JVM runner normalized12 actual bodies, repeated normalization
  byte-identical and cue counts preserved.
- Offline receiverFixture Gradle debug/test builds and lintDebug.
- Final Android fixture build/lintDebug passed. Emulator checks: subtitle8,
  backend12, UI9, locked-screen/service14 and dual receiver4 (47 total).
- `git diff --check` and native adapter/runner JavaScript syntax checks passed.
- Release/deployment evidence is recorded below after publication.

## What worked

Homepage samples: Masterplan, Tenzing, Law & Order: SVU S1E1 and The Simpsons S1E1.
Twelve English/Portuguese bodies were SRT,364–1660 cues. Private URLs/bodies remain
ignored. The connected Samsung was displaying Bad Apples; its public catalog had
38 tracks including English, Portuguese and Portuguese(Brazil), all declared VTT.

Preparation failures send no Add. Full serving plus Kodi's observed selected and
enabled index is required for success. Known tickets are renewed before Set;
explicit unavailable proof retires only completed bindings. Unknown Add outcomes
remain guarded. Original catalog URLs survive, local capability URLs are native-only.
The active source may retain128 tracks/16MiB without evicting registered files.
Fetch work has an8s ceiling and does not occupy Kodi's5s network budget.

## What failed

The first Android TTML check exposed that Android Expat did not retain the wrapped
fixed validation error through Throwable.getCause. The parser now retains that
error explicitly and inspects legacy SAX nesting; exact expected captions and
unsupported-profile rejection were preserved. The isolated TCP fixture also
needed the already-existing GETPASSWORDCONFIG handshake. The final rerun passed.
An initial unprivileged Node run could not open localhost; only its stalled
processes were terminated, and all201 checks passed with fixture access.

## Remaining questions

- Complete socket serving is not receiver parsing or visible caption evidence.
  Physical subtitles, synchronization, receiver crash reliability and full
  phone-off continuation require household acceptance.
- DNS addresses are checked per redirect but URLConnection resolves again; this
  is not DNS pinning. No cookies/auth are forwarded to public subtitle fetches.
- TTML supports conservative media-time clock/offset/duration cues only; frame,
  tick, ancestor/span timing and other unsupported profiles fail explicitly.
- Phone must be reachable while initially fetching a prepared subtitle. Kodi
  reads text files into RAM, but shutdown acceptance has not been observed.
- Julia's Motorola is unavailable; its locked-screen hardware behavior is not
  established by emulator tests.

## Suggested next prompt

Try a non-default title in0.4.5, select Portuguese(Brazil) or English after video
starts, and report whether captions appear and stay synchronized. If they fail,
use the now-updating address bar and language label for an exact reproduction.

## Evidence and boundaries

[Kodi JSON-RPC AddSubtitle](https://github.com/xbmc/xbmc/blob/21.2-Omega/xbmc/interfaces/json-rpc/PlayerOperations.cpp#L1192-L1207)
acknowledges asynchronous player work. [Subtitle stream reader](https://github.com/xbmc/xbmc/blob/21.2-Omega/xbmc/cores/VideoPlayer/DVDSubtitles/DVDSubtitleStream.cpp#L24-L83)
reads complete text into RAM; neither behavior proves physical rendering.

## Final local validation

All201 Node,89 relevant browser,24 JVM parser,10 JVM transport and47 Android
fixture checks passed. The final emulator service check genuinely closed the
Activity and locked the screen; this does not certify Motorola firmware behavior.
No household power, volume or seek controls were used during these fixtures.

## Published release and phone observation

0.4.5/code14 is public from source
`584ad80bad7593714cb93c4482a8958b9b28ca7b`. Production signed committed-source
build/lintVital and16KiB alignment passed. APK9,666,716bytes, SHA-256
`88febb047cd356a3cddda6b62579826ed7b37212f16740b8ea0b7504e2bb85f8`.
Pages commit`6bb54527a726a58887466c9365c9b1f72038ad02` built with no error.
Public feed/APK GET200; exact prepared metadata, bytes, hash and signer verified.
Install/update page: https://kiansheik.io/kodaklumaremote/

Installed signed update on the connected Samsung with `adb install -r`, without
resetting settings. Package reports0.4.5/code14; saved projector connection
returned immediately. The remote observed existing receiver playback around
19:39/103:24. Its service had recovered a generic Kodi video without the old
captured catalog; Subtitles therefore showed no matching choices after this
process-restarting upgrade. Closed the sheet and left playback untouched.
No fresh cast, subtitle Add/Set, seek, pause, volume or power request was issued
on household devices in this turn. The next fresh cast will supply its catalog
and use the new preparation flow. Actual visible subtitle output remains pending.
