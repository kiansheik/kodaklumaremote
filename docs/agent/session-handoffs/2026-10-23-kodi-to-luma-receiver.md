# 2026-10-23 Kodi → LUMA Receiver

Goal: replace Kodi on the Kodak LUMA with our own receiver APK and migrate the
phone app, tests and release pipeline to it; add synced scrubbing and
projector-side history.

Changed: `android-receiver/` (new app + README), phone `NativeBackend`,
`ConnectionService`, `SettingsStore`, `LibraryStore`, `MainActivity`;
`src/mobile/{app.js,index.html}`, `src/handoff/coordinator.mjs`; site install
page; release scripts (`build-android-release`, `publish-android-release`,
`release-next-android`, `generate-update`, `build-pages`, `deploy-gh-pages`,
`site/update-manifest.js`); tests (JS, browser, emulator fixtures, release);
`scripts/receiver-dev.mjs`; removed `kodi-addon/`, `package-kodi-addon.mjs`,
Mac Kodi controller and their tests; `release-notes/0.4.9.txt`; docs.
Emulator-fixture details: [fixture handoff](2026-10-23-android-receiver-fixture-migration.md).

Commands: receiver Gradle unit tests (9) and dev-channel deploys 0.1.1→0.2.0 to
the projector; `npm run check` (Node 241, browser 157); emulator groups backend
14, service 18, settings 6, subtitles 9, multi-receiver 5, packaged UI 9 (also
9/9 on b59e15b in a temporary worktree).

Failures/notes: earlier packaged-UI 6/9 was leftover emulator state (passes from
clean install). Playwright and emulator runs stall when the battery-powered Mac
idles; use `caffeinate -i -u`. Picture effects (gamma/brightness) showed no
visible difference mid-playback. Signing-migration and live-controls groups
compile only. A user `make deploy-gh-pages` attempt bumped to 0.4.9/code 19 and
staged files before stopping at checks; rerunning keeps that version.

Open: physical phone pairing/casting/sync/scrub acceptance; redundant guarded
seek after a receiver-applied resume in system Next (harmless); first-install UX
for other projectors (Pages link in TV Bro).

Next prompt: "Publish 0.4.9 with `make deploy-gh-pages`, install it on the phone,
pair with the projector and verify cast, subtitles, synced scrubbing and Library
sync."
