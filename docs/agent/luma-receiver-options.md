# Kodak LUMA receiver options

Research checked 2026-10-18. Exact user projector model, installed Android
version, application store and firmware are still unknown. The user subsequently
installed/configured Kodi 21.2: public-film playback and controls worked, with
user-confirmed picture/sound and bounded continuation after the Mac controller
stopped. See [hardware evidence](../../tests/manual-kodi.md). No APK was installed
by the agent and no actual phone-off test was performed.

## Start with the existing receiver

Kodak's current [LUMA 350 support FAQ](https://kodakprojectors.caglobal.com/pages/luma-350-portable-smart-projector-support)
and [LUMA 400 support FAQ](https://kodakprojectors.caglobal.com/pages/kodak-luma-400-portable-hd-smart-projector-support)
explicitly advertise DLNA casting on the same network and installing apps from
the built-in store. The current [LUMA 450 product page](https://kodakprojectors.caglobal.com/products/kodak-luma-450-portable-full-hd-smart-projector)
also advertises Android apps and DLNA. Therefore a built-in DLNA renderer is a
reasonable first hypothesis to test before adding receiver software.

This is a manufacturer capability claim, not observed UPnP AVTransport support.
The official [350 manual](https://cdn.shopify.com/s/files/1/0568/7024/0322/files/RODPJS350_Manual_EN_121824_07.17.26.pdf?v=1784721755)
and [400 manual](https://cdn.shopify.com/s/files/1/0568/7024/0322/files/RODPJS400_Manual_EN.pdf?v=1738320197)
describe app-store installation and Miracast/AirPlay menus, but their searchable
text does not describe DLNA activation or APK sideload installation. Confirm
the actual device UI and discover its services before claiming a working route.

The current [350 product listing](https://kodakprojectors.caglobal.com/products/kodak-luma-350-portable-smart-projector)
says Android 9; the 400 support FAQ and 450 product listing also say Android 9.
These do not establish the OS installed on the user's particular unit. Read
Settings/About and the model label; do not assume an older or newer revision.
APK installation, CPU architecture and compatibility remain device checks.

## Existing receiver apps

These are publisher descriptions, not device compatibility results. Official
store availability is distinct from OEM licensing or permission to redistribute
an app inside our product. No redistribution or SDK agreement was verified.

| Candidate | Publicly documented capability | Relevance and limit |
| --- | --- | --- |
| [AirPin PRO](https://play.google.com/store/apps/details?id=com.waxrain.airplaydmr3&hl=en_GB) | Android/TV/projector receiver; DLNA/UPnP renderer and player; external-player support; direct NAS/DLNA/Samba playback; mode preserving playback when the controller exits. | Strong off-the-shelf DLNA experiment. Paid app; verify installation and phone-off behavior on this projector. The description does not promise arbitrary HLS/auth/codec compatibility. |
| [BubbleUPnP](https://bubblesoftapps.com/bubbleupnp/) | Integrated UPnP/DLNA renderer controlled by another network control point; Android TV support. | Another standards-based receiver/controller combination. Install on the projector as renderer, and use a separate controller. Phone-hosted local media, capture, proxying or transcoding still creates a phone dependency. |
| [AirScreen](https://play.google.com/store/apps/details?id=com.ionitech.airscreen&hl=en) | Receiver advertising Cast, DLNA, AirPlay and background service; ads and in-app purchases. Publisher flags incomplete support for some Android TV models; Miracast is disabled from Android 8. | Useful compatibility experiment for existing senders. A compatibility label is not proof of full Google Cast behavior, certification, DRM support or phone-off playback. Choose media URL casting, not screen mirroring. |
| [AirReceiver](https://play.google.com/store/apps/details?id=com.softmedia.receiver&hl=en) | Paid multi-protocol receiver advertising Cast and DLNA, background service and startup on boot. | Another compatibility experiment; same hardware, protocol and direct-fetch acceptance checks apply. |
| [Web Video Caster Receiver](https://play.google.com/store/apps/details?id=com.instantbits.cast.receiver&hl=en) | Official receiver requires the Web Video Caster mobile companion. | Quick end-to-end app-pair experiment, but not a documented open receiver API for our own controller. Publisher listing alone does not establish whether a given source is fetched directly or relayed through the phone. |

Do not infer minimum receiver Android versions from the sender compatibility
versions in marketing copy. Confirm the store's compatibility assessment or
publisher APK metadata against the actual device. Prefer the built-in store or
publisher-authorized distribution; this investigation did not obtain APKs.

## Proposed experiment

1. Identify model/Android version and connect phone/projector to the home router.
   A phone hotspot cannot remain the network if that phone must power off.
2. Open any built-in DLNA receiver and inspect its SSDP description for
   `MediaRenderer`/`AVTransport`. If unusable, try one compatible receiver app.
3. Use a long, authorized public HTTP(S) MP4 served independently of the phone;
   send its URL to the renderer. Preserve sanitized evidence of the renderer
   fetching new bytes or ranges after the controller disconnects.
4. Power the phone fully off and leave playback running beyond a short buffer.
   Also verify seek/pause/resume before shutdown and control reconnection after
   startup. A resumed next item or expiring token is a separate acceptance test.
5. Test the intended HLS/media source only after the transport works: receiver
   connectivity, codecs, subtitles, cookies/headers, URL expiry and any supported
   DRM workflow can each determine feasibility.

An Android shell app is feasible in principle if this firmware permits normal
app installation: receive a playback command, hand an authorized URL to a local
player, and let the projector fetch the media. It needs a documented remote
control transport and receiver-side ownership of playback. Installing a player
alone does not create remote discovery or a phone-to-projector launch protocol.
See [mobile target](mobile-target.md) for the acceptance requirement.
