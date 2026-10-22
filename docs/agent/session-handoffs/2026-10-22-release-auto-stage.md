# Automatic release staging

## Goal

Let `make deploy-gh-pages` include the new 0.4.7 source/tests/docs without a
separate manual `git add`, while keeping unrelated or risky local files out.

## Files inspected

- Required agent documentation and the current release/config handoffs.
- `scripts/release-next-android.mjs`, its focused tests and release docs.
- The untracked paths reported by the user's failed release attempt.

## Files changed

- `scripts/release-next-android.mjs` and `tests/release-next.test.mjs`.
- Release documentation, current state, decisions, repo map, log, index and this
  handoff.

## Commands run

- `node --check scripts/release-next-android.mjs` — passed.
- Focused config, release, manifest and Pages tests — 13 passed.
- Full `npm test` outside the restricted localhost sandbox — 249 passed.
- `git diff --check` — passed.

## What worked

- All new `.md`, `.mjs` and `.txt` files named by the failed 0.4.7 attempt are
  accepted for automatic staging.
- New files are constrained by directory/type, per-file size, aggregate size and
  count. Private-key markers and common credential paths/tokens fail closed.
- Symlinks, binary/unsupported additions and `android/local.properties` fail
  closed. A maintainer may explicitly review and stage an otherwise valid file.
- Unrelated root files such as `headers.txt` stay untracked, and the ignored
  `.luma-release.json` remains outside Git.

## What failed

- The production deployment target was not rerun because it commits, pushes and
  publishes externally; the user can now retry it intentionally.

## Remaining questions

- Physical update acceptance remains pending after the production release.

## Suggested next prompt

Run `RELEASE_NOTES='Description of this release' make deploy-gh-pages`, then
verify the public manifest/APK and update the installed phone app.
