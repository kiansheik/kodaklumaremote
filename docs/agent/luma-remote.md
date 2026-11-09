# LUMA remote protocol and prototype

2026-11-05: The Kodak LUMA Android client exposes a local EShare-style remote
protocol. A loopback Node.js bridge, laptop remote and packaged phone remote
implement its bounded control subset. Installed receiver 0.3.17 retained the 28
launchable apps including Crunchyroll and renders the same complete, icon-backed
catalog as its Home screen. Earlier physical checks confirmed Kodi launch and
Right/Left navigation. The user physically accepted calibrated Air Mouse aiming;
the more-sensitive preset and Cat Mode still need user-visible acceptance.

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
| App inventory | `GETAPPINFOSEVNET`; the spelling is present in the APK. Reply uses little-endian total/field lengths. Names, package identifiers and bounded icons are validated; receiver 0.3.14 also exposes its complete Android launchable-app catalog through the authenticated API. |
| App launch | `STARTAPPEVNET` with a package from the current verified inventory. The spelling is present in the APK. |
| Pointer bounds | `GETFBINFO\r\n1\r\n\r\n`; the first two reply fields are width and height. The phone validates them and uses 1280×720 only as the archived client's fallback. |
| Air mouse | `GYROSCOPEEVENT\r\n{x}\r\n{y}\r\n{action}\r\n\r\n`; `-1` is hover, `0` down, `2` pressed move and `1` up. A click is down then up at the current coordinate. |
| Cat Mode | Paired receiver HTTP methods `cat.start/status/pointer/pounce/release/stop`; pointer coordinates are strict integers `0..1000` and the receiver owns the manual lease/session limits. |

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

## Projector Home, air mouse and Cat Mode

Receiver 0.3.17 is installed as a reversible Android Home candidate and its Home
screen shows all enabled launchable packages with logos. This does not silently
select itself as Android's default Home; projector settings and a cold power
cycle remain authoritative acceptance. **Open Kodi** and other tiles are app
launch requests, not boot configuration. Existing Kodi playback/subtitle
acceptance is recorded in [the Kodi test](../../tests/manual-kodi.md).

Installed phone 0.4.34 air mouse uses the game rotation vector (rotation-vector
fallback), stable-pose calibration, dead-zone/hysteresis and adaptive smoothing.
It sends bounded absolute pointer coordinates; it does not integrate
accelerometer position. The user accepted the grip-independent calibration;
the default High preset now needs about 23–25% less rotation than Normal. Air
mode remains explicit, foreground-only and stopped by pause, Browse,
target/configuration changes or disconnect.

The paired receiver's authenticated `cat.*` API owns a 12-minute Cat Mode
session. Its Canvas view uses high-contrast cyan/yellow-green prey shapes,
nonperiodic darts/pauses/hides and generated chirps without bundled audio. After
the initial sound proved completely absent, not merely quiet. Installed 0.3.17
uses a checked 48 kHz streaming track plus a system-tone fallback and exposes
bounded audio-path diagnostics. Live state reported `pcm-stream` with every
written sample represented in the playback-head count; audibility remains user
acceptance.
It runs autonomously until the phone's filtered Air Mouse samples take a
1.5-second manual lease; OK pounces and Air off releases the lease. Live API
acceptance passed start → pointer → pounce → release → stop and left the mode
stopped. Visual/audio/cat-response acceptance is still pending.
