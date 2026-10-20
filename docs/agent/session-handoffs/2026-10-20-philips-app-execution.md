# Philips app execution discovery — 2026-10-20

## Goal

First prove our own TV app execution suitable for a custom player shell. The
user rejects ordinary Internet Hello World as the outcome; Kodi is figurative
behavior, not an install/port requirement or a mandatory programming language.
Android remains excluded until separately requested. Preserve distinctions
between app JavaScript, native bridges, process execution and root.

## Files inspected

Required agent docs and prior Philips development/DevView evidence; maintained
JointSPACE upstream CBC implementation; private current system snapshots;
public DevView source and exact focused declarations; official TITAN platform,
app/developer/debug docs; exact Philips manual/open-source notices; older
manufacturer-linked TPM256L ZIP metadata and two bounded ranges. Full artifact
provenance/limitations are in [execution evidence](../philips-app-execution.md).

## Files changed

Agent execution domain, current-state/development/index/repo-map/open-questions/
log and this handoff. Follow-up implementation adds
`src/tv-bridge-probe/index.html` and extends `scripts/tv-hello-server.mjs` with
fixed private probe mode, ready-only bridge booleans and aggregate connection/
rejection counts. Ignored `.local/tv-hello/{devview-analysis,native-analysis}/` retain static
evidence, scripts and partial artifacts; model/firmware snapshot is private.
Stopped the ordinary browser proof's own verified PID with SIGINT; later started
a fresh private listener for the presence-only bridge diagnostic. Pairings
retained. No Android/release/firmware edit or deployment.

## Commands run

- Targeted `rg`/file reads and `git diff --check`.
- Offline Python/openssl decoding of only model/software fields using upstream
  public AES-CBC format:43PFG6910/78,TPM256L_V251.003.105.021. No device command.
- Public primary-source web research, source download for static analysis only.
- Cached REA6.7.0 `analyze-javascript-application` with30s deadline/768MiB bound:
  full679,841-byte DevView bundle exhausted worker heap before AST; four exact
  focused declarations parsed926 AST nodes, unresolved dependencies and unknown
  semantic coverage. Full commands/envelopes retained in ignored report JSON.
- Three bounded TCP connections to verified TV22/23/5555, passive possible-banner
  read only: SSH/Telnet timeout, ADB refused. No credentials/handshake/payload.
- curl verified TLS/HEAD and exact206 first/last65,536-byte ranges of older
  official ZIP; Python central-directory parse/bounded DEFLATE prefix. Retained
  `.local/tv-hello/native-analysis/inspect-packaging.py` reproduces capture and
  refuses existing files. No full archive download or execution.
- Private-session PID/command verification, SIGINT to only our browser proof.
- `node .local/tv-bridge-smoke.mjs`: actual fixture server + local Chrome
  absent/present bridge tripwires, zero native calls, invalid-heartbeat-field
  rejection,720p fit/no JS errors. Final screenshot visually inspected; its
  flags are fake fixture values. `node --check`, `git diff --check` passed.
- Listener start/status in bridge-probe mode: actual GET/ready/connections0,
  bridges null. No URL/key/native command was sent without field-focus feedback.
- Address-family validation caught an invalid IPv6 additional-peer config;
  removed it. Current config admits only original verified IPv4. Follow-up
  metadata comparison confirms savedIPv6 identity, while savedIPv4 refuses.

## What worked

Exact live-session firmware identity is now established. Official table maps
TPM256L to Philips2025 LATAM and documents its media/app runtime family.
Source-derived DevView normal enrollment guard and launch/debug bridge consumers
are recorded with exact artifact identity. Older official UPG family packaging
is confirmed using only128KiB of network data; all raw/partial data stays ignored.

## What failed / limitations

The user created a portal account but reports waiting for developer approval.
No known ETA, enrollment or debug toggle. No accountless native loader found.
CDP still refuses; passive common-shell checks do not establish universal service
absence. REA whole-bundle AST failed on heap; focused analysis is not runtime
validation, complete semantic coverage or a vulnerability finding.

Older ZIP251.002.192.121 is578,406,729bytes and contains one578,233,552-byte
Autorun.upg, exceeding REA's128MiB firmware input bound and requiring Linux
extraction tooling. Only a64KiB member prefix was decompressed. Signing,
encryption, filesystem structure and current-build applicability remain unknown.
Python default CA trust failed; curl succeeded with normal verification.
No Hello World/native process/custom TV app launch, sandbox escape, root or
arbitrary code execution is established. No unrelated old Android-root method
was attempted, nor firmware update/reset/flash, active exploit or Android change.

## Remaining questions

Can we obtain the exact current firmware/corresponding source for offline native
interface analysis? Can approved DevView load our minimal app and reveal actual
bridge/media capabilities? Does any verified accountless app execution route
exist for this exact firmware? The concrete next on-TV diagnostic is presence
of DevView's bridge globals in Internet; user has been asked for empty/selected
address-field focus. It does not invoke a native function. Do not substitute a
browser-only success for native app execution.

## Suggested next prompt

“Continue Philips TPM256L_V251.003.105.021 execution research. Keep Android
untouched. Seek a matched firmware/source artifact or a verified app-loading
entry point; preserve exact artifact/probe boundaries. First demonstrate our own
minimal TV app execution, then build our player shell. Do not flash/reset or
apply unrelated Android rooting recipes.”
