# Open questions

1. Can the Philips43PFG6910/78 expose a general media receiver (Google Cast app
   or UPnP AVTransport) accepting arbitrary authorized media URLs? Chrome currently
   enables it for YouTube but disables it for frameXTV/general tab casting.
   The user has now opened Media Station X. A custom direct-play receiver and
   dual-device remote are implemented; physical MSX setup still fails with
   status0. No TV HTTP/TLS request was established by the temporary Mac probe.
   Media codecs, subtitles and phone-off continuation need device acceptance.
   See [MSX receiver](msx-receiver.md).
2. Does frameXTV's existing native Cast control already satisfy the user in an
   ordinary uninstrumented Chrome session? Its code is present, discovery-gated.
3. Kodi 21.2 fetched/decoded the actual HLS and Portuguese subtitles. Can genuine
   Google Cast receivers accept the same source? Long-term expiry/auth renewal,
   alternate source compatibility and codec details remain unknown.
4. Which enabled disable-devtool detector causes automated blanking? Manual
   DevTools-open and Anti Anti Debug fingerprinting reports not reproduced.
5. Portuguese rendering and dialogue timing are user-confirmed from the Samsung
   sender. The phone now loads the preferred track first and keeps the full
   website catalog for on-demand selection. Other languages are not render-tested.
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
10. Does the installed0.4.0 phone reconnect after app/process restarts, navigate
    the TV and enroll another phone through its transient QR window? Native
    manual-code pairing completed with production trust enabled. Phone speaker
    volume1→2→1 was independently read back from the Mac, restoring the start.
    Mac pairing/inventory/Home status0 and an additional-controller window also
    passed. The phone also opened a TV-accepted additional-phone window; its
    code/QR cleared on dismiss/reopen. Second-phone enrollment, signed reinstall/
    reconnect, navigation, power/input effects and camera scanning remain
    pending. No ContentLauncher or
    ApplicationLauncher appeared, so Matter does not establish website video or
    external-subtitle delivery on this firmware. See [Matter evidence](philips-matter.md)
    and [native phone handoff](session-handoffs/2026-10-18-native-matter-phone.md).
11. Does final signed reinstall reconnect the existing phone fabric and retain
    Kodi setup? Source recovery/lifecycle checks passed54 browser and18 final
    focused native cases, plus26 earlier unaffected native cases. Confirmed
    local-only recovery retains the encrypted CA/LUMA state, issues no TV command
    and explains that it cannot remove an unreachable TV's controller entry.
    A reset/unreachable household TV was not deliberately created for this test.

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
