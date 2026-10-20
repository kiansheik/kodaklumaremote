# Open questions

1. Can the Philips43PFG6910/78 expose a general media receiver (Google Cast app
   or UPnP AVTransport) accepting arbitrary authorized media URLs? Chrome currently
   enables it for YouTube but disables it for frameXTV/general tab casting.
   The user has now opened Media Station X. A custom direct-play receiver and
   dual-device remote are implemented; physical MSX setup still fails with
   status0. No TV HTTP/TLS request was established by the temporary Mac probe.
   Media codecs, subtitles and phone-off continuation need device acceptance.
   JointSPACE now advertises browser activities on the actual TV; authenticated
   launch of our standalone receiver may avoid MSX startup. PIN grant succeeded,
   but browser200 left the user at Home with no receiver traffic; GET/hello and
   playback are not yet established. API context labels did not match the screen.
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
   complete serving plus Kodi selection before success. Twelve non-default
   homepage bodies normalized successfully; physical non-default-title caption
   rendering remains unverified. See the
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
    unresolved. Android JointSPACE/encrypted-credential/browser-handoff
    integration follows only after this proof; published0.4.1 does not have it.
    See [browser setup](session-handoffs/2026-10-18-philips-browser-setup.md).
12. Can the physically confirmed DIAL route launch another supported receiver?
    Exact-TV multicast discovery supplied an `Application-URL`; Netflix state
    changed stopped→running after one empty POST201 and the user confirmed
    visible Netflix launch. YouTube GET was403. No arbitrary app/browser URL
    or subtitle payload is established; app IDs/endpoints must not be guessed.
    See [TV evidence](philips-tv.md) and the
    [current follow-up](session-handoffs/2026-10-18-philips-power-cycle-follow-up.md).

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

LUMA native remote now has confirmed app inventory, Kodi launch, navigation and
ready-device discovery. Remaining: text/volume changes, reverse-screen view and
touchpad, PIN pairing, actual Samsung acceptance, projector packaging, cold-boot timing and startup
launcher support on the exact Android/model. See [remote evidence](luma-remote.md).
