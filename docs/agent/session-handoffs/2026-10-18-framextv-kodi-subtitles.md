# Handoff — actual frameXTV playback and Kodi subtitles

## Goal

Complete the intended-source receiver test after the public MP4 proof, expose
subtitle controls, and load every available language with Portuguese preferred
then English. Preserve the eventual phone-fully-OFF requirement.

## Files inspected

Required agent index/current-state/repo-map/open-questions; README, progress,
decisions, log, mobile-target, manual Kodi evidence and previous handoff;
extension player/shared media, Kodi controller/UI and related tests. Ordinary
native Chrome player, extension manager and loopback UI. Kodi 21.2 JSON-RPC and
released subtitle/player source were consulted for asynchronous add/select
semantics and `Player.SubtitleDelay` diagnostics.

## Files changed

- `src/extension/player.js`: trusted-click copy of validated video URL or all
  declared subtitle descriptors, sanitized status, stale-route completion guards;
  copy controls remain available when the site's native Cast launcher appears.
- `scripts/kodi-controller.mjs`: validated direct-source handoff, subtitle
  list/select/off/add, bounded in-memory aliases verified against receiver and
  SHA-256 of current media. No browser headers, persistence or media relay.
- `src/kodi/controller.html`: direct URL/format playback, subtitle refresh/
  selection/off/external link, Portuguese then English ordering, disambiguated
  generic labels and honest asynchronous command status.
- `tests/kodi-controller.test.mjs`, `tests/browser/{extension,kodi}.spec.js`:
  source validation/transport, subtitle commands/aliases and real browser flows
  with mocked receiver/CAF and clipboard fixtures.
- README, manual Kodi evidence, agent current-state/repo-map/open-questions,
  progress/mobile-target/handoff/index/decisions/log and this handoff.

## Commands run

- `npm run check`: build +55 Node +32 browser tests passed before final aliases.
- `npm test`: final 60/60 Node tests passed, including27 Kodi tests.
- `npx playwright test tests/browser/kodi.spec.js`: final7/7 passed after sorting.
- `npm run build` and `npx playwright test tests/browser/extension.spec.js --grep
  'preserves visible site Cast button'`: final native-control copy fix passed1/1.
  Total browser coverage remains25 extension +7 Kodi; transports are mocked.
- `npm run kodi`: loopback server on127.0.0.1:4175; native Chrome UI used for
  source capture and physical receiver handoff. Temporary runtime Node calls
  used `performAction` for bounded status, commands and sequential subtitle adds.
- Read-only bounded PT/EN subtitle diagnostics: response format and cue timing
  summarized in memory; no subtitle bodies, active URLs or credentials committed.
- Final `git diff --check`, relative Markdown-link validation and scoped
  sensitive-value scan passed. Own temporary raw source/subtitle JSON removed;
  ignored subtitle screenshot retained. Original untracked `headers.txt` preserved.

## What worked

Ordinary native Chrome displayed Avatar: Seven Havens S1E1 (Miner XIX server),
whereas a fresh instrumented tab blanked. Trusted-click **Copy video link**
exposed the declared HLS source. Kodi21.2 reported22:35 and the user explicitly
confirmed actual episode picture and sound. No anti-debugging modification was
used. Restart at08:24:09 without seeking stayed active through08:35:28, paused
at531/1355 seconds; media progressed while the Mac controller server was absent.

The site leaves HTML text tracks hidden/custom-rendered, so copy-all reads every
validated declared track. Sequential `add-subtitle` calls, polling for each new
index, registered19 tracks. Portuguese (Brazil), index18 in this session, was
selected/enabled. User first saw no captions, then explicitly confirmed they
were visible and matching dialogue. Only Portuguese received physical rendering
acceptance. Kodi retains multiple available tracks and displays one at a time.

Kodi's generic external/proxy names were replaced in the Mac dropdown by copied
language/label metadata, verified against current media identity. Portuguese is
first, English next, remaining languages alphabetical. The actual subtitle UI
and selected PT track are captured in ignored `.local/framextv-kodi-subtitles.png`.

## What failed

A rapid pause → seek60 → resume sequence was followed by no active video; user
confirmed the episode stopped by itself. Cause unresolved. Public MP4 seek had
worked, but actual HLS seek is not accepted. Do not retry disruptive controls on
the user's paused video without a deliberate test plan.

PT was served as `text/vtt` but contained SRT syntax (241 cues, first51.579s),
whereas English was actual WebVTT. Kodi content-sniffs captions; this mismatch
alone did not establish the initial missing-caption cause. Subtitle delay was0.
Do not infer playhead from duration or introduce conversion/relay without evidence.

All19 tracks were batch-loaded through ephemeral API orchestration. The Mac UI
only adds one external link at a time; no integrated JSON batch-import flow.
Aliases live only until server restart and do not rename Kodi's native TV menu.

## Remaining questions

Why does this HLS stop after the rapid control sequence? Does settled seeking
work? Can all-track loading/labels become a normal UI handoff? Which Android
source-capture flow and receiver adapters should a phone controller use? Full
phone power-off, reconnect, URL expiry/renewal, other-language rendering, source
changes and generic Cast acceptance remain untested. Exact LUMA model/Android
are unknown. No Android APK is implemented yet.

## Suggested next prompt

“Continue from Kodi's confirmed frameXTV HLS and Portuguese subtitles. Make the
source/all-track handoff a normal controller flow, investigate HLS seeking with
settled commands, then build an Android sender and test playback after fully
powering the phone off. Keep credentials/active URLs out of Git and avoid relays.”
