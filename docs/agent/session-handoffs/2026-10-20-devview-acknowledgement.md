# DevView acknowledgement and existing Ghidra — 2026-10-20

## Goal

Explain how DevView acknowledges its displayed developer code and what is visible
in public client code versus firmware. Also resolve the user's report that agents
keep missing an existing Ghidra installation. Android/TV/portal state unchanged.

## Files inspected

Required agent index/current-state/repo-map/open-questions; Philips development,
app execution, firmware and prior DevView handoffs. Captured public DevView bundle
and provenance, focused excerpts/REA envelopes; refreshed official DevView/debug
pages and live entry/bundle. Cached REA6.7.0 GhidraInstallation implementation,
Homebrew ghidraRun wrapper, Ghidra application.properties and JDK metadata.

## Files changed

- New docs/agent/philips-devview-protocol.md: complete client protocol/state/timing,
  native/backend limits, exact source identity and verified Ghidra setup.
- Required current-state/log/new handoff, index/repo-map/open-questions and focused
  cross-references/corrections in development/app-execution/firmware reports.
- Ignored devview-analysis holds pairing-flow excerpts/observations/13-scenario
  fixture, refreshed bundle, Ghidra discovery and doctor results. Ignored
  devview-ack-rea holds10 exact source spans, two REA envelopes and provenance.
- No production source, Android, REA checkout, global MCP/config or memory-folder
  changes. Earlier dirty work and unrelated headers.txt preserved.

## Commands run

- Targeted rg/sed/structured JSON reads; lightweight memory lookup had no hits.
- curl only public DevView entry/assets; live bundle SHA-256 matched captured
  681019982612ca30cb3b89826364519b6af194d7301f6cad8249dbbbe4c332c8.
- Private pairing-flow-fixture.mjs:13 named scenarios passed, fake transport,
  hooks/timers/storage, synthetic PIN,0 network fetches; selected declarations
  executed in isolation, whole vendor bundle not executed.
- Cached REA analyze-javascript-application on10 exact disjoint spans/8480bytes,
  timeout30s/heap768MiB/owned compiler cache. Warm retry succeeds6.203s, one file,
  2093ASTnodes,0parsefailures. Evidence ev_ecbe16811b596c61fadc04ed490ce598e678e73b8c1810eb317c1b9f3b3a160c.
  Complete envelope; semantic1180nodes/1103relations/426unknowns, coverageunknown.
- Read-only command/path/wrapper/metadata discovery. Process-only existing
  GHIDRA_INSTALL_DIR and JAVA_HOME overrides with owned Swift cache, cached
  REA doctor --format json --full-output. Approved run confirms Ghidra available.
- Narrow Markdown links/whitespace/privacy and git diff checks. No unrelated
  app suites needed for documentation-only repository changes.

## What worked

Visible DevView flow: credentialed POST devices/link returns PIN; GET
/devices/check with identity/PIN is checked roughly every3s via countdown-derived
SWR keys. Success callback ignores body fields, schedules /apps after2000ms.
POST session supplies firmware/chipset/year; truthy response updates user-storage.
90s and3-code limit are UI constants; backend TTL unknown. Cached UI/401/403/
logout behavior is recorded separately from actual backend authorization.
Native loadApp/enableDevtools are consumers here, not implementations.

Ghidra was already installed: Homebrew12.1.3 in /opt/homebrew/opt/ghidra/libexec,
plus Downloads11.4.2. ghidraRun is on PATH but resolves to a shell wrapper in bin;
REA incorrectly treats bin as root and misses libexec metadata/headless. Stable
process overrides point to libexec and installed openjdk@21. Approved doctor:
configured:true/available:true/providerVersion12.1.3; JDK21.0.12.1 and native arm64
checks pass. No reinstall or global mutation. Required agent docs now carry this.

## What failed / limits

Initial REA cold Swift-cache compile timed out during worker startup; warm retry
succeeded. Initial Ghidra Java spawn returned EPERM in sandbox; approved rerun
resolved it. Neither means Java/Ghidra were absent. Doctor's optional-provider
aggregate can still be unhealthy while Ghidra's own entry is available.

Whole bundle/browser native binary/backend were not executed or extracted.
Focused REA omits most HTTP/SWR/React/store dependencies, so parser success is
not whole-program/authorization proof. Exact TV-loaded DevView revision is unknown.
Portal redeem endpoint, approval internals, session cookie issuance/flags, code
randomness/TTL and native bridge checks remain unseen. No actual pairing code,
MAC/device ID, cookie, credentials or backend API request used/saved.

No TV navigation, enrollment, debug toggle, app launch, firmware flash,
installation, global setup, vendor message, commit/push or Android change.
Working MSX probe/tunnel/listeners were left intact.

## Remaining questions

Does the approved portal account become eligible, and which DevView build does
the TV actually load? What are the permitted native bridge grants in that context?
Can corresponding firmware/source expose those implementations after decoding?
Ghidra is ready for an admitted native binary; UPG decoding remains the barrier.

## Suggested next prompt

“Continue DevView from the client-protocol report. Use the existing Homebrew
Ghidra/JDK paths, preserve MSX remote updates, and distinguish portal/backend,
client cache and native bridge authority. Do not reinstall tools or submit codes
unless enrollment is requested.”
