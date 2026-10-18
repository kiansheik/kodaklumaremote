# LUMA Remote

An independent Android remote for compatible Kodak LUMA projectors and Kodi.
Control playback, projector volume, navigation and subtitles from the phone,
notification shade or lock screen. Kodi retrieves media directly.

**[Install for Android](https://kiansheik.github.io/kodaklumaremote/)** ·
[Releases](https://github.com/kiansheik/kodaklumaremote/releases) ·
[Android setup](android/README.md)

The app offers optional updates when opened, with a manual check in Settings.
Downloads are verified and Android asks before installing. Projector setup and
the encrypted Kodi login remain on the phone. This is an early release; Kodi
startup and subtitle failures remain under investigation.

For maintainers: [signing and publishing a release](docs/releasing.md).
The original Cast extension and Mac proof-of-concept tools remain below.

## Original frameXTV Cast experiment

A minimal Manifest V3 extension that uses frameXTV's **existing** Google Cast
SDK to send an exposed HLS, DASH, or MP4/WebM URL to a receiver. The receiver
retrieves the media; the browser supplies remote controls. Actual frameXTV
playback on a physical Chromecast/Google TV remains **unverified**.

The site already contains native Cast code. This extension preserves its visible
Cast control and adds a small fallback beside the video only when that control
is absent. It supports the target origin's `/watch/` and `/embed/` pages,
including matching same-origin frames. Cross-origin backup players are outside
its permission scope.

## Install the experimental Chrome extension locally

Use Node.js with npm, then run:

```sh
npm ci
npm run build
```

Open `chrome://extensions` in Chrome, enable **Developer mode**, choose
**Load unpacked**, and select this repository's **`dist/extension`** directory.
Reload the target page after loading or rebuilding the extension:

```text
https://framextv.xyz/watch/284833/1/1
```

Use the site's native Cast control if it appears. Otherwise, the extension's
Cast button opens the SDK's device picker. Local playback pauses after a
successful receiver LOAD. The fallback supplies pause/resume, seek, volume,
VTT subtitle selection, and disconnect. Returning to local playback leaves the
video paused so the user chooses when to resume.

Load `dist/extension`, not `src/extension` or `dist/test-extension`. The latter
is a separate generated build restricted to local automated fixtures.

## Checks and device testing

```sh
npx playwright install chromium
npm run check
```

Unit tests and actual loaded-MV3 browser fixtures exercise extraction, dynamic
players, same-origin embeds, navigation, Cast wiring, errors, and cleanup. Cast
itself is mocked in those fixtures; they do not establish receiver playback.

For an ordinary web sender using the same Cast adapter and an openly licensed
public MP4, run:

```sh
npm run build
npm run demo
```

Open `https://localhost:4174/cast-demo` in Chrome and accept the warning for this
generated local development certificate. This is a separate demo page, not an
extension context; only that page loads Google's remote sender SDK. SDK readiness
and receiver success must be observed in the browser and on the TV. Follow the
[physical-device checklist](tests/manual-chromecast.md) and record the result.

`npm run smoke:cast` checks live SDK readiness in an isolated installed Chrome
profile. It passed over local HTTPS with zero media requests; no device was
selected. The public sample's HEAD returned 200, `video/mp4`, byte ranges and
CORS `*`. Neither check establishes receiver playback.

## Boundaries

- All extension executable code is packaged locally. It has no SDK loader,
  telemetry, cookie access, broad host permissions, or media relay.
- Missing/not-ready SDK, unsupported sources, encrypted playback, and failed
  device loads produce an error without pausing ordinary local playback.
- A browser `blob:` URL is not sent to the receiver. A declared child
  `<source>` may expose the underlying authorized HTTP(S) manifest instead.
- Source access, CORS, expiry, codecs, subtitles, and receiver reachability remain
  provider/device constraints. Finding a media URL does not prove compatibility.
- Target automation may be blanked or disrupted by anti-debugging. No
  authentication, DRM, or anti-debugging bypass is included.
- Chrome's menu **Cast → Sources → Cast tab** is a manual mirroring fallback.
  It is browser-dependent and is not this extension's direct-playback mode.

See the [handoff](docs/agent/handoff.md), [investigation](docs/agent/investigation.md),
[playback findings](docs/agent/playback-analysis.md), and
[Cast feasibility](docs/agent/chromecast-feasibility.md) for evidence and remaining
questions. Keep live source URLs, cookies, credentials, and media out of Git.

Live Philips43PFG6910/78 testing found YouTube-specific playback working, while
Chrome disables the TV for frameXTV with “Available for specific video sites”.
The intended final mobile solution must keep playing after the phone is fully
off; a Miracast-only mirror cannot meet that requirement. See
[receiver evidence](docs/agent/philips-tv.md) and [mobile target](docs/agent/mobile-target.md).

## Kodi on an Android projector

The user's LUMA running Kodi 21.2 played both the public Big Buck Bunny MP4 and
an actual frameXTV HLS episode with user-confirmed picture and sound. All 19
declared subtitle tracks were registered for that episode; Portuguese (Brazil)
was selected and the user confirmed visible subtitles matching the dialogue.
Kodi keeps multiple tracks available and displays one at a time.

```sh
npm run kodi
```

Open **http://127.0.0.1:4175** in Chrome. On the projector, enable Kodi's
**Settings → Services → Control → Allow remote control via HTTP**, set its
username/password, and keep Kodi open on the same home network. Enter those
details in the local controller, connect, and choose **Play Big Buck Bunny**.
Use **Refresh status** after a playback command; Kodi starts/seeks asynchronously.
Closing the page does not stop the video; use **Stop** explicitly.

For frameXTV, use the extension's **Copy video link**, paste into the controller's
**Video link**, choose the reported format, and select **Play video link**.
The successful episode used HLS. **Refresh subtitles** lists Kodi's tracks;
choose a track or **Off**, then **Apply subtitle**. Portuguese appears first,
followed by English. An **External subtitle link** can add one track at a time.
The extension's **Copy subtitle list** copies all exposed tracks as JSON; the
controller has no batch-import UI yet. The 19-track test used the local API,
described in [Kodi testing and discovery](tests/manual-kodi.md).

This Mac-only controller sends authenticated JSON-RPC commands to one private
IPv4 address. Credentials and pasted links are not persisted; the Mac does not
fetch or relay video. The source continued playing with the controller process
stopped. A rapid pause/seek/resume sequence stopped the HLS episode; a restart
without seeking remained stable, so HLS seeking remains unresolved. This is a
working desktop stepping stone. An Android sender is now implemented below;
actual phone playback and full phone-off acceptance are still pending.

## LUMA projector remote

A separate remote prototype implements the Kodak LUMA app’s local control
protocol: navigation, volume, text entry, app selection and **Open Kodi**.
It has a responsive browser interface and a small Node.js bridge; REA is only
needed for the investigation, not to run the remote.

```sh
npm run luma
```

Open **http://127.0.0.1:4176** on this Mac. Turn on the projector, use the same
Wi-Fi network, and select **Find projector** or enter its IP address. A search
finishes after about 2.5 seconds; choose **Connect** when a result appears.
Once connected, **Refresh apps** retrieves the app list. **Open Kodi** checks
that Kodi is present before sending its launch command. Select a text field on
the projector before sending a short line of text.

The real projector returned 23 installed apps. **Open Kodi** launched Kodi 21.2,
and the user confirmed both Kodi opening and navigation through this remote.
Volume and typing are implemented from the APK protocol but not physically
confirmed yet. Initial boot/discovery compatibility findings and test boundaries
are recorded in the linked protocol notes.
The remote does not change startup behavior. Launching Kodi at boot still
requires the projector’s exact Android/model details and a separate device test.

The bridge listens only on this Mac’s loopback address, accepts one private IPv4
receiver and keeps its connection in memory. An ordinary website cannot open the
raw TCP connection this protocol uses. See
[LUMA protocol and testing](docs/agent/luma-remote.md) for evidence and remaining
device checks.

## Android phone app

The phone prototype packages a separate no-scroll web remote with a native
network bridge. It connects directly to LUMA and Kodi without the Mac.
**Browse → Cast to LUMA** launches Kodi, waits for readiness, opens the exposed
video and loads Brazilian Portuguese with English as the fallback. All website
languages remain on the phone for loading on demand. It then opens the combined **Remote** screen.
Playback, volume and navigation stay visible; languages, typing and apps have
separate panels. The projector fetches the video and subtitles itself.

First setup opens/authenticates Kodi and saves its login encrypted on the phone.
Later opens connect automatically using the cached projector address. The
foreground connection survives switching apps and provides notification/system
media controls. The phone UI keeps setup behind Settings, shows compact status,
has corner Home/Back and a volume slider with step buttons. Disconnect leaves
the video playing; saved setup can be wiped independently.
After a process restart it can attach to Kodi's continuing video from fresh
status without restarting playback. Expand the ordinary LUMA notification for
Volume − / Play-Pause / Volume +; a separate media card serves system controls.
The main Remote's Power icon requires confirmation before sending the Kodak
power command, then disconnects while retaining saved setup.

Build/setup: [Android README](android/README.md). Implementation, fixture results
and physical acceptance: [phone app](docs/agent/phone-app.md). Source capture
currently supports HTTPS frameXTV and readable same-origin players. This is an
installable debug prototype. The user confirmed Samsung casting with picture,
sound and Portuguese subtitles, plus working notification/lock-screen controls.
Later Kodi cold-start crashes/missing subtitles, interrupted-network recovery
and phone fully off still need physical acceptance.

The [review analysis](docs/agent/luma-review-findings.md) records a bounded sample
of 220 public written Google Play reviews. Repeated specific complaints informed
bounded discovery, explicit retry/manual connection, preserved text drafts and
input gating. Review frequency does not establish which controls people use most.
