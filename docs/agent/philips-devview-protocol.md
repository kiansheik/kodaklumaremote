# DevView pairing acknowledgement and analysis setup

2026-10-20. Static review of the public DevView client; no enrollment, portal
changes, real-code requests or TV navigation. The working MSX session is intact.
Android was excluded during that earlier review; the user subsequently authorized
the existing phone app. This page's source analysis still makes no device/account
changes. The resumed [local-mode review](philips-sideload-followup.md#resumed-local-devview-review)
records fresh public-source/REA verification and the remaining catalog-context gap.

## What is actually visible

The [public main bundle](https://devview.titanos.tv/assets/index-BQQTuhjP.js)
is679,841bytes, SHA-256
`681019982612ca30cb3b89826364519b6af194d7301f6cad8249dbbbe4c332c8`,
with displayed build `v2.11.4-1ad88063d310f799bec601139c3ba8b01044f8b3`.
The live entry and bundle were fetched again and matched these exact bytes.
This is minified JavaScript, not an extracted native firmware executable. Its
presence in the downloaded UPG, and this exact revision being loaded by the TV's
DevView entry, have not been established.

The [official workflow](https://docs.titanos.tv/devviewinstall) separates portal
DevView-access review from TV pairing: an approved portal user uses Device
Management to enter the code shown by the TV. Entering that code is not itself
developer-account approval. The user reported an account awaiting review; that
account state was not accessed or changed during this investigation.

## How the TV client observes acknowledgement

API base: `https://partners.titanos.tv/tv/api`. The client uses
`credentials: include`; these calls add no explicit Bearer token. Cookie name,
value, flags, issuance and backend validation were not captured.

| Stage | Visible client request or action |
| --- | --- |
| Device identity | Requires a valid SDK deviceID. Uses a nonempty known MAC when available; otherwise passes deviceID as the macAddress fallback |
| Request displayed code | POST /devices/link with JSON fields macAddress and deviceId; display response.pin |
| Observe linking | GET /devices/check with query fields deviceId, macAddress and pin |
| Acknowledge in UI | Successful request and JSON parse invoke the success callback; wait2000ms, then replace location with /apps |
| Refresh session | POST /session with firmwareVersion, chipset and modelYear; a truthy response updates client user state |
| Retrieve candidates | Credentialed GET /apps or /apps/qa, with query filter |

The PIN is returned by the server in this path. No local PIN-generation or
firmware PIN-comparison implementation appears here. The portal's redeem request
and the backend device/account association code are outside this TV bundle.

```mermaid
sequenceDiagram
    participant TV as DevView JavaScript
    participant API as TITAN TV API
    participant Owner as Owner in Partner Portal
    TV->>API: POST /devices/link (device identity)
    API-->>TV: JSON containing pin
    TV-->>Owner: Display code
    Note over Owner,API: Owner submits code; portal/backend implementation unseen
    loop Countdown-derived checks, roughly every 3 seconds
        TV->>API: GET /devices/check (identity and pin)
        API-->>TV: Request result
    end
    Note over TV: Successful JSON response: delay 2 seconds, open /apps
    TV->>API: POST /session (firmware/platform/year)
    API-->>TV: Session response
    TV->>API: GET /apps
```

The acknowledgement component does not inspect a linked/approved boolean in the
JSON body. Any successfully resolved JSON request reaches its success callback.
That is client control flow; it does not show which server conditions produce a
successful response. A redirect alone is not independent proof of authorization.

## Timing, cached state and errors

The UI starts a90-second countdown with1s ticks. Its SWR key includes the PIN
and floor(countdown/3), causing checks roughly every3s; SWR refreshInterval is0.
The key remains non-null at count0 until the parent marks the screen expired.
After fewer than3 successful code creations it requests another code; at the
limit it shows expiry. Manual retry resets that state. These are UI constants,
not a verified backend TTL, entropy contract or inflight-request cancellation.
No WebSocket acknowledgement is used in this path.

Zustand persists user state under localStorage key user-storage. A cached user
can keep the protected UI visible while fresh session retrieval is loading;
a falsy fresh result does not itself overwrite that cached user. API401 clears
user state. API403 navigates to the root without directly clearing it. The
logout handler clears local user state and replaces the route with device-link;
it contains no explicit server logout request or cookie deletion. Server session
lifetime and invalidation remain unknown. Client UI/cache state does not establish
backend authorization.

## Where firmware enters the picture

Pairing does not directly call the debug switch. Settings separately checks an
ambient enableDevtools bridge and invokes numeric0/1 when toggled. Its UI says
a restart is needed and the setting persists across reboot. App launch separately
calls ambient loadApp with URL/name/NetTV/resolution, or falls back to window.open.
Neither bridge's native implementation or permission checks are in this bundle.
The [official debug guide](https://docs.titanos.tv/debugging) describes the Philips
Settings toggle and remote inspection on9222 or7001.

The [firmware investigation](philips-firmware-implementation.md) has not recovered
a readable browser/native bridge binary from the UPG. Ghidra availability does
not decode that container automatically. Distinguish client workflow, remote
backend policy and native platform implementation.

## Ghidra exists: REA's Homebrew lookup mismatch

Installed and read back from application.properties:

- Homebrew Ghidra12.1.3: `/opt/homebrew/opt/ghidra/libexec`.
- Its headless launcher: `/opt/homebrew/opt/ghidra/libexec/support/analyzeHeadless`.
- Installed JDK21: `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`.
- Separate downloaded Ghidra11.4.2: `/Users/kian/Downloads/ghidra_11.4.2_PUBLIC`;
  this is outside the cached REA6.7.0 provider's12.1.x requirement.

The current shell already finds `/opt/homebrew/bin/ghidraRun`. It resolves to a
Homebrew wrapper in Cellar/ghidra/12.1.3/bin; the wrapper executes libexec/ghidraRun.
REA's discovery follows realpath and treats the wrapper's parent bin directory as
the installation root, then fails to find Ghidra/application.properties and
support/analyzeHeadless there. It does not inspect the shell wrapper to find
libexec. This is an installation-discovery mismatch, not a missing installation.

Use process-scoped overrides for REA in this workspace; no reinstall or global
configuration edit is needed:

```sh
GHIDRA_INSTALL_DIR=/opt/homebrew/opt/ghidra/libexec \
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
node .local/npm-cache/_npx/a1722018f28f9df2/node_modules/rea-agents/scripts/rea.mjs doctor --format json --full-output
```

Use an owned REA_DARWIN_MODULE_CACHE_PATH when the compiler-cache sandbox requires
it. An execution EPERM is distinct from an absent or incompatible JDK. Preserve
explicit user environment choices and do not change global registration silently.
With these process-only settings, the approved doctor run reports Ghidra
configured:true/available:true, version12.1.3; JDK21.0.12.1 and the native arm64
decompiler checks pass. The first sandboxed Java launch returned EPERM; that
was an execution restriction, resolved in the approved run. No global config
was changed. Optional providers leave the aggregate environment flag unhealthy;
Ghidra's own entry is available. Headless disassembly remains a separate
acceptance check, now completed through REA on an owned ELF control in the
[Ghidra reassessment](philips-ghidra-reassessment.md). The full UPG is still
unrecognized by independent Ghidra loader/filesystem probes.

## Validation and provenance

Thirteen isolated scenarios execute selected exact declarations with stubbed
request builder/hooks/timers/storage. They check request fields, credentials,
countdown/check keys, success delay/body handling, renewal limits, MAC fallback,
session/cache/error/logout behavior. They use a synthetic PIN and make0 network
fetches. This does not test real cookie issuance, account approval, backend
response policy or firmware bridges. No whole vendor bundle was executed.

Ignored devview-analysis/pairing-flow observations/excerpts/fixture-result retain
exact UTF-8 byte ranges against the bundle hash. Useful ranges: API client/check/
link/session524761–525516; code/ack components545073–547469; session guard
538485–539473; debug state630618–631021. These are source offsets, not firmware
addresses. Live refresh and Ghidra discovery/doctor artifacts also stay ignored.
See the [handoff](session-handoffs/2026-10-20-devview-acknowledgement.md).

Focused REA6.7.0 analysis used10 exact source spans,8480bytes, with0 parse
failures and2093 AST nodes. Evidence:
`ev_ecbe16811b596c61fadc04ed490ce598e678e73b8c1810eb317c1b9f3b3a160c`.
Its complete envelope is retained in ignored devview-ack-rea. Semantic coverage
remains unknown (426 unknowns); HTTP/SWR/backend dependencies are omitted. A
cold Swift-cache startup timeout preceded the successful6.203s retry. Parser
success does not prove backend authorization, whole-bundle coverage or TV behavior.
