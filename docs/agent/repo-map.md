# Repository map

- All-format disk preload: `PlaybackDiskCache` handles progressive byte windows
  and Media3 DownloadHelper/Downloader adaptive time windows. Its shared quality
  parameters are also applied by `PlaybackController`. Session-hashed adaptive
  keys, fresh playback manifests, bounded LRU and span-validated time coverage
  keep storage/status scoped to the current source. `CacheInstrumentation` and
  `scripts/test-receiver-cache.mjs` exercise paused preload/1080p/blocked-upstream
  seeks/cleanup for HLS, DASH and direct MP4 on an isolated emulator.

- Stream recovery: `Catalog` selects/probes fresh candidates and alternate
  providers through `StreamProbe`; `PlaybackController` retries failed startup.
  `StreamRecovery` holds expiring canonical phone requests, exposed by paired
  `recovery.status` and player status. Phone `NativeBackend` validates these;
  mobile recovery card and `ConnectionService` notification open the episode
  through existing browser/capture/Cast support. No stream URL is persisted.

- Receiver watch history: `HistoryActivity` supplies controller UI; pure
  `HistoryIndex` validates/groups canonical progress and bounds resume positions.
  `LibraryStore.historyProgress` reads existing nondeleted sync records; `Catalog`
  adds season/episode metadata and movie/current-episode resolution beside auto-next.
  `history.show/status` support paired-client navigation checks. Media URLs remain
  transient; generation/lifecycle checks cancel stale opens.
- `AccessibilityPanel` resolves standard/exported direct panels, then explicitly
  opens hidden Android TV `MainSettings` before general vendor settings. Home's
  Accessibility button and Controller Setup share this resolver. Paired
  `controllers.settingsPanels` reports bounded settings component metadata.

- NES Games: receiver `GamesActivity`, `NesGameView`, `NesNative`,
  `GameLibrary`, `CheckpointHistory`, `NesFrameInput`,
  `ControllerInput`/`ControllerState` plus pinned QuickNES JNI
  sources under `android-receiver/app/src/main/cpp/`; phone `GamesLibrary`/
  `GamesManager`, external import intents and mobile Games sheet. Native, JVM
  transfer, browser and isolated Android tests are described in [Games](games.md).
- Receiver controller setup: `ControllerSetupActivity`/`ControllerDiagramView`,
  pure `ControllerCalibration` and `ControllerProfile`, private
  `ControllerProfileStore`, and `ControllerPlayers`/`ControllerPlayerStore`.
  Hardware calibration, receiver navigation, NES actions and player slots are
  separate. `ControllerNavigation` supplies the focus-navigation stage skipped
  by app-translated keys. `ControllerLeds`/`HidHostTransport` and pure
  `NintendoPlayerLights` provide bounded Android 9 Pro Controller LED output
  through the existing Bluetooth service; physical Player 1 light is accepted.
- Direct remote: `RemoteInput` dispatches authenticated `input.key` locally;
  `ControllerSystemService` supplies Accessibility focus/click/back outside the
  receiver (screen controls stay local), with `LocalProjectorKeys` fallback.
  `NativeBackend` routes supported `luma.*` actions over receiver HTTP when
  `input.remote` is advertised; `ConnectionService`/mobile use `remoteConnected`
  independently of `nativeConnected`. `ReceiverApp.ensureListening` starts the
  listener from process/service/Accessibility/boot entry points.
- Smooth Cat: `CatControllerMotion` integrates calibrated analog velocity per
  animation frame and holds neutral; `CatToyView` supplies its rendered position
  for takeover, while `CatModeState` arbitrates phone/controller ownership.
- Across-app buttons: `ControllerSystemService` is an opt-in Accessibility key
  filter using saved profiles, with `LocalProjectorKeys` dispatch through the
  existing loopback EShare service. It leaves analog motion and focused LUMA
  activities alone. `ControllerHomeHold`/pure `HomeHoldState` coordinate short
  Home and three-second sleep/power; Cat Mode has a Home launcher entry.

- `src/extension/manifest.json`: site-only MV3 manifest, MAIN world, matching
  same-origin frames, no extension API permissions/background/network observers.
- `src/extension/player.js`: video selection, Shadow DOM fallback UI, native Cast
  preservation, navigation/source lifecycle, local-return behavior, trusted-click
  video-link and all-declared-subtitle-list clipboard actions.
- `src/cast/session.js`: dependency-injected adapter to the page's initialized
  CAF SDK; no SDK loader or receiver-configuration replacement.
- `src/cast/demo.js`: ordinary web demo using the same adapter; excluded from
  extension bundle and loads the SDK through the demo HTML only.
- `src/shared/media.js`: validated HTTP sources, declared HLS behind blob/MSE,
  metadata/WebVTT tracks; rejects attached MediaKeys and bare blobs.
- `scripts/build.mjs`: produces ignored `dist/extension`, loopback-only
  `dist/test-extension`, and separate `dist/demo`.
- `scripts/recon-http.mjs`, `scripts/recon-browser.mjs`: public static inventory
  and clean-browser metadata, sanitized by default; outputs ignored `.local/`.
- `scripts/serve.mjs`, `scripts/demo-server.mjs`: loopback fixtures and HTTPS
  demo. `scripts/public-cast-smoke.mjs`: actual SDK readiness only, no device.
- `tests/*.test.mjs`: Node media/session unit tests.
- `tests/browser/extension.spec.js`: actual loaded MV3 + mocked CAF in Chromium.
- `PlaybackDiskCache` plus phone/laptop remote buffer labels: actual contiguous
  disk-ahead and RAM-ahead telemetry for opaque local progressive sessions;
  receiver paths/storage keys never cross the native boundary.
- `ExternalCaptions` and `ResolvedSources`: six-hour, canonical-identity-scoped
  caption/media-track caches. Receiver Next completion is also generation,
  source and content guarded so slow work cannot replace a later cast.
- `tests/fixtures/`: synthetic player/SDK/VTT and public-demo HTML, no media files.
- `tests/manual-chromecast.md`: hardware acceptance checklist and evidence table.
- `scripts/discover-tv.mjs`: bounded read-only SSDP/descriptor probe for the
  exact Philips TV; no playback controls/media/port scanning. Companion parser
  and output/URL-boundary tests: `tests/discovery.test.mjs` (8 fixtures). It now
  preserves the descriptor `Application-URL` only as validated `dialAdvertised`,
  never the raw base, and makes no app requests. Empty XML service entries do
  not exclude DIAL; actual Netflix launch is separately user-confirmed.
- `docs/agent/{philips-tv,mobile-target}.md`: actual TV evidence and final
  phone-powered-off retrieval requirement; physical mobile acceptance pending.
- `docs/agent/philips-matter.md`: paired Philips endpoint inventory, accepted Home
  and speaker-volume readback; user-confirmed phone reconnect/navigation after
  TV power cycle, plus URL/subtitle boundaries.
- `tools/matter-controller/`: separate pinned Matter.js0.17.9 Mac commissioner,
  sanitized inventory, explicit keys/volume/window and cached private-IP hint.
  Private fabric and pairing-window artifacts stay under ignored `.local/`.
- `scripts/philips-controller.py`, `tests/test_philips_controller.py`: Python
  standard-library JointSPACEv6 pairing/Digest client, sanitized context and
  explicit browser/key and stdin-only Unicode requests. This authority is
  separate from Matter. Actual PIN grant succeeded; browser200 did not produce
  a visible launch or receiver traffic, and context labels were inconsistent.
  See [protocol and acceptance boundaries](philips-jointspace.md).
- `src/tv-hello/`, `scripts/tv-hello-server.mjs`: standalone ES5 proof, private
  TV-peer listener, bounded same-origin reports and separate loopback status/
  fixed-reload control. `src/tv-bridge-probe/index.html` is selected by private
  config `mode:bridge-probe`; it samples bridge descriptors and fixed browser/
  media declarations. Current follow-up adds two fixed remote command handlers,
  selected standard/vendor getter reads, constructor-registry names and a fixed
  HbbTV object-presence test. One zero-argument CSManager construction failed
  with TypeError; no opaque platform IPC or relay activation ran. Routine mode now disables native calls; MSX mode supplies
  peer-pinned startup/home and exact-origin framed diagnostics. Media is not fetched.
  Source hashes drive automatic reload on the next heartbeat; a persisted private
  receiver token preserves the same URL across server restarts. Actual TV GET/
  ready/heartbeat, explicit reload and source-edit refresh are verified.
  `tests/tv-hello-server.test.mjs` has eighteen isolated protocol/privacy fixtures.
  [Measured compatibility](philips-browser-compatibility.md) separates actual
  Chrome112 token from the family's documented Chromium122, API exposure and
  codec declarations from actual playback. [Development history](philips-development.md)
  and [execution evidence](philips-app-execution.md) retain the DevView/native
  investigation. That earlier TV-only research excluded Android; current
  phone-hosted MSX work is authorized and documented below.
- `docs/agent/philips-devview-protocol.md`: exact public-client PIN/check/session
  flow, cached-state/native/backend boundaries and13 offline scenarios. Includes
  the verified Homebrew Ghidra12.1.3/JDK21 stable paths and REA wrapper-discovery
  mismatch; no installation/global setting change required. Raw excerpts,
  fixtures, REA/doctor reports and cookie/code-free evidence stay ignored.
- `docs/agent/philips-ghidra-reassessment.md`: actual REA/Ghidra native-control
  acceptance, full UPG recognition/admission results, rejected filesystem hits,
  full first-segment default-key/hash checks, pinned matching vendor upgrade
  source and newly acquired official gateway launch/context analysis. Exact
  Philips native implementation remains unavailable; private evidence is under
  `.local/tv-hello/ghidra-revisit/`.
- `docs/agent/philips-probe-control.md`: actual ping/label receipts and DOM
  readback, two-second polling protocol, bounded TCP/policy/vendor registry and
  HbbTV object results, actual MSX HTTPS plugin/control acceptance and measured
  v/t JSON cache-query/CORS repair, plus REA archive/chunk follow-up. Raw source,
  firmware, capability URLs and evidence remain ignored. Function/source
  diagnostics use exact ready-only shapes with a16KiB aggregate report limit.
- `scripts/msx-mac-proof.mjs`, `tests/msx-mac-proof.test.mjs`: isolated Mac
  direct-browser receiver proof reusing `src/msx/` assets. Explicit private
  interface/TV peer, secret asset/session path, separate authenticated loopback
  status/stdin commands and observed ACKs; no media proxy. Import starts no
  listener. Private connection details remain ignored; physical browser/media
  acceptance is tracked in the [setup handoff](session-handoffs/2026-10-18-philips-browser-setup.md).
- `docs/agent/msx-local-address-source.md`: exact public MSX validator/bootstrap/
  security/XHR byte trace, REA/fixture receipts, retained TLS/zero-byte evidence,
  and explicit limits on the browser private-network-policy inference.
  `src/tv-bridge-probe/index.html` now also has a fixed parent-framework query:
  only version/secure shown onscreen, no external SDK, extended/system getters
  or report-schema changes. Actual TV result remains pending.
- `docs/agent/{luma-receiver-options,receiver-apps}.md`: manufacturer DLNA claims,
  existing Android receiver apps, Kodi/VLC control interfaces and proposed tests;
  Kodi public/actual HLS and Portuguese subtitle acceptance is in `tests/manual-kodi.md`.
- `scripts/kodi-controller.mjs`, `src/kodi/controller.html`: Mac loopback UI and
  allowlisted authenticated Kodi JSON-RPC for the public sample/direct HTTP
  sources, subtitle list/select/off/add, and API-only media-scoped label aliases.
  No media relay or credential persistence; aliases are memory-only.
- `tests/kodi-controller.test.mjs`, `tests/browser/kodi.spec.js`: Kodi transport/
  localhost boundaries and real UI with mocked receiver. `tests/manual-kodi.md`:
  setup/discovery, Kodi 21.2 public MP4 and frameXTV HLS/subtitle evidence, including
  the unresolved rapid-seek stop and remaining phone-off acceptance.
- `kodi-addon/script.lumaremote.metadata/`: source-only Kodi companion that
  validates a strict versioned payload, opens the exact public source with
  canonical movie/episode `ListItem` metadata and records a LUMA unique ID.
  `NativeBackend` brackets playback properties with two item reads and exposes
  identity only when source and metadata are stable. A joining phone may then
  adopt the item, hydrate fixed details/subtitle endpoints without resolving a
  replacement stream, and update its own WatchHistory/Library from Kodi polls.
  [Contract and acceptance boundary](kodi-metadata-sync.md).
- `scripts/package-kodi-addon.mjs`, `tests/kodi-addon-package.test.mjs`: bounded,
  deterministic add-on ZIP and exact-tree/release-asset validation. The guarded
  release path places the ZIP beside the APK and update manifest and uploads all
  three only from matching committed source.
- `docs/agent/`: concise evidence, decisions, and reproducible handoffs.
- `scripts/luma-{protocol,discovery,controller}.mjs`: archived-APK-derived native
  LUMA transport, bounded UDP discovery and loopback HTTP bridge. Persistent TCP
  controls are separate from Kodi; no fileserver or auth advertisement.
- `scripts/stream-local-media.mjs`, `scripts/local-media-remote.mjs`: laptop
  local-file path for LUMA Receiver. The first serves capability-scoped byte
  ranges or high-quality ffmpeg HLS plus subtitle sidecars; the second is a
  localhost-only, random-path browser remote for pairing, root-scoped source
  selection, transport, captions, volume, scrubbing and shared previews.
- `LocalMediaServer.java`: phone-owned private-LAN byte-range server for
  user-selected document-provider MP4/MKV/WebM and external subtitles. Exact
  capability URLs are accepted only by the service/native receiver path, never
  by the browsing WebView or generic public-URL validator.
- Receiver `MediaRequest`/`PlaybackController` and phone `NativeBackend`/
  `ConnectionService`: opaque `local:` identities let a second paired phone
  adopt authenticated receiver playback and reuse projector-cached scrub JPEGs
  without granting it arbitrary access to the initiating phone or laptop.
- Receiver `PlaybackDiskCache`: process-owned Media3 cache for progressive
  opaque local sessions. Playback-priority reads keep the 8–20 second RAM window;
  a lower-priority worker fills whole files that fit or a bounded rolling window
  on constrained storage, including while paused. Opaque session keys are
  cleared on source/Stop/startup. Mounted app-writable external cache surfaces
  are considered and a roomier removable card is preferred; status exposes only
  non-sensitive storage class/capacity/progress, never filesystem paths.
- `src/luma/remote.html`: responsive projector remote, explicit short discovery/
  direct IP, complete icon-backed app inventory/package launch, navigation,
  text and volume refresh.
- `tests/luma-*.test.mjs`, `tests/browser/luma.spec.js`: fake TCP/UDP/HTTP
  boundaries and actual browser flows. `docs/agent/luma-remote.md` records REA
  provenance, physical acceptance and remaining startup/phone work.
- `src/handoff/coordinator.mjs`: bounded receiver launch/readiness/media/subtitle
  orchestration, video-first startup and manual subtitle catalog, observed-index
  bindings, cancellation and a guarded cold-start Open retry. Exact-source
  observation reconciles unresolved Opens. Startup makes no subtitle requests;
  launch and subsequent mutations have one-second settling. Manual language
  selection preserves the playing remote/catalog without another video Open.
  Native adapters prepare selected text before Add, verify complete-body serving
  after index selection, and preflight existing tickets before reselection.
  Explicit unavailable-ticket proof retires known bindings; unknown writes remain guarded.
- `src/mobile/{index.html,app.js,capture.js}`: no-scroll combined phone remote,
  native RPC adapters, same-origin declared source/track capture and matched pause.
  Two D-pad corners provide explicit Air-mouse start/stop and recenter controls.
  Native state gates them to a connected LUMA target; while active, ordinary OK
  requests pointer down/up and Back stays a key.
  The main remote exposes scrub, back 30 and catalog-proven next episode. The same
  exact successor is projected to notification/MediaSession skip-next. A
  current-title tap opens the exact saved Library title/episode, while bounded
  long-press actions delete an episode or whole title through native synchronized
  tombstones. Provider-title mismatches have a distinct user-visible error. A
  verified anime episode exposes a Sub/Dub segmented control inside the subtitle
  sheet only when the opposite edition resolves. Switching uses a fresh guarded
  source handoff at the observed position and adopts that edition's subtitle catalog.
  **Change stream** opens the current canonical watch route in the separate
  app-owned browser without mutating receiver playback. Library can likewise
  open the selected watch route or the verified movie/TV/anime title route, then
  relies on the site's ordinary server picker and existing capture/Cast flow.
  A native-backed title preference stores one −30…+30% brightness value per movie
  or whole series and restores it with source/content guards after each cast. A
  notification tap returns through the app's guarded catalog selection, while a
  lock-screen MediaSession action resolves and opens it in the service without an
  Activity or unlock; neither path guesses or reuses an old stream URL. Scrub input requests the latest
  phone-only preview without seeking until commit; unsupported previews leave
  ordinary scrubbing available. `ScrubPreviewManager.java` extracts bounded
  low-resolution sync frames on demand from direct HTTPS, unencrypted VOD HLS
  media playlists for the exact verified source and canonical content, with an
  adaptive memory-only cache revoked on source/content/service changes.
  [Provider profile and acceptance boundary](scrub-preview-profile.md).
  Native capture accepts the current HTTPS `framextv.cc` and legacy `.xyz`
  exact origins. Packaged UI fixtures exercise capture across that page change,
  not only mocked capture metadata. The native opt-in captures declared SRT,
  ASS/SSA and TTML alongside WebVTT; generic CAF extraction remains WebVTT-only.
- `android/`: phone APK shell and native networking; browser WebView has no
  native bridge. MainActivity owns capture/job lifecycle and visible-app retries.
  `LiveMediaObserver` retains a bounded, page-generation-scoped set of likely
  standard-media requests from the visible WebView, while `LiveMediaVerifier`
  independently checks public HTTPS format evidence only after explicit Cast.
  `MediaHeaders` is the shared phone-side Referer/Origin/User-Agent allowlist;
  browser cookies and authorization are never copied. Normal same-origin DOM
  capture remains first and only source-unavailable pages may use this fallback;
  ConnectionService owns background native-projector and LUMA Receiver traffic,
  MediaSession/notification and cached state. NativeBackend keeps independent
  `nativeConnected` (EShare/navigation 8121) and `receiverConnected` (paired
  receiver HTTP 4191) readiness. Saved auto-connect authenticates receiver HTTP
  first, so casting can start before projector navigation; later retries attach
  native controls without dropping direct playback. SettingsStore encrypts the
  pairing token and caches the private receiver address. Generated assets/APKs
  are ignored. System media actions use bounded wake locks and fresh
  source/revision guards independently of the projector's TCP connection;
  projector volume/navigation actions remain gated on native readiness. Back 30 appears as
  both an explicit notification action and standard MediaSession `ACTION_REWIND`,
  which supplies the lock-screen control only for verified seekable playback.
  Canonical playback observed from another phone is adopted only from stable
  add-on metadata; source/content transitions revoke subtitle delivery, reject
  stale work and cannot attribute the new timestamp to the prior Library item. The
  service also owns a two-second coalesced absolute-volume queue shared by the
  main remote and system media buttons, preserving its projected value while
  polling and cancelling unsent writes on receiver/source changes. The
  [Motorola recovery handoff](session-handoffs/2026-10-18-motorola-kodi-recovery.md)
  distinguishes fixture evidence from pending physical acceptance.
- `android-receiver/`: projector-side HTTP receiver, player, synchronized
  Library and foreground service. `StartupReceiver` starts the sticky service
  for boot/package replacement; `MainActivity` has separate launcher/Leanback
  and `HOME`/`DEFAULT` intent filters. Selecting it once in Android's Home-app
  settings makes the receiver the boot foreground/default Home screen; idle OK
  opens setup and Menu reopens the reversible setting. Receiver 0.3.7 persists
  distinct OK presses and Home-settings launches; the public `info.startup`
  object exposes those counters/timestamps plus live default-Home/UI state.
  `LauncherCatalog` queries enabled launchable packages, bounds labels/icons and
  supplies both the Home logo grid and authenticated app-list/launch endpoints.
  Installed receiver 0.3.17 retains the live-confirmed 28-app catalog including
  Crunchyroll. `CatModeState`, `CatMotion`, `CatToyView` and `CatSoundPlayer`
  own the bounded 12-minute Cat session, deterministic autonomous prey motion,
  Canvas UI, an immediate plus recurring media-stream chirp schedule and
  1.5-second Air Mouse lease. Authenticated
  `cat.*` endpoints use strict milli-normalized integer coordinates.
  Receiver 0.3.17 replaces silent 24 kHz static PCM with checked 48 kHz stream
  writes, playback-head diagnostics and a system-tone fallback.
- `AirMouseFilter.java` and `AirMouseController.java`: pure-Java relative-
  quaternion calibration/filtering plus Activity-owned foreground sensor
  lifecycle. Latest hover wins, only one transport write is in flight and an
  atomic click emits down/up at the current absolute projector coordinate. The
  default High preset is about 23–25% more responsive than Normal. When Cat Mode
  is active, filtered coordinates and OK route to its pointer/pounce endpoints;
  stopping Air Mouse explicitly releases manual control.
- `NativeBackend.java`: `luma.airMouseInfo` reads `GETFBINFO` bounds and
  `luma.airMouse` emits validated `GYROSCOPEEVENT` hover frames;
  `luma.airMouseClick` writes down/up together on the serialized EShare control
  connection. The service suppresses per-frame state publications and follow-up
  playback polls. It also validates Cat status and maps the bounded Cat API.
  Host/backend/browser fixtures cover the contract; the original calibration
  model has user acceptance, while the new sensitivity and Cat UI remain pending.
- `SubtitleNormalizer.java`, `SubtitleDelivery.java`: phone-side bounded text
  conversion to UTF-8 SRT and source/receiver-scoped RAM capabilities. Separate
  fetch work does not block Kodi controls. Up to128 tracks/16MiB stay available
  for the active source; disconnect/config/source/service teardown revokes them.
  Every redirect hop resolves once, rejects mixed public/private answers and
  connects only to an approved address while preserving Host/SNI/TLS hostname
  verification; the validation result is pinned to the HTTP socket.
  Timed SRT/VTT may discard at most 10 cues and 2 percent of a track, retaining the
  discard count; larger malformed fractions still fail the whole preparation.
  JVM runners cover parser/transport; Android subtitle fixtures cover runtime
  XML/charsets and trusted-ticket versus captured-LAN boundaries.
- `src/msx/`, `MsxServer.java`: TV-owned standalone-browser/MSX player/subtitle renderer and
  capability/peer-pinned phone LAN control server. No media proxy. Independent
  service executor/heartbeat and selected-target system controls coexist with
  LUMA/Kodi. Fixed `msx.sample` owns its public MP4/short-lived authorization;
  healthy `error:null` reports are accepted. New pages bootstrap before hello;
  retained current-capability pages can reclaim an unapproved peer in an open
  setup window, still requiring phone approval. Reset revokes the old capability.
  `/tv/connect.html` redirects locally into the receiver; `/msx/connect.html`
  remains only as a compatibility alias for saved links. Standalone pages skip
  the legacy optional player bridge.
  Channel state/status exceptions preserve poll/heartbeat retries and pending
  receipts without replaying executed commands; Ready follows a successful state
  POST. Shared player/channel timer defaults call browser-global delegates;
  direct copied host methods previously threw TypeError after hello in Chromium.
  Native accepted-stage counters/current-state age and sparse stage logs
  distinguish approval from connection. A guarded capability-relative diagnostic
  GET records only six fixed client error codes; it never establishes readiness.
  Fixed ping/label/reload use the approved queue; remote reload waits for state
  plus explicit ACK before page exit. Actual TV matching d66f118d4c revision,
  ping0→1, label ACK and reload/fresh-state ping1→0 are verified. A native-only
  opt-in same-byte LAN fixture is installed: fixed capability/peer range route,
  no general media proxy/private-URL authorization. Actual TV GET206/successful
  Open, stable Pause and resumed progress are confirmed. The user also confirmed
  picture and sound after an uninterrupted replay of this fixed clip.
  See [receiver domain](msx-receiver.md).
- `PhilipsTextController.java`: separate phone-local JointSPACE PIN grant,
  Keystore-encrypted credentials and per-TV certificate pin. Browser setup lets
  the UI choose only native-owned `stable` or `direct` values: the stable
  value is the exact `kiansheik.io` launcher; direct is the exact
  HTTP/private IPv4/high-port origin plus
  `/tv/connect.html`, empty while stopped. Paced/cancellable input receipts
  include mode, total and partial counts; no arbitrary UI URL/text, clearing or
  uncertain retry. Mobile setup sends Matter Enter/Go only after all URL
  characters are acknowledged, with a manual-OK fallback.
  Matter supplies the paired TV's resolved private address. `ConnectionService`
  cancels queued/active typing on target change, reset/disconnect or shutdown.
- `src/mobile/{index.html,app.js}`: distinct TV-remote/video setup. Disconnected
  state says **Set up TV receiver**; setup selects the stable launcher or direct
  phone fallback and has one **Type and open selected address** action that folds
  in saved/first-use PIN permission and Enter/Go. Connected state retains **Open TV receiver**.
  No Media Station X setup is exposed. Sample action and Home/Back remain beside Up.
  Installed debug APK retains real hello/poll/state and observed remote controls.
  Collapsed Developer tools compare loaded/server revisions, uptime/pings and
  reported capabilities. Phone-only fractional-uptime/later-hello notice fixes
  pass29 browser cases, are installed and physically verified by uptime705→1s/
  “TV page reconnected.” after idle app Reload. Public W3C sample14:26:41
  failed MEDIA_FAILED code4/network3/ready0 before metadata; no codec/TLS cause
  follows. Same bytes over LAN now report Playing0:03, Pause0:19 stable, resumed
  Playing0:24; the subsequent replay has user-confirmed picture and sound.
  Full-length completion and broader media compatibility remain unproved.
- `scripts/build-mobile.mjs`, Android asset task: optional explicit
  `LUMA_DIAGNOSTIC_SAMPLE` accepts only the verified4372373-byte/SHA-bound W3C
  clip and removes the generated copy by default. No media is checked in. Root
  verified omission/wrong-file rejection/exact opt-in bytes. Native8/multi4/
  build/lint and signed installation pass; receiver revision stays d66.
- `tests/msx-receiver.test.mjs`, `tests/browser/mobile-receivers.spec.js`,
  `MsxInstrumentation.java`: direct-fetch player/channel, dual-target phone UI
  and isolated emulator HTTP/approval/control/recovery tests. Receiver Node38 and
  `tests/browser/msx-receiver.spec.js` real-browser timer regression pass, covering
  native timer binding, polling/heartbeat, playback timeout and clearing. Preceding
  native MSX 8 passed diagnostic guards; retained ACK/getter failure tests also pass.
  `PhilipsTextInstrumentation.java` covers Digest/HMAC, PIN/certificate/storage
  scope, fixed text pacing, partial failures/cancellation and capability-gated
  browser acceptance versus load proof with fake TV IO.
- `MatterControllerAdapter.java`: service-owned native Matter controller,
  Keystore-encrypted fabric, per-phone pairing, capability-based hardware
  navigation/volume/inputs, confirmed Off and additional-phone windows.
- `ScanMatterActivity.java`, `MatterQrDecoder.java`: private camera scanner;
  `tests/java/MatterQrDecoderTest.java` covers bounded YUV/QR decoding.
- `tests/browser/mobile-matter.spec.js`, `MatterInstrumentation.java`: phone
  pairing/secret lifecycle/composite remote/no-scroll and isolated native JNI,
  trust initialization, encrypted storage and request guards.
- `android/matter-sdk.json`, `scripts/{build-matter-sdk.sh,package-matter-sdk.mjs,
  verify-matter-sdk.mjs}`: pinned official Android SDK build, hashed artifacts,
  production trust assets, logging privacy guard and ARM64/16KB native checks.
  SDK binaries stay ignored; [build details](matter-android-path.md).
- `scripts/build-mobile.mjs`, `scripts/test-android-*.mjs`: mobile assets and
  explicit emulator-only native backend, UI, service/persistence and Keystore tests.
- `LiveControlsInstrumentation.java`: separately confirmed actual-phone
  notification/MediaSession Pause/Play and volume checks with exact starting-volume
  restoration; no fixture reset, media opening or power commands.
- `android/app/src/main/res/`: native projector/action vectors and adaptive
  launcher/themed icon; media cards prioritize guarded volume/playback controls.
- `UpdateManager.java`, `UpdateApkProvider.java`: optional foreground update
  offers from fixed Pages metadata, verified GitHub release downloads and one
  temporary read-only APK grant to Android's installer. No background auto-install.
- `site/`, `Makefile`, `scripts/{release-next-android,release-config,
  configure-android-release,deploy-gh-pages,build-latest-apks}.mjs`:
  guarded next-patch Android release through `make deploy-gh-pages`, plus scoped
  page-only fast-forward deployment through `make deploy-page` and guarded
  signed rebuild and direct installation of the current Gradle phone/receiver
  APK outputs through `make install-latest-apks`, without update manifests.
  One-time
  `make configure-release` creates a mode-0600, Git-ignored JSON config so
  repeated releases do not require signing-variable exports. The release target
  auto-stages bounded allowlisted new text files, ignores unrelated untracked
  files and rejects sensitive-looking/unsupported additions.
  `site/tv.html` is the fixed TV speed-dial launcher; both the Pages builder and
  deployment reviewed-file allowlist must include it.
  `scripts/{generate-update,build-pages,build-android-release,
  publish-android-release}.mjs` prepare/verify local signed releases and publish.
  `android/release-certificate.sha256` is a public fingerprint, never a key.
- `UpdateInstrumentation.java`, `SigningMigrationInstrumentation.java` and
  updater/migration test runners: emulator-only update fixtures and separately
  read-only signing/decryption checks. Seed mode is explicitly emulator guarded.
- `docs/releasing.md`, `release-notes/`: local signing/publication procedure and
  public plain-text notes. No signing credentials belong in the repository/CI.
- `scripts/{build,install}-latest-apks.mjs`: rebuild both current signed Gradle
  outputs, collapse duplicate ADB transports by hardware identity, select the
  existing physical Remote installation, install it with an explicit serial and
  then use the paired receiver update channel. Genuine multi-phone ambiguity
  requires `ANDROID_SERIAL`; no update manifest is involved.
- `tests/handoff.test.mjs`, `tests/browser/mobile*.spec.js`,
  `android/app/src/androidTest/`: coordinator, capture, layouts and Android tests.
- `docs/agent/{phone-app,luma-review-findings}.md`: phone architecture/acceptance
  and bounded public review sample, specific complaints and implementation limits.
- `artifacts/`, `.local/`: ignored local evidence; never commit credentials/media.
- `README.md`: build/load instructions; `package-lock.json`: pinned npm tools.

- `WatchHistory.java`: bounded app-private movie/episode progress keyed by canonical
  content identity; source bindings stay in RAM. Native fixtures cover receiver
  handoff ownership, resume guards and persistence. Service integration is active.
- `CatalogClient.java`: typed metadata/source reads from the frontend’s fixed API
  origins. A provider-owned Films365 title slug that conflicts with catalog
  metadata is rejected and triggers bounded declared-provider fallback; opaque
  source URLs remain unverifiable. `ConnectionService` also evicts cached sources
  with that positive mismatch evidence. Raw source resolution is not equivalent
  to every frontend fallback.
  The full TV catalog and owned WebView resolver are under active integration.
- `AnimeCatalogClient.java`: fixed-origin AniZip/AniList bridge for canonical
  anime identity and episode-specific sub/dub streams. `ConnectionService` uses
  it for resolution, Next and a read-only alternate-edition availability probe;
  its cache keys separate AniList season and audio edition.
- [TV remote controls](tv-remote-controls.md): key/pointer delivery, native caption
  preparation, startup and shared-progress validation boundaries.

- Offline viewing Library: `LibraryStore.java` persists watched title metadata,
  per-episode progress and bounded deletion tombstones in private SQLite;
  `WatchHistory` supplies validated points, and `ConnectionService` serves
  paginated offline reads/durable resume lookup plus phone/receiver deletion sync.
  Phone Library uses existing typed fresh-source handoff.
  [Contract and boundaries](offline-library.md).
