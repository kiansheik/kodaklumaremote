# Kodak LUMA review findings

Checked 2026-10-18 against public Google Play data for
[`com.caglobal.kodakluma`](https://play.google.com/store/apps/details?id=com.caglobal.kodakluma).
The listing exposed only three featured reviews and live browser expansion
failed. A bounded public-endpoint read using
[google-play-scraper 10.1.4](https://github.com/facundoolano/google-play-scraper#reviews)
then retrieved 200 newest English/US, 100 helpful English/US and 15
Portuguese/Brazil entries. Deduplicating by review ID produced **220 written
reviews**: 205 from the English queries and 15 additional entries from the
Portuguese query, dated **2020-03-21–2026-09-15**. Query locale does not guarantee
the language in which each reviewer wrote.

This is a language/sort-selected sample, not every rating or a random sample.
Themes were manually coded conservatively and can overlap. Generic insults,
unspecified bugs and broad failure reports were not assigned specific causes.
The sample contains 151 one-star, 22 two-star, 14 three-star, 11 four-star and
22 five-star reviews. Star score alone did not determine complaint coding.
Raw reviews, IDs, dates, stars, URLs and coding are retained only in ignored
`.local/luma-analysis/reviews-{deduplicated,coded}.json`. No login or Google
credentials were used; no review text is reproduced verbatim.

## Repeated specific issues

| Theme | Reviews out of 220 | Meaning |
| --- | ---: | --- |
| Crashes or failure to open/start | **87** | Includes a large 2025 cluster. Phone/OS mentions are reports, not a diagnosed root cause. |
| Discovery, connection or disconnection | **31** | Includes visible devices refusing connection, repeated attempts and delays; two explicitly mention phone sleep/timeouts. |
| Navigation, touchpad or lag | **11** | Scrolling inside streaming apps, unresponsive touchpad, stuck directions or general input lag. |
| Projector app/store/playback faults | **10** | App updates/install compatibility and native-service playback/account issues; these are not automatically remote-client bugs. |
| Specific hardware/firmware faults | **6** | Power, focus/keystone, battery or Bluetooth reports; keep separate from LAN remote behavior. |
| Rearrangeable projector home apps | **1** | A feature request, not a repeated dominant complaint. |

Fifteen reviews mention particular phones/Android versions while describing
failure; that is a cross-cutting compatibility signal, not fifteen proven OS
defects. These counts establish frequency only in the retrieved sample.

## Dated examples

| Review date/source | Stars | Specific report, paraphrased |
| --- | --- | --- |
| [2021-02-27](https://play.google.com/store/apps/details?id=com.caglobal.kodakluma&reviewId=2e051f02-b04a-46b1-98a8-1a5849b3c172) | 2 | Device appears, but connection repeatedly fails. |
| [2022-09-17](https://play.google.com/store/apps/details?id=com.caglobal.kodakluma&reviewId=829be9ff-1aa4-450e-b3b5-f05498a4f2d1) | 2 | Touchpad barely responds and navigation feels clumsy. |
| [2023-12-11](https://play.google.com/store/apps/details?id=com.caglobal.kodakluma&reviewId=52f8419b-925d-42be-8830-91a653326670) | 1 | App fails to open on Android 14. |
| [2025-07-06](https://play.google.com/store/apps/details?id=com.caglobal.kodakluma&reviewId=38d5330a-8645-4698-a9cc-51e3dca0d3d7) | 1 | Startup crash on Samsung S24+/Android 15 before the initial screen. |
| [2026-05-04](https://play.google.com/store/apps/details?id=com.caglobal.kodakluma&reviewId=de7c3854-afca-4aaa-bbb5-6ec199739545) | 3 | Roughly two-minute discovery delay, projector-store setup failure and home-app arrangement request. |

## Product decisions and acceptance

The following criteria are our implementation decisions, not reviewer-validated
fixes. The mobile UI and Android shell are implemented; **22 mobile browser
checks with a mocked native bridge, eight backend, four service, five packaged
UI and five Keystore settings checks passed**. Updated0.2.0 is installed on
Samsung Android16. User confirmed baseline projector control; updated live
casting, background/lock-screen and phone-powered-off acceptance remain pending.
Existing code and physical checks remain the source of truth.

The primary controls follow the household’s requested workflow and conventional
remote layout. Review-theme frequency measures complaints in this sample;
it does not measure which controls people use most.

| Concern | Existing prototype | Next acceptance criterion |
| --- | --- | --- |
| Startup crashes and phone compatibility | Android shell hosts packaged local UI assets and a separate source browser; a narrow native transport controls the receiver. Offline UI startup and recoverable errors have fixture coverage. | Cold-start and resume on the actual Samsung phone with network absent/present. A receiver outage must produce a recoverable error, never prevent the interface opening. |
| Discovery wait and failed linking | Bounded search and a cached single-projector address, tried automatically on later opens. Settings provides a fresh scan and manual address/selection; a unique rescan result can connect directly. | Search ends within its advertised interval; distinguish no reply from connection failure; test cached-address recovery and rescan on the real network. Measure boot-to-service readiness separately. |
| Repeated setup and unreliable recovery | First setup launches Kodi and validates its HTTP login before saving. Kodi details are encrypted using an Android Keystore key in app-private storage; the web UI never receives saved secrets. Settings can edit or selectively forget the projector/login. Service restoration reuses observed playback and language aliases without replay. Native persistence/reset/auth fixtures passed. | Verify wrong-login handling, saved-login reuse after reopening, reset/rescan and real network interruption on Samsung. |
| Background/lock-screen use | Foreground connection service, source-bound notification actions and Android MediaSession are implemented. Opening the notification selects Remote without discarding drafts or active handoff progress. | Test the Samsung lock-screen controls and return-from-other-app behavior. Disconnect must leave playback running; explicit Stop must stop it. Force-stop/reboot/process death are not guaranteed restoration paths. |
| App access and arrangement | Combined mobile Remote includes Kodi launch and a searchable, paginated app list; launches use the validated receiver inventory. | Verify the complete phone-to-projector launch flow. Optional reorderable app shortcuts would customize this client only; no projector home-screen rearrangement is implemented. |
| Scrolling, stuck keys and lag | Large D-pad, one command in flight, bounded keyboard repeats; secondary controls open one panel at a time. | Test focused D-pad navigation from the actual phone; confirm no stuck direction after release/backgrounding. Touchpad/scroll gestures remain unimplemented and require protocol/device validation. |
| Household no-scroll remote | Home is upper left, Back upper right, with playback, an observed-range volume slider/± and a central five-button D-pad. Valid setup hides the primary Devices tab behind Settings. Typing, apps, seeking and language selection use separate panels. Browser checks verify viewport fit at 360×640 and 390×844, plus a 360×400 keyboard-sized layout. | Confirm layout, keyboard behavior, actual volume readback and reachability on Samsung with its font/display settings. These priorities follow the household request, not inferred review usage. The external video website may scroll normally. |
| Store/update compatibility | Remote can launch installed packages. | Show app availability accurately. Do not promise to repair projector firmware, store certification, incompatible APKs or account setup. |
| Phone/version variation | Responsive local UI and native receiver operations have browser/native fixture coverage; a baseline APK was installed on Samsung Android 16. | Test live playback and lifecycle behavior on that phone/projector; installation and fixtures do not establish full device compatibility. |
| Projector Bluetooth | No Bluetooth implementation. | Keep outside this LAN-remote scope until a separate hardware/firmware diagnosis establishes an actionable cause. |

The implemented **Browse → Cast → Remote** flow and Portuguese-first subtitle
handoff have fixture coverage. Receiver-side media fetching and playback after
the phone is powered fully off remain household requirements, not dominant
repeated requests demonstrated by this review sample. The complete phone flow
still needs physical playback and phone-off acceptance.

Implementation/evidence: [LUMA remote](luma-remote.md),
[phone-off target](mobile-target.md), [Kodi device test](../../tests/manual-kodi.md).
