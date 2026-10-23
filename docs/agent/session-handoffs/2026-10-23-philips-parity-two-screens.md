# 2026-10-23 Philips parity, two-screen move/sync, brightness

**Goal (user):** keep resolved links indefinitely (with fallback); subtitles on
Resume remembered per title; volume cooldown halved; Auto-play switch instant;
Philips receiver feature/experience-equivalent to LUMA Receiver; More actions to
move playback between screens and to play both in sync; Library casts must keep
subtitles; brightness in More (LUMA video looked dark). User also proposed hosting
the TV page on GitHub Pages.

**Changed:** `src/msx/receiver{,-core}.js|html|css` (ended/rate/display state;
`display`/`notice`/`rate` commands; LUMA caption style; scrub overlay long poll;
Back dismisses notices and counts as input), `MsxServer.java` (validation, `scrub`
GET long poll + `scrub()`, `playCatalog`, 4 workers), `ConnectionService.java`
(`msx.scrub`, `msx.settings`, `msx.display|rate`, native Philips auto-play with
caption language, TV display restore, `ResolvedSources` without expiry,
`receiver.rate` side channel), `NativeBackend.java` (`receiver.rate`),
`ResolvedSources.java`, `MainActivity.java` (`app.captionPreferences`),
`src/handoff/{coordinator,sync}.mjs`, `src/mobile/{app.js,index.html}` (Philips
size/auto-play/scrub/next-after-end, brightness, move, sync, caption memory,
optimistic switch, 1 s volume cooldown), receiver `PlaybackController`
(`setRate`, `rate` in status, caption fallbacks, GL effects removed),
`ControlServer` (`player.rate`), `DisplaySettings` (colour matrix),
`MainActivity` + `main.xml` (TextureView PlayerView when adjusted),
`ExternalCaptions` (48 MB disk cache), tests (`sync.test.mjs`,
`CaptionCandidatesTest`, handoff track cap, brightness browser test).

**Verified:** Node and browser suites, receiver unit tests, both release builds.

**Not verified:** any Philips behaviour on the real TV; move/sync between real
devices; whether the projector shows the brightness change; emulator groups.

**Hosting answer:** an HTTPS GitHub Pages page cannot call the phone's HTTP LAN
server (mixed content; browsers also cannot scan the LAN). Viable: a Pages
*launcher* at a fixed URL that remembers phone addresses in its own
localStorage and navigates to `http://<phone>:<port>/msx/connect.html`
(top-level navigation is allowed); TV state the phone also keeps is re-applied
on connect. Not built yet.

**Next prompt:** "I tested brightness/Resume/Library subtitles on LUMA: <…>.
Build the GitHub Pages TV launcher" or Philips physical test results.

## Follow-up: fixed TV launcher and multiple phones

- `site/tv.html` (published as https://kiansheik.io/kodaklumaremote/tv.html, added
  to `build-pages.mjs`): phones open it as `tv.html#phone=IP:PORT&name=…`; it keeps
  up to 8 phones and one stable TV client id in its own localStorage and forwards to
  `http://IP:PORT/msx/connect.html#client=…&phones=…`. Without a fragment it lists
  the phones and opens the most recent after 4 s. Private IPv4 and ports ≥1024 only.
- `PhilipsTextController.launchBrowser` now opens the launcher (`fixedLauncherUrl`
  admitted by the transport); manual typing still types the direct phone link.
- `MsxServer` serves `/msx/probe.gif`; `src/msx/receiver.js` adopts the launcher's
  client id (approvals survive phone address changes) and, after 20 s offline with
  nothing playing, probes its own and the other phones and moves to one that answers.
- Not live until the Pages deploy; untested on the real TV. The projector brightness
  was changed outside this session to a translucent overlay (TextureView went black).
