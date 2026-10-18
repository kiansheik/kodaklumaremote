# Philips Matter control

2026-10-18. Exact TV: **43PFG6910/78**, Titan OS. The user sees the TV's
Matter control setting. Its [official Philips datasheet](https://www.documents.philips.com/assets/20250604/c0cf294a62ad4c36b61ab2f200952bb3.pdf)
advertises Matter Smart Home control; it does not enumerate the exposed clusters.

## Observed boundary

A bounded, read-only DNS-SD inspection observed a Matter advertisement with
`DN=PHILIPS TV` and `DT=40` (decimal40 = `0x0028`, Basic Video Player). This is
advertised discovery metadata, not a commissioned endpoint inventory or proof
that every control works. Instance identifiers, addresses and setup codes are
intentionally omitted.

**The TV remains unpaired with our controller. No Matter cluster commands,
power/volume/navigation controls, media launching or subtitle behavior have been
verified.** An exact-model CSA certification record was not verified during the
bounded research; records for other Philips models cannot establish this TV's
capabilities.

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

The likely integration is Matter for TV hardware/navigation controls alongside
MSX for website video/subtitles. This remains a proposal, not an implemented
Matter adapter.

## Pairing prerequisites and next inspection

1. Open the TV's Matter setup and obtain its displayed QR payload/manual setup
   code privately. The TV and controller must share a reachable local network.
   If another ecosystem already commissioned the TV, use its additional-controller
   commissioning window rather than resetting existing setup.
2. Use a real Matter commissioner/controller and persist its operational fabric
   credentials securely. A bare IP address or the previous Kodi/ADB credentials
   do not authorize Matter controls. No Matter SDK was found in this repository,
   its node dependencies or the inspected local Gradle module cache.
3. After commissioning, first read Descriptor `PartsList`, `DeviceTypeList` and
   `ServerList`, then each relevant cluster's `FeatureMap`, `AcceptedCommandList`
   and attributes. Include media input/output lists, speaker volume capabilities,
   playback/text tracks, and Content Launcher MIME/protocol/URL support. Only then
   add supported controls and test their actual effects.

Implementation paths: the [official Android CHIPTool/controller](https://github.com/project-chip/connectedhomeip/tree/master/examples/android/CHIPTool)
can commission and control Matter devices. [Google Home Android APIs](https://developers.home.google.com/apis/android/supported-device-types)
also describe Basic/Casting Video Player traits but introduce a separate Google
Home SDK, ecosystem access and consent flow. Neither is currently integrated.
Commissioning concepts and multi-admin behavior are described in the
[official commissioning guide](https://developers.home.google.com/matter/primer/commissioning).
