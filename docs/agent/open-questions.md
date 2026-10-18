# Open questions

1. Which physical receiver/model will be used, and does the public HTTPS demo
   actually discover it, play picture/audio and pass remote controls?
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

Resolved: public reachability confirmed; same-origin embed + declared source
found statically; existing-page CAF avoids an extension remote-SDK loader;
public demo live SDK ready over HTTPS. These do not settle receiver feasibility.
