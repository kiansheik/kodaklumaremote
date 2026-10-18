# Philips 43PFG6910/78 receiver evidence

Checked 2026-10-18. Manufacturer-document findings and live session observations
are identified separately below.

## User's clarified acceptance requirement

Start authorized frameXTV playback from a Samsung phone, then **power the phone
fully off** while the receiver continues fetching and playing the original
media. Locked screen/background playback is insufficient. No phone proxy,
phone-hosted media server, phone hotspot, or continuous screen mirroring can be
a required part of this path. A generic Miracast-only receiver cannot meet this
requirement merely by supporting mirroring; it needs an additional independent
media-player protocol/app. Do not silently substitute mirroring as a success.

## Exact-model evidence

- Philips identifies the exact `43PFG6910/78` as **TITAN OS**, not Google TV:
  [product page](https://www.philips.com.br/c-p/43PFG6910_78) and
  [official datasheet](https://www.documents.philips.com/assets/20250604/c0cf294a62ad4c36b61ab2f200952bb3.pdf),
  printed p.3, Smart TV specifications.
- The datasheet's Matter / Google smart speaker compatibility on printed p.2
  describes TV control. It does not establish Google Cast receiver support.
- The [official manual](https://www.documents.philips.com/assets/20250325/8209e624f4694ae9a48bb2ab00357e47.pdf)
  covers `32PHG6910` and `43PFG6910`. Printed p.39 documents the TV's YouTube
  app, but does not establish YouTube/DIAL remote launching or general Cast
  media loading.
- Text searches of both exact-model PDFs found no named Chromecast, Google
  Cast, SimplyShare, DLNA, Miracast, or AirPlay support. Missing documentation
  is **not** proof that a feature cannot exist in current firmware or apps.

## Generic Titan OS guidance and its limits

The [Philips UK Titan OS guide](https://www.philips.co.uk/c-e/tv-buying-guide/tv-101/what-is-titan-os)
advertises AirPlay and casting from mobile streaming apps. It does not specify
this Brazilian model or guarantee that an arbitrary Google Cast receiver app ID
(including Google's Default Media Receiver) can launch. Do not infer full Cast
support or absence of support from the operating-system name alone.

Philips's [Google TV casting support article](https://tpvcrmplus.my.site.com/TPVB2B/s/article/How-to-stream-apps-from-a-mobile-device-to-a-Philips-Google-TV-1695639441742)
explicitly separates Google/Android TV instructions from non-Google TV casting.
Its [Linux/Titan OS casting article](https://tv-sound-monitors.philips.com/s/article/How-to-cast-content-from-a-mobile-app-on-the-Philips-Linux-based-TV-1702301381005?language=en_US)
returned HTTP 502 during research. It could not be used to verify an exact-model
procedure. Search-result excerpts and third-party quotations about the
`Casting by AirServer` app are leads only; availability on this TV is unverified.
The [AirServer Philips TV Edition page](https://www.airserver.com/PhilipsTV)
targets professional display models, so it is not evidence of support here.

## Useful physical checks

The final bounded SSDP probe at2026-10-18 07:49:29 America/Sao_Paulo, with user
confirming normal Home/YouTube mode, reached the exact43PFG6910/78 and read one
device description without errors. That description exposed no recognized
service entries or AVTransport actions. No generic DLNA playback path is thereby
established; do not infer that every mode/firmware lacks DLNA. Protocol of the
SSDP reply was not retained in sanitized metadata, so that initial probe did
not establish DIAL. A later exact-TV check below now establishes its app-launch
protocol; empty XML service entries did not exclude it. See E12 in
[experiments](experiments.md) for the earlier observation.

1. In Chrome's native Cast picker, see whether the powered-on TV is discovered
   on the same network. Record the picker result separately from extension
   messages and SDK readiness.
2. On the TV, inspect Apps for `Casting`, `Casting by AirServer`, or an equivalent
   receiver app. If present, open it and repeat discovery; do not assume it is
   installed from generic Titan OS instructions.
3. Distinguish YouTube-app remote playback, browser-tab mirroring, and the
   extension's Default Media Receiver media load. Success in one does not
   establish support for the others.
4. If the Cast picker has no suitable receiver, media URL/CORS/codec acceptance
   has not been exercised. A sender SDK being ready is a separate observation.

## Live follow-up: separate working paths

The parent agent's native Chrome testing found `43PFG6910/78`, disabled for
ordinary casting with the status `Available for specific video sites`. YouTube's
`Play on TV` route was selectable and reported playback. The user confirmed
picture and sound on the physical TV; pause, seek, resume and stopping that route
were exercised by the parent agent. This establishes the YouTube route, not
generic media loading or Chrome tab mirroring. Chromium's
[message definition](https://chromium.googlesource.com/chromium/src/+/82b2ea2e525fd2de9d03f633dc5be55d8c8d76e5/chrome/app/media_router_strings.grdp)
describes that status as compatibility with particular video websites. DIAL or
an app-specific receiver is a plausible explanation; the protocol was not
captured or proven by the picker text.

Later, a bounded unicast DIAL search returned0. Standard multicast searches for
DIAL and MediaRenderer, restricted to the paired TV address, returned one
descriptor matching43PFG6910. Its response advertised an `Application-URL`.
The old `discover-tv.mjs` retained descriptor XML but discarded this header,
so its empty service array was insufficient to exclude DIAL. It now reports
only a validated `dialAdvertised` boolean: HTTP(S), same numeric private
descriptor peer, no credentials/query/fragment; an advertised different port
is allowed. Eight offline discovery fixtures plus syntax/whitespace checks
passed. A subsequent live `node scripts/discover-tv.mjs --no-save` returned one
exact-model target, `dialAdvertised:true`, empty serviceTypes/AVTransport and
zero errors. This read-only tool fetches no app endpoints and launches nothing.

At that advertised application base, read-only YouTube GET returned403 and
Netflix GET returned200/stopped. Root sent exactly one empty POST to the
advertised Netflix endpoint, with no Select or further navigation: launch201,
then GET200/running. The user confirmed Netflix visibly opened.
This proves DIAL endpoint discovery/state/launch acceptance, not arbitrary URL
playback, app-name enumeration or subtitle delivery. No app
names or endpoint bases were guessed. See the
[current follow-up](session-handoffs/2026-10-18-philips-power-cycle-follow-up.md).

The user also reports successful Samsung-phone connection after entering the
TV's `modo de espelhamento`, while normal Chromecast does not see that mode.
[Samsung's Smart View support](https://www.samsung.com/us/support/troubleshoot/TSG10001542/)
explicitly identifies Miracast receivers. This makes **Miracast / Smart View the
leading hypothesis** for that separate mode. The user's report verifies that
phone-to-TV mirroring works; it does not identify packets or certify every
Miracast mode the TV supports.

## Extension and Mac implications

The checked-in controller uses the page's Google Cast SDK (`requestSession`,
then `loadMedia`); it contains no Miracast transport or tab capture. Google's
[sender integration guide](https://developers.google.com/cast/docs/web_sender/integrate)
documents this media-URL / receiver-app flow. A YouTube-only route cannot be
treated as a generic receiver for arbitrary frameXTV URLs.

Miracast is a different implementation: Microsoft's
[wireless projection documentation](https://learn.microsoft.com/en-us/windows-hardware/design/device-experiences/wireless-projection-implementing-over-existing-network)
describes Wi-Fi Direct discovery and RTSP, while the
[protocol details](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-mice/940d808c-97f8-418e-a8a9-c471dc0d21bb)
describe RTP/RTSP frames. A JavaScript Cast-SDK change cannot turn that flow into
Miracast. Chrome's [tabCapture API](https://developer.chrome.com/docs/extensions/reference/api/tabCapture)
can provide tab audio/video as a MediaStream, but does not implement that
wireless-display negotiation or transport. Raw sockets documented under
[Chrome Apps](https://developer.chrome.com/docs/apps/reference/sockets/udp) or
[Isolated Web Apps](https://developer.chrome.com/docs/iwa/direct-sockets) are not
a portable MV3 extension Miracast API.

A native companion could be connected through Chrome's
[Native Messaging API](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging),
but it would still need an actual supported Miracast sender stack, compatible
network/device capabilities, media encoding and transport. No such Mac stack or
TV compatibility has been validated here; adding a helper alone does not solve
them. This is a separate native integration project, not a small extension fix.

Apple's supported Mac mirroring path is
[AirPlay through Control Center > Screen Mirroring](https://support.apple.com/guide/mac-help/stream-video-and-audio-with-airplay-mchld7e543a0/mac).
That can diagnose receiver capabilities, but Mac or Samsung screen mirroring
does **not** satisfy the clarified phone-off requirement. Samsung Miracast
success does not establish AirPlay support either.

## Direct-play architecture decision

| Receiver path | Can meet phone-off architecture? | Current Philips evidence |
| --- | --- | --- |
| Google Cast media receiver | Yes, with receiver-accessible source and suitable receiver lifecycle | Generic route disabled; YouTube route works; no generic media acceptance yet |
| UPnP/DLNA MediaRenderer | Yes, if the TV fetches an origin URL itself | Exact-model description received in normal mode; no recognized AVTransport services/actions advertised |
| DIAL app launch | App-specific; does not establish arbitrary media delivery | Advertised base discovered; Netflix201/running and visible launch user-confirmed |
| Miracast / Smart View mirror | No; powered sender supplies ongoing video/audio stream | User reports Samsung mirror works |
| Independent TV browser/receiver app | Potentially; requires compatible TV player and independent media/auth access | Not tested |

Google's [receiver lifecycle guide](https://developers.google.com/cast/docs/web_receiver/basic)
separates receiver playback from sender control: explicit session stop and idle
without senders can close the receiver. Android's
[SessionManager.endCurrentSession](https://developers.google.com/android/reference/com/google/android/gms/cast/framework/SessionManager#endCurrentSession(boolean))
separately controls whether disconnecting also stops the receiver. This supports
the phone-off design, but actual receiver/app behavior still needs testing.

UPnP's [MediaRenderer:3 specification, Annex A, printed p.24](https://upnp.org/specs/av/UPnP-av-MediaRenderer-v3-Device.pdf)
explicitly makes content transfer independent of the control point. Its
[AVTransport:3 specification, Appendix A.1.3, printed p.128](https://upnp.org/specs/av/UPnP-av-AVTransport-v3-Service-20101231.pdf)
defines `SetAVTransportURI` / `Play` using the renderer's HTTP GET of the URL.
An SSDP response alone is insufficient: inspect `MediaRenderer`, `AVTransport`
and `ConnectionManager` services/capabilities before planning that route.

For the Samsung-phone product, the appropriate candidate is a native Android
sender using Google's [Android Cast SDK](https://developers.google.com/cast/docs/android_sender/integrate)
plus a UPnP controller only for verified renderers. Existing desktop MV3 code
does not deliver that mobile product; Google's
[extension installation help](https://support.google.com/chrome/answer/2664769)
describes desktop installation, and the phone flow adds extensions to desktop.
Do not scaffold a new sender until a viable receiver path is established.

For each direct-play route, test public MP4 first, then the authorized original
HLS/MP4. Validate receiver codecs, TLS, manifests/segments/tracks, auth and URL
expiry without depending on the phone. The decisive acceptance test powers the
phone off beyond the receiver's buffer and observes sustained playback/new
media requests. Sender-off behavior and source-request evidence are not yet
verified for this Philips TV.
