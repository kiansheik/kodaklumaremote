# Current subtitle provider profile

## 2026-10-22 homepage sample

Read-only sampling covered five titles visible on the current homepage. No raw
subtitle bodies, media URLs, session data or credentials were retained in Git.
For four titles, English, Portuguese and Portuguese (Brazil) were downloaded to
temporary storage and passed through the checked-in Android normalizer.

| Title | API tracks | EN cues | PT cues | PT-BR cues | Observation |
| --- | ---: | ---: | ---: | ---: | --- |
| Clayface | 0 | — | — | — | The API returned no subtitle choices. |
| Bad Apples | 42 | 1609 | 1215 | 1236 | API metadata said VTT; all three bodies were actually SRT and normalized successfully by body sniffing. |
| Breaking Bad S1E1 | 64 | 589 | 655 | 559 | All three sampled VTT bodies normalized successfully. |
| Masterplan | 51 | 1310 | 1475 | 1473 | All three sampled VTT bodies normalized successfully. |
| The Simpsons S1E1 | 72 | 273 | 399 | 435 | PT/PT-BR passed. EN had 273 valid cues plus one final reversed timestamp and previously failed as a whole. |

All 12 selected provider responses returned complete HTTP bodies in this sample.
The one reproduced parser failure was not a transport or file-extension problem:
the Simpsons English body ended with a cue whose end time was earlier than its
start time. The former strict parser rejected all 273 earlier valid cues.

The normalizer now discards isolated malformed timed-text cues only when both
bounds hold: no more than 10 discarded cues and no more than 2 percent of the
track. It still rejects a wholly or materially malformed response. The discarded
count is retained in normalization/preparation metadata. The sampled Simpsons
track now yields 273 cues and reports one discarded cue; all 12 sampled bodies
normalize.

## Delivery conclusion

Hosting normalized subtitles from the phone was already implemented. Kodi uses
`SubtitleDelivery`; the Philips receiver uses `TvSubtitleBridge`. Both fetch the
selected public track, normalize it to UTF-8 SRT, keep it in bounded RAM and serve
it through a short-lived source/receiver-pinned LAN capability. No general media
proxy or persistent subtitle cache is needed for the reproduced malformed-cue
failure.

This profile does not prove visible captions on either household receiver. The
remaining failure space includes titles with no provider tracks, provider/network
failures not represented in this sample, receiver retrieval/acceptance, and
device-specific rendering or crashes. A fresh phone/receiver test is still needed.
