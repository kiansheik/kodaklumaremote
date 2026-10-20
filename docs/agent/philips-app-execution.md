# Philips app execution investigation

2026-10-20 history. The user wants an independently running custom TV app/player shell.
Kodi is a behavioral reference, not a required package. First prove execution
in a useful TV app context; an ordinary Internet webpage alone is not accepted
as that milestone. Latest clarification asks us to investigate browser version
and URL-app compatibility using REA/direct measurements; that work is recorded
in [browser compatibility](philips-browser-compatibility.md). Do not equate web
JavaScript, a native bridge and process execution. No Android work is authorized.

Latest follow-up: a complete **current-version** official firmware archive is
now acquired and validated at the ZIP/header level. Its secure container is not
yet decoded into browser/native components. The user now asks for implementation
and TV-hosted server details; see [the report](philips-firmware-implementation.md).
The older bounded-prefix work below remains historical evidence.

## Actual platform

Offline decoding of the current session's system response using the maintained
JointSPACE client's public AES-CBC protocol gives:

- Model:43PFG6910/78.
- Firmware:**TPM256L_V251.003.105.021**.
- Live advertised OS:Linux; JointSPACE6.1.

Only model/software fields were decoded for output; serial/device IDs stay
private. System snapshots and the compact result remain ignored under
`.local/tv-hello/`. The [maintained protocol implementation](https://github.com/danielperna84/ha-philipsjs/blob/ade6feb9c792d25e82083f68cf783391f7fe5150/haphilipsjs/__init__.py)
defines the public key and CBC format. This is metadata decoding, not an
authentication bypass or code execution.

[TITAN's platform table](https://docs.titanos.tv/platform-versions) maps TPM256L
to Philips2025 LATAM/MT9676 2K, Cortex-A53×4,1.5GB RAM/8GB flash and Chromium122.
It lists HLS/DASH/MSS. This is vendor family documentation, not a live decoder
or userspace ABI test. Another [vendor table](https://docs.titanos.tv/device-allowlisting)
uses a conflicting chipset label; keep TPM256L as the reliable family key.
Older TPM171E Android rooting work is not evidence for this device/firmware.

The [exact-model manual](https://www.documents.philips.com/assets/20250325/8209e624f4694ae9a48bb2ab00357e47.pdf),
printed pp.53–56, lists licensed Linux/BusyBox/glibc/GStreamer/gdb components and
offers corresponding source by request. A license inventory does not prove
those versions or tools are installed/reachable in the current build.

## Entry points checked

- Existing JointSPACE Digest pairing works at the identity-verified rediscovered
  address through a separate private credential copy. It controls documented
  TV operations; it does not establish process execution.
- One browser-launch acknowledgement produced no Hello World GET/ready. The
  user subsequently rejected that browser proof as the desired outcome.
- DevView is physically present/open, user-confirmed. The user created a Partner
  Portal account and reports waiting for developer verification. No verification
  duration is known. The pairing code was not persisted or submitted by us.
- Both documented developer HTTP ports9222/7001 refuse connections, including
  after opening DevView. No debug toggle was invoked.
- Three bounded passive TCP checks: SSH22 and Telnet23 timed out; ADB5555 refused.
  No login, ADB handshake, payload or service mutation was sent. These checks
  establish only those observations at those ports/time, not global absence of
  a shell or service elsewhere.

## REA and DevView static evidence

The later [acknowledgement review](philips-devview-protocol.md) traces server PIN
issuance/check/session, verifies the live bundle hash and distinguishes cached
UI state from backend authorization. It also records the existing Ghidra setup.

Public DevView bundle `index-BQQTuhjP.js`,679,841bytes, SHA-256
`681019982612ca30cb3b89826364519b6af194d7301f6cad8249dbbbe4c332c8`,
was downloaded for static inspection and never executed. Ignored
`.local/tv-hello/devview-analysis/report.json` retains artifact provenance,
exact source ranges, commands and REA envelopes.

REA6.7.0 full-bundle analysis exhausted its768MiB worker before AST facts;
retained inventory is partial. Four exact focused declarations parsed926 AST
nodes with unresolved dependencies/unknown semantic coverage. No vulnerability
finding or safety conclusion follows from that run.

Source-derived observations, not on-TV runtime proof:

- App/Settings layout checks session-user state, including a persisted cached
  user while fresh session fetch loads. Backend authorization is separate.
  Code/retry is the normal unlinked screen; no normal accountless URL editor
  was found.
- Debug settings call `window.enableDevtools(0|1)` when present, say a restart is
  required, and describe persistence after reboot. It was not called.
- App launching calls `window.loadApp(url,name,'NetTV',resolution)`, otherwise
  `window.open`. That function has no HTTPS-only check, but portal/firmware URL
  acceptance remains unknown.
- These globals are bridge consumers. Their native implementations and actual
  availability/privileges are not in the downloaded bundle. Neither establishes
  shell access, a binary loader or arbitrary code execution.

The [official public app model](https://docs.titanos.tv/introduction) is hosted
HTML5 with TV media/platform integration. [Jellyfin's TITAN client](https://github.com/jellyfin/jellyfin-titanos)
calls its adapter a native shell but injects it into HTML and serves it overHTTP;
that naming is not evidence of an executable installer or sandbox escape.

## Bounded official firmware packaging inspection

A manufacturer-posted support reply linked an older
[TPM256L251.002.192.121 ZIP](https://portaltpv.com.br/down-rede/up-software/UPG_TPM256L_251.002.192.121.zip)
and [6910 update guide](https://portaltpv.com.br/down-rede/software/6910_atualizacao_UPG.pdf).
Direct HTTPS HEAD returned200/application-zip,578,406,729bytes, range support.
This is older than the actual251.003.105.021. It was not installed or fully
downloaded.

Only first/last65,536-byte ranges were retained after exact206/Content-Range
validation. ZIP central directory lists one `Autorun.upg`,578,233,552bytes
uncompressed. A bounded65,536-byte member prefix contains the old version,
MTK markers and `REE_OFFSET_START/LEN` fields. Encryption, signing, partition
layout and executable contents remain unknown; entropy is not proof of any.
Complete archive/member hashes and CRC were not verified.

Reproducible capture, metadata/hashes and partial bytes are ignored under
`.local/tv-hello/native-analysis/`. `inspect-packaging.py` refuses overwriting
existing evidence. REA's firmware adapter requires caller-supplied Linux tooling
and allows128MiB inputs; this whole UPG exceeds that bound. The current build or
a separately identified/extracted component is needed before meaningful native
firmware analysis. A small prefix is not an extracted filesystem.

## Current runtime boundary

The user loaded the read-only probe in Internet and confirmed the initial flags.
Actual reports show loadApp=false,enableDevtools=false,SmartTvA_API=true,
oipfObjectFactory=false. Descriptor-only sampling found exit and hasCapability,
both without a native-code text marker. Values/accessors/methods are not invoked;
these names and markers do not prove an implementation or app-install privilege.

The fixed remote-reload channel and source-hash auto-refresh both passed on the
actual TV after one user bootstrap navigation; no more address entry was needed.
A same-URL listener restart also recovered through an existing-page heartbeat.
The current compatibility page reports Chrome112.0.4147.62, differing from the
family's documented122. See the [measured profile](philips-browser-compatibility.md)
and [input/reload handoff](session-handoffs/2026-10-20-tv-probe-live-control.md).

Server config selects only fixed hello/bridge-probe sources. The listener admits
only the current SSDP/identity-verified IPv4 peer; separate loopback capability
URLs control status and the single reload action. Additional IPv6/stale-IPv4
aliases were explored but not enabled. Raw identities, addresses and capability
URLs stay ignored. Original credentials/fabrics, Android/releases and firmware
remain unchanged.

No native-process Hello World or Home-launcher app is established. DevView
verification is still pending. Current work is browser compatibility discovery,
not vulnerability testing or firmware modification.
