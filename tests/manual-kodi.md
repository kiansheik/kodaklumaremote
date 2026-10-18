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
5. Pause, resume, seek to an absolute position, and refresh after the command
   settles. Kodi command processing is asynchronous. Stop only the test video.
6. For independence, leave playback running and close the controller/stop its
   process. Reconnect later and check advancing position without restarting it.
7. Final mobile acceptance still requires an actual phone sender, phone fully
   powered off, independent projector networking and continued media retrieval.
   Then separately test intended authorized HLS, tracks and URL expiry.

The fixed sample URL is in `scripts/kodi-controller.mjs`, shared with the Cast
demo's current Google-hosted Big Buck Bunny source. No media is hosted by this
controller. `KODI_CONTROLLER_PORT` changes only the Mac's loopback UI port.

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
| Automated checks | 44 Node tests passed, including 11 Kodi tests. Three new actual-browser Kodi tests passed with mocked transport. Prior 16 MV3 tests were not rerun for this separate tool. |

No target-source playback, subtitles, mobile app, full phone shutdown or
receiver-side token renewal was tested. Passwords, actual private addresses and
raw device advertisements are not recorded in Git. The local screenshot stays
ignored under `.local/kodi-controller-playing.png`.
