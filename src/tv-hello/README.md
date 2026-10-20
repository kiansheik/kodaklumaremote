# Philips Hello World proof

`index.html` is a standalone, ES5-syntax browser experiment. It needs no build,
media, account, external library or Android change. Serve it over local HTTP
and open its exact URL on the TV. Browser compatibility on the physical Philips
must be verified separately from a desktop render.

The page displays a live clock and JavaScript runtime counter. Arrow keys move
between six tiles; OK/Enter activates a tile. Conventional key codes37–40 and13
are supported. Back codes 8/461 are observed without preventing browser navigation.
Mouse clicks work for a desktop smoke check. No cookie or browser storage is used.

## Optional evidence endpoint

The page POSTs JSON to relative `report`, beside `index.html`. A capability-based
server can therefore place the page and endpoint under the same private path.
Failure to report does not stop the page. Requests time out after 3 seconds;
heartbeats run once per 10 seconds and keys are limited to one per 120 milliseconds.

```json
{"v":1,"event":"ready","sequence":1,"uptimeSeconds":0}
{"v":1,"event":"heartbeat","sequence":2,"uptimeSeconds":10}
{"v":1,"event":"key","sequence":3,"uptimeSeconds":12,"key":"right"}
```

`event` is `ready`, `heartbeat` or `key`. `sequence` is a positive integer and
`uptimeSeconds` a non-negative integer. Only key events carry `key`, whose
fixed values are `left`, `up`, `right`, `down`, `ok`, `back` and `other`.
These Hello World JSON reports exclude raw key values, user agent, device
identifiers, screen details and media URLs. The endpoint should return 2xx to
acknowledge receipt. Ordinary browser HTTP headers are not logged by the server.

The standalone server also supports `src/tv-bridge-probe/index.html`, selected
with private config `mode: "bridge-probe"`. Its ready report may add four exact
bridge booleans, at most48 descriptor rows and an exact whitelisted compatibility
object. Compatibility includes parsed numeric browser/vendor version fields,
geometry/context,18 API-presence flags and12 fixed media declarations. The raw
UA and arbitrary fields are rejected; the probe makes no media requests and does
not call opaque vendor IPC. Later fixed diagnostics
read Window.external and opera_omi.constructors once, inspect a CSManager native
marker/prototype and create one documented HbbTV typed object without activating
its relay. An earlier active experiment tried one zero-argument registered CSManager
construction and failed with TypeError on TV. Routine mode now disables
constructor calls and typed-object creation; activeDiagnostics:true is explicit
and MSX mode always remains passive.
Raw bounded constructor text stays private and normal CLI status
prints metadata only. See [measured schema/results](../../docs/agent/philips-browser-compatibility.md)
and `tests/tv-hello-server.test.mjs` for protocol boundaries.

The probe reports its SHA-256 source revision and `remoteReload:true` on ready
and heartbeat. The server injects the revision when serving the page. A changed
source hash requests the single action `location.reload()` in the next heartbeat
acknowledgement (normally within10s). `node scripts/tv-hello-server.mjs reload`
queues the same action through a separate loopback capability; queuing alone
is not completion. A new matching GET/ready verifies the update. There is no
arbitrary code/command endpoint. Private config may persist the receiver token
so our URL survives restart; keep it and the separate status/reload URL out of Git.

An HTTP page GET establishes retrieval only. `ready` establishes script execution;
later `heartbeat` establishes continued execution; `key` establishes a DOM key
event or tile click. None establishes visible TV rendering without observation.

## Fixed remote command proof

The bridge probe announces `remoteCommands:true` and polls every2s. CLI `ping`
and `label` queue one fixed command through the authenticated loopback control
URL; label always means `Controlled remotely`, with no arbitrary text/code.
Responses carry a command ID and fixed method. A ready/heartbeat
`commandResult:{id,method,ok:true}` must match the pending command; duplicate
matching receipts are idempotent. Queued/completed:false is not completion.
The probe reads back label text before acknowledgement; physical pixels remain
separate. See [current protocol and live evidence](../../docs/agent/philips-probe-control.md).

Optional ready-only `socketDiagnostics`, `csManagerSource`, `csManagerAttempt` and `hbbtvObject`
fields have strict bounded schemas in the server/tests. The source field is
protected in local status and excluded from normal CLI source output. Unknown
raw fields, unsafe names, oversized data and diagnostic fields on heartbeat
are rejected. This command channel controls the loaded page through the Mac;
it does not implement TV-hosted TCP, Kodi/media controls or phone integration.

## Installed MSX diagnostic

Private config mode `msx-probe` serves `/msx/start.json`, one capability-protected
content tile and a separate embeddable probe route. Exact configured MSX origins
apply to bootstrap CORS and the framed HTML only; ordinary HTML remains
unframeable and reports retain their own-origin check. `msxAllowNullOrigin` is
false by default and affects bootstrap only. `probeContext` is ready-only and
records `{v:1,embedded,activeDiagnostics}` without parent data. Bounded MSX
HTTP/TLS diagnostics contain counters and canonical origin metadata only.
Actual TV MSX bootstrap/plugin execution and ping/label receipts now pass
through the temporary trusted-HTTPS route. Fully LAN MSX startup remains
unverified; the current proof depends on the Mac/tunnel.

MSX appends version (`v`) and timestamp (`t`) cache fields to JSON URLs. Only
bootstrap/home accept these bounded numeric fields; duplicate/unknown fields and
queries on report/page/control routes are rejected. A separate private HTTPS
adapter proof uses the same validator and does not publish local controls.
