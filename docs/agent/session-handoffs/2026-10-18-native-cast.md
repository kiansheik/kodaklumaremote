# Session handoff — native Cast investigation and increment

## Goal

Inspect the requested frameXTV frontend and anti-debug behavior; implement a
minimal Chrome MV3 native Cast wrapper as technically supported; provide tests,
reproducible documentation and honest physical-device validation boundaries.

## Files inspected

- User attachment mission and prescribed agent-note paths (initially absent).
- Workspace inventory (only original empty `headers.txt`; no Git repository).
- Ignored `.local/recon/` public watch HTML, config, index, WatchPage, EmbedPage
  and Player chunks. Hashes and source offsets: [investigation](../investigation.md).
- Sanitized `.local/recon/browser-metadata.json` and
  `.local/recon/browser-chrome-headed.json` from independent clean profiles;
  `.local/recon/browser-extension.json` from opt-in live extension sampling.
- Official Chrome MV3/CSP/RHC and Google CAF/media docs linked in
  [feasibility](../chromecast-feasibility.md); Google Cast sample media catalog
  and Blender's license page linked in [experiments](../experiments.md).
- Pinned Playwright launch switches (MediaRouter disabled by automation defaults).
- All new source/config/test files; source is authoritative over these notes.

## Files changed

- `AGENTS.md`, `package.json`, `package-lock.json`, `.gitignore`, `playwright.config.js`.
- `src/extension/{manifest.json,player.js}`; `src/shared/media.js`;
  `src/cast/{session.js,demo.js}`.
- `scripts/{build,serve,demo-server,public-cast-smoke,recon-http,recon-browser}.mjs`.
- `tests/{media,cast-session}.test.mjs`, `tests/browser/extension.spec.js`,
  `tests/fixtures/` synthetic HTML/CAF/WebVTT and public-demo HTML;
  `tests/manual-chromecast.md`.
- `README.md` and `docs/agent/` index/current-state/repo-map/open-questions,
  research pages, decisions/experiments/progress/log and handoffs.
- Generated `dist/`, `.local/` evidence/TLS and local npm dependencies are ignored,
  not checked-in sources. Original `headers.txt` untouched and left untracked.

## Commands run

- `rg`/targeted reads; attempted required agent-note reads; `git init`.
- Target/control HTTPS HEAD and bounded public frontend downloads; SHA-256 and
  bounded marker/offset analysis. No stream decryption or API response extraction.
- `npm install --offline --cache /tmp/framextv-npm-cache --ignore-scripts`
  (failed empty cache), then `npm install --offline --cache /Users/kian/.npm
  --logs-dir /tmp/framextv-npm-logs --ignore-scripts` (passed; lock generated).
- `node --check scripts/recon-http.mjs`; `node scripts/recon-http.mjs --help`;
  `node scripts/recon-http.mjs --assets` (passed outside network sandbox).
- `node scripts/recon-browser.mjs`; headed stable variant in [handoff](../handoff.md).
- `npm run build`, `npm test`, `npm run test:browser` iteratively; final
  **`npm run check` passed build +25 unit tests +15 loaded-MV3 browser tests**.
- `node scripts/public-cast-smoke.mjs` (final HTTPS exit0, actual SDK ready,
  secure context, mediaRequests0, deviceSelectionAttempted false).
- Header-only `fetch(publicURL,{method:'HEAD',signal:AbortSignal.timeout(15000)})`
  against the current official public MP4 listed in E08 (200, mp4, ranges, CORS).
- `git diff --check`; explicit selective stage/commit (no raw evidence/media).

## What worked

- Network sandbox DNS failure distinguished from real target reachability.
- Mapped existing native Cast and source-bearing same-origin embed before building
  the fallback, avoiding an unnecessary extension SDK download.
- Genuine loaded-extension fixtures verified the MAIN-world existing-SDK bridge,
  frame matching, local playback gating, remote controls, subtitle metadata,
  duplicates, unavailable page, dynamic players, races and cleanup.
- Live extension probe verified execution in actual watch/embed frames with CAF
  present, although page blanking preceded a visible player/source/control.
- Fixed concrete defects found in browser checks/review: author `:host` display
  overriding hidden; ended-state reset; rejecting disconnect Promises; source
  changes while LOAD pending; wrong-episode playhead restoration; BFCache return.
- Actual SDK loaded on ordinary HTTPS demo; current official MP4 is reachable
  with appropriate advertised range/CORS headers.

## What failed

- Sandbox blocked initial DNS and local test-port bind; normal scoped escalation
  enabled read-only network/browser tests and Git operations.
- Initial browser regressions failed before the above fixes (recorded in E06).
- Target clean automation repeatedly ended at `about:blank`, even headed stable
  Chrome. Final DOM alone could not establish source/player decode/receiver access.
- Initial HTTP public demo bootstrapped HTTP gstatic resources blocked by policy.
  Playwright also disables MediaRouter by default. Used HTTPS and a managed
  temporary profile with that pinned default switch removed. Discard-all-defaults
  approach failed; only owned smoke Node processes were terminated.
- Old public Google MP4 samples returned403 and a guessed Blender M4V404. Replaced
  with current official Google sample catalog URL; no workaround for server403.

## Remaining questions

See [open questions](../open-questions.md). No physical receiver chosen or tested.
Public SDK readiness and deterministic tests do not prove target TV playback.
Anti-debug triggering detector/manual DevTools/fingerprinting, codec/expiry/auth,
receiver CORS, actual subtitles and audio-language control remain open. No
evidence currently justifies a relay or custom receiver.

## Suggested next prompt

“Run tests/manual-chromecast.md with my [receiver model/name], first the public
HTTPS demo, then the existing native frameXTV button and fallback where needed.
Record actual device results and isolate failures without storing active source
URLs/cookies or bypassing provider restrictions.”
