# Agent instructions

Before editing code, read `docs/agent/index.md`, `current-state.md`, `repo-map.md`,
and `open-questions.md`. Use `rg`, `git grep`, `find` and targeted reads before
opening large files. Do not load the whole repository into context.

Code, tests, schemas and checked-in configuration are authoritative.
`docs/agent/` is compiled memory; correct it when it disagrees with source.
Do not invent behavior; distinguish verified observations from hypotheses.
Keep generated notes concise and linked; summarize broad exploration into the
repo map or a domain page.

After significant work, update `docs/agent/current-state.md`, `log.md`, and add
a handoff in `session-handoffs/` containing goal, inspected/changed files,
commands, successes, failures, remaining questions and suggested next prompt.

Prefer small reviewable diffs. Do not edit generated files unless explicitly
asked. Do not run destructive commands without explicit user approval. Run the
narrowest useful checks and explain unavailable tests in the handoff. Keep raw
media, session data, credentials and active media URLs out of Git.
