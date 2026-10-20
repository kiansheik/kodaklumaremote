# Offline viewing Library

The phone Library keeps watched movie/series metadata and exact episode progress
in app-private SQLite. It stores no media files, stream URLs or authorization
tokens. The same phone owns this history while controlling Philips or Kodak/LUMA
with Kodi; there is no cloud or multi-phone synchronization.

## Storage and identity

Titles use canonical provider kind/id; TV progress also includes season/episode.
The existing `WatchHistory` validates accepted handoffs, fresh source-matching
playback and same-episode ownership before notifying `LibraryStore`. Progress
writes immediately. The durable Library does not inherit the recent-history
100-record eviction. Existing retained history migrates idempotently; previously
untracked viewing cannot be reconstructed from a title guess.

Catalog details and episode metadata are enriched in the background for seen
titles/seasons. Network failure preserves progress and available metadata. Source
resolution is separate: explicit Resume/Start over/next actions obtain a fresh
source using the existing typed native catalog route. Older progress must be
looked up from durable storage even after leaving recent history.

## Read-only interface

- `watch.library {offset?,limit?,query?}` returns grouped titles, a flat latest
  progress summary, availability and pagination. Limit is1..100, default50;
  the optional search query is at most120 characters.
- `watch.libraryDetails {kind,id}` returns saved title metadata, cached seasons
  and episode progress, movie progress, and a known next selection when proven
  by cached catalog entries. A watched fallback row does not establish catalog
  ordering or a season boundary.

These reads use local storage and initiate no network or playback operation.
The phone exposes Library in bottom navigation and Browse. Opening a title shows
exact episode/progress/watched status and last receiver. Resume, Start over and
Play next episode preserve the selected playback device. Unknown next episodes
remain unknown; missing cached metadata does not erase watched progress.

## Validation boundary

Native persistence/migration/offline/metadata-failure/ordering fixtures and phone
browser results are recorded in the final release handoff. Artwork is represented
by fixed catalog URLs; artwork bytes are not downloaded into offline storage. Physical Philips catalog,
remote navigation and cross-device resume remain separate acceptance tests.
No Philips device actions should resume after the user's stopping point unless
requested. The current release task authorizes publication, not another TV test.
