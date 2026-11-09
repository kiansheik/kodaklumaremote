# Current state

## Installed receiver 0.3.33: all-format disk preload and high-quality playback

- User clarified that paused loading must work for all supported media and that
  quality should stay high even if buffering takes longer. The old local-file-only
  eligibility is removed: remote/direct progressive files, HLS and DASH use the
  current-title disk cache. Phone remains0.4.37; its existing cache UI works.
- Media3 DownloadHelper chooses decoder-supported highest-bitrate tracks with
  the same quality parameters as playback; adaptive playback no longer drops to
  lower bitrate with bandwidth. HLS/DASH prefetch runs in60-second time ranges,
  with audio/init/key resources handled by Media3, independently of RAM/pause.
  Playback also caches adaptive segments. Manifests stay fresh for playback.
- Cache has a shared LRU hard limit, 512MiB/10% reserve and8GiB ceiling; on this
  projector the selected internal budget is about1523MiB. When nearly full, keep
  the paused forward range and refill as playback consumes it. Seek restarts the
  download range; changing source/stop cancels and clears old session keys.
  Live content caches received segments but cannot preload unpublished future.
- Complete adaptive windows are checked against actual disk spans before reporting
  seconds ahead. That counter advances conservatively in60-second batches. A
  fully cached tail is not labeled a fully cached title. Signed0.3.33/code47 is
  installed. Real paused S4E2 at522.025s stays1920x1080/AAC with zero dropped
  frames; bytes grew0.6→25.7MB while RAM stayed around23.5s. Final sample
  reports137.975s disk ahead and still filling; left paused as announced.
- Receiver140/140 unit tests pass. Isolated Android integration passes all three
  formats: paused preload,1080p choice,seek to90s with upstream media blocked,
  and cleanup. Emulator fixtures never touched the user library. No public feed
  or commit/push. [Handoff](session-handoffs/2026-11-09-all-format-cache-quality.md).

## Installed receiver 0.3.32 / phone 0.4.37: failed-stream recovery

- User accepts Watch History UI; Gilded Age S4E2 failed startup. Diagnostics
  show about 90 seconds buffering without audio/video, with disk cache off.
  History stores canonical progress, not playable URLs. Fresh API candidates
  also timed out in direct checks; alternate providers reported no streams.
- Catalog now probes fresh sources (HLS playlist plus first media bytes),
  rejects empty/error responses, and tries available alternate providers within
  a bounded lookup budget. A startup error or 25 seconds without audio/video
  triggers one fresh resolution excluding the failed URL. Zero duration alone
  is not treated as failure. This does not recover failures after playback starts.
- If projector resolution fails, a five-minute canonical episode request appears
  in phone controls and its notification. Open episode uses the existing phone
  browser/Play/Cast flow; this is guided assistance, not automatic browser capture.
  Cancel/new playback clears requests; the phone card revalidates before opening.
- Receiver139/139 unit tests and signed builds pass; focused recovery/remote
  browser checks pass. Receiver0.3.32/code46 installed; phone0.4.37/code47
  installed preserving data. User authorized saving Mario and installation;
  pause-menu Save checkpoint and clean game stop were performed.
- Live projector S4E2 resolves in about 8 seconds; later status shows duration
  3093.007 seconds, AAC audio and position24.829 advancing. User confirms picture
  and audio play correctly. No phone assistance was needed for this successful
  attempt. Actual failed-source phone handoff remains covered by browser/unit
  checks, not an end-to-end physical failure reproduction.
  [Handoff](session-handoffs/2026-11-09-stream-recovery-phone-fallback.md).

## Installed receiver 0.3.31: Watch History and Android TV settings shortcut

- User confirms Cat Mode control is now good. Refreshed Accessibility permission
  is verified: `controllers.system.directNavigation:true`. Standard direct-panel
  availability remains false; that field is distinct from the enabled permission.
- The real settings app is `com.android.tv.settings/.MainSettings`, enabled and
  exported but absent from launcher inventory. It contains Accessibility as a
  fragment, not an exported activity. The new **Accessibility** Home button and
  controller-setup shortcut open this full settings menu, with instructions to
  select Accessibility → LUMA Controller across apps. Droid Settings is separate.
- **Watch History** on Home opens a controller-operated offline library grouped
  by title/recent viewing, with saved resume/watched states, resume/restart,
  next episode, season and episode browsing. Fresh metadata/source resolution
  uses the existing receiver catalog; saved episodes remain browsable when the
  catalog is unavailable. Movies and mapped anime retain canonical identities.
  No stale media URLs are persisted. Cancel/Back/activity pause or intervening
  playback changes prevent delayed source resolution from taking over playback.
- Receiver135/135 tests and signed build pass; installed0.3.31/code45. Actual
  projector smoke passes Home tile →10 titles →series actions →seasons →9 episodes
  in the selected season →episode actions →Back through every level, using only
  `input.key` receiver routes. Physical resume/next playback acceptance is pending.
- Reinstalled the latest signed phone0.4.36/code46 with `adb install -r` as requested;
  saved app data retained. No phone source changes in this follow-up. Cold-boot
  timing and long-Home power behavior remain untested; do not shut down while the
  user is checking history playback.
  [Handoff](session-handoffs/2026-11-09-projector-watch-history-settings.md).

## Installed receiver 0.3.28 and phone 0.4.36: smooth Cat input and direct remote

- User accepted the prior launcher focus/Cat activation fix, but reported jerky
  motion and jumps on stick release. Cat now receives calibrated analog values
  before menu quantization, integrates movement each animation frame, takes over
  at the rendered target position, and holds position at neutral. Phone pointer
  takeover retains its existing short lease. Disconnect/pause stops movement.
- Receiver API starts in Application initialization and is also ensured by
  Accessibility connection, boot/update receiver and foreground service. Startup
  diagnostics expose process/listener/accessibility elapsed times. On this
  **update restart**, listening began 1,268 ms after process creation; this is
  not a cold-boot measurement or a promise of immediate Wi-Fi readiness.
- Authenticated `input.key` handles own-app navigation/Home and Android volume;
  external focus/click/back uses Accessibility with a bounded vendor fallback.
  `input.remote` advertises the capability. Phone direct connect now enables
  remote controls, volume, app list/launch and opening the receiver without an
  EShare socket; manual device connect also tries the paired receiver first.
  Older receivers retain their native path. Power, text entry and system Air
  Mouse still use vendor transport. Discovery for an unknown/changed IP still
  uses vendor discovery; saved-address direct connection is the tested path.
- Accessibility now requests current-screen controls for explicit local remote
  actions; they are not stored or transmitted. After installation Android still
  reports `directNavigation:false` with the old service permission. User was
  asked to toggle the service off/on and accept the updated description. Own
  receiver controls do not require this added permission. Other-app controls
  before EShare remains unverified until that permission and physical check.
- Receiver 130/130, phone 16 emulator instrumentation checks plus wire assertions,
  two focused browser tests and both signed builds pass. Both apps installed;
  projector `input.key home` confirms `route:receiver.home`. Cat smoothness and Accessibility permission refresh were subsequently confirmed;
  cold-boot timing remains pending. Long-Home
  power test remains deferred until Cat is accepted.
  [Handoff](session-handoffs/2026-11-09-smooth-cat-early-receiver-api.md).

## Installed receiver 0.3.27: launcher focus and Cat Mode activation repaired

- Reproduced the Cat tile failure on the projector: focused Cat Mode + OK
  moved focus to the first app and left Cat inactive. The idle OK handler had
  omitted Cat from its button whitelist. It now activates the actual focused
  clickable launcher control, including Cat Mode.
- Home/resume explicitly focus the first installed app and reset grid scrolling;
  returning from later rows no longer retains stale lower-list focus. Cat exit
  also restores first-app focus. Native remote directions share the same Cat
  movement helper as translated controller directions.
- Receiver126/126 and signed release pass; 0.3.27/code41 installed. Actual
  projector check passed first-app Home focus, lower-row reset, Cat tile launch,
  directional manual control, confirm without exit, Back exit and restored
  focus. User confirmed focus and activation; motion quality is addressed above. The user deferred
  long-Home power testing until Cat Mode is fixed; do not test power yet.
  [Launcher/Cat handoff](session-handoffs/2026-11-09-launcher-focus-cat-fix.md).

## Installed receiver 0.3.26: buttons across apps, Home hold and Cat Mode

- User enabled **LUMA Controller across apps** in Kodak's general settings and
  confirms A selects, B goes back and the existing left stick still works in
  other apps. The Accessibility service filters saved-controller button keys,
  translates using hardware calibration and sends ordinary keys through the
  projector's loopback EShare service. It skips LUMA's own focused activities,
  preserving game mappings/Capture; no screen contents are requested or read.
- Android 9's analog mapping remains unchanged outside LUMA, as the user wants.
  The standard Accessibility settings action and an exported direct panel are
  absent; the general-settings fallback works and the user found the toggle.
  Live service is enabled and has translated actual physical presses.
- A short calibrated Home press returns to LUMA. Holding it for 3 seconds saves
  the game, requests Pro Controller sleep (HID 06 00), then sends the existing
  projector power command. Early release/device removal cancels the timer.
  Physical controller sleep and projector off/standby acceptance remain pending.
- Launcher now has **Cat Mode**; stick/D-pad moves, A pounces, B exits. Physical
  Cat acceptance is pending. Receiver126/126, manifest check and signed build
  pass; 0.3.26/code40 installed, phone unchanged, no public release.
  [System-controller handoff](session-handoffs/2026-11-09-controller-system-home-cat.md).

## Installed receiver 0.3.24: Capture quick load assigned

- Capture was calibrated and supported by input routing but omitted from the
  NES editor's editable-control list. It is now selectable as **Capture**.
- Installed 0.3.24/code38 and used the actual projector UI to save Capture →
  Load latest checkpoint (2048) on the paired Pro Controller. Saved-profile
  comparison verified every other binding and setting is unchanged. The
  calibrated Capture hardware key is 106.
- Receiver121/121 and signed build pass. The user confirmed that pressing
  Capture during Mario gameplay restores the latest checkpoint.
  [Capture handoff](session-handoffs/2026-11-09-capture-checkpoint-binding.md).

## Installed receiver 0.3.23: five checkpoints and controller quick load

- Manual saves retain the newest five checkpoints per game. Pause menu
  **Checkpoint history** offers dated older saves; loading does not consume or
  reorder history. The existing single checkpoint is preserved and imported
  before the first new save. Automatic resume remains separate.
- **Controllers → NES button assignments → Load latest checkpoint** can be
  assigned to an editable button and explicitly saved. Player 1 triggers it once
  per fresh press; held controls must return to neutral after loading. Existing
  mappings, retrigger behavior and LEDs remain intact.
- All 121 unit tests, signed release build and isolated Android integration
  pass, including six saves retaining five, older/latest restore and scroll
  navigation. Installed with app data preserved; Mario is paused at its menu
  with one existing checkpoint and no reported error. Physical shortcut
  acceptance remains pending; phone stays 0.4.35. No public release.
  [Checkpoint handoff](session-handoffs/2026-11-09-checkpoint-history-shortcut.md).

## Installed receiver 0.3.22: overlapping NES button presses

- A fresh press on another control assigned to an already-held NES button now
  releases that virtual button for one emulated frame, then restores the held
  state. Releasing one binding preserves the others; the final release clears
  the button and pending pulses. Rapid taps queue with a held frame between them.
- Logical press edges travel separately from the aggregate held mask, so Android
  key repeats and key/axis aliases do not retrigger. Menu and volume actions are
  excluded. Pause, focus loss and disconnect cancel pending pulses.
- All 114 receiver unit tests pass, including 16 new scheduler/profile tests;
  the signed release APK is installed with app data preserved. Mario reopened
  and reached 3,687 native frames without a reported error. `games.status`
  recorded 45 retriggered presses consumed by the emulator. The user then
  confirmed that holding B to run and tapping the duplicate B trigger fires
  successfully while running.
  Phone remains 0.4.35; controller mappings and LED implementation are unchanged.
  [Retrigger handoff](session-handoffs/2026-11-09-nes-duplicate-button-retrigger.md).

## Installed receiver 0.3.21: physical navigation and steady player light accepted

- User confirms NES gameplay works on the projector and receiver/setup navigation
  now works after 0.3.20. The cause was app-translated directions bypassing Android's
  ViewRootImpl focus-navigation stage. `ControllerNavigation` now moves focus and
  scrolls explicitly; physical diagnostics recorded focus moving between buttons.
- Receiver 0.3.21/code35 is installed with data preserved. NES assignments now
  include Volume down/up and a **Use ZL / ZR for volume** preset. New profiles
  default to trigger volume; existing saved assignments are preserved until Save.
  Game volume actions are edge-triggered and never enter the NES button mask.
- The first Android HID-host reflection call was blocked by the actual firmware.
  The Android-9-only fallback binds the existing exported system HID service,
  checks its Binder descriptor and normal Bluetooth permissions, then sends the
  verified Nintendo steady-player-light report. API state is `sentUnconfirmed`
  because no hardware ACK is decoded, but the user explicitly confirmed that
  Player 1's light is now steady. No
  driver, pairing, input-report mode or hidden-API policy change was made.
- All 98 unit tests and the signed release build pass. Phone remains 0.4.35.
  [Navigation and HID handoff](session-handoffs/2026-11-09-controller-navigation-hid-lights.md).

## Installed controller calibration: receiver 0.3.19 / phone 0.4.35

- Receiver 0.3.19/code33 is installed in place. It adds a Pro Controller diagram,
  live test, guided 22-step calibration, explicit hardware-profile save/discard,
  separate NES assignments and both-stick navigation across Home/Games/player.
  Plus toggles playback; shoulders seek and triggers adjust volume. Controller
  profiles reload on Activity resume, with release and callback guards.
- Player slots 1–4 persist separately. The physical projector reports the paired
  Pro Controller connected and assigned to Player 1; other slots are empty.
  Only Player 1 feeds the current NES port. Physical player LEDs remain unchanged:
  Android 9 lacks the supported light API, and an OEM HID output path is unverified.
- All 94 receiver unit tests and the signed optimized build pass. Isolated Android
  checks confirmed game launch, menu, unmapped-key isolation, Home, media
  play/pause, both seek directions and both volume triggers. A final direct
  AudioManager volume fix is included in the installed build. After returning
  through Home, the physical setup screenshot succeeded and was visually checked:
  connected Pro Controller, Player 1, saved profile present, and live stick input.
  Full button/calibration correctness and physical media/NES acceptance still
  need user confirmation; a saved profile alone does not prove all mappings.
- [Controller handoff](session-handoffs/2026-11-09-controller-calibration.md).

## Installed Games baseline: phone 0.4.35 / receiver 0.3.18

- Live follow-up: both production-signed candidates are installed with data
  preserved. Projector discovery found a changed LAN address; the existing bearer
  pairing remained valid. The supplied ROM is in the projector library and
  phone Downloads. Actual Android 9/ARM32 runtime reached 2,814 frames with no
  reported error; its pause menu held the frame count stable. The real phone Games
  sheet confirmed the ROM on both devices. The user paired the full-size Pro
  Controller and accepted receiver navigation; NES picture/audio acceptance and
  final calibrated buttons remain pending.
- Added projector-local NES playback using pinned QuickNES engine source,
  controller navigation throughout the receiver, Bluetooth settings entry,
  Games library and automatic/manual save states. Android owns pairing; physical
  calibrated Nintendo mapping, sound and projector performance remain pending.
- Phone imports `.nes` through its document picker and compatible external
  VIEW/SEND intents, keeps private hash-identified copies, and synchronizes
  missing ROMs both directions over the existing paired connection. Saves stay
  on the projector. SNES/GBA are future work, not advertised capabilities.
- Receiver unit tests pass 65/65. The supplied private Mario Tupi ROM passes
  3,600 native frames, generated audio, deterministic save replay and ASAN.
  Signed optimized receiver APK passed an isolated Android emulator integration
  covering authentication, upload/download, native gameplay, pause/checkpoints,
  automatic resume and process restart. Phone browser70/70 and storage/HTTP-sync
  checks pass; actual Android picker import and paired library sync were verified
  on the isolated emulator. Final compact list/Home button layouts were visually
  checked. The initial preparation was local only; the later live installation
  above is now verified. No public update feed was changed.
- [Implementation and acceptance boundaries](games.md),
  [handoff](session-handoffs/2026-11-09-projector-nes-games.md).

## Phone 0.4.34 and receiver 0.3.17 live: Cat audio-path verification

- Receiver 0.3.17/code31 is installed on the Kodak projector. Its existing Home
  grid still reads all enabled launchable apps from Android, including the
  previously live-confirmed Crunchyroll entry. It now also has an authenticated,
  projector-native **Cat Mode**: cyan/yellow-green moth, mouse and bug targets
  dart, quiver, hide and rest on a dark field, with sparse generated low-volume
  chirps. After the user could not hear the first build, audio was changed from
  a delayed roughly -27.5 dBFS effective peak to an immediate two-part call,
  about 12–14 dB stronger, followed by brief calls every 4–10 seconds through
  the media stream, but the user confirmed this still produced exactly zero
  output. Receiver 0.3.17/code31 replaces the likely unsupported 24 kHz static
  track with checked 48 kHz `MODE_STREAM`, native minimum buffering,
  playback-head diagnostics and a system media-tone fallback. A session ends
  after 12 minutes; Back stops it immediately.
- Phone 0.4.34/code44 is installed on the Samsung. The user physically accepted
  the calibrated orientation approach in arbitrary grips and asked for more
  travel. The default now uses the existing High preset (20° horizontal / 12°
  vertical rather than 26° / 16°), about 23–25% less rotation for full travel,
  while retaining the dead zone, hysteresis, bounded stepping and smoothing.
- **More → Start cat mode** starts autonomous play. While Air Mouse is active,
  its filtered normalized pointer controls the toy and OK triggers a pounce;
  Air off sends an explicit release so autonomy resumes. A 1.5-second receiver
  lease also restores autonomy if the phone or network disappears. A dropped Cat
  sample does not tear down the calibrated Air Mouse session.
- Receiver tests pass 48/48, the complete phone browser suite passes 68/68, host
  Air Mouse and native-boundary checks pass, phone main/AndroidTest Java compile,
  and both signed release builds pass. Live acceptance confirmed receiver
  capability plus autonomous start, manual pointer, pounce, release and stop;
  that first live state was stopped. Package/device reads confirm phone
  0.4.34/code44 and receiver 0.3.17/code31. Projector media volume was 8/15 and
  unmuted, confirming the earlier missing audio path was not a quiet/muted
  stream. After the user restored the receiver, the signed APK installed and a
  live Cat start reported `pcm-stream`, 48,000 Hz, 10,560 samples written and
  all 10,560 frames played. Cat Mode remains running for audible/user acceptance;
  no public release was published.

## Installed phone 0.4.32 / receiver 0.3.13: live Backup HLS cast accepted

- The user authorized a Cast-time live-media fallback for the already playing
  cross-origin Backup iframe. The visible Browse WebView now keeps at most 48
  in-memory GET candidates for five minutes and only for the exact current
  FrameXTV page generation. It never retains bodies, cookies, authorization,
  popup/main-frame requests or obvious segments/subtitles/static assets, and it
  clears the ring on navigation and Activity destruction.
- Existing same-origin DOM capture still wins. Only `SOURCE_UNAVAILABLE` may use
  the observed fallback after the explicit Cast gesture; DRM/capture failures do
  not. The verifier re-fetches at most eight ranked public HTTPS candidates
  without browser cookies, follows at most four HTTPS redirects, rejects private
  DNS answers, HTML/JSON, unsupported bodies and obvious HLS/DASH DRM, and
  requires HLS/DASH/container evidence before returning a normalized MIME.
- `Referer`, `Origin` and `User-Agent` are the complete optional header allowlist
  from WebView observation through coordinator, phone native Open, remembered
  remote metadata and receiver ExoPlayer. Cookie, Authorization,
  Proxy-Authorization and unknown receiver headers are rejected. Prepared-source
  identity now includes MIME and these normalized headers.
- Receiver parsing no longer defaults an undeclared extensionless URL to MP4.
  Known extensions or an explicit supported MIME are required, so an iframe/page
  URL fails as `INVALID_MEDIA` rather than surfacing a misleading container error.
- Physical acceptance used the already reported Avatar S1E11 Backup page. After
  reinstall/reload, the page resumed visibly, the native Cast attempt logged
  `verified observed format=hls` and progressed captured -> waiting -> opening ->
  remote -> ready. Authenticated projector state on receiver 0.3.13/code27 then
  reported active/ready playback for canonical `framextv:tv:284833:1:11`, duration
  1360.818 seconds, advancing position/buffer and no error. Phone 0.4.32/code42
  showed the live remote with the same 22:40 duration. Receiver state also
  confirmed seekability, stereo AAC and a 20-second RAM lead. It reported zero
  subtitle tracks for this Backup HLS, so this acceptance is video/audio/control,
  not Backup-caption support. Raw media URLs and tokens were not retained in
  documentation or logs.
- This proves the current standard HLS Backup case, not every provider. The first
  version does not copy browser cookies, bypass access controls, scrape arbitrary
  subtitles, pause the cross-origin sender, correlate a manifest to multiple
  observed segments or probe from the projector before Open. Ambiguous/ad/DRM,
  service-worker/blob and IP-bound sources may still fail closed or need a later
  playback witness/projector-side probe. Public update feeds remain unchanged.

## Installed phone 0.4.31 / receiver 0.3.12: live Browse recovery and subtitle-loop repair

- Physical evidence corrects the initial interpretation of the phone's
  `net::ERR_FAILED`. The cached outer watch page stayed visible while its
  same-origin player iframe failed. On that same phone, Android's ordinary
  resolver could no longer resolve the previously failed apex `.cc`/`.xyz`
  names even though Wi-Fi advertised validated `8.8.8.8`/`8.8.4.4`; Google DoH
  returned current records, DoH-forced HTTPS reached the site with valid TLS,
  and the fresh `www` name resolved normally. This isolates stale negative DNS
  entries retained after the router change, not a current ISP-IP/WAF/TLS block.
- Sending Android's DNS-cache-clear broadcast made ordinary phone HTTPS return
  200 for both the site root and exact embed route. Reloading the unchanged
  watch page immediately restored the real player at its saved position and it
  remained visible after the anti-debug timer window. This is a causal physical
  recovery of the reported iframe failure; no APK or router setting changed.
- Separate clean automated Chromium evidence still shows the shipped
  `disable-devtool` bundle aborting requests and navigating page contexts to
  `about:blank`. Phone 0.4.31 keeps an already loaded HTTPS Browse page when JavaScript
  tries to replace its main frame with exactly `about:blank`. It still permits
  initial/app-owned and child-frame blank pages, rejects other non-HTTPS Browse
  navigation and does not inject or spoof page JavaScript. The post-flush
  physical player did not reproduce child-frame blanking, so automation alone
  does not establish that as the current phone failure.
- Fresh unauthenticated API probes expose a secondary upstream reliability risk:
  all allowed API origins reported `no_streams` for this exact later episode,
  while an early-episode control returned a source. One provider's direct lookup
  returned a real 404 without CORS, which Chromium represents as generic
  `net::ERR_FAILED`. The phone nevertheless resumed its already cached playable
  source after DNS recovery, so this provider state is not claimed as the cause
  of the recovered physical incident.
- The Philips paralysis matched two redundant status loops plus unconditional
  subtitle-list replacement. Native service state already arrives every 2.5
  seconds, while the page separately issued a timeout-sensitive three-second
  poll and rebuilt every subtitle button on each snapshot. The redundant poll
  is removed and equal subtitle state preserves the actual button nodes, so a
  touch cannot be detached between press and release.
- Public subtitle choices again use the phone's bounded fetch/normalization and
  confirmed LAN delivery before Add. Only a validated subtitle belonging to the
  exact phone-hosted private local-media origin stays direct. This avoids making
  the Android 9 projector resolve/fetch public caption providers itself.
- Production-signed phone 0.4.31/code41 and receiver 0.3.12/code26 APKs build and
  match the established release certificate. Focused handoff tests pass 82/82,
  focused browser regressions pass 4/4 and the site build passes. A broader Node
  run was stopped after hanging for four minutes in `luma-controller` server
  teardown, so no new full-suite claim is made.
- Receiver 0.3.12/code26 is installed on the physical Android 9 projector and
  its live info endpoint confirms the version and attached UI. Wireless ADB was
  re-paired to the Samsung and phone 0.4.31/code41 installed successfully with
  existing app data preserved. Package-manager verification confirms the exact
  version, and a post-install screen capture shows the foreground LUMA Library
  rendered normally. The exact watch page and embedded player now have physical
  post-flush acceptance; Cast and caption selection still need user-visible
  acceptance. Public update feeds are unchanged.

## Prepared 0.4.30 / receiver 0.3.12: truthful disk UI, DNS diagnosis and subtitle isolation

- Phone and laptop remotes now show RAM ahead separately from current-title disk
  progress. Disk-ahead time is derived from the contiguous bytes actually cached
  at the current playhead, not the planned rolling-window end. Waiting, filling,
  complete/window-ready, clearing, low-storage and error states remain visible;
  Stop clears stale status.
- The reported Wi-Fi-only watch-page failure is DNS suppression by the two
  ISP-provided resolvers. They answer ordinary hosts and `api.framextv.tech` but
  time out for the `.cc`/`.xyz` FramexTV names over UDP and TCP. Public resolvers
  answer them, and certificate-verified HTTPS to both current Cloudflare IPv4
  edges succeeds over the same Wi-Fi when only DNS is bypassed. This rules out
  source-IP/WAF, TLS and routing blocks. Router DNS should be changed to a public
  resolver; the app must not pin rotating Cloudflare addresses.
- The current upstream English tracks for the reported episode are distinct from
  the prior episode and contain episode-specific later dialogue. The first roughly
  47 seconds are an intentional recap. The installed receiver was offline, so the
  exact previously rendered cached body could not be recovered and the incident
  itself is not claimed reproduced.
- Three real stale-state defects are fixed: receiver caption files are now keyed
  by canonical content/local-media identity, URL and effective headers with a
  six-hour TTL; phone resolved media plus tracks expire after six hours and must
  carry the exact requested canonical episode/anime edition; and asynchronous
  Next work is generation/source/content guarded before it can open. Next's
  caller guard and enqueue now occur in one receiver main-thread turn.
- Production-signed phone 0.4.30/code40 and receiver 0.3.12/code26 APKs build and
  verify with the established certificate. Receiver unit tests pass 33/33; Node
  passes 262/262 and browser checks pass 174/174. Phone main/AndroidTest compile,
  but runtime instrumentation did not run because no emulator/phone was connected.
  The projector endpoint was offline and wireless ADB discovery found the Samsung
  service but its endpoint refused connection, so neither APK was installed and
  public feeds remain unchanged.

## Live 0.4.29 local-subtitle candidate

Public latest remains 0.4.23/code33. The 0.4.24-0.4.28 labels below were local
planning iterations, never public releases, but the physical phone already had
0.4.28/code38 installed. After the user reported that the laptop's 1.5-second
poll erased a pending subtitle choice and post-cast phones saw no receiver-owned
tracks, the release was stopped before commit/publication. The laptop now keeps
the user's pending choice until Apply, and phone service polling imports external
subtitle tracks/selections for opaque `local:` playback only.

Signed phone 0.4.29/code39 was installed over the physical Samsung with data
preserved and brought to the foreground. Signed receiver 0.3.7/code21 was built,
deployed through the paired projector channel and confirmed installed at
2026-10-31T01:12:11.609Z. Focused laptop browser regression and phone compilation
passed; the broader emulator service test was deliberately skipped at the user's
request in favor of immediate live movie testing. The public release and both
auto-update feeds remain unchanged pending that live acceptance.

The user's `Never.After.Dark` MKV then exposed a separate compatibility-mode
problem. Its real container duration is 6292.832 seconds, video is H.264 High
1920x800, and it has Japanese plus English E-AC-3 audio and three embedded
English SubRip tracks (Forced, full, SDH). Auto had rejected E-AC-3, converted
the file to a growing HLS event playlist, and dropped all embedded subtitles;
the receiver therefore reported only generated duration and could not retain a
seek beyond it. Auto now treats H.264 with AC-3/E-AC-3 as direct-compatible and
extracts supported embedded text tracks for receiver rendering, preferring full
dialogue over forced/SDH. A real full-English extraction produced 505 cues.

The old laptop process was replaced and the exact file reopened through the new
browser remote. Live authenticated receiver state reports fixed duration
6292.832, `canSeek:true`, direct Matroska, full English external track 512 loaded
and enabled, plus the three embedded and three receiver-rendered English choices.
The user then watched the entire movie and confirmed working AAC sound,
subtitles and seeking with substantially improved buffering. This is physical
full-length acceptance for that exact file. The laptop server was stopped after
the movie and a loopback refusal confirmed it is no longer listening.

## Prepared 0.4.28: manual stream-source pages

- Remote now shows **Change stream** whenever the active movie/episode has
  canonical frameXTV identity. It opens the exact `/watch/...` page in the
  app-owned browsing WebView without sending Stop or Open to either receiver;
  the current projector video keeps playing until another captured source is
  deliberately cast.
- Library detail exposes both the selected movie/episode player page and its
  title page. Ordinary TV uses the verified `/tv/{id}?season={season}` route,
  movies use `/movie/{id}`, and anime uses its retained `/anime/{anilistId}`
  route. The UI directs the user to the episode player's server menu and then
  the existing Cast action.
- Phone 0.4.28/code38 and receiver 0.3.7/code21 signed artifacts are prepared
  under ignored `.local/releases/v0.4.28`; nothing was installed or published.
  Node passes 253/253, the two complete phone UI suites pass 100/100, the
  focused new flow passes 2/2, the site build passes, and Android debug plus
  signed release/lint-vital builds pass.

## Prepared receiver 0.3.7: observable Home setup

- A real hard-start attempt did not launch the receiver. While the projector's
  saved address was present, receiver port 4191 and native port 8121 both
  refused connections; no LUMA discovery reply arrived. After the user manually
  opened the app, unauthenticated `info` confirmed receiver 0.3.6/code 20 and an
  authenticated snapshot confirmed its Activity UI was attached. This is a
  failed cold-start acceptance, not proof of a delayed successful boot.
- The connected Samsung is running phone 0.4.27/code 37. Its foreground
  `ConnectionService` is active; filtered live logs reported `connected=true`
  and a 20:45:53 cast handoff advanced through captured/waiting/opening to a
  verified active session and accepted remote metadata.
- Receiver 0.3.7/code 21 now counts distinct initial OK/Enter presses (ignoring
  key-up and held-key repeats), records Home-settings launches and their source,
  reports current default-Home/UI state plus timestamps and counters in `info`,
  displays the OK count in the idle footer, and confirms an OK press on screen
  when it is already the Home app.
- Receiver unit/debug and signed release builds pass; the packaged APK is
  0.3.7/code 21 with valid v1/v2 signatures. It was deliberately not installed
  because the phone reported an active viewing session. Existing 0.3.6 cannot
  reconstruct earlier key presses, so counters begin after the 0.3.7 upgrade.

## Prepared receiver 0.3.6: projector Home app

- `MainActivity` is now an explicit Android `HOME`/`DEFAULT` candidate while
  remaining available through the ordinary and TV launchers. Once the user
  selects **LUMA Receiver** as the projector's default Home app, Android itself
  opens it in the foreground during startup and returns to it for the Home key.
- The receiver idle screen says when setup is needed. OK opens Android's Home-app
  settings; Menu always reopens those settings so the choice is reversible.
  Android does not allow an ordinary sideloaded app to silently choose itself as
  default, so this one-time projector confirmation is intentional.
- The existing `BOOT_COMPLETED` receiver and sticky foreground service still
  start port 4191 independently, preserving receiver-first casting even before
  the native LUMA/EShare service is ready.
- Receiver 0.3.6/code 20 had a signed release APK. The full Node suite passed
  253/253, receiver unit tests/debug assembly pass, release assembly and
  lint-vital pass, and the packaged manifest contains the separate launcher and
  Home filters. Full debug lint still reports 14 pre-existing min-SDK findings
  in unrelated receiver files. It is now installed and manually opens, but the
  first reported hard-start acceptance failed; default-Home selection was not
  remotely observable in this version.

## Prepared 0.4.27: episode-aware anime Sub/Dub switching

- The Subtitles sheet now shows a two-state **Sub / Dub** audio selector only
  when the alternate edition has been resolved and verified for the exact anime
  episode. Ordinary movies/TV and anime episodes without an alternate edition
  keep the existing subtitle-only UI.
- Availability is a non-mutating native probe. It validates canonical TMDB,
  AniList episode and requested audio identity, caches the resolved stream, and
  treats fixed-host 404/410 responses as `SOURCE_UNAVAILABLE` only after trying
  every fixed anime API origin.
- Tapping the alternate edition uses the existing guarded catalog/handoff path,
  replaces the video/audio source and subtitle catalog, and seeks the new stream
  to the currently observed playback position. Lookup failure leaves the current
  video playing.
- Phone 0.4.27/code37 and receiver 0.3.5/code19 signed APKs build and their
  package metadata/signature were verified. Node 252/252, browser 169/169,
  Android/AndroidTest compilation and emulator catalog/history/Library/TV
  catalog/web groups 11/11, 7/7, 9/9, 5/5 and 4/4 pass. No real device was
  installed or controlled. Full debug lint still reports six independent API-24
  findings in `ResolvedSources`/`ThumbnailStrip`; release lint-vital passes.

## Prepared 0.4.26: Library deletion, title shortcut and wrong-server guard

- Long-pressing a saved episode or title now reveals an explicit delete action.
  Deletions create synchronized tombstones on the phone and receiver so stale
  multi-phone progress cannot immediately restore a removed episode or series.
- Tapping the current title in Remote opens that exact Library title; for TV it
  selects the episode currently playing. Existing version-one Library databases
  upgrade in place before tombstones are written.
- A live `Buddy` cast exposed a Films365 source path naming the distinct movie
  `Buddy Games`; changing the site's server supplied the correct movie. The
  native resolver now treats that conflicting provider-owned title slug as
  positive mismatch evidence, refuses to cache/open it and tries up to eight
  declared providers within the existing shared deadline, preferring available
  providers and skipping the rejected one. Recasting also discards an older
  cached source with the same provable mismatch.
- The embedded-site capture path rejects the same mismatch with a specific
  `SOURCE_MISMATCH` result. Opaque provider URLs remain accepted: their contents
  cannot be identified safely before playback, so the app does not guess from
  filename, runtime or subtitles.
- Phone 0.4.26/code36 and receiver 0.3.5/code19 signed release APKs build and
  their package metadata was verified, but they are not installed or published.
  Browser 167/167 and emulator catalog/history/Library/TV catalog/web groups
  10/10, 7/7, 9/9, 5/5 and 4/4 pass. Real-device recast/provider fallback and
  synchronized deletion remain physical acceptance checks.

## Local dual-device install target

- `make install-latest-apks` now resolves duplicate ADB transports itself. It
  probes each authorized transport's hardware serial and installed LUMA package,
  groups Bonjour aliases for the same physical Samsung, excludes emulators and
  receiver-only devices, and installs through the preferred stable transport.
  It still fails closed for two genuinely different eligible phones unless
  `ANDROID_SERIAL` names one explicitly. The live resolver collapsed the current
  two Samsung entries to one phone without installing anything; focused 5/5 and
  full Node 252/252 tests pass.
- The first real `make install-latest-apks` run exposed that the existing Gradle
  outputs were stale: their metadata was phone 0.4.23/code33 and receiver
  0.3.3/code17, exactly matching the unchanged physical installs. The source
  declares 0.4.24/code34 and 0.3.4/code18.
- The target now rebuilds both signed release APKs from current source before
  running `adb install -r` for the phone and the existing receiver dev deployment
  command for the projector. It has no update-manifest or `.local/releases/`
  dependency and does not publish, bump versions or clear phone data.

## Prepared 0.4.25: audited anime identity, sub/dub and correct Next

- The failing physical state labeled *The World's Strongest Witch* S1E2 but its
  source URL named *Arifureta* and it had neither `animeId` nor `animeAudio`.
  This was not merely stale display data: capture had lost anime provenance and
  Next therefore called the ordinary TV resolver, which returned that unrelated
  stream and subtitle catalog.
- Public site code and live fixed APIs were audited across *The World's
  Strongest Witch*, *Frieren*, *Solo Leveling* and *Demon Slayer*, with sub and
  dub responses. The outer `/watch/{tmdb}/{season}/{episode}` route can still be
  visible while its same-origin `/embed/anime` iframe is already playing. Anime
  seasons can also use distinct AniList IDs, and sub/dub availability, provider
  and subtitle catalogs genuinely differ by title and episode.
- Capture now retains the iframe's exact AniList ID, anime episode and audio
  edition during that redirect race. Native mapping verifies the AniZip TMDB,
  canonical season/episode and related-season relationship before using them.
  The stream response must repeat the requested AniList ID and audio edition;
  a mismatch is rejected instead of being labeled as the requested episode.
- The chosen `sub`/`dub` edition is read from the selected same-origin player
  iframe (the top-level URL does not change), persisted per series, and carried
  through Resume, Library replay, notification/lock-screen Next, phone-side
  Next, receiver-side Next and auto-play. Live API checks confirmed both the
  episode-3 subtitle and dub sources for this title.
- Anime resolution uses `/anime/api/stream`; it never falls through to the
  ordinary TV stream endpoint. Resolved-source cache keys now separate ordinary
  TV, each AniList season, sub and dub. Cross-season Next first resolves the
  related AniList season ID. Future air-dated episodes remain unavailable.
- Library details now sends the native store's exact `{kind,id}` schema; local
  anime provenance is retained for replay rather than sent as rejected extra
  fields. This fixes the observed `LIBRARY_INVALID_INPUT` and empty episode view.
- Phone 0.4.25/code35 is prepared with `release-notes/0.4.25.txt`; receiver
  0.3.4/code18 was already installed and needs no additional change for this
  correction. The Samsung advertises wireless ADB but refuses the connection,
  so 0.4.25 is not yet installed. No real anime playback claim is made.
- Validation passed after the final change: focused browser 63/63 and complete
  browser 166/166; Android/AndroidTest compilation; emulator catalog 9/9, watch
  history 7/7, Library 7/7, TV catalog 5/5 and Web content 4/4. The signed
  release build passed and APK metadata confirms phone 0.4.25/code35.

## Released 0.4.23: receiver-first cold start and disconnected casting

- A physical check showed LUMA Receiver 0.3.2 already answering authenticated
  requests at `192.168.68.55:4191` immediately after projector startup, while
  the phone UI treated the saved but temporarily disconnected projector as
  unavailable. Library casting therefore stopped before its existing bounded
  reconnect/wake handoff could run.
- A saved LUMA target now remains selectable while offline. Cast enters the
  handoff, restores the encrypted receiver host/token in a fresh Android service
  process, connects directly to the receiver, and treats unavailable native
  EShare launch as an optional wake hint. Hardware controls still require their
  real live connection and authentication failures still return to pairing.
- Receiver 0.3.3 starts its foreground listener and brings `MainActivity` to
  the front for both `BOOT_COMPLETED` and package replacement. The release gate
  passed all 164 browser tests, receiver unit tests and phone AndroidTest service
  instrumentation 19/19 (after two unrelated timing-flake reruns).
- Published phone 0.4.23/code 33 and receiver 0.3.3/code 17 from
  `224a878b7a17c96dd97c0547d3e44c0953899dcb`. The exact 1,134,601-byte receiver
  APK (`3250643ef08f221c6176b3b0775f57bd3caef9ac6cc7b626f0c57c11f26bcafb`)
  was downloaded through the receiver's signed public updater, confirmed over
  EShare and installed on the physical projector. It returned healthy on port
  4191, and `app.snapshot` proved the foreground activity was attached after
  replacement. A true power-off cold boot remains the final startup acceptance
  check. No phone ADB endpoint was available, so the Samsung still needs to
  accept the normal in-app 0.4.23 update before the phone-side cast repair is
  physically exercised.

## Released 0.4.22: stable Philips scrubbing and restart reconnect

- A live phone capture on 0.4.20 reproduced the reported blinking while a
  Philips video was scrubbed backward. Seek used the generic command path,
  which marked the entire app busy and redrew/disabled the remote until each
  TV acknowledgement returned. Repeated drags could also be rejected by that
  global busy guard.
- Philips seeking now has a source-guarded, optimistic queue independent of
  global app busy state. It sends the current target and coalesces all further
  drags to the latest unsent target, so the controls remain stable and usable
  while the TV answers. A browser regression holds the first seek response and
  proves that only the current and latest positions are sent.
- The first 0.4.21 install exposed a separate restart regression: an already
  approved TV at the exact saved peer was incorrectly required to have a new
  pairing window before `/msx/start.json` could reload. Bootstrap now admits
  that exact peer, continues to stage a different private peer for identity
  proof, and requires an open setup window only for a genuinely unpaired TV.
  The isolated receiver fixture passes 8/8, including saved approval surviving
  restart and explicit reset revoking it.
- The release gate passed Node 247/247 and browser 163/163. LUMA Remote
  0.4.22/code 32 was published from `6ee4a17803e234050f15820f2378a3f3ca79804e`.
  Its 9,822,082-byte APK has SHA-256
  `d21683525250e34cd83b7f96d82f4d303e0ab0aee34b424c8cf490fde769007b` and
  is installed in place on the Samsung. The foreground service is running, but
  the locked phone prevented a post-install screen capture and the TV made no
  fresh receiver request during the observation window. One real backward
  scrub and automatic reconnect after unlock remain physical acceptance checks.

## Philips authenticated connection-peer migration candidate

- Post-0.4.18 physical logging still showed HTTP403 `PEER_REJECTED`. The log
  intentionally omitted address values, which made the earlier Matter-address
  and timed migration changes hypothesis-driven rather than directly observable.
- The next candidate no longer predicts the browser peer from Matter. A new
  private peer reaching `/tv/connect.html` may load the receiver shell for 60
  seconds, but the saved TV browser must present the exact previously approved
  receiver client ID in `hello` before the phone persists that peer. The old
  approved peer remains unchanged until identity proof succeeds.
- Live logs now record `PEER_CANDIDATE incoming=… saved=…` and
  `PEER_MIGRATED from=… to=…`. Android app and AndroidTest sources compile.
  Instrumentation covers candidate redirect, no pre-proof mutation and
  identity-gated migration, but it was not run on the physical phone because it
  resets receiver pairing state.
- The first 0.4.19 physical trace made the mismatch concrete: incoming Philips
  `192.168.68.52`, saved peer `192.168.68.54` (the phone). Candidate bootstrap
  succeeded, then the browser presented a different client identity and was
  rejected. The 0.4.20 candidate converts that exact condition into an explicit
  **Allow TV** request. The old approval remains until the person approves the
  observed private peer/client pair; approval then replaces both atomically.

## Philips IP-only launcher and receiver-peer rebind candidate

- The stable TV launcher now begins with a focused, empty 15-character field
  and accepts only a private IPv4 address. Protocols, ports, paths and public
  addresses are rejected. Saved-phone probing/automatic redirect and the
  explicit open-anyway fallback remain.
- **Autofill this phone on LUMA page** uses a distinct native-owned `phone`
  choice and types only the host from the current receiver listener, such as
  `192.168.68.24`, then sends Matter Enter/Go. The full
  `http://PHONE:PORT/tv/connect.html` value remains only under the separate
  direct browser-address fallback.
- Focused launcher/setup browser tests pass 10/10, and Android app plus
  AndroidTest Java compilation passes. The actual Philips field/JSON behavior
  still needs physical confirmation after deployment.
- Real-TV follow-up showed the HTTPS image probe falling into its negative path.
  Because that probe can be browser-blocked and does not prove receiver
  reachability, its negative state now opens the same blank autofill field with
  neutral guidance instead of claiming that the phone did not answer.
- The previous launcher also suggested the wrong `192.168.1…` subnet; the
  deployed IP-only flow instead derives and types the phone's actual listener
  host (currently `192.168.68…`). A separate HTTP 403 `REQUEST_REJECTED` was
  reproduced while the listener was healthy: the saved receiver approval was
  still bound to the TV's former address. Before phone-IP autofill, 0.4.16 now
  moves an existing approval to the current private Matter-authenticated TV
  peer, preserves the receiver identity/capability, clears the stale session and
  persists the new peer. Public addresses are rejected. Android app and
  AndroidTest sources compile; physical Philips acceptance remains pending.
- Published 0.4.16/code 26 from `faa7c6f`; its 9,821,746-byte APK has SHA-256
  `54406ba6220343b273e8d764d86e11d7cf42e91b36208b214de4230226a94774`.
  It was installed in place and cold-launched on the Samsung. A fresh real-TV
  retry after installation is still required.

## Released 0.4.18: actual-peer migration, no-pointer navigation and Speed dial

- The phone-hosted receiver reached through the GitHub speed dial no longer
  requests Pointer Lock or maps hover position directly to a control. Tiny
  Philips remote movements accumulate to four pixels, select their dominant
  axis and produce one Up/Down/Left/Right focus step with a 160 ms repeat bound;
  OK activates the focused control. The cursor remains hidden.
- The focused receiver browser suite passes 11/11. Because this page is bundled
  into the phone listener, deployment requires the 0.4.17 phone build rather
  than a GitHub Pages-only change.
- Live phone logs after 0.4.16 identified the remaining white-page response as
  `PEER_REJECTED`, not a host/origin/path failure: the TV browser connected from
  a private address different from Matter's resolution. The 0.4.18 candidate
  therefore arms a 60-second migration window only after explicit phone-IP
  autofill; the actual private peer reaching `/tv/connect.html` in that window
  replaces the saved address while retaining the approved receiver identity.
  Normal mismatched peers remain rejected outside that window.
- The Philips remote now exposes **Speed dial**. It always requests the native
  Philips Internet-browser activity through saved JointSPACE permission, even
  if receiver state is already connected; acceptance still does not prove the
  TV visibly opened it. The focused UI test passes.
- Guarded release verification passed Node 247/247 and browser 162/162; Android
  app and AndroidTest compilation passed. Release 0.4.18/code 28 was published
  from `8a84bbf`; its signed 9,821,602-byte APK has SHA-256
  `276c27937666ab5c68c4d02b93f98ee77b1535a4021dd1f7b3f73bc5a6afbf37`.
  The exact APK installed in place on the Samsung and cold-launched. The bounded
  peer migration, physical Speed dial launch and directional strokes remain
  real-Philips acceptance checks.

## Released 0.4.14: Philips typing rebind and one-speed-dial setup

- A saved JointSPACE typing credential no longer dead-ends after the Matter TV
  resolves to a different private address. The same-address request remains a
  no-op error, but a different current Matter peer may start a new TV PIN
  exchange. The old credential stays saved and usable until the new grant
  succeeds; only then is it replaced.
- The setup screen explains the intended TV configuration: keep the canonical
  LUMA page as the Internet browser's only speed dial. That fixed page focuses
  its phone field and now accepts the exact native-owned direct receiver URL as
  well as an IP. **Autofill this phone on LUMA page** types that current URL and
  sends Matter Enter/Go. Direct browser-address typing remains the fallback.
- Guarded release verification passed Node 247/247 and browser 161/161; Android
  app and AndroidTest Java compilation also passed. Release 0.4.14/code 24 was
  published from `aec4bb4`; Pages commit `8e1ce499` built and the live launcher
  plus update feed were verified. The signed 9,821,742-byte APK has SHA-256
  `02d15163f0f64128f5b43e35cd32d5619fd41e6eea68ca1e0db3f685b9be9ad1`;
  it installed in place over wireless ADB and cold-launched on the Samsung. The
  Android instrumentation source covers rebind/rollback but was only compiled
  because no emulator is connected. Real TV focus, typing, Go and speed-dial
  launch remain physical acceptance checks.

## Released 0.4.13: Philips browser-only receiver setup

- When the Philips receiver is disconnected, More now says **Set up TV
  receiver** and opens setup instead of sending the known-failing browser launch
  request. When connected, it remains **Open TV receiver**.
- Setup now has one TV-browser flow: open Internet on the TV and select its URL
  field, choose **Stable address** or **Direct phone address**, then tap **Type
  and open selected address**. The same action starts the phone listener,
  requests the one-time Philips text-input PIN when needed, types the fixed URL,
  and sends Matter Enter/Go. If Enter fails, it asks for manual Go/OK.
- Stable typing is native-owned and exact:
  `https://kiansheik.io/kodaklumaremote/tv.html`. The fixed page attempts local
  `/24` prefix inference, asks only for the phone's final IP number, saves it,
  probes and auto-opens one remembered phone, and shows a chooser for multiple
  phones. Failed inference/probing leaves an editable full address and an
  open-anyway fallback. The backup remains
  `http://PHONE:PORT/tv/connect.html`. Arbitrary text remains impossible; old
  fragment links and `/msx/connect.html` remain compatibility-only.
- The only exposed phone-side direct launch remains JointSPACE
  `activities/browser`; this TV previously acknowledged it without visibly
  navigating. Its `applications` inventory is unavailable. Firmware
  `GotoOpenInternet` is a privileged in-TV bridge, not a network phone-control
  route, so no second direct-launch method is claimed.
- Verified locally: Node 247/247, browser 158/158, focused release/deploy 15/15,
  focused new setup 8/8, receiver Node 61/61, Android app and AndroidTest Java
  compilation, and JS syntax. No emulator was connected, so instrumentation was
  not run. The guarded release published source commit `6e4ca1e`, GitHub release
  `v0.4.13`, and Pages commit `6a20f13`; the live custom-domain page contains the
  new first-run UI and manifest 0.4.13/code 23. Signed APK 9,821,506 bytes,
  SHA-256 `4aacc6a636412fc880e67100f526fab5710ed84b01fa7b26ed753bf59343d479`,
  was installed in place over wireless ADB and cold-launched successfully on the
  Samsung. The real Philips flow remains untested.

## Released 0.4.12 / receiver 0.3.2: Philips parity, two-screen move/sync, brightness

- Published from source commit `bd9223f` as GitHub release `v0.4.12`; live
  manifests expose phone 0.4.12/code 22 and receiver 0.3.2/code 16. The stable TV
  launcher is live at `https://kiansheik.io/kodaklumaremote/tv.html`. The first
  Pages deployment omitted `tv.html` because `deploy-gh-pages.mjs` had not added
  the already-built file to its reviewed copy allowlist; that allowlist and its
  isolated deployment regression were corrected, then the same release was
  redeployed as Pages commit `5decdde`.

- Philips TV page (`src/msx/`) now matches LUMA Receiver: LUMA caption style
  (centre 50 %, translucent rounded panel) with phone A−/A+ size (`display`
  command, kept in TV localStorage and re-applied by the phone from its own
  copy), CSS brightness/contrast, `rate`, `notice`, `ended` state, and a scrub
  overlay fed by a `scrub` long poll on `MsxServer` (frame from the phone's
  `ThumbnailStrip`). Worker pool is now 4 fixed threads.
- Philips auto-play runs natively in `ConnectionService` (works with the phone
  locked): 10 s TV notice, Back on the TV or any phone command cancels, stops
  after three unattended episodes, keeps/remembers the caption language, uses
  `MsxServer.playCatalog` (same path as the TV Library). Setting `msx.settings`.
- More: Brightness (LUMA translucent light/dark tint above the always-direct
  SurfaceView — Media3 GL effects showed no change and both TextureView paint
  variants blacked the physical projector; Philips CSS filter). The user
  physically confirmed the final overlay is "perfect". Both targets now use
  gradual 2% steps bounded to −30…+30%; `app.picturePreferences` stores one
  value per movie or series and guarded post-cast restore shares it across all
  episodes. An unseen title starts at neutral. The signed local phone build
  0.4.11/code 21 and receiver 0.3.2/code 16 containing this follow-up are
  installed; device behavior is not yet checked. Also **Move to <other screen>**
  and **Play on both screens**
  (`src/handoff/sync.mjs`: speed nudges ≤8 % under 2 s, seek above; play/pause
  and seeks mirrored). Sync runs in the phone page, so it pauses when the phone
  sleeps; moves/sync need catalog titles (`app.chooseCatalog`).
- Library/auto-next casts no longer fail with more than 64 subtitle tracks or one
  malformed link (coordinator keeps the first 128 usable tracks).
- Resume: resolved sources cached indefinitely (60 titles) behind a 1-byte probe;
  per-title subtitle language stored natively (`app.captionPreferences`) and
  re-selected after every cast; picture preference stored the same way; receiver
  caches caption files (48 MB).
- Not physically verified: anything Philips (TV unavailable), move/sync, and
  the new per-title restore/bounds on devices. The underlying SurfaceView
  brightness rendering is now user-confirmed on the Android 9/Amlogic projector.
  API 36 emulator evidence before the final bound showed monotonic nonblack
  composition through the full range. The live HTTPS TV page is deliberately a
  launcher: it remembers phone addresses, then navigates the TV to the selected
  phone's HTTP receiver rather than attempting a blocked mixed-content request.

## Unreleased 0.4.12 / receiver 0.3.1: remote layout, fast Resume, caption language on auto-next

- Remote tab order: player card (title/status + power, seek bar with undo/redo
  icons and elapsed/duration below, −30s / Play-Pause / Next), volume row (level
  beside slider; refresh hidden on LUMA), D-pad, bottom row Subtitles /
  Auto-play switch / More. Status line no longer repeats the time; Choose video
  shows only when nothing is loaded. Compact mode under 660 px height.
- Phone `ResolvedSources` (cacheDir `resolved-sources.json`, 30 entries, 6 h)
  is used by `ConnectionService.resolveCatalogMedia`; a cached source is reused
  only after a 1-byte Range probe answers 200/206, otherwise a fresh catalog
  lookup runs. Physical Resume timing not yet measured.
- Receiver remembers `captionLanguage` (cleared by Subtitles off) and on
  auto-next/`player.next` queues up to three same-language files (exact code,
  then base language); the earliest that loads is shown
  (`PlaybackController.captionCandidates`, unit-tested).
- Verified: Node 242, browser 159, receiver unit tests; phone release installed
  via ADB; receiver 0.3.1 deployed to the projector (saved position kept).
  Not yet physically checked: new layout on the phone, Resume speed, auto-next
  captions. Projector scrub start delay noted by the user but deferred.

## Unreleased after 0.4.10: receiver-first cold-boot casting

The receiver APK already requests `BOOT_COMPLETED`, starts a `START_STICKY`
foreground service that opens port 4191, and brings `MainActivity` forward when
`player.open` arrives. The phone had nevertheless gated saved setup on the
projector's unrelated EShare/navigation socket on port 8121. It now configures
and authenticates directly to the saved private receiver address/token first,
publishes separate `receiverConnected` and `nativeConnected` readiness, and
allows casting as soon as the receiver answers. The Activity keeps retrying the
native socket every 15 s after direct readiness so navigation, apps, volume and
power arrive later without delaying playback. A native-socket loss preserves
receiver polling, playback controls and casting while removing hardware-only
volume/navigation actions.

Validation: Android debug/AndroidTest builds pass; backend 15/15 and service
18/18 emulator checks pass; the focused handoff suite passes 80/80; and the full
browser suite passes 158/158, including direct-only cold-boot casting. The full
Node aggregate was interrupted after it stopped producing output, so no fresh
aggregate Node count is claimed. The current receiver APK was locally rebuilt
and reinstalled on the physical projector as the same 0.2.3/code 10 version
(1,185,598 bytes, SHA-256 prefix `fadc21c1efd7`); it returned with a new install
timestamp and healthy idle status. The phone APK was not installed. A real
projector power-cycle still must prove that this firmware sends the boot
broadcast promptly, keeps the service alive, and exposes port 4191 before
EShare port 8121.

## Unreleased 0.4.10 / receiver 0.2.3: self-rendered captions and crash fixes

Published 0.4.9 was user-tested. Follow-up fixes (see
[receiver page](luma-receiver.md#2026-10-23-follow-up-0410--receiver-023)):
receiver-rendered captions with no rebuffer and all 63 sampled tracks parsing,
RTL repair, catalog URLs with spaces, the scrub-preview native crash,
auto-reconnect forgetting the projector, a smaller buffer and persistent
diagnostics after a projector freeze of unknown cause. Receiver 0.2.3 is on the
projector. `npm run check` and emulator backend/scrub/subtitles/service/catalog
groups pass. Release with `make deploy-gh-pages` (notes in
`release-notes/0.4.10.txt`).

## Unreleased 0.4.9: Kodi replaced by LUMA Receiver everywhere

The phone app now drives [LUMA Receiver](luma-receiver.md) (`android-receiver/`,
receiver 0.2.0 installed on the physical projector) instead of Kodi:
`receiver.*` RPCs with unchanged result shapes, PIN pairing (encrypted token,
legacy Kodi credentials deleted), receiver-side atomic source guards,
connect-before-launch casting, 2 s polling, 60 s two-way Library sync, mirrored
scrub previews and projector-persisted caption size. Releases now carry
`luma-receiver-<v>.apk` + `receiver-update.json` instead of the Kodi add-on; the
add-on, its packager and the Mac Kodi controller were removed. The phone version
is already bumped to 0.4.9/code 19 by an interrupted `make deploy-gh-pages`
(safe to rerun; it keeps the prepared version). `npm run check` passes (Node 241,
browser 157); emulator backend 14, service 18, settings 6, subtitles 9,
multi-receiver 5 and packaged UI 9 pass. No physical-phone test of 0.4.9 yet.
[Handoff](session-handoffs/2026-10-23-kodi-to-luma-receiver.md).

## Unreleased: Android emulator fixtures migrated from Kodi to LUMA Receiver

The phone's native instrumentation and `scripts/test-android-*.mjs` fixtures now
model the [LUMA Receiver](luma-receiver.md) LAN API (`/api/v1/*`, `{ok,result}` /
`{ok:false,error:{code}}`, Bearer token, pairing) instead of Kodi JSON-RPC.
Mutation assertions name receiver methods and the forwarded guard fields
(`expectedSourceUrl`, `expectedContentKey`, `onlyIfInactive`,
`expectedCurrent*`, `resumeSeconds`). On the API 36 ARM64 emulator, backend
14/14, service 18/18 (new pairing and two-way Library pull/push check), settings
6/6 (pairing token plus legacy Kodi credential deletion), subtitles 9/9 and
multi-receiver 5/5 pass with all Node wire assertions. One source fix:
`receiver.status` failures are observed again (stale/clear) after the
side-channel refactor had silenced them. Signing migration and live controls
compile only. [Handoff](session-handoffs/2026-10-23-android-receiver-fixture-migration.md).

## Published 0.4.8: cross-phone Kodi metadata and system playback controls

Android source is versioned as 0.4.8/code 18. A production-signed local candidate
was built at `.local/releases/v0.4.8/luma-remote-0.4.8.apk` (9,793,842 bytes,
SHA-256 `09b72de640663539af191a4e4f32305ced99f90d65a148b8eb3bd1d9897d3202`)
with `script.lumaremote.metadata-1.0.0.zip` (8,426 bytes, SHA-256
`a4aa9fe6513395520d8073c13177e5d44946bd7cd8c17e80540742bc71950b8e`).
The APK package/version/minSdk, sole production signer, update-manifest hash,
notes and exact five-file add-on contents were independently revalidated. Commit
`b59e15b` is on `main` and `origin/main`; GitHub release `v0.4.8` is public with
the matching APK, `update.json` and add-on ZIP, and the public Pages feed returns
0.4.8/code 18 with the exact APK hash. The APK and add-on have not yet been
installed on the physical phone or Kodi. A debug APK cannot update the
production-signed app. The user completed the private local production-signing
configuration.
`make deploy-gh-pages` is now the guarded complete-release entry point: it
auto-increments the next patch/code, checks and signs before committing, pushes
the exact source, publishes the APK, update manifest and deterministic Kodi
metadata add-on ZIP, and deploys the validated update page. Bounded,
allowlisted new text source/test/docs files auto-stage; unrelated untracked files
remain untouched, while sensitive-looking or unsupported additions fail closed.
The current local web-receiver docs, dev script, Android receiver tree and header
capture are explicit local-only exclusions and remain untracked and unstaged.
It never extracts credentials from other repositories.
One-time `make configure-release` saves the required SDK/tool/signing values in
Git-ignored `.luma-release.json` with mode 0600; subsequent releases load it
automatically, while explicit environment variables retain precedence.
`make deploy-page` preserves page-only resynchronization.

The main phone remote has a visible playback scrubber, source-guarded Back 30
and catalog-proven next episode. Back 30 is now also an explicit notification
action and standard MediaSession `ACTION_REWIND`, making it available to the
lock screen for verified active, seekable playback. The service reads fresh
Kodi status and performs a revision/source-bound absolute seek clamped at zero.
Next episode retains the exact successor and uses the existing guarded catalog
resolution flow; it never guesses an episode number or replays a stale URL. The
notification action is a direct user-initiated app `PendingIntent`. MediaSession/
lock-screen Next instead runs inside the service, rechecks the exact source and
canonical key after catalog resolution and Kodi's add-on probe, and neither opens
an Activity nor unlocks the screen in the API 36 fixture. Kodi does not provide
an atomic compare-and-open operation: an identity change observed by the final
pre-write check is rejected, but another controller could still switch playback
in the narrow gap between that read and `ExecuteAddon`/`Player.Open`.

The source-only Kodi metadata add-on starts app-selected playback with strict
canonical movie/episode metadata. A second configured phone can adopt that exact
active item, hydrate title and subtitle metadata from fixed details/subtitles
catalog endpoints without replacing the playing stream, and bind Kodi's roughly
four-second timestamp observations to its own WatchHistory and durable Library.
That restores Resume, subtitle selection, next episode and current-item details
on the joining phone. Source/content/revision guards reject observed stale work,
including a same-URL next episode updating the prior episode. Transitions revoke
old phone-hosted subtitle tickets; each phone prepares its own selected subtitle.
This does not retroactively import another phone's older viewing history. Kodi
does not expose a per-Open nonce through this adapter, so an invisible player
recreation with both the identical source and canonical key may retain a cached
external subtitle index until a discrepancy becomes observable.

Scrub input now requests a phone-only preview after a short latest-wins debounce
without seeking until commit. On Android 8.1/API 27 or newer, a bounded direct
HTTPS, unencrypted VOD HLS media playlist may yield 256 x 144 JPEG quality-55
sync frames through `MediaMetadataRetriever`. The decoder receives only bounded
in-memory playlist-segment bytes, never a remote URL. The adaptive memory-only
cache is revoked on source/content/service changes. Master, live, encrypted and
byte-range HLS, DASH and progressive sources remain unsupported. The current
five-title provider sample exposed no storyboard, image adaptation set or
trick-play manifest, so previews remain best-effort and ordinary scrubbing
continues when extraction is unsupported.

A current homepage/API profile sampled five titles and 12 EN/PT/PT-BR bodies.
Four titles exposed 42/64/51/72 tracks; one exposed none. Eleven bodies already
normalized. The failing Simpsons English track had 273 valid cues followed by
one reversed timestamp. Timed-text normalization now discards only a bounded
minority (maximum 10 and 2 percent), reports the discard count and preserves
strict failure for materially malformed files. All 12 sampled bodies now normalize.
Phone-hosted receiver-pinned SRT delivery already existed for Kodi and Philips;
the reproduced blocker was whole-file rejection, not absence of hosting. Public
subtitle downloads now resolve every redirect hop once, reject an answer set if
any address is non-public, and connect only within that approved set while
preserving the original Host, TLS SNI and certificate hostname verification.
This is tested hardening, not physical proof that captions now render reliably.

Verified checks: Node 269/269; browser 155/155, including mobile 45/45;
handoff 78/78; Android backend 13/13, service 17/17, subtitles 9/9, scrub
preview 7/7 and multi-receiver 5/5; catalog/history/Library/TV-catalog/resolver
groups 6/6/6/5/4; JVM subtitle delivery 13/13 and normalizer 25/25; and
package/release 13/13. `assembleRelease`, release lint and final artifact
verification also passed. The guarded full release check repeated the build,
Node and browser suites before commit/publication. No physical-phone, two-phone
or physical-Kodi acceptance is claimed. See the
[metadata contract](kodi-metadata-sync.md),
[preview profile](scrub-preview-profile.md), [subtitle profile](subtitle-provider-profile.md)
and [handoff](session-handoffs/2026-10-22-cross-phone-metadata-and-scrub-previews.md).

## Published0.4.6: Philips player and offline viewing library

The user confirmed actual Avatar S1E1 HLS picture, sound and Portuguese captions,
with smooth playback and 23 captured tracks. The normalized selected caption also
returned LAN GET200 with a successful receipt while video continued. These are
specific device acceptance results, not a promise for every stream or language.

The last installed phone APK is the pointer candidate SHA-256
`9d2d4a7df94f3e0e3678220671461f2c4de97af90ff63b0d82668844896fc797`,
loaded/server receiver revision `48c1b14a42`, version0.4.6/code15. Remote JavaScript
reload works. The physical remote produces pointer input: Down then OK opens the
panel, but arrows/selection on that installed candidate failed. The newer unlocked
pointer/focused-OK adapter and full catalog are source-tested, not TV-accepted.
The user stopped Philips device work for the day; do not resume it implicitly.

The user set Last source. Discrete Matter On was acknowledged while already on;
standby wake and visible browser restoration remain unverified. No power-off
acceptance test ran. Local DevView/native TV execution is not established.

The user then explicitly authorized publication. Published public0.4.6/code16
APK SHA-256 is `90349bb058c110ebd5e8798a17681be1b105c4a19d13647289d4c131e7afd790`,
size9757226 bytes, receiver revision `000c06d0d4`. The production signature,
package/version/minimum API and hash/size are verified; the diagnostic media fixture
is absent. GitHub release, both assets and the live update feed are verified. The public APK
download matches this digest. Release source/tag target is2c177b2.
This published APK has not been installed on the phone; the last device build remains code15.

The new phone **Library** keeps durable SQLite movie/series metadata and every newly
observed episode's progress, independently of the recent-history100-record limit.
It migrates genuine retained history, serves offline search/details, shows watched
state/last device, and offers explicit Resume/Start over/known next episode on the
selected Philips or Kodak/LUMA/Kodi target. Reads perform no network or playback.
Fresh source resolution is separate; URLs/tokens/media are not saved. Artwork bytes
are not cached. Earlier untracked or already-evicted viewing cannot be recovered.

Checks: Node243, Python controller21, mobile browser41+39, receiver browser11;
native catalog5/history6/offline Library6/TV catalog5/resolver4/MSX8/multi4/Matter7
pass (45groups). A separate fixed-title
live emulator capture resolved HLS with three declared tracks; its plain stream API
had returned no source. Final signed Android build/lint pass. Library, cross-device
resume and the latest remote adapter remain without real-device acceptance.
[Library contract](offline-library.md), [TV implementation](tv-remote-controls.md),
[release handoff](session-handoffs/2026-10-20-release-0.4.6.md),
[device-work checkpoint](session-handoffs/2026-10-20-tv-player-catalog-progress.md).

## Latest: user confirms LAN sample picture and sound

After a requested uninterrupted replay, the user confirmed **“yeah video and
sounds, it looked great actually.”** Picture and sound for the fixed phone-hosted
Sintel clip are now physically verified. Pause/resume and remote reload have
separate TV-report evidence below. Full-length completion, general streams,
subtitles, phone-off use and native TV execution remain unverified.
[Acceptance handoff](session-handoffs/2026-10-20-tv-picture-sound-acceptance.md).

The combined local0.4.6/code15 APK is installed with Success and saved data
preserved. Installed SHA-256:
`e0ddaf939cc648e2ad5e7c3b6f54e06a5469ceeeaa53ebc01808ddc4fc9410a4`.
Build/lint and native MSX8/multi-receiver4 groups pass;38 receiver Node,
29 mobile browser and one real Chromium check remain passing. The unchanged
loaded/server receiver revision is `d66f118d4c`; the existing TV page reconnected
without another user refresh, BOOTSTRAP/HELLO14:33:56 and POLL/STATE14:33:57.

At14:34:18 the TV requested the fixed LAN sample with GET206. Its valid Open
receipt produced **“Sample is playing”** and receiver state **Playing0:03/0:52**.
Pause acknowledged at **0:19**, a later report remained **Paused0:19**, then
Resume acknowledged and advanced to **Playing0:24**. This is actual TV-reported
playback/control proof. The subsequent replay has user-confirmed picture and
sound; full-length completion was not captured. Private evidence is
`.local/tv-hello/android-player/lan-sample-physical-proof.json` and the four
`lan-sample-{playing,paused,paused-stable,resumed}-ui.xml` files.

The public W3C delivery failed at14:26:41 with MEDIA_FAILED, media code4/
networkState3/readyState0 before metadata. The exact same bytes work over LAN,
isolating a delivery-path difference without identifying TLS or another exact
cause. This clip's codecs are not a general blocker. The fixed phone-hosted
fixture uses the approved-peer/capability route with ranges; it is not an
arbitrary media proxy or private-URL admission. Explicit `LUMA_DIAGNOSTIC_SAMPLE`
packaging verifies4372373 bytes/SHA-256
`b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`;
default builds omit it, wrong input fails, and no media is checked into Git.

Both phone feedback repairs are now installed and physically checked. Uptime
shows numeric seconds; after playback was idle, an app Reload acknowledgement
was followed by **“TV page reconnected.”**, uptime705→1s and matching d66 revision.
Evidence: `debug-final-reconnected-ui.xml`. The later requested replay was
started from the app and left uninterrupted for the user's picture/sound check.
No further manual TV refresh is requested. Real streams, subtitles and phone-off
operation remain open.
Local DevView routing is loopback-tested only; no native permission or native
process Hello World is established.
[Latest LAN sample handoff](session-handoffs/2026-10-20-lan-sample-playback.md).

## Earlier debug channel checkpoint

The user asked to carry the earlier probe's debug controls into the actual TV
receiver. Fixed ping, **LUMA debug channel ready** label and receipt-ordered
reload are implemented in the existing approved channel and collapsed phone
Developer tools. Empty native RPCs require Philips selected; reload is blocked
during active playback or pending work. Ping/label preserve media. The receiver
reports bounded uptime/ping count/capabilities and its injected revision; native
diagnostics report the packaged revision. Missing legacy fields remain compatible.

The final signed debug APK is installed with Success and saved data preserved.
Its checks pass: 38 receiver Node, 29 mobile receiver browser, one real Chromium,
8 native MSX and 4 multi-receiver groups. Tools stay hidden during PIN entry or
disconnection without reported debug state; fixture visibility/summary bounds
also account for hidden closed-details children. Packaged bytes/revision are
verified. The ignored Android-player installation receipt records
`remoteReceiverDebugControls`; use it to distinguish same-version candidates.

The user confirmed the TV page was already reloaded; one Down returned focus
from the URL bar. Actual phone Developer tools now show matching Loaded/Server
`d66f118d4c`, reported capabilities and enabled controls. A real app Ping was
acknowledged and incremented its counter0→1; the fixed label was acknowledged.
One remote Reload was acknowledged, followed by fresh receiver state with the
same revision, ping count1→0 and controls enabled again. Remote JavaScript
reload completed on the actual TV. A label ACK is not visible-text confirmation.
Private evidence includes `debug-reload-reconnected-ui.xml` under Android-player.

Two phone-only feedback repairs follow: fractional uptime was valid but its UI
formatter required an integer. It now accepts finite seconds0..315360000 and
floors the display, keeping ping counts strictly integral. Reload waiting now
clears only after a later connected snapshot has a valid loaded revision and
hello count greater than the valid receipt's baseline. An unchanged heartbeat
or revision alone is insufficient; an observed counter reset invalidates that
proof. Focused3/3 and full29/29 browser checks pass. This earlier checkpoint
preceded their combined installation and physical verification above; the
61fb673f…ccde005 debug APK has been superseded. Receiver JavaScript stays d66.

The actual W3C sample at14:26:41 failed `MEDIA_FAILED`, with media error code4,
networkState3 and readyState0, before metadata. This numeric tuple does not
identify a codec, TLS, policy or network cause. The later successful LAN
comparison is recorded above. At this checkpoint a native-only candidate was
being implemented: explicit
`LUMA_DIAGNOSTIC_SAMPLE` packaging accepts only the4372373-byte file with SHA-256
`b670602fa00934ca27c4351bb0efe7ea7a07fae57284e44226025eeed7c51254`.
Root verified default builds omit it, a wrong file fails, and opt-in output
matches those bytes. The fixed capability/approved-peer route is neither a
media proxy nor arbitrary private-URL authorization; no media is added to Git.
The later candidate native fixtures/build/install and TV-reported playback now
pass as above. Use the private receipt to distinguish installed artifacts.

Local DevView routing
is prepared/tested only on loopback; no DNS/native grant or native Hello World
is established.
[Debug-channel handoff](session-handoffs/2026-10-20-receiver-debug-channel.md).

## Installed numeric diagnostics before debug carryover

The user explicitly resumed: **“don't stop. I want to get something working”**,
and asked about local DevView. Continue in the existing Android app; the earlier
navigation deferral no longer freezes the task. TV browser remains the default,
with the exact native LAN origin plus `/msx/connect.html`, saved PIN/certificate
permission and no public redirect dependency. MSX remains secondary.

Before debug carryover, the signed local0.4.6/code15 numeric-media diagnostic build was installed on the Samsung
with `adb install -r` Success and saved data preserved. Debug/test/release builds
and lint pass;33 receiver Node checks and one real Chromium receiver
regression pass. Native MSX8/8 and multi-receiver4/4 groups pass;
earlier26/26 mobile receiver browser checks also pass.
Current artifact identity
and installation fields are in ignored
`.local/tv-hello/android-player/browser-shortcut-build-receipt.json`; use the
receipt rather than an older same-version APK hash. Its `browserHostTimerFix`
and `exceptionSafeReceiver` fields are true; `mediaDiagnostics` and
`persistentPlaybackError` are also true.

After the latest installation, the existing TV page automatically reconnected:
current-process BOOTSTRAP_READY/HELLO_READY14:08:03 and POLL_READY/STATE_READY
14:08:04 all200. The actual phone-to-TV handshake is established and survives
the native upgrade without another address-entry step. These requests used the
already loaded page; they do not prove that the new diagnostic JavaScript ran.

After an earlier helper stopped at the native website overlay without sending
the sample, a grounded ADB tap reached **Play sample** and visibly showed Starting.
This actual sample RPC returned a generic rejection. The user reported no TV
error, only no playback. The installed Google-hosted sample returned403 during
a source check; the generic receipt does not independently identify the TV-side
failure. W3C Sintel was then installed and tried through a grounded raw sample
tap. It failed with `ACK_FAILED open MEDIA_FAILED` at14:04:35. Real Mac Chromium
video/core playback of that W3C source succeeds with duration52.208s; this is not
a TV decode/playback result. Picture, sound, progressing time and successful TV
playback remain unverified.

At this earlier checkpoint, bounded nullable media diagnostics/error persistence
were installed and one TV refresh was pending. That bootstrap gap is now closed
by the matching reported debug revision and actual ping/reload proof above.
The subsequent sample supplied the numeric failure tuple; no specific cause or
successful playback follows from it.

The preceding TV refresh reached hello but no poll/state/client-error report.
Real Chromium reproduced the source fault: copying native timer functions onto
an object and invoking them as methods throws TypeError. Both player and channel
defaults now delegate through ordinary global calls, with guarded scheduling.
The real browser regression covers this host behavior; independent Chromium
execution produced hello/poll/state with no page errors. The actual TV then
recovered its handshake after the same fix; no speculative video-getter cause
is claimed.

The installed changes keep tick/loop/heartbeat retries alive after synchronous
player/request/status-callback failures and preserve a pending command receipt
without replaying its action. A first-failure report uses one of six fixed codes
independently of `player.state()`. Its capability/peer/origin-bound GET diagnostic
route and first HELLO/POLL/STATE logs never confer connection. The TV now shows
Ready only after a successful state POST.

Before this fix, the first direct LAN browser-launch request was accepted but produced no observed
new session; visible navigation caused by it was not separately confirmed. The
user confirmed they were already on the LUMA page showing **“Approve this TV in
LUMA Remote on your phone.”** At the agent's request they refreshed that current
page once. The phone then showed **“TV allowed. Waiting for receiver status.”**:
`sessionApproved:true`, `lastStateMsAgo:-1`. A fresh page contacted the phone and
saved approval was recognized, but no valid playback state has been received for
this session. New bootstrap200 and no400 were observed. The precise stall after
hello/poll remained unknown; sample playback was **not attempted**. One later
Confirm-key acknowledgement still left it waiting for state; it did not prove
field focus or a required gesture.

Read-only checks of the two documented inspector ports9222/7001 at `/json/list`
were unreachable (`URLError`). Exact TV identity was verified; a read GET of
`activities/browser` was rejected, so no current browser URL was obtained. A
bounded34.9-second Mac loopback observation left its counters unchanged; it does
not diagnose the phone receiver's missing state report.

Fresh local DevView research found no supported accountless backend mode, and the
installed catalog URL/native grants remain unknown; an owned presence-only test
and loopback-tested local routing harness are prepared, with no real DNS/router/
account change or native-process Hello World established.
[DevView findings](philips-sideload-followup.md#resumed-local-devview-review).
[Current resume handoff](session-handoffs/2026-10-20-receiver-handshake-resume.md),
[earlier browser entry history](session-handoffs/2026-10-20-browser-receiver-shortcut.md)
and [receiver contract](msx-receiver.md).

## Earlier installed candidate and discovery history

Current user authorization: **Android is now in scope** (explicitly revoked the
earlier exclusion). Continue in the existing phone app, not a separate phone
browser remote. An older local0.4.6/code15 candidate was built, signed and installed on the actual
phone with saved pairing preserved. It includes fixed sample playback, healthy
`error:null` validation, retained-page approval recovery, revised MSX instructions,
native PIN-paired **Enter TV address**, and Home/Back flanking Up. Build/lint,
25 receiver/14 Mac-proof Node fixtures and native8+4+4 groups pass. The full mobile
browser run passed54/55; its sole failure expected the deliberately replaced
corner layout. That assertion now checks Home–Up–Back inside the D-pad and passes
on a focused rerun; no UI change was needed for the failure.

Earlier PIN diagnosis: user saw the TV PIN, entered it on the phone and reported
an unclear return to the same setup screen. Live app inspection shows address
entry enabled and a successful typing acknowledgement. Source conditions imply
the PIN grant and credential save succeeded. The user confirms the TV remained
on the **LUMA Remote waiting screen**, where no MSX address field was selected;
keys being acknowledged did not establish visible insertion. No PIN re-pair or
automatic typing retry was initiated. User was asked to open/clear the MSX setup
field. Persistent pairing confirmation, contextual errors/expiry and setup-window
restart visibility are now fixed, built/linted and installed on the phone without
clearing its data. Seven focused browser cases and four native text groups pass;
independent review found no blocker. Post-install physical UI confirmation was
not obtained (app was no longer foreground); no own-package crash appeared in
the checked buffer. Actual video playback remains unverified.
Current DHCP addresses differ from older Mac hints; never treat a stale address
as TV identity. Private captures/addresses remain ignored.
[Receiver details](msx-receiver.md).

The user rejected another blind LAN retry and requested exact MSX/REA tracing.
Official0.1.167 source confirms bare `IP:port` was already valid; HTTP/HTTPS URL
prefixes and paths are invalid in the startup field. Padlock/pageHTTPS/secure2or4
select HTTPS.29 exact-declaration fixtures and focused REA parsing pass; platform
defaults are secure0, not proof of installed settings. Earlier two TLS-to-HTTP
connections are explained; two zero-byte connections remain unidentified.
Chromium112 public-insecure-to-private blocking is a source-backed inference,
not a measured TV net error. A fixed parent framework/secure query is prepared
in the probe, onscreen only, with source/origin/correlation checks;19 helper,
10 exact-contract and one framed-browser fixture pass. Actual TV result pending;
user says the earlier diagnostic tile/address is not saved. Do not ask for it
as though it were available. [MSX source report](msx-local-address-source.md).

Earlier redirect lead: public `https://httpbin.org/redirect-to?url=...` was verified
to return302 to fictional private-IP targets, with no follow. Public httpbingo
restricts destinations and rejected that test. This is a potential page-navigation
bootstrap; an MSX JSON XHR redirect still ends at the LAN target and does not
remove browser policy. It also does not supply `/msx/start.json`. No new tunnel,
short link, public deployment or actual TV navigation was performed.

Native TV/DNS work remains separate: local zero-network context probe prepared,
but DNS routing/native enableDevtools grants are unproved and unapplied. Public
DevView HTTP delivery means TLS alone cannot rule out content routing; catalog
scheme and firmware grants remain unknown. No native-process Hello World,
partner account/backend change, DNS change or publication occurred.
[Sideload follow-up](philips-sideload-followup.md).

Latest Ghidra reassessment: actual REA/Ghidra import+disassembly+decompile now
passes on an owned ELF control. Proper REA admission and independent actual
Ghidra loader/filesystem probes recognize the official ZIP but not its full
UPG; no native Philips component is extracted. Both old filesystem-magic hits
fail structural checks. Full first5MiB default-key tests on both releases fail
script detection and the reference plaintext SHA384 record comparison.
New pinned vendor U-Boot source matches the upgrade structure and explains
decrypted-segment hashes/CBC handling, with an explicit different-product/SoC
boundary. Newly acquired official TITAN gateway JS traces catalog lookup,
URL resolution and loadApp forwarding; it does not supply an absent native
binding. Gateway initialization has native listener/getter side effects and
was not loaded on TV.15 isolated gateway checks pass with fake APIs/network0.
Focused REA gateway/SDK excerpts parse5736ASTnodes/0failures; semantic coverage
unknown, no vendor-code execution.
Existing MSX loopback status responds with matching revisions, rejected0 and
no pending command/reload. Android/MSX/tunnel/global settings preserved.
[Reassessment](philips-ghidra-reassessment.md).

Latest DevView review: live public bundle re-fetched/hash matched. The client
POSTs devices/link for a server PIN, checks devices/check roughly every3s, and
on successful JSON schedules /apps after2s, then POSTs session metadata. Client
UI/cache and backend authorization are distinct;90s is a UI timer, backend TTL
and portal redeem/cookie internals are unknown.13 offline stub scenarios pass;
REA parsed2093 AST nodes from exact excerpts with semantic coverage unknown.
No pairing/account/native bridge/TV state changed. [Protocol report](philips-devview-protocol.md).

Ghidra is installed: Homebrew12.1.3 at /opt/homebrew/opt/ghidra/libexec. REA's
PATH discovery mistakes its bin/ghidraRun wrapper for an installation root.
Use GHIDRA_INSTALL_DIR=/opt/homebrew/opt/ghidra/libexec and
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home for REA.
Approved doctor verifies configured:true/available:true and JDK/native arm64
checks; no reinstall/global edits. Initial Java EPERM was sandbox-only. Do not
infer missing installation from an unconfigured provider. Firmware container
decoding is still outstanding. Actual headless control/probes are now verified
in the reassessment above. [Setup handoff](session-handoffs/2026-10-20-devview-acknowledgement.md).

Latest MSX result: user successfully opened the diagnostic through temporary
trusted HTTPS. Fresh TV pageGET/ready/heartbeat and subsequent exact ping/label
receipts verified embedded passive execution, DOM text readback and1920×1080.
Only viewport height differs from the measured Internet compatibility profile;
TCP socket APIs/public HbbTV factory remain absent, OMI registry matches.
Bootstrap Origin is HTTP MSX; HTTPS plugin still secureContext:false, consistent
with Chromium's insecure-ancestor rule. No native calls in this MSX sample.
The current startup uses this Mac/temporary tunnel; fully LAN MSX remains
unverified.18 server+1 private adapter fixtures and desktop MSX/framed checks pass.
Android and installed firmware unchanged.

Earlier steering: compare installed Media Station X with Internet. User opened
MSX and retried its existing Mac startup parameter; exact status0 reproduced.
Separate peer-pinned MSX bootstrap/home/framed-probe routes and passive sampling
are prepared. Local framed Chrome command test passes; actual TV made two
connections but no HTTP/bootstrap/plugin request, no ready report. Initial
clientErrors2/TLS-prefix0 did not identify the cause. A later controlled retry
with both padlock settings had4 expected-TV connections:2 TLS,2 empty idle
timeouts,1034bytes and0 HTTP. HTTPS reached the HTTP-only listener; no CORS
request was received. Temporary trusted-HTTPS delivery now passes real desktop MSX menu/plugin and
label ACK. Its measured v/t JSON query parameters exposed a strict-route/CORS
bug, now fixed with a bounded validator. Separate public adapter exposes no
controls/firmware; TV attempt used a fresh capability after desktop reset and passed. Routine
native constructor/object experiments are now disabled by default.

Latest 2026-10-20 request: continue REA firmware probing in parallel with
privileged TCP/vendor API investigation toward a custom remote. **Android stays
excluded.** Actual TV fixed `ping` and `set-label` commands now have matching
handler receipts; final label acknowledged only after DOM text readback. No
browser GUI navigation was needed. This uses the existing Mac listener and
TV-originated2s polling, not a newly established TV TCP listener. See
[live control and privileged-interface follow-up](philips-probe-control.md) and
[handoff](session-handoffs/2026-10-20-probe-control-round2.md).

Current Internet-page TCPServerSocket remains absent; direct-sockets policy
query unsupported. Standard external getter read returned normal search-provider
methods. Newly observed opera_omi exposes messaging/event methods plus a
constructors registry. One fixed registry getter read and standard Map iteration
revealed OIPF/HbbTV classes including CSManager, RemoteManagement and
NetworkInterface; no opaque message was sent. One final zero-argument CSManager
construction attempt failed with TypeError and no instance. Its source was only a
38-character native marker; sampled prototype had no relay methods. The documented
HbbTV typed-object route was also tested: DOM object created, factory unavailable,
local/remote relay methods absent after1.5s. No relay activation or TCP bind ran.
These are bounded context observations, not absence across all vendor app modes.

REA6.7.0 independently inventoried the complete matching current-version ZIP and
UPG hashes, with complete container-entry coverage but unknown inner format/
architecture. Current/older footer lengths equal48bytes per117/111 segments;
raw-segment/payload MD5/SHA1/SHA256/SHA384 checks matched none of the footer.
No filesystem/native executable recovered; Linux firmware engines remain
unavailable. Ghidra has since been found/configured as recorded above; the
earlier default provider report did not establish installation absence. Source captures, raw firmware and evidence stay
ignored. [Main implementation report](philips-firmware-implementation.md),
[browser source appendix](philips-browser-internals.md) and
[media function contract](philips-media-internals.md) preserve the upstream versus
actual-vendor boundary. Eighteen protocol fixtures and final isolated Chrome
checks pass. Original pairings, releases, Android and installed firmware unchanged.

The user visibly loaded our probe in Internet. Fixed server-triggered reload and
automatic source-edit refresh both subsequently produced new GET/ready reports
on the actual TV without further clicks. The same private receiver URL survives
our listener restarts. The compatibility probe then auto-deployed successfully:
reported **Chrome112.0.4147.62 / OMI4.24 / TitanOS3.0**,1920×945 viewport on a
1920×1080 screen. This differs from TITAN's TPM256L family table (Chromium122);
Vewd defines OMI as its Core version; exact vendor engine/build provenance is
unresolved. Do not infer CPU ABI from
navigator.platform or Safari/WebKit from compatibility tokens.

Actual probe exposes fetch/Promise, WebAssembly, WebSocket, MediaSource and
WebGL/WebGL2 constructors. All ten fixed codec MIME checks declare probably/MSE
support; HLS and DASH declare maybe via canPlayType. These are declarations,
not decoding/playback proof. This LAN HTTP page is not a secure context; absent
EME/VideoDecoder/service-worker/subtle-crypto exposure does not settle HTTPS or
catalog-app support. Only SmartTvA_API is present among the four bridge samples;
its bounded surface contains exit and hasCapability. The initial compatibility sample invoked no vendor method/getter; the later
fixed getter/object diagnostics are recorded above. See [browser compatibility](philips-browser-compatibility.md).

The exact TV is43PFG6910/78 on **TPM256L_V251.003.105.021**, TITAN/Linux.
The user created a Partner Portal account and awaits developer verification;
DevView enrollment/debug and a Home-launcher app remain unverified. Earlier REA
DevView inspection found enrolled app/debug bridge consumers, not their native
implementation. Focused public SDK analysis now informs browser classification
and media checks, with partial/unknown semantic coverage. Native-process Hello
World has not been established. Original pairings/Android/releases/firmware and
unrelated headers.txt are preserved. Private session evidence stays ignored.
See [latest handoff](session-handoffs/2026-10-20-browser-compatibility.md),
[execution history](philips-app-execution.md) and
[typing/remote-update evidence](session-handoffs/2026-10-20-tv-probe-live-control.md).

2026-10-18: New repository; original empty `headers.txt` preserved. Public frontend
inspection and two clean automated browser baselines are complete. The site is a
React SPA with same-origin `/embed/` HTML5/hls.js playback and **existing native
Cast code**. `disable-devtool` clears the console and can navigate to
`about:blank`; both baselines reproduced clearing/blanking, but the detector and
manual DevTools/extension-fingerprinting reports remain unisolated.

Implemented: bundled MAIN-world MV3 fallback using
only the page's existing CAF SDK; same-origin frames; declared HTTP media sources;
remote controls; navigation/load cancellation/cleanup. Preserve visible native
Cast UI. A separate ordinary HTTPS demo uses the same controller with a public
Big Buck Bunny MP4. No SDK injection, relay, auth/DRM bypass, telemetry, or media
bundling is part of the extension.

Cast/Kodi validation: build and 60 Node tests passed; browser coverage is 25
loaded-MV3 extension tests plus seven Kodi UI tests. Full check passed at 55 Node
and 32 browser tests; subsequent Node and narrow Kodi runs cover the final
media-scoped label registry and subtitle sorting. A final build and native-Cast
coexistence browser case passed after keeping copy buttons visible beside native
Cast. Cast/Kodi transports are
mocked in fixtures. Production and loopback-only test manifests are generated
from checked-in source configuration.
Live SDK smoke: Chrome 155.0.8059.39, 2026-10-18 07:15:24 America/Sao_Paulo,
HTTPS secure context, SDK ready, zero media requests; no receiver selected.
Current Google Cast sample MP4 HEAD:200, video/mp4, byte ranges, CORS `*`.
Live extension probe also confirmed execution in watch and same-origin embed with
CAF present; page blanked before any video/control appeared. It verifies actual
origin/frame injection, not live extraction or physical playback.

Live follow-up in the user's ordinary Chrome: the target player and extension
control were visible. Installed unpacked path verified as `dist/extension`.
Improved error classification was built, reloaded in Chrome and verified live;
the failure is at session establishment, not an observed media LOAD rejection.
After the user brought the TV online, native Chrome discovered **43PFG6910/78**
but disabled it for frameXTV with “Available for specific video sites”. YouTube's
own Play on TV enabled that same receiver; browser reported connected/playing
and user confirmed actual Big Buck Bunny picture and sound on TV. Pause/seek/
resume changed sender remote state; own YouTube test was stopped and returned to
local paused playback. This is app-specific playback, not generic Cast acceptance.

User's final mobile requirement: Samsung phone starts authorized media, then can
switch **fully off** while the receiver continues fetching it. Mac/extension is
only a stepping stone. User reports Samsung works in Philips “modo de
espelhamento”; Miracast is a strong hypothesis, not a captured protocol. Miracast
alone cannot meet phone-off receiver retrieval. A real Cast receiver or supported
UPnP MediaRenderer can in principle. A bounded probe with TV confirmed in normal
Home/YouTube mode found the exact target and read its SSDP device description,
but that description exposed no recognized service entries/AVTransport actions.
This does not prove the absence of DLNA in all firmware/modes, and it does not
establish a direct playback route. No media or control commands were sent.
See [TV evidence](philips-tv.md) and [mobile target](mobile-target.md).

Android receiver follow-up: user also owns an older Kodak LUMA with an app store
that blocks some apps; native YouTube/Crunchyroll/Amazon run, and YouTube is
discoverable only while open. Exact model, Android/CPU and store are unknown.
Current Kodak 350/400/450 pages advertise DLNA, which needs device confirmation.
Kodi's documented HTTP JSON-RPC `Player.Open` is the preferred programmable
receiver experiment if installable; VLC Android 3.6+ Remote access is another
candidate, with network-URL launch confirmed in released 3.6.5 source only.
AirPin and other commercial DLNA receivers are alternatives. An installable
thin receiver APK could receive LAN commands and own playback or launch a local
player. These were researched routes; Kodi now has actual target-media playback below.
See [LUMA options](luma-receiver-options.md) and [receiver interfaces](receiver-apps.md).

User subsequently installed/configured Kodi. A bounded Bonjour query discovered
its HTTP service and resolved a private address/port matching the user's report.
Authenticated connection identified Kodi 21.2. `Player.Open` of the public MP4
started receiver playback; user confirmed projector picture and sound. Pause/
resume succeeded, and later position confirmed an absolute seek to 90 seconds.
Playback advanced with the Mac controller process stopped (28-second bounded
check). `npm run kodi` serves a loopback-only UI for credentials, public sample,
direct video links and subtitle selection/loading. It sends only Kodi JSON-RPC,
with no media fetch/relay or saved credentials. Native Chrome's password
notification initially obstructed clicks, not an established app-code failure.

Actual frameXTV HLS now played on Kodi 21.2: ordinary native Chrome showed the
watch/embed player, and the extension's **Copy video link** supplied its declared
source to the controller. User confirmed episode picture and sound. A rapid
pause → seek60 → resume sequence was followed by playback stopping by itself;
the cause is unresolved. Restarted without seeking at 08:24:09, it remained
active through 08:35:28 (paused at 531/1355 seconds). Playback progressed while
the Mac controller server was absent; this is still not a full phone-off test.

**Copy subtitle list** captures every declared validated track, including the
site's hidden/custom-caption tracks. All 19 were registered in Kodi using
sequential `Player.AddSubtitle` calls and observed track-index polling. Portuguese
(Brazil) was selected/enabled; user explicitly confirmed visible subtitles
matching dialogue. Other tracks were registered, not individually render-tested.
The Mac dropdown sorts Portuguese first, English second, then other languages;
one track is visible at a time. Kodi reports generic external names, so an
in-memory alias registry associates copied metadata with receiver + SHA-256 of
the verified current source. Aliases vanish on server restart and do not rename
Kodi's own menu. Batch import is currently an API-driven test procedure, not a
controller UI feature. See [Kodi evidence](../../tests/manual-kodi.md) and the
[latest handoff](session-handoffs/2026-10-18-framextv-kodi-subtitles.md).

No generic Google Cast target-source LOAD, long-term source expiry, physical
mobile playback or full phone-off acceptance is established. TV-side generic Cast app availability is
unknown. System Settings computer access was unavailable, so macOS local-network
permission was not inspected or changed. Chrome debug internals were disabled
and were not enabled. No anti-debugging/auth/DRM bypass was performed.

LUMA remote follow-up: user requested a replacement for the slow Kodak phone
remote and eventual Kodi startup. Static REA6.7.0/JADX analysis of archived
`com.caglobal.kodakluma` v1.0.116 recovered raw TCP8121 controls, UDP48689
discovery, key/text/app-list/launch frames. New `npm run luma` serves a responsive
browser remote at127.0.0.1:4176 with a loopback-only socket bridge; an ordinary
website cannot directly use these sockets. The archived third-party APK was not
installed/executed and Codex configuration was not changed. See
[protocol/evidence](luma-remote.md).

Physical acceptance: feature/pairing replies identified Kodak Luma and no
configured password. Firmware ignores optional `getServerInfo`, which is now
omitted. Actual app inventory contained23 entries including Kodi. Browser
**Open Kodi** was user-confirmed; Kodi HTTP was refused before launch and
identified21.2 afterward. Right/Left navigation was also explicitly confirmed.
Volume status returned6/30; volume changes and text rendering are not physically
confirmed. Discovery initially discarded the versioned `ECloudBox516` signature;
after correcting the parser, the normal browser **Find projector** window found
the receiver. Search is bounded2.5seconds once its service is available; cold
boot/service startup delay has not been eliminated or benchmarked.

Controls do not change startup/default launcher.
Transport/HTTP/discovery and browser tests are recorded in the
[remote handoff](session-handoffs/2026-10-18-luma-remote.md).

Android phone sender now implemented: separate browser/local-control WebViews,
native LUMA/Kodi adapter, user-triggered frameXTV source capture, Kodi launch/
readiness, sequential all-subtitle registration, PT-BR then English selection
and combined Remote. Phone UI is no-scroll with primary playback/volume/D-pad
and separate Browse/Devices, subtitle/typing/app panels. Native per-attempt
tokens revoke queued mutations on Cancel/source change; retry distinguishes
proved-unsent subtitle adds from uncertain receiver outcomes.

Final build passed,109 Node tests and54 browser tests passed. Native Android
fixtures passed8/8 and packaged UI smoke passed4/4, including no-scroll320×592
and native token lifecycle/bridge isolation. The debug APK is built locally;
emulator fixture validation is recorded in
the [initial phone handoff](session-handoffs/2026-10-18-phone-handoff.md). The
baseline APK was subsequently installed on Samsung Android16; the user confirmed
projector connection/control. Phone media/subtitles and full phone-off remain
unaccepted.
The [phone app](phone-app.md) and [Android README](../../android/README.md) explain
build/setup and remaining tests. Touchpad, reverse-screen and automatic boot
remain unimplemented. A bounded 220-review Google Play sample is documented in
[review findings](luma-review-findings.md); complaint counts are not usage data.

Subsequent phone request: foreground ConnectionService owns native I/O and
MediaSession notification/lock-screen controls. User explicitly authorized
saved login: SettingsStore encrypts validated Kodi credentials with Android
Keystore/AES-GCM and caches the verified private receiver address. Visible-app
retries use cache then bounded discovery; secondary Settings provides rescan and
scoped wipe. UI now has corner Home/Back, compact red/green status, volume slider
and ±. First setup opens/authenticates Kodi before saving and continuing Cast.
Current checks/phone boundary:
[background/settings handoff](session-handoffs/2026-10-18-phone-background-settings.md).
Final0.2.0 installed on phone. Build,109 Node/65 browser checks,8 native backend,
4 service,5 packaged UI and5 Keystore settings checks passed. Live updated phone
Cast/Portuguese, lock-screen controls and full phone-off remain pending.

Live updated-phone follow-up: fixed app diagnostics reported captured HLS with19
tracks, authenticated setup and launching/waiting/subtitles/selecting/remote/ready.
A read-only Kodi query then reported playing at04:51/22:35,19 available subtitle
tracks, enabled and index6 selected. This establishes phone handoff completion
and receiver state; actual projector picture/PT-BR rendering, lock controls and
phone-off still await explicit user confirmation.

Subsequent user confirmation: Samsung casting has picture, sound and working
Portuguese subtitles. Reported0.2.0 failures: cold Kodi launch could stall until
Cancel/Retry, and system controls were not visible. Read-only phone diagnostics
found permission granted, an unblocked posted foreground notification and a
fresh active playback session, but its session launch target was null.

Version0.2.1/code3 repairs the launch target, uses an ordinary notification
before verified media, requests immediate foreground display and publishes
Stop/Disconnect as modern Android playback custom actions. It also loads only
PT-BR initially (English fallback), keeps all website languages in service RAM
for on-demand selection and adds one guarded retry after acknowledged Open plus
confirmed idle Kodi. Unknown Open outcomes never overlap another Open; explicit
late prewrite proof permits retry. Current coverage:121 Node,69 browser and22
native Android checks; final mobile and packaged UI checks were rerun after the
last web-only receipt changes. Installed/open on Samsung; saved setup connected
automatically, ordinary public notification posted and session launch target
nonnull. Physical system-control display/actions and repaired cold start remain
pending. [Latest handoff](session-handoffs/2026-10-18-phone-system-controls.md).

0.2.1 physical follow-up failed: user saw no buttons/lock controls. The phone
had a posted ordinary notification with only Disconnect and an inactive/NONE
media session while Kodi continued playing: the APK restart lost its RAM remote.
User also reported repeated starts/no subtitles on S1E2. Own logs showed native
errors in the subtitle phase and repeated SUBTITLE_TIMEOUT, with Kodi reporting
zero tracks. The old3-second window was shorter than a native multi-RPC read.

0.2.2/code4 now installed: fresh active public playback is adopted without Open/
Add if no remembered source or active handoff exists. Ordinary playback controls
and the media card are separate; latest-state notification posts coalesce at
most once/second. Subtitle windows30seconds, bounded transient read retries,
fresh registration windows and uncertain-Add reconciliation preserve single
writes.128 Node and the full70 browser cases passed. Updated Service4 and
packaged UI5 passed after fixing posted-card
timing in fixtures. On actual Samsung,2 confirmed live checks passed ordinary
notification Pause/Play and MediaSession Pause/Play, preserving the identical
source and leaving it playing. The no-confirmation gate rejected on emulator.

S1E2's public PT-BR file returned200 quickly,268 cues, SRT body despite VTT API
label (same known mismatch as S1E1). This alone does not establish a parse cause.
A single public stream did not match Kodi's current source exactly, so no Mac
subtitle mutation was sent. Phone opened the exact S1E2 link; user asked to Cast
once and check visual notification/lock controls. Those results remain pending.
[Current handoff](session-handoffs/2026-10-18-phone-reattachment-readiness.md).

0.2.2 user acceptance: notification-shade and lock-screen Play/Pause are visible
and functional. S1E3 failed physically: first attempt exited Kodi to LUMA Home;
Retry played video without subtitles. Own logs show Kodi becoming unreachable
during subtitle preparation, then AddSubtitle KODI_RESPONSE on Retry. The crash
cause is unconfirmed; no successful new subtitle rendering is claimed.

0.2.3/code5 is built, installed and open on Samsung. Ordinary controls now
prioritize Volume − / Play-Pause / Volume +; MediaSession volume custom actions
come first, with Stop/Disconnect secondary. Relative volume reads the receiver
back immediately; distinct projector/action vectors and observed volume improve
the cards. Main Remote has a confirmed Power off icon using the archived Kodak
app's KEYEVENT26 flow. Cancel/Escape/backdrop send nothing; accepted send tears
down local controls, pauses reconnect and retains saved setup. Physical
shutdown versus standby remains untested.

Verified known JSON-RPC rejections now permit explicit Retry without preserving
an impossible pending Open/Add. Unknown/malformed/mismatched/internal response
outcomes remain protected against duplicate writes. Safe numeric HTTP/RPC
diagnostics support the next live investigation; this is not a crash fix claim.
130 Node passed. Full browser run73/74 exposed a long-title layout overflow;
after its narrow fix all31 mobile cases passed, covering all74 current browser
cases across runs. Final APK build and native Backend9/Service5/packaged UI5
checks passed. The live phone suite sent zero controls: cached receiver was
absent while encrypted Kodi settings remained; user confirmed projector OFF.
Actual new volume/power and S1E3 retest remain pending. See
[handoff](session-handoffs/2026-10-18-phone-volume-power.md).

Distribution follow-up: user explicitly authorized publishing to their empty
public `kiansheik/kodaklumaremote` repository, GitHub Pages install/update metadata
and signed APK releases using their existing local Android key.0.2.4/code6 adds
optional foreground update offers (five-minute throttle; Later24h), manual
Settings checks, verified consent-only downloads and Android installer handoff.
Site sources, strict derived release manifests, local signing/publishing helpers
and a scoped Pages workflow are implemented. Signing material stays local;
only the public certificate fingerprint is tracked. Source history and new
publication files are reviewed before push. No projector changes were made.

Local checks:134 Node,77 browser, updater6 and packaged UI5 passed. Release
build/lint passed after fetching the missing official lint dependency; an
initial Intent chaining compile error was fixed. Emulator signing proof passed
debug → private API33+ lineage bootstrap → ordinary production signer-only APK,
preserving encrypted credentials and a private marker without uninstall/reset.
Final publication and actual-phone migration evidence are recorded in the
[distribution handoff](session-handoffs/2026-10-18-github-release-updates.md).

Public0.2.4 released from6e4b7df; APK740,817bytes and SHA-256
`e41a99e4e9120bb1abdeb0217615e920859328b56b4ff280a8f98a6010360adf`.
Real GitHub/CDN download returned200 and matched hash/size. Account Pages uses
`https://kiansheik.io/kodaklumaremote/`; HTTPS page and update.json returned200
with exact release metadata. The original custom workflow deployed after adding
the required release-tag environment rule. Actual Samsung then passed read-only
signer/decryption checks before bootstrap, after bootstrap and after installing
the exact public APK; encrypted Kodi login survived with no uninstall/reset.
User follow-up prefers `make deploy-gh-pages` and direct static branch hosting;
that replaces the custom Actions deployment while retaining the release/feed.
The real Make target passed; Pages now has `build_type=legacy`, source
`gh-pages:/`, HTTPS enforced and a successful branch build. Seven focused
release/deploy checks passed. Public files are the five site assets,
`update.json` and `.nojekyll`; no APK/key/wiki is pushed to that branch.

Dual receiver follow-up: user opened **Media Station X** on the Philips and
requested a compact LUMA/Philips switcher with both connected simultaneously.
Version0.3.0/code7 adds independent target state/transports, private saved target
selection and selected-target notification/MediaSession controls. LUMA retains
its projector/Kodi controls. Philips provides playback, seek, subtitles and
player volume, with unsupported hardware controls hidden.

The phone hosts a private-interface MSX asset/control listener, normally4178;
MSX Start Parameter setup uses the displayed IP:port. The user opens Connect
this TV, then approves the pinned TV on the phone. Pairing/capability/port are
saved privately. TV media and subtitles are fetched directly from their public
origins. A TV-owned SRT/WebVTT renderer loads PT-BR first, English fallback and
other languages on demand. Command receipts and independent heartbeat avoid
replaying unknown Opens; failed subtitles preserve correctly playing media.

Checks so far:154 Node, full84 browser plus final41 mobile (including9 receiver
cases) passed; the final added setup instruction also passed the narrow-layout
case. Final native regression checks passed35 (backend9, service5, settings5,
packaged UI5, MSX7, dual-service4). APK/debug lint passed
after replacing an API24-only Map call for API23 compatibility. Isolated
`.receiverfixture` builds preserve the emulator's existing production-signed
app. Final publication/install and complete native regression results are in
the [dual-receiver handoff](session-handoffs/2026-10-18-dual-receiver-msx.md).
Signed0.3.0 is installed/open on Samsung. The existing read-only signer and
credential-decryption check passed before/after; encrypted Kodi login survived.
Published from72a600e; public APK/download digest matches the963,544-byte signed
file. Pages build590e0b2 completed; HTTPS install page/feed returned200 and the
feed exactly matches0.3.0 metadata. Production signing material remains local.
Actual Philips playback, subtitle appearance, HTTP/mixed-content behavior and
phone-off continuation remain unconfirmed. [Domain notes](msx-receiver.md).

MSX physical follow-up failed: the TV reports **Server responded with status0**.
The actual phone listener is reachable, but0.3.0 rejected browser OPTIONS.
Version0.3.1/code8 adds restricted CORS preflights, safe TLS/bootstrap diagnostics
and fixed setup hints with explicit HTTP padlock instructions. Preflights cannot
claim a pending TV or return a capability. Actual TV failure cause remains
unconfirmed until a diagnostic retry; forced HTTPS is still a possible cause.

Final checks:22 focused Node,43 mobile browser,8 MSX native and4 dual-service
native cases passed; isolated debug/test APK+lint and signed release+lintVital
passed. Signed0.3.1 is installed/open on Samsung; read-only signer/decryption
checks passed before/after, preserving Kodi login. Publication and the actual TV
retry are tracked in the [setup handoff](session-handoffs/2026-10-18-msx-setup-matter.md).
Source056b6c9 and signed releasev0.3.1 are published; APK965,037bytes with
SHA-25689630f78e8fb41194aa9a3fb82513297e350a0589fad15f769b2e4b300342cec.
Pages deploymentce82fa2 is built. HTTPS install page/feed returned200 with exact
0.3.1 metadata; public APK returned200 and matched size/SHA-256.
The user retried0.3.1 and still saw status0. A safe phone log has one generic400
without origin; this can also be an abandoned TCP connection, so it does not
establish a parser failure. A temporary Mac bootstrap diagnostic also failed on
the TV and saw a zero-byte TCP timeout, no HTTP/TLS. The user declined further
manual browser tests because the TV is outdoors in the sun and explicitly
redirected work to Matter automation. Both temporary Mac listeners were stopped.

The user redirected work to Matter automation to avoid repeated outdoor TV
steps. A separately pinned Matter.js0.17.9 Mac controller commissioned the
Philips and read three endpoints: Root, Basic Video Player and Speaker.
KeypadInput/MediaInput/OnOff and speaker LevelControl are present; no
ContentLauncher/ApplicationLauncher/ApplicationBasic server appeared. Matter
therefore supplies a hardware remote path, without an established arbitrary
website-video/app-launch/external-subtitle path. [Inventory](philips-matter.md).

Actual Mac checks: Home returned status0; speaker volume was freshly observed
1→2→1, restoring the starting level in `finally`, with both writes status0 and
verified readback. A Right attempt failed before invoking its key; Left was not
sent. A confirmed private IPv4/advertised-port hint succeeded after a bounded
operational mDNS query returned no results; a subsequent CASE/descriptors/speaker
read took4.4seconds.
The Mac also successfully opened an enhanced commissioning window, so phone
pairing did not require another visit to TV settings. Private codes/fabric and
network details remain ignored. Mac commissioning used the library's default
attestation policy and does not prove production PAA trust.

Version0.4.0/code9 now implements the native Android Matter controller and is
installed on Samsung. Read-only signer/decryption checks passed before/after,
preserving existing Kodi login. Manual code/private camera-QR pairing saves an
AES-GCM/Keystore-protected fabric; each phone enrolls separately. Capabilities
drive navigation, speaker volume, inputs and confirmed Off. Add another phone
requests a temporary QR/manual-code window; codes leave the UI on close/expiry.
LUMA/Kodi remains independent, and Philips combines Matter hardware controls
with MSX video controls. Actual phone manual-code pairing completed through the
Mac-opened window with production trust enabled, without another TV setup step.
Phone Volume+1→2 and Volume−2→1 were each independently read back from the Mac;
starting volume is restored. Actual phone **Add another phone** opened a
TV-accepted enhanced window and displayed a fresh manual code/QR/expiry notice;
dismiss/reopen left no code or QR. No second phone was enrolled. Navigation/Power
controls are enabled; phone key/Off/input effects, camera scan and second-phone
enrollment remain pending.

The app now requires Android API24+/ARM64. Official CHIP SDK source is pinned
to3584c8c8552f4a0d471f2aad24a15b478959c230 with Kotlin2.1.10; locally built
JAR/JNI/trust artifacts are verified against97 hashes, with16KB ELF/ZIP alignment,
eight JARs/two libraries/72 production PAA/five CD roots. Progress/detail SDK
logging is disabled and checked to prevent pairing-code leakage. The upstream
issuer's default test IPK remains documented in the [build path](matter-android-path.md).

Final review adds an explicit **TV unavailable? Forget on this phone** recovery
button in the Forget confirmation. It removes only the saved TV node/cache,
retains the encrypted controller CA and LUMA setup, sends no TV command and
reports local-only recovery; the TV may still retain this phone's controller
entry. There is no automatic reset. The Matter-only Settings Disconnect button
is also fixed. Async commissioning-state changes reschedule service polling and
update foreground lifetime; per-attempt `stopDevicePairing(pairingNode)`
preserves the shared I/O factory. Explicit null volume/input state clears old
cache. The UI uses only a connected speaker-capable Matter volume or connected
MSX player volume, so re-pairing to a no-speaker TV cannot show stale40%.

Final source checks passed:54 mobile browser,22 focused Node and7 QR JVM;
18 final focused native checks (Matter7/service6/packaged UI5), debug/test APK
and lint. Earlier unaffected native26 (backend9/settings5/MSX8/dual receiver4)
also passed:44 native checks across runs, not a fresh full44-case rerun. These
fixtures are separate from the actual-phone pairing/speaker proof above.

Release0.4.0/code9 is published from source
4d487cf2fdd3613b1d098838cfa5a23134871b0b. Final signed build/lintVital and
ZIP16KB alignment passed. APK9,631,440bytes, SHA-256
`a3f03b098f739d7e4253a0716771cbe7e8a5d7cd1fcf4aa6be73e6d6d93766fa`.
That exact signed APK was installed on Samsung; signer and saved Kodi Keystore
decryption passed before/after, with receiver/Kodi setup retained. Own-process
service logs returned to foreground=true/connected=true on the saved Philips
target without a new setup code. `verified=false` is expected with no verified
MSX media. The phone's secure PIN blocked foreground UI inspection after this
upgrade; at that point only service-level reconnection was established. No
further lock-screen dismissal attempts were made. The user later confirmed the
signed phone app immediately recognizes the TV and navigation visibly works
after switching the TV off/on. This establishes actual saved-connection and
navigation acceptance; it does not establish phone Off/input or media playback.

GitHubv0.4.0 is public. Pagesc542b9089cb27708d34478690de1b0625b314dc0 is built;
HTTPS install page returned200 with ARM64 compatibility text. HTTPS update.json
returned200 and exactly matched the signed0.4.0 manifest (code9/minSdk24).
Public GitHub APK download returned200 and matched the installed APK's size/hash.
See the [native phone handoff](session-handoffs/2026-10-18-native-matter-phone.md).

The user next explicitly authorized using the existing Matter connection to
set up TV playback without more outdoor manual navigation. A fresh Mac Home
command returned status0 (CASE57ms, descriptors14ms); it does not prove which
screen has focus. Exact-TV SSDP still supplied no services, and the documented
debug/screenshot routes on9222/7001 were unavailable in bounded checks.

A separate Philips JointSPACE route is now observed: HTTP1925 `/1/system`
and HTTPS1926 `/6/system` returned200 and identify API6.1/Linux. Advertised
features include key/unicode input, text-entry context and browser activities,
with Digest pairing/secured transport. Protected HTTP activity returned403;
HTTPS context/activity/screen-state returned401 with a Digest challenge. This
is distinct from Matter authentication. The first30-second PIN window expired;
a fresh explicit request and hidden-stdin PIN grant then returned SUCCESS.
Credentials are saved privately with mode0600; the PIN is not persisted.
Authenticated context returned200, but its Skype/Options/NA labels disagreed
with the user's visible Home screen, so it is not reliable focus evidence.
Browser POST returned200 (`pageLoadVerified:false`), while the user still saw
Home and our receiver recorded no TV TCP/assets/hello. Application enumeration
was rejected; no undocumented application-launch payload was attempted.

`scripts/philips-controller.py` passed15 offline fixtures. Its new stdin/hidden
Unicode command follows maintained upstream
`input/key` semantics and reports acceptance without claiming text was applied.
Advertised text-entry context flags do not establish a field-replacement API.
A separate Mac
direct-browser proof (`scripts/msx-mac-proof.mjs`) reuses the existing receiver
assets/channel with explicit private interface/TV peer, secret URL and separate
loopback-only authenticated control. It passed13 loopback fixtures plus syntax
checks. It serves no media/subtitle proxy and makes no source fetches. Its tests
did not contact devices. Root subsequently started the temporary actual LAN
listener; sanitized status is running=true/connected=false, assets0/hello0.
The private launch configuration remains prepared. After the TV power cycle,
one explicit saved-URL browser retry again returned
`accepted:true/pageLoadVerified:false`; immediate TCP/assets/hello counts
remained0. The user confirmed that retry did nothing and Home remained visible.
A later context read reported `contextObserved:true,focus:{}`. The user
identified a recent Netflix tile at the left edge of Home and said no photo
was needed. Continue from that known starting tile using text feedback, while
API context remains unreliable. Root sent one Matter Right from Netflix;
endpoint1 returned SendKey status0 (CASE62ms/descriptors17ms). The user confirms
this reached the regular Netflix tile; no Select or media was sent.
The proposed browser route could avoid MSX
startup setup; actual browser
GET/hello, autoplay, video and PT-BR remain untested.
These Mac tools are not integrated into the published0.4.0 APK.

A subsequent exact-TV DIAL check found a separate direct app-launch route.
Unicast search returned0; bounded standard multicast for DIAL/MediaRenderer,
filtering the paired TV address, found one43PFG6910 descriptor with an
`Application-URL` response header. The old discovery path discarded this
header; empty XML services did not exclude DIAL. At the advertised base,
YouTube GET403 and Netflix GET200/stopped preceded exactly one empty Netflix
POST:201, then GET200/running. The user confirmed Netflix visibly opened.
No Select/navigation accompanied this launch,
and no arbitrary URL/media/subtitle delivery is established. The private
advertised base remains under ignored mode0600 evidence. The read-only discovery
script now preserves only a validated `dialAdvertised` boolean, requiring the
descriptor's numeric private peer and no credentials/query/fragment. Eight
discovery fixtures, syntax and whitespace checks passed. Root's live
`node scripts/discover-tv.mjs --no-save` then returned one exact-model target,
`dialAdvertised:true`, empty serviceTypes/AVTransport and zero errors, without
app requests. No APK or phone feature changed.

The user rearranged favorites so the browser is one Right and MSX two Rights
from the initial Home tile. A Home→Right→Select batch unexpectedly reached
HDMI1, as the user confirmed; its cause is unproven. Root then sent Home alone
and waited: the user confirmed real Home with the first tile highlighted.
Only then did Right→Select run (both status0, CASE49/55ms), showing “Editar speed
Dial”. That screen remains ambiguous. One Back
and another accepted JointSPACE browser request then returned to HDMI1, with
receiver counters still0. The user then confirmed JointSPACE named Home
visibly returned HDMI1→Home. JointSPACE Right, a700ms pause and Confirm were
accepted, but the resulting screen is unknown because the user left before
replying. All device work/questions stopped at that boundary; saved pairings
and the temporary listener were preserved. No browser/MSX launch is established,
and the cause of earlier outcomes remains unproven. A key acknowledgement alone
cannot prove focus. No new phone feature or release was made.

The saved S1E2 source returned403 to a bounded HEAD request. An initial egress
approval was rejected; after offline provider inspection, the same-provider
request was approved and its403 confirmed, without auth/redirect workarounds.
Ordinary Chrome again blanked the episode page to about:blank; direct public
configuration GET also returned403. No source bypass was attempted and a fresh
usable source is unresolved. Two private Open-command fixtures are prepared
offline for public and S1E2 playback (16 declared tracks, PT-BR preferred); this
preparation made no requests and does not establish source reachability.
No new TV playback/subtitle success is claimed.
See the [browser setup handoff](session-handoffs/2026-10-18-philips-browser-setup.md).
The latest [power-cycle and browser retry follow-up](session-handoffs/2026-10-18-philips-power-cycle-follow-up.md)
records the new phone acceptance without a new app release.

Release preparation: the user requested building/deploying the latest source.
Android version is now0.4.1/code10, a maintenance build of the existing phone
features. New Philips/DIAL work is desktop tooling, not phone integration.
Focused release/deploy/discovery/receiver Node checks passed28/28 and Philips
Python fixtures15/15. Initial loopback sandbox denials were resolved by running
the fixtures with loopback permission. The first signing attempt selected an
older Gradle8.11.1 cache; the project uses cached Gradle9.6.1. The existing
production certificate matches. Final committed-source build and publication
are tracked in the [maintenance release handoff](session-handoffs/2026-10-18-release-0.4.1.md).

Release0.4.1/code10 is now public from source
`a84dbb1ef360d1351861e076f68e00dcc784bb82`. Signed committed-source build and
lintVital passed; production certificate and16KB ZIP alignment verified.
APK9,631,440bytes, SHA-256
`a650dd4d44c8413bcd01b38c7c8d68f13ce758fdccc222222e0f36b43bc008e7`.
GitHub release assets are uploaded/public. Pages commit
`f38fa65de56ee6a59045a45021fd4a88b4008b3a` is built; Chrome rendered0.4.1 and
the exact download link. Anonymous HTTPS feed/APK returned200; served metadata
and downloaded bytes/signature match the prepared release. The Python HTTPS
client lacked a trusted CA bundle; system curl and Chrome verified successfully
without trust changes. No installation, device commands or new phone features
were part of this maintenance deployment.

Motorola follow-up: Julia's phone needed about three attempts to start a movie
on LUMA/Kodi, kept showing startup after playback began, never loaded subtitles,
and lost its lock-screen card after an ineffective Pause. The user identified
the title as Bly Manor. The Motorola is unavailable for debugging and the exact
episode/source is unknown; this report does not establish a device-specific cause.

Version0.4.2/code11 now prepares startup, subtitle and background-control fixes.
The coordinator observes exact-source playback while Open is unresolved or its
response is unknown, without replaying uncertain writes. Kodi's null
`currentsubtitle` now means its subtitle player is not ready; an empty object is
ready without a selection. Initial subtitle failures preserve verified playback,
the full captured catalog and a subtitle-only retry. English fallback requires
explicit proof that the preferred Add was not applied.

Native Kodi operations share one five-second budget across their RPCs, require
exact mutation acknowledgements and reject mixed-source observations. The
service retains prepared catalog metadata before handoff completion, preserves
a matching stale media card on transient HTTP failures and separates Kodi HTTP
control from projector TCP availability. Every system media action gets a
bounded30-second CPU wake lock and fresh source/revision checks before writes.
Per-operation JS abort no longer revokes the whole cast job; explicit user
Cancel and source changes still revoke it.

Final Node184 checks (including coordinator52), mobile browser56,
debug/AndroidTest builds and lintDebug passed. Isolated `receiverFixture`
checks passed: Service10, Backend12, packaged UI5, settings5 and dual receiver4.
The service test used a genuinely screen-off/keyguard-locked emulator after its
Activity closed, verified Pause/Resume and bounded CPU-lock release, and restored
the original emulator lock setting. Final review also caught and fixed a
same-URL recast blocked by an earlier unknown response: only an actual native
completion receipt plus prior matching playback can release that cached attempt.
Late bridge receipts are bounded and never justify overlapping unknown writes.
Release0.4.2/code11 is public from source
`976a1c3b643193ca0bf5f92ecdef8fcd1a4d96e7`. Committed-source signed build,
lintVital, production certificate and16KB ZIP alignment passed. The APK is
9,632,212bytes, SHA-256
`886e1711953a88257f8dbd0fdbc5c2dd85d859cc11c2cf469ee8eb7609a9032f`.
Pages commit`f87edf802420f0a71e0bf55342dcc6e691f28378` is built/no error.
Anonymous feed/APK returned200; served metadata and downloaded bytes/signature
match the prepared release. Chrome rendered0.4.2 with the exact APK link. No
physical phone/projector/TV actions were sent during this repair. Motorola
locked-screen controls, the exact Bly Manor episode and visible subtitles still
need physical acceptance. See the
[recovery handoff](session-handoffs/2026-10-18-motorola-kodi-recovery.md).

2026-10-19 follow-up: the user still reports first-start Kodi crashes, hangs and
missing subtitles. They requested video-first playback with manual language
selection, one-second command spacing and coalesced volume.0.4.3/code12 now
removes all startup subtitle reads/adds/selections; the full captured catalog
remains available after exact-source playback opens Remote. Launch settles for
one second before readiness, and subsequent playback/subtitle mutations are
paced with cancellation and fresh source checks. MSX also receives an explicit
manual-subtitle startup policy; older clients retain their existing default.

The main volume slider and ± buttons show the requested value immediately.
LUMA's service shares one two-second trailing cooldown with notification and
lock-screen ±, then sends a single absolute volume change. Projected state
survives old reads; receiver/source changes revoke unsent writes. Philips UI
volume is independently debounced and explicit switch/disconnect/cast/reset/
power actions cancel its unsent timer. Node181 checks pass (coordinator46 and
MSX20 included), as do all57 mobile browser checks and the isolated debug/test
build plus lintDebug. Native40 checks pass (service14/backend12/UI5/settings5/
dual receiver4). Final service14 and packaged UI5 were rebuilt/retested after
the callback-delivery projection fix; the deterministic delayed-reply regression
passed. The Activity was closed and emulator screen/keyguard locked for system
actions, including mixed UI/system volume bursts, in-flight new input and target
cancellation. Release evidence is in the
[video-first handoff](session-handoffs/2026-10-19-video-first-volume.md).
No physical crash diagnosis or new household-device success is established.

Release0.4.3/code12 is public from source
`b5604ba05f95bd83dc33229b9f4fdc3097db8417`. Committed-source production signing,
lintVital and16KB ZIP alignment passed. APK9,648,884bytes, SHA-256
`f3703b75ceae7678a6908ea4aa096c177b7e9f651920dd07c4ebfa3afda6b7a8`.
Pages commit`6a7346ab1e0eb023d32bb732b796116762312943` is built/no error.
Anonymous update feed/APK GET200; exact metadata, downloaded bytes and production
signer match the prepared release. Chrome shows0.4.3 and the exact APK link.
No physical phone installation or projector/TV commands were part of this work.

Capture follow-up: the user reports Cast rejecting the default episode with an
HTTPS-page message after updating. Live HTTPS HEAD follows the original `.xyz`
episode link to HTTP200 on `framextv.cc`; the connected Samsung's address bar
also shows HTTPS `.cc`. MainActivity accepted only exact `.xyz`, so the new
host was rejected before capture. Both APKs contain identical9,395-byte capture
assets; this is a domain restriction, not a missing script or HTTP downgrade.
0.4.4/code13 accepts both exact HTTPS hosts (same port/credential boundaries),
starts Browse at `.cc`, adds `.cc` VIEW links and separates origin/asset/script
errors. Eight focused browser checks, debug/test builds, lintDebug and all7
packaged Android UI checks pass, including actual production capture across an
intercepted `.xyz`→`.cc` page change, both direct origins, track catalog and
navigation revocation. Release and live-phone evidence are in the
[hotfix handoff](session-handoffs/2026-10-19-capture-domain.md).

Release0.4.4/code13 is public from source
`743b890e4d657b2154b1461541ebb488c3be99c7`. Signed committed-source build,
lintVital and16KB ZIP alignment passed. APK9,648,904bytes, SHA-256
`2b8ed48a21d05d170c99075f66f5f505b80a1df1ba2758735ef923d38f380c42`.
Pages commit`6587626102ac3f9d4226e2958c2954a5e99c74db` built/no error;
public feed/APK GET200, exact metadata/hash/production signer verified.
The signed APK was installed with `adb install -r` on the connected Samsung,
preserving saved setup; package reports0.4.4/code13. One live default-page Cast
captured22 HLS subtitle tracks, progressed launching→waiting→opening→remote→ready
and showed the combined remote with Kodi-reported Playing/22:35. Full URLs and
UI images/logs remain ignored locally. Visible projector picture/sound and manual
subtitle rendering were not separately confirmed. Chrome was unavailable for an
optional install-page render check; public HTTPS assets were verified directly.

Subtitle normalization follow-up (2026-10-19): user reports most non-default
titles have no subtitles and the Browse address bar does not follow navigation.
The current public homepage supplied four sampled titles (Masterplan, Tenzing,
Law & Order: SVU S1E1, The Simpsons S1E1). Twelve English/Portuguese bodies were
all SRT and normalized idempotently with364–1660 cues (SVU Portuguese588;
Simpsons English364). Their original bodies/URLs remain ignored locally.
Earlier Avatar evidence had MIME/body mismatches. Multiple formats alone are
not an established cause of the reported failures.

0.4.5/code14 prepares the selected subtitle on a separate native executor,
sniffs/validates its contents and converts SRT, WebVTT, ASS/SSA and a conservative
media-time TTML/DFXP subset to UTF-8 SRT. BOM/declared UTF16 and common Portuguese
legacy encodings are supported; malformed captions/error pages, unsafe XML,
unsupported frame/ancestor timing and oversized files fail without a Kodi Add.
The service serves only prepared RAM files to the matching receiver through an
opaque capability. Complete response serving plus observed Kodi selected/enabled
index is required; this is not a rendering or parsing acknowledgement.
No automatic startup subtitle requests were reintroduced. Existing normalized
files persist for the active source (128 entries/16MiB aggregate); capacity fails
without evicting registered files. A known unavailable ticket can be replaced
on explicit language choice, while unknown Add outcomes remain protected.
Browse history follows SPA navigation without overwriting a focused edited draft.

Validation/release and physical acceptance are recorded in the
[normalization handoff](session-handoffs/2026-10-19-subtitle-normalization.md).

Final local0.4.5 checks passed: Node201, browser89 (mobile/capture41 and
extension/dual/Matter48), JVM parser24/transport10, Android47 (subtitle8,
backend12, UI9, service14 and dual4), final offline debug/test build/lintDebug.
Android runtime caught and corrected Expat validation-error wrapping; exact
Unicode/timing expectations remain. Native serving is bounded and source-scoped;
queued old-source preparation is invalidated by a separately captured generation.
Production signing/publication and physical acceptance follow.

Release0.4.5/code14 is public from
`584ad80bad7593714cb93c4482a8958b9b28ca7b`; signed committed-source build/lintVital
and16KiB alignment passed. APK9,666,716bytes, SHA-256
`88febb047cd356a3cddda6b62579826ed7b37212f16740b8ea0b7504e2bb85f8`.
Pages`6bb54527a726a58887466c9365c9b1f72038ad02` built/no error; public feed/APK
GET200, exact metadata/hash/production signer verified. Signed Samsung upgrade
preserved saved setup and immediately reconnected. Existing Kodi playback was
observed around19:39/103:24 with generic recovered metadata and no captured
catalog after the service restart. Closed its empty subtitle sheet and left
playback untouched; no fresh cast/receiver mutation was issued. A new cast is
needed to supply the catalog and physically verify normalized subtitle rendering.

## 2026-10-30 local media and laptop browser remote (unreleased)

The phone Browse screen can now select a local MP4/MKV/WebM document plus up to
16 external SRT/WebVTT/ASS/SSA/TTML files. A service-owned server binds only the
active private Wi-Fi/Ethernet IPv4 address, uses random per-selection
capabilities, admits private peers, and implements GET/HEAD plus single byte
ranges. Generic private URLs remain rejected: only exact server-owned URLs and
the exact source reported by an authenticated LUMA Receiver are usable by
native playback controls.

`npm run stream:local -- --root /path/to/media` now opens a localhost-only
browser remote with receiver pairing, root-scoped source selection, direct/auto/
compatibility modes, subtitles, volume, transport and scrub controls. Auto mode
preserves conservative H.264/AAC inputs; compatibility mode uses ffmpeg H.264
High/yuv420p/CRF16 and 320 kb/s AAC in 1080p HLS. The laptop uploads 30-second
JPEG thumbnails to the receiver under an opaque `local:` ID. The receiver owns
playback, so other paired phones adopt the session, guard writes against its
exact observed source, and download the shared preview cache.

Validation: phone Java compilation and receiver unit tests pass with cached
offline Gradle; Node 259/259 and the mobile browser suite 62/62 pass; the local
browser page/API was smoke-tested on loopback; `npm run build` passes. No APK was
installed, no physical projector/phone media playback was attempted, and no
arbitrary codec/subtitle matrix is physically accepted yet. The Wi-Fi-only
public watch-page failure remains unproven as an IP/ISP block; DNS/router/CDN or
site browser-policy differences remain live alternatives.

## 2026-10-30 receiver buffer follow-up (unreleased)

The receiver's earlier 24 MiB target was treated as a hard stop even before its
15-second minimum was reached. That protects the low-memory projector, but for a
high-bitrate local MP4/MKV it can represent only a few seconds and produce a
READY/BUFFERING loop. Media3 now starts at 750 ms, requires 2 seconds after a
rebuffer, keeps an 8-20 second window, and treats 24 MiB as soft until the
8-second time floor is present, subject to Media3's heap-pressure guard. This
still prevents the old ~70-second prefetch while making high-bitrate media obey
a useful time buffer.

Laptop compatibility HLS now forces keyframes every two seconds and emits
two-second independent segments instead of nominal four-second segments, reducing
initial and post-seek latency. Receiver unit tests and the focused local-media
Node suite pass. A real ffmpeg smoke produced four independent segments of
2/2/2/1 seconds. This RAM policy was physically accepted through the complete
high-bitrate *Never After Dark* playback; the user described buffering as much
improved but still near the minimum window.

## 2026-10-30 direct local audio companion (live candidate, unreleased)

The exact *Never After Dark* MKV carries only E-AC-3 audio, which the physical
projector accepted as a direct container but did not render. Direct H.264 local
playback now retains the original video and, when no receiver-safe AAC/MP3 track
exists, prepares only the preferred audio stream as a seekable 320 kb/s stereo
AAC M4A. The same-origin companion URL is accepted only for opaque `local:`
media. The receiver merges it with the original video and explicitly selects the
supported AAC group. Generated audio is cached under ignored `.local/` storage,
keyed by source path/size/mtime identity, so repeat playback avoids encoding.

Receiver 0.3.8/code22 was physically installed for the movie. The live exact-file session
reports ready/playing, fixed 6292.832-second duration, `canSeek:true`, 20.917
seconds buffered, `audio/mp4a-latm` stereo selected, and full English external
subtitle index512 enabled. The cached M4A independently probes as AAC, 48 kHz,
two channels and the same 6292.832-second duration. The subsequent full-movie
view physically confirmed audible output, captions and seeking.

Future laptop launches generate three random-access scrub previews concurrently
and distribute the first previews across the whole timeline before filling
30-second gaps. This replaces sequential full-film decoding; the current live
process predates that in-process change and retains its existing thumbnail job.
Focused local-media Node tests pass 9/9. Phone remains the installed
0.4.29/code39 candidate; neither auto-update feed was published.

## 2026-10-31 current-title disk prefetch (receiver 0.3.11 installed, unreleased)

Progressive opaque `local:` playback keeps the accepted 8–20 second/24 MiB RAM
window and now has a separate Media3 disk cache. A background `CacheWriter` uses
download priority while ExoPlayer registers playback priority only during RAM
loading, so playback preempts prefetch. Prefetch continues after the RAM window
fills, including while paused. The AAC companion is cached completely when it
fits; a whole video is cached when safe storage permits. Larger videos keep a
rolling byte window around/ahead of playback, trim fully consumed fragments and
refill freed space. A seek cancels and repositions only that rolling worker.

Stop/source replacement cancels the writer, clears media sources, deletes the
old session's opaque video/audio keys on one executor and reports `clearing`
until done. Process start sweeps crash residue. Playback reads cache without
writing, so the explicit budget cannot be exceeded; disk failures fall back to
ordinary upstream playback. The policy reserves at least 512 MiB and 10% of
currently free space, disables below 128 MiB, and caps at 8 GiB. HLS/DASH and
non-local provider streams are deliberately excluded from whole-item prefetch.

Receiver 0.3.11/code25 is signed, installed and healthy. Android exposes only
internal app cache: 1,568,206,848 bytes usable at the observed restart, yielding
a 1,031,335,936-byte safe limit. No mounted app-writable removable/SD cache
directory was visible. The receiver now enumerates standard external cache
surfaces and automatically prefers a roomier mounted SD card on a later restart.
With current internal space, the exact 6.9 GB movie would use a rolling window,
not a whole-file cache. Status/Diagnostics expose storage, state, bytes, target,
window and estimated seconds ahead; the laptop remote renders progress.

Receiver unit/debug build passes with 29 tests, including four cache policy
tests; focused laptop media Node tests pass 9/9. The server remains off and no
new local playback was started, so paused growth, rolling refill, seek
repositioning and cleanup are implemented/compiled but not physically accepted.
The phone remains 0.4.29/code39 and public auto-update feeds remain unchanged.
