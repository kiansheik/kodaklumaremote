# Firmware internals and TV-hosted server — 2026-10-20

## Goal

Produce the deeper implementation report requested by the user: obtain the
installed-version firmware if possible, trace exposed low-level features and
specify necessary player/control functions. User steering makes TV-hosted
server feasibility central, including WebAssembly/WebGL ideas. Android remains
excluded. This is research/protocol work, not a player/server deployment.

## Files inspected

Required agent docs; live probe/server and Node fixtures; prior firmware ranges;
public exact-model manual; REA firmware instructions; upstream Chromium112,
its V8/ANGLE dependencies, Emscripten3.1.34 and public Titan/TiVo docs.
Inspected fixed `unixtract` secure-container reader/source at commit
144cf51e3e1b6e832119fde60f676a6def8266f7, without running its binary.
Ignored per-source manifests, link validation and live socket-profile snapshots
record evidence identities and source ranges.

## Files changed

- New `docs/agent/philips-firmware-implementation.md`: main report, complete
  current/older artifact evidence, container boundary, server architectures,
  feature map and function-by-function implementation requirements.
- New `philips-browser-internals.md`: upstream paths/functions, dependencies,
  Direct Sockets exact gates, WASM/GL/host boundary, networking/storage/context.
- New `philips-media-internals.md`: media declaration/pipeline/caption/DRM paths,
  embedder boundary and proposed source-revisioned player/state/receipt contract.
- Current-state/index/repo-map/open-questions/log, browser-compatibility and
  app-execution docs, plus this handoff; README now says18 API-presence checks.
- `src/tv-bridge-probe/index.html`: five additional fixed constructor-presence
  checks (TCPServerSocket/TCPSocket/UDPSocket/WebTransport/RTCPeerConnection).
- `scripts/tv-hello-server.mjs`, `tests/tv-hello-server.test.mjs`: exact bounded
  schema/fixture updated for these five boolean fields.

Private downloads/scripts/source clones/status remain ignored under
`.local/tv-hello/firmware-deep/`. Earlier uncommitted session work is preserved.
Original pairings/fabrics/Android/releases/firmware state and `headers.txt` remain
untouched. No commit/push or vendor message was sent.

## Commands run

- Targeted `rg`, reads and Git status/diff; lightweight registry memory lookup
  found no relevant Philips/framexty/TPM256L/Vewd memory entry.
- Bounded official HTTPS downloads: older ZIP578,406,729bytes; current
  ZIP608,732,145bytes. Current filename was inferred from the known vendor
  pattern, then full body validated against its internal build marker.
- Ignored `inspect-full.py` / `inspect-current.py`: streamed ZIP/member hashes,
  complete member CRC checks, safe single-member extraction, fixed magic scan
  and sampled entropy; no code execution from firmware.
- Ignored `check-container.py`: validates markers/region arithmetic; mirrors
  reader's128-byte known-public-default AES-256-CBC script sample check using
  local OpenSSL. Both samples fail the reader's ASCII-script heuristic. No
  key brute force, full payload decryption or firmware modification.
- Bounded upstream primary-source fetches with manifests/hashes: browser review
  verified62 files and54 source links; media review verified40 pinned links.
- `node --check scripts/tv-hello-server.mjs` and
  `node --test tests/tv-hello-server.test.mjs`: six passed using loopback fixtures.
- Verified own listener PID/command; SIGINT cleanup/restart with persisted private
  receiver URL; source update auto-loaded on actual TV.
- Bounded local status polling saved `socket-profile.json`: GET1/ready1/heartbeat1,
  rejected0, remoteReload true, current/loaded source revisions match.
- `node .local/tv-compatibility-smoke.mjs`: final HTML/real isolated server/local
  Chrome accepted profile, media12 checks, zero external requests/vendor calls,
  and rejected a rawUserAgent extra field.
- Targeted document-link/whitespace/privacy checks and `git diff --check`.

## What worked

The current official ZIP contains one608,642,544-byte Autorun.upg with
TPM256L_V251.003.105.021 at offset256. SHA-256 identities are recorded in the main
report/private metadata. ZIP CRC verified. This is a current-version download,
not installed flash readback or publisher-signature validation.

The secure MediaTek reader's markers/layout match both builds. Current payload
starts4096, length608,632,832; hash region608,636,928+5,616 ends exactly at EOF.
No readable native component has been extracted. The report keeps this boundary
separate from named, pinned upstream implementation references.

Actual HTTP-page constructor results: TCPServerSocket/TCPSocket/UDPSocket/
WebTransport absent, RTCPeerConnection present; WebSocket/WASM/WebGL present.
No constructor, connection, listen, permission, ICE, decoder or GPU work ran.
Upstream source demonstrates a privileged Direct Sockets listener path, while
WASM function imports and WebGL graphics APIs do not grant host socket authority.

The report covers both direct TV-hosted serving and outbound broker control,
plus load/play/pause/seek/buffering/tracks/captions/status/fullscreen/lifecycle.
An independent review checked firmware/provenance and server claims; it caught
and corrected overly broad WebCodecs wording and clarified non-function WASM
imports. Concrete vendor functions remain explicitly unknown.

## What failed / validation limits

The public default key did not produce a recognized script; no filesystem,
browser executable, native backend or accepted TV listener was recovered.
Raw short filesystem signatures were not validated and are not extraction proof.
Entropy is not encryption proof. Current-version marker is not binary identity
with the running device. Corresponding vendor-source package was not found;
exact-model manual gives an English source-request address. No message sent.

REA's firmware adapter needs Linux Binwalk/Unblob and at most128MiB input.
These prerequisites were unavailable; the full UPG exceeds that bound. Docker
availability check hung and our own read-only check was interrupted. No REA
firmware extraction, foreign binary execution or emulator ran. Upstream source
acquisition had corrected unavailable path/CA lookups, recorded in manifests.
No playback, physical picture/sound/subtitles, successful WebSocket/WebRTC,
background lifetime, privileged DevView context or phone-off acceptance was
established. No Android/full-repository tests were relevant or run.

## Remaining questions

Can a documented decoder/key/source release yield validated current components?
Which exact browser/renderer/decoder/GPU/socket services are built into this TV?
Can an approved app context expose Direct Sockets or a supported native IPC
bridge and stay running? Does an outbound control player satisfy the practical
phone-off goal without a TV listener? Stream fetch/decode/audio/captions/lifecycle
remain distinct physical checks.

## Suggested next prompt

“Continue from the implementation report. Prioritize a supported way to inspect
current firmware components or approved TV app socket/IPC permissions. If using
a browser prototype, keep transport separate from the player and verify actual
media/control behavior without claiming a TV-hosted listener. Leave Android
unchanged and preserve private artifacts.”
