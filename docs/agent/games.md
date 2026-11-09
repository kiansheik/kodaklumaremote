# Projector Games

Phone **0.4.35/code45** and receiver **0.3.27/code41** are installed in place on
the physical devices. Physical NES gameplay, receiver navigation and the steady
Player 1 light are user-confirmed. The user also confirmed that holding B to run
and tapping the duplicate B trigger fires successfully while running in Mario.
Capture → Load latest checkpoint is also physically user-confirmed.
No public release was performed.

## Runtime and input

- `android-receiver/.../GamesActivity.java` owns a receiver-local NES library,
  full-screen `NesGameView`, pause menu, automatic resume and manual checkpoints.
  Video playback is paused and pending autoplay cancelled when entering Games.
  Explicit video/Cat/receiver commands leave Games; automatic updater installs
  wait while its Activity is open.
- QuickNES engine source is pinned at
  `26bb785c9deddb66a17717b21bb4e328f03ade32`, under
  `app/src/main/cpp/third_party/quicknes/`. Its LGPL engine is used directly,
  without the upstream GPL libretro frontend. Source hashes, individual notices,
  license text and build instructions are retained; the APK includes its license
  in **Games → Emulator info**. NDK r30 builds ARM32/ARM64/x86_64 shared libraries.
- The frontend is NTSC/60 Hz, 48 kHz mono audio and unfiltered 4:3 picture.
  iNES and a deliberately limited NES2 subset are accepted. Extended mapper,
  RAM, size, nonstandard console/input and PAL-only NES2 declarations are
  rejected. Other mapper compatibility is determined by QuickNES at load.
  SNES/GBA are not implemented. The supplied Mario Tupi ROM fits the supported
  NES2 subset and runs, but is never bundled or tracked.
- `ControllerInput`/`ControllerState` support Android gamepad buttons, D-pad,
  hats and both sticks. Saved `ControllerPlayers` slots identify each controller;
  the current NES frontend accepts gameplay input only from Player 1.
  Disconnect/focus loss releases input, and opposing directions are neutralized.
  Navigation has repeat and stick hysteresis. MainActivity uses the same adapter
  for launcher/player/Cat controls. `ControllerNavigation` moves focus explicitly:
  Activity-translated D-pad events skip Android's ViewRootImpl navigation stage.
  The optional across-app Accessibility service skips focused LUMA activities,
  so gameplay/calibration retain this local input path.
- Home/Games **Controllers** opens `ControllerSetupActivity`: full Pro Controller
  diagram, live input test, guided hardware calibration, separate NES assignments,
  receiver shortcut reference and Android Bluetooth settings. Android owns pairing.
  The user's full-size Pro Controller paired and appeared as an Android input
  device; the user confirmed launcher navigation but reported the missing right
  stick in the previous build.
- `ControllerCalibration` captures a draft with neutral/release and device guards.
  `ControllerProfileStore` saves only explicitly, using stable device/model keys;
  Activity resume reloads profiles. Optional Home/Capture can be skipped when
  Android reserves them. `ControllerProfile` separates raw keys/axes from logical
  Nintendo controls and NES actions, including a remappable game-menu action.
- Receiver shortcuts: either stick/D-pad navigates; A/left-stick click confirms;
  B goes back; X/minus/right-stick click opens Menu; Plus confirms or toggles media
  playback; L/R seek; ZL/ZR adjust volume. Delivered Home returns to the launcher;
  Android-reserved Home uses the normal Home intent. These mappings apply within
  the receiver. With **LUMA Controller across apps** enabled, calibrated buttons
  also become ordinary remote keys in other apps; Android 9's stick/hat mapping
  remains in place. User accepted cross-app A/B and existing stick behavior.
  NES defaults are A/Y → A, B/X → B, Plus/Minus → Start/Select and L/R → game
  menu. New profiles use ZL/ZR for volume; older saved assignments remain intact.
  The NES editor offers Volume down/up and a trigger-volume preset followed by
  explicit Save. Volume actions are edge-triggered outside the NES input mask.
  The right stick is separately opt-in for NES directions.
- Fresh physical presses are delivered separately from aggregate NES holds.
  `NesFrameInput` gives an already-held virtual button one released emulated
  frame before restoring the remaining holds. This lets a second B binding fire
  while the first B binding remains held for running. Rapid taps queue up to
  eight pulses per virtual button with pressed frames between them. Partial
  release preserves other holds; final release clears that button's queue.
  Key repeats and key/axis aliases do not count as fresh logical presses; menu
  and volume never enter the scheduler. Pause, focus loss and device removal
  cancel pulses. The release duration is one NES frame, about 16.7 ms here;
  games that do not sample inputs every frame may need different treatment.
  Simultaneous rises mapping to the same NES bit within one Android event merge
  into one pulse; separate events queue independently.
- Player slots 1–4 persist independently of calibration, with explicit swaps.
  All connected players can navigate the receiver. Additional emulator ports
  are future work. `ControllerLeds` sends Nintendo command 0x30 with neutral
  rumble and steady masks 1/3/7/15. The projector blocked hidden-method reflection;
  `HidHostTransport` instead binds the existing exported Android 9 system service,
  validates its descriptor and permissions, and uses verified AIDL transactions
  for inventory/output. This is API-28-only and requires one unambiguous Bluetooth
  Pro Controller. The service accepted the packet and the user confirmed a steady
  Player 1 light. No driver, pairing or input-report-mode change was needed.
  `sentUnconfirmed` still means transport acceptance, not decoded controller ACK.
- Saves are private on the projector, under each ROM hash. ROM synchronization
  does **not** replicate save states or battery saves to the phone.
- Receiver 0.3.23 adds five manual checkpoints per game, newest saved first.
  **Save checkpoint** appends; **Checkpoint history** lists dated saves for
  individual restoration; **Load checkpoint** loads the newest. Loading an older
  save does not reorder or consume history. Automatic resume/battery saves remain
  separate. The legacy single checkpoint remains readable and is copied into the
  history before the first new save; its original file is retained as a backup.
  Immutable checkpoint files are flushed and renamed into place before older
  entries are pruned. Interrupted temporary files are ignored.
- NES assignments offer **Load latest checkpoint** on any assignable button.
  Capture is included in the editor as of 0.3.24; the user's calibrated Pro
  Controller now has Capture → Load latest checkpoint explicitly saved.
  It triggers on a fresh logical press for Player 1 only, never enters the NES
  input mask, and requires neutral controls after success. Existing assignments
  are preserved. Quick load stops and flushes audio before restore, then resumes;
  a missing or unreadable checkpoint shows a message and resumes current play.

## ROM import and synchronization

Phone `GamesLibrary`/`GamesManager` keep private, durable copies. **Library →
Games library** or **More → Games** offers an explicit document picker. Android
VIEW/SEND MIME filters and `.nes` extension filters offer external opening when
the source file manager supplies a compatible URI/MIME type; picker import is
the reliable fallback for opaque content URIs.

Missing ROMs are copied in both directions on receiver connection and on manual
sync, on a dedicated bounded worker. Files are identified by SHA-256, validated
before atomic publication and transferred through the existing paired bearer
connection. Interrupted transfer is retried from zero; no deletion propagates.
Each file is at most 8 MiB, libraries at most 256 games/128 MiB, and a chunk at
most 128 KiB. Receiver permits four incomplete uploads with ownership and expiry.

Authenticated `POST /api/v1/<method>`:

| Method | Request / result |
| --- | --- |
| `games.list` | `{games:[{id,name,system,sizeBytes}],systems,chunkBytes,maxRomBytes}` |
| `games.put` | `{id,name,offset,totalSize,data}` base64; `{received,complete,game?}`. Existing identical hash/size completes immediately. |
| `games.get` | `{id,offset,length}`; `{id,offset,totalSize,data}` base64. |
| `games.play` | `{id}`; launch accepted, **not** confirmation of native playback. |
| `games.show` / `games.stop` | Open library / save and stop the current game. |
| `games.status` | `{active,id,paused,frames,retriggeredPresses,checkpointCount,checkpointLoads,error}`; frames prove native advancement. Retriggers count forced-release bits consumed at frame boundaries since the current game load; checkpointLoads counts successful restores in this game view. |
| `games.controllers` | `{devices:[...]}` input inventory, not a Bluetooth scan. |
| `controllers.show` | Open controller setup. |
| `controllers.status` | Active page, connected device, calibration progress and bounded raw input trace. |
| `controllers.players` | Four saved player slots and connected controller assignments. |
| `controllers.snapshot` | PNG of the setup UI only, for device verification. |
| `controllers.navigation` | Receiver focus and last app-translated navigation. |
| `controllers.system` | Accessibility connection, local input transport, mapped press count and Home-hold/power request state. |
| `controllers.accessibility` | Open available Accessibility panel or general settings; requires the user to enable the service normally. |
| `controllers.setLights` | `{deviceId}`; request steady lights for that controller's saved player slot. |
| `controllers.lights` | Bounded transport result; hardware ACK is not decoded. |

## Reproduction and acceptance

```sh
scripts/test-nes-core.sh /private/path/game.nes
node scripts/test-phone-games.mjs
# Running isolated AVD only; never accepts a physical-device serial:
ANDROID_TEST_SERIAL=emulator-5582 node scripts/test-receiver-games.mjs --rom /private/path/game.nes
```

The integration test expects an installed receiver APK, pairs through its
visible PIN, checks unauthorized rejection and byte-identical upload/download,
then native frames, injected Start, stable pause, checkpoint restore, automatic
resume and process restart. Screenshots remain ignored under `.local/nes-tests/`.
No physical Bluetooth, projector audio or projector frame-latency claim follows
from emulator/native-core tests.

Physical evidence: both candidates installed with data preserved; supplied ROM
reached 2,814 native frames on Android 9/ARM32 with no reported error and stable
pause. The real phone Games sheet showed the ROM on both phone and projector,
confirming receiver-to-phone sync. User confirmed Pro Controller pairing and
basic receiver navigation. Human NES picture/audio and precise mapping acceptance
are still separate from runtime receipts.

Next hardware acceptance: finish calibration, then check both-stick launcher
focus/confirm/Back, media playback/seek/volume, Start, jump/run, simultaneous
direction+button, configured game menu, checkpoint,
disconnect release, sound and visible frame pacing. Disconnect the phone and
confirm gameplay continues. Reconnect and verify deduplication. Test a second
phone for receiver-to-phone download. Existing earlier Cat audibility questions
remain independent and unresolved.
