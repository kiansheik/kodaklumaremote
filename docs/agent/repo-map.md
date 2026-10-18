# Repository map

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
- `tests/fixtures/`: synthetic player/SDK/VTT and public-demo HTML, no media files.
- `tests/manual-chromecast.md`: hardware acceptance checklist and evidence table.
- `scripts/discover-tv.mjs`: bounded read-only SSDP/descriptor probe for the
  exact Philips TV; no playback controls/media/port scanning. Companion parser
  and output/URL-boundary tests: `tests/discovery.test.mjs`.
- `docs/agent/{philips-tv,mobile-target}.md`: actual TV evidence and final
  phone-powered-off retrieval requirement; physical mobile acceptance pending.
- `docs/agent/philips-matter.md`: exact-model Matter documentation and bounded
  Basic Video Player advertisement; unpaired control/launch/subtitle boundaries
  and the required endpoint capability inspection before integration.
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
- `docs/agent/`: concise evidence, decisions, and reproducible handoffs.
- `scripts/luma-{protocol,discovery,controller}.mjs`: archived-APK-derived native
  LUMA transport, bounded UDP discovery and loopback HTTP bridge. Persistent TCP
  controls are separate from Kodi; no fileserver or auth advertisement.
- `src/luma/remote.html`: responsive projector remote, explicit short discovery/
  direct IP, app inventory/Kodi launch, navigation, text and volume refresh.
- `tests/luma-*.test.mjs`, `tests/browser/luma.spec.js`: fake TCP/UDP/HTTP
  boundaries and actual browser flows. `docs/agent/luma-remote.md` records REA
  provenance, physical acceptance and remaining startup/phone work.
- `src/handoff/coordinator.mjs`: bounded receiver launch/readiness/media/subtitle
  orchestration, preferred-first/on-demand subtitle catalog, observed-index
  bindings, cancellation and a guarded cold-start Open retry.
- `src/mobile/{index.html,app.js,capture.js}`: no-scroll combined phone remote,
  native RPC adapters, same-origin declared source/track capture and matched pause.
- `android/`: phone APK shell and native networking; browser WebView has no
  native bridge. MainActivity owns capture/job lifecycle and visible-app retries;
  ConnectionService owns background TCP/UDP/Kodi, MediaSession/notification and
  cached state. NativeBackend implements transport; SettingsStore encrypts
  validated credentials and caches the receiver. Generated assets/APKs ignored.
- `src/msx/`, `MsxServer.java`: TV-owned MSX player/subtitle renderer and
  capability/peer-pinned phone LAN control server. No media proxy. Independent
  service executor/heartbeat and selected-target system controls coexist with
  LUMA/Kodi. See [receiver domain](msx-receiver.md).
- `tests/msx-receiver.test.mjs`, `tests/browser/mobile-receivers.spec.js`,
  `MsxInstrumentation.java`: direct-fetch player/channel, dual-target phone UI
  and explicitly isolated emulator HTTP/approval/control tests.
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
- `site/`, `Makefile`, `scripts/deploy-gh-pages.mjs`: scoped static install page
  and direct `gh-pages` branch deployment using `make deploy-gh-pages`.
  `scripts/{generate-update,build-pages,build-android-release,
  publish-android-release}.mjs` prepare/verify local signed releases and publish.
  `android/release-certificate.sha256` is a public fingerprint, never a key.
- `UpdateInstrumentation.java`, `SigningMigrationInstrumentation.java` and
  updater/migration test runners: emulator-only update fixtures and separately
  read-only signing/decryption checks. Seed mode is explicitly emulator guarded.
- `docs/releasing.md`, `release-notes/`: local signing/publication procedure and
  public plain-text notes. No signing credentials belong in the repository/CI.
- `tests/handoff.test.mjs`, `tests/browser/mobile*.spec.js`,
  `android/app/src/androidTest/`: coordinator, capture, layouts and Android tests.
- `docs/agent/{phone-app,luma-review-findings}.md`: phone architecture/acceptance
  and bounded public review sample, specific complaints and implementation limits.
- `artifacts/`, `.local/`: ignored local evidence; never commit credentials/media.
- `README.md`: build/load instructions; `package-lock.json`: pinned npm tools.
