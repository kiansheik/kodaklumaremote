# Network analysis

## Observed HTTP evidence

At 2026-10-18 07:02 America/Sao_Paulo, sandboxed curl failed DNS resolution.
The same HTTPS request with permitted network access returned **HTTP/2 200** and
`text/html` through Cloudflare. A public control origin also returned 200.
The first result therefore does not imply the target is offline.

For static inspection, only public HTML and JavaScript/CSS were downloaded. No cookies or
authorization headers were supplied, no redirects were followed, and no media
or playback manifests were requested. Raw HTML includes per-response challenge
values and stays ignored locally. No raw HAR is retained.

Reproduce a sanitized page/static-asset inventory:

```sh
node scripts/recon-http.mjs
node scripts/recon-http.mjs --assets
```

The script allowlists response headers, omits request cookies/authorization,
rejects input query/fragment/credentials, bounds response size, and does not
follow redirects. `--assets` only downloads same-origin `/assets/*.js|css` links
from the HTML into `.local/recon-http/`. It does not recursively fetch lazy
chunks or call source APIs. Metadata defaults to ignored local output.

## Static request topology, not a traffic capture

`config-a1rf98t0.js` defaults to `https://api.framextv.tech` and can fail over to
`https://api.framextv.xyz` after network failures or 5xx responses. An allowlisted
stored/backend-query preference can select the initial backend. A fetch wrapper
handles that failover; this is observed code, not confirmation a switch happened.

EmbedPage builds `/api/stream` requests from title/type/episode/provider and
provider-availability checks. Player's source normalizer (~588650) preserves
existing proxy/worker/skipProxy URLs; otherwise it constructs the provider's
existing `/api/proxy` URL, including optional source metadata used by that
backend. Subtitle URLs have a separate normalization path. The integration does
not construct new header-spoofing or bypass proxies.

## Clean automated-browser metadata

At 07:04:52 America/Sao_Paulo, headless Chromium 156.0.8078.4 loaded the target
with a clean profile, no runtime overrides and no opened DevTools UI. Sanitized
response metadata (no response bodies, cookies, exact media paths or query values)
is local-only at `.local/recon/browser-metadata.json`.

- Watch/embed and their player assets loaded successfully.
- `api.framextv.tech` returned 200 for details, season, providers, stream and
  subtitles APIs. The metadata reports presence of CORS headers on these replies;
  it does not store their exact policy values.
- `crap.hybrows.workers.dev` returned 200 with HLS-manifest, `video/mp4`, VTT and
  SRT content types. `api.wecollege.net` and `frappe.hybrows.workers.dev` also
  appeared. These are observed origins, not proof of a receiver-accessible URL.
- The page ultimately navigated to `about:blank`; media/texttrack requests were
  aborted around that visit. No manifest, media, subtitle body or HAR was saved.

Successful responses do not establish decoded playback, codecs, expiry,
authentication/DRM state, or receiver CORS. A header merely marked present is
insufficient for manifest/nested-playlist/segment/subtitle receiver acceptance.

A second clean headed Chrome 155.0.8059.39 visit at 07:05:53 also loaded
watch/embed/stream with 200 responses and ended at `about:blank`. Its sanitized
local metadata is `.local/recon/browser-chrome-headed.json`. This repetition
does not establish stable normal-profile playback.
