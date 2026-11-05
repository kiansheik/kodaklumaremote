# Manual stream-source pages

## Goal

Let a person reopen the current or saved movie/episode in LUMA Remote's browser,
visit the corresponding movie/series page, choose another website server and
cast that replacement without interrupting the stream already playing.

## Files inspected

- `docs/agent/{index,current-state,repo-map,open-questions,site-architecture,network-analysis}.md`
- `src/mobile/{index.html,app.js,capture.js}`
- `android/app/src/main/java/dev/framextv/lumaremote/{MainActivity,WatchHistory,LibraryStore,CatalogClient,AnimeCatalogClient,WebContentResolver}.java`
- `.local/recon/WatchPage-DY33XwPg.js`
- `tests/browser/{mobile,mobile-receivers}.spec.js`
- Android release configuration and existing release notes

## Files changed

- `src/mobile/index.html`
- `src/mobile/app.js`
- `tests/browser/mobile.spec.js`
- `android/app/build.gradle`
- `release-notes/0.4.28.txt`
- Agent current state, log, index, repo map, open questions and this handoff

## Commands run

- Targeted `rg`, `find` and `sed` source/route inspection
- `git diff --check`
- `npm run build`
- `npm test`
- Focused and complete Playwright phone suites
- Offline Android debug assembly
- Signed Android phone/receiver release build and manifest generation

## What worked

- The inspected site bundle confirms title routes `/movie/{id}` and
  `/tv/{id}?season={season}`; retained anime provenance uses `/anime/{anilistId}`.
- Remote **Change stream** opens the exact canonical `/watch/...` route. Opening
  it does not send receiver Stop/Open commands, so current playback continues.
- Library opens either the selected player page or the corresponding title page.
  Its hint points to the site's player server menu and the existing Cast action.
- Node passes 253/253. The complete phone suites pass 100/100 and the focused new
  route/non-mutation cases pass 2/2. Site build and Android debug assembly pass.
- Signed phone 0.4.28/code38 and receiver 0.3.7/code21 artifacts and manifests
  were prepared in ignored `.local/releases/v0.4.28`.

## What failed

- The first Playwright run could not bind its loopback server in the sandbox;
  the approved outside-sandbox rerun passed.
- The repository has no Gradle wrapper. The first cached-Gradle run could not
  load its macOS native library in the sandbox; the approved offline rerun passed.
- A sandboxed full Node run stalled in local protocol/server fixtures after its
  early groups passed; the outside-sandbox rerun completed 253/253.

## Remaining questions

- No APK was installed and no projector or live provider was controlled in this
  change. Physical acceptance must confirm the built-in page remains usable and
  a newly selected provider actually improves the resumed `Buddy` stream.
- The app deliberately does not claim to remember which opaque provider was best
  yesterday. The website's ordinary server UI and its own browser storage remain
  the source-selection mechanism.

## Suggested next prompt

Install the prepared 0.4.28 phone and 0.3.7 receiver APKs after playback ends,
then test Change stream on the current Buddy episode without stopping it first.
