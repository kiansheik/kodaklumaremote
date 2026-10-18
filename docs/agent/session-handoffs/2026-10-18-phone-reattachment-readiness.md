# Phone playback reattachment and subtitle readiness

## Goal

Repair the user's failed0.2.1 physical control test and repeated-start/missing
subtitle report for `https://framextv.xyz/watch/284833/1/2`. Preserve independent
receiver playback, saved setup and exact-source control guards.

## Files inspected

Required agent docs, previous phone/control and successful Kodi subtitle handoffs;
coordinator/mobile/native source and fixtures; Android MediaSession/MediaStyle,
Android16 AOSP notification/media pipeline and Kodi21.2 player implementation.
Public site config/EmbedPage subtitle merge and ordinary S1E2 API responses.
Filtered own-app phone state/logs and read-only Kodi playback/subtitle queries.

## Files changed

- `ConnectionService.java`: adopt continuing public HTTP playback only without
  remembered remote/authorized handoff; generic RAM metadata and new revision,
  no Open/Add. Separate ordinary41 and MediaStyle42; shared launch target,
  coalesced latest-state posts at most once/second, visible-content dedup and
  both-card cleanup. State-only `LumaControls` diagnostics.
- `MainActivity.java`, `NativeBackend.java`: fixed metadata-retention diagnostics
  and fixed RPC method/error codes, without links or credentials.
- `src/handoff/coordinator.mjs`, handoff tests:30-second subtitle/read windows,
  propagated bridge deadlines, bounded transient read retry, fresh registration
  observation and uncertain-Add reconciliation without another write.
- Mobile browser fixtures: fatal rejection vs uncertain response distinction,
  fake-clock timeout/recovery preserving the selected language and no replay.
- Service instrumentation/runner: actual ordinary buttons, separate media card,
  safe generic adoption, current posted controls, valid custom Stop, stale
  revisions, both-card cleanup and Disconnect preserving playback.
- `LiveControlsInstrumentation.java` and runner mode: confirmed live ordinary
  notification and MediaSession Pause/Play with guarded restoration.
- Android version0.2.2/code4 and README/agent documentation.

## Commands run

- Targeted `rg`/`sed`, `git status`, `git diff --check`.
- `npm run check`: build and128 Node passed;68/69 browser passed before fixing
  the old immediate-failure expectation. Final mobile27/27 passed, including its
  new bounded uncertain-write case; then the full browser rerun passed70/70.
  No production JS changed after the128 Node run.
- Offline Gradle `:app:assembleDebug :app:assembleDebugAndroidTest` passed after
  final native/coalescing changes using the existing cached toolchain.
- `node scripts/test-android-service.mjs`: final4/4 plus wire assertions passed.
  `node scripts/test-android-ui.mjs`: final5/5,320x592 without scrolling.
- Paired Samsung: app/test APK `install -r`; confirmed `am instrument -w -e mode
  live-controls -e confirmLivePlayback true` using the existing test runner.
  Two live checks passed; all four Pause/Play transitions observed, same source
  preserved, finalPlaying=true. This is actual phone action delivery, not a
  SystemUI screenshot or visual acceptance.
- Unconfirmed live mode on emulator rejected with LIVE_CONFIRMATION_REQUIRED
  before playback access. Fixture scripts still reject physical serials.
- Opened the exact S1E2 HTTPS link in the updated phone app; user asked to Cast
  once and check shade/lock-screen visibility. No result recorded yet.
- Ignored debug APK SHA-256:
  `e7656c5708a3190afefe190e30d9613f65755b40a1d9aa89a664f62809b24b41`.

## What worked

- Failed-phone evidence established an inactive/NONE session and ordinary card
  with only Disconnect despite continued Kodi video. The upgrade had lost its
  RAM descriptor. Permission remained granted; no permission bypass was needed.
- New service adoption reattached playback without replay. Actual Samsung
  notification and media-controller Pause/Play now both worked. The live test
  never opens media, adds subtitles, seeks, stops, disconnects or resets setup;
  it restores playing only for the same active source.
- Own logs showed repeated subtitle-stage KODI_UNAVAILABLE/KODI_RESPONSE and
  SUBTITLE_TIMEOUT. Kodi reported zero subtitle tracks. The former3-second
  metadata window could expire before multi-RPC native reads completed.
- Public S1E2 catalog:16 auxiliary tracks/14 languages, PT-BR and two English
  entries. PT-BR fetched200 in0.53s,20,292bytes/268 cues. SRT syntax despite VTT
  API label repeats the known S1E1 mismatch; it does not prove a parsing cause.
  Provider and auxiliary tracks merge, so phone capture19 is not contradictory.

## What failed

- Initial service fixture captured an existing posted card across a metadata
  revision, then timed out waiting for its action. It now waits for current
  posted title/action and exercises generic adoption without an artificial
  metadata revision. Coalescing also avoids flooding notification updates during
  bursts of status/metadata reads; source/revision checks remain intact.
- Initial mobile expectation assumed any subtitle failure returned immediately;
  unknown write outcomes now reconcile for a bounded window. The test separates
  fatal validation rejection from uncertain response and checks both paths.
- A single public S1E2 stream did not exactly match Kodi's current file, even
  after conservative normalization. No subtitle write was sent to that unknown
  source and no provider/query grid was tried.
- Automatic approval review rejected forwarding the phone WebView debugger to
  localhost because it could expose authenticated browser/control contents.
  No forward was created or workaround attempted. Safe own-app diagnostics,
  public HTTP reads and restricted app instrumentation supplied the evidence.

## Remaining questions

Does S1E2 now finish on its first Cast attempt with visible PT-BR? Are controls
visibly present in the expanded ordinary notification and lock/media panel?
Both questions are pending asynchronously. The current app logs expose fixed
methods/codes to locate any next failure without private links. Full phone-off,
HLS seek stability and interrupted-network recovery remain unaccepted.

Active URLs/API bodies are ignored local diagnostics under
`.local/subtitle-episode-check/`; none, credentials or phone endpoints entered
Git. The test APK remains installed on the phone for possible follow-up; it has
no launcher and requires the separate mode/confirmation. Preserve unrelated
untracked `headers.txt`.

## Suggested next prompt

“Use the installed0.2.2 S1E2 result to finish first-attempt/PT-BR and visible
system-control acceptance. Read own LumaHandoff/LumaControls/LumaKodi logs for any
failure; preserve current playback and source guards. Once accepted, remove the
temporary test APK and test phone fully off separately.”
