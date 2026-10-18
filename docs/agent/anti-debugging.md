# Anti-debugging

The inspected main bundle includes **disable-devtool 0.3.9**. These are static
code findings; they do not establish which detector fired in a user's browser.
Offsets refer to the hashed bundle in [investigation](investigation.md).

## Confirmed initialization and code paths

- Startup calls `Vd()` near the bundle end. For public production hosts it
  initializes the library with `url` and timeout destination `about:blank`,
  context-menu blocking and log clearing enabled. Local/development hostnames
  have a separate bypass condition; no bypass was applied to the real target.
- Library polling interval is **500 ms** by default. Console clear is captured
  from the original console and invoked while polling, and by some detectors.
  Evidence: defaults/`te()` around offsets ~787500–790000 and polling ~792600.
- On detection, the library calls its configured callback. Because the target
  sets an explicit URL, the active branch changes `window.location.href` to
  `about:blank`. The library's generic close/back/timeout fallback exists but
  is not the chosen branch for this configuration.
- Default detector IDs are `[1,3,4,5,6,7]`: DOM id inspection, Date/function string
  conversion, an iOS timing detector, console-print timing, and checks for
  Eruda/vConsole debugging libraries. Date/function checks count conversions;
  performance detection compares console table/log timings. Code: ~791600–801100.

## Distinguish definitions from enabled behavior

| Reported mechanism | Evidence and limit |
| --- | --- |
| Console clearing | Enabled in initialization; clean automated Chromium recorded 69 clears. |
| Navigation/execution disruption | Explicit `about:blank` detection action; same destination reproduced in the automated baseline, but triggering detector is unknown. |
| Window dimensions | Size detector implementation exists, but its ID **2 is absent** from configured defaults. Do not call it active. |
| `debugger` loop | No `debugger` statements found in inspected main/player/watch/embed chunks. The named iOS detector only compares adjacent clock reads in this build. |
| Anti Anti Debug extension detection | Not reproduced. No `chrome-extension` literal found in those chunks. This does not rule out all indirect behavior or uninspected third parties. |
| Function integrity checks | Date/function string-conversion detectors exist; a general patched-function integrity check was not identified. |
| Obfuscation/resource integrity | Code is bundled and minified. No specific anti-tamper/resource-integrity check established; encrypted source-provider routines are not proof of one. |

No runtime detector modification, property spoofing, extension fingerprint
evasion, or control/DRM bypass was performed. A later isolated local fixture can
test detector semantics without changing the target. Opening DevTools in real
Chrome should be a separately recorded experiment after a clean-profile baseline.

## Automated runtime baseline

At 07:04:52 America/Sao_Paulo, clean-profile headless Chromium 156.0.8078.4 with
no runtime overrides and no DevTools UI loaded the watch/embed pages, then ended
at top-level `about:blank`. Console counts were log 70, clear 69, table 19,
warning 3 and info 2. This matches the configured library's log/timing/navigation
behavior, but the metadata does not identify the actual detector that fired.
Playwright's automation/CDP connection can itself influence console inspection.
This is not a reproduction of manually opening DevTools, Anti Anti Debug
extension detection, or the user's normal Chrome profile.

A second clean-profile **headed Chrome 155.0.8059.39** visit at 07:05:53, again
without DevTools UI or runtime overrides, also ended at top-level `about:blank`.
It recorded log 44, clear 43, table 14, warning 6 and info 2. Repetition across
these browser modes strengthens the observation of automation-associated
disruption; it still does not identify the detector or isolate CDP effects.
