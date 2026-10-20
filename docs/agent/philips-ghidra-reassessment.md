# Philips blockers revisited with working Ghidra

Research date: 2026-10-20. Target remains Philips 43PFG6910/78,
TPM256L_V251.003.105.021. Android is excluded. This continuation revisits
earlier firmware/native-app findings after correcting REA's Ghidra discovery.

The earlier Ghidra provider was unconfigured, although Ghidra was already
installed. That limitation should have been stated explicitly. Correcting the
Homebrew wrapper lookup makes native analysis available; it does not decode an
unknown firmware container automatically. We tested both statements separately.

| Earlier wall | New check | Result / useful next target |
| --- | --- | --- |
| REA could not use Ghidra | Compile an owned ELF control and request its function through REA/Ghidra | Import, analysis, disassembly and decompile succeed; native analysis is operational |
| The current UPG has no readable executable | Present the full file to REA; independently run actual Ghidra loader/filesystem probes | REA rejects unsupported format; Ghidra recognizes the outer ZIP, not the inner UPG |
| Two filesystem magic hits might be extraction points | Parse their candidate headers and bounds | Both invalid; neither is a filesystem recovered from the TV image |
| Only a short default-key sample had been checked | Audit unixtract offsets/IV and decode a complete first 5 MiB segment from each image using its one published default key | Neither yields the reader's script marker; expanded negative coverage, no working key |
| The48-byte-per-segment footer had unknown semantics | Compare public vendor bootloader source with exact container fields | Matching source family hashes decrypted segments and has an AES256/SHA384 configuration; a much better decoder-validation contract |
| DevView's loadApp implementation was missing | Follow the official SDK's public gateway URL and capture its linked gateway code | New launch/app-availability/platform-dispatch implementation in JS; native binding and privileged context remain unresolved |
| Matching native component/source acquisition | Recheck archive membership, emulator download and exact-model source offer | No clear native component found; official corresponding-source request remains an acquisition route |

## Ghidra is now exercised, not just detected

Existing Ghidra 12.1.3 and JDK 21 work with the process-scoped paths in the
[setup report](philips-devview-protocol.md#ghidra-exists-reas-homebrew-lookup-mismatch).
No reinstall or global configuration edit was needed.

The control is our own 24-byte function in a 752-byte ELF object, compiled using
`clang -target x86_64-linux-gnu -O1 -g0 -fno-stack-protector
-fno-asynchronous-unwind-tables -c`. REA6.7.0's
`function <control.o> owned_control --provider ghidra --format json --full-output`
completed in 28.907 seconds. Ghidra returned seven instructions and pseudocode
equivalent to the source: 11 for input 13, otherwise 7*x+3.
Evidence: `ev_de3f896a52924e5a9f036acafb1ffac0ef1764c120aeec4e2730dc9af1b605a6`.
This validates our toolchain; it says nothing about the TV's CPU/ABI.

The proper REA command `analyze <current Autorun.upg> --provider ghidra` instead
returns `target_unavailable`, `unsupported_target`, constraint
`file_format_rejected`. Independently, a headless Ghidra script uses
`LoaderService.getSupportedLoadSpecs`, excluding the raw BinaryLoader, and
`FileSystemFactoryMgr.test`. Results:

| Input | Recognized non-raw loader | Recognized filesystem |
| --- | --- | --- |
| Owned control.o | ELF | false |
| Official current ZIP608,732,145bytes | none | true |
| Current Autorun.upg608,642,544bytes | none | false |
| Header4096bytes | none | false |
| Hash region5616bytes | none | false |

The installed inventory has 34 loader names including Raw Binary and 47 filesystem
types. These are root-level recognition tests, not a recursive extraction or a
proof that every decoder/plugin would fail. No architecture was guessed to
force ciphertext through a disassembler. No TV code was executed.

Private receipts, probe script, control source/object, envelopes and logs:
`.local/tv-hello/ghidra-revisit/runtime/`.

## Firmware extraction: new vendor-source contract

The official current archive still contains only Autorun.upg; there is no clear
updater executable or sibling browser library inside the ZIP. Existing complete
hash/CRC/version provenance remains in the
[firmware report](philips-firmware-implementation.md).

An important new primary source is Amazon's published MediaTek MT9026 U-Boot
[upgrade header](https://github.com/Amazon-OSS-Kernels/android_kernel_common-android13_gki_aarch64/blob/308bb911d12ac01ce29fb7c98d42a1c705cba172/bootable/bootloader/uboot-mtk/mt9026/u-boot-2019.04/Mediatek-internal/impl/upgrade/include/upgrade_utility.h)
and
[secure upgrade implementation](https://github.com/Amazon-OSS-Kernels/android_kernel_common-android13_gki_aarch64/blob/308bb911d12ac01ce29fb7c98d42a1c705cba172/bootable/bootloader/uboot-mtk/mt9026/u-boot-2019.04/Mediatek-internal/impl/upgrade/secure/secure_upgrade.c),
pinned at308bb911d12ac01ce29fb7c98d42a1c705cba172. Its header defines the same
MTK...../.....mtk markers,0x1000 packed header,0x200 version and image-offset
areas,0x80 chunk structure and0x100 signature size. The chunk fields match the
observed segment/data/hash/buffer layout.

This is a format-family reference from a different product/vendor/SoC, not
recovered Philips code. TITAN's platform data labels TPM256L as NT690; the
container's MTK markers alone do not identify our TV's processor.

In that source, `check_chunk_info` validates segment count against hash length
and exact data/hash/file boundaries. `check_image_segement_SHA` reads through
`filepartloadSegAES` and hashes its decrypted output before comparing each hash
record. `common_sha` selects SHA384 under CONFIG_SECURE_AES256_SHA384 and SHA256
otherwise. AES decryption obtains an upgrade key by a secure-key service;
the first script has a zero IV and later reads recover CBC chaining from the
preceding ciphertext. This gives a concrete way to validate a future decoded
segment against its footer, rather than relying only on printable strings or
filesystem magic. Actual Philips signature/key-service implementation and
configuration remain unknown.

The expanded default-key check audits unixtract's AES256-CBC offset4096/zero-IV
behavior and processes the complete first5MiB from both current and older UPGs.
The original128-byte results reproduce. Neither first32768bytes nor the full
segment contains `filepartload`; output entropy is approximately7.99996bits/byte.
The SHA384 of each rejected decoded segment also fails to match its first
48-byte footer record. This rejects that published key at that layout; it is not
key exhaustion or a proof that every decoder route is closed. No key guessing
was performed.

The previous BE-SquashFS hit at355297296 has invalid version/block-size/size
fields. The UBIFS hit at309527958 has invalid node type/group and a length beyond
the remaining file. Both are false positives. A signature hit was never a
recovered filesystem.

The installed Ghidra includes SquashFS/EXT4/CPIO and other filesystem support;
that will be useful once a validated clear image is available. Linux Binwalk/
Unblob are still unavailable. Docker CLI/context exist, but approved read-only
daemon version and existing-image queries each timed out after12s; a usable
extraction VM was not established. No image was pulled, created or run.

Private source captures, candidate bytes, rejected default-key outputs and
structured comparisons: `.local/tv-hello/ghidra-revisit/firmware/`.

## Native app bridge: new public gateway artifact

The official SDK's TPVBridge points to
[TITAN's gateway page](https://app.titanos.tv/gateway/index.html), which links
[gateway.js](https://sdk.titanos.tv/gateway/gateway.js). The captured page is313
bytes; linked JS259,059bytes, SHA256
`90ea105a16c08736cf628d3bdbe0ecc2d6d14ff31cf2797b735ab1160c020d01`.
This is newly acquired readable JS, not native Philips firmware. It can be
analyzed directly without Ghidra.

The launch schema accepts a nonempty app code and optional trimmed query;
there is no literal app-code allowlist in this JS. It requests app availability
from `/v1/apps/{code}` with market/device/firmware/model/locale and requires app
metadata before dispatch. A query that is a complete HTTP(S) URL becomes the
resolved launch URL; other queries are combined with the catalog app URL.
Some app codes have special platform dispatch. The normal path forwards through
the selected platform's launchResource.
The TPV implementation ultimately consumes window.loadApp. The wrapper reads
the current window's globals; it does not provide loadApp to a page where it is
absent. Platform selection tests for getDeviceContent before choosing TPV.
This narrows the question to which app/frame contexts receive those native
bindings, rather than assuming the SDK itself contains an app loader.

The gateway's visible message dispatcher requires a type and requestId and
uses fixed launch/device-info/TTS handlers. It passes event.origin as context
without a visible origin/source comparison there; replies use parent postMessage
with '*'. The SDK client has separate iframe-source/request bookkeeping.
These observations are not a finding that native permissions or app enrollment
can be bypassed: firmware binding policy, surrounding delivery controls and the
actual TV-loaded gateway revision remain unknown. No gateway messages or app
launch were sent to the TV in this review.
The [official App Control contract](https://docs.titanos.tv/titan-sdk-app-control)
explicitly requires authorization of the calling app. The captured JS is not
enough to identify where every required permission check is enforced.

Loading the whole gateway is not a presence-only test. Initialization can call
getEnvUrl/getPlatformConfig when present and register language/country listeners.
Its TPV
notification service can subscribe through omi_platform.addPlatformEventListener
before emitting LAUNCHER_READY. The ready message contains type/timestamp, not
platform identity. The captured HTTP HEAD response had no CSP/X-Frame-Options header;
that does not establish our parent page's frame policy or native binding grants.
These side effects were traced statically; the gateway was not silently loaded
into the working TV probe.

Fifteen offline checks pass on exact selected declarations with in-memory stubs:
nine cover message routing, READY/source checks, reply bookkeeping and launch
URL resolution; six cover successful TPV initialization and fixed accessibility
settings reads. They make0 network calls and0 real vendor calls. The TPV fixture
records seven initialization stub calls: getEnvUrl, getPlatformConfig, language/
country listener registration and three OMI event subscriptions. A settings
request separately reads three named getDeviceContent values. Browser cookie/
frame behavior, other platforms, error paths and the whole bundle were not run.

Captured SDK spans, gateway, response headers and analysis stay private in
`.local/tv-hello/ghidra-revisit/bridges/`.

Focused REA6.7.0 static analyses also succeed on exact byte spans, without
executing vendor code:

| Input | Selected bytes/spans | AST nodes / parse failures | Semantic coverage |
| --- | --- | --- | --- |
| New gateway | 19,654 / 7 | 2889 / 0 | unknown; 1703 nodes, 1055 relations, 604 unknowns |
| SDK bridge client | 12,312 / 2 classes | 2847 / 0 | unknown; 1724 nodes, 1487 relations, 676 unknowns |

Both outputs are untruncated; times2.307s/2.423s. Evidence IDs
`ev_6e060b8b5fc1929e733ffafbf81f646f68fffe51185333452045780e0165e9cf`
and `ev_0da6a33c2b855184a3556393f6591ea55e2dd8f7bfc9c4b59f9dcf3a88277d13`.
Raw envelopes/ranges/source hashes are under
`.local/tv-hello/ghidra-revisit/gateway-rea/`. These excerpts omit surrounding
dependencies. An inferred DeviceInfo endpoint candidate from windowApi.get is
not proof of HTTP/network activity; firmware authority is not resolved by this
semantic graph. Initial relocated Swift-cache startup failures are retained
separately; a fresh owned cache fixed compilation.

## Practical next leads and remaining limits

The gateway is designed to operate in a TV app context that supplies native bindings.
Track that launch milestone separately from arbitrary machine-code execution
and from firmware extraction.

1. Use the gateway initialization/platform map and offline fixture to design a
   bounded context/readiness test before asking it to launch anything.
   Determine whether a supported app context gets getDeviceContent/loadApp.
2. Use the matching upgrade-source structure and decrypted-segment hash contract
   to assess a real model-compatible decoder/component. Repeating ciphertext
   disassembly cannot settle native API implementation.
3. Obtain the exact-model corresponding open source through the
   [manual's source offer](https://www.documents.philips.com/assets/20250325/8209e624f4694ae9a48bb2ab00357e47.pdf).
   The retained manual was checked; no vendor request was sent. OSS availability
   does not promise proprietary Vewd/native bridge source.

A native-process Hello World and TV-hosted TCP listener remain unachieved. The
working MSX probe/control/tunnel was preserved. No firmware installation,
enrollment, debug toggle, Android modification or global tool change occurred.
Final authenticated loopback status still responds: GET1/ready1/heartbeat978,
rejected0, both earlier commands acknowledged, source/loaded revisions equal,
remote updates enabled and no pending reload/command. This is an observed
counter snapshot, not a new media/native execution test.
