# LUMA remote protocol and prototype

2026-10-18: The Kodak LUMA Android client exposes a local EShare-style remote
protocol. A loopback Node.js bridge and responsive browser remote now implement
its bounded control subset. Seven mocked-browser cases pass. The actual receiver
returned 23 apps; the user confirmed Kodi launch and Right/Left navigation from
this browser remote. Kodi's HTTP service was absent before launch and identified
as 21.2 afterward. Volume and typing remain physically unverified.

## Run and check

```sh
npm ci
npm run luma
```

Open `http://127.0.0.1:4176` on the Mac. Use **Find projector** or enter the
projector’s private IPv4 address, then **Connect**. Discovery runs for about
2.5 seconds and fills one result or offers a selector for multiple devices;
it does not connect automatically. If the device is booting, retry after it is
ready or enter its address directly.

The remote provides a D-pad and keyboard navigation, Back/Home/Menu, volume,
**Open Kodi**, **Refresh apps**, app launch and text entry. App launches require
the package to appear in the current receiver inventory. Text is a single line,
at most 1,000 characters (4 KiB UTF-8); select the target field on the projector first.
Command feedback reports that a command was sent, not that the projector visibly
completed it. Connection, address, app metadata and drafts are memory-only.
REA and the archived APK are not runtime dependencies.

The user reports the official app can take 5–10 minutes to discover the device.
The previous address initially timed out while the projector was off, then
refused port 8121 during startup, and eventually accepted the recovered protocol.
Features identified `Smart Projector / Kodak Luma`; pairing returned `configed
none`. This firmware does not answer optional `getServerInfo`, so identity is
derived from the feature reply instead. No file-sharing server or `auth` storage
advertisement was required for observed app inventory, launch or key controls.

The physical discovery reply starts `ECloudBox516`, adding a firmware version
suffix to the prefix checked by the APK. The initial exact-match parser discarded
it. After correcting that compatibility issue, normal **Find projector** in
Chrome found the real receiver during its 2.5-second window. This measures a
ready service; it does not prove that cold-boot service startup is faster.

## Artifact and analysis provenance

The [Google Play listing](https://play.google.com/store/apps/details?id=com.caglobal.kodakluma)
identifies package `com.caglobal.kodakluma`. The analyzed archived APK is
version **1.0.116**, version code **7**, obtained through
[APKPure’s version page](https://apkpure.net/kodak-luma/com.caglobal.kodakluma/download/v1.0.116).
Matching mirror downloads establish the analyzed artifact’s identity, not an
official publisher signature. Its SHA-256 is:

```text
4c0e44afa4d1e7a73066d3f3fa8be2b2d847f3c556fff113d7df51163b063274
```

Analysis used REA **6.7.0**, matching the user’s `~/code/rea`, and its official
package with pinned JADX **0.7.1** under the existing JDK 21. The JADX artifact
SHA-256 was verified as:

```text
6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75
```

Only static APK/package/method inspection was performed. The APK was not
executed or installed, and MCP configuration was not changed. Original analysis
envelopes, APK and engine remain in ignored `.local/luma-analysis/`; no archived
APK, raw session data or receiver address is committed. Static method bodies
describe this client version, not guaranteed behavior on every LUMA firmware.

## Observed client protocol

| Purpose | APK observation and implemented boundary |
| --- | --- |
| Discovery | UDP 48689. A 50-byte binary request writes the big-endian value 13 at byte offset 12 and `FindECloudBox` at offset 20. |
| Discovery reply | Text begins `ECloudBox` with optional numeric version suffix, then `:name:port:…`; receiver address comes from the datagram source. The prototype accepts private source addresses on the selected local subnet and control port 8121. |
| Control connection | Raw TCP, default port 8121. Feature and pairing-config requests precede controls; optional unsupported device-info query is omitted. |
| Keys | `KEYEVENT` with an allowlisted Android key code. |
| Power (phone app) | Original `dialog_power_off` confirmation uses dialog case101 → MainActivity.d(26) → worker → EShare key serializer. The phone implements this as dedicated confirmed `luma.powerOff`, not an ordinary power key. KEYEVENT26 send does not prove shutdown rather than standby. |
| Text | `CONTENT`, mode `1`, payload prefixed `0 `. CR/LF/NUL and oversized UTF-8 payloads are rejected. |
| App inventory | `GETAPPINFOSEVNET`; the spelling is present in the APK. Reply uses little-endian total/field lengths. Names and package identifiers are validated; icon bytes are never served. |
| App launch | `STARTAPPEVNET` with a package from the current verified inventory. The spelling is present in the APK. |

This protocol is separate from Kodi’s HTTP JSON-RPC. Kodak’s
[LUMA 350 support page](https://kodakprojectors.caglobal.com/pages/luma-350-portable-smart-projector-support)
provides manufacturer context; the user’s exact projector model remains unknown.

## Implementation boundaries

- [Transport](../../scripts/luma-protocol.mjs) bounds connect/reply timeouts,
  frame sizes and queued commands. It requires a nonempty printable pairing
  response; missing/unknown pairing state fails before sending controls.
  Password/PIN pairing is not implemented. No password guessing or fallback
  approval is used.
- [Discovery](../../scripts/luma-discovery.mjs) sends a small fixed number of
  UDP requests on eligible local interfaces and ends after 2.5 seconds by
  default. It does not scan hosts or ports. Discovery identifies a compatible
  service, not an authenticated receiver.
- [HTTP bridge](../../scripts/luma-controller.mjs) binds `127.0.0.1:4176`, checks
  the exact request Host/Origin and accepts a bounded JSON action allowlist.
  It has no CORS permission, arbitrary command endpoint, file sharing,
  authentication advertisement or saved credentials. Only private numeric IPv4
  targets are accepted.
- [Remote UI](../../src/luma/remote.html) gates busy controls, drops excess
  keyboard repeats rather than accumulating them, and renders receiver labels
  as text. Browser tests cover connection/storage boundaries, explicit commands,
  safe labels/errors, busy handling and discovery results. Lost connections
  clear stale controls while preserving a text draft; focused native buttons
  keep Enter activation. **Refresh volume** explicitly reads the receiver's
  scale (actual observed value6/30), without hidden polling.

An ordinary website cannot open the required raw TCP/UDP sockets. Chrome’s
[Direct Sockets API](https://developer.chrome.com/docs/iwa/direct-sockets)
is restricted to Isolated Web Apps rather than general browser pages. The
Node bridge is required for the desktop remote. The implemented
[Android shell](phone-app.md) packages a separate no-scroll mobile UI and native
socket adapter, so the Mac is unnecessary for the phone prototype. Actual
Samsung capture/playback acceptance remains pending.

## Kodi at startup

**Open Kodi** is a launch request, not boot configuration. Kodi is not a HOME
launcher. A boot helper or custom launcher
would need the projector’s exact Android version/model, installation support,
an explicit device choice and actual boot testing. No startup setting, launcher
or APK on the projector has been changed by this remote work. Existing Kodi
playback/subtitle acceptance is recorded in [the Kodi test](../../tests/manual-kodi.md).

Next device acceptance: test volume and typing on the projector, reconnect and
cold-boot discovery timing. Keep the existing media-playback proof separate
from this new remote-control acceptance. Phone hardware acceptance, reverse
screen view, touchpad and startup behavior remain unfinished.
