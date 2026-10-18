# Handoff — working Kodi public playback on LUMA

## Goal

Turn the user's confirmed Kodi availability into a physical direct-playback
experiment, preserving the eventual Android phone → receiver → phone fully OFF
requirement. Build a reproducible Mac stepping stone and validate its boundaries.

## Files inspected

Required agent index/current-state/repo-map/open-questions; original mission,
receiver-apps/LUMA/mobile notes; package scripts, demo sample, discovery tests,
fixture server/config, README. Primary Kodi HTTP/auth/API and released network
service source; public sample HEAD/catalog. Native Chrome controller/app UI.

## Files changed

- `scripts/kodi-controller.mjs`: private IPv4 receiver validation, authenticated
  JSON POST `/jsonrpc`, fixed public MP4, connect/status/pause/resume/absolute
  seek/stop; bounded responses/timeouts, no redirects/raw errors/credential saves.
- `src/kodi/controller.html`: responsive module-scoped UI; private credentials,
  connection-gated controls and honest accepted-vs-playing status.
- `package.json`: `npm run kodi`.
- `tests/kodi-controller.test.mjs`, `tests/browser/kodi.spec.js`: transport/API/
  localhost boundaries and actual browser flow against mocked Kodi.
- `tests/manual-kodi.md`, README and agent current-state/repo-map/open-questions/
  mobile-target/receiver-apps/LUMA/progress/log/index/handoff plus this handoff.
- Ignored `.local/kodi-controller-playing.png`: live UI screenshot, not Git.
  Original untracked empty `headers.txt` remains untouched.

## Commands/actions run

- Targeted `rg`/`sed`/Git reads; `node --check`; whitespace/local-link checks.
- Bounded macOS `dns-sd -B _xbmc-jsonrpc-h._tcp local` (6 seconds), `-L` (5),
  `-G v4` (5), terminated subprocesses; no general port scan or stored inventory.
- `npm run kodi`, loopback-only UI at `http://127.0.0.1:4175`.
- Narrow Kodi Node/browser tests; final `npm test`: **44/44** passed including
  11 Kodi tests. **3/3** new Kodi Playwright tests passed. Previous 16 MV3 browser
  tests were not rerun; no extension source/build changed.
- Local socket tests/server initially denied sandbox listening; approved network
  execution succeeded. No automatic approval-review rejection occurred.
- Native Chrome: enter user-provided receiver settings, connect (Kodi 21.2),
  inspect blocked controls, dismiss native password notification, refresh status.
- Direct ephemeral module calls used the same `performAction` implementation
  for actual public play/status/pause/seek90/resume. Credentials stayed in runtime
  calls, not files, stdout, saved UI state or Git.

## What worked

Bonjour automatically resolved the receiver endpoint, matching the user's
independent report. Authenticated Kodi 21.2 accepted the public MP4; receiver
reported active/playing at 7/596 seconds, and user confirmed projector picture
and sound. Pause reported false and resume true. Seek initially returned stale
status at 34 seconds; later position at 117 seconds confirmed the queued 90s
seek and continued progression. Mac controller stopped at 08:12:01; status at
08:12:29 still advanced. No Mac media request/relay exists in this code. Later
actual browser UI showed Playing 3:30/9:56; screenshot saved locally.

## What failed / limits

Initial Chrome clicks were obstructed by a native password notification and
tab/native-window targeting; full native window inspection resolved it. No
application JavaScript fault was established; module scope is robustness only.
Kodi accepts playback/seek asynchronously; an immediate response can be stale.
Only the public MP4 is exposed by this tool. No frameXTV URL load, subtitles,
phone app, phone shutdown, source expiry/auth renewal or generic Cast success.
Model/Android/CPU remain unknown; user installed Kodi, agent did not sideload.

## Remaining questions

Can the intended authorized HLS source be handed to Kodi independently, including
tracks/expiry without phone-only cookies or renewal? Which native Android source
capture/controller path should integrate Kodi JSON-RPC plus genuine Google Cast
for other receivers? A phone-hosted relay/hotspot cannot meet full phone-off.

## Suggested next prompt

“Continue from confirmed Kodi 21.2 LUMA public-film playback. Integrate the
authorized source and Android phone controller, reusing the source/receiver
separation. Prove full phone-power-off playback, reconnection, tracks and expiry.
Keep Kodi distinct from Google Cast; no phone media relay or credential logging.”
