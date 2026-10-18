# Philips browser setup and Mac receiver proof

## Goal

Use the existing Matter connection and the actual TV's supported local API to
open a direct-play receiver without repeated outdoor manual setup. Matter
hardware control is already implemented/published in0.4.0; MSX status0 and
Philips media/subtitles remain unresolved. This Mac proof is a stepping stone,
not a newly shipped phone playback feature.

## Files inspected

Required agent index/state/map/questions; Philips Matter/MSX/native-release
handoffs; `MsxServer.java`; `src/msx/` player/channel/HTML assets; public official
MSX setup, parameters, input/plugin/actions documentation and public bootstrap
examples; maintained haphilipsjs pairing/HMAC/Digest/browser implementation.
Current Python CLI and its domain notes, Node proof and synthetic fixtures.
Existing private discovery/source evidence was inspected only by the owning
root task; no credentials, network addresses or active URLs are copied here.

## Files changed

- `scripts/philips-controller.py`, `tests/test_philips_controller.py`,
  `.gitignore` and `docs/agent/philips-jointspace.md`: separate standard-library
  JointSPACEv6 client with private pairing state, Digest/HMAC grant, sanitized
  context and explicit browser/key calls. Unicode text uses stdin/hidden input
  and the maintained upstream `input/key` payload; it neither persists text nor
  claims replacement of an active field. Matter fabric remains untouched.
- `scripts/msx-mac-proof.mjs`, `tests/msx-mac-proof.test.mjs`: standalone Mac
  asset/channel proof with explicit RFC1918 interface and TV peer; random
  receiver capability plus a different loopback control capability; one client/
  session, observed ACKs, stale-source/order guards and no automatic Open retry.
- Agent index/state/log/map/questions and this handoff. Existing shared player
  assets and the published Android APK are unchanged. `headers.txt` is untouched.

## Commands and observed results

Across the team:

- One actual Matter Home returned SendKey status0, CASE57ms and descriptor14ms.
  This is accepted-key evidence, not screen/focus or application-launch proof.
- Bounded exact-TV SSDP still returned no services. Documented9222/7001 debug
  routes were unavailable; no screenshot/CDP session was established.
- HTTP1925 `/1/system` and HTTPS1926 `/6/system` returned200, API6.1/Linux.
  Advertised input key/unicode, text-entry context and browser activity support;
  Digest pairing/secured transport. Protected HTTP activity403; HTTPS context/
  current activity/screen-state401 with a Digest challenge.
- First actual JointSPACE pair-request returned SUCCESS/PIN_REQUIRED with a
  30-second timeout. Root requested the four-digit TV PIN once; the window then
  expired. After the user was ready, a fresh explicit request and PIN through
  hidden stdin completed grant with SUCCESS. Credentials are saved privately
  with mode0600; the PIN is not persisted. Matter credentials do not authorize
  this separate local API; no automatic prompt/retry occurred.
- Authenticated context GET returned200, but Skype/Options/NA labels conflicted
  with the user's visible Home. This endpoint is not reliable current-focus
  evidence on the actual firmware. Browser POST returned200 and
  `pageLoadVerified:false`; the user still saw Home, and the owned receiver
  recorded no TV TCP/assets/hello. Authenticated application enumeration was
  rejected; no speculative application-launch schema was used.
- `python3 -m unittest discover -s tests -p 'test_philips_controller.py' -v`:
  **15/15 offline fixtures passed**, covering request/grant/HMAC/Digest,
  private persistence, expiry,
  URL/address and sanitized-output boundaries. Subsequent Unicode tests cover
  exact authenticated payload, preserved spaces and private input without an
  echo fallback. No real TV/phone call occurred in these fixtures.
- `node --test tests/msx-mac-proof.test.mjs`: **13/13 loopback fixtures passed**.
  Covers peer/token/origin/Host boundaries, private/symlink config handling,
  bounded bodies/state, session reset, ACK ordering/idempotence, source guards,
  delivered unknown outcomes, no duplicate Open and TCP/TLS/malformed counters.
- `node --check` on both new Node files and whitespace checks passed. Tests
  never contacted the actual TV/phone or fetched media. The implementation did
  not start an actual LAN listener.
- Read-only official MSX bootstrap checks: the Experts Content Guide endpoint
  returned200 JSON/CORS for both HTTP and HTTPS. No server was modified.
- Root subsequently started the temporary actual Mac proof listener and prepared
  `.local/philips-controller/config.json` mode0600 with its private receiver URL.
  Status remains running=true/connected=false with TV TCP/assets/hello counts0
  after the accepted browser request. No media command has occurred. The server
  is temporarily ready to resume; no auto-start/persistence is installed.
- The saved S1E2 source HEAD returned403. Automatic review initially rejected
  unverified egress; offline provider inspection allowed the same-provider,
  no-auth/no-redirect retry, which confirmed403. Ordinary Chrome blanked the
  episode page to about:blank; direct public configuration GET403. No bypass.
- Root prepared separate private public/S1E2 Open-command fixtures offline,
  with16 episode tracks and PT-BR preferred. This made no media/device request
  and does not prove the saved source usable. A later user report removes the
  photo requirement: Netflix is the leftmost default Home tile. See the
  [power-cycle follow-up](2026-10-18-philips-power-cycle-follow-up.md) for the
  repeated no-effect browser request, user-confirmed Netflix navigation and
  physically confirmed DIAL Netflix launch, sanitized discovery metadata and
  ambiguous Speed Dial/HDMI1 navigation. Named Home visibly worked; the last
  Right/Confirm screen is unknown and device work stopped when the user left.

## Proof interface

Root's actual listener is already running; do not start a duplicate. These are
the explicit commands for a later restart/status/control:

```sh
node scripts/msx-mac-proof.mjs --host "$MAC_LAN_IPV4" --tv-host "$PHILIPS_LAN_IPV4" --port 4179
node scripts/msx-mac-proof.mjs status
node scripts/msx-mac-proof.mjs command < "$PRIVATE_COMMAND_FILE"
```

The start command writes exclusive0600 `.local/msx-mac-proof.json`; it refuses
existing files/symlinks and does not print its receiver/control URLs. The first
URL is for the explicitly pinned TV; the second is loopback-only. `command`
reads `{method,params}` JSON from stdin. Open accepts validated public source/
subtitle URLs and PT-BR preference; non-Open controls require the expected
source. Status/command output removes media URLs and capabilities. A delivered
timeout stays unknown/pending until ACK or a new session; never blindly retry.
The TV fetches original media/subtitles; the Mac hosts only assets and controls.

## What worked

Matter remained reachable and JointSPACE PIN grant succeeded on the actual TV.
The isolated Python and Node proof paths pass meaningful protocol/server
boundaries. Existing receiver initialization runs before its optional MSX SDK,
so its player/channel can run as a top-level page if the TV loads it.

## What failed or is uncertain

- MSX startup still fails status0. Official docs confirm HTTPS-launched MSX
  upgrades JSON/plugins to HTTPS; `secure=0` does not bypass this. The phone's
  HTTP-only server and a secure launcher could conflict, but that was not proved
  as the actual TV failure. No parser loosening was justified.
- No documented MSX external text-entry, screenshot or initial startup setter
  was found. Its Remote Control setting is an in-app overlay. Loaded plugins
  can later exchange structured UI/focus state and execute actions; that does
  not create an initial bootstrap route into the TV app.
- JointSPACE grant is accepted, but browser200 did not produce a visible launch
  or receiver GET+hello. The CLI reports `pageLoadVerified:false`, and context
  labels do not establish current focus. Inspect owned receiver counters/state
  and user screen feedback independently. The user identified the initial Home
  tile as Netflix and said no photo was needed. Use known starting focus and
  text feedback rather than requiring a photo or guessing subsequent labels.
- The TV advertises text-entry context, but no implemented request/replace
  schema was established in primary sources. Only proven Unicode key input is
  included; acceptance does not establish where text went or replace a field.
- Autoplay, codec/CORS, actual source playback, visible PT-BR and phone/Mac-off
  continuation remain untested. A trusted Select before Open may permit browser
  autoplay, but this is a hypothesis. Preserve PLAYBACK_REJECTED instead of
  claiming playback or automatically reopening.
- Saved source403 does not identify expiry versus provider policy. A fresh
  authorized capture remains needed. No access-control/anti-debugging bypass,
  media proxy or raw source publication was performed.
- The shipped0.4.0 APK still has Matter hardware plus its existing MSX handoff;
  it cannot yet launch the TV browser through JointSPACE. Complete receiver
  GET/hello and media acceptance before deciding on Android
  JointSPACE pairing, encrypted credentials and direct-browser handoff. The CLI
  and Mac listener are stepping stones to that phone solution.

## Remaining questions and suggested next prompt

“Device work stopped when the user left. Preserve saved JointSPACE/Matter
pairings and the temporary Mac listener. DIAL Netflix visibly opens; named
JointSPACE Home visibly works. The last accepted Right/Confirm screen is
unknown, and the receiver never received TV TCP/assets/hello. Resume only at
the user's request, establish visible focus first, and distinguish app launch
from arbitrary media/subtitles. Do not repeat pairing or guess app payloads.”
