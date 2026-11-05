# Agent index

Start with [current state](current-state.md), [repo map](repo-map.md), and
[open questions](open-questions.md). Code and tests override these notes.

Research: [investigation](investigation.md), [site architecture](site-architecture.md),
[anti-debugging](anti-debugging.md), [network](network-analysis.md),
[playback](playback-analysis.md), [Cast feasibility](chromecast-feasibility.md).
Device follow-up: [Philips TV evidence](philips-tv.md),
[Philips DevView and standalone Hello World](philips-development.md),
[current Philips app execution investigation](philips-app-execution.md),
[DevView code acknowledgement and local Ghidra setup](philips-devview-protocol.md),
[Ghidra reassessment, upgrade decoder and native gateway leads](philips-ghidra-reassessment.md),
[local DevView/DNS and sideload follow-up](philips-sideload-followup.md),
[measured Philips browser compatibility](philips-browser-compatibility.md),
[firmware implementation and TV-server report](philips-firmware-implementation.md),
[live probe commands and privileged-interface follow-up](philips-probe-control.md),
[MSX local address, protocol selection and status0 source trace](msx-local-address-source.md),
[browser/API internals](philips-browser-internals.md),
[media internals and receiver function contract](philips-media-internals.md),
[Philips Matter inventory and control evidence](philips-matter.md),
[native Android Matter build](matter-android-path.md),
[mobile phone-off target](mobile-target.md),
[Kodak LUMA options](luma-receiver-options.md),
[LUMA without Kodi: browser receiver or own APK](luma-web-receiver.md),
[Kodi/VLC receiver interfaces](receiver-apps.md).
Working Kodi stepping stone: [setup and physical test](../../tests/manual-kodi.md).
Latest device result: [actual frameXTV and Portuguese subtitles](session-handoffs/2026-10-18-framextv-kodi-subtitles.md).
Projector remote: [LUMA protocol and browser controller](luma-remote.md).
Phone implementation: [Android sender and combined remote](phone-app.md),
[review findings](luma-review-findings.md),
[phone handoff](session-handoffs/2026-10-18-phone-handoff.md).
Saved setup: [background controls](session-handoffs/2026-10-18-phone-background-settings.md).
System controls: [cold start and lazy subtitles](session-handoffs/2026-10-18-phone-system-controls.md).
Latest: [playback reattachment and subtitle readiness](session-handoffs/2026-10-18-phone-reattachment-readiness.md).
Current: [system volume, confirmed power and Kodi error receipts](session-handoffs/2026-10-18-phone-volume-power.md).
Distribution: [signed releases and optional updates](../releasing.md),
[GitHub/Pages handoff](session-handoffs/2026-10-18-github-release-updates.md).
Local dual-device install: [guarded Make target](session-handoffs/2026-10-23-install-latest-apks-target.md).
ADB duplicate resolution: [physical-phone selection for the install target](session-handoffs/2026-10-23-install-latest-apks-device-resolution.md).
Current release: [0.4.23 receiver-first cold start and disconnected casting](session-handoffs/2026-10-23-receiver-first-cast-release.md).
Projector launcher candidate: [receiver 0.3.6 as the Android Home app](session-handoffs/2026-10-24-receiver-home-launcher.md).
Home/input observability: [receiver 0.3.7 diagnostics and live phone state](session-handoffs/2026-10-24-home-input-diagnostics-phone-state.md).
Manual source switching: [exact watch/title pages in the built-in browser](session-handoffs/2026-10-24-manual-stream-source-pages.md).
Local media and laptop remote: [phone byte-range streaming, compatibility HLS and cross-phone controls](session-handoffs/2026-10-30-local-media-browser-remote.md).
Current local playback: [full-movie audio acceptance and receiver disk prefetch](session-handoffs/2026-10-31-receiver-disk-prefetch.md).
Current buffer/DNS/subtitle follow-up: [truthful disk UI, ISP DNS diagnosis and episode-scoped subtitle caches](session-handoffs/2026-10-31-buffer-dns-subtitle-isolation.md).
Current anime repair: [audited identity, sub/dub, Library and Next](session-handoffs/2026-10-23-anime-library-next.md).
Anime audio UI: [episode-aware Sub/Dub toggle and guarded source swap](session-handoffs/2026-10-23-anime-audio-toggle.md).
Current Library/provider repair: [deletion, current-title shortcut and wrong-server fallback](session-handoffs/2026-10-23-library-delete-provider-identity.md).
Dual receiver: [Philips MSX and LUMA selection](msx-receiver.md),
[implementation handoff](session-handoffs/2026-10-18-dual-receiver-msx.md).
Current local Android update: [phone receiver, address entry and layout](session-handoffs/2026-10-20-phone-msx-native-text.md).
Current follow-up: [PIN feedback, MSX source trace and public redirects](session-handoffs/2026-10-20-msx-pin-and-address-diagnosis.md).
Earlier browser entry: [direct LAN install and initial navigation gap](session-handoffs/2026-10-20-browser-receiver-shortcut.md).
Earlier continuation: [handshake restored and numeric diagnostics installed](session-handoffs/2026-10-20-receiver-handshake-resume.md).
Debug carryover: [physical TV controls and remote reload proven](session-handoffs/2026-10-20-receiver-debug-channel.md).
LAN implementation: [TV-reported playback, pause/resume and feedback fixes](session-handoffs/2026-10-20-lan-sample-playback.md).
Latest acceptance: [user-confirmed TV picture and sound](session-handoffs/2026-10-20-tv-picture-sound-acceptance.md).
Native phone implementation: [Matter pairing and hardware remote](session-handoffs/2026-10-18-native-matter-phone.md).
Current published release: [0.4.6 Philips player and offline Library](session-handoffs/2026-10-20-release-0.4.6.md).
Current unreleased work: [remote next/scrub/back and subtitle provider profile](session-handoffs/2026-10-22-remote-controls-subtitle-profile.md),
[subtitle sample matrix](subtitle-provider-profile.md),
[scrub-preview provider profile](scrub-preview-profile.md),
[Kodi metadata and cross-phone progress](kodi-metadata-sync.md), and the
[combined feature handoff](session-handoffs/2026-10-22-cross-phone-metadata-and-scrub-previews.md).
Release preparation: [0.4.7 candidate and signing boundary](session-handoffs/2026-10-22-release-0.4.7-preparation.md).
Local release setup: [persistent ignored signing configuration](session-handoffs/2026-10-22-local-release-config.md).
Release staging: [safe automatic inclusion of new text files](session-handoffs/2026-10-22-release-auto-stage.md).
Prior release: [0.4.5 phone normalization and SPA addresses](session-handoffs/2026-10-19-subtitle-normalization.md).
Prior published release: [0.4.4 frameXTV capture domain fix](session-handoffs/2026-10-19-capture-domain.md).
Current repair: [video-first startup, manual subtitles and coalesced volume](session-handoffs/2026-10-19-video-first-volume.md).
Capture hotfix: [current frameXTV domain and native capture regression](session-handoffs/2026-10-19-capture-domain.md).
Mac evidence and isolated UI checks: [controller/browser handoff](session-handoffs/2026-10-18-matter-controller-browser.md).
Current playback experiment: [Philips local browser API](philips-jointspace.md),
[paired API, unverified browser launch and Mac receiver proof](session-handoffs/2026-10-18-philips-browser-setup.md).
Latest device feedback: [phone reconnect/navigation, browser retry and DIAL app launch](session-handoffs/2026-10-18-philips-power-cycle-follow-up.md).

Execution: [decisions](decisions.md), [experiments](experiments.md),
[progress](progress.md), [log](log.md), [handoff](handoff.md).

Active player work: [TV remote controls and shared progress](tv-remote-controls.md),
[catalog/progress handoff](session-handoffs/2026-10-20-tv-player-catalog-progress.md).

Viewing history: [offline Library, episode progress and device resume](offline-library.md).
