# PR6.2 iOS Fetch Findings (HLS Manifest Recovery)

Date: 2025-12-25
Build: DEBUG
Device: Emulator (sdk_gphone64_x86_64)
Android: 16 (SDK 36)
Locale: en-GB / US
NewPipeExtractor: v0.24.8

## Setup
- Enabled iOS client fetch via `YoutubeStreamExtractor.setFetchIosClient(true)` in app init (debug).
- Captured youtubei requests/responses via OkHttpDownloader debug capture.
- Monitored `AdaptiveAvail` logs for HLS/DASH availability.
         
- iOS request captured for `V2Brp_esIVI` (archived in tar):
  - `docs/plans/npe_capture_round4.tar` contains:
    - `npe_capture_round4/V2Brp_esIVI_1766678837548_request.txt` (clientName IOS)
    - `npe_capture_round4/V2Brp_esIVI_1766678837548_response.json`
- The iOS response contains `streamingData.hlsManifestUrl`.
- The iOS response does NOT contain `streamingData.dashManifestUrl`.
- WEB/ANDROID responses for the same VOD IDs still lack dash/hls manifest URLs.

## Coverage Sample (Round 6)
Source: `docs/plans/npe_capture_round6.tar`
- Unique VOD ids (latest response per id): 17
  - HLS_ONLY: 17
  - NONE: 0
- iOS requests present: 17 / 17
- iOS responses with `hlsManifestUrl`: 17 / 17

## Implication
- Enabling iOS fetch materially increases HLS availability for VODs.
- DASH remains unavailable; this path improves HLS only.
- This uses NPE public API (no app-layer probing), but adds one extra youtubei request per extraction.
