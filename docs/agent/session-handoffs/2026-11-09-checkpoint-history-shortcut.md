# Checkpoint history and quick load — 2026-11-09

## Goal

Keep five manual checkpoints per game so new saves do not immediately erase
older experiments, and offer a controller binding for quick loading.

## Files inspected

- Agent index/current-state/repo-map/open-questions and Games documentation.
- NesGameView, GamesActivity, ControllerInput/Profile/SetupActivity/ProfileStore,
  controller tests, receiver Gradle configuration and isolated Games smoke script.

## Files changed

- New CheckpointHistory and six JVM tests: immutable state files, bounded newest
  five list, commit before eviction, interruption handling and per-game isolation.
- NesGameView: legacy single-slot fallback/import, save/restore history entries,
  and checkpoint diagnostics. Original manual.state remains as a backup.
- GamesActivity: dated history picker, latest/older restore, audio pause before
  restore, neutral input gate after success and safe error/resume handling.
- ControllerProfile/Input/SetupActivity: persisted Load latest checkpoint action
  (2048), fresh-press-only Player 1 dispatch, no NES-bit leakage or default remap.
- ControllerProfileTest: action round-trip/edge tests and updated invalid-action
  boundary. Receiver version 0.3.23/code37.
- scripts/test-receiver-games.mjs: six saves, five-entry retention, scrollable
  history, older and newest loads, restore counter and retained history checks.
- Agent current state/log/repo map/Games docs and this handoff.

## Commands run

- Targeted rg/sed, git diff --check and node --check.
- Private release config loader, offline Gradle :app:testDebugUnitTest and
  :app:assembleRelease; test XML and release metadata inspection.
- Isolated LumaGames AVD, explicit emulator-5582 adb install and
  scripts/test-receiver-games.mjs with the private ROM (never committed).
- Authenticated projector games.status/games.stop, verified active=false,
  then receiver-dev deploy --no-build of the tested release APK.

## What worked

- All 121 receiver unit tests and signed optimized release build pass.
- Artifact: 3,195,560 bytes; SHA-256
  `78853317915fd296a8d01f5b3eb4fdc792ba7afbc5972fc4f97a7cc2bd1c96f7`.
- Storage tests cover five-save eviction/restart, non-destructive older lookup,
  ignored interrupted writes, rejected saves retaining existing entries, legacy
  archive dates/bytes and independent games. Profile test covers persistence,
  press vs repeat/release and exclusion from virtual NES edges.
- Isolated Android integration passes six manual saves → five retained entries,
  focus/scroll access to all five, older restore, latest restore, unchanged
  history after loading, native advancement, auto-resume and process restart.
  History screenshot was visually inspected: readable dated entries, fifth-row
  focus and reachable Back button. Evidence stays under ignored .local/nes-tests.
- Installed receiver 0.3.23/code37 at 2026-11-09T19:42:17.081Z. Projector
  game.stop was verified before installation. Reopened the same ROM and left it
  paused via Back: frames=2, checkpointCount=1, checkpointLoads=0, error=null.
  This confirms the existing checkpoint is recognized, not a physical restore
  acceptance. Phone remains 0.4.35; no public release, commit or push.

## What failed / limits

- Initial build caught an overbroad edit inserting the profile helper into
  nested classes; corrected before build. An existing rejection fixture used
  the newly valid action value 2048; updated to unsupported 4096.
- The first Android check expected all five rows in one UIAutomator snapshot.
  Only four fit onscreen; updated the test to navigate/scroll to the fifth row.
- Five means the newest five manual saves; loading an older entry does not
  promote it. The shortcut consistently loads the newest saved checkpoint.
- Physical shortcut/button selection and human history acceptance remain pending.

## Remaining questions

- Which physical button will the user choose for Load latest checkpoint? It is
  offered in Controllers → NES button assignments without altering saved mappings.
- User acceptance of the chosen physical shortcut remains pending.

## Suggested next prompt

“Choose Load latest checkpoint for a controller button and save the assignments;
verify it restores once per press, then choose an older save from Checkpoint history.”
