# Repository map

- `src/extension/manifest.json`: site-only MV3 manifest, MAIN world, matching
  same-origin frames, no extension API permissions/background/network observers.
- `src/extension/player.js`: video selection, Shadow DOM fallback UI, native Cast
  preservation, navigation/source lifecycle and local-return behavior.
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
  phone-powered-off retrieval requirement; no mobile app implemented yet.
- `docs/agent/{luma-receiver-options,receiver-apps}.md`: manufacturer DLNA claims,
  existing Android receiver apps, Kodi/VLC control interfaces and proposed tests;
  Kodi public-media acceptance is recorded in `tests/manual-kodi.md`.
- `scripts/kodi-controller.mjs`, `src/kodi/controller.html`: Mac loopback UI and
  allowlisted authenticated Kodi JSON-RPC for the fixed public sample; no media
  relay, credential persistence or arbitrary-source endpoint.
- `tests/kodi-controller.test.mjs`, `tests/browser/kodi.spec.js`: Kodi transport/
  localhost boundaries and real UI with mocked receiver. `tests/manual-kodi.md`:
  setup/discovery, actual Kodi 21.2 public-film/control/continuation evidence.
- `docs/agent/`: concise evidence, decisions, and reproducible handoffs.
- `artifacts/`, `.local/`: ignored local evidence; never commit credentials/media.
- `README.md`: build/load instructions; `package-lock.json`: pinned npm tools.
