# Handoff — live Philips test and mobile target

## Goal

Test the user's installed extension against powered Philips43PFG6910/78, fix
misleading errors, and preserve the clarified final requirement: phone initiates
then powers fully off; receiver fetches media independently. Mac is a stepping
stone. Mirroring does not pass this acceptance.

## Files inspected

Required agent notes; `src/cast/session.js`, `src/extension/player.js`,
session/media/browser tests and fixtures; native Chrome player/extension
management/Cast/YouTube UI; exact Philips manuals, Samsung Smart View docs,
Google Cast error/lifecycle and Android SDK docs, Microsoft Miracast docs,
official UPnP architecture/AVTransport specs (linked in domain notes).

No raw browser AX snapshots, personal tabs, device identifiers, signed media URLs,
private addresses, cookies or media were saved to Git.

## Files changed

- `src/cast/session.js`: sanitized errors distinguish SDK readiness, canceled
  chooser, receiver unavailable, session timeout/failure and media LOAD failure.
- `src/extension/player.js`: preserve those distinctions with fixed messages.
- `tests/cast-session.test.mjs`, `tests/browser/extension.spec.js`, mock CAF:
  regression coverage that discovery failure does not call or blame media LOAD.
- `scripts/discover-tv.mjs`, `tests/discovery.test.mjs`, package discovery command:
  bounded read-only SSDP/descriptor capability inspection, target-only output.
- README, current-state/log/progress/index/repo-map/open-questions/decisions,
  experiment log, TV/mobile target notes and manual acceptance record.
- Generated `dist/extension` rebuilt; loaded instance reloaded via Chrome UI.
  Original untracked empty `headers.txt` preserved.

## Commands/actions run

- Targeted reads and `git diff --check`.
- `node --test tests/cast-session.test.mjs` (19 session tests).
- `npm run check` (build +30 unit +16 actual-MV3 browser tests passed).
- `node --test tests/discovery.test.mjs`; bounded `node scripts/discover-tv.mjs`
  outside network sandbox; final discovery result documented in experiments.
- Final `npm test`:33 passed including3 discovery tests. Final probe with TV
  confirmed in normal mode read exact target description, no recognized service
  entries/AVTransport and zero errors. This is not a successful direct-media load.
- Native Chrome UI: read loaded path `~/code/framexty-analysis/dist/extension`,
  Reload extension, reload player, click Cast. Error now correctly reports a
  receiver/session connection failure, not a media-access failure.
- Native Chrome menu Cast initially “No devices found”; after user restored TV
  network,43PFG6910/78 appeared disabled, “Available for specific video sites”.
- Opened public Blender film `https://www.youtube.com/watch?v=aqz-KE-bpKQ`,
  selected the same Philips in Play on TV; observed Connected/Playing on receiver;
  user explicitly confirmed actual TV picture and sound. Sender pause, +5-second
  seek and resume reflected in remote UI. Stopped own test route; local paused
  player returned. No unrelated browser tab was altered.
- Inspected native source menu (Cast tab selected, Cast screen offered globally;
  neither is proof that this TV supports those modes). Did not mirror the desktop.

## What worked

The real user Chrome profile keeps the target player usable, unlike earlier
clean Playwright disruption. Visible extension insertion/live retry is verified.
Source extraction progressed far enough to attempt a Cast session; generic media
LOAD was not accepted or tested. YouTube-specific hardware playback was physically
confirmed, so discovery/network and general Cast capabilities must be separated.
New errors retain the correct failing stage and do not expose SDK details/URLs.

## What failed / limits

No generic frameXTV receiver could be selected. Exact TV receiver capability
cannot be inferred just from the discovered name or YouTube success. Initial TV
offline interval explained the empty list; later disabled-site status is a
different issue. Mac System Settings computer access was unavailable; permission
state was not inspected or changed. Chrome media-router internals were disabled;
did not enable debug settings and closed only the task's unavailable debug tab.
Read-only UPnP discovery is bounded negative/positive evidence only, not a full
protocol or firmware inventory. No arbitrary media stream, phone app, phone-off
test, relay, receiver software installation, auth or DRM bypass performed.

## Remaining questions

Does Philips expose a generic Cast receiver/app or UPnP MediaRenderer in normal
mode? Does a chosen receiver fetch an authorized target URL with no phone proxy,
hotspot or browser-only credentials, across manifest expiry? Which Android
source-integration path preserves the provider's ordinary playback flow?
Miracast-only targets cannot meet phone-off retrieval without another player.

## Suggested next prompt

“Continue the Android direct-playback controller using the receiver capabilities
recorded here. Prove public URL playback on a genuine Cast or verified DLNA
renderer, then actual source playback and full phone-power-off continuation.
Keep Miracast marked mirroring-only; don't create a phone-hosted media relay.”
