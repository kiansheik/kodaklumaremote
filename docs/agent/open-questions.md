# Open questions

1. Can the Philips43PFG6910/78 expose a general media receiver (Google Cast app
   or UPnP AVTransport) accepting arbitrary authorized media URLs? Chrome currently
   enables it for YouTube but disables it for frameXTV/general tab casting.
2. Does frameXTV's existing native Cast control already satisfy the user in an
   ordinary uninstrumented Chrome session? Its code is present, discovery-gated.
3. Can that receiver fetch and decode the target's authorized source and tracks
   independently? Actual codecs, expiry, receiver CORS/auth and DRM unknown.
4. Which enabled disable-devtool detector causes automated blanking? Manual
   DevTools-open and Anti Anti Debug fingerprinting reports not reproduced.
5. Are exposed subtitle tracks compatible on the actual receiver? Extension
   supports HTTP WebVTT, not blob/SRT/internal HLS conversion or audio selection.
6. Are cross-origin backup players needed? They are outside current host scope.
7. Does wrapper/full-video fullscreen and BFCache work on real Chrome? Wrapper
   handling and simulated BFCache are covered; native video fullscreen cannot
   display the extension overlay outside the replaced video element.
8. Full mobile acceptance is phone fully OFF, receiver still fetching media.
   Which direct receiver transport will be supported by the Android controller?
   Miracast-only targets need another player and cannot satisfy this alone.

Resolved: public reachability confirmed; same-origin embed + declared source
found statically; existing-page CAF avoids an extension remote-SDK loader;
public demo live SDK ready over HTTPS. These do not settle receiver feasibility.
Additional resolved observations: TV discovered after user brought it online;
YouTube app-specific playback physically confirmed by user. Generic Cast and
phone-off target-media playback are still unverified.
