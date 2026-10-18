# Philips playback setup: final power-cycle and DIAL follow-up

## Goal and final state

Use the existing paired TV controls to set up independent receiver playback
without more outdoor setup. The user confirmed immediate signed-phone TV
recognition/navigation after TV off/on and a direct DIAL Netflix launch.
Browser/MSX receiver playback was not established. The user then left Philips;
all device work and questions stopped. Saved pairings and the temporary Mac
listener were preserved; the current TV screen is unknown.

## Files inspected

Required agent index/state/map/questions; TV/Matter/MSX and native-release
notes; JointSPACE upstream pairing/Digest/key/browser implementation; existing
MSX assets/channel; `scripts/discover-tv.mjs` and its tests; the new Philips
client/Mac receiver and their fixtures. Private evidence/configuration stayed
under ignored `.local/`; no addresses, pairing secrets or active URLs are
copied here.

## Files changed

- `scripts/philips-controller.py`, `tests/test_philips_controller.py`,
  `.gitignore`, `docs/agent/philips-jointspace.md`: private JointSPACE pairing,
  Digest/HMAC, sanitized reads, explicit browser/keys and stdin-only Unicode.
- `scripts/msx-mac-proof.mjs`, `tests/msx-mac-proof.test.mjs`: peer/capability-
  pinned receiver assets/channel with loopback control, observed ACKs and no
  media/subtitle proxy or source fetch.
- `scripts/discover-tv.mjs`, `tests/discovery.test.mjs`: preserve validated
  descriptor DIAL metadata only as `dialAdvertised`; no app requests added.
- Shared agent state/index/map/questions/log, TV/Matter/MSX domains and relevant
  handoffs, including this follow-up. Published0.4.0 APK and unrelated
  `headers.txt` are unchanged.

## Commands run and validation

- Philips CLI `pair-request`, hidden-stdin `pair-grant`, `context`, `open-url`
  and explicit named keys. Grant succeeded and saved private credentials;
  PIN was not persisted. Browser200 did not establish page load. Named Home
  visibly returned HDMI1→Home. Final Right,700ms pause,Confirm were accepted;
  their screen result is unknown because the user left.
- Explicit Mac Matter Home/Right/Select/Back checks returned accepted statuses.
  User feedback distinguished recent/regular Netflix. Favorites were arranged
  browser1Right/MSX2Right; navigation also reached HDMI1 and an ambiguous
  “Editar speed Dial”. Neither its meaning nor the earlier outcomes' cause
  was established. Acknowledgements do not establish focus or browser launch.
- Bounded exact-TV unicast discovery0, then DIAL/MediaRenderer multicast1 with
  matching descriptor and advertised `Application-URL`. At that base, GET
  YouTube403 and GET Netflix200/stopped preceded one empty Netflix POST201,
  then GET200/running. The user confirmed Netflix visibly opened; no Select
  or navigation accompanied that direct launch.
- `node scripts/discover-tv.mjs --no-save`: one exact-model target,
  `dialAdvertised:true`, empty serviceTypes/AVTransport, zero errors. The old
  discovery path had dropped the header; empty XML services did not exclude
  DIAL. Updated discovery makes no app requests and exposes no raw base.
- `python3 -m unittest discover -s tests -p 'test_philips_controller.py' -v`:
  **15/15**; Python compile check passed. `node --test
  tests/msx-mac-proof.test.mjs`: **13/13**. `node --test
  tests/discovery.test.mjs`: **8/8**. Node syntax/diff checks passed. Fixtures
  did not contact devices or fetch media.
- Temporary Mac receiver start/status used explicit private-interface/TV-peer
  configuration. Every actual status check recorded TV TCP/assets/hello0;
  no media command was sent. Saved episode HEAD/public-config GET403 and Chrome
  about:blank left fresh capture unresolved. Public/episode Open fixtures
  were prepared offline only (16 episode tracks, PT-BR preferred).
- Final documentation checks: targeted reads, local Markdown links and
  `git diff --check`; no further tests or device calls at the user-away boundary.

## What worked, failed and remains open

Phone reconnect/navigation, private JointSPACE pairing, visible named Home,
DIAL discovery and visible Netflix launch worked. DIAL proves this app-specific
launch, not arbitrary app IDs, browser URLs, media or subtitle delivery.
JointSPACE context labels were inconsistent/empty and browser acceptance
produced no TV receiver traffic. Speed Dial remains ambiguous. No browser/MSX
launch, new media/PT-BR playback or phone-off continuation is accepted, and
no new phone feature or release was made.

## Suggested next prompt

“Resume Philips setup when I am available to observe it. Preserve the saved
pairings and temporary listener, establish the actual screen/focus before
navigation, and confirm receiver GET/hello before media. DIAL Netflix and
JointSPACE Home are physically proven; the last Right/Confirm screen is unknown.
Do not infer arbitrary video/subtitle support or repeat pairing.”
