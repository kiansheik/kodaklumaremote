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
