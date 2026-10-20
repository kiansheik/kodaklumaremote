# Work log

- 2026-10-18: Read attached mission. Workspace has no existing Git repository or
  agent docs. Initialized baseline notes. Web fetch of target unavailable;
  independent HTTP and clean-browser reconnaissance pending.
- 2026-10-18: Confirmed public reachability outside sandbox; inspected hashed
  frontend assets. Existing native Cast and same-origin embed/source flow found.
  Both clean headless Chromium and headed Chrome reproduced console clearing and
  eventual `about:blank`; no detector bypass or DRM/auth modification used.
- 2026-10-18: Implemented local-code-only MV3 fallback, shared CAF adapter/media
  extraction, deterministic fixtures and HTTPS public demo. Unit/browser checks
  found and fixed hidden-host CSS, source-change races, lost disconnect errors,
  wrong-episode position restoration, and BFCache lifecycle issues.
- 2026-10-18 07:15 America/Sao_Paulo: Live Google SDK initialized on HTTPS demo
  with native Cast enabled. Default Playwright disables MediaRouter; local HTTP
  SDK resources were blocked. Final smoke used a managed ephemeral Chrome profile,
  HTTPS and exact removal of the pinned Playwright disabled-features switch.
- 2026-10-18 07:18–07:20 America/Sao_Paulo: Actual-origin probe confirmed extension
  execution in watch/embed, but blanking preceded video/control appearance.
  Final build +25 unit +15 loaded-MV3 browser tests passed. HTTPS SDK smoke was
  repeated successfully with no selected device or media requests. Updated public
  demo from403 sample to current official200 MP4. Handoff and device checklist
  completed; physical receiver acceptance remains outstanding.
- 2026-10-18 follow-up: Used native Chrome UI on the user's installed extension.
  Verified loaded path, refreshed build and player. Replaced generic Cast error
  with separate sanitized SDK/discovery/session/media errors; full check passed
  build +30 unit +16 browser tests. Live retry reports session failure.
- 2026-10-18 follow-up: TV initially absent while offline; after reconnect native
  Chrome discovered43PFG6910/78 but disabled it for frameXTV (“Available for
  specific video sites”). YouTube connected and reported playing; user confirmed
  TV picture/sound. Exercised sender pause/seek/resume and stopped own test route.
  Mac OS Settings access unavailable; debug internals disabled; neither changed.
- 2026-10-18 follow-up: User clarified phone fully OFF after launch is the final
  product acceptance. Samsung “modo de espelhamento” is likely Miracast; that
  protocol alone cannot meet independent retrieval. Recorded Android Cast plus
  conditional UPnP direct-renderer strategy; added bounded read-only discovery.
- 2026-10-18 07:49 America/Sao_Paulo: Final normal-mode discovery reached exact
  Philips model and read its device description, with no recognized AVTransport
  services exposed and no errors. No media/controls sent. Subsequent `npm test`
  passed33 tests, including3 discovery tests; previous loaded-MV3 tests passed16.
- 2026-10-18 receiver follow-up: User introduced an older app-capable Kodak LUMA;
  exact model/Android unknown. Researched official Kodak DLNA claims, Kodi HTTP
  JSON-RPC, VLC Android 3.6+ Remote access/released URL-opening implementation,
  commercial DLNA receivers and a possible thin receiver APK. Documented first
  experiment and phone-off acceptance. No app installation/device playback or
  application-code changes; documentation whitespace/local-link checks only.
- 2026-10-18 08:10–08:14 America/Sao_Paulo: User configured Kodi. Bounded Bonjour
  discovery found its HTTP service and resolved the user's matching endpoint.
  Implemented loopback Mac UI/allowlisted JSON-RPC commands. Authenticated Kodi
  21.2 accepted public MP4; user confirmed picture/sound. Pause/resume and later
  position confirmed queued seek. Controller stopped at 08:12:01; read-only
  status at 08:12:29 showed ongoing playback. Native Chrome password notification
  blocked initial UI clicks; dismissing it and using native window controls
  yielded live Playing status. No app-code fault established; helpers scoped as
  a module. 44 Node tests +3 new Kodi browser tests passed. No target stream,
  phone app/full phone shutdown or stored credentials/device addresses.
- 2026-10-18 08:21 onward America/Sao_Paulo: Corrected the incomplete public-only
  test by handing the actual declared frameXTV HLS source from ordinary native
  Chrome to Kodi. User confirmed episode picture/sound. Rapid pause/seek60/resume
  stopped playback by itself; restarted at 08:24:09 without seeking and remained
  active at 08:35:28, paused at 531/1355 seconds. HLS seek cause unresolved.
- 2026-10-18 subtitle follow-up: Added trusted-click video/source-list copy,
  Kodi direct-source and subtitle list/select/off/add controls, plus memory-only
  receiver/media-scoped label aliases. All 19 declared tracks registered via
  sequential local API calls; Portuguese (Brazil) selected. User explicitly
  confirmed captions visible and matching dialogue. Other languages registered,
  not individually render-tested. Portuguese/English preference order appears
  in the actual Mac UI. Batch UI and Android sender remain unimplemented.
- 2026-10-18 validation: Full build/check passed at 55 Node/32 browser tests;
  final Node run passed60 and Kodi browser run passed7. Review found copy actions
  hidden alongside a native Cast control; fixed and rebuilt, affected native
  coexistence browser case passed1. Browser count remains25 extension +7 Kodi.
  Saved a subtitle-only screenshot, removed own temporary raw source/track JSON,
  and kept credentials/active links outside Git. Full phone-off remains untested.
- 2026-10-18 LUMA replacement remote: Read the user's new `~/code/rea` checkout
  and Android workflow; used official REA6.7.0 package/pinned verified JADX for
  static analysis of archived Kodak Luma APKv1.0.116. Recovered TCP8121 commands,
  binary app inventory and UDP48689 discovery. No APK execution/installation,
  global REA setup, phone fileserver or projector startup changes.
- 2026-10-18 live remote acceptance: Receiver initially offline, then refused
  control port during boot. Once ready, features identified Kodak Luma and
  pairing replied `configed none`. Optional `getServerInfo` unsupported; omitted
  after source/live comparison. Actual23-app frame parsed; browser Open Kodi
  user-confirmed, with Kodi HTTP refused before and21.2 identified after. User
  confirmed Right/Left navigation. Status read actualvolume6/30.
- 2026-10-18 discovery/review: Physical reply `ECloudBox516` exposed an exact-
  prefix bug; fixed to version-aware matching and verified normal Chrome Find
  projector within the2.5-second search window. Cold-boot speed remains unknown.
  Added reconnect-state propagation/draft preservation, explicit volume refresh
  and native Enter behavior. Tests and remaining gaps are in the new handoff.
- 2026-10-18 phone workflow: Implemented a packaged Android browser plus separate
  local-control WebView, native LUMA/Kodi transport, source-bound launch/readiness/
  media/all-subtitle handoff, PT-BR then English defaults and a no-scroll combined
  Remote. Per-attempt native tokens revoke queued writes; confirmed-unsent Add
  receipts permit safe retry without guessing uncertain subtitle outcomes.
- 2026-10-18 review/UI priorities: Retrieved a bounded public Google Play sample,
  220 unique written reviews. Conservative overlapping coding found87 startup/
  crash,31 connection and11 navigation/lag reports. Not a random sample or usage
  telemetry. Main controls follow household workflow; secondary functions use
  separate paginated/searchable panels. Native/browser fixture results, Android
  build and actual-device gaps are in the phone handoff; no Samsung installation
  or full phone-off acceptance is claimed.
- 2026-10-18 phone testing/steering: Paired wireless debugging at the user's
  request, installed baseline APK on Samsung Android16. User confirmed projector
  control; Cast reached setup/Kodi opening without completed media transfer.
  Requested automatic single-projector connection, cached address, explicitly
  saved login, hidden configuration, slider and corner Home/Back.
- 2026-10-18 background/settings implementation: Service owns native I/O beyond
  Activity destruction and publishes source/revision-bound MediaSession controls.
  Disconnect immediately closes old generation/rejects queued work without Stop.
  Added Keystore/AES-GCM saved authenticated login and receiver cache/reset,
  visible automatic retry, validated first setup with pending-Cast continuation,
  compact status and real absolute volume. REA confirmed normalized setter0..30;
  UI consumes observed readback. Final results/phone acceptance are in the
  [background handoff](session-handoffs/2026-10-18-phone-background-settings.md).
- 2026-10-18 updated-phone live handoff: Safe app-only log reported HLS capture
  with19 tracks and completion through subtitles/selecting/remote/ready. Read-only
  Kodi state showed playing04:51/22:35,19 subtitle tracks, enabled/index6. No media
  or control mutation was sent by this diagnostic. Physical picture/PT-BR and
  phone-off confirmation remain pending; source/credentials were not logged.
- 2026-10-18 phone acceptance/control repair: User confirmed picture, sound and
  Portuguese subtitles from Samsung, then reported cold-start stalling and no
  visible notification/lock controls. Phone diagnostics found granted permission,
  posted/unblocked notification and fresh active media, with null session launch
  target. Added shared launch intent, conditional MediaStyle, immediate display,
  modern Stop/Disconnect actions, preferred-first/lazy subtitles and bounded
  idle-only Open retry with late prewrite-proof cancellation recovery.
- 2026-10-18 validation/install:121 Node,69 browser and22 native checks passed
  across aggregate/narrow final runs. Built/installed0.2.1/code3 and opened it on
  Samsung. Saved setup connected, ordinary public service notification posted
  and media-session launch intent is present. Actual Samsung visibility/actions,
  cold-start retest and phone-off remain pending; see the
  [system-control handoff](session-handoffs/2026-10-18-phone-system-controls.md).
- 2026-10-18 physical0.2.1 failures: user reported missing system controls and
  repeated starts/no subtitles on S1E2. Diagnostics distinguished continued Kodi
  playback from an inactive phone session after upgrade lost RAM media. Subsequent
  own casting logs showed subtitle-stage native errors/timeouts; current Kodi had
  zero subtitles. Replaced3-second metadata budget with30-second bounded reads/
  registration, transient retry and uncertain-Add reconciliation without replay.
- 2026-10-18 controls0.2.2: safe current-playback adoption, separate ordinary/media
  cards and notification coalescing. Fixed service fixtures to wait for current
  posted controls rather than any previous card.128 Node, full70 browser,
  Service4 and packaged UI5 passed. Installed on Samsung;2 live
  notification/MediaSession Pause/Play checks passed, same source left playing.
  Exact S1E2 opened for user subtitle/first-attempt and visual-control acceptance.
  Public PT-BR read succeeded; no receiver subtitle write on unmatched source.
- 2026-10-18 physical0.2.2 result: user confirmed visible working shade/lock
  controls; S1E3 exited Kodi on first Cast and played without subtitles on Retry.
  Own logs distinguish GetItem/API unavailability from later AddSubtitle response
  failure. Receiver log download did not complete; no receiver access policy changed.
- 2026-10-18 controls0.2.3: volume −/Play-Pause/volume + on ordinary/media
  controls, observed readback and projector/action icons. Added main-remote Power
  confirmation using statically verified Kodak KEYEVENT26 flow; accepted send
  disconnects local controls and retains saved setup. No physical power sent.
  Verified known JSON-RPC rejection receipts permit manual Retry; uncertain
  errors remain nonduplicating. Numeric fixed diagnostics retain privacy.
- 2026-10-18 checks/install:130 Node; browser73/74 then final31/31 mobile after
  fixing long-title overflow; native Backend9, Service5 and final packaged UI5
  passed. Built/installed0.2.3/code5 and opened it on Samsung. Live suite safely
  stopped before controls because cached receiver was absent; encrypted Kodi
  login remains. User confirmed projector OFF. Live volume/power and recurring
  S1E3 crash/subtitle acceptance remain open; see
  [handoff](session-handoffs/2026-10-18-phone-volume-power.md).
- 2026-10-18 distribution authorized: attached the empty public GitHub repository,
  implemented install page/scoped Pages workflow, derived signed-APK metadata,
  local-only production signing and optional foreground/manual app updates.
  Existing local release key identified; no key/password uploaded. Public
  fingerprint pins publishing; private debug-to-release migration stays local.
- 2026-10-18 distribution checks:134 Node/77 browser/updater6/packaged UI5 passed.
  Corrected an Intent API compile error; fetched official missing lint dependency
  and passed signed release build. Emulator migration preserved encrypted Kodi
  settings and marker across bootstrap and ordinary production APK, no uninstall.
  Publication/actual-phone evidence follows in the
  [distribution handoff](session-handoffs/2026-10-18-github-release-updates.md).
- 2026-10-18 publication: source pushed; production0.2.4 published with verified
  APK/update.json. Corrected current aapt2 minSdk field handling and inherited
  canonical Pages domain; HTTPS enforced. First deploy was blocked by default
  main-only environment rule; adding version tags and retrying succeeded.
  Page/feed returned200; public APK/CDN bytes matched metadata. Actual phone
  bootstrap→public APK preserved decryption of the existing Kodi login and uses
  the production signer. User then requested simpler direct `gh-pages` deploy
  through `make deploy-gh-pages`; no native APK/feed contract change is required.
- 2026-10-18 dual receiver: user confirmed Media Station X on Philips. Added
  compact target selection, independent simultaneous LUMA/Philips connections,
  direct-fetch TV player/subtitles, explicit local pairing and selected-target
  system controls. Kept hardware-specific projector controls on LUMA.
  154 Node/full84 browser/final41 mobile and35 native emulator checks
  passed; final setup-copy narrow-layout check also passed. Debug build/lint
  passed after an API23 compatibility fix. Signing, publication, installation
  and remaining physical acceptance are tracked in the
  [handoff](session-handoffs/2026-10-18-dual-receiver-msx.md).
- 2026-10-18 dual receiver installation: production-signed0.3.0/code7 installed
  and opened on Samsung. Read-only signer/decryption checks passed before/after
  with encrypted Kodi login retained. User was given MSX startup/approval steps;
  no actual Philips playback acceptance is recorded yet.
- 2026-10-18 dual receiver publication: main72a600e, signed releasev0.3.0 and
  Pages590e0b2 live. HTTPS page/feed returned200 with exact0.3.0 metadata;
  public GitHub/CDN APK matched963,544bytes/SHA-256. Actual TV setup/playback is
  still awaiting user confirmation.
- 2026-10-18 MSX setup follow-up: user reported status0. The phone listener was
  reachable, but0.3.0 rejected a browser OPTIONS preflight.0.3.1/code8 adds scoped
  bootstrap CORS/preflight support, TLS/rejection diagnostics and fixed no-scroll
  setup hints. Native8 MSX+4 dual-service,43 mobile browser and22 focused Node
  checks passed; debug/release build and lint passed. Installed signed0.3.1 on
  Samsung, preserving encrypted Kodi login per read-only before/after checks.
  Actual TV retry and publication are tracked in the
  [handoff](session-handoffs/2026-10-18-msx-setup-matter.md).
- 2026-10-18 setup patch publication: source056b6c9 and signedv0.3.1 published;
  GitHub asset digest/size match the prepared APK. Pages deployment and physical
  TV retry results follow in the setup handoff. No new MsxReceiver event was
  recorded on the updated phone at the last read.
- 2026-10-18 Matter assessment: exact-model Philips datasheet supports Matter;
  bounded read-only discovery observed PHILIPS TV, DT40 Basic Video Player.
  No pairing/control commands or Matter adapter implemented. Hardware/navigation
  capabilities require paired inspection; URL launch and external subtitles are
  not implied. Added [research notes](philips-matter.md).
- 2026-10-18 live setup retest:0.3.1 still reports status0. Generic phone400
  does not establish HTTP arrival; a Mac nonsecret bootstrap diagnostic also
  failed and recorded only a zero-byte TCP timeout. No parser loosening or
  additional APK was attempted. Direct TV browser connectivity test requested.
  Pagesce82fa2 built; HTTPS page/feed and public APK verified200 with exact
  0.3.1 manifest, size and hash. Physical playback remains unconfirmed.
- 2026-10-18 user steering: TV is outdoors in sun; repeated physical setup checks
  are burdensome. Stopped both temporary Mac listeners and switched priority to
  Matter automation. Read-only refresh still observes commissioning mode1/DT40.
  Preparing a separately pinned Mac controller before requesting one setup code.
- 2026-10-18 Matter Mac proof: Matter.js0.17.9 commissioned/read the actual
  Philips Root/Basic Video Player/Speaker endpoints. No ContentLauncher or app
  launcher appeared. Home returned status0; speaker volume1→2→1 was verified
  and restored. Confirmed private IPv4/announced-port cache gave a4.4-second
  CASE/descriptor/speaker read. Enhanced commissioning window succeeded without
  another outdoor TV setup step. Mac default attestation is not production PAA
  verification. [Evidence](philips-matter.md).
- 2026-10-18 native Matter phone:0.4.0/code9/API24+/ARM64 built and installed
  with existing Kodi Keystore login preserved by before/after checks. Native
  code/QR commissioning, encrypted fabric, supported hardware remote and
  temporary additional-phone QR windows implemented. SDK pinned3584c8c8552f,
  Kotlin2.1.10/NDK30,97 verified artifacts/16KB ELF, production PAA/CD roots and
  pairing-log privacy guard.53 mobile browser,22 Node,7 QR JVM,42 native checks
  and debug/release build/lint passed. Actual phone acceptance follows below;
  MSX status0 unresolved. HEAD056b6c9/public release still0.3.1;0.4.0 source is
  uncommitted/unpublished. [Handoff](session-handoffs/2026-10-18-native-matter-phone.md).
- 2026-10-18 actual native phone proof: manual-code commissioning from the
  Mac-opened window completed with production trust enabled, without a new TV
  manual step. Phone speaker+1→2 and−2→1 were independently verified by Mac
  reads and starting level restored. Home/D-pad/Power UI enabled; phone key,
  Off/input effects and camera scan remain pending. APK16KB ELF and
  ZIP alignment verified. No power/input command was sent.
- 2026-10-18 actual phone sharing: **Add another phone** successfully opened a
  TV-accepted enhanced commissioning window and showed its fresh manual code,
  QR and expiry notice. Dismiss/reopen removed code/QR. No second phone was
  enrolled and camera scanning was not attempted; final signed reinstall/
  reconnect remains pending.
- 2026-10-18 final recovery review:54 mobile browser cases passed after fixing
  Matter-only Settings Disconnect and adding a separate confirmed local-only
  forget path for an unreachable/reset TV. It retains the encrypted CA/LUMA
  setup and explains that the TV may retain this controller. Native lifecycle
  fixes synchronize async-pair polling/notification lifetime and replace global
  commissioning shutdown with per-attempt cancellation; native checks and final
  post-commit rebuild remain pending. Earlier42-native aggregate is retained.
- 2026-10-18 final source validation: recovery fixtures exposed stale40% from a
  disconnected MSX session after re-pairing to a no-speaker TV. Connected-target
  volume routing and explicit null volume/input cache clearing fixed it. Final
  browser54 passed; native Matter7/service6/UI5 passed18/18 with debug/test APK
  and lint. Earlier unaffected26 also passed (44 total across runs). Async pair
  polling/foreground lifetime, per-attempt cancellation and confirmed local-only
  recovery are covered. Final committed-source signing/install/publication next.
- 2026-10-18 native Matter release completed: source4d487cf2fdd3613b1d098838cfa5a23134871b0b
  committed/pushed with headers.txt excluded. Final signed0.4.0/code9 build/
  lintVital/ZIP16KB passed; APK9,631,440bytes, SHA-256
  a3f03b098f739d7e4253a0716771cbe7e8a5d7cd1fcf4aa6be73e6d6d93766fa.
  Exact APK installed on Samsung; signer/Kodi Keystore decryption and retained
  receiver/Kodi setup passed. Safe service logs reconnected the saved Philips
  target without a new code. Secure PIN prevented foreground UI acceptance;
  no further dismissal attempts. GitHubv0.4.0 public, Pagesc542b908 built,
  HTTPS install/feed200 with exact signed manifest, and public APK200 matched
  installed size/hash. Camera, second-phone enrollment, phone key/Off/input and
  MSX media acceptance remain open. [Final handoff](session-handoffs/2026-10-18-native-matter-phone.md).
- 2026-10-18 playback automation follow-up: user authorized existing Matter
  controls; Home returned0 with57ms CASE/14ms descriptors. Bounded exact-TV
  SSDP still exposed no services; documented9222/7001 debug routes unavailable.
  JointSPACE API6.1/Linux system endpoints responded; protected activities/
  context require Digest. First pair-request returned SUCCESS/PIN_REQUIRED,
  timeout30seconds; four-digit PIN/grant pending, no browser launch yet.
- 2026-10-18 Mac browser proof prepared: Philips Python CLI12 offline checks;
  peer/capability-pinned Node receiver13 loopback checks and syntax passed.
  Existing assets/channel reused, no media proxy/source fetch or actual LAN
  listener by tests. Saved S1E2 HEAD403 confirmed after provider-scope review;
  Chrome about:blank/public-config403 left fresh capture unresolved, with no
  bypass. Browser/TV playback/PT-BR acceptance pending. [Handoff](session-handoffs/2026-10-18-philips-browser-setup.md).
- 2026-10-18 temporary receiver ready: root started the peer-pinned Mac listener
  and prepared a0600 private browser-launch URL config. Status running=true,
  connected=false, asset/hello counts0. No browser/grant/playback yet. The first
  JointSPACE30-second PIN window expired; await user readiness before another
  explicit request. No auto-start/persistence is installed.
- 2026-10-18 authenticated browser attempt: a fresh explicit JointSPACE request
  and hidden-stdin PIN grant returned SUCCESS. Private0600 credentials are
  saved, with no PIN persistence. Authenticated context200 reported labels that
  disagreed with the user's visible Home; browser POST200 was accepted but Home
  remained visible and the receiver recorded no TV TCP/assets/hello. Application
  enumeration was rejected. A user photo is requested for feedback-based
  navigation rather than inferring focus. The CLI adds proven stdin/hidden
  Unicode input with no field-replacement claim; no new APK or playback proof.
- 2026-10-18 Unicode CLI validation: all15 offline Python cases passed, plus
  the existing13 receiver fixtures. Private public/S1E2 Open commands were
  prepared offline (16 declared episode tracks, PT-BR preferred); no media or
  TV request was sent. Navigation is paused for the requested Home photo.
- 2026-10-18 TV power-cycle feedback: user confirms the signed phone app
  immediately recognizes the TV and visible navigation works after TV off/on.
  Home starts on Netflix at the left edge; user says no photo is needed, so
  the photo blocker is removed in favor of text feedback from this known tile.
  Root retried the saved JointSPACE browser URL once after that state change:
  accepted=true/pageLoadVerified=false, receiver TCP/assets/hello still0.
  User then confirmed it did nothing and remained Home. A read-only context had
  empty focus; one Matter Right from Netflix returned endpoint1 status0
  (CASE62ms/descriptors17ms). Highlighted app label pending; no Select/media
  sent, and no new playback or phone release.
  [Follow-up handoff](session-handoffs/2026-10-18-philips-power-cycle-follow-up.md).
- 2026-10-18 DIAL route found: user identified the first tile as recent Netflix;
  one Matter Right reached regular Netflix. Bounded unicast search0, then
  standard DIAL/MediaRenderer multicast filtered to the paired TV found one
  matching descriptor with `Application-URL`. Existing discovery drops that
  header; empty XML services do not exclude DIAL. Advertised-base GET YouTube403,
  GET Netflix200/stopped; one empty Netflix POST201, then GET200/running.
  Visible app launch awaits user confirmation. No Select/navigation accompanied
  the DIAL launch; no arbitrary media/subtitle payload, code change or APK release.
- 2026-10-18 physical DIAL proof and focus correction: user confirmed Netflix
  visibly opened. They arranged browser1Right/MSX2Right from the initial tile.
  A Home→Right→Select batch reached HDMI1; cause unproven. Home alone then
  returned status0 (CASE64ms/descriptors13ms), with real Home/first-tile focus
  user-confirmed before Right→Select (CASE49/55ms,status0). Latest screen result
  pending; no browser/MSX/media claim. Separate key acknowledgements from focus.
- 2026-10-18 discovery and focus follow-up: read-only discovery now preserves
  validated descriptor `dialAdvertised` only;8 fixtures/syntax/whitespace passed,
  no app fetch/launch added. Right→Select showed ambiguous “Editar speed Dial”,
  with its meaning unproven. One Back plus accepted browser request
  returned to HDMI1; TV receiver counters remain0. Named JointSPACE Home was
  accepted with visible effect pending. No browser/MSX claim or proven cause.
- 2026-10-18 live discovery regression: `node scripts/discover-tv.mjs --no-save`
  returned one exact43PFG6910 target, `dialAdvertised:true`, empty serviceTypes/
  AVTransport and zero errors. No app endpoints were requested by the tool.
- 2026-10-18 final user-away boundary: user confirmed JointSPACE named Home
  visibly returned HDMI1→Home. Right,700ms pause,Confirm were both accepted;
  resulting screen is unknown because the user left before replying. All
  device work/questions stopped, retaining private pairings and the temporary
  receiver. DIAL Netflix visible launch is proven; TV receiver TCP/assets/hello
  remained0 throughout, with no media/subtitles or new phone feature/release.
- 2026-10-18 requested maintenance release: version0.4.1/code10 and accurate
  notes distinguish existing phone features from new desktop Philips/DIAL tools.
  Release/deploy/discovery/receiver Node28 and Philips Python15 checks passed.
  Loopback sandbox failures were environmental; permitted fixture runs passed.
  Corrected an initial older-Gradle selection to cached9.6.1. Production signing
  certificate matches. Committed-source build/publication verification follows
  in the [release handoff](session-handoffs/2026-10-18-release-0.4.1.md).
- 2026-10-18 deployment complete: sourcea84dbb1, publicv0.4.1/code10, signed
  build/lintVital/16KB alignment passed. Pagesf38fa65 is built and Chrome shows
  version0.4.1 with its exact APK link. Anonymous feed/APK GET200; metadata,
  9,631,440-byte APK/SHA-256 and production signature match. Python's CA-bundle
  error was isolated to that client; curl/Chrome verified normal TLS. No phone
  install or TV operation. Desktop probes remain separate from phone features.
- 2026-10-18 Motorola/Kodi recovery: user reports repeated Bly Manor starts,
  startup UI persisting after playback, absent subtitles and a disappearing,
  ineffective locked-screen Pause card. Exact episode and Motorola debugging
  are unavailable.0.4.2/code11 adds exact-source Open reconciliation, subtitle
  readiness and partial success, bounded multi-RPC reads, retained pending
  catalog/card state, independent Kodi HTTP controls and per-action wake locks.
  Node181/mobile55 and debug/test/lint builds passed; isolated fixture rebuild
  passed. Service10/Backend12 native checks and signing/publication are pending
  at this checkpoint. No physical device controls were sent.
  [Recovery handoff](session-handoffs/2026-10-18-motorola-kodi-recovery.md).
- 2026-10-18 final recovery validation: Node184/coordinator52, mobile56 and
  native36 passed (service10/backend12/UI5/settings5/dual receiver4). Strict
  screen-off/keyguard-locked actions and CPU-lock cleanup passed; the emulator's
  original lock setting was restored. A late native-completion receipt now
  releases an already-observed same-source Open and resumes an explicit waiting
  recast with the native idle guard, without overlap or a third tap. Final
  packaged UI was rebuilt/retested after those bridge/coordinator refinements.
  Signed release/public verification follows; actual Motorola acceptance remains
  pending because the device is unavailable.
- 2026-10-18 recovery deployed: publicv0.4.2/code11 from source976a1c3;
  committed-source signed build/lintVital/16KB alignment passed. Pagesf87edf8
  is built. Anonymous feed/APK GET200, exact metadata/hash/production signer and
  Chrome's0.4.2 install link verified. APK9,632,212bytes, SHA-256
  `886e1711953a88257f8dbd0fdbc5c2dd85d859cc11c2cf469ee8eb7609a9032f`.
  No physical phone installation or household playback test was possible.
- 2026-10-19 video-first follow-up: after continued first-start Kodi crashes and
  absent subtitles, implemented the requested manual subtitle flow, one-second
  launch/mutation spacing and two-second coalesced absolute volume. LUMA UI and
  system volume share a native queue; Philips UI timers cancel on explicit
  receiver actions. Node181 passed; browser/native checks and signed0.4.3/code12
  publication are in progress. Physical cause remains unproven.
  [Handoff](session-handoffs/2026-10-19-video-first-volume.md).
- 2026-10-19 final validation: Node181/coordinator46/MSX20, mobile57 and native40
  passed. Final debug/test/lintDebug build and service14/UI5 rerun include the
  deterministic delayed-volume delivery regression. Locked-screen actions,
  two-second single-write bursts, newer in-flight input and source/target
  cancellation passed on the isolated emulator; original lock setting restored.
  Production signing/publication follows; no physical device success claimed.
- 2026-10-19 deployed0.4.3/code12 from sourceb5604ba. Signed committed-source
  build/lintVital/16KB alignment passed. Pages6a7346a built/no error; public
  feed/APK GET200, exact metadata/hash/production signer verified. APK9,648,884
  bytes, SHA-256`f3703b75ceae7678a6908ea4aa096c177b7e9f651920dd07c4ebfa3afda6b7a8`.
  Chrome shows0.4.3 and its download link. No household device controls or real
  phone installation; first-start crash and visible subtitles require retest.
- 2026-10-19 capture hotfix: default `.xyz` URL now resolves over HTTPS to `.cc`,
  confirmed in the connected Samsung's app address bar. The exact-host capture
  guard rejected it and conflated origin and script failures.0.4.4/code13 allows
  both exact origins, starts at `.cc`, supports its VIEW links and separates
  failure messages. Eight focused browser checks, debug/test/lintDebug and native
  UI7 (real capture after an intercepted HTTPS domain change) passed. Signing,
  release and live-phone verification follow. [Handoff](session-handoffs/2026-10-19-capture-domain.md).
- 2026-10-19 deployed0.4.4/code13 from743b890; committed-source signing/lintVital/
  16KB alignment passed. Pages6587626 built; public feed/APK GET200, exact
  metadata/hash/signer verified (9,648,904bytes, SHA-256
  `2b8ed48a21d05d170c99075f66f5f505b80a1df1ba2758735ef923d38f380c42`).
  Installed the signed update on the connected Samsung without reset. One live
  default-page Cast captured22 HLS tracks and reached ready/combined Remote with
  Kodi-reported playback. Projector picture/sound and subtitles not independently
  observed. No extra volume, power, navigation or subtitle commands were sent.

- 2026-10-19 subtitle normalization: sampled twelve public English/Portuguese
  subtitle files across four non-default homepage titles. All were actual SRT
  and converted idempotently with preserved cue counts; format diversity alone
  is not a proven failure cause. Implemented phone-side bounded download, body
  sniffing/UTF-8 SRT normalization, receiver-scoped delivery and complete-response
  observation after Kodi selection. Video-first/manual language behavior remains.
  Known unavailable tickets recover on explicit selection; unknown writes stay
  guarded. Fixed SPA address updates with draft preservation. Node201, JVM
  parser24/transport10 and browser89 checks pass. Android validation, production
  signing and0.4.5 publication follow. [Handoff](session-handoffs/2026-10-19-subtitle-normalization.md).
- 2026-10-19 final normalization validation: Node201/browser89, JVM24+10 and
  Android47 passed, including actual Android TTML/charsets, trusted tickets,
  SPA/draft navigation, closed-Activity locked-screen/service and dual target
  regressions. Final debug/test build and lintDebug passed. Release0.4.5 follows.
