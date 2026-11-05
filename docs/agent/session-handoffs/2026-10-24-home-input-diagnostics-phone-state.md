# Home-input diagnostics and connected phone state

## Goal

Determine whether the receiver and connected phone were actually alive after a
failed projector startup, and make future OK/Home setup attempts observable.

## Files inspected

- Required `docs/agent/{index,current-state,repo-map,open-questions}.md`
- Receiver startup, Activity, diagnostics and control-server sources/tests
- Phone `ConnectionService`, logging sites and installed Android package/service
  state through the explicitly connected Samsung ADB transport

## Files changed

- `android-receiver/app/src/main/java/dev/framextv/lumareceiver/{ReceiverApp,MainActivity,ControlServer}.java`
- `android-receiver/app/src/test/java/dev/framextv/lumareceiver/StartupReceiverTest.java`
- `android-receiver/app/build.gradle`
- `android-receiver/README.md`
- `release-notes/0.4.27.txt`
- `docs/agent/{index,current-state,repo-map,open-questions,log}.md`
- This handoff

## Commands run

- Live receiver `info` before and after manual open; authenticated
  `app.snapshot` reduced to UI-attached/encoded-size fields
- Bounded LUMA discovery and saved-address port 4191/8121 checks
- `adb devices -l`, filtered phone package/service `dumpsys`, `pidof`, and
  narrowly tagged `LumaControls`/`LumaReceiver`/`LumaHandoff` logcat
- `node --test tests/receiver-manifest.test.mjs`
- Receiver `:app:testDebugUnitTest :app:assembleDebug`
- `node scripts/build-latest-apks.mjs`
- `aapt2 dump badging`, `apksigner verify`, and `git diff --check`

## What worked

- After manual open, receiver 0.3.6/code 20 answered on port 4191 and its UI
  snapshot callback was attached.
- Phone 0.4.27/code 37 is running PID 2186 with foreground
  `ConnectionService`. Filtered logs show connected=true followed by a complete
  verified cast handoff and accepted remote metadata at 20:45:53.
- Receiver 0.3.7 persists only initial center/Enter down events, excluding key-up
  and held-key repeats. It separately records Home-settings openings and whether
  OK or Menu caused them.
- `info.startup` reports `defaultHome`, `uiAttached`, both counters, timestamps
  and the last settings source. The idle UI displays the OK count and confirms
  received OK when LUMA is already Home.
- Receiver unit/debug and signed release builds pass. APK metadata is 0.3.7/code
  21 and signature verification passes v1/v2.

## What failed

- The first live `info` request failed. The saved projector address then refused
  both receiver port 4191 and native port 8121 and emitted no bounded discovery
  response. This confirms that reported startup did not make the receiver ready.
- Existing receiver 0.3.6 never recorded input history, so earlier OK presses
  cannot be reconstructed.

## Remaining questions

- Install 0.3.7 after playback ends; installation/restart would interrupt the
  verified active session observed during this work.
- Use `info.startup` to prove the OK press reaches LUMA, Home settings actually
  launch, and Android resolves LUMA as default Home before the next cold boot.
- If `defaultHome:true` is established but the vendor launcher still wins a hard
  boot, evaluate device-owner persistent Home provisioning or privileged system
  integration rather than adding unverified broadcast actions.

## Suggested next prompt

Playback is finished. Install receiver 0.3.7, press OK once, read
`info.startup`, and then monitor a hard projector reboot.
