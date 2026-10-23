# 2026-10-23 Remote layout, fast Resume, auto-next caption language

**Goal:** user feedback after 0.4.12/receiver 0.3.0 test: Resume spends seconds
"finding video"; auto-play should keep the last enabled subtitle language; the
remote UI became clunky and needs reprioritising. Scrubbing declared done
(projector preview is slightly slow to start; deferred by the user).

**Changed:** `src/mobile/index.html` (remote markup and CSS), `src/mobile/app.js`
(render: play label/state, seek elapsed/duration, status without time, hidden
LUMA refresh and Choose video when loaded), `android/.../ResolvedSources.java`
(new), `ConnectionService.java` (cache in `resolveCatalogMedia`, `resolveFresh`),
`android-receiver/.../PlaybackController.java` (captionLanguage pref,
`captionCandidates`, fallback captions), `CaptionCandidatesTest.java` (new),
receiver `build.gradle` 0.3.1/15, tests `mobile.spec.js` and
`mobile-receivers.spec.js` (time assertions moved to `#seek-time`, LUMA volume
refresh is hidden), `release-notes/0.4.12.txt`.

**Commands:** `npx playwright test` (159 pass), `npm test` (242 pass), receiver
`:app:testDebugUnitTest`, signed release builds of both apps, `adb install -r`
to the phone, `node scripts/receiver-dev.mjs deploy --no-build` (0.3.1 installed;
S1E6 progress kept at 38:12).

**Not done / open:** physical check of the new layout, Resume speed with the
cache, auto-next caption selection; emulator instrumentation groups not rerun
for this change; nothing committed or released (`caffeinate -i make
deploy-gh-pages` when the user asks).

**Suggested next prompt:** "I tested the new remote layout and Resume: <findings>."
