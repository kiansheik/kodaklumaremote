# Native Philips Matter phone remote

## Goal

Deliver durable Matter commissioning/hardware controls inside the existing
Android remote, including manual/camera-QR setup and adding another phone.
Native0.4.0 is now signed, installed and publicly verified; actual manual pairing,
speaker changes and an additional-controller window passed. Preserve LUMA/Kodi
and keep unresolved MSX video/subtitles separate from the completed hardware
remote implementation, without repeated outdoor TV setup steps.

## Files inspected

Required agent index/state/map/questions; Philips Matter, native SDK and earlier
MSX/Mac handoffs; Android manifest/build/README, Matter adapter/scanner, service
and MainActivity RPC; mobile UI/browser fixtures; SDK pin/build/package/verify
scripts; native/JVM fixtures and release notes. Local artifact provenance and
signing/decryption checks were inspected without printing secrets.

## Files changed

- `android/matter-sdk.json`, SDK build/package/verification scripts and Gradle:
  official source3584c8c8552f4a0d471f2aad24a15b478959c230, Kotlin2.1.10,
  API24+, ARM64, hashed local artifacts and production trust roots.
- `MatterControllerAdapter.java`, `ScanMatterActivity.java`, `MatterQrDecoder.java`,
  MainActivity/ConnectionService/manifest and trusted mobile UI: code/QR pairing,
  encrypted persistent fabric, reconnect, capability-based hardware controls,
  confirmed Off and temporary additional-phone windows. The web browsing view
  has no native bridge; only the local remote sees controller RPC results.
- Matter native/JVM/browser fixtures and emulator runner; version0.4.0/code9
  and release notes. SDK binaries/build products/private fabric stay ignored.
- Final review adds explicit local-only forget for reset/unreachable TVs,
  Matter-only Settings Disconnect, async-pair service polling/lifetime updates
  and per-attempt cancellation. Explicit null snapshots and connected-target
  volume routing remove stale volume/input state after recovery.
- `site/index.html`: Portuguese download compatibility copy identifies ARM64
  (64-bit) phones, matching the0.4.0 packaged ABI. No extra test for static copy;
  the existing22 focused release/feed/receiver checks passed earlier.
- Agent index/state/log/map/questions, Philips/native SDK domains, Android README
  and handoffs. Unrelated `headers.txt` remains untouched and excluded.

## Commands run and results

Across the team:

- `bash scripts/build-matter-sdk.sh`, `node scripts/package-matter-sdk.mjs` and
  `node scripts/verify-matter-sdk.mjs`:97 verified artifacts, eight JARs/two
  ARM64 libraries,72 production PAA/five CD roots;16KB ELF loads and notices.
- SDK GN progress/detail logging disabled. Packaging rejects the upstream
  manual/QR-payload format strings, retaining error diagnostics.
- Isolated Gradle `-PreceiverFixture=true :app:assembleDebug
  :app:assembleDebugAndroidTest :app:lintDebug`: passed on final source.
  Committed-source release build/lintVital and ZIP16KB alignment also passed.
- Mobile Playwright fixtures:54 passed, including local-only recovery;
  focused Node:22 passed; QR JVM:7 passed.
- Final focused native18/18 passed: Matter7/service6/packaged UI5, with final
  debug/test APK and lint. Earlier unaffected26 (backend9/settings5/MSX8/
  dual receiver4) passed:44 native checks across runs, not a new full44-case
  rerun. Matter checks cover JNI/trust loading,
  authenticated persistence/tamper failure, QR round-trip and request guards,
  without commissioning or controlling a real TV.
  Final committed-source signing/install/publication passed as detailed below.
- Actual Samsung signed upgrade to0.4.0: read-only signer/Kodi Keystore
  decryption passed before/after installation. No uninstall/reset was used.
- APK16KB ELF and ZIP alignment verified. A dedicated16KB-page kernel runtime
  test is not implied by these static checks.
- Mac Matter commissioning and three-endpoint inventory passed. Home returned
  SendKey status0. Speaker fresh1→observed2→restored1 passed status0/readback;
  no power/input effect test was performed. Right failed before invoke; Left
  was not sent. A private IPv4/announced-port hint enabled subsequent reads;
  short read4.4seconds, CASE2.6seconds and descriptors0.6seconds.
- Actual Mac enhanced commissioning window succeeded and supplied the private
  phone-enrollment code without revisiting TV settings.
- Actual phone manual-code pairing through the UI completed with configured
  production PAA/CD trust enabled. Home/D-pad/volume/Power became enabled.
  Phone Volume+1→2 and Volume−2→1 were each independently read back from the
  Mac; starting volume restored. No Off/input write or phone key test occurred.
- Actual phone **Add another phone** opened a TV-accepted enhanced window and
  displayed a private fresh manual code, pairing QR image and expiry notice.
  Dismiss/reopen contained neither code nor QR. No second phone enrolled and no
  camera scan was attempted.
- Source commit/push4d487cf2fdd3613b1d098838cfa5a23134871b0b (40 files,
  unrelated `headers.txt` excluded); local signed release preparation and
  publication helper completed for0.4.0/code9.
- Exact final signed APK installed with `adb install -r`; read-only signer and
  saved Kodi Keystore decryption passed before/after, with receiver/Kodi setup
  retained. Safe own-process service evidence progressed to
  foreground=true/connected=true on the saved Philips target without a new code.
  No verified MSX media was present, so media `verified=false` was expected.
- Secure PIN blocked foreground UI after the final upgrade. Supported keyguard
  dismissal could not dismiss it; no further attempts. Service-level restore
  passed. Later user feedback confirms the signed phone app immediately finds
  the TV and visibly navigates after TV off/on; no new APK was needed.
- Pages build/API and HTTPS install/feed/public APK checks completed: page/feed
  HTTP200, feed exact signed-manifest equality, public APK exact size/SHA-256.
- Documentation verification: SDK pin/hash verifier and whitespace/local-link
  checks; no secret codes, active media URLs or network addresses added.

## What worked

Native account-free controller and scanner compile/load; encrypted storage
survives fixture recreation and fails closed on tampering. Browser tests caught
and fixed bridge argument arity, asynchronous pairing readiness and self-aliased
capability clearing. Manual/QR secret lifecycle, transient share expiry,
independent LUMA/Philips routing and320×592/390×844 no-scroll layouts pass.

Mac proof establishes actual hardware command/readback and a working route to
another commissioner's setup. The phone subsequently paired and changed speaker
volume with independent readback. Its additional-controller window and secret
clearing also passed on the phone. The TV inventory contains no ContentLauncher,
ApplicationLauncher or ApplicationBasic server; this remains a hardware remote.

## What failed or remains unaccepted

- Physical MSX setup remains status0. Restricted preflights passed fixtures,
  but temporary diagnostics did not establish TV HTTP/TLS arrival. Do not loosen
  parsing or claim website playback/subtitle success based on Matter controls.
- Actual phone camera scan, input/Off effects and another phone's enrollment
  remain pending. Reconnection and visible navigation after TV off/on are now
  user-confirmed; see the [follow-up](2026-10-18-philips-power-cycle-follow-up.md).
- Recovery fixtures initially exposed stale40% volume from disconnected MSX
  after re-pairing to a no-speaker TV. Connected-target routing and explicit null
  volume/input fields fixed it; final54 browser and18 focused native passed.
  The recovery button is separate and confirmed: retain encrypted controller CA,
  remove saved TV node/cache, issue no TV command and report `LOCAL_ONLY`/
  `tvFabricRemoved:false`; the TV can retain its controller entry. No automatic
  reset. Pair cancellation uses `stopDevicePairing(pairingNode)`
  because `shutdownCommissioning()` kills the shared singleton I/O factory.
- Mac Matter.js default attestation did not prove production PAA trust. Android
  retains production verification; missing trust roots must fail closed.
- Upstream Android issuer uses `GroupTesting::DefaultIpkValue`; changing only
  `ControllerParams.setIpk` would mismatch the issuer. Random private CA/CASE keys
  remain protected; the issuer/group-privacy boundary is documented in
  [SDK path](../matter-android-path.md). No issuer fork was made.
- Android floor increased23→24; only ARM64 is packaged. Dedicated16KB-page
  runtime acceptance is separate from ELF verification/ordinary JNI loading.
- Original Kodi cold-start/subtitle failure and full phone-off media acceptance
  remain open.

## Publication state and remaining questions

Public [releasev0.4.0](https://github.com/kiansheik/kodaklumaremote/releases/tag/v0.4.0)
is non-draft, built from source4d487cf2fdd3613b1d098838cfa5a23134871b0b.
The exact signed/installed/downloaded APK is9,631,440bytes, SHA-256
`a3f03b098f739d7e4253a0716771cbe7e8a5d7cd1fcf4aa6be73e6d6d93766fa`.
Version code9/minSdk24/ARM64 match the release metadata.

Pages commitc542b9089cb27708d34478690de1b0625b314dc0 is built. The
[HTTPS install page](https://kiansheik.io/kodaklumaremote/) returned200 and
contains the Portuguese ARM64 compatibility text. Stable/no-cache HTTPS
`update.json` returned200 and exactly matched the local signed0.4.0 manifest.
Public GitHub APK HTTP200 download matched the exact installed size/hash.
An initial feed read still served0.3.1 while Pages was building; final checks
were performed after build completion. No publication or feed verification
remains pending. Signing credentials, pairing codes, fabric keys and active URLs
remain private; no precommit APK hash is used as the release identity.

## Suggested next prompt

“Phone reconnection and visible navigation after TV off/on are confirmed.
Camera/second-phone enrollment and Off/input effects remain separate tests.
Native0.4.0 is already published; preserve saved settings. Resume Philips media
receiver work separately: MSX status0, video/subtitles, original Kodi cold-start
subtitles and phone-off playback remain unresolved acceptance.”
