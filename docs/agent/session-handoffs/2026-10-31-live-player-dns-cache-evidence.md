# Live player DNS-cache evidence

## Goal

Determine with physical evidence why the installed phone's outer watch page
loaded while the player iframe still displayed `net::ERR_FAILED`.

## Files inspected

- `android/app/src/main/java/dev/framextv/lumaremote/MainActivity.java`
- `android/app/src/main/AndroidManifest.xml`
- `docs/agent/{network-analysis,anti-debugging,site-architecture,playback-analysis}.md`
- Ignored sanitized browser reconnaissance metadata
- Current public watch/embed/player bundles and bounded API/provider responses

## Files changed

- `docs/agent/current-state.md`
- `docs/agent/open-questions.md`
- `docs/agent/log.md`
- This handoff

No application source changed.

## Commands run

- ADB screen capture and UI inspection of the outer watch page and iframe error
- `dumpsys webviewupdate`, Activity/process mapping and PID-filtered logcat
- Phone-side ordinary HTTPS/DNS probes, Google DoH controls and forced-DoH TLS
  probes (no media bodies or active media URLs retained)
- Active Wi-Fi LinkProperties/private-DNS inspection
- Bounded public stream/provider-status probes with working-episode controls
- `am broadcast -a android.intent.action.CLEAR_DNS_CACHE`
- Phone-side root and exact embed HTTPS HEAD probes after the flush
- One unchanged page reload plus immediate and delayed physical screen captures

## What worked

- The outer HTTPS watch page was present while its same-origin HTTPS player
  iframe alone showed Android WebView's `net::ERR_FAILED`; the renderer stayed
  alive and current WebView 156 was active.
- The phone already advertised validated `8.8.8.8`/`8.8.4.4`. Ordinary Android
  resolution failed only for the previously failed apex names, while Google DoH
  returned their records, DoH-forced HTTPS passed TLS and returned the site, and
  a fresh `www` name resolved ordinarily. This isolated stale negative cache.
- After Android's DNS-cache-clear broadcast, ordinary phone HTTPS returned 200
  for both the site and exact embed route. Reload restored the real player at
  its saved position; it remained visible after an additional 20-second delay.
  No app/router/media change was needed, establishing the causal diagnosis.

## What failed

- Android exposed no usable `cmd netd` service and legacy `ndc` did not return;
  the standard cache-clear broadcast was used instead.
- Fresh backend calls for the exact later episode currently return `no_streams`
  across all allowed API origins. One provider lookup returns 404 with no CORS,
  so Chromium reports generic `ERR_FAILED`. This is a real upstream risk but did
  not prevent the phone's cached playable source from loading after DNS recovery.
- Clean automation still triggers public anti-debug `about:blank` behavior, but
  the post-flush physical player stayed visible through that timing window.

## Remaining questions

- Does Cast capture the restored cached source and open it on receiver 0.3.12?
- Does selecting a public subtitle after that cast deliver and render correctly?
- When the current cached source expires, will upstream providers again supply
  this exact later episode, or will the observed `no_streams` state block it?
- Should a future phone release surface subframe host-lookup errors and recommend
  Wi-Fi reassociation, without weakening mixed-content/cookie/Safe Browsing rules?

## Suggested next prompt

Cast the now-visible player to LUMA, then select one subtitle. If a future fresh
episode fails, capture the iframe before reloading so resolver and provider
failures can be distinguished again.
