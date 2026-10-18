# Android receiver app routes

Research checked 2026-10-18. Subsequent user setup and authenticated control
confirmed Kodi 21.2 on the LUMA and public MP4 picture/sound. See
[hardware test](../../tests/manual-kodi.md). Exact model, Android and CPU remain
unknown. Other app compatibility and sideloading were not tested.
The acceptance criterion remains [phone fully powered off](mobile-target.md).

## Documented candidates

| Projector-side app | Incoming control | Evidence and limits |
| --- | --- | --- |
| Kodi | HTTP JSON-RPC `Player.Open`; optional UPnP remote control | Strongest documented integration API. Kodi runs the player and opens the supplied URL itself. Requires compatible Android/CPU, Kodi running, and HTTP control enabled. |
| VLC Android 3.6+ | Authenticated Remote access web UI; implementation-specific HTTP routes | Real Android feature, introduced in 3.6. Released 3.6.5 source accepts a network URI in `/play`. This is separate from desktop VLC's HTTP/Lua interface. Actual LUMA compatibility and latest-version API behavior remain untested. |
| Thin receiver APK using an existing player | Paired LAN command, followed by same-device Android player intent | Feasible architecture, not implemented here. Network listener must run on projector; clicking an intent URI on the phone does not remotely launch the projector's app. |
| Thin receiver APK embedding LibVLC | Paired LAN control plus local decoding | VideoLAN explicitly supports using LibVLC to power another Android player. A custom receiver provides control over pairing, headers, source renewal and UI, at the cost of maintaining that receiver. |

Kodi's Android wiki documents Android 5.0+ for Kodi v17 and later and requires
an appropriate CPU. That wiki is marked as updated for an old release, so check
the exact APK's current minimum SDK and ABI before installing. VLC 3.6.5's
checked release Gradle source sets API 17 (Android 4.2); that historical floor
is not proof that its Remote access feature, TLS or current APK works on LUMA.
Prefer a currently supported official package compatible with the actual device.

## Preferred first experiment: Kodi

1. Establish LUMA model, Android version and CPU ABI in its Settings/About.
2. If compatible, install official Kodi and leave it open. In Settings > Services
   > Control, enable HTTP remote control and require username/password.
3. Send one authorized public MP4 URL to `http://<projector-ip>:<configured-port>/jsonrpc`
   using authenticated HTTP POST with `Content-Type: application/json`:

   ```json
   {"jsonrpc":"2.0","id":1,"method":"Player.Open","params":{"item":{"file":"https://media.example.org/authorized-test.mp4"}}}
   ```

4. Confirm projector picture/sound; turn the controller completely off while
   the projector retains its independent Wi-Fi connection. Observe continued
   playback beyond any initial buffer. Repeat with authorized HLS and tracks.
5. Add phone control only after receiver retrieval passes. The Mac can issue
   the same JSON-RPC during the first experiment.

The media URL above is a placeholder, not a working fixture. Authenticated HTTP
control alone suffices; Kodi's separate unauthenticated TCP/WebSocket remote
settings are not required for this experiment. Keep control on the local network.
UPnP is an alternative using Kodi's “Allow remote control via UPnP” setting;
receiver discovery/control and codecs still need a physical test.

## VLC-specific evidence

VideoLAN documents enabling Remote access in VLC, opening the displayed remote
URL in another device's browser, accepting the device certificate and pairing
using a one-time password. This is the safest first UI experiment with VLC.

Verified source: VLC Android tag `3.6.5`,
`application/remote-access-server/src/main/java/org/videolan/vlc/remoteaccessserver/RemoteAccessRouting.kt`:

- line 468 wraps player routes in `authenticate("user_session", ...)`;
- lines 1008–1020 accept `/play`, an `id`, and a `path`; `id <= 0` constructs
  a media wrapper directly from `Uri.parse(path)`;
- lines 1044–1050 check foreground-video behavior and call `MediaUtils.openList`.

Therefore `/play?id=0&path=<URL-encoded-authorized-media-URL>` is a source-backed
candidate for an already-paired client on that release. It is not a documented
stable public SDK, a tested LUMA request, or evidence that desktop endpoints like
`/requests/status.json` work on Android. Test the current compatible release
through its UI before implementing an adapter. Receiver playback may require
VLC to remain in the foreground; Android background-launch restrictions matter.

The controller must hand off an independently accessible source. A phone-hosted
relay, phone hotspot, cookies available only on the phone, short-lived URL or
phone-dependent token renewal can still fail the phone-off test with any app.

## Primary sources

- [Kodi JSON-RPC transports](https://kodi.wiki/view/JSON-RPC_API)
- [Kodi v21 JSON-RPC schema / Player.Open](https://kodi.wiki/view/JSON-RPC_API/v13.5#Player.Open)
- [Kodi HTTP control settings](https://kodi.wiki/view/Settings/Services/Control)
- [Kodi UPnP settings](https://kodi.wiki/view/Settings/Services/UPnP_DLNA)
- [Kodi Android requirements and installation](https://kodi.wiki/view/Android)
- [VLC Android Remote access documentation](https://docs.videolan.me/vlc-user/android/3.X/en/more/remoteaccess/remote_access.html)
- [VLC 3.6.5 remote routing source](https://github.com/videolan/vlc-android/blob/3.6.5/application/remote-access-server/src/main/java/org/videolan/vlc/remoteaccessserver/RemoteAccessRouting.kt#L1008)
- [VLC 3.6.5 SDK floor](https://github.com/videolan/vlc-android/blob/3.6.5/build.gradle#L52)
- [VideoLAN Android downloads](https://www.videolan.org/vlc/download-android.html)
- [VideoLAN Android / LibVLC integration and licenses](https://github.com/videolan/vlc-android#libvlc)

Initial research was source/document review only. Subsequent implementation:
`npm run kodi` runs a Mac localhost controller for the fixed public MP4;
authenticated connection, playback, controls and bounded continuation after the
controller stopped were checked on Kodi 21.2. No intended-source extraction or
phone app/full phone-off test occurred. See [manual evidence](../../tests/manual-kodi.md).
