# TV receiver

These files run in the TV Internet browser or inside Media Station X. The Android sender serves the
receiver assets and a small control channel on its Wi-Fi address. The TV fetches
the original video and selected subtitle URL itself; the phone never relays
media. Once the video and selected subtitle have loaded, losing the phone control
connection does not stop or reset the TV player.

## Setup and platform boundary

In LUMA Remote, select Philips → Video receiver → **TV browser** and tap
**Open TV receiver**. The phone prepares its listener. First time only, enable
TV browser control with the four-digit PIN shown by the TV, then tap **Open TV
receiver** again. Existing saved permission is reused. The native launch request
uses this phone's direct LAN URL `http://PHONE:PORT/msx/connect.html`; callers
cannot provide a URL. A launch acknowledgement does not prove the TV opened the
page. Wait for the actual TV connection, then explicitly **Allow this TV**.

If automatic launch is unavailable, choose **Enter link manually**. Open
**Internet on the TV**, select and clear its address bar, then tap **Type browser
link** on the phone. Check the TV address and select **Go / OK on the TV**. The
phone types its own fixed LAN setup link only; it does not clear or submit the field.
The phone and TV must share a network; no public redirect service is involved.
Actual TV launch, navigation and playback remain device acceptance checks.

The secondary **Media Station X** option retains its original setup flow. Tap
**Set up TV** if needed. Enable address entry once with the TV PIN if it is not
already saved.
This text-control permission is separate from Matter and receiver approval.
After permission is saved, return to **Media Station X → Settings → Start
Parameter → Setup** on the TV. Select and clear its address field, leave the
keyboard open and the padlock open, then tap **Enter TV address** on the phone.
The phone sends only its own current `PHONE:PORT` startup address. Check the
displayed text and confirm **Go / OK on the TV**; the phone does not submit it.
If entry is interrupted or partial, clear the field before trying again. A
successful protocol acknowledgement does not verify the displayed text.
If text control is unavailable, enter the shown address manually. MSX requests
`http://PHONE:PORT/msx/start.json`. The phone generates that start object and a
home page containing a `video:plugin:` action pointing at `receiver.html` under
the private capability prefix. Open **Connect this TV**, then explicitly approve
the detected TV on the phone. No media commands are accepted before approval.

Startup must be possible over HTTP from the installed MSX app. An HTTPS-hosted
MSX launcher can upgrade HTTP JSON/plugin URLs or block mixed content; a successful
desktop-browser test does not establish compatibility with the Philips firmware.
The phone and TV must be on the same network. Exact Philips picture, sound,
subtitle rendering, control behavior and phone-off continuation require physical
acceptance. MSX availability in the Titan OS catalog includes Brazil but varies
by TV model and region.

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
