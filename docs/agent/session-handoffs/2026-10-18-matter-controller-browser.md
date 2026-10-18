# Matter controller and phone UI fixtures

Goal: commission and inspect the existing Philips TV without native SDK setup
on the Mac, then verify the phone's manual/QR pairing and combined remote UI.

Inspected: required agent docs; pinned Matter.js0.17.9 controller, commissioning,
peer fallback, interaction and attestation sources; mobile UI and native adapter.

Changed:

- `tools/matter-controller/`: isolated pinned dependencies/lock, private durable
  fabric, hidden/stdin commissioning, sanitized descriptor inventory, explicit
  single-key and speaker-level controls, and explicit second-client window.
- Controller reads include known data versions so persisted caches cannot make
  a later inventory appear empty. Feature flags remain visible; operation
  failures expose a fixed phase/type without native messages or secrets.
- Optional `MATTER_ADDRESS_HINT` accepts only private IPv4 plus an explicitly
  supplied port and updates the SDK's cached operational fallback.
- `tests/browser/mobile-matter.spec.js`: ten isolated browser fixtures, including
  delayed pairing readiness, secret clearing, independent targets, expiry and
  no-scroll320×592/390×844 layouts.

Commands/checks:

- `node --check tools/matter-controller/controller.mjs`
- `node --check tests/browser/mobile-matter.spec.js`
- `npx playwright test tests/browser/mobile-matter.spec.js`: **10/10 passed**.
- `git diff --check -- tools/matter-controller tests/browser/mobile-matter.spec.js`
- Bounded `dns-sd -G v4v6` against only the TV's announced Matter hostname.
- One actual `inventory` with its DNS-confirmed private IPv4/announced UDP port:
  exit0, three endpoints,100.4seconds. Private artifacts remain under `.local/`.
- One subsequent actual `volume read`: exit0 in4.4seconds; explicit CASE
  connection2626ms, descriptor599ms, speaker endpoint2 level1/min1/max254.
  Before/after both1; no volume change. Actual advertised UDP port is5640.
- Explicitly authorized speaker proof: fresh1 → requested/observed2 → restored
  and observed1 in `finally`; both writes returned status0/verifiedtrue. All
  three CLI calls exited0 in3.5seconds total. No other controls were sent.

Worked: parent successfully commissioned the TV and verified Home status0. A
fresh read-only inventory works after leaving TV settings, without another TV
step. Browser fixtures caught and parent fixed native bridge argument arity,
async-pair readiness, and self-aliased capability clearing after pairing.

Failed/limits: parent Right attempt failed before invoking the key; Left was
  not sent. No repeat, power command or commissioning-window command was sent
  by this follow-up. IPv4 inventory's100-second total does not
identify whether optional attribute reads or a prior connection attempt dominate;
the subsequent short read established a fast CASE/read path. Initial
buffered DNS capture produced no output; PTY capture resolved both addresses.
Actual Android SDK pairing/control and second-client commissioning are pending.

Trust boundary: Mac prototype retains Matter.js's backward-compatible default
attestation policy. Its commissioning success proves fabric/connectivity,
not production PAA-chain acceptance. Android uses a separate native verifier.
No device credentials, fabric material, actual pairing codes or active media
URLs are checked in or printed.

Remaining questions: short-read reconnection latency; actual native phone
pair/control; ability to open an enhanced window over the existing fabric;
physical input behavior. Speaker1→2→1 readback is established; perceived loudness
was not user-confirmed. No ContentLauncher/ApplicationLauncher was
reported, so arbitrary URL playback is not established through Matter.

Suggested next prompt: finish the native SDK build, verify one read-only status
on the existing TV, then explicitly open a second-client window using the
already-paired Mac and pair the phone without revisiting TV settings.
