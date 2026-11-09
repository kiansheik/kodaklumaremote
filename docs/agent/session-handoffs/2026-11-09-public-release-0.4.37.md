# Public release: LUMA Remote0.4.37 / Receiver0.3.33

## Goal
Publish the accumulated app updates so Julia can download/install them.

## Inspected / changed
Read required agent docs, docs/releasing.md, release-next/build/publish/deploy
scripts, versions, Git state and imported emulator provenance/licenses. Memory
registry had no relevant project entry. Added release-notes/0.4.37.txt and
.gitattributes (preserve checksum-pinned upstream formatting); excluded unused
QuickNES tools/proto binary in receiver .gitignore and corrected its README.
Committed the accumulated reviewed app/test/docs source requested for publication.
Updated current-state/log/open-questions and this handoff after deployment.

## Commands / validation
- git fetch origin main --tags; main matched origin, latest stable was0.4.23.
- npm run check:268 Node tests and185 browser tests pass.
- Verified133 upstream import hashes; only unused Linux tools/proto was binary
  among the new additions. It remains local/ignored. New source/text scan found
  no private-key/credential patterns; APK inventory contains no ROMs/signing keys.
- Used documented lower-level build/publish path for existing tested0.4.37 and
  0.3.33. Automatic next-patch helper expects0.4.24 and would not accept the
  intervening locally installed versions; did not renumber/downgrade them.
- Production signed APK preparation passes; phone9,879,842 bytes, SHA256
  38eeec4252fe7d58894e153dddb25846fa67630f18cccc20a1dbef35001ffb0e.
  Receiver3,230,553 bytes, SHA256
  09c61d38ae3d10d8bce8b61a627daaf81f2d675048c37055b41d492d6f745454.
- Source commit0c7d36475353e4271f89948248904761c90f43f7 pushed to main.
- publishAndroidRelease --directory .local/releases/v0.4.37 created/published
  v0.4.37 with both APKs and both manifests, then deployed gh-pages.
- Public APK GETs match exact prepared size/hash. Public page HTTP200. Fixed
  update.json and receiver-update.json now report0.4.37 and0.3.33 respectively.

## What failed / resolved
Automatic approval review initially rejected the public source push as broader
than permission for downloadable binaries. No workaround attempted. User then
explicitly approved publishing source/tests/licenses/docs plus app updates;
retry succeeded. Upstream whitespace warnings were preserved via a narrow
vendor-only gitattributes rule after verifying import checksums. Pages briefly
served old feeds while deployment was running; both new feeds subsequently
verified at their ordinary URLs, without cache-busting paths.

## Outcome / remaining questions
Release: https://github.com/kiansheik/kodaklumaremote/releases/tag/v0.4.37
Install: https://kiansheik.io/kodaklumaremote/
Direct phone APK: https://github.com/kiansheik/kodaklumaremote/releases/download/v0.4.37/luma-remote-0.4.37.apk
Julia can install via page or Settings → Check for updates on existing app.
No message sent to Julia and no claim she installed it. No projector interruption
or reinstall during publishing. Existing cold-boot/power/live-cache acceptance
limits remain as recorded in earlier handoffs.

## Suggested next prompt
Help Julia install the published phone update or verify her phone connects to
the updated receiver; do not reset saved app data.
