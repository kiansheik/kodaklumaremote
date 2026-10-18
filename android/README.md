# LUMA phone prototype

An Android sender with a packaged web remote and native TCP/UDP/Kodi HTTP
transport. The Mac is not needed at runtime. The projector retrieves media and
subtitles directly; the phone does not relay them.

Install: **https://kiansheik.io/kodaklumaremote/**.
Published APKs use the stable production signing key. Opening the app checks for
updates; **Settings → Check for updates** checks immediately. A new version
offers Later/Update, downloads only on request, verifies the APK and opens
Android's installation confirmation. See [release guide](../docs/releasing.md).

## Use

Install the locally built debug APK on the Samsung phone. Keep the phone and
LUMA on the same Wi-Fi. On first use, find/connect the projector and enter the
HTTP username, password and port configured in Kodi. **Use Kodi** opens Kodi,
waits for its API and validates the login before saving it on this phone. The
single projector address is cached; Kodi credentials are encrypted with an
Android Keystore key in app-private storage. Credentials are not returned to
the web UI. A video selected before setup continues casting after validation.

Later opens try the cached projector automatically and show Remote with a small
red/green connection indicator. Setup moves behind **Settings**, which provides
connection/login editing, a fresh scan and separate forget actions for Kodi,
the projector or everything. Switching apps or recreating the Activity restores
the living service's remote without reconnecting or replaying media.
If Android restarts the process, the app attaches to Kodi's already-playing
public HTTP video using fresh status and generic metadata. Website language
names/source catalogs return after another capture; they are not saved to disk.

Open **Browse**, visit the frameXTV episode and wait for its player, then tap
**Cast to LUMA** in the browser toolbar. An HTTPS frameXTV page can also be
shared from Chrome to this app. The sender launches installed Kodi, waits for
its API, opens the declared media and loads Brazilian Portuguese, falling back
to English. The app then opens **Remote**. All website languages remain listed
under **Subtitles**; selecting another loads it on demand. Only one subtitle
track displays at a time.

The no-scroll Remote screen keeps Home at upper left, Back at upper right,
playback and a central five-button D-pad visible. Its volume slider and ±
buttons use the observed receiver range; a slider gesture sends one committed
value and reads volume back. **Subtitles** opens language selection; **More** holds typing, apps,
seek, Kodi launch and Stop. Lists use search and pages. Cancelling casting
revokes queued source-bound commands; commands already accepted by the receiver
cannot be undone. Browsing another page after a successful handoff does not
stop receiver playback.
The Power icon on Remote opens a confirmation with Cancel focused. **Turn off**
sends the original Kodak power command once and disconnects local controls,
keeping saved setup. Actual standby/shutdown behavior depends on the receiver.

After connecting, allow notification permission when Android asks if you want
notification controls. Expand the ongoing LUMA notification to use
**Volume − / Play-Pause / Volume +** for the verified current source.
A separate media card and MediaSession serve Android’s lock-screen/system
controls, with Stop/Disconnect in the expanded media controls and full remote.
Both action routes passed live Pause/Play checks on Samsung; the user confirmed
the shade and lock-screen controls are visible and functional. Cards now show
distinct action icons and observed projector volume after a volume press.
**Disconnect** closes the controller connection and leaves Kodi playback alone.
Use **Stop video** to end the video.

Force-stop, reboot or process death can end the live session. Saved setup is
used when the app next opens; automatic service restart and restoration of
website language aliases after process death are not guaranteed. This service
controls the projector; media continues
to come directly from its source without the phone or Mac relaying it.

## Build

Requirements: Node/npm, JDK 17 or newer, Gradle 9.6.1, Android SDK platform 35
and Build Tools 36.0.0. Android Gradle Plugin is pinned to 9.3.1. No Gradle
wrapper binary or debug signing key is checked in.

From the repository root:

```sh
npm ci
gradle -p android :app:assembleDebug
```

Gradle bundles the local web assets using `scripts/build-mobile.mjs`. To build
those assets separately, run `npm run build:mobile`. The installable output is
`android/app/build/outputs/apk/debug/app-debug.apk`; build outputs are ignored.

## Validation and limits

`npm run check` covers source capture, handoff coordination, browser layouts and
desktop transports with fixtures. For native Android transport fixtures:

```sh
gradle -p android :app:assembleDebug :app:assembleDebugAndroidTest
node scripts/test-android-backend.mjs
node scripts/test-android-service.mjs
node scripts/test-android-ui.mjs
node scripts/test-android-settings.mjs
```

The native runner installs only on the explicitly selected emulator and uses
Mac loopback fixtures via `10.0.2.2`; it does not discover or contact the actual
projector. See [phone implementation and acceptance](../docs/agent/phone-app.md)
for recorded results and physical tests still required.

For an explicitly intended live phone playback/volume test, install both built APKs
on that phone, then invoke the separate confirmed mode:

```sh
adb -s "$LUMA_PHONE_SERIAL" shell am instrument -w \
  -e mode live-controls -e confirmLivePlayback true \
  dev.framextv.lumaremote.test/dev.framextv.lumaremote.BackendInstrumentation
```

This mode requires saved setup and an already-playing verified public HTTP
source. It tests notification and media-session Pause/Play and volume down/up,
then restores the exact starting raw volume and verifies the identical source
is playing. It never resets setup, opens media, adds
subtitles, seeks, stops or disconnects. Without the confirmation flag it exits
before accessing playback. Do not use the emulator fixture modes on a phone.

The mobile cases cover validated setup, saved-settings presentation,
cancellation, reset/rescan, volume commits, session restoration, system-control
launch and confirmed power commands. Current0.2.3 results and installed artifact
are recorded in the [handoff](../docs/agent/session-handoffs/2026-10-18-phone-volume-power.md).
The user confirmed live casting with picture, sound and Portuguese subtitles
on0.2.0, and system-control display/actions on0.2.2. Cold-start crashes/missing
subtitles recurred on S1E3; that receiver failure and full phone-off acceptance
remain unresolved.

The service uses Android’s
[`connectedDevice` foreground-service type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)
and a [`MediaSession`](https://developer.android.com/reference/android/media/session/MediaSession).
The live backend and subtitle aliases live in service memory; the Activity
binds to that session. The receiver cache and encrypted, validated Kodi login
persist separately. **Disconnect** retains saved setup and leaves playback
alone; **Settings → Forget saved setup** removes the chosen stored details.
The app does not install a boot receiver or promise automatic session recovery
after force-stop, reboot or process death.

Source capture currently accepts only HTTPS `framextv.xyz` pages and readable
same-origin players. Unsupported/encrypted/bare-blob sources and cross-origin
players are rejected. The website WebView has no native JavaScript bridge. A
separate local-only WebView hosts the trusted remote. No cookies or browser
authorization headers are copied to Kodi. App pairing that requires a PIN,
touchpad/reverse-screen view and projector boot/default-launcher changes remain
outside this prototype. HLS seek stability remains unconfirmed on the device.
