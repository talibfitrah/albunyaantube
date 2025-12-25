# PR6.2 Curl Matrix (WEB player variant)

This experiment replays the NPE WEB client request **without** the `$fields=` restriction to fetch full player responses.
Each row compares the captured ANDROID reel response (used by NPE for streamingData) with the WEB player response fetched via curl.

| videoId | ANDROID reel (captured) adaptive/formats | ANDROID reel dash/hls | WEB player (curl, no $fields) adaptive/formats | WEB player dash/hls | Conclusion |
|---|---|---|---|---|---|
| Ab3JXRI4iOk | 19 / 1 | False / False | 16 / 1 | False / False | Manifest absent in both ANDROID reel and WEB player responses |
| KYiYY05F1Cw | 7 / 1 | False / False | 7 / 1 | False / False | Manifest absent in both ANDROID reel and WEB player responses |
| T8lXiW5x8_s | 31 / 1 | False / False | 30 / 1 | False / False | Manifest absent in both ANDROID reel and WEB player responses |
| V2Brp_esIVI | 31 / 1 | False / False | 30 / 1 | False / False | Manifest absent in both ANDROID reel and WEB player responses |
| mFkxukW7WLA | 31 / 1 | False / False | 30 / 1 | False / False | Manifest absent in both ANDROID reel and WEB player responses |

Notes:
- NPE's original WEB request uses `$fields=microformat,playabilityStatus,storyboards,videoDetails` and therefore does not include streamingData.
- The WEB player curl variant returns streamingData but **no dashManifestUrl/hlsManifestUrl** for these VOD samples.
- The ANDROID reel response contains adaptiveFormats but also lacks dash/hls.
- This points to **manifest absent in response** (not a parsing/filtering bug) for these videos, at least for WEB and ANDROID clients.
