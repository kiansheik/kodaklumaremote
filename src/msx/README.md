# TV receiver

These files run in the TV Internet browser. The Android sender serves the
receiver assets and a small control channel on its Wi-Fi address. The TV fetches
the original video and selected subtitle URL itself; the phone never relays
media. Once the video and selected subtitle have loaded, losing the phone control
connection does not stop or reset the TV player.

## Setup and platform boundary

When the receiver is offline, LUMA Remote shows **Set up TV receiver** instead
of attempting a browser launch. In the TV Internet browser, keep the stable LUMA
page as its only speed dial and open it. The launcher's empty phone-IP field takes
focus automatically; **Autofill this phone on LUMA page** types only the current
native-owned private phone IP and presses Go. First use requests the separate
four-digit Philips TV-text permission in the same flow. If the speed dial is
unavailable, **Direct phone address** plus **Type and open selected address**
remains the backup for the browser address bar.

The stable choice types only
`https://kiansheik.io/kodaklumaremote/tv.html`. The fixed page presents a blank,
IP-only field and remembers the submitted private phone IP. Full URLs are
rejected. One saved phone is probed and opened automatically; multiple saved
phones produce a chooser. If the HTTPS-to-HTTP image probe is unavailable in
the TV browser, the IP field stays blank for phone autofill and an **Open saved
phone anyway** fallback is shown. The direct backup types
`http://PHONE:PORT/tv/connect.html`.

After the fixed value is typed, the phone sends the TV's Matter **Enter/Go** key.
If that key is rejected, the phone explicitly asks the user to press **Go / OK**
on the TV. Callers choose only between native-owned fixed values (the stable
URL, direct backup URL, or current private phone IP) and cannot provide arbitrary
text. The legacy `/msx/connect.html` route and old launcher
fragments remain only for previously saved links.

The phone and TV must share a network. A successful text-input acknowledgement
does not prove the address is visible or that the TV loaded it. Actual TV
navigation, HTTP access, playback, picture, sound, subtitles and phone-off
continuation remain physical acceptance checks.

## Playback and controls

- `open`: `{source,mimeType,title,subtitles:[{url,language,label}],preferredLanguage}`.
  HTTP/HTTPS sources only. Native HLS is used when available; otherwise bundled
  HLS.js is used if the TV supports MediaSource. HLS.js performs direct origin
  requests and therefore needs appropriate source CORS headers. Codec support
  still depends on the TV.
- The complete subtitle catalog is kept in TV memory, but only PT-BR (or English
  when Portuguese is absent) is initially fetched. Selecting another index lazily
  fetches it directly from its origin. Subtitle URLs need CORS. SRT and WebVTT
  are parsed locally; timed text is drawn with `textContent`, never active HTML.
  Failed Portuguese loading returns `SUBTITLE_FAILED` and leaves video playing;
  it does not silently substitute another language.
- `playPause {play}`, `play`, `pause`, `seek {seconds}`, `volume {value:0..100}`,
  `subtitle {index,enabled}` and `stop` act on the TV-owned player. Volume is the
  media element's volume; hardware speaker volume remains TV-specific.
- Controls optionally carry `expectedSourceUrl`. A mismatch returns
  `SOURCE_CHANGED` without modifying the current video.
- Open receipts wait for actual playback startup and the selected subtitle
  attempt to settle. Network/codec/startup/subtitle failures return bounded error
  codes. A failed subtitle attempt can coexist with active video playback.

When embedded in MSX, the official `TVXVideoPlugin` bridge connects the TV's
native player overlay to the same player. Its SDK is loaded once from the official
`https://msx.benzac.de/js/tvx-plugin.min.js`, with no referrer. It is deliberately
not redistributed under an assumed license. Standalone TV browser pages skip
this SDK and bridge entirely. An SDK network failure does not
disable the phone control channel or stop an already playing video; native MSX
overlay integration will be unavailable for that launch. HLS.js is separately
pinned and bundled by the mobile build.

## Control channel

All endpoints below are under the phone-generated secret URL prefix. Native
code additionally pins the approved TV address/client and owns consent,
validation and command timeouts.

1. Before each new session, the receiver GETs same-origin `/msx/start.json` and
   checks its content URL against the already loaded capability. This lets a
   waiting TV reclaim its peer after the phone renews an expired setup window,
   without reloading or affecting media. A changed capability is rejected;
   the page never navigates automatically or approves itself.
   `GET hello?client=ID` → `{sessionId,approved}`. The random TV client ID persists
   in local storage when the TV browser permits it.
2. `GET poll?client=ID&sessionId=ID&after=N` → `{approved,commands:[{id,method,params}]}`.
3. `POST state {client,sessionId,playback}` reports observed playback before an ACK.
   Playback contains `active,source,title,paused,positionSeconds,durationSeconds,
   canSeek,volume:{current,max:100},tracks:[{index,language,label}],selectedIndex,
   subtitlesEnabled,error`.
4. `POST ack {client,sessionId,id,ok,error?}`. Both POST routes return JSON.

Commands are serialized, IDs are monotonic, duplicate/stale IDs are ignored, and
an unsent ACK is retried from its saved receipt without replaying `open`. A newly
issued session resets the cursor and invalidates older native commands. Unapproved
receivers only poll; they send neither playback state nor ACKs. After phone network
failure, bounded exponential reconnect attempts run independently of playback.

## Checks and official references

`node --test tests/msx-receiver.test.mjs` covers direct source/subtitle ownership,
SRT/WebVTT conversion, preferred/lazy languages, startup failures, subtitle error
receipts, stale-source rejection, command ordering/deduplication, lost ACKs,
approval and playback continuation during control-network failure.

- [MSX startup convention](https://www.msx.benzac.de/wiki/index.php?title=Setup_Start_Parameter)
- [Start object](https://www.msx.benzac.de/wiki/index.php?title=Start_Object)
- [Video/audio plugin mechanism](https://www.msx.benzac.de/wiki/index.php?title=Video/Audio_Plugin)
- [Plugin API](https://www.msx.benzac.de/wiki/index.php?title=Plugin_API_Reference)
- [Titan app catalog](https://www.titanos.tv/apps)
- [HLS.js pinned upstream release](https://github.com/video-dev/hls.js/releases/tag/v1.7.3)
