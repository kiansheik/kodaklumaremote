# Philips development and Hello World

Checked 2026-10-20. Historical discovery and first-proof notes follow. The user
excludes Android changes until separately requested. The Internet probe is now
running with verified remote reload/source updates; current scope is browser
implementation and URL-app compatibility. See [measured profile](philips-browser-compatibility.md)
and [execution history](philips-app-execution.md). Earlier zero-counter snapshots
below describe the initial launch attempt, not current runtime state.

## Platform and supported loading routes

The Philips43PFG6910/78 runs TITAN OS. Its live JointSPACE6.1 metadata reports
Linux, Digest pairing, secured transport, browser activities and Unicode input.
Android APK/ADB instructions do not establish an install route on this TV.
Samsung's comparable TV developer platform is
[Tizen](https://developer.samsung.com/smarttv/develop/faq/specifications-and-feature-support.html).

TITAN's [official introduction](https://docs.titanos.tv/introduction) describes
hosted HTML5 applications supplied by URL. Two useful paths are:

1. **Internet browser:** the [exact-model manual](https://www.documents.philips.com/assets/20250325/8209e624f4694ae9a48bb2ab00357e47.pdf),
   printed pp.4/36, documents the Internet app and viewing websites fullscreen.
   This is the simplest initial execution proof; it does not install a Home app.
2. **DevView:** [official testing instructions](https://docs.titanos.tv/devviewinstall)
   describe Partner Portal account → DevView sandbox app URL → Device Management
   pairing using the code displayed by DevView on the TV → launch candidate.
   On newer TITAN devices, search Apps → Lifestyle for DevView. Portal DevView
   access can require a request/review. The user has now found and opened DevView
   on this particular TV and privately supplied its pairing code. The user created
   a portal account and reports awaiting DevView review. Device enrollment and
   local-HTTP URL acceptance remain pending. The later
   [protocol review](philips-devview-protocol.md) traces the client acknowledgement.

The [official debugging guide](https://docs.titanos.tv/debugging) says to open
DevView Settings, enable Debug Mode and confirm with OK, then inspect the TV
from Chrome using port9222 or7001. Both ports currently refuse connections;
this does not rule out debugging after the documented toggle is enabled.
The separate old-model firmware procedure is not needed or attempted here.

TITAN also publishes a [Hello Titan example](https://github.com/Titan-OS/titan-sdk-examples/tree/master/1-example-hello-titan).
Its [SDK documentation](https://docs.titanos.tv/titan-sdk) recommends DevView for
device APIs. [App Control](https://docs.titanos.tv/titan-sdk-app-control) requires
app authorization; it is not an unrestricted app installer. Nothing here proves
root/shell access, permanent sideloading, replacing the launcher or removing apps.

## REA provenance and limits

Existing local source `/Users/kian/code/rea` points to
[morluto/rea](https://github.com/morluto/rea), package6.7.0, inspected commit
`093ce695f0a7c3407c7fde520ed8b20c0981b408`. The source checkout has no built runtime;
the already cached6.7.0 CLI under ignored `.local/npm-cache/` runs successfully.
No installation, update or Codex configuration changes were needed.

REA can inspect captured web applications and supplied packages. Its Android
tools apply to Android devices/companion APKs, while its
[firmware adapters](https://github.com/morluto/rea/blob/main/docs/firmware-analysis.md)
use caller-supplied Linux Binwalk/Unblob. Inspection does not supply a firmware
flash/install path. The [Samsung discussion](https://github.com/morluto/rea/discussions/3768)
was unanswered at inspection and did not establish a working replacement OS or
arbitrary sideloading procedure. No new TV firmware/package artifact was acquired.

## Live reconnection evidence

Bounded SSDP discovery found one exact-model Philips descriptor, DIAL advertised,
and no listed AVTransport services. Its address differs from saved hints.
Before reusing credentials, local comparisons matched the previously recorded
encrypted serial, model and device ID in both HTTP1925 `/1/system` and HTTPS1926
`/6/system`. Raw identities/addresses remain ignored.

An isolated copy of the existing JointSPACE credentials was bound to that
verified address under `.local/tv-hello/philips-controller/`. Original JointSPACE
credentials, Matter fabrics, address hints and Android setup were preserved.
Authenticated context succeeded but returned no recognized focus labels.
At the freshly advertised DIAL base, Netflix GET returned200/stopped; no DIAL
launch or other key/media/volume/power command was sent in this session.

Private discovery/system/configuration files live in `.local/tv-hello/`.
The initial cached-address HTTPS failure was not proof that the TV was offline.

## Standalone proof and evidence protocol

- `src/tv-hello/index.html`: self-contained ES5 syntax, clock/runtime, six tiles,
  directional/OK input and unobstructed Back; no external assets, media or storage.
- `scripts/tv-hello-server.mjs`: explicit private-interface/TV-peer HTTP listener,
  random receiver path and separate loopback status capability. It serves only
  the page and bounded same-origin JSON reports. Session details are mode0600.
- [Page README](../../src/tv-hello/README.md): exact report schema and semantics.

Private `.local/tv-hello/config.json` contains `host`, `tvHost` and `port` (4180).
Do not copy live values or capability URLs into Git. Start/status commands:

```sh
node scripts/tv-hello-server.mjs
node scripts/tv-hello-server.mjs status
```

Start refuses an existing session file. Preserve a live session; verify its
private PID before renaming a stale file. SIGINT/SIGTERM closes the proof and
removes only its own session file. The page URL is in the private session file;
it is not printed by the CLI. The isolated Philips configuration already holds
that URL for this run:

```sh
python3 scripts/philips-controller.py \
  --state-dir .local/tv-hello/philips-controller \
  --address-hints .local/tv-hello/address-hints.json open-url
```

One launch was sent and accepted, `pageLoadVerified:false`. Actual listener
counters remained GET0/ready0/key0/heartbeat0. No TV execution is established.
Do not keep repeating this acknowledged-but-ineffective launch or infer focus.
The user has been asked to open Internet and focus its address field, or identify
DevView. Send Unicode only after that field is known to be focused.

Evidence ladder: GET = page retrieval; `ready` = script ran; later `heartbeat` =
continued script execution; `key` = DOM key/click handling. Visible rendering and
physical remote behavior still require user observation.

## Validation

Desktop Chrome integrated with the real fixture server passed at1280×720:
ready1, Right→Down→OK selected Local, key3, heartbeat1, no vertical overflow or
JS errors; Back was not prevented. Invalid-origin and extra-field reports were
rejected. An isolated render also passed1920×1080 and legacy Back461. The
initial720p overflow was fixed; final screenshot was inspected. Fixtures used
loopback only and are distinct from the real TV counters above. The existing
Android/release suites were not run because no app/release code changed.

The user reports creating the Partner Portal account and waiting for developer
verification. No account/credential form was submitted by us; no approval ETA
is known. DevView enrollment/debug toggle remain unperformed. A new read after
DevView opened still found both documented debug ports refusing connections.
See the [app execution investigation](philips-app-execution.md) and subsequent
[measured browser compatibility](philips-browser-compatibility.md). No Android
integration is authorized.
