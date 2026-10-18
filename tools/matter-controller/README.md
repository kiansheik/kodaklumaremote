# Local Philips Matter inventory

Standalone Mac/Node controller pinned to Matter.js **0.17.9**. It uses the
existing Wi-Fi/IP network and installs no Bluetooth or native Matter SDK.
Requires Node **20.19–21.x or 22.13+**; the current Mac's Node24 is supported.

```sh
cd tools/matter-controller
npm ci --ignore-scripts --no-audit --no-fund
node controller.mjs check
node controller.mjs commission
node controller.mjs inventory
node controller.mjs key home
node controller.mjs key right
node controller.mjs key left
node controller.mjs volume read
node controller.mjs volume 2
node controller.mjs open-window
```

`commission` asks for the TV's Matter manual pairing code with input hidden.
It also accepts the code through stdin or `MATTER_PAIRING_CODE`; never put the
code in command arguments or shell history. Eleven- and 21-digit manual codes
are accepted, with display separators removed. Bluetooth is disabled. The TV
must have an open commissioning window and already share the Mac's IP network.
An advertising `CM=1` identifies that window, not the secret pairing code.

Commissioning adds this controller's fabric to the TV. Its first action is
reading descriptors, supported command/feature lists and selected
input/playback/volume attributes. `inventory` only reads. Inventory does not
establish that a listed command works on the physical TV.
This prototype currently uses Matter.js's default attestation policy; its
commissioning success establishes fabric/connectivity, not verified production
PAA trust. It is separate from the Android app's native verifier.

`key` sends exactly one explicit Home/Up/Down/Left/Right/Select/Back key after
checking the endpoint's accepted SendKey command, and returns the TV's status.
`volume read` reads the Speaker endpoint. `volume LEVEL` validates the freshly
reported minimum/maximum, sends MoveToLevel without changing OnOff, then reads
back the observed level. There are no automatic key repeats, macros, playback,
power, reset, app launch or decommissioning commands.

`open-window` explicitly opens a15-minute enhanced commissioning window so a
second phone/controller can join without revisiting the TV's settings. It
replaces any existing commissioning window, verifies that the new window is
open, and writes the fresh manual code/passcode/discriminator only to the private
`.local/matter-controller/commissioning-window.json` file. Standard output
contains confirmation and expiry, never the code. Each additional client gets
its own fabric; opening a window does not remove an existing client.

Fabric keys and the sanitized last inventory remain in the repository's ignored
`.local/matter-controller/`, with directories0700 and files0600. Keep that folder
to reconnect without pairing again. Library logs and raw error messages are
suppressed so pairing codes, URLs and fabric material do not reach output.
Do not delete or publish the private folder. A timeout preserves local state;
check discovery/commissioning-window status before requesting another pairing.

If operational mDNS stops after the TV leaves its settings, the library tries
its cached address. `MATTER_ADDRESS_HINT=PRIVATE_IPV4:ANNOUNCED_PORT` explicitly
prefers a confirmed private IPv4 address for an existing pairing. The library
persists its selected operational address. Obtain both address and port from the TV's announced Matter host/service;
do not guess the UDP port. This changes no TV settings or keys.

Official references:

- [Pinned controller example](https://github.com/matter-js/matter.js/blob/v0.17.9/examples/control-onoff/src/OnOffController.ts)
- [Commissioning options](https://github.com/matter-js/matter.js/blob/v0.17.9/packages/node/src/behavior/system/commissioning/CommissioningClient.ts)
- [Node platform bootstrap configuration](https://github.com/matter-js/matter.js/blob/v0.17.9/packages/nodejs/src/config.ts)

The upstream generic controller example also contains automatic OnOff toggles;
this tool does not run that example.
