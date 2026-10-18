# Philips Matter control

2026-10-18. Exact TV: **43PFG6910/78**, Titan OS. The user sees the TV's
Matter control setting. Its [official Philips datasheet](https://www.documents.philips.com/assets/20250604/c0cf294a62ad4c36b61ab2f200952bb3.pdf)
advertises Matter Smart Home control; it does not enumerate the exposed clusters.

## Observed boundary

A bounded DNS-SD inspection observed `DN=PHILIPS TV` and `DT=40` (decimal40 =
`0x0028`, Basic Video Player). The subsequently paired inventory below is the
capability evidence; an advertisement alone proves no control. Instance
identifiers, addresses and setup codes are intentionally omitted.

The user supplied the setup code privately. The Mac controller successfully
commissioned the TV and read the endpoint inventory, then closed cleanly.
Fabric material stays in ignored private storage. No setup code belongs in this
wiki, command arguments, release notes or public artifacts.
The Mac prototype uses Matter.js's backward-compatible default attestation
policy; successful commissioning did not establish production PAA-chain trust.
The Android adapter uses its separate strict native trust store.

## Paired inventory

| Endpoint | Observed capabilities |
| --- | --- |
| 0 | Root node; parts1 and2 |
| 1, Basic Video Player | OnOff, WakeOnLan, Channel, TargetNavigator, MediaPlayback, MediaInput, KeypadInput |
| 2, Speaker | OnOff and LevelControl, current/min/max level attributes |

KeypadInput accepts SendKey. MediaInput lists TV, HDMI1/2/3 and USB with a
SelectInput command. Speaker LevelControl accepts MoveToLevel and relative
commands. TargetNavigator's list is empty. MediaPlayback lists Play/Pause/Stop;
its current-state read returned status135 and advanced media/text-track reads
returned unsupported status134 in the current TV context.

Actual Mac checks: Home returned SendKey status0. A fresh speaker read reported
level1, minimum1 and maximum254; explicit MoveToLevel2 read back2, then the
`finally` restoration read back1. Both writes returned status0/verifiedtrue.
These are command/readback results; no claim of perceived loudness or physical
power/input effects is made. A later Right attempt failed before invoking its
key, and Left was not sent.

The cached operational address was initially scoped IPv6. Resolving only the
TV's announced Matter hostname supplied a confirmed private IPv4 and advertised
UDP port5640. The Mac controller's explicit hint/cache restored a read-only
three-endpoint inventory, though the full pass took100seconds. A later short
speaker read took4.4seconds: CASE2.6seconds, descriptors0.6seconds. An explicitly
requested enhanced commissioning window then succeeded, enabling phone pairing
without asking the user to revisit the outdoor TV's settings.

**No ContentLauncher, ApplicationLauncher or ApplicationBasic server appears in
the paired endpoint inventory.** The observed firmware therefore supplies a
hardware/navigation control path, without an established arbitrary URL or app
launch route. Listed commands still require an explicit effect test; reading an
accepted command list alone does not establish a visible physical change.

## Control versus playback

The [official Matter TV example](https://project-chip.github.io/connectedhomeip-doc/examples/tv-app/README.html)
distinguishes Basic Video Player (`0x0028`: On/Off, Media Playback, Keypad Input)
from Casting Video Player (`0x0023`, additionally Content Launcher). The actual
paired server/feature inventory must drive our UI:

| Desired behavior | Capability to inspect |
| --- | --- |
| Power and playback buttons | On/Off and Media Playback; wake from standby also depends on network/optional Wake on LAN support |
| Navigation, select, back/menu | Keypad Input features and supported key commands; arbitrary Unicode typing is not established |
| Speaker volume slider/mute | Appropriate speaker endpoint/Level Control or supported keypad codes; Audio Output alone is not proof of volume control |
| Input selection and app launch | Optional Media Input, Target Navigator and Application Launcher capabilities |
| Independent media URL playback | Content Launcher **URL Playback** feature, supported MIME types/protocols, and LaunchURL command |

[CSA's Application Cluster specification](https://csa-iot.org/wp-content/uploads/2023/10/Matter-1.2-Application-Cluster-Specification.pdf)
§6.7 defines LaunchURL with a content URL, optional display string and branding.
It requires HTTPS and constrains playback to the advertised `AcceptHeader` and
`SupportedStreamingProtocols`. The presence of Matter or basic playback controls
does not establish arbitrary HLS URL launching.

The current [official Media Playback schema](https://raw.githubusercontent.com/project-chip/connectedhomeip/master/src/app/zap-templates/zcl/data-model/chip/media-playback-cluster.xml)
has optional Text Tracks features: available/active tracks and activation by
track ID. These select tracks already available to the player; they do not add a
general external SRT/WebVTT URL. Our PT-BR subtitle handoff therefore still needs
the [MSX receiver](msx-receiver.md), or another verified subtitle-capable player,
unless subsequent device testing establishes an adequate native route.

Matter supplies hardware/navigation alongside MSX or another receiver for
website video/subtitles. MSX setup still physically fails with status0; Matter
does not resolve that receiver/bootstrap failure by itself.

## Native phone implementation

Version0.4.0/code9 is built and installed on Samsung, with existing Kodi
Keystore credentials verified decryptable before/after the signed upgrade.
It adds manual-code and private camera-QR pairing, durable authenticated
Keystore-encrypted fabric, reconnect, supported navigation/speaker volume/inputs
and a confirmation before Off. The service retains one native controller; each
phone commissions its own fabric rather than copying the Mac's credentials.
**Add another phone** requests a temporary commissioning window and displays
its QR/manual code only while the share panel remains open and unexpired.

Actual phone manual-code commissioning completed using the Mac-opened window
with the configured production PAA/CD trust store, without another TV setup
step. Phone Volume+1→2 and Volume−2→1 were independently checked by Mac reads;
the starting level was restored. Actual **Add another phone** opened a
TV-accepted enhanced window, displaying a fresh manual code, pairing QR and
expiry notice. Dismiss/reopen cleared the code and QR. No second phone was
enrolled. The user subsequently confirmed the signed phone app immediately
recognizes the TV and visibly navigates after TV off/on. Off/input writes,
camera scanning and second-phone enrollment remain pending.
The final signed upgrade retained the signer/Kodi login and reconnected the
saved Philips target at service level without another code. The secure PIN
initially blocked foreground UI inspection after upgrade; the later user report
supplies actual reconnection/navigation acceptance without a new app release.

Final review adds a separate confirmed **TV unavailable? Forget on this phone**
action for a reset/unreachable TV. It clears this phone's saved TV node/cache,
keeps the encrypted controller CA and other receivers, and sends no TV command;
the TV can retain its controller entry. Normal reachable-TV removal remains
separate. Matter-only Settings Disconnect is usable. Browser recovery coverage
and final native recovery/cancellation/lifecycle checks passed. Async pairing
updates service polling/lifetime; per-attempt cancellation preserves shared I/O.
Null volume/input snapshots clear stale cache, and disconnected MSX volume is
not reused when the newly paired TV has no speaker capability.

The no-scroll Philips remote combines Matter hardware controls with separately
connected MSX video controls; LUMA/Kodi keeps its independent connection. Results are in
the [native phone handoff](session-handoffs/2026-10-18-native-matter-phone.md).

The pinned [native SDK build](matter-android-path.md) requires Android API24+
and ARM64. It uses production PAA/CD roots and disables SDK progress/detail
logs that could otherwise expose generated pairing payloads. A documented
upstream default-IPK limitation remains; do not describe the development
controller wrapper as a separately audited production commissioner.

## Pairing prerequisites and next inspection

1. Open the TV's Matter setup and obtain its displayed QR payload/manual setup
   code privately. The TV and controller must share a reachable local network.
   If another ecosystem already commissioned the TV, use its additional-controller
   commissioning window rather than resetting existing setup.
2. Use the implemented native commissioner and persist its operational fabric
   securely. A bare IP address or previous Kodi/ADB credentials do not authorize
   Matter controls. Later phones use an additional-controller window; resetting
   the TV is not part of the normal flow.
3. After commissioning, first read Descriptor `PartsList`, `DeviceTypeList` and
   `ServerList`, then each relevant cluster's `FeatureMap`, `AcceptedCommandList`
   and attributes. Include media input/output lists, speaker volume capabilities,
   playback/text tracks, and Content Launcher MIME/protocol/URL support. Only then
   add supported controls and test their actual effects.

Implementation paths: the [official Android CHIPTool/controller](https://github.com/project-chip/connectedhomeip/tree/master/examples/android/CHIPTool)
can commission and control Matter devices. [Google Home Android APIs](https://developers.home.google.com/apis/android/supported-device-types)
also describe Basic/Casting Video Player traits but introduce a separate Google
Home SDK, ecosystem access and consent flow. Our app integrates the native CHIP
controller directly; Google Home APIs are not integrated.
Commissioning concepts and multi-admin behavior are described in the
[official commissioning guide](https://developers.home.google.com/matter/primer/commissioning).

The working Mac prototype is [tools/matter-controller](../../tools/matter-controller/README.md),
pinned to Matter.js0.17.9. It uses on-network commissioning without Bluetooth,
suppresses raw library logs and preserves private fabric state for reconnection.
The first real commissioning, inventory, accepted Home, speaker readback and
additional-controller window completed successfully. Phone acceptance remains
separate from this Mac proof.
