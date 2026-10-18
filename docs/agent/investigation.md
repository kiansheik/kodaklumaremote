# Investigation

Observed 2026-10-18, from 07:02 America/Sao_Paulo (10:02 UTC).
The public target is reachable outside the network sandbox. No login, cookies,
stream API response, manifest, media segment, or receiver was required or fetched
for this static investigation.

## Findings and boundaries

- HTTPS GET returns a small HTML application shell; JavaScript renders the watch
  page. Static signatures identify React 19.2.8, React Router 7.18.3 and Vite.
- The website already initializes Google Cast's Default Media Receiver and has
  a player Cast control. Its availability condition can hide the control when
  no Cast device is discovered. Adding another button cannot establish receiver
  compatibility by itself.
- The watch route embeds a same-origin playback page. Its native player leaves
  a URL-bearing `source` element in the DOM, including when hls.js uses MSE.
- The main bundle starts `disable-devtool` 0.3.9, configured to clear logs and
  navigate to `about:blank` upon detection. Clean automated visits in headless
  Chromium 156 and headed Chrome 155 reproduced console clears and later
  top-level `about:blank` navigation;
  an actual opened DevTools UI and its triggering detector remain untested.
- Static paths for backend source acquisition, proxying and refresh exist.
  Browser metadata includes successful stream API and HLS/MP4-typed responses.
  Actual decoded playback, codecs, expiry, receiver authorization/CORS and
  Cast-device success remain unverified.
- An opt-in live run with the built MV3 extension confirmed its execution marker
  in both watch and same-origin embed frames, with the page Cast SDK present.
  No video/source or visible extension control was captured before blank-page
  disruption. This verifies frame injection, not live media/control acceptance.

The wrapper should run in both supported same-origin watch/embed frames,
preserve a usable existing Cast button, and use ordinary DOM sources. No reason
to intercept encrypted responses, modify anti-debugging code, spoof upstream
headers, or add a relay has been established.

## Evidence identity

Raw public frontend files remain ignored in `.local/recon/`; they are not
portable fixtures. Hashes identify the inspected version without committing
page challenge values or active playback URLs.

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| `index-BJnIXHji.js` | 805857 | `2a882d0631aa8307b2e7600e37e6c934e367333ab00b2811e136b25d9f298db6` |
| `config-a1rf98t0.js` | 4142 | `9c904a6505e5a3a42ca0844f07a7289f4a446ec62fdafc936aa7d2de01a4266c` |
| `WatchPage-DY33XwPg.js` | 55030 | `eb2656b725faec2e9697e5b07305bf4f51828140d0d15ac8d97f9311411f3379` |
| `EmbedPage-BB5N66fF.js` | 7272 | `eb3685f1ad220d10f120a645ae1cf49bdd83524dbea8602cf374cf6bb3447912` |
| `Player-Btc_vwEb.js` | 626981 | `2fc058e9c532f2e50b2603560751ff588cf44f68894c6b51fc665ef64b122d37` |

References below use **Unicode character offsets after UTF-8 decoding**, not byte
offsets or source-map locations. Asset names and hashes must be refreshed when
the site's build changes. See [experiments](experiments.md),
[architecture](site-architecture.md), [network](network-analysis.md),
[playback](playback-analysis.md), and [anti-debugging](anti-debugging.md).
