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
