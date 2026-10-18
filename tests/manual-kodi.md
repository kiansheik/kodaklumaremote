# Kodi receiver acceptance

## Setup and reproduction

1. Install/open Kodi on the projector, on the same home router as the controller.
2. Enable **Settings → Services → Control → Allow remote control via HTTP**.
   Set a username/password and note the HTTP port (usually 8080). The separate
   TCP/WebSocket application-control options are unnecessary for this test.
3. Run `npm run kodi`; open `http://127.0.0.1:4175` in Chrome. Enter the private
   IPv4 address, HTTP port and credentials. This tool does not save them.
4. Connect, then **Play Big Buck Bunny**. Confirm physical picture and sound.
   Accepted `Player.Open` is not a claim of playback; **Refresh status** shows
   whether a video is active and its receiver-reported position.
5. For the public sample, pause, resume, seek to an absolute position, and refresh
   after each command settles. Kodi command processing is asynchronous. Stop only
   the test video. HLS seeking is not yet accepted; see the observed failure below.
6. For independence, leave playback running and close the controller/stop its
   process. Reconnect later and check advancing position without restarting it.
7. Reload the built extension on the ordinary frameXTV page. Use **Copy video
   link**, paste into **Video link**, choose its format, and **Play video link**.
   Confirm the intended episode's picture and sound on the projector. This copies
   a page-declared source, without browser cookies or extra request headers.
8. Use **Refresh subtitles**, select a track or **Off**, and **Apply subtitle**.
   **External subtitle link → Load subtitle** adds one remote track; refresh
   afterward because adding is asynchronous. Confirm actual captions during
   dialogue, not only the track list or successful command response.
9. Final mobile acceptance still requires an actual phone sender, phone fully
   powered off, independent projector networking and continued media retrieval.
   Source expiry and reconnect still need separate tests.

The fixed sample URL is in `scripts/kodi-controller.mjs`, shared with the Cast
demo's current Google-hosted Big Buck Bunny source. No media is hosted by this
controller. Direct links support MP4, WebM, HLS and DASH, subject to receiver
compatibility. `KODI_CONTROLLER_PORT` changes only the Mac's loopback UI port.

## Multiple external subtitle tracks

The extension's **Copy subtitle list** copies JSON of the form
`{"tracks":[...]}`, containing all validated declared subtitle descriptors, even
when the site's own custom subtitle renderer leaves HTML text tracks hidden.
There is no batch-import UI in the Mac controller yet. The live 19-track test used
a temporary local script; no active source or subtitle URLs belong in Git.

To reproduce through the controller API, send JSON `POST /api/kodi` to its
loopback origin with `Content-Type: application/json` and the matching `Origin`.
Every request includes `receiver: {host, port, username, password}` supplied only
at runtime. Use these action payloads:

| Action | Additional fields | Meaning |
| --- | --- | --- |
| `play-source` | `media: {url, contentType}` | Ask Kodi to open the authorized source; inspect `status` afterward. |
| `subtitles` | None | Read registered tracks, selected index and enabled state. |
| `add-subtitle` | `subtitleUrl` | Register one direct remote subtitle URL; acceptance alone does not prove download or display. |
| `set-subtitle` | `subtitle: <reported index>` or `"off"` | Select/enable one registered track, or disable subtitles. |
| `label-subtitles` | `mediaKey`, `tracks: [{index, name, language}]` | Give the Mac UI human-readable aliases for the current video's track indices. |

For each copied descriptor, call `add-subtitle`, then poll `subtitles` until its
new index appears before adding the next one. Preserve that reported index in a
label map; do not assume copied order equals Kodi order. Finally select the
Portuguese (Brazil) index. Only one subtitle track displays at a time, while the
others remain selectable. The Mac selector lists Portuguese first, English next,
then the remaining languages; refresh preserves Kodi's current selection.

Kodi reported generic external/proxy names in this session. For aliases,
`mediaKey` is the lowercase SHA-256 hex digest of the exact source URL Kodi
reports as its current `Player.GetItem` file. The controller verifies that match
before accepting labels, and scopes them to receiver and video. Aliases live
only in controller memory until restart and do not rename tracks in Kodi's native
TV menu. Starting another video or stopping clears that receiver's aliases.
See [Kodi's documented Player.AddSubtitle](https://kodi.wiki/view/JSON-RPC_API/v13.5#Player.AddSubtitle).

## Read-only automatic discovery on macOS

Kodi publishes `_xbmc-jsonrpc-h._tcp` when its HTTP web server and Zeroconf are
available. This was confirmed in [Kodi 21.3 source](https://github.com/xbmc/xbmc/blob/21.3-Omega/xbmc/network/NetworkServices.cpp).
Use bounded queries; stop `dns-sd` with Ctrl-C after a few seconds:

```sh
dns-sd -B _xbmc-jsonrpc-h._tcp local
dns-sd -L '<advertised Kodi instance>' _xbmc-jsonrpc-h._tcp local
dns-sd -G v4 '<resolved host>.local.'
```

Discovery does not validate identity or credentials. Only connect to the user's
intended receiver. No general port scan is needed. If Kodi is not advertised,
its **Settings → System information → Network** screen has the IP address.

## Observed 2026-10-18, America/Sao_Paulo

| Check | Result |
| --- | --- |
| User installation / protocol | User reported Kodi available and configured it. Bonjour found one Kodi HTTP service; resolved port/address matched the user's independently supplied values. |
| Authentication / app identity | Authenticated JSON-RPC connection succeeded; Kodi reported version 21.2. Exact LUMA model/Android version remain unknown. |
| Public media | `Player.Open` accepted the fixed MP4; active video reported 7/596 seconds; user explicitly confirmed picture and sound. |
| Controls | Pause reported `playing:false`; seek requested 90 seconds; resume reported `playing:true`. Immediate status still showed 34 seconds, but later status at 117 seconds confirmed the queued seek and progression. |
| Controller independence | Mac controller stopped at 08:12:01; a fresh read-only process at 08:12:29 reported 117/596 seconds, playing. No Mac media fetch or relay exists in this implementation. This is a bounded Mac-process test, not phone-off acceptance. |
| Actual browser UI | Connection and refresh status showed Kodi 21.2 and Playing 3:30/9:56. Chrome's native password notification blocked initial clicks; native window inspection/dismissal restored controls. No application JavaScript failure was established. |
| Actual frameXTV source | Ordinary Chrome exposed an HLS link for Avatar: Seven Havens S1E1 through **Copy video link**. Kodi reported 22:35 duration; user explicitly confirmed episode picture and sound. The page used the Miner XIX server. |
| HLS seek failure | Rapid pause → seek to 60 seconds → resume was followed by no active video. User confirmed it stopped by itself. Cause remains unknown; public MP4 seeking success does not establish HLS seeking. |
| HLS restart / continuation | Restarted at 08:24:09 without seeking while the Mac controller server was stopped. Position advanced through later checks; at 08:35:28 Kodi remained active and paused at 531/1355 seconds. The implementation has no Mac media relay. This is not the final phone-off test. |
| External subtitle registration | All 19 declared tracks registered in Kodi. Portuguese (Brazil), index 18 in this session, was selected/enabled; English tracks and other languages remained selectable. Human-readable aliases were verified against the current source identity and appeared in the Mac UI. Registration does not prove every language downloaded or rendered. |
| Physical subtitle acceptance | Portuguese initially did not appear. The user later explicitly confirmed that Portuguese subtitles were visible and matching the dialogue. No all-language visual acceptance or stable subtitle behavior after source changes is claimed. |
| Automated checks | Build plus 55 Node and 32 browser tests passed before the final alias tests. Subsequent Node run passed 60/60, and the seven Kodi browser tests passed after final selector sorting. Aggregate browser coverage is 25 extension + 7 Kodi tests, with mocked Cast/receiver transport. |

No Android controller, full phone shutdown, receiver-side token renewal or
reliable target HLS seeking has been accepted. Passwords, actual private
addresses, active links and raw device advertisements are not recorded in Git.
Local screenshots stay ignored under `.local/kodi-controller-playing.png` and
`.local/framextv-kodi-subtitles.png`.
