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
- Can be selected as the projector's Android Home app so it is the first and
  default screen after boot. On the idle screen, press OK once to open Android's
  Home-app settings; Menu reopens that setting if you want to switch back later.
  The idle footer and unauthenticated `info` response report whether it is the
  current Home app and count distinct OK presses/Home-settings attempts.
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
| `player.open {media, onlyIfInactive?, expectedCurrentSourceUrl?, expectedCurrentContentKey?}` | Open media; guards are checked atomically on the player thread. `media` = `{url, contentType?, title?, mediaId?, content?, resumeSeconds?, subtitles?[], headers?}`. `mediaId` is an opaque `local:` identity used only for shared local-file scrub frames. |
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

## Local files and laptop browser remote

The phone app's Browse screen can choose an MP4, MKV or WebM file plus external
SRT, WebVTT, ASS/SSA or TTML subtitles. The app serves byte ranges from a random
capability URL bound only to the phone's active private Wi-Fi address. This is a
direct stream: the container and codecs must already be supported by the
projector.

For arbitrary or newer laptop media, install `ffmpeg` and run:

```sh
npm run stream:local -- --root /path/to/media
```

That opens a localhost-only browser remote. It pairs with the receiver, lists
media below the configured root, discovers same-basename subtitle sidecars, and
provides play/pause, seek, subtitle and volume controls. Auto mode preserves
compatible H.264/AAC files byte-for-byte; compatibility mode converts other
formats to high-quality 1080p H.264/AAC HLS (`slow`, CRF 16, 320 kb/s AAC).
Use `--no-open` to print the browser URL without opening it, or stream one file
without the browser:

```sh
npm run stream:local -- /path/movie.mkv --host 192.168.1.50 --subtitle /path/movie.pt-BR.srt@pt-BR
```

Playback remains owned by LUMA Receiver, not by the initiating browser. Other
paired phones can therefore attach, pause, seek, select loaded subtitle tracks
and stop it. The laptop generates bounded JPEG scrub frames and stores them in
the receiver's expiring thumbnail cache so all paired phone remotes can reuse
the same previews without gaining file-system access to the laptop.

For progressive local files, the receiver keeps the existing 8–20 second RAM
window and separately prefetches into a current-title disk cache at lower network
priority. Playback always preempts that worker; after the RAM window fills, the
worker continues while playing or paused. Files that fit are cached completely.
Larger files use a rolling ahead window that removes consumed spans and reuses
the space. Stop or source replacement deletes the prior title, and process start
sweeps crash residue. At least 512 MiB and 10% of currently free storage remain
reserved, with an 8 GiB ceiling. A mounted writable SD-card cache directory is
preferred when it offers more safe space; otherwise internal app cache is used.
`player.status` reports the storage, state, bytes, target and estimated seconds
ahead, and the laptop remote shows that progress. HLS/DASH are not described as
whole-file cached because their manifests require playlist-aware downloading.

The laptop/phone and receiver must be on the same private LAN and the router must
allow client-to-client traffic. Mobile data succeeding while a watch page fails
on Wi-Fi does not by itself prove an ISP or IP block; DNS filtering, router
filtering, CDN routing, or the site's own browser policy can produce the same
symptom. Local-file streaming bypasses that public watch-page path.

First install on a new projector: open the Pages site in the projector's
browser (TV Bro) and download LUMA Receiver, or serve the APK on the LAN and
type its URL. Allow "install unknown apps" for that browser once.
