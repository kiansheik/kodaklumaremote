# Parallel firmware and privileged API probing — 2026-10-20

## Goal

Continue firmware reverse engineering with REA while testing exposed privileged
TCP/vendor interfaces, working toward a custom remote that does not require
navigating the TV browser GUI. Android stays excluded. User-owned TV, existing
identity-verified private proof URL and credential copies reused; no push/release,
vendor message, firmware flash or account change.

## Files inspected

Required agent docs; current/older firmware packages and metadata; REA6.7.0
CLI/providers/firmware capabilities; unixtract container reader; pinned Chromium
TCPServerSocket/runtime/policy/isolated-context and Window.external source;
public Titan SDK/DevView source; official HbbTV factory reference/ETSI specification
and reference ORB implementation. The opaque opera_omi message format was not
inferred from other vendors' examples.

## Files changed

- `scripts/tv-hello-server.mjs`: fixed ping/set-label queue/control CLI,
  remoteCommands capability, matching receipts, bounded ready-only diagnostics,
  private CSManager source capture with CLI redaction, fixed constructor-attempt
  metadata, HbbTV object presence and
  self-origin object CSP. Existing peer/origin/Host/capability/body constraints
  preserved; only one pending command.
- `src/tv-bridge-probe/index.html`: handlers/receipt dedupe, label DOM readback,
  2s polling, no-bind TCP diagnostic, selected getter/descriptor/Map registry
  sampling, fixed CSManager source/prototype capture, one standard typed HbbTV
  object/method-presence sample after1.5s, then one zero-argument registered
  CSManager construction attempt.
- `tests/tv-hello-server.test.mjs`:13 protocol fixtures, including concurrency,
  capability/restart, schema/bounds/source projection and HbbTV CSP.
- `src/tv-hello/README.md`, agent current-state/index/repo-map/open-questions/log,
  firmware implementation/browser compatibility pages; new
  `docs/agent/philips-probe-control.md` and this handoff.
- Private authored candidates/builders/browser fixtures and raw evidence stay
  ignored. Earlier dirty work and untracked `headers.txt` preserved.

## Commands run

- Cached REA6.7.0 CLI help/providers, then `inspect-artifact` on authentic current
  ZIP. Exact commands/envelopes are under ignored firmware-deep/rea-round2.
- `python3 .../rea-round2/check-chunk-metadata.py`: full encrypted/raw payload
  segment/footer digest comparison for both builds. Six bounded raw components
  retain original byte offsets/hashes and passed source readback checks.
- Targeted primary-source acquisition/search for TCP context/runtime gates,
  Window.external, opera_omi and HbbTV CSManager creation/relay semantics.
- `node --test tests/tv-hello-server.test.mjs`: final13 loopback fixtures passed;
  syntax and narrow diff checks passed. No TV use in unit fixtures.
- `node .local/tv-hello/firmware-deep/round2-smoke.mjs`: final real isolated
  server+Chrome fixture passed ping/label/DOM, fixed constructor-error mock,
  one standard external read/one vendor registry read, Map data enumeration,
  CSManager source/prototype capture and mock HbbTV method presence. Mock CSManager was constructed once; its separate throwing branch was also
  checked. Opaque methods, nested getters and relay getters had0 calls.
  No external requests; maximum measured fixture report4314bytes (limit16KiB).
- Verified own listener PID/command before SIGINT/restarts; preserved receiver
  token/URL. Each source revision auto-loaded on TV without another GUI click.
- Fixed CLI ping/label, bounded loopback status polling and exact receipt/revision
  checks. Private snapshots record successive listener instances separately.
- Final link/whitespace/privacy/JSON/source-revision checks; `git diff --check`.

## What worked

Actual TV ping and label each acknowledged the matching command ID/method. Later
label handlers read back `Controlled remotely` from the DOM before acknowledging.
Commands and automatic code updates required no TV GUI navigation. This is
Mac-mediated control of the loaded page; no claim of incoming TV TCP or mobile/
media integration. Queued and acknowledged results remain separate.

REA independently verified current ZIP/Autorun.upg hashes, complete one-entry
container coverage, and unknown inner format/architecture. Evidence IDs:
`ev_33572564a50a716841c326a888a0aff6c6539068dad19d1bccb570132648e7c5` and
`ev_d1427522d481a5cfe7a6e5b2dfe25f537833a849a17da318788c9b6733b6d0d0`.
ZIP encrypted:false is only ZIP-layer metadata.

Current and older footers equal48bytes×117/111 segments. MD5/SHA1/SHA256/SHA384
of all raw segments, alternate file-aligned segments and entire payloads matched
no footer bytes. Record semantics/authentication remain unresolved.

Actual vendor discovery: opera_omi has sendPlatformMessage,
addPlatformEventListener and constructors accessor. Selected getter read yielded
a registry accepted by the standard Map iterator; safe names include NetworkInterface,
RemoteManagement, DownloadManager, ApplicationManager and CSManager. Only
CSManager was tried with zero arguments; no opaque message was invoked. NetTV Connection/DeviceUI samples
were constants. Window.external returned ordinary search-provider methods.

Fixed CSManager toString capture was38characters/native marker, with no relay
methods on its sampled prototype. The official HbbTV object route was tested:
factoryAvailable:false, objectSupported:null, elementCreated:true,
localMethod:false, remoteMethod:false, errorName:null after1.5s. No URL getter,
relay activation or WebSocket relay handshake ran. A final exploratory
zero-argument CSManager construction returned TypeError, no instance. The error
does not identify its cause or a supported constructor signature. The final
revision again acknowledged ping/label with matching IDs/source hash, rejected0.

## What failed / limits

Current HTTP-page TCPServerSocket remains absent and policy query unsupported;
there was no callable constructor, permission error, bind or accept. Pinned
Chromium close()/failed-open paths are incomplete, so no successful bind was
attempted assuming cleanup. A local fake verifies only the fixed invalid-IP
branch. Other app contexts remain unknown.

Private candidate regeneration initially reused already generated HTML and
created duplicates; a Chrome strict-selector failure caught it. Builder now
uses a hash-verified pre-round2 base. A later escaping error was also caught
before live deployment and fixed. A non-escalated test retry hit the sandbox
loopback-bind restriction; rerun with loopback permission passed13/13. Final
fixture passed; neither failed candidate
was deployed.

REA raw-header native admission failed correctly; Linux firmware/layout engines
and native providers are unavailable/unconfigured. A scoped owned Swift cache
resolved the initial ownership-helper failure. No filesystem/browser binary
recovered, no disassembly, downloaded firmware execution or key brute force.
A registry/native marker is not an implementation, public constructor contract
or privilege grant. HbbTV requires a permitted application context; current
Internet-object result does not exclude DevView/HbbTV modes.

No physical-pixel observation, playback, subtitles, native TV listener,
background/autostart or phone-off acceptance. Android and installed firmware
state unchanged; no relevant Android/full-repository suites run.

## Remaining questions

Can an approved HbbTV/DevView app expose the built-in app2app relay or Direct
Sockets, and what permitted platform identity does it require? Can current
firmware yield decoded components or matched corresponding source? Can a small
player use the proven outbound command transport and later reconnect without
reopening media? Keep these separate from custom incoming TV TCP.

## Suggested next prompt

“Continue from the GUI-free ping/label proof. Investigate supported HbbTV/DevView
app identity and native relay availability using the observed registry and
standard contracts, or build a minimal player on the proven transport. Continue
firmware work only with a concrete decoder/source/component lead. Leave Android
unchanged and keep raw firmware, code and endpoints private.”
