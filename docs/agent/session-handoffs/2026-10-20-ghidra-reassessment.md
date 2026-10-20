# Ghidra reassessment and new source leads — 2026-10-20

## Goal

Revisit earlier Philips firmware/native-app blockers with the already-installed
Ghidra now working through REA. Find concrete new leads and distinguish actual
native analysis from format recognition and reference-source analysis. Android
remains excluded; preserve the working MSX probe and unrelated dirty work.

## Files inspected

Required agent index/current-state/repo-map/open-questions and recent log;
firmware implementation, DevView protocol and preceding handoff. Actual current
ZIP/UPG and older UPG; unixtract source and existing provenance/bounded parts.
Installed Ghidra loader/filesystem APIs and cached REA6.7.0 CLI. New pinned
vendor upgrade sources, official SDK/TPVBridge, public gateway page/linked JS,
official App Control docs, exact-model manual/source offer and retained emulator
links. Private local listener session/status read without printing URLs.

## Files changed

- New docs/agent/philips-ghidra-reassessment.md: wall-by-wall results, actual
  native toolchain acceptance, full UPG probes and new decoder/gateway leads.
- Required current-state/log/this handoff; index/repo-map/open-questions and
  narrow corrections in firmware implementation/DevView protocol.
- Ignored .local/tv-hello/ghidra-revisit/runtime/: own C/ELF control, REA/Ghidra
  envelopes, receipts, actual loader/filesystem probe script and summary.
- Ignored ghidra-revisit/firmware/: candidate headers, full first-segment rejected
  default-key decodes, structured tests and pinned public vendor source/manifest.
- Ignored ghidra-revisit/bridges/: gateway and exact source ranges, acquisition
  observations, two offline fixtures/results. Gateway REA outputs are separate.
- Ignored msx-preservation-status.json: authenticated local read-only snapshot.
- No production code/Android/global configuration edits, commits or pushes.
  Earlier dirty work and unrelated empty headers.txt preserved.

## Commands run

- Targeted rg/sed/JSON reads and public source/download checks; no broad repo dump.
- clang -target x86_64-linux-gnu -O1 -g0 -fno-stack-protector
  -fno-asynchronous-unwind-tables -c on own control source.
- Process-scoped existing GHIDRA_INSTALL_DIR/JAVA_HOME, REA function control.o
  owned_control --provider ghidra --format json --full-output. Success28.907s.
- Proper REA analyze current-UPG --provider ghidra: typed format rejection.
- Actual analyzeHeadless control import with ProbeContainers.java: non-raw
  LoaderService and FileSystemFactoryMgr tests against control, full ZIP/UPG,
  header and hash region. Corrected run7.344s, complete structured receipt.
- Offline published-default-key AES256-CBC first5MiB checks and SHA384 record
  comparison for both releases; parsed candidate filesystem structures/bounds.
- Read-only Docker daemon version/existing-images requests: each12s timeout.
  No Docker pull/run/create or package installation.
- node gateway-fixture.mjs and gateway-init-fixture.mjs: root reran both;
  9+6 checks pass, network0 and real native0, exact selected declarations/stubs.
- Focused REA source analyses on exact gateway/SDK byte spans, with bounded
  input/timeout30s/heap768MiB:19,654bytes/7spans→2889ASTnodes and
  12,312bytes/2classes→2847ASTnodes,0parsefailures,2.307s/2.423s. Untruncated,
  semantic coverageunknown. Evidence ev_6e060b8b5fc1929e733ffafbf81f646f68fffe51185333452045780e0165e9cf
  and ev_0da6a33c2b855184a3556393f6591ea55e2dd8f7bfc9c4b59f9dcf3a88277d13.
- Authenticated loopback status GET using private session URL: validate loopback
  host, retain private raw response, print only bounded nonsecret metadata.
- Narrow documentation links/whitespace/privacy/evidence checks. No application
  suite rerun required: production probe/server/Android sources unchanged.

## What worked

Ghidra12.1.3 actually imports/analyzes/decompiles an admitted native object via
REA6.7.0. Evidence ev_de3f896a52924e5a9f036acafb1ffac0ef1764c120aeec4e2730dc9af1b605a6.
Owned function's seven instructions/pseudocode match source; this is not a TV ABI
or native Hello World proof. Installed inventory34loaders including Raw Binary,
47filesystems; raw fallback explicitly excluded in target recognition.

New pinned Amazon-distributed MediaTek MT9026 U-Boot source independently matches
the Philips container's field layout and supplies CBC/key-service/plaintext-hash
contracts. SHA384 configuration explains48-byte records as a reference model,
not verified Philips build flags. Both filesystem magic hits are now rejected;
full first-segment tests reproduce/extend earlier default-key failure.

New official public gateway JS traces schema→catalog availability→URL resolution
→platform launchResource→ambient loadApp. It exposes where the native dependency
is consumed and what an actual context test must establish. Fifteen offline
checks validate selected routing/init behavior with synthetic APIs. Official App
Control docs require caller authorization; source does not prove bypassability.
Focused REA analyses parse5736ASTnodes without failure, with unknown semantic
coverage. Inferred DeviceInfo from windowApi.get is not an established network
endpoint. Exact ranges/envelopes remain under private gateway-rea/.

MSX listener still responds: GET1/ready1/heartbeat978/rejected0, commands2/ACK2,
matching source/loaded revisions, remote updates enabled and no pending work.
No TV navigation, debug/enrollment, gateway load or opaque/native IPC performed.

## What failed / limits

REA full-UPG admission rejects unsupported format in0.656s. Actual Ghidra probes
recognize the outer ZIP but not the inner full UPG/header/hash region. These are
root probes, not exhaustive recursive extraction. No matching native executable
or filesystem recovered. Ghidra is available; supported decoded input remains
the barrier. Current archive has one UPG and no clear sibling native helper.

Default-key full first5MiB outputs fail filepartload marker and reference SHA384
record comparisons for both versions. This tests one published key/layout only;
no key guessing or firmware execution. No RSA signature validation. Different-
product source/MTK markers do not prove the TV's processor; TITAN labels TPM256L
NT690. Exact Philips key provisioning/config/native functions remain unknown.

First standalone Ghidra probe failed before probes ran because ProjectLocator
rejects a path component named .local. Agent used an owned temporary path alias,
redirected private caches/settings, reran successfully and removed the alias.
First gateway REA attempts reused a relocated Swift PCM cache with embedded old
paths and failed helper startup; a fresh owned cache fixed compilation. Retain
failure envelopes separately from source-analysis results.

No responsive Docker daemon established. Discovered Vewd4.22 emulator link404;
no verified4.24/native component download. Exact-model manual offers OSS by
request, not a discovered public source archive; no vendor message sent.
Whole gateway was not executed: initialization can invoke vendor getters and
register listeners before READY, so loading it is not a passive-presence test.
External frame/native-binding policy, real account grants and actual TV-loaded
gateway build remain untested. Native Hello World/TCP listener remain unachieved.

## Remaining questions

Which supported frame/app contexts receive getDeviceContent/loadApp and what
exactly enforces caller authorization? Can a bounded context/readiness probe
answer this while preserving MSX and avoiding app launches? Can corresponding
TPM256L sources or a legitimate compatible decoder produce a validated native
component for Ghidra? Is the48-byte footer exactly the reference SHA384 branch?

## Suggested next prompt

“Continue from the Ghidra reassessment. Use the new TITAN gateway context map to
design a bounded runtime test; first account for initialization calls and frame
policy. Keep MSX remote updates working, do not change Android, and separately
pursue a legitimate TPM256L native component using the pinned upgrade source.
Use the existing Ghidra/JDK paths and avoid repeating rejected default-key or
filesystem candidates.”
