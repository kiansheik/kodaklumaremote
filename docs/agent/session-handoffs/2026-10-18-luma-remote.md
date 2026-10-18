# Handoff — native LUMA browser remote

## Goal

Use the user's new REA checkout to understand the Kodak Luma app and build a
better local control prototype. Investigate direct Kodi launch/startup while
preserving the larger phone-powered-off playback objective.

## Files inspected

Required agent index/current-state/repo-map/open-questions; existing LUMA/mobile/
Kodi notes, README, server/UI/test patterns. REA AGENTS, Android analysis and
connection recovery guides, shipped skill and public CLI launcher. Archived
LUMA APK manifest/class inventory and selected transport, command, app parser,
keyboard callback and discovery methods. Actual Chrome remote and receiver API.

## Files changed

- `scripts/luma-protocol.mjs`: persistent bounded serialized raw TCP, feature/
  pairing gate, allowlisted keys, text, binary app inventory and verified launch.
- `scripts/luma-discovery.mjs`: bounded UDP request, private interface/netmask
  selection, source-IP reply validation and versioned firmware-prefix support.
- `scripts/luma-controller.mjs`: loopback-only HTTP UI/API, Host/Origin/JSON/body
  boundaries, private numeric target, fixed errors and lost-connection state.
- `src/luma/remote.html`: responsive D-pad, keyboard, Kodi/app launch, bounded
  discovery/direct IP, text draft, explicit volume refresh and reconnect handling.
- `tests/luma-{protocol,discovery,controller}.test.mjs` and
  `tests/browser/luma.spec.js`: transport, parser, timeout, authorization/state,
  interface cleanup and real browser fixtures.
- `package.json`: `npm run luma`. README and agent protocol/current-state/log/
  repo-map/decisions/progress/open-questions/index/handoff notes.

## Commands run

- Read-only memory registry search returned no relevant entry.
- REA6.7.0 official npm package CLI with existing JDK21 and pinned JADX0.7.1:
  `inspect-android-package`, `search-android-classes`, `inspect-android-class`,
  selected `inspect-android-method` operations. Complete Evidence stays ignored
  under `.local/luma-analysis/`. Checkout did not require building/config changes.
- SHA-256 verified downloaded JAR; archived APK hash matched mirror publication.
  Exact artifact identity and acquisition limits are in [protocol notes](../luma-remote.md).
- `npm test`: 89/89 passed before one final reconnect HTTP regression; final
  focused HTTP5/5, protocol16/16 and discovery9/9 passed. Seven LUMA browser cases
  passed after final UI fixes. Subsequent complete run is recorded below if rerun.
- Final shared tree:109/109 Node and54/54 browser checks passed, including the
  Android coordination/mobile fixtures documented in the
  [subsequent phone handoff](2026-10-18-phone-handoff.md).
- `npm run luma`: current loopback4176 server. CUA Chrome used for physical
  discover/connect/apps/launch/key acceptance. Bounded network probes used only
  known target services or the recovered discovery protocol, no port/subnet scan.
- Final whitespace/link/sensitive-value checks and local commit; existing
  untracked empty `headers.txt` preserved. APK/evidence/screenshots remain ignored.

## What worked

Actual LUMA replied with feature model/application identity and no configured
pairing password. Recovered app-list framing exactly matched90757 received bytes
and23 installed apps, including Kodi. Browser Open Kodi was user-confirmed;
Kodi HTTP was refused before launch and reported21.2 afterward. User also
confirmed Right then Left moved Kodi's selection. Receiver volume read6/30.

Discovery first failed because firmware says `ECloudBox516`, while the initial
parser expected exact `ECloudBox`. REA confirmed original prefix matching. After
the fix, the normal per-interface browser search found the actual receiver in
its bounded2.5-second window. Direct IP avoids discovery when the address is
already known. No claim that boot-time networking/service readiness is fixed.

The actual app uses raw TCP/UDP, so an ordinary website needs a bridge. Current
bridge is only on Mac loopback; protocol/HTML can later be reused in a thin
Android shell or a projector-hosted helper. No phone filesystem server or
storage-root auth advertisement was required for verified controls.

## What failed

Initial network checks timed out while off, then control was refused during
startup. Optional `getServerInfo` never replied on this firmware even though
features/pairing/apps worked; removed that mandatory compatibility assumption.
No negative pairing timeout fallback is used; unknown/password cases fail closed.

Review exposed stale Connected state after socket loss, a volume readout with no
producer, and Enter overriding focused button activation. Fixed, with targeted
tests. Browser fixtures do not establish physical volume changes/text display.

## Remaining questions

Exact LUMA model/Android, cold-boot startup timing, explicit PIN pairing, actual
volume changes/text rendering, touchpad, reverse-screen rendering and broader
projector settings remain unverified. Startup/default launcher was not changed.
Kodi itself is not an Android HOME launcher; a boot helper/custom launcher needs
device compatibility, installation and actual reboot acceptance. The subsequent
[phone increment](2026-10-18-phone-handoff.md) now builds an Android sender; its
Samsung/full phone-off acceptance remains separate from Mac-controlled playback.

## Suggested next prompt

“Continue from the physically verified LUMA browser remote. Package the UI and
native socket adapter for my Samsung phone or a projector helper, verify text/
volume, and identify the exact Android/model before adding reversible Kodi boot
launch. Preserve ready-device discovery and test cold boot separately.”
