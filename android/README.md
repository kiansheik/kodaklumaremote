# LUMA phone prototype

An Android sender with a packaged web remote and native TCP/UDP/Kodi HTTP
transport. The Mac is not needed at runtime. The projector retrieves media and
subtitles directly; the phone does not relay them.

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

Open **Browse**, visit the frameXTV episode and wait for its player, then tap
**Cast to LUMA** in the browser toolbar. An HTTPS frameXTV page can also be
shared from Chrome to this app. The sender launches installed Kodi, waits for
its API, opens the declared media, registers all unique declared subtitle tracks
and selects Brazilian Portuguese, falling back to English. The app then opens
**Remote**. Only one subtitle track displays at a time.

The no-scroll Remote screen keeps Home at upper left, Back at upper right,
playback and a central five-button D-pad visible. Its volume slider and ±
buttons use the observed receiver range; a slider gesture sends one committed
value and reads volume back. **Subtitles** opens language selection; **More** holds typing, apps,
seek, Kodi launch and Stop. Lists use search and pages. Cancelling casting
revokes queued source-bound commands; commands already accepted by the receiver
cannot be undone. Browsing another page after a successful handoff does not
stop receiver playback.

After connecting, allow notification permission when Android asks if you want
notification controls. The ongoing notification opens Remote and offers
Play/Pause, **Stop video** and **Disconnect** for the verified current source.
The app publishes a MediaSession for Android’s lock-screen/system media
controls; their actual appearance and behavior still need testing on Samsung.
**Disconnect** closes the controller connection and leaves Kodi playback alone.
Use **Stop video** to end the video.

Force-stop, reboot or process death can end the live session. Saved setup is
used when the app next opens; automatic service restart and restoration of
playback/language aliases after process death are not guaranteed. This service
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

The mobile UI has 22 passing browser cases, including validated first setup,
saved-settings presentation, cancellation, reset/rescan, volume commits,
session restoration and notification-open handling. Final checks passed:
109 Node,65 browser,8 native transport,4 service,5 packaged UI and5 Keystore
settings checks. Updated0.2.0 is installed on Samsung Android16. Live casting, switching apps,
lock-screen controls and full phone-off playback remain unconfirmed there.

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
