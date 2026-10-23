# LUMA Receiver

The projector-side player that replaces Kodi on the Kodak LUMA (Android 9,
Amlogic, 32-bit `armeabi-v7a`). Pure Java + Media3 ExoPlayer, about 1.2 MB,
signed with the same production certificate as the phone app.

## What it does

- Plays HLS, DASH, MP4/WebM/MKV through ExoPlayer on a hardware video surface.
- Side-loads SRT/WebVTT subtitles and draws them in the house style: outlined
  text wrapped to the centre half of the screen on a rounded, nearly transparent
  panel. Size and style persist on the projector.
- Brightness from −30% to +30% while video remains on the hardware-direct
  SurfaceView. A translucent dark/light overlay avoids the projector's broken
  TextureView path; the phone remembers the value per movie or whole series.
- Records watch progress on the projector itself and syncs it with phones.
- Shows phone scrub previews (position + the phone's frame) live on screen.
- Runs a foreground service from boot (and after self-update) so phones can cast
  without launching anything first; opening a video brings the player forward.
- Updates itself from the release feed (`auto`/`ask`/`off`, default `ask`) or
  from a paired computer (dev channel). Android 9 always shows its own install
  confirmation for sideloaded apps.

Projector remote: OK = play/pause, Left/Right = −10 s/+30 s, Up/Down = caption
size, Back twice = stop. On the idle screen OK installs a ready update and Back
skips it.

## LAN API (port 4191)

`GET /api/v1/info` is public. Everything else is `POST /api/v1/<method>` with a
JSON object body and `Authorization: Bearer <token>`. Replies are
`{"ok":true,"result":…}` or `{"ok":false,"error":{"code":…}}`. Only private,
link-local and loopback peers are served.

| Method | Purpose |
| --- | --- |
| `pair.begin {clientName}` / `pair.complete {pairingId, code}` | Show a 6-digit code on screen, exchange it for a token (2 min, 5 attempts). Only token hashes are stored. |
| `player.open {media, onlyIfInactive?, expectedCurrentSourceUrl?, expectedCurrentContentKey?}` | Open media; guards are checked atomically on the player thread. `media` = `{url, contentType?, title?, content?, resumeSeconds?, subtitles?[], headers?}`. |
| `player.status {afterRevision?, waitMs?}` | Snapshot; with `afterRevision` it long-polls up to 25 s. |
| `player.pause/resume/stop/seek` | Accept `expectedSourceUrl` / `expectedContentKey`. |
| `player.subtitles`, `player.addSubtitle {track}`, `player.setSubtitle {index\|'off'}` | Same shapes the phone used for Kodi. |
| `player.audioTracks`, `player.setAudioTrack` | Audio language selection. |
| `player.scrub {active, positionSeconds, durationSeconds?, image?}` | Mirror a phone scrub (JPEG data URI ≤150 KB). |
| `display.get` / `display.set` | Caption scale/style and picture adjustments; writes may carry source/content guards. |
| `library.pull {since, limit}` / `library.push {records}` | Last-writer-wins sync of progress records. |
| `volume.get/set/mute`, `app.notice`, `app.show` | Utilities. |
| `update.status/check/install/skip/setPolicy/installFrom` | Release feed and dev channel. |

## Build and dev loop

```sh
gradle -p android-receiver :app:testDebugUnitTest
node scripts/receiver-dev.mjs pair --host <projector IP>   # once; read the code off the screen
node scripts/receiver-dev.mjs deploy                       # build, push, confirm install, wait
node scripts/receiver-dev.mjs open <url> --sub <srt-url>@pt
node scripts/receiver-dev.mjs call <method> '<json>'
```

`deploy` signs with the private release config (`.luma-release.json`), serves
the APK once from this Mac, asks the receiver to install it, presses the Android
installer's Install button over EShare (`down,right,enter` on this projector)
and waits for the new build to report back. The token lives in
`.local/receiver-dev.json` (mode 0600).

First install on a new projector: open the Pages site in the projector's
browser (TV Bro) and download LUMA Receiver, or serve the APK on the LAN and
type its URL. Allow "install unknown apps" for that browser once.
