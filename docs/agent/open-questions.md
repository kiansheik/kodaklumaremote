# Open questions

Current checkpoint: actual HLS picture/sound/PT-BR on the Philips are user-confirmed.
Physical remote input is browser pointer emulation; the installed candidate opens
its panel but cannot navigate/select, and the later adapter is source-tested only.
Last source is configured; standby/browser restoration and native TV execution
remain unverified. The user ended Philips device work, then authorized publication
and added a durable offline viewing Library for Philips/Kodak. Follow
[current state](current-state.md) over the older chronological observations below.

Current Kodi questions: install the packaged metadata add-on on the physical
LUMA, start an episode from one updated phone, then join from a second updated
phone and verify title/season/episode, Resume movement over time, subtitle
selection and next-episode transition. Only the active canonical Kodi item is
synchronized; historical progress that exists solely on the first phone is not.
Kodi has no atomic compare-and-open RPC: the final source/key read rejects an
already-observed switch, but another controller can still switch in the narrow
gap before `ExecuteAddon`/`Player.Open`; physical two-phone acceptance must cover
that residual race.
Also measure whether representative direct HTTPS, unencrypted VOD HLS sources
yield timely 256 x 144 phone scrub previews on API 27+. DASH, progressive and
unsupported HLS sources intentionally remain timestamp-only unless a validated
provider-native storyboard path is found. The provider sample exposed no such
asset, and emulator extraction does not establish physical-device codec/network
support.

Current scope: Android work in the existing phone app is explicitly authorized.
The browser-first direct LAN handshake works. The actual TV reports matching
loaded/server debug revision d66f118d4c: Ping ACK0→1, fixed-label ACK and Reload
ACK followed by fresh state/ping1→0 establish the debug channel and remote page
reload. No manual-refresh bootstrap is pending. Phone-only fractional uptime and
later-hello reconnect-notice repairs are installed and physically verified:
idle app Reload produced “TV page reconnected.” and uptime705→1s, same d66.
The actual W3C sample at14:26:41 returned MEDIA_FAILED, media code4/networkState3/
readyState0 before metadata. This does not identify codec/TLS/network cause;
The same bytes served over the approved phone LAN route now have actual TV
GET206/successful Open/Playing0:03, stable Pause0:19 and ResumePlaying0:24 proof.
Combined installation/native8/multi4/build/lint pass;29 mobile/38 Node/one Chromium
remain passing. After an uninterrupted replay, the user confirmed picture and
sound for this clip. Full-length completion is not established. Real streams/
subtitles/phone-off operation and DevView/native
grants remain open; do not infer the exact external transport cause.
Earlier approval/deferral/MSX/Mac observations are
historical, not the current readiness state.

1. Can the Philips43PFG6910/78 expose a general media receiver (Google Cast app
   or UPnP AVTransport) accepting arbitrary authorized media URLs? Chrome currently
   enables it for YouTube but disables it for frameXTV/general tab casting.
   The user has now opened Media Station X. A custom direct-play receiver and
   dual-device remote are implemented. Later trusted-HTTPS delivery loaded the
   actual MSX diagnostic, and a temporary redirect reached the phone-hosted
   LUMA waiting screen. Earlier status0 did not establish a general LAN ban.
   Current0.4.6 adds expired-approval recovery and native PIN-paired address
   entry; general stream playback, visible typing, media formats,
   subtitles and phone-off continuation still need device acceptance.
   The user's current choice is the TV Internet browser. A follow-up build adds
   **Open TV receiver**, reuses saved PIN permission and requests the exact current
   native phone origin plus `/msx/connect.html`, without a public redirect.
   Manual browser-link typing is separate from the secondary MSX authority field.
   This direct LAN build is installed and a current refreshed TV session works;
   accepted launch/key receipts will not establish visible navigation or text.
   JointSPACE now advertises browser activities on the actual TV; authenticated
   launch of our standalone receiver may avoid MSX startup. PIN grant succeeded,
   but browser200 left the user at Home with no receiver traffic. A later expected
   TV peer was approved and the user confirms the LUMA page visible. The timer
   fix subsequently restored fresh state; the first and W3C samples were rejected.
   The same-byte LAN sample now has successful playback/pause/resume reports;
   the user subsequently confirmed picture and sound after replay. API context labels did not match
   the screen.
   A later exact-TV DIAL discovery did advertise an application base; one empty
   Netflix POST returned201 then running, and the user confirmed visible launch.
   This is not arbitrary media/subtitle delivery.
   See [MSX receiver](msx-receiver.md).
2. Does frameXTV's existing native Cast control already satisfy the user in an
   ordinary uninstrumented Chrome session? Its code is present, discovery-gated.
3. Kodi 21.2 fetched/decoded the actual HLS and Portuguese subtitles. Can genuine
   Google Cast receivers accept the same source? Long-term expiry/auth renewal,
   alternate source compatibility and codec details remain unknown.
4. Which enabled disable-devtool detector causes automated blanking? Manual
   DevTools-open and Anti Anti Debug fingerprinting reports not reproduced.
5. Portuguese rendering and dialogue timing are user-confirmed from the Samsung
   sender. The phone keeps the full website catalog for on-demand selection.
   0.4.3 now starts video without any
   subtitle requests and lets the user choose afterward, as requested after
   continued crashes/hangs in0.4.2. Other languages are not render-tested.
   0.4.5 now prepares selected text on the phone as UTF-8 SRT and observes
   complete serving plus Kodi selection before success. A 2026-10-22 refresh
   found 11/12 sampled bodies passing and one English track with 273 valid cues
   plus one reversed final timestamp. The unreleased bounded-cue repair makes all
   12 pass without accepting materially malformed files. One sampled title had
   no provider tracks. Physical non-default-title caption rendering remains
   unverified. See the [current profile](subtitle-provider-profile.md) and prior
   [normalization handoff](session-handoffs/2026-10-19-subtitle-normalization.md).
   Does lazy selection and Activity restoration pass on the actual phone?
   S1E3 subsequently crashed Kodi on first attempt and played without subtitles
   after Retry. The phone now distinguishes verified rejection from an unknown
   write outcome; the receiver crash cause and fresh subtitle acceptance remain
   open. Current Mac UI adds one link at a
   time; its physical batch test used the local API. No audio selector.
6. Are cross-origin backup players needed? They are outside current host scope.
7. Does wrapper/full-video fullscreen and BFCache work on real Chrome? Wrapper
   handling and simulated BFCache are covered; native video fullscreen cannot
   display the extension overlay outside the replaced video element.
8. Full mobile acceptance is phone fully OFF, receiver still fetching media.
   Android now implements LUMA launch plus Kodi HTTP; does the actual Samsung
   sender pass phone-off playback and reconnection? Live capture and playback
   with Portuguese subtitles are user-confirmed.
   Miracast-only targets need another player and cannot satisfy this alone.
9. Which Kodak LUMA model/Android/CPU does the user own? Kodi is installed and
   public MP4 and actual HLS/subtitles worked on version 21.2. Does built-in DLNA expose a usable
   MediaRenderer? Documented app interfaces are [here](receiver-apps.md), and
   manufacturer/commercial options are [here](luma-receiver-options.md).
10. Can the0.4.0 phone scan a code and enroll another phone through its transient
    QR window, and do confirmed Off/input effects work? Native
    manual-code pairing completed with production trust enabled. Phone speaker
    volume1→2→1 was independently read back from the Mac, restoring the start.
    Mac pairing/inventory/Home status0 and an additional-controller window also
    passed. The phone also opened a TV-accepted additional-phone window; its
    code/QR cleared on dismiss/reopen. The final signed upgrade retained Kodi
    login and reached saved-target service-level connection without another
    code; subsequent user feedback confirms immediate TV recognition and visible
    phone navigation after TV off/on. Second-phone enrollment, power/input
    effects and camera scanning remain pending. No ContentLauncher or
    ApplicationLauncher appeared, so Matter does not establish website video or
    external-subtitle delivery on this firmware. See [Matter evidence](philips-matter.md)
    and [native phone handoff](session-handoffs/2026-10-18-native-matter-phone.md).
11. Can the newly observed JointSPACEv6 browser activity open the Mac proof
    receiver and sustain direct-source video/PT-BR? This uses separate Digest
    pairing, not the existing Matter credentials. A fresh hidden-stdin PIN grant
    succeeded and privately saved credentials. Browser200 was accepted without
    visible launch or receiver TCP/assets/hello, while authenticated context
    labels disagreed with Home. A single saved-URL browser retry after TV off/on
    was again accepted with TCP/assets/hello0; the user confirms it did nothing
    and remained Home. A context read had empty focus. One Matter Right from
    the recent Netflix tile returned status0 and user-confirmed regular Netflix
    focus. The user later positioned browser1Right/MSX2Right from the first tile.
    A three-key Home batch reached HDMI1. Separate confirmed Home plus Right→
    Select showed ambiguous “Editar speed Dial”; Back plus a browser request
    returned to HDMI1, still receiver0. Named JointSPACE Home visibly returned
    to Home. Right,700ms pause,Confirm were accepted, but their screen result is
    unknown because the user left; all device work/questions stopped. Future
    browser/MSX setup requires observed focus, with no assumed cause. A photo is not
    required; use text feedback and do not infer focus from API results.
    Fifteen Python protocol/Unicode fixtures and13 Node fixtures pass without real
    device/media actions. The saved S1E2
    source HEAD and public configuration returned403, so fresh capture is also
    unresolved. Published0.4.1 did not contain Android JointSPACE integration.
    Android is now authorized: native PIN pairing and encrypted-credential
    address entry are installed; the new fixed browser-launch/link-typing
    direct LAN candidate is installed. Prior key acknowledgements/receiver-page
    visibility and phone approval alone do not establish fresh state. The timer
    fix later produced actual hello/poll/state. The later LAN sample has playback/
    pause/resume reports and user-confirmed picture and sound.
    See [browser setup](session-handoffs/2026-10-18-philips-browser-setup.md).
    The latest candidate repairs saved typing after a TV DHCP address change:
    it re-pairs against the current Matter-resolved private peer without deleting
    the old credential until grant success. The intended browser setup now uses
    the canonical LUMA page as the only speed dial. Real-TV feedback showed that
    putting the full direct receiver URL in its field was incompatible and led
    to a simple JSON rejection; the follow-up leaves the field blank and types
    only the phone's current private IP. The old `192.168.1…` suggestion was
    distinct from a reproduced receiver HTTP403 `REQUEST_REJECTED`: the phone
    listener was reachable at `192.168.68…`, but its saved approval still named
    the TV's former peer. The 0.4.16 candidate transfers that existing approval
    only to the current private Matter-authenticated TV before phone-IP autofill
    and persists it; this is compiled but not physically accepted. This does not yet
    prove that phone controls can foreground Internet or select the speed dial.
12. Can the physically confirmed DIAL route launch another supported receiver?
    Exact-TV multicast discovery supplied an `Application-URL`; Netflix state
    changed stopped→running after one empty POST201 and the user confirmed
    visible Netflix launch. YouTube GET was403. No arbitrary app/browser URL
    or subtitle payload is established; app IDs/endpoints must not be guessed.
    See [TV evidence](philips-tv.md) and the
    [current follow-up](session-handoffs/2026-10-18-philips-power-cycle-follow-up.md).
13. Can DevView launch our independent player-shell app through the normal
    app context? The user created a Partner Portal account and awaits developer
    verification. Enrollment/debug toggle/Home-launcher integration remain
    unverified; no pairing code is persisted. Ordinary Internet now runs our
    diagnostic with verified remote reload/source updates. The initial DevView
    research excluded Android; that restriction was later explicitly revoked
    for the existing phone app. DevView public-client PIN/check/
    session flow is now traced; exact TV-loaded bundle/backend cookie and approval
    internals remain unverified. [Protocol](philips-devview-protocol.md).
14. What execution interface can support an independent app on actual
    TPM256L_V251.003.105.021? Earlier REA DevView inspection identifies enrolled
    app/debug bridge consumers, not a native process loader. Runtime Internet
    samples expose only SmartTvA_API with exit/hasCapability; loadApp,
    enableDevtools and oipfObjectFactory are absent in that context. No method
    was invoked. Native-process execution remains unproved; Kodi is a behavior
    reference. See [execution history](philips-app-execution.md).
15. Why does the actual browser report Chrome112.0.4147.62 while the TPM256L
    platform table lists Chromium122? Exact engine/vendor binary provenance and
    DevView-versus-Internet differences remain unresolved. Actual API and fixed
    media declarations are collected. One fixed LAN MP4 now has user-confirmed
    picture and sound; broader decode support, subtitles, sustained playback,
    HTTPS/DRM and a particular website's acceptance require
    separate physical tests. This is the user's clarified current research
    scope, with no exploit work. See [measured browser profile](philips-browser-compatibility.md).
16. Can the current TPM256L251.003.105.021 secure container be decoded into
    validated browser/native components? Its official full ZIP/member are now
    downloaded, hashed, CRC checked and header-version matched. Matching
    MediaTek secure-container fields exactly bound the payload/hash regions;
    a bounded public-default-key sample did not yield a recognized script.
    No filesystem/native code extracted; corresponding vendor source remains
    unavailable. This closes acquisition, not implementation identity.
    Reassessment now verifies real REA/Ghidra native-control analysis; full UPG
    root recognition still fails. Both filesystem candidates are false positives.
    Full first5MiB default-key/script/SHA384 checks fail for both releases.
    Matching different-product vendor source now supplies structural and
    plaintext-hash/CBC contracts, not a Philips key or binary.
    [New evidence and gateway lead](philips-ghidra-reassessment.md).
17. Which supported app context can bind a TV TCP listener and reach the player?
    Current HTTP probe exposes no TCPServerSocket/TCPSocket/UDPSocket; WebSocket/
    RTCPeerConnection are exposed but unconnected. Upstream Direct Sockets has
    privileged runtime/context/policy gates; actual DevView/vendor grants remain
    unknown. WASM computation and WebGL shaders do not grant host socket APIs.
    Phone-hosted polling control is implemented and the actual phone receiver
    waiting page was delivered and its timer fix established actual phone/TV
    polling/state readiness. Media playback still needs device acceptance; a
    TV-hosted listener remains unproved. See
    [implementation/server report](philips-firmware-implementation.md).
18. Can a permitted HbbTV/DevView context expose the built-in app2app relay or
    socket service? Actual opera_omi constructor registry includes CSManager,
    but its native marker/prototype does not establish public construction;
    one zero-argument construction attempt failed with TypeError/no instance.
    Standard typed-object diagnostic in Internet created the DOM object without
    exposing local/remote URL methods; no relay activation attempted. Existing
    Mac-mediated fixed page commands already work without TV GUI navigation;
    extending this to media and phone control remains separate from a direct TV
    listener. See [live follow-up](philips-probe-control.md).

19. Can installed MSX run the same startup/plugin entirely over LAN? Public
    trusted-HTTPS delivery now passes actual TV plugin/commands, with the same
    native socket/factory absences and only viewport height changed. LAN attempts
    had2 TLS-to-HTTP and2 empty timeouts; real MSX v/t JSON cache queries exposed
    and fixed a separate route/CORS bug. HTTP MSX ancestor keeps HTTPS plugin
    insecure. Private-network policy is a source-backed inference, not a measured
    TV network error. The user now prefers the Internet browser; its installed
    direct LAN launch/link flow avoids this MSX bootstrap path, with no public
    redirect dependency. A refreshed phone-hosted receiver now completes actual
    hello/poll/state; automatic shortcut navigation remains unproved. Existing tunnel/Mac delivery does not
    prove independent browser startup or phone-off playback.
    [Current evidence](msx-local-address-source.md).

20. How early and reliably does the exact projector deliver the LUMA Receiver
    boot path? Source and emulator checks establish `BOOT_COMPLETED` → sticky
    foreground service → port 4191, direct phone authentication without port
    8121, and UI foregrounding on `player.open`. They do not prove the vendor
    firmware's broadcast timing, background policy or behavior after a hard
    power cycle. Measure first visible UI, first 4191 response, first accepted
    cast and first 8121 response on the physical projector. Also verify a second
    power cycle after force-stop/update, because Android normally suppresses boot
    delivery after an explicit force-stop until the app is opened again.

Resolved: public reachability confirmed; same-origin embed + declared source
found statically; existing-page CAF avoids an extension remote-SDK loader;
public demo live SDK ready over HTTPS. These do not settle receiver feasibility.
Additional resolved observations: TV discovered after user brought it online;
YouTube app-specific playback physically confirmed by user. Generic Cast and
phone-off target-media playback are still unverified.
Kodi public MP4 playback on LUMA is now physically confirmed, with remote
pause/resume/seek and bounded continuation after the Mac controller stopped.
Actual frameXTV HLS picture/sound and Portuguese subtitles are now physically
confirmed on LUMA Kodi from both the Mac and Samsung sender. Full phone shutdown
remains open. The phone user reported no visible controls in0.2.1 and repeated
starts/missing subtitles on S1E2. Diagnostics found inactive phone media state
after upgrade despite receiver playback, then subtitle-stage native errors and
timeouts during new attempts. The user confirmed0.2.2 notification shade and
lock-screen Play/Pause display/actions. S1E3 cold-start/subtitle failure still
needs physical resolution; see the latest current-state entry.
Why did the rapid pause/seek60/resume sequence stop this HLS? MP4 seek worked;
target HLS seek reliability has not passed. Retest deliberately with settled
commands before changing transport or introducing a relay.

LUMA native remote now has confirmed app inventory, receiver launch, navigation
and ready-device discovery. Remaining: text/volume changes, reverse-screen view
and touchpad, PIN pairing, actual Samsung acceptance and system-wide launcher
behavior on the exact Android/model. LUMA Receiver's source-level boot service
and receiver-first phone path are implemented; their physical cold-boot timing
is question 20. See [remote evidence](luma-remote.md).
