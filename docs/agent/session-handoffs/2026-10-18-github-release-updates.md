# GitHub distribution and optional app updates

## Goal

Publish the user-specified `kiansheik/kodaklumaremote` repository, a GitHub Pages
install/update page and a signed APK that a household member can install.
Offer optional updates on app opens using the user's existing local Android
key, preserving the owner's current debug-install settings.

## Files inspected

Required agent docs and previous phone handoffs; native lifecycle/manifest/
signing/settings, mobile Settings UI and existing tests. Public repository/Pages
state, local signing configuration privately, Android signing/installer docs and
GitHub Pages workflow docs. Existing11-commit/263-blob history reviewed for
credentials, active sources and archived binaries; no such publication blocker.
The local archived third-party APK and device details remain ignored.

## Files changed

- `UpdateManager.java`, `UpdateApkProvider.java`, MainActivity hooks/manifest:
  fixed Pages feed, five-minute foreground checks, silent automatic offline
  failure, Later24h, explicit download, size/hash/package/version/current-signer
  validation and read-only temporary installer URI. Unknown-source settings
  return requires explicit Continue; Android owns final installation approval.
- Mobile Settings manual update action; app label LUMA Remote/version0.2.4/code6.
- `site/`, Pages workflow and strict manifest/release generation/bundling tests.
- Local build/publish helpers, public certificate fingerprint and release notes.
  Keys/passwords are not stored in the repo or GitHub Actions.
- Updater/migration native fixtures and browser/page tests. Migration seed is
  emulator-only; actual-device migration check is read-only and prints no secrets.
- README/Android README, `docs/releasing.md` and agent state/map/index/decisions/
  progress/log plus this handoff.

## Commands run

- Targeted `rg`/reads, repository/history/private signing metadata inspection;
  `git diff --check`, JavaScript/XML/YAML checks.
- `npm test`:134/134; `npm run build`; full browser77/77, including install-page
  phone/desktop and manual Settings update action. Representative screenshots
  reviewed. The page contains no media or runtime household credentials.
- Debug/test compilation; signed release build and release lint passed. Initial
  native Intent chain failed compilation (`setClipData` returns void), corrected.
  Offline release lacked `lint-gradle`; official online resolution completed it.
- Emulator-only updater6/6 and final packaged UI5/5 passed. No installer or real
  projector command was opened by updater fixtures.
- Private `apksigner rotate/sign` using exact debug and user-owned release keys;
  default API33+ migration. No key files changed/exported/uploaded.
- Emulator signing migration passed three stages: debug fixture seed, private
  lineage bootstrap, ordinary production-signer-only APK replacement. Encrypted
  credentials and private marker survived; no uninstall/reset was performed.
- Final build/publication, Pages verification and actual-phone migration are
  recorded below after their returned results, rather than assumed from builds.

## What worked

The existing production certificate was found and validated. Publishing checks
its public fingerprint. A separate private bootstrap migrates the owner's API36
debug install; fresh public installs use production signer only. Emulator proof
shows ordinary future production updates no longer require the debug signer.

The public page and native updater share fixed package/repository/release-asset
contracts. Metadata derives from the actual signed APK, not a manually entered
version/hash. Draft assets finish uploading before publication triggers Pages.
Pages bundles only reviewed page files and matching stable-release metadata.

## What failed

The first compile caught an Android API chaining mistake; fixed and rebuilt.
Offline release lint needed a previously uncached official dependency; fetched
without disabling checks. No signing credentials or private key were included
in source, releases or GitHub Actions.

## Remaining questions

Full physical download → Android permission → user-confirmed installer update
still needs household acceptance after a genuinely newer public release. The
emulator covers update network/integrity/provider/eligibility boundaries and
actual signed APK replacement/settings preservation. Projector-off volume/power
tests and the earlier Kodi startup/subtitle failures remain separate and open.

## Suggested next prompt

Install from the published page on the second phone. Connect to the household
projector/Kodi with its existing configured login. On the next higher-version
release, open the app and confirm Later/Update, Android installation approval and
preserved setup. Continue S1E3 receiver diagnosis when the projector is on.

## Publication evidence

Pending returned publication and phone-migration results.
