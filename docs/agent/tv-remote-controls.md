# Philips TV remote player

## Confirmed stream baseline

On 2026-10-20 the existing phone capture opened an actual HLS episode on the
Philips 43PFG6910/78. It exposed 23 subtitle tracks; playback progressed from
0:09 through 2:51 of 22:35, including a brief phone-background interval. The user
confirmed smooth picture, sound and Portuguese captions. This acceptance used
the earlier e0ddaf93…410a4 APK. Phone-off and long background operation were
not tested. Private evidence is `stream-before-update-proof.json` under ignored
Android-player artifacts; no media URL belongs here.

## Receiver controls

The receiver source provides a focus-driven TV panel. In a key-delivering context,
OK or a directional key reveals it; arrows move focus and OK activates the selected control. Playback,
±10-second seek, subtitles/Off, optional standard full screen and Hide controls
are available. Back first closes the language picker, then the panel; a further
Back is left to the browser. Home, speaker volume and unknown keys remain native.
The panel hides after six seconds; an open language picker remains visible.

The caption picker prioritizes Brazilian Portuguese while preserving native
catalog indices. It prepares only the selected language, keeps video running,
and ignores stale completion after Off or a source change. CSS hides the page
cursor; browser chrome can still display its own pointer or consume keys.
The full-screen action requires a user gesture and its failure is nonfatal.

[Official Titan key documentation](https://docs.titanos.tv/navigation) supplies
ordinary `keydown` mappings: arrows37–40, Enter13, Back8, Play415, Pause19,
Play/Pause179, Stop413, rewind412 and forward417. No special registration API
was required by the reviewed primary documentation. Exact hardware delivery is
a separate device acceptance check.

The actual browser delivers pointer input rather than ordinary arrow/OK DOM keys.
The installed pointer-lock candidate opens its panel after Down/OK but does not
navigate it correctly. The newer source handles unlocked movement, stored focus,
background OK activation and direct button hover/click. Bounded diagnostics count
movement and coarse pointer-lock errors without coordinates. Browser fixtures
pass; physical input delivery and edge clamping need a later TV test.

## Catalog and viewing progress

The TV source includes movie/series search, seasons/episodes and recent titles.
Native typed selection resolves a fresh source; if the fixed stream API has no
source, an owned canonical watch-page WebView captures the ordinary declaration.
The fixed-title emulator test resolved HLS and three declared tracks. No arbitrary
URL is accepted through this catalog selection API.

`WatchHistory` records exact movie/episode identity and fresh observed progress
only after accepted native handoff. A positive resume requires fresh same-source
near-position confirmation; failed seeks preserve prior progress. Per-episode
records remain distinct. Recency follows accepted selection (`lastStartedAt`),
not later polling from an older receiver. Stream URLs/tokens remain in RAM.
The same phone owns progress across Philips and LUMA/Kodi; this is not multi-phone
cloud synchronization. The durable offline Library addition is tracked in the
current handoff; no TV test of the latest catalog/resume source has run.

## Subtitle delivery

`TvSubtitleBridge` fetches only a selected, previously declared public HTTP/HTTPS
caption. It applies the existing `SubtitleNormalizer`, an 8-second/2 MiB fetch
budget, four redirects with no HTTPS downgrade, and bounded RAM storage.
The TV receives a same-origin capability URL restricted to the approved peer,
accepted source/catalog and current session. This carries caption text only.

Phone selection keeps `msx.subtitle`; the server injects its prepared URL.
Physical remote selection uses the exact authenticated `subtitle-prepare` POST
with client, session, index and expected source, then changes the local player.
This endpoint neither queues a playback command nor fabricates an acknowledgement.
Off performs no fetch. Source/session/reset revocation and selection generations
prevent stale captions. An accepted catalog survives browsing-token revocation;
a native process restart requires a fresh authorized Open to recover its catalog.
Subtitle errors are separate from media errors and preserve prior working captions.

## Startup and standby

The user set **Conduta de inicialização → Última fonte** using the exact-model
[Philips manual](https://www.documents.philips.com/assets/20250325/8209e624f4694ae9a48bb2ab00357e47.pdf),
printed p31. It promises the last source, not restoration of a browser page.
The known settings-tree API exposed only Ambilight nodes, so no startup-setting
ID or hidden setting was guessed.

Philips More offers **Open TV receiver**. A fresh receiver is reused without
interrupting playback. Otherwise one browser request is followed by bounded
observation of actual receiver state/approval. HTTP acceptance alone is never
called a visible launch. The fallback is Internet → the saved LUMA bookmark.
The bookmark uses the phone LAN origin plus `/msx/connect.html`; a changed phone
address still requires updating that bookmark.

A separate **Turn on TV** action requires an advertised On capability, refreshes
that capability, invokes one discrete Matter On and optionally reads OnOff.
Known ACKs survive optional readback failure/timeout without inventing visible
power state or automatically resending. Existing Off confirmation is preserved.
The manual printed p30 requires **Ligue com LAN** for phone wake from standby.
A full cold boot, standby wake and visible browser restoration need device proof.

## Validation

Before the durable Library addition: Node243, mobile browser37+39 and receiver
browser11 pass; native catalog5/history6/TV catalog5/resolver4 pass, plus one
explicit live fixed-title resolver check. Signed production build/lint pass.
The receiver DOM is checked at 1920×1080 and 1280×720; controls, captions and the
catalog preview have been reviewed. These checks do not replace the outstanding
physical remote, catalog/resume, standby and browser-restoration acceptance.
Current release and later Library validation belong in the linked current state
and session handoff.
