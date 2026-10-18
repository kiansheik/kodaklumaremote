# Philips local API stepping stone

`scripts/philips-controller.py` is a Python-standard-library CLI for the already
known TV address. It does not discover devices or alter the existing Matter
fabric. Root observed API6.1 at the local Philips HTTP/HTTPS endpoints, including
browser/text-entry features and Digest authentication on protected resources.
Root subsequently completed actual PIN grant with `SUCCESS`; authenticated
context returned200, but its app labels disagreed with the visible Home screen.
Browser launch returned200 with no receiver TCP/assets/hello and no visible
launch. Neither those labels nor a successful POST prove current TV focus.

The protocol reference is the maintained
[haphilipsjs source at ade6feb9](https://github.com/danielperna84/ha-philipsjs/blob/ade6feb9c792d25e82083f68cf783391f7fe5150/haphilipsjs/__init__.py).
Pair request uses native client metadata, a random32-hex device ID, read/write/
control scope and the advertised feature metadata. Grant uses Digest credentials
from the pending exchange and Base64 HMAC-SHA1 of timestamp followed by the PIN,
with the protocol's public vendor key. The PIN stays a string and is never saved.

Run from the repository:

```sh
python3 scripts/philips-controller.py info
python3 scripts/philips-controller.py pair-request
python3 scripts/philips-controller.py pair-grant
python3 scripts/philips-controller.py context
python3 scripts/philips-controller.py open-url
python3 scripts/philips-controller.py key enter
python3 scripts/philips-controller.py type
```

`pair-request` displays one TV PIN prompt. `pair-grant` accepts the four digits
through hidden terminal input or stdin, never an argument. An unexpired pending
request prevents a second prompt. PIN failure preserves the pending exchange;
expiry requires a new explicit request. Credentials are committed only after
the TV reports `SUCCESS`.

`open-url` reads a private0600 `.local/philips-controller/config.json` containing
`{"url":"..."}`, or accepts hidden input/stdin if no config exists. It accepts
HTTP/HTTPS without credentials or fragments and rejects loopback/link-local
destinations. It does not print the source. HTTP acceptance is reported as
`pageLoadVerified:false`; actual receiver GET/hello and media playback still
require independent observation. Keys are limited to ordinary navigation;
there are no power/reset controls. Context reports only known focus enums.

`type` accepts one printable Unicode string through hidden terminal input or
stdin only, preserving spaces and rejecting controls/overlong text. It posts
`input/key` with the upstream `unicode` field and never logs or persists text.
It reports `textAppliedVerified:false`: the correct field must be visibly
focused, and TV acceptance does not prove typing or replacement. No verified
textentry read/replace schema was found, so no guessed textentry endpoint or
replacement payload is implemented. A terminal that cannot hide input is
refused rather than falling back to visible echo.

The default address comes from the sole RFC1918 IPv4 in ignored
`.local/matter-address-hints.json`; the API endpoint is fixed HTTPS1926/version6.
No scanning or HTTP downgrade occurs. The Philips self-signed certificate is
accepted only by this request's TLS context; system trust is unchanged. The
client disables environment proxies and redirects. This local endpoint does not
have CA-authenticated identity. State lives in a0700 directory with atomic0600
pending/credential files. Errors omit addresses, identifiers, PINs, keys, URLs,
response bodies and exception details. Timeout after a write remains unknown;
the CLI never retries a write except the required Digest challenge exchange.

Offline verification:

```sh
python3 -m unittest discover -s tests -p 'test_philips_controller.py' -v
```

Fifteen loopback-only cases passed: exact pairing/device/grant payloads, real
Digest challenge/replay, HMAC/leading-zero PIN, successful-only persistence,
expiry/no duplicate prompts, sanitized info/context, browser/key payloads,
rejected writes, redirect isolation, URL/address validation, private-file guards
secret-safe argument rejection, authenticated Unicode typing, rejected text and
private-input handling. No TV/phone calls occurred in these tests.
