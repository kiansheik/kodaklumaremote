# Philips player, catalog and offline Library — release checkpoint

## Goal

Fast receiver entry after power-on, real streams/subtitles, physical remote TV UX,
full movie/episode browsing and same-phone progress shared with Kodak/LUMA/Kodi.
The user ended Philips device work for the day, then explicitly authorized
publication and requested a durable offline metadata Library with Resume,
Start over and known next episode. No further Philips actions are authorized
implicitly by the release task.

## Files inspected

Required agent docs; MainActivity, ConnectionService, MsxServer, SubtitleDelivery,
MatterControllerAdapter, WatchHistory, CatalogClient, WebContentResolver,
TVCatalogBridge, mobile/receiver/core and handoff coordinator; release scripts,
exact-model manual, official Titan keys and Pointer Lock documentation.

## Files changed

Receiver HTML/CSS/JS/core and phone UI/tests; native caption bridge, MsxServer and
fixtures; discrete Matter On and fixtures; WatchHistory/CatalogClient,
WebContentResolver/TVCatalogBridge and fixtures; native capture/service integration;
new native runner; version0.4.6/code16, release notes and agent docs. Durable
LibraryStore/phone Library integration is being added before final publication.
Preserve unrelated headers.txt and all ignored private artifacts.

## Commands run

- Targeted rg/sed, git diff --check, bounded public-source privacy review.
- Full Node243/243; Python controller21/21 (localhost permission required).
- Mobile browser37+39 and receiver browser11 passing before Library additions.
- Production-signed build/lint and update metadata generation; public build
  omits the explicitly opt-in diagnostic media fixture.
- Native isolated catalog5/history6/TV catalog5/WebView resolver4 pass; prior
  MSX8/multi4/Matter7 pass. Structured TV fixture result fields were normalized.
- Separate live fixed-title emulator capture: HLS, three declared tracks, identity
  checked; no TV controls sent. The plain API had returned no source.
- Earlier adb install -r, own-app UI automation, ordinary own-TV keys and remote
  JavaScript reload. No physical action after the user's Philips stopping point.

## What worked

User-confirmed actual episode picture/audio/PT-BR,23 captured tracks; phone-normalized
caption LAN GET200/receipt while video continued. Data-preserving upgrades and
matching remote receiver revisions. Discrete On ACK while already on. Catalog
metadata, source-resolution fallback and bounded cancellation fixtures pass.
Latest installed APK remains9d2d4a7d…96fc797, receiver48c1b14a42, code15.

## What failed / remaining questions

Installed physical panel opens via Down/OK but arrows/select fail. Later unlocked
pointer/focus adapter passes browser checks; it is not installed or TV-accepted.
Pointer Lock API exists but was not active; actual unlocked event delivery/edge
clamping remains unverified. Last-source standby/browser restoration and native
TV/DevView execution are not established.

Current Kodak identity could not be read from the Mac: known HTTP API returned401,
with no saved Mac credentials. No credential guesses or playback controls. A retained
S1E2 record is historical, not today's current episode. Migrate only actual saved
phone progress; do not invent prior viewing history.

Durable Library implementation and checks are complete; final signedcode16 is
published and verified. Source commit/push, publication and live feed verification are tracked
in the [release handoff](2026-10-20-release-0.4.6.md). No final phone/TV install
or physical acceptance is claimed.

## Private evidence

Ignored Android-player artifacts hold stream-before-update-proof.json, candidate
installation receipts, matching-revision UI snapshots, browser/native logs and
control/catalog previews. Prepared releases live under .local/releases/v0.4.6.
Never add media URLs, provider bodies, credentials, APKs or headers.txt to Git.

## Suggested next prompt

Finish the offline Library checks and authorized0.4.6/code16 publication, retaining
the explicit physical-test boundaries. Resume Philips testing only when asked;
then test unlocked navigation, catalog/resume and startup/standby visibly.
