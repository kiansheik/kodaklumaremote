# Handoff

2026-10-18 America/Sao_Paulo. Detailed record:
[session handoff](session-handoffs/2026-10-18-native-cast.md).
Device follow-up: [live Philips test and mobile goal](session-handoffs/2026-10-18-philips-mobile.md).
Receiver research: [LUMA and existing apps](session-handoffs/2026-10-18-luma-receivers.md).
Public receiver proof: [Kodi public playback](session-handoffs/2026-10-18-kodi-playback.md).
Latest: [actual frameXTV HLS and subtitles](session-handoffs/2026-10-18-framextv-kodi-subtitles.md).
New projector-control work: [LUMA replacement remote](session-handoffs/2026-10-18-luma-remote.md).
Latest phone work: [Android handoff and no-scroll remote](session-handoffs/2026-10-18-phone-handoff.md).
Current: [saved setup/background controls](session-handoffs/2026-10-18-phone-background-settings.md).

## Outcome

Implemented a narrow MV3 fallback using the **site's existing** CAF SDK. The site
already implements native Cast; visible native controls take precedence.
Packaged MAIN-world code runs on same-origin `/watch/` and `/embed/` players and
reads declared direct HTTP sources, including child HLS sources behind blob/MSE.
No remote extension code, cookie/header extraction, relay, telemetry or
anti-debugging/auth/DRM bypass was added.

Supported fallback controls: select device/load, pause/resume, seek, volume,
external HTTP WebVTT subtitles and disconnect. Local playback pauses after
successful LOAD; returns paused at the available remote position only if still
on the same episode/source. Dynamic insertion, source changes, stale loads,
navigation, removal and BFCache lifecycle are handled.

## Reproduce

```sh
npm ci
npx playwright install chromium
npm run check
```

Load unpacked **`dist/extension`** in `chrome://extensions` with Developer mode.
Reload `https://framextv.xyz/watch/284833/1/1`. Do not load source or the separate
loopback-only `dist/test-extension`. The production manifest matches only the
observed site origin and does not access cross-origin backup players.

```sh
npm run demo
```

Open `https://localhost:4174/cast-demo` in Chrome; accept its local self-signed
development certificate. The loopback page uses the same shared controller and
sends a public Google-hosted Big Buck Bunny MP4 to the Default Media Receiver.
The generated development TLS identity stays ignored under `.local/tls/`.

`npm run smoke:cast` verifies actual Google SDK readiness in a managed clean
installed Chrome profile, preserving Cast/MediaRouter support. It does not open
the device picker or fetch media. Its exact ignored disabled-features switch
matches pinned Playwright1.64.0; revisit it when upgrading tools.

Research refresh:

```sh
node scripts/recon-http.mjs --assets
node scripts/recon-browser.mjs
BROWSER_CHANNEL=chrome HEADED=1 RECON_OUTPUT=.local/recon/browser-chrome-headed.json node scripts/recon-browser.mjs
SAMPLE_DOM=1 EXTENSION_DIR=dist/extension RECON_OUTPUT=.local/recon/browser-extension.json node scripts/recon-browser.mjs
```

Network/browser access may require the execution environment's normal approval.
Only sanitized metadata is persisted by browser recon, no response bodies/HAR.
Static `--assets` outputs remain ignored; asset hashes may change with site builds.

## Evidence and limits

- Latest automated coverage: build, **60 Node tests**, **25 loaded-MV3 browser
  tests** and **seven Kodi browser tests**. Final label/sort/native-control
  changes were covered by subsequent narrow runs after the full check.
  Browser CAF is mocked. Actual MV3 injection, frame scope and page-world bridge
  are tested, not device discovery/decode.
- Actual SDK smoke passed in Chrome155.0.8059.39 over local HTTPS, secure context,
  zero media requests, no receiver selected.
- Current official sample HEAD returned200, `video/mp4`, byte-range support,
  CORS `*`; old familiar sample returned403 and was replaced.
- Static frontend architecture/playback and existing native Cast code are
  documented with asset hashes/offsets. Two clean automated target visits
  reproduced console clearing followed by `about:blank` without runtime changes.
  Exact detector, manual DevTools opening and extension fingerprinting unproven.
- Live extension injection confirmed in watch and same-origin embed with CAF
  present. Blanking occurred before video/source/control appeared in that probe;
  a later ordinary native Chrome session successfully copied the actual HLS
  source and all 19 declared subtitle descriptors through trusted-click controls.
- Generic Cast receiver playback, long-term expiry and Cast local-return behavior
  on hardware remain unverified. Kodi target HLS picture/sound and Portuguese
  subtitle rendering/timing are now explicitly confirmed by the user.
- No audio-language selector, internal HLS/blob subtitle conversion, cross-origin
  iframe permission, custom receiver or relay. Direct `<video>` fullscreen cannot
  display an overlay outside the replaced element; wrapper fullscreen is handled.
- User-confirmed hardware exception: YouTube public test played picture/sound on
  Philips43PFG6910/78. Native Chrome disables that TV for frameXTV with
  “Available for specific video sites”. This is app-specific playback; generic
  Cast target-media success and phone-off playback remain unverified.

Final mobile acceptance and candidate transports: [mobile target](mobile-target.md).
Bounded read-only renderer discovery: `npm run discover:tv`; sanitized output
stays ignored under `.local/tv-discovery.json`. A negative probe is not proof
that the TV has no service under another mode/network configuration.

## Next highest-value work

LUMA Kodi 21.2 now plays the actual frameXTV HLS and Portuguese subtitles.
All 19 tracks are available, with Portuguese first and English next in the Mac
menu; one displays at a time. Use `npm run kodi` and
[manual instructions](../../tests/manual-kodi.md). Investigate the observed HLS
stop after rapid pause/seek/resume. The Android sender now implements all-track
handoff and a no-scroll combined remote; install its debug APK and perform actual
Samsung capture/playback, phone-power-off, reconnect and expiry acceptance.
Model/Android remain unknown. See [setup](../../android/README.md).

Use [the physical-device checklist](../../tests/manual-chromecast.md) first with
the public demo, then the site's own native Cast control in uninstrumented Chrome.
Exercise the extension only where the native control is absent. Record receiver
model/build and actual TV picture/audio/control results with sanitized evidence.
Finding a URL, SDK readiness, a fake LOAD, HTTP200 or a selected device is not
proof of native playback. If direct streaming fails, isolate the actual provider
or receiver error before proposing a custom receiver/authorized relay.

Chrome's manual **Cast → Sources → Cast tab** is a documented mirroring fallback,
not an extension-native mode. It does not satisfy independent receiver retrieval.

Suggested next prompt: “Install the built Android phone prototype and test live
frameXTV → Kodi launch → all subtitles → Portuguese → combined remote, then
power the phone off and verify continued playback. Resolve actual phone/device
failures before adding touchpad, boot launch or other receiver adapters.”
