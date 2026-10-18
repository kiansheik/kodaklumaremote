# Mobile target: receiver playback after phone power-off

User clarification, 2026-10-18: the full solution is a Samsung/Android phone
starting authorized media on a receiver, then switching fully off while the
receiver continues retrieving it. Mac and desktop MV3 are stepping stones.
Do not silently substitute mirroring or a phone-hosted relay.

## Capability gate

| Receiver path | Fits phone-off retrieval? | Evidence / limits |
| --- | --- | --- |
| Google Cast + compatible media receiver | Yes in principle | Receiver loads its own URL. Actual content access/codec/expiry and phone-off acceptance must pass. |
| UPnP/DLNA MediaRenderer | Potentially | Renderer HTTP GET follows SetAVTransportURI/Play independently of control point. TV must expose those services and accept media. Philips description received, but no recognized AVTransport was advertised. |
| Miracast-only display | No by itself | Source sends an ongoing encoded display stream. Needs another source/player left running; switching phone off stops its stream. |
| YouTube's TV receiver | YouTube playback verified; phone-off not tested | User confirmed public film picture and sound. Does not accept arbitrary frameXTV URLs through our Cast adapter. |
| Kodi 21.2 on LUMA | Public MP4 receiver playback confirmed; phone-off pending | User confirmed picture/sound; controls and progression after Mac controller stopped checked. Phone sender/intended media/model/Android remain unverified. |

Primary sources: [Google Cast overview](https://developers.google.com/cast/docs/overview),
[receiver lifetime](https://developers.google.com/cast/docs/web_receiver/basic),
[UPnP MediaRenderer architecture](https://upnp.org/specs/av/UPnP-av-MediaRenderer-v3-Device.pdf),
[AVTransport HTTP retrieval](https://upnp.org/specs/av/UPnP-av-AVTransport-v3-Service-20101231.pdf),
[Miracast transport](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-mice/940d808c-97f8-418e-a8a9-c471dc0d21bb).

## Intended implementation sequence

1. Establish receiver capability and a public-media direct-playback proof before
   building a mobile shell. Existing Philips results are [here](philips-tv.md).
   LUMA Kodi 21.2 public-media playback now passes the initial receiver gate;
   see [hardware evidence](../../tests/manual-kodi.md). Integrate a phone sender
   and the intended authorized source next. For other receivers, test DLNA/Kodi/VLC;
   see [LUMA options](luma-receiver-options.md) and [interfaces](receiver-apps.md).
2. For actual Cast receivers, use the official Android Sender SDK for device
   selection, media LOAD and remote controls. Keep source discovery independent
   from receiver/session code so the extraction rules can be reused.
3. Add an Android UPnP control path only if receiver services are observed and a
   public MP4 retrieval test succeeds. Standard multicast discovery and SOAP
   control need native networking, not a desktop-only MV3 control surface.
4. Capture only sources exposed by the provider's ordinary authorized playback
   flow. Never copy browser credentials or claim an inaccessible URL is portable.
   A WebView/browser integration strategy requires separate mobile-site testing.
5. Reject direct mode clearly when receiver/media capabilities do not support it.
   Any optional mirroring is a distinct mode and does not satisfy this goal.

The [Android CAF SDK](https://developers.google.com/cast/docs/android_sender)
is the natural Cast controller for the user's Android phone. No Android app/APK
has been implemented or installed in this increment. A Chrome Native Messaging
helper on Mac would still need a real supported transport; it does not turn
Miracast into independent media retrieval.

`npm run kodi` is a Mac-only stepping stone: localhost UI → authenticated Kodi
HTTP commands → receiver fetches fixed public MP4. It neither integrates with
the MV3 extension nor substitutes for the future phone controller. Kodi uses a
different transport from Google Cast; both can be adapters behind source selection.

## Required acceptance

- Test public media, then the intended authorized source; confirm TV picture and
  sound and remote pause/seek/volume before sender shutdown.
- Start from phone on ordinary Wi-Fi. TV Internet must not depend on that phone's
  hotspot, proxy, relay, cache server or local file endpoint.
- Switch the phone fully off. Observe playback for several minutes and across a
  buffering/segment boundary. Record exact receiver/firmware/app build and source
  class without committing active URLs.
- Reopen the controller and test reconnect/status without restarting media.
- Test eventual URL expiry, subtitles, source changes and receiver disconnect.

Opening a device picker, a successful SDK initialization, YouTube playback or a
fake LOAD is not acceptance of the full mobile/direct-streaming requirement.
