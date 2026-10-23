# Kodi metadata and cross-phone progress

## Canonical playback contract

Cross-phone recovery depends on the source-only Kodi add-on
`script.lumaremote.metadata`. When the Android app starts a catalog item, the
native Kodi adapter checks that the add-on is enabled and sends one bounded,
base64url-encoded version-1 payload through `Addons.ExecuteAddon`. The payload
contains the exact public media source plus canonical movie or episode identity.
The add-on validates every field, creates the Kodi `ListItem`, and records a
`v1:framextv:...` unique ID with title, show, season and episode metadata before
playback starts. It logs only fixed failure text, never a payload or media URL.

If the add-on is absent or disabled, a pre-dispatch Kodi error may fall back to
the existing `Player.Open` path. Ambiguous failures after possible dispatch are
not retried. Legacy playback remains controllable but cannot provide canonical
cross-phone identity.

`kodi.status` reads the active Kodi item both before and after its playback
properties. It exposes `content` only when both reads report the same exact
source and the same strictly valid LUMA unique ID and episode fields. Title or
URL guessing never creates Library identity.

## Joining-phone adoption

While a configured phone is connected, the service polls active Kodi playback
about every four seconds. A phone with no current handoff may adopt a strictly
identified item started by another phone. It binds the exact source and content
key to `WatchHistory`/`LibraryStore`, so subsequent Kodi timestamps update that
phone's Resume position and current episode metadata.

After adoption, the phone hydrates only fixed catalog `/api/details` and
`/api/subtitles` endpoints. It never calls `/api/stream` for this operation and
never replaces the source already playing in Kodi. The refreshed metadata and
track catalog restore the ordinary next-episode lookup, Library details and
subtitle chooser.

The phone commits source and canonical-content transitions together. A new item
receives a new media revision, stale controls/hydration results are rejected,
and a same-URL episode transition cannot write the new timestamp into the
previous episode. Changing source or canonical item also revokes prepared
subtitle bytes and capability URLs and cancels queued work tied to the old item.

Notification taps still use a direct, user-initiated app `PendingIntent`, but
MediaSession/lock-screen Next does not depend on a background Activity launch.
The service re-reads the exact current source and canonical key, resolves the
retained successor, checks the old identity again after Kodi's add-on capability
probe, and opens only that resolved item. It then installs the same prepared
remote metadata and history binding the visible app would have used. The screen
stays locked and no Activity is required.

Kodi does not expose an atomic compare-and-open JSON-RPC operation. The final
pre-write read rejects a change that is already observable, but another
controller could still switch media in the narrow interval between that read
and `Addons.ExecuteAddon` or `Player.Open`. Physical two-phone acceptance must
exercise this residual race; the documentation does not claim it is eliminated.

Each phone independently downloads, normalizes and temporarily serves the
subtitle selected on that phone. Prepared subtitle tickets are RAM-only and
source/receiver scoped; they are not copied between phones. Kodi's observed
subtitle state can be shown after reattachment, but another phone's private
prepared URL is never reused.

Public subtitle downloads are DNS-pinned per redirect hop: all resolved
addresses must be public, and each socket connects directly to one address from
that once-resolved set. A failed endpoint may fall back within the same approved
set without another DNS lookup. HTTPS retains the original Host, SNI and
certificate hostname check. This closes the validation-versus-connection DNS
race without turning the phone into a general proxy.

## Packaging and installation

`make deploy-gh-pages` now prepares the deterministic
`script.lumaremote.metadata-1.0.0.zip` beside the APK and `update.json`, validates
that its source matches the release commit, and includes all three assets in a
new GitHub release. The add-on ZIP has not been published by this feature slice.
Installation instructions are in [`kodi-addon/README.md`](../../kodi-addon/README.md).

## Acceptance boundary

The emulator fixtures cover add-on transport, strict identity, joining-phone
adoption, progress persistence, catalog hydration and transition guards. The
current browser suite also covers the restored remote UI, and an API 36 emulator
covers a locked, Activity-free service-side Next transition. Installing the
add-on on the physical LUMA Kodi, joining from two real phones, long-play
timestamp drift, subtitle selection after adoption, OEM lock-screen behavior and
an actual next-episode transition are still unverified.

Kodi does not currently expose a per-Open session nonce through this adapter. If
Kodi destroys and recreates its player while preserving both the identical
source URL and canonical key, an already observed external subtitle index may be
reused until another discrepancy is visible. Source or canonical-key changes do
revoke all old bindings; this narrower identical-identity recreation remains a
known limitation.

This is synchronization of the currently playing canonical item, not an account
or peer-to-peer history service. Episodes watched only on another phone before
the current Kodi session are not imported retroactively.
