# Handoff — LUMA and existing Android receiver apps

## Goal

Assess whether the user's older Kodak LUMA can run an existing or thin receiver
APK that accepts a media URL and keeps fetching after the phone powers fully
off. Preserve the broader Cast/DLNA mobile target without substituting mirroring.

## Files inspected

Required index/current-state/repo-map/open-questions; mobile target, progress,
log, prior handoffs, original attached mission; existing repository status.
Primary Kodak support/product/manual pages, Kodi API/settings/Android docs,
VideoLAN Android Remote access docs and released 3.6.5 source, Android Media3
docs, and receiver publishers' official sites/store listings. Source links live
in [receiver interfaces](../receiver-apps.md) and [LUMA options](../luma-receiver-options.md).

## Files changed

- New `docs/agent/receiver-apps.md`: Kodi JSON-RPC example, VLC Android source
  evidence, receiver APK architecture, compatibility limits and first experiment.
- New `docs/agent/luma-receiver-options.md`: Kodak DLNA claims, commercial/app
  alternatives and direct-fetch acceptance sequence.
- Updated current-state/index/repo-map/open-questions/mobile-target/progress/log/
  handoff and this session handoff to link the research and preserve uncertainty.
- No application code, installed extension, projector settings or APK changed.
  Original untracked `headers.txt` remains untouched.

## Commands/actions run

- `git status --short`, targeted `rg`/`sed`/`tail` reads and diff inspection.
- Primary-source web searches/opens, including Kodi `Player.Open`, HTTP control,
  VLC Android 3.6+ docs and released network-URL playback route.
- `git diff --check`; local Markdown-link target check for changed documents.
- No runtime tests rerun: changes are documentation only. Existing code-test
  results remain those in current-state, not new hardware acceptance.

## What worked

Kodi provides a documented authenticated HTTP JSON-RPC control route; a URL can
be handed to the receiver-side player with `Player.Open`. VLC Android has real
Remote access since 3.6; released 3.6.5 code accepts a URI via its paired `/play`
route. That endpoint is an implementation detail, so prefer its browser UI for
the first experiment. Kodak advertises DLNA on current 350/400/450 pages. AirPin
documents DLNA receiving and a mode preserving playback when the controller
exits. These support concrete experiments without building another player first.

## What failed / limits

No LUMA hardware was inspected, discovered or controlled. The user cannot recall
the model; reports native YouTube/Crunchyroll/Amazon, an app store blocking some
apps, and YouTube receiver discovery only while open. That does not establish
generic Cast, installed Android version or APK compatibility. Current Kodak
pages may describe newer revisions. Sideload support, app installability, target
media codecs/auth/expiry and phone-off behavior are unknown. No projector app
installation, APK download, protected media extraction or playback occurred.

## Remaining questions

Can the projector install Kodi or VLC from its store? An optional user question
was sent asking which is available. Does its built-in DLNA expose AVTransport?
What exact model/Android/ABI is installed? Can the selected receiver fetch the
intended source with no phone relay, hotspot or phone-only token renewal?

## Suggested next prompt

“Kodi [or VLC / built-in DLNA] is available on my LUMA. Test an authorized public
MP4 with receiver-owned retrieval first, using the Mac as an initial controller
if needed. Then prove phone-fully-off continuation and test the intended media.
Use the documented API; build a thin receiver only if existing apps are unsuitable.”
