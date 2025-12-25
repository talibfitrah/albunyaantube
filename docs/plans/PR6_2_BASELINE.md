# PR6.2 Baseline: Adaptive Manifest Availability

Date: 2025-12-25T15:14:55+01:00
Source log: docs/plans/PR6_2_adaptive_avail.log
Build: DEBUG (AdaptiveAvail logs are debug-only)
Device: Google sdk_gphone64_x86_64 (emulator)
Android: 16 (SDK 36)
Locale: en-US
NewPipeExtractor version (Android): v0.24.8

## Sample Summary
- Total JSON samples: 61
- Unique videoIds: 61
- Dedupe: 0 duplicates

## Overall Availability
- hasHls: 5 / 61 (8.2%)
- hasDash: 5 / 61 (8.2%)
- none (no HLS + no DASH): 56 / 61 (91.8%)
- Adaptive status:
  - BOTH: 5
  - HLS_ONLY: 0
  - DASH_ONLY: 0
  - NONE: 56

## Breakdown by streamType
- VIDEO_STREAM: 56 (NONE)
- LIVE_STREAM: 5 (BOTH)

Observation: in this sample set, only live streams have adaptive manifests; all VOD resolved to progressive-only.

## Duration Buckets (durSec)
- <60s: 14
- 60-299s: 14
- 300-899s: 9
- 900-1799s: 5
- >=1800s: 14
- unknown (-1): 5

Note: No explicit Shorts flag is logged; <60s is a proxy only.

## NO_ADAPTIVE videoIds (first 20)
- V2Brp_esIVI
- WFBUqjDt_oA
- dU8hTbYdfMc
- T8lXiW5x8_s
- IpiijxxkwQQ
- 6eJKCvCegPU
- i4KOLSr_Bpc
- poL6KjiUCZY
- tXEMflvBUVU
- IMOD0FZ7yj8
- 7KP-elyP-EE
- WoQwKqlov2o
- 1T1D8gFcncU
- -ZjmgAZ3D5A
- U4am56SC9xY
- n1Z3I1ZoXPg
- A2NCkECxFF8
- Vj5_eLEQkn0
- 3Evk4P6id_s
- ilJBOUI7LTY

Full NO_ADAPTIVE list is in the raw log (56 ids).

## Initial Hypotheses (to validate in PR6.2 investigation)
- NPE may be using the Android "reel" Innertube response (which can omit dash/hls for VOD).
- iOS client fetch is disabled by default in NPE; HLS may only appear for live streams.
- Web metadata player response is $fields-limited and does not include streamingData, so adaptive
  manifests are primarily dependent on Android/iOS/embedded player responses.
