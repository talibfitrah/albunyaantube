# FitrahTube iOS — App Plan

> **Status**: research and review complete, no code written. Playback facts were measured on **2026-08-22** from one residential IP (Morocco) with no cookies and no login; the catalog and Android facts were read from the live API and the repo the same day. YouTube changes extraction rules every few weeks — **re-run Appendix A from a real iPhone before building on these numbers.** Goal, as stated: playback from the user's phone requesting streams from YouTube directly, minimal backend load and rate-limit exposure, low App Store rejection risk, and **feature parity with the Android app**.

---

## 1. Decision

Build a native SwiftUI app (iOS 18+, iPhone + iPad) that:

1. Uses the existing backend (`/api/v1/*`, `/api/account/*`) for the curated catalog, accounts and sync. No server-side stream resolution, no media proxy.
2. Resolves streams **on the phone** with the **VISIONOS** InnerTube client → YouTube's own HLS manifest → `AVPlayer`. No poToken, no JS-player deciphering, one POST per play. Works for lectures, nasheeds and **live streams**; does not work for made-for-kids videos.
3. Ships a **fallback ladder** ordered by remote config: VISIONOS HLS → ANDROID itag 18 (360p, verified on all 18 kids videos in the sample) → terminal "not available" state. *(Owner directive 2026-08-27: the ladder ends here — no "Open in YouTube" hand-off, ever.)* **Ship-dark decision (controller 2026-08-27):** the official YouTube embed rung (`WKWebView`, navigation-locked) ships in the binary but is removed from the bundled `resolverOrder`; `RemoteConfig.sanitize` still accepts `embed`, so a published config can enable it as disaster recovery. The embed is the only path YouTube's Terms authorize (§9); `resolverOrder: ["embed"]` stays one remote-config edit away.
4. Lists **channel and playlist pages on the phone** with InnerTube `browse`, exactly as Android does with NewPipeExtractor today (§6.7) — the catalog is 845 channels and 452 playlists; the 245 standalone videos are the small part.
5. Ships **every Android feature** (§7) in two releases: v1 = everything usable without an account; v1.1 = accounts, Me tab, sync, submissions, import. Downloads and in-app update do not port (App Store rules); Chromecast becomes AirPlay.
6. Keeps every YouTube-facing parameter as **remote-config data**; ships **no over-the-air code** and **no dormant poToken minter** (the minter is kept built and tested on a branch, §6.12).
7. Has **no IAP, no FitrahTube ads, no analytics SDK, no telemetry upload** in v1 — the things that move App Store risk.

The open product problem is **made-for-kids content**: VISIONOS refuses it, every other native path was tested and fails at a ~1 MB CDN cap (§4 rows 8, 17–19), so those videos play at 360p or in YouTube's own player. How much of actual viewing that is, is unknown — §2, §10 item 7.

---

## 2. What the catalog actually is (live API, 2026-08-22)

| Fact | Number | Consequence |
|---|---|---|
| Approved channels / playlists / standalone videos | **845 / 452 / 245** (`/api/v1/content?type=…`, paged) | What users watch is mostly channel uploads and playlist items, which the backend does not list — Android enumerates them on-device (§6.7). The iOS plan must do the same. |
| "Content for children" category (`JCCMh7BIMNSgjc9XWWCQ`) | 22 channels (2.6 %), 3 playlists, 2 videos | The made-for-kids share of *plays* is not 45 %. The earlier "~45 % of the catalog" was the share of the **40 newest standalone videos** (18/40), which daily-uploading kids channels dominate. The flag also reaches outside the category (Language Learning Market, 1001Inventions). Measure before deciding (§10 item 7). |
| Live streams in the catalog | present (e.g. One4kids 24/7 `J3bDNstCnzE`) | VISIONOS returns live HLS for them (7 variants) — including a kids-channel live stream — and AVPlayer plays live HLS natively. No itag 18 exists for live; the fallback is the embed. |
| Kids uploaders in the 245 standalone videos | One4kids 17, Learn with Zakaria 15, أناشيد الروضة 12, Osratouna 11, Marah Tv 9, Zido World 4, Dar al-Hudaa 4, toyorbabytv 3, ~8 channels with 2 | ≈ 85/245 standalone videos; the kids problem is concentrated in ~10–22 channels. |

---

## 3. Hard constraints learned

| Constraint | Evidence | Design consequence |
|---|---|---|
| Bare InnerTube requests trip the bot check on most catalog videos (`LOGIN_REQUIRED "Sign in to confirm you're not a bot"`) | 22/40 failed bare; all passed once `visitorData` was reused | Persist and reuse `visitorData`; never fire session-less requests |
| VISIONOS returns `UNPLAYABLE "This video is not available"` for made-for-kids videos | 18/40 newest standalone videos, twice; yt-dlp source comment + maintainer thread | Kids videos need another path |
| **A web-platform poToken cannot be used on the IOS or ANDROID clients** | yt-dlp PO Token Guide: "A PO Token from one platform cannot be used on another (i.e., Web PO Token cannot be used on Android or iOS)" | Explains §4 row 8 completely. Every BotGuard token we can mint (WKWebView, Node, the backend sidecar) is a web token → the IOS client is dead for this app, not "unverified" |
| Web-family clients return the kids video with 22 direct URLs up to 1080p (MWEB), but the CDN caps delivery at **1.00 MB** with or without the backend's sidecar token | §4 rows 17–19 | No native full-quality kids path exists today; itag 18 (360p) is the floor |
| ANDROID itag 18 (360p muxed) streams fully without a token, with AVPlayer's User-Agent | 18/18 kids IDs: `OK`, itag 18 present, `AppleCoreMedia` UA ranges 206 | Verified floor for kids videos; yt-dlp lists `android` as "GVS or Player" token-required, so this is selective enforcement and can end (android_vr precedent) |
| `hlsManifestUrl` from VISIONOS plays natively in AVPlayer with no proxy and any User-Agent; manual captions arrive as `EXT-X-MEDIA:TYPE=SUBTITLES` renditions; auto-generated captions do not | macOS AVFoundation test; HLS masters inspected | No `AVAssetResourceLoader` proxy; captions menu works for manual tracks; auto-captions need an overlay (§6.5) |
| Stream URLs are bound to the client IP (`ip=` / `/ip/`) and expire (`expire=`, `expiresInSeconds = 21540`) | Every VISIONOS URL inspected | Cache in memory only, invalidate on network-path change, re-resolve on 403 (§6.2); AirPlay needs testing (§6.5) |
| Age-restricted videos return `LOGIN_REQUIRED` with an age reason; YouTube's embed refuses them too | yt-dlp `AGE_GATE_REASONS`; YouTube Help: age-restricted videos "cannot be watched on most third-party websites" | Branch on `reason`; never rotate the session for an age gate; go straight to the terminal "not available" state (Owner directive 2026-08-27: no "Open in YouTube" hand-off). Curation should reject `ytRating = ytAgeRestricted` (§8) |
| Apple 5.2.3 names YouTube explicitly for downloads | Guideline text (§9) | No download feature on iOS |
| YouTube's Terms authorize only the embeddable player; Apple 5.2.2/5.2.3 say "authorization must be provided upon request" | Verbatim text (§9) | Embed is the compliance floor and must always work; `resolverOrder: ["embed"]` stays one config edit away |
| Apple requires guest access when accounts are not essential, in-app account deletion when accounts exist, Sign in with Apple next to Google Sign-In, and a privacy manifest | 5.1.1(v), 4.8, privacy-manifest requirement since 2024-05-01 (§9) | Guest mode is mandatory on iOS (Android forces sign-in); accounts ship in v1.1 with deletion and Sign in with Apple |

---

## 4. Evidence — test matrix (2026-08-22)

Method: raw `POST youtubei/v1/player`, chunked `Range` fetches against googlevideo, BotGuard tokens minted in (a) Node + jsdom, (b) a real macOS `WKWebView`, (c) the production sidecar (`GET /api/v1/dub-potoken`), nsig solved by yt-dlp 2026.08.19 with Node, and AVPlayer via a compiled Swift tool. Catalog sample = first 40 IDs from `https://app.fitrahtube.com/api/v1/content?type=VIDEOS&limit=40`.

| # | Test | Result | Verdict |
|---|---|---|---|
| 1 | VISIONOS, popular control video, bare | `OK`; 27 adaptive URLs (no cipher, no `n`); HLS master with 17 variants 144p→2160p; 720p: 28 segments / 153 s all 200; full 3.45 MB audio file 200 | verified |
| 2 | VISIONOS, 40 catalog videos, bare | 0/40: 22 `LOGIN_REQUIRED` (bot check), 18 `UNPLAYABLE` | failed |
| 3 | VISIONOS, catalog, with `visitorData` session | Bot-check failures → `OK` + HLS + all URLs. The 18 `UNPLAYABLE` stay unplayable. Reproduced in a second run the same evening: 22 OK / 18 `UNPLAYABLE`, identical IDs | verified / kids gap |
| 4 | AVPlayer (macOS) on VISIONOS HLS URL | `readyToPlay` < 4 s, rate 1.0, buffered all 213 s, 0 stalls, `playbackType=VOD`, video+audio tracks | verified |
| 5 | CDN User-Agent enforcement on `rqh=1` URLs | Safari UA, `AppleCoreMedia/1.0.0` UA, Python default UA → all 200 | verified |
| 6 | VISIONOS sustained delivery, second session | itag 140 full 4.36 MB via 9 ranges, all 206 | verified |
| 7 | IOS 21.26.4 (current) | `OK` but 0/27 URLs (SABR only), with or without pot | failed |
| 8 | IOS 21.03.2 (NewPipeExtractor 0.26.5 pin) and 20.10.4, + BotGuard pot | 20/20 URLs, **no HLS**; CDN: 206 ×4 then **403** at ~1 MB audio, 206 ×5 then 403 at ~5 MB 720p — all four placements, jsdom- and WKWebView-minted tokens. **Cause (found in review): web tokens are not valid for the IOS client (§3).** | dead |
| 9 | IOS 19.45.4, ANDROID 19.44.38 | Request rejected (no playability status) | failed |
| 10 | ANDROID 21.26.364, itag 18 360p muxed | Direct URL, no pot needed, full 5.73 MB delivered (kids video) | verified |
| 11 | ANDROID_VR 1.61.48 | `UNPLAYABLE` (kids) / `LOGIN_REQUIRED` (other) | dead |
| 12 | tv, tv_downgraded, tv_simply, web, web_embedded, mweb — bare, **without** `signatureTimestamp` | `page needs to be reloaded` / `Video unavailable` — an artefact of omitting `playbackContext.contentPlaybackContext.signatureTimestamp`, not a client verdict (see rows 16–19) | superseded |
| 13 | Made-for-kids attribution | `madeForKids` absent from watch-page HTML; 18/18 `UNPLAYABLE` IDs are uploads by kids channels (Marah Tv, toyorbabytv, Osratouna tv, Learn with Zakaria, Language Learning Market, Moslim Kids Entertainment, abdelmajid ait abbou, 1001Inventions). Authoritative source is Data API `videos.list part=status` → `status.madeForKids`; no API key on the dev machine, not run | attributed, not confirmed — §10 item 7 |
| 14 | Live streams on VISIONOS (`J3bDNstCnzE` One4kids 24/7, kids channel) | `OK`, `isLive=true`, live HLS master with 7 variants; ANDROID also `OK` (HLS + DASH, no `formats[]`) | verified — live plays natively, no itag 18 for live |
| 15 | Captions in VISIONOS responses / HLS | `captions.playerCaptionsTracklistRenderer.captionTracks` present (mostly `ar` auto, some manual `en`); HLS master carries `EXT-X-MEDIA:TYPE=SUBTITLES` only for manual tracks (`uIwfpuLw24E`: 1 entry, `hls_timedtext_playlist`); `CLOSED-CAPTIONS=NONE`; 2 audio groups | verified |
| 16 | tv (TVHTML5 7.20250923), web_embedded — with `signatureTimestamp`, kids + control | `LOGIN_REQUIRED` (bot check) / `ERROR This video is unavailable` | failed |
| 17 | web, web_safari (Safari UA) — with sts, session reused | `OK`, 20–22 adaptive formats, **0 URLs** (SABR only), no HLS ("HLS only for trusted sessions" per yt-dlp) | dead |
| 18 | **MWEB 2.20250925 — with sts, kids video `wfs51fK4zdc`** | `OK`, **22 adaptive formats with direct URLs (no cipher), 1 muxed, top 1080p**; SABR URL also present | playable in principle |
| 19 | MWEB kids video via yt-dlp 2026.08.19 (nsig solved with Node) + **sidecar token** `GET /api/v1/dub-potoken?videoId=` appended as `&pot=` | itag 140: 206 ×2 then **403 at 1.00 MB** — identical with the token removed; itag 136 (720p): 403 at 5.5 MB; itag 18: full 4.78 MB. Same with AVPlayer's UA | **failed — the sidecar token does not lift the cap for MWEB video/audio formats from this IP** |
| 20 | ANDROID itag 18 across all 18 kids IDs, ranges fetched with `AppleCoreMedia/1.0.0` UA | 18/18 `OK`, itag 18 present, first and mid-file ranges 206 | verified |
| 21 | IP binding | HLS manifest path contains `/ip/<client IP>/` and `/expire/`; adaptive URLs carry `ip=`, `expire=`, `rqh=1` | verified |
| 22 | Catalog composition | 845 channels / 452 playlists / 245 videos; 22 kids-category channels | verified (§2) |

---

## 5. External corroboration

- **yt-dlp master** ([`_base.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py), [`_video.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py)): `_DEFAULT_CLIENTS = ('visionos', 'web')`, `_DEFAULT_JSLESS_CLIENTS = ('visionos',)`. The `visionos` block has **no GVS poToken policy** and `REQUIRE_JS_PLAYER: False`, with the comment `# "Made for kids" videos aren't available with this client`. Client added in [PR #17184](https://github.com/yt-dlp/yt-dlp/pull/17184), merged 2026-07-09. The `android` block: `GVS_PO_TOKEN_POLICY HTTPS: required=True … not_required_with_player_token=True` — itag 18 working without a token is selective enforcement.
- **yt-dlp [PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide)**: "A PO Token from one platform cannot be used on another (i.e., Web PO Token cannot be used on Android or iOS)"; `ios` and `android`: "GVS or Player" required; `mweb`: GVS required; tokens are "bound to the user session (Visitor ID or account Session ID) or to the video ID".
- **yt-dlp maintainers** ([#17226](https://github.com/yt-dlp/yt-dlp/issues/17226), 2026-07-15): "android_vr is rolling out a pot-like requirement, visionos does not have this." / visionos is "not being available for 'Made for Kids' videos and not having pre-merged formats … very similar to `ios`".
- **The precedent for enforcement arriving without notice** — `android_vr` block: "Since 2026.07, intermittent/selective POT enforcement has been observed for non-HLS formats. Since 2026.08.17, ALL formats (including live HLS and itag 18) are 403'd with version 1.65.10." This app retired its `AndroidVrStreamResolver` on 2026-08-18 for exactly that reason (`NewPipeExtractorClient.kt`).
- **This repo's backend** already runs a BotGuard sidecar: `DubPotokenService.java` — "an Android WebView … only yields an sps=2 'preview' token that caps at 1 MB … Node/desktop-class environments DO produce sps=3, so the actual minting runs in a small sidecar (`bgutil-ytdlp-pot-provider`)"; public `GET /api/v1/dub-potoken?videoId=`, one mint per video per ~6 h. Android uses it for **MWEB dub-audio** tracks (`DubAudioResolver.kt`: "MWEB gives direct (rawUrl) dub URLs; WEB/web_safari are now SABR-only"), with nsig solved by running the full player JS in a WebView (`NsigSolver.kt`, `assets/nsig_solver.js`). Row 19 shows the same token does **not** unlock MWEB video/audio for a kids video from a residential IP; whether dub audio still streams past 1 MB on Android today is worth checking before anyone builds on this sidecar.
- **NewPipeExtractor `dev`** ([`YoutubeStreamExtractor.java`](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java)): streaming data now comes **only** from `fetchVisionOsClient`; `ClientsConstants.VISIONOS_CLIENT_VERSION = "1.04"`; a non-OK playability status throws `ContentNotAvailableException` (no kids fallback upstream either).
- **youtubei.js 18.0.0** (2026-08-13, MIT): `SUPPORTED_CLIENTS` includes `VISIONOS`; `Session` options `po_token`, `visitor_data`, `fetch`, `client_type`; parser exposes `hls_manifest_url` and `server_abr_streaming_url`; sig/n deciphering via pluggable `Platform.shim.eval`.
- **bgutils-js 4.0.3** (2026-08-04, MIT): requires a browser-like runtime; token content-binding = visitorData (session) or videoId; integrity token TTL observed 43 200 s.
- **SmartTubeIOS** ([GPL-3.0](https://github.com/milika/SmartTubeIOS), last push 2026-08-15) — `BotGuardWebViewRunner.swift`: JavaScriptCore-produced BotGuard snapshots get `integrityToken = null`; the websafe fallback token "is accepted by the YouTube InnerTube API but **rejected by the CDN** for `rqh=1` adaptive streams (HTTP 403)". Its WKWebView runs at a youtube.com origin via `robots.txt` so CORS to `jnn-pa.googleapis.com` works; `prepare` takes 3–8 s, minting < 5 ms after that.

---

## 6. Architecture

### 6.1 Components

```
SwiftUI app (iOS 18+, iPhone + iPad)
├── CatalogClient          → backend /api/v1/* (home, content, categories, search, channel/playlist/video details, HEAD gates, reports, index/streams)
├── AccountClient (v1.1)   → Firebase Auth (email, Google, Sign in with Apple) + /api/account/* (profile, me, sync, import)
├── BrowseClient           → InnerTube `browse` on the phone: channel tabs + playlist items + continuations (§6.7); Atom feeds for the Me tab
├── StreamResolver (actor) → InnerTube `player` POST per strategy; client contexts from RemoteConfig
│     ├── SessionStore     → visitorData per client family (UserDefaults; Keychain survives uninstall — not wanted), rotation rules §6.3
│     ├── ManifestCache    → memory-only, TTL = min(config, expires − duration − 600 s, 1 h), flushed on NWPathMonitor change
│     └── strategies       → visionosHLS | androidItag18 | embed  (Owner directive 2026-08-27: no openInYouTube rung)
├── Player                 → AVPlayerViewController + custom toolbar; PiP, AirPlay, Now Playing, background audio, captions overlay (§6.5)
├── EmbedPlayer            → WKWebView + bundled IFrame-API HTML wrapper, navigation-locked (§6.4 row 3, §6.10)
├── LocalStore             → SwiftData: favorites, subscriptions, saved playlists (sync columns as Android's Room schema), search history (10), settings
└── RemoteConfig           → data-only JSON on raw.githubusercontent (same pattern as releases-meta.json); bundled default; last-known-good
```

### 6.2 One play, step by step

1. User taps a video → `ManifestCache` hit (same network path, not expired)? play it.
2. `StreamResolver` walks `resolverOrder`. For `visionosHLS`: `POST youtubei/v1/player` with the VISIONOS context + `context.client.visitorData` + `X-Goog-Visitor-Id`. One in-flight task per videoId; superseded requests are cancelled.
3. `playabilityStatus.status == OK` and `streamingData.hlsManifestUrl` present → `AVPlayer(url:)` (VOD or live). Audio-only mode uses the itag 140 URL from the same response.
4. Branch on `status` **and** `reason`:
   - `LOGIN_REQUIRED` + bot-check reason → **if no visitor is held yet, adopt the `responseContext.visitorData` the bot-check response itself carries and retry the same rung** (session bootstrap — no rotation consumed); if a visitor was already held, rotate it (at most once per 10 min) and retry once; further failures → exponential back-off, next rung. *(Corrected 2026-08-24 from live proof: the FIRST tokenless call is always bot-checked, so "rotate on bot-check" alone burns the rotation budget on a token never held and can never reach OK — the ladder silently demotes to ANDROID 360p. See `.superpowers/sdd/2026-08-23-ios-phase2-innertubekit/live-fix-report.md`.)*
   - `LOGIN_REQUIRED` / `AGE_CHECK_REQUIRED` + age reason → terminal "not available" state directly (the embed is also age-gated). Never rotate the session for an age gate. *(Owner directive 2026-08-27: no "Open in YouTube" hand-off — this was previously `openInYouTube`.)*
   - `UNPLAYABLE` (kids) → `androidItag18` (`streamingData.formats[itag 18].url`, progressive MP4) → `embed` → terminal "not available". Once the catalog carries `madeForKids` / `embeddable` (§8), skip rungs that are known to fail.
   - `LIVE_STREAM_OFFLINE` → "scheduled" state with the start time; no rung change.
5. Mid-play recovery: `AVPlayerItem.status == .failed` before the first frame → next rung. A 403 in `errorLog()`, `AVPlayerItemFailedToPlayToEndTime`, or a stall > 8 s → re-resolve the **same** rung once, `replaceCurrentItem(with:)` + `seek(to: lastTime)`; only then step down. On `willEnterForeground`, re-resolve pre-emptively if past `resolvedAt + expires − margin`. Never swap a playing native stream into the embed silently (it restarts with YouTube chrome and possibly an ad) — show the state change (§6.6).
6. Failures are counted locally for the developer screen (§7); nothing is uploaded in v1 (no backend sink exists; §6.13).

Per-play network cost from the phone: 1 InnerTube POST + media segments. Backend cost per play: 0.

### 6.3 Session hygiene (what turned 0/40 into playable)

- Take `responseContext.visitorData` from the first response that CARRIES one — which is normally the initial `LOGIN_REQUIRED` bot check, NOT a `playabilityStatus OK` success (the establishing call is bot-checked by design; its response still includes a usable `visitorData`); store it; send it on every call as both `context.client.visitorData` and the `X-Goog-Visitor-Id` header. *(Corrected 2026-08-24: the old "first successful response" wording contradicted this file's own probe.py protocol note and was the direct source of a live HLS-rung failure.)*
- Keep the client context byte-identical across calls (UA, versions). One `visitorData` per client family (VISIONOS for `player`, WEB for `browse`); never mix contexts under one visitor.
- Dedicated `URLSession(configuration: .ephemeral)` with `httpCookieAcceptPolicy = .never`, `httpShouldSetCookies = false` (InnerTube sets `VISITOR_INFO1_LIVE`/`YSC`; a cookie jar would replay the old visitor next to a rotated one), fixed `httpAdditionalHeaders` per client, `timeoutIntervalForRequest = 15`, `waitsForConnectivity = false`.
- Resolve on tap, not on scroll. Shorts: resolve the current item only (Android disables swipe-to-next deliberately, §6.8). ≥ 500 ms spacing between `player` POSTs; 300 ms settle debounce; a persisted cooldown on HTTP 429 / repeated bot checks (Android: `CooldownState.kt`, 1 h → 24 h).

### 6.4 Fallback ladder (remote-config ordered)

| Order | Strategy | Quality | Needs token | Status |
|---|---|---|---|---|
| 1 | `visionosHLS` — VOD and live | up to 2160p, AVPlayer ABR | no | verified |
| 2 | `androidItag18` — VOD only (live has no `formats[]`) | 360p muxed progressive, labelled "Standard quality" | no (selective enforcement) | verified on 18/18 kids videos |
| 3 | `embed` — bundled HTML wrapper around the official IFrame API (`youtube-nocookie.com`, `playsinline=1`, `rel=0`, `enablejsapi=1` + `origin` equal to the `baseURL` origin, `hl=<app locale>`), loaded with `loadHTMLString(_:baseURL:)` so a Referer is sent; `allowsInlineMediaPlayback = true`, `mediaTypesRequiringUserActionForPlayback = []`, `allowsPictureInPictureMediaPlayback`, ≥ 200×200, no native overlays, no pre-tap autoplay; `decidePolicyFor` cancels every main-frame navigation off the bundled page and `createWebViewWith` returns nil (§6.10); one `WKScriptMessageHandler` (weak proxy, removed on teardown) bridging `onReady/onStateChange/onError`; `webViewWebContentProcessDidTerminate` → reload once then terminal "not available" state; paused on `didEnterBackground`; AVPlayer torn down before the embed loads | YouTube's player: YouTube's ads; `rel=0` only narrows related videos to the same channel; no background audio (III.I.9) | no | the only YouTube-authorized path; IFrame errors 100 (deleted/private), 101/150 (uploader disabled embedding), 153 (no Referer — a wrapper bug), 2/5 (retry once) |

*(Owner directive 2026-08-27: rung 4 — `openInYouTube`, a confirmation sheet then `UIApplication.open(youtube://watch?v=)` with `https://youtu.be/` fallback — is removed. The ladder ends at rung 3 (embed); anything unplayable past that is a terminal "not available" state. No redirect or hand-off to YouTube, in any form, regardless of Safe Mode.)*

`iosPotSyntheticHLS` (IOS client + web token → byte-range HLS) is **removed**: web tokens are not valid for the IOS client (§3). The embed guarantees App Review never meets a dead player, and `resolverOrder: ["embed"]` is the configuration under which the app uses YouTube only as its Terms allow. It does not make the rungs above it authorized — never present it that way in a 5.2.2 inquiry (§9).

### 6.5 Player implementation (the hidden bulk)

- **Base**: `AVPlayerViewController` hosted in SwiftUI (stock transport, scrubber, skip ±10 s, speed menu, subtitle menu, PiP button, AirPlay route picker, RTL and VoiceOver for free) with a custom toolbar: quality ceiling, captions, audio language, audio-only, share, favorite, report. Android's double-tap seek/zoom gesture layer is dropped in v1 (AVPlayerViewController has its own). Everything `@MainActor`; `AVPlayer`/`AVPlayerItem` are not `Sendable`.
- **Background audio**: `AVAudioSession` category `.playback`, `UIBackgroundModes: audio`, `player.audiovisualBackgroundPlaybackPolicy = .continuesIfPossible`. With the `audio_only` setting on (or when backgrounded on cellular) swap to the itag 140 URL at `currentTime` so the phone stops downloading video. Handle `interruptionNotification` (`.shouldResume`) and `routeChangeNotification` (`.oldDeviceUnavailable` → pause).
- **PiP**: `allowsPictureInPicturePlayback`, `canStartPictureInPictureAutomaticallyFromInline`; only ever user-initiated (App Review rejects programmatic PiP); never detach the player while PiP is active.
- **Now Playing / lock screen**: `MPNowPlayingInfoCenter` (title, channel, artwork, duration, elapsed) + `MPRemoteCommandCenter` (play/pause/skip/`changePlaybackPosition`).
- **Quality**: HLS on AVPlayer gives *ceilings*, not a picker — Android's manual pick is also a cap (`QualityTrackSelector.kt`). UI: Auto / ≤1080p / ≤720p / ≤480p / Data saver → `preferredMaximumResolution` + `preferredPeakBitRate`; default the resolution cap to the layer's pixel size so a phone never pulls 2160p; `preferredMaximumResolutionForExpensiveNetworks` (720p) and `preferredPeakBitRateForExpensiveNetworks` on cellular; honour Low Data Mode via `NWPath.isConstrained`. Rung 2 has no quality control.
- **Captions**: manual tracks appear in the stock subtitle menu (HLS `SUBTITLES` renditions, verified). Auto-generated tracks (most of the catalog, `kind=asr`) need `CaptionsProvider`: fetch `captionTracks[].baseUrl&fmt=vtt`, render cues in an overlay driven by `addPeriodicTimeObserver`, label "(Auto-generated)", auto-enable when `UIAccessibility.isClosedCaptioningEnabled`. v1.1 item; on the embed pass `cc_lang_pref`/`cc_load_policy`.
- **Audio languages (dubs)**: `asset.mediaSelectionGroup(forMediaCharacteristic: .audible)` — verify a multi-dub catalog video lists `LANGUAGE=` renditions (§10 item 3); Android's MWEB dub path does not port.
- **Live**: VISIONOS live HLS plays natively (§4 row 14); "LIVE" badge, seek disabled unless the manifest has a DVR window; re-resolve before `expire`.
- **AirPlay**: `allowsExternalPlayback` defaults to true and the Apple TV fetches the IP-bound URL itself. Test on IPv6 Wi-Fi and with the phone on cellular (§10 item 3); if it 403s, set `allowsExternalPlayback = false` (audio still routes; mirroring still works).
- **Resume / history**: Android keeps neither persistently; iOS matches (session-only resume across re-resolution). Up Next = the playlist the video was opened from; Android has no recommendation source either.
- **Not ported**: Chromecast (Google Cast SDK; AirPlay instead), downloads, playback speed and sleep timer do not exist on Android (speed comes free from the stock menu).

### 6.6 What the user sees (player states per rung)

| State | UI | Never |
|---|---|---|
| Resolving | thumbnail + spinner + "Loading…" (no "resolving" jargon); 8 s budget before demotion; `NWPathMonitor` offline gate before step 1 so offline is one state, not four cascading timeouts | — |
| Rung 1 playing | stock chrome + toolbar | — |
| Rung 2 playing | persistent pill **"Standard quality (360p)"**; quality button hidden; `currentTime` carried over when demoted mid-play | "HD", silent downgrade |
| Rung 3 (embed) | caption **above** the frame, "Playing in YouTube's player" (RMF forbids overlays on the player); all FitrahTube controls hidden (quality, audio-only, PiP, background); end screen covered by a FitrahTube "Replay / Back" card on ENDED | "ad-free" anywhere in-app; any kids-vs-lecture explanation (say what is playing, never why) |
| Embed errors | 100 → "This video was removed"; 101/150 → "This video isn't available" (never "only on YouTube", never an Open in YouTube action); 2/5/153 → retry once, log | — |
| Rung 4 | removed — Owner directive 2026-08-27: no redirect or hand-off to YouTube, ever; anything past the embed rung is a terminal "not available" state | an automatic hand-off; any "Open in YouTube" affordance |
| Transitions | `AccessibilityNotification.Announcement` ("Playing in standard quality", "Playing in YouTube's player"); cross-dissolve, static under Reduce Motion | — |

### 6.7 Channel and playlist pages (what Android does with NewPipe)

Android's channel page (Videos / Live / Shorts / Playlists / About) and playlist page come **directly from NewPipeExtractor on the device**, not the backend (`ChannelDetailRepository.kt`: "This screen does not use backend API calls"); the backend only serves approved metadata (`/api/v1/channels/{id}` = channel + approved playlists), the exclusion list, and a HEAD availability gate. Extracted items are pushed back to `POST /api/v1/index/streams` so the backend's search index grows. iOS does the same:

- `BrowseClient`: InnerTube `browse` with the WEB client context and its own `visitorData`; uploads via the `VLUU…` uploads playlist (stable continuation, the trick Android uses because channel-tab continuations are unreliable past 1–2 pages), tabs via channel `params` for Live / Shorts / Playlists, playlist items via `VL<playlistId>`; apply the backend's exclusions; push items to `/api/v1/index/streams` like Android.
- Degraded mode when `browse` is bot-checked: the backend's approved playlists + the channel's public Atom feed (`https://www.youtube.com/feeds/videos.xml?channel_id=` — authorized RSS, 15 newest uploads, no bot check) + indexed search results. The Me tab's "new from subscribed channels" feed is built on these Atom feeds on Android already (`AtomChannelFeedFetcher.kt`) and ports unchanged.
- Alternative considered: backend endpoints wrapping NewPipeExtractor (`/api/v1/channels/{id}/videos?cursor=`, cached 1 h) — one day of work, but it concentrates bot-check exposure on a datacenter IP (the backend's validation scheduler already carries a YouTube rate-limit circuit breaker) and contradicts the load goal. Keep as the fallback if on-device `browse` proves fragile.

### 6.8 Shorts

Android: a single 9:16 player with swipe-to-next **disabled** on purpose ("to NOT contribute to doom-scrolling", `ShortsPlayerFragment.kt`), repeat-one loop, share / audio / captions / quality / report. iOS: the same — one item, the same ladder, loop, no pager. On the embed rung size the `WKWebView` 9:16 and keep the navigation lock; Shorts from kids channels follow the same Safe Mode rules.

### 6.9 Accounts, Me tab, sync, import (v1.1)

Android forces sign-in (every signed-out route ends at `SignInFragment`; providers: Firebase email/password + Google; Microsoft wired but hidden), collects display name, **date of birth, phone number** and password at bootstrap, and deletes under-13 accounts with a terminal screen (`MIN_AGE = 13`, `AccountProfileService.java:30`). iOS must differ in four places, each an App Store rule (§9):

- **Guest mode is mandatory** (5.1.1(v): "If your app doesn't include significant account-based features, let people use it without a login"). Browse, search, play, local favorites, report and share work signed-out. Sign-in unlocks the Me tab (personal feed, synced favorites / subscriptions / saved playlists via `/api/account/sync`), submissions and suggestions (moderator/admin role), and Import from YouTube.
- **Sign in with Apple** next to Google (4.8), through Firebase Auth's `OAuthProvider("apple.com")`. Email/password stays.
- **Age gate**: ask date of birth only at account creation; under 13 → no account, **continue as guest** (5.1.4(a): "must include some useful functionality or entertainment value regardless of a person's age"). Phone number optional or dropped (5.1.1: no personal information "except when directly relevant to the core functionality").
- **In-app account deletion** (5.1.1(v)) — needs a new backend `DELETE /api/account` (reuse the soft-delete + Firebase disable path the age gate already uses); Android only deletes through the age-ineligible flow.
- **Import from YouTube**: Google OAuth `youtube.readonly` via the Google Sign-In iOS SDK (or `ASWebAuthenticationSession` + AppAuth) → the same three Data API calls Android makes (`subscriptions`, `playlists`, `videos?myRating=like`) → `POST /api/account/import/resolve`. The token stays on the device and must be revocable in-app (5.1.1(v)); the Google Cloud project needs an iOS OAuth client ID.
- `X-Device-Id`: random UUID in UserDefaults, header on every backend call (backend rejects requests without it); Firebase ID token only for `app.fitrahtube.com` hosts, single 401 retry — mirror `FirebaseAuthInterceptor.kt` and `AccountStatusInterceptor.kt` (403 `ACCOUNT_BLOCKED` / `ACCOUNT_DELETED` → sign out + terminal dialog).

### 6.10 Safe Mode and kids safeguards

Android's `safe_mode` switch is written by Settings and **read by nothing** (grep); iOS implements it for real, default **on**, named "Safe Mode" (never "parental gate" — 2.3.8 wording):

- The embed is navigation-locked always (§6.4 row 3): taps on the title, logo, "Watch on YouTube", share, and end-screen cards never navigate in-app; `onStateChange` checks `getVideoData().video_id == expected`, else `stopVideo()`; the end screen is covered on ENDED.
- Safe Mode additionally disables playlist auto-advance (the PRD's "no autoplay to next video" persona promise; Android auto-advances playlists on `STATE_ENDED`). *(Owner directive 2026-08-27: the `openInYouTube` rung this bullet used to remove no longer exists at all — there is no YouTube hand-off in or out of Safe Mode. Safe Mode is now just: auto-advance off + the embed navigation lock above, full stop.)*
- Test under a Screen Time child account with Web Content = "Only Approved Websites" and specify the blocked-load state (§10 item 8). These rules are also what justifies answering "Unrestricted Web Access: No" in the age-rating questionnaire (§9).

### 6.11 iPad, RTL, accessibility

- **iPad**: `TabView` + `.tabViewStyle(.sidebarAdaptable)` as the rail (Android `NavigationRailView` at sw600dp/sw720dp; this is the iOS 18 floor); `LazyVGrid(columns: [GridItem(.adaptive(minimum: scaledMin))])` with `@ScaledMetric` 180 pt regular / 160 pt compact (Android `grid_item_min_width` 180/200 dp); branch on `horizontalSizeClass`, never `userInterfaceIdiom`, so Split View / Stage Manager narrow windows get the phone layout; pagination = `.onAppear` on the last row **plus** `onScrollGeometryChange` "content fits and `hasMore` → `loadMore()`" with an in-flight guard (the CLAUDE.md rule). Player 16:9 top-anchored up to a max width; compact-height landscape hides metadata. Pointer/keyboard come with AVPlayerViewController.
- **RTL (Arabic)**: AVPlayerViewController's chrome is the reference (scrubber mirrors, `play.fill` does not, skip symbols localize); never force `.leftToRight` on transport. Titles leading-aligned, `lineLimit(2)`; descriptions in a `UITextView` with `.natural` alignment (a paragraph follows its own language), links restricted to http/https as in `PlayerDescriptions.kt`; wrap arguments of composite strings in U+2068/U+2069; numerals only via `formatted()` / `Duration.formatted(.time(...))` (ar_MA → Western digits, ar_EG → Eastern, as Android's ICU does); SF Arabic + Dynamic Type, no bundled font; icons `chevron.forward` / `arrow.backward`, never `.left/.right`; embed gets `hl=`. Per-app language through iOS Settings (`UIApplication.openSettingsURLString`) replaces Android's in-app picker; `CFBundleLocalizations` = en, ar, nl.
- **Accessibility**: brand green fails AA on white (`#35C491` 2.22:1) — use Android's two tokens (light `#275E4B`, dark `#35C491`) as a dynamic color set for text and tint; Shorts overlay text on a scrim; ≥ 44 pt targets; grid min width scaled, single column at `.accessibility1+`, no `minimumScaleFactor`; every rung transition announced; custom buttons carry label + value ("Quality, Auto"); Reduce Motion → static skeletons. Do not claim Audio Descriptions in App Store accessibility labels (YouTube provides none).

### 6.12 PoToken minter — built on a branch, not shipped

The harness in Appendix A.2/A.3 (bgutils-js in a `WKWebView` at a youtube.com origin via `robots.txt`) mints a **web** token with the integrity token present, TTL 12 h, a few seconds to prepare, milliseconds per mint. It is useful for exactly one future: VISIONOS (a web-family client for token purposes) starting to demand a GVS token on its HLS. Port it now (1–2 days), keep it **CI-tested on a private branch, not in the shipped binary** — so enforcement day is a submission day, without shipping a dormant subsystem that Apple 2.3.1 ("hidden, dormant, or undocumented features") invites questions about. When it ships, the JavaScript is bundled (Android does the same — `assets/po_token.html` via `loadDataWithBaseURL`) and `resolverOrder: ["androidItag18", "embed"]` bridges the review window (§11).

### 6.13 Remote config (served like `releases-meta.json`)

```json
{
  "schemaVersion": 1,
  "minAppVersion": "1.0.0",
  "resolverOrder": ["visionosHLS", "androidItag18"],
  "manifestCacheSeconds": 3600,
  "clients": {
    "visionos": {
      "clientName": "VISIONOS", "clientVersion": "1.02", "clientNameId": 101,
      "deviceMake": "Apple", "deviceModel": "RealityDevice17,1",
      "osName": "visionOS", "osVersion": "26.5.23O471",
      "userAgent": "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
    },
    "android": {
      "clientName": "ANDROID", "clientVersion": "21.26.364", "clientNameId": 3,
      "androidSdkVersion": 30, "osName": "Android", "osVersion": "11",
      "userAgent": "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
    },
    "web": { "clientName": "WEB", "clientVersion": "2.20250925.01.00", "clientNameId": 1 }
  }
}
```

*(Owner directive 2026-08-27: `openInYouTube` is no longer a valid `resolverOrder` entry. `RemoteConfig.sanitize` drops any `openInYouTube` entry it finds, so a published config can never re-enable it.)*

*(Ship-dark decision (controller 2026-08-27): `embed` is removed from the bundled `resolverOrder` above (default ladder = `visionosHLS → androidItag18 → terminal`); `RemoteConfig.sanitize` still accepts `embed`, so a published config can enable it as disaster recovery.)*

Every field above is something YouTube has changed in the last 12 months; keeping them as data is the difference between a JSON edit and a 1–3 day App Review cycle. Rules: data only (strings and orderings consumed by bundled code — nothing is fetched and executed); unknown strategy names are dropped; a bundled default and the last-known-good copy cover unreachable or malformed config; body capped at 64 KiB; fetched on launch and `willEnterForeground` with ≥ 15 min spacing (raw.githubusercontent serves `max-age=300`). Not signed in v1: the config contains no code, the damage model is availability only, and TLS + repository ACLs cover it; add an Ed25519 signature if the repo gains more writers. 2.3.1 line: parameter tweaks (versions, UA, order among shipped strategies) are config; a new strategy or client family is an App Store submission.

Telemetry: Android uploads nothing except download analytics (which iOS has no use for); `PlaybackAnalyticsEvent` stays on the device. iOS v1 matches — no upload. The runbook's signal (§11) is a scheduled run of Appendix A.1 against 10 catalog IDs from a **residential** line (a Mac at home, or a self-hosted runner — never a GitHub-hosted runner, whose datacenter IPs are bot-flagged first), which alerts a human; config is never flipped automatically. Crashes and hangs come from Xcode Organizer / MetricKit. A `POST /api/v1/playback-events` sink is a later option (it needs `X-Device-Id`, which is an identifier for the privacy label).

### 6.14 No over-the-air code

Apple DPLA §3.3.1(B), current text (verified from the agreement page, 2026-08-22): "Except as set forth in the next paragraph, an Application may not download or install executable code. Interpreted code may be downloaded to an Application but only so long as such code: (a) does not change the primary purpose of the Application by providing features or functionality that are inconsistent with the intended and advertised purpose of the Application (b) does not bypass signing, sandbox, or other security features of the OS; and (c) for Applications distributed on the App Store, does not create a store or storefront for other Applications." The stricter rule is App Store Guideline 2.5.2 (§9): apps "may not download, install, or execute code which introduces or changes features or functionality of the app". Downloading a BotGuard/extraction bundle is a feature change by any reading, and a SHA-256 pin whose expected value lives in the same mutable config document as the bundle URL protects nothing. Policy: every line of JavaScript the app runs is in the bundle and went through review; remote config carries no URLs to code. The only remote JavaScript the app executes is YouTube's own player inside the embed `WKWebView` and the BotGuard interpreter inside the (branch-only) minter's WebView.

### 6.15 Explicitly not doing

- Porting NewPipeExtractor (JVM-only; no J2ObjC/KMP path) or forking SmartTubeIOS (GPL-3.0, one maintainer, 80 open issues; its working path harvests HLS URLs from the watch page in a WKWebView and proxies HLS through `AVAssetResourceLoader`).
- Server-side stream resolution or a media proxy. (The backend's existing token sidecar is not a stream resolver; it also did not help, §4 row 19.)
- The IOS client in any form (web tokens are invalid for it), `iosPotSyntheticHLS`, and the MWEB + nsig + sidecar path (tested, capped).
- Downloads / offline (Apple 5.2.3), in-app update (App Store), Chromecast (AirPlay instead).
- Embedding youtubei.js in v1. It becomes the upgrade path only if a future client needs JS-player sig/n deciphering; it drops into a WKWebView with a native `fetch` bridge — bundled, shipped through review.
- Over-the-air JavaScript of any kind; a dormant minter in the shipped binary.
- Creator-licensed self-hosting of kids content (proposed in review; decided against on 2026-08-22 — the app sources from YouTube only, as Android does).

---

## 7. Feature parity — Android → iOS

Source of truth: the Android nav graphs (`app_nav_graph.xml`, `main_tabs_nav.xml`), `SettingsPreferences.kt`, `PlayerFragment.kt`, and the Retrofit interfaces under `data/`. "v1" = first App Store build (guest), "v1.1" = accounts release.

| Android feature | iOS | Release |
|---|---|---|
| Splash → onboarding → main routing (`SplashRouter.kt`) | Same routing minus the forced sign-in: onboarding → main (guest) | v1 |
| Onboarding pages | Two pages (Browse, Language); no "download"/"background" promises (§9) | v1 |
| Home (category carousels, `GET /api/v1/home`, infinite sections, "See all") | `ScrollView` + horizontal `LazyHStack` rows; Featured list = "See all" | v1 |
| Videos / Channels / Playlists tabs (cursor-paged `GET /api/v1/content`, filters: category, length, date, sort) | Same API, `LazyVGrid`, filter sheet; iPad auto-load rule (§6.11) | v1 |
| Categories / subcategories (`GET /api/v1/categories`) | Same | v1 |
| Search (backend catalog `GET /api/v1/search`, local history 10 items, delete/clear) | Same; history in UserDefaults | v1 |
| Channel detail (Videos / Live / Shorts / Playlists / About from NewPipe; backend HEAD gate; exclusions) | `BrowseClient` (§6.7) + HEAD gate + exclusions; degraded mode via Atom + approved playlists | v1 |
| Playlist detail (NewPipe; backend HEAD gate) | `BrowseClient` `VL<playlistId>` | v1 |
| Player: quality (auto + manual cap), CC (incl. auto-generated), audio language, PiP, fullscreen, gestures, audio-only toggle, background play, media session, Up Next (playlist), share, favorite, report, download, Cast | §6.5: quality ceilings, CC (manual v1 / auto-generated overlay v1.1), audio language via media selection, PiP, fullscreen, audio-only (itag 140), background audio, Now Playing, Up Next (playlist), share, favorite, report, AirPlay. **Cut**: download, Chromecast, gesture layer | v1 (captions overlay v1.1) |
| Live streams (LIVE badge, disabled seek, VOD transition) | VISIONOS live HLS (§4 row 14) | v1 |
| Shorts player (single item, no swipe-to-next, loop, share/report/CC/quality/audio) | Same (§6.8) | v1 |
| Favorites (Room, per-user rows, sync) | SwiftData, local in v1; synced via `/api/account/{favorites,subscriptions,playlists}` + `GET /api/account/sync` cursor in v1.1 | v1 / v1.1 |
| Downloads screen, download quality / Wi-Fi-only / storage settings, download notifications, `api/downloads/*` | **Not ported** (Apple 5.2.3). The Downloads screen's links to Favorites / history move to the Me tab | — |
| Report content (11 reasons, free text, parent context, `POST /api/v1/reports`, 429 handling) | Same sheet, same endpoint, works signed-out | v1 |
| Share (`https://app.fitrahtube.com/api/{watch\|channel\|playlist}/{id}`, OG metadata publish) | `ShareLink` with the same URLs; metadata publish only when signed in (as Android); share text without "ad-free" (§9) | v1 |
| Deep links `albunyaantube://{video,channel,playlist,shorts}/{id}`; App Links for `app.fitrahtube.com/{watch,channel,playlist,api/…}` | Custom URL scheme + **Universal Links** (`applinks:app.fitrahtube.com`) — needs the AASA file the host currently 403s (§8) | v1 |
| Sign-in (email/password, Google; hidden Microsoft), email verification, profile bootstrap (name, DOB, phone, password), age gate 13+ | Firebase email/password + Google + **Sign in with Apple**; verification; bootstrap with DOB, phone optional; under-13 → guest (§6.9) | v1.1 |
| Me tab (week-bucketed feed from subscribed channels via Atom, favorites chips, Content / Pending tabs) | Same, Atom feeds port unchanged | v1.1 |
| Profile view/edit (name, DOB, email, phone, password) + **account deletion** | Same + "Delete account" (new backend endpoint, §8) | v1.1 |
| My Submissions (`/api/admin/approvals/my-submissions`, edit note, withdraw) and Suggest Content (`/api/admin/youtube/search` proxy, URL paste) — moderator/admin roles | Same, role-gated | v1.1 |
| Import from YouTube (Google OAuth `youtube.readonly`, 3 Data API calls, `POST /api/account/import/resolve`) | Same with the iOS OAuth client (§6.9) | v1.1 |
| Settings: language (system/en/ar/nl), theme (system/light/dark), audio only, background play, safe mode | Language → iOS per-app language (Settings deep link); theme via `preferredColorScheme`; audio only; background play; **Safe Mode (real, §6.10)** | v1 |
| Settings: sign out, downloads library, storage, clear downloads, check for updates, available versions | Sign out (v1.1); the rest not ported (App Store handles updates; `minAppVersion` in remote config shows an "update required" screen linking to the App Store) | v1.1 / — |
| About (version, 7-tap developer dialog, links to privacy/terms/licenses/GitHub) | Same links; developer dialog shows local resolver counters and cooldown state | v1 |
| In-app update (GitHub releases, APK install) | **Not ported** | — |
| Telemetry (local logs; download analytics upload) | Local only; no upload | v1 |
| Locales en / ar / nl, RTL; phone / sw600dp / sw720dp layouts | en / ar / nl, RTL; compact / regular size classes (§6.11) | v1 |
| Notifications (download progress, media playback) | Media playback only (Now Playing); no push | v1 |
| `POST /api/v1/index/streams` (client-side indexing of extracted items) | Same, from `BrowseClient` | v1 |

---

## 8. Backend and infrastructure work for iOS

| Item | Why | Size |
|---|---|---|
| Backend (Spring, which serves `app.fitrahtube.com`) must serve `/.well-known/apple-app-site-association` and `/.well-known/assetlinks.json` as `application/json` with no redirect. No handler exists in `backend/src/main` today and both paths return **403** through Cloudflare (`server: cloudflare`, `cf-cache-status: DYNAMIC`), so whatever answers 403 for unknown paths (security config, the `X-Device-Id` check, or a Cloudflare rule) must exempt `/.well-known/*` | Universal Links for the share URLs; Android App Links (`autoVerify`) need the same `assetlinks.json` | small |
| `DELETE /api/account` (self-service soft-delete + Firebase disable, reuse the age-gate path) | 5.1.1(v) | small |
| Daily `VideoValidationScheduler`: `videos.list part=status,contentDetails,snippet` (50 IDs per quota unit) → store `madeForKids`, `embeddable`, `contentRating.ytRating`, `regionRestriction`, `liveBroadcastContent`; expose in `/api/v1/content` and `/videos/{id}`; reject `ytAgeRestricted` at curation | Deterministic routing (no doomed POSTs), confirms the kids attribution, skips the embed where `embeddable=false`. The PRD's "never fall back to the official YouTube API" was about sourcing content; the admin dashboard already uses the Data API (`youtubeService.ts`). Amend the PRD note. Alternative without the Data API: persist `visionosPlayable=false` from a server-side VISIONOS probe — bot-check exposure on a datacenter IP, so the Data API is preferred | ~20–40 lines |
| Swift client generation from `docs/architecture/api-specification.yaml` (extend `scripts/generate-openapi-dtos.sh` with `swift-openapi-generator`) | One source of truth for DTOs, as for TS/Kotlin | small |
| `<meta name="apple-itunes-app" content="app-id=…">` on `WatchPageController` pages | Smart App Banner on shared links once the App Store ID exists | trivial |
| Drop "ad-free" from `share_app_promo` (Android too) | §9 | trivial |
| Android: `safe_mode` is a no-op — implement §6.10 there or remove the switch | Parity in the other direction | Android |

---

## 9. App Store compliance

### Guideline text (verbatim, fetched 2026-08-22)

- **5.2.3 Audio/Video Downloading** — "Apps should not facilitate illegal file sharing or include the ability to save, convert, or download media from third-party sources (e.g. Apple Music, YouTube, SoundCloud, Vimeo, etc.) without explicit authorization from those sources. Streaming of audio/video content may also violate Terms of Use, so be sure to check before your app accesses those services. Authorization must be provided upon request."
- **5.2.2 Third-Party Sites/Services** — "If your app uses, accesses, monetizes access to, or displays content from a third-party service, ensure that you are specifically permitted to do so under the service's terms of use. Authorization must be provided upon request."
- **2.5.2** — "Apps should be self-contained in their bundles, and may not read or write data outside the designated container area, nor may they download, install, or execute code which introduces or changes features or functionality of the app, including other apps."
- **2.3.1(a)** — "Don't include any hidden, dormant, or undocumented features in your app; your app's functionality should be clear to end users and App Review. All new features, functionality, and product changes must be described with specificity in the Notes for Review section of App Store Connect (generic descriptions will be rejected) and accessible for review."
- **4.8 Login Services** — "Apps that use a third-party or social login service (such as Facebook Login, Google Sign-In, …) to set up or authenticate the user's primary account with the app must also offer as an equivalent option another login service" that "limits data collection to the user's name and email address", "allows users to keep their email address private", and "does not collect interactions with your app for advertising purposes without consent". Not required if "Your app exclusively uses your company's own account setup and sign-in systems."
- **5.1.1(v) Account Sign-In** — "If your app doesn't include significant account-based features, let people use it without a login. If your app supports account creation, you must also offer account deletion within the app. Apps may not require users to enter personal information to function, except when directly relevant to the core functionality of the app or required by law. … An app may not store credentials or tokens to social networks off of the device and may only use such credentials or tokens to directly connect to the social network from the app itself while the app is in use."
- **5.1.4 Kids** — "(a) … Apps may ask for birthdate and parental contact information only for the purpose of complying with these statutes, but must include some useful functionality or entertainment value regardless of a person's age. Apps intended primarily for kids should not include third-party analytics or third-party advertising." "(b) … Apps not in the Kids Category cannot include any terms in app name, subtitle, icon, screenshots or description that imply the main audience for the app is children."
- **1.2 User-Generated Content** — applies to "apps with user-generated content or social networking services": filtering, a report mechanism, blocking, published contact information. FitrahTube's suggestions are moderated before anything is published and reports exist; keep the contact information public.
- **4.2 Minimum Functionality** — "Your app should include features, content, and UI that elevate it beyond a repackaged website."
- **TestFlight** is not a review bypass: the first external build "gets sent to App Review to make sure it follows the App Review Guidelines"; only internal testing (≤ 100 App Store Connect users) skips review.

### What YouTube's Terms allow (verbatim, fetched 2026-08-22)

- Permitted: "You may also show YouTube videos through the embeddable YouTube player."
- Not permitted: "access, reproduce, download, distribute, transmit, broadcast, display, sell, license, alter, modify or otherwise use any part of the Service or any Content except: (a) as expressly authorized by the Service; or (b) with prior written permission from YouTube"; "circumvent, disable, fraudulently engage with, or otherwise interfere with any part of the Service (or attempt to do any of these things), including security-related features or features that (a) prevent or restrict the copying or other use of Content or (b) limit the use of the Service or Content"; "access the Service using any automated means (such as robots, botnets or scrapers)".
- API Services Developer Policies (bind the embed and any Data API use): III.I.14 — no "technology other than YouTube API Services to access or retrieve API Data, including to access any portion of any YouTube audiovisual content"; III.I.5 — do not "modify, interfere with, replace, or block advertisements placed or served by YouTube"; III.I.9 — no features "that play content … from a background player".

**Plain reading.** `visionosHLS`, `androidItag18` and on-device `browse` are outside what the Terms authorize — the position the Android app has been in since day one. The embed is the only authorized path, and the embed cannot meet the PRD: YouTube's ads play, `rel=0` only narrows related videos to the same channel, and background audio is forbidden for API clients. This plan takes that trade-off knowingly; the mitigation is that the compliant configuration is one remote-config edit away (`resolverOrder: ["embed"]`), not a rewrite.

| Path | Media fetched by the phone | Backend load | Meets PRD (no ads, no uncurated suggestions) | YouTube Terms | App Store exposure |
|---|---|---|---|---|---|
| VISIONOS HLS → AVPlayer (this plan) | yes | 0 | yes | not authorized | 5.2.2/5.2.3 if a reviewer asks; removal on a YouTube complaint |
| Official embed only | yes | 0 | no | authorized | lowest |

### App Store Connect and technical requirements

- **Age rating questionnaire** (new tiers 4+/9+/13+/16+/18+; required since 2026-01-31; social-media questions required for new apps from September 2026): Unrestricted Web Access = **No** (the `WKWebView` is navigation-locked to the bundled player, §6.10); Social media = No (no redistribution of user content); UGC = No (moderated submissions); Advertising = answer for the shipped ladder — with the embed rung enabled, ads served by YouTube's player can appear and must be declared; Parental controls = Safe Mode (an in-app control, not age assurance); Age assurance = the DOB gate at account creation only. Target 4+ with those answers; never "for kids" in metadata (5.1.4(b)).
- **Privacy manifest** (`PrivacyInfo.xcprivacy`, required since 2024-05-01): `NSPrivacyTracking = false`; required-reason APIs: UserDefaults (CA92.1) for session/config/settings, file timestamps (C617.1) only if the cache reads them; TTLs via `Date`/`ContinuousClock`, not `systemUptime`. Firebase Auth and the Google Sign-In SDK ship their own manifests and signatures.
- **App Privacy labels** — cannot be "Data Not Collected": Identifiers (`X-Device-Id` UUID, Firebase uid — linked to the user in v1.1), Contact Info (email, name, phone if kept — v1.1), User Content (report text, submissions — v1.1); no tracking; no third-party analytics. `visitorData` is a Google-issued identifier sent to YouTube on every play — disclose third-party data flow to YouTube.
- **Export compliance**: HTTPS only → `ITSAppUsesNonExemptEncryption = false` in Info.plist.
- **SDK**: since 2026-04-28 submissions must be built with Xcode 26 / iOS 26 SDK; deployment target iOS 18.
- **Networking**: IPv6-only support is mandatory (test on a NAT64 hotspot); no ATS exceptions (all hosts HTTPS); `UIBackgroundModes: audio` justified by real background audio.
- **EU distribution** (Dutch locale): DSA trader status required for EU availability.
- **Availability**: iPad apps are offered on Apple silicon Macs and Apple Vision Pro by default — opt out of both for v1 in App Store Connect (no `youtube://`, desktop UA in the embed, VISIONOS impersonation on a real Vision Pro is untested). tvOS has no `WKWebView`, so the compliance floor does not exist there; not in scope (Android has no TV build either).
- **Privacy policy and support URLs** set in App Store Connect and linked in-app (5.1.1(i)).

### Checklist

- [ ] No download/save/convert feature anywhere (5.2.3). The Android `DownloadModule` does not port.
- [ ] Free. No IAP, no FitrahTube ads, no donations inside the app ("monetizes access to" in 5.2.2). Never market the app as "ad-free" — the embed path shows YouTube's ads. Fix Android's `share_app_promo` ("Get FitrahTube for ad-free Islamic content!") at the same time.
- [ ] Guest mode works end to end; v1 ships without accounts; v1.1 adds Sign in with Apple, in-app deletion, under-13 guest continuation (§6.9).
- [ ] App name / subtitle / keywords / screenshots: no YouTube logos, no "ad-free", no "YouTube Premium features", no "for kids" / "for children" (5.1.4(b), 2.3.8). Screenshots show the native player only (the embed shows YouTube's logo, title and avatar), localized en / ar (RTL) / nl, captions like "Safe Mode", never "kids". Frame what is true: curated Islamic video library for families; editorial curation; distraction-free. Mentioning YouTube as the content source is not auto-fatal (Smart Tube BDP's live listing says "native YouTube client") — advertising ad-removal is what draws the complaint.
- [ ] Reviewer notes (2.3.1): curated library selected by an editorial team; content is the creators' public videos; works without an account; demo path that plays during review; the player is described truthfully — native playback with YouTube's embedded player as fallback, which shows YouTube's ads and branding — never the embed as *the* playback method. Embed fallback enabled so a blocked review IP still sees a working player.
- [ ] Age-rating questionnaire answered as above; Kids Category **not** claimed.
- [ ] Background audio entitlement kept; not advertised. Apple checks that the mode plays real audio; YouTube policy III.I.9 binds API clients, which the native path is not (it is off-Terms as a whole, see above).
- [ ] No third-party analytics SDK; no telemetry upload in v1; local counters only.
- [ ] `madeForKids` / `embeddable` / `ytRating` stored per video at validation time (§8).
- [ ] Privacy manifest, privacy labels, export compliance flag, Xcode 26 build, IPv6 test, privacy/support URLs (above); AASA served by the backend (§8).
- [ ] If delisted: iOS distribution ends outside the EU (notarized alternative distribution continues in the EU); TestFlight is not a fallback. The real plan B is `resolverOrder: ["embed"]`.

### Risk read

No number — whether a reviewer invokes 5.2.2/5.2.3 is discretionary and cannot be estimated. Evidence that reviewers do not always ask: Smart Tube BDP (v5.1, updated 6 July 2026, Entertainment, 4+, free) is live today describing itself as "a clean, native YouTube client", and Musi lived ~10 years. That is evidence, not precedent you can rely on. The durable exposure is removal-on-complaint: unappealable (March 2026 Musi ruling: Apple may delist "with or without cause"), lower for lecture/nasheed content than for label-owned music (Musi's complainants were labels), never zero, and it lasts for the life of the app. Yattee's removal (Jan 2026) was the developer pulling an outdated build — the maintenance cadence §6.13 exists to prevent that failure. Lifetime assumption for VISIONOS: yt-dlp's default client is the first one YouTube targets; android_vr went from working to fully 403'd in ~6 weeks. Plan for VISIONOS to need a token within months, not years — §6.12 and §11 are the standing response.

---

## 10. Day-1 verification checklist (before writing app code)

Run from a real iPhone on **cellular** and on **Wi-Fi** (IPv6-enabled), plus one run from a Mac on a VPN exit in the EU/US:

1. Appendix A.1 against 20 catalog IDs (mix of lecture, kids and one live stream): record VISIONOS OK-rate, HLS presence, 720p segment codes, kids failure rate. Expect ≈ §4 rows 3/10/14.
2. `browse` probe: WEB-context `browse` for three approved channels (`VLUU…` uploads playlist + Live/Shorts/Playlists tab params) and two playlists, with a reused `visitorData`, 3 continuation pages each. Bot-check rate decides whether §6.7's on-device path ships or the backend-proxy alternative does.
3. AVPlayer on the VISIONOS `hlsManifestUrl` on-device: PiP, background audio with `audiovisualBackgroundPlaybackPolicy`, lock-screen controls, seeking past the buffered range, the stock subtitle menu on a video with manual captions, `mediaSelectionGroup(.audible)` on a multi-dub video, **AirPlay on IPv6 Wi-Fi and with the phone on cellular** (§6.5), and a Wi-Fi → cellular switch mid-play (expect the re-resolve path, §6.2 step 5).
4. Measure time-to-first-frame for the full path (player POST → AVPlayer ready). Target < 2 s on LTE.
5. Check whether VISIONOS still returns HLS for the newest uploads in the catalog (HLS presence is per-video; it was 100 % of OK responses in testing).
6. Re-read yt-dlp `_base.py` for the VISIONOS block — any new `GVS_PO_TOKEN_POLICY` or version comment means the world changed.
7. **Size the kids gap properly** (key = the admin dashboard's `VITE_YOUTUBE_API_KEY`): `videos.list part=status,contentDetails` over (a) the 18 `UNPLAYABLE` IDs `WFBUqjDt_oA,er5F_E1_mp4,xuE4k3wQt9I,A47eqQ_nOk4,7GsLw3TUMDc,AElUZfDGqjk,wfs51fK4zdc,iycAN1zhJWY,4MFHx5rBHHM,uJN6ux23b9w,MmveKGjeVZE,XgQCBwgJ_4k,eBpPZYDQVyg,7t7HFC-XSgI,eFdTWrW6cyA,Aa1WCJhEUVY,Wa1ufZVuJa4,O5_tjyCmKOw` plus control `EnfgPg0Ey3I` (expect `madeForKids=true` for the 18), then (b) all 245 standalone videos and the 15 newest uploads (Atom) of each of the 22 kids-category channels — ~600 IDs, 12 quota units. Report the made-for-kids share by video and by channel; that number, not "45 %", is the size of the 360p/embed gap.
8. Embed wrapper on device: no error 153 (Referer via `baseURL`), 101/150 on a video with embedding disabled, 100 on a deleted one, inline playback, exactly one player alive at a time; tap the title / logo / end-screen cards and confirm nothing navigates in-app (this is the evidence for "Unrestricted Web Access: No"); run under a Screen Time child account with "Only Approved Websites". Take one network trace of a full play and confirm media flows only between the phone and `googlevideo.com`/`youtube.com` — nothing via the backend.
9. ANDROID itag 18 on a kids video inside AVPlayer (`AVURLAsset`, default UA) end to end — the chunked probe passed with `AppleCoreMedia`; confirm in the real player incl. seeking.
10. IPv6-only (NAT64) pass of items 1, 3 and 8.

---

## 11. Runbook — when something breaks

| Symptom | Likely cause | Action (remote config first, app release last) |
|---|---|---|
| `LOGIN_REQUIRED` spikes on `player` | session signals rejected | app already rotates `visitorData` (rate-limited); bump `clientVersion`/`osVersion`/UA to current from yt-dlp `_base.py` |
| HLS segments 403 after ~60 s on VISIONOS | poToken enforcement arrived (android_vr pattern) | same day: `resolverOrder: ["androidItag18", "embed"]` (Owner directive 2026-08-27: no `openInYouTube` rung to fall back to). Then merge the branch-built minter (§6.12), bundle it, use the placement yt-dlp lands on (`/pot/{token}` manifest path or `&pot=`), ship through App Review |
| `UNPLAYABLE` for non-kids videos | client version retired | change `clients.visionos.clientVersion` (watch NewPipeExtractor `ClientsConstants` and yt-dlp) |
| SABR-only responses (no `hlsManifestUrl`, `serverAbrStreamingUrl` present) | client moved to SABR | swap `resolverOrder` to the next client; if every JS-less client is gone, bundle youtubei.js in a WKWebView (sig/n deciphering) and ship through App Review |
| itag 18 403s (yt-dlp already lists `android` as token-required) | selective enforcement ended | kids videos → embed; kids ∧ `embeddable=false` → terminal "not available" (Owner directive 2026-08-27: no `openInYouTube` fallback). That set's size (§10 item 7) is the true unsolved gap |
| `browse` bot-checked | channel pages empty | degraded mode (Atom + approved playlists) is automatic; then the backend-proxy alternative (§6.7) |
| Everything fails | — | `resolverOrder: ["embed"]` keeps the app functional while you work (Owner directive 2026-08-27: no `openInYouTube` rung remains to append) |

Note: the embed rung ships in the binary but dormant by default (excluded from the bundled `resolverOrder`, §6.13) — every "add `embed`" action above is this same lever, a published `resolverOrder` change, not a release. A dormant-but-present subsystem is a known App Store 2.3.1 ("hidden, dormant, or undocumented features") talking point; the ship-dark rationale is §1.

---

## 12. Effort and release plan

| Phase | Scope | Notes |
|---|---|---|
| 0 | Day-1 checklist (§10) | 2 days; decides the `browse` path and sizes the kids gap |
| 1 — v1 (guest) | Catalog UI (Home, Videos/Channels/Playlists, Featured, Search, Categories), channel + playlist pages (`BrowseClient`), player with the ladder and states (§6.5–6.6), Shorts, local favorites, report, share + Universal Links, Settings (language, theme, audio-only, background, Safe Mode), onboarding, About; en/ar/nl, RTL, iPad, accessibility; remote config | The resolver is a few hundred lines; the player (§6.5) and `BrowseClient` (§6.7) are the bulk. Backend: §8 rows 1 and 3 (AASA, Data API fields). Roughly 8–12 weeks for one developer |
| 2 — v1.1 (accounts) | Sign in with Apple / Google / email, verification, profile + DOB gate + deletion, Me feed (Atom), sync, submissions + suggest, Import from YouTube; auto-generated captions overlay | 3–4 weeks + backend `DELETE /api/account` |
| 3 | Minter ported from A.2/A.3, CI-tested on a branch, not shipped (§6.12) | 1–2 days, done during phase 1 |
| 4 | App Store submission per §9 | metadata, questionnaire, privacy manifest/labels, reviewer notes; first external TestFlight build is already a Beta App Review |

---

## 13. Corrections and non-findings

- **"~45 % of the catalog" was a sampling artefact.** It was 18 of the 40 *newest standalone videos*; the catalog is 845 channels / 452 playlists / 245 videos and the kids category holds 22 channels (§2). The share of plays is unmeasured.
- **The IOS-client + poToken failure (§4 row 8) has a cause**: web-platform tokens are not valid for the IOS (or ANDROID) client (yt-dlp PO Token Guide). Every token this project can mint is a web token. The Android `WebViewPoTokenProvider.kt` comment claiming "web-context visitorData + token is accepted by the iOS client (verified: pot applied, HTTP 206, full ladder)" is contradicted by that guide and by the 1 MB cap measured here; Android telemetry should say which is true today.
- **The backend's token sidecar does not solve kids content** (§4 row 19), although it was a reasonable thing to try given `DubPotokenService`'s "proven on-device" claim for dub audio. `web_safari` returns no HLS for untrusted sessions; `tv` and `web_embedded` are bot-checked or unavailable (§4 rows 16–17).
- **An earlier draft of this document quoted a superseded DPLA clause as "verbatim"** (the pre-2017 "WebKit and JavaScriptCore … HTML5-based content" wording, returned by a fetch summarizer). The current §3.3.1(B) text is in §6.14; the no-OTA-code decision rests on Guideline 2.5.2 and stands.
- **Yattee is not on the App Store.** Removed January 2026 by its own developer because the last approved build was outdated and broken ([yattee #906](https://github.com/yattee/yattee/issues/906)); continues on TestFlight. Not an Apple/Google action.
- **Smart Tube BDP is live** (v5.1, 6 July 2026, Entertainment, 4+, free) with "native YouTube client" in its description — "never mention YouTube" was overstated.
- A single popular video is not a test. VISIONOS and IOS both "worked" on a control video and then 0/40 on the catalog until the session fix; always test on catalog IDs.
- The IOS client **does** return `hlsManifestUrl` for some videos (the control) and not for others; HLS presence is per-video, so never assume it.
- Could not confirm the made-for-kids flag without a Data API key; attribution rests on yt-dlp's documentation and the channels involved (§4 row 13).
- Grayjay has no iOS version and none planned ([FAQ](https://grayjay.app/faq.html)). "NPiP" does not exist; App Store "NewPipe" listings are impostors.

---

## 14. Android note

`YoutubeClientRotator.kt` rotates IOS → ANDROID per video; `WebViewPoTokenProvider.kt` feeds one WebView-minted web token to whichever client NewPipeExtractor 0.26.5 uses. Per yt-dlp's guide that token cannot be valid for the IOS client, and this research measured the 1 MB cap with the same client version — so production Android is most likely playing through NewPipeExtractor's own path (which `NewPipeExtractorClient.kt` says "sustains past the boundary … with NO poToken provider registered") or falling to ANDROID 360p. Check playback telemetry before trusting the IOS recipe anywhere; note NewPipeExtractor `dev` has already replaced the IOS client with VISIONOS, and that upgrade will inherit the kids gap on Android too.

---

## Appendix A — Reproduction harness

All four files were run on 2026-08-22 (macOS 24.6, Python 3, Node 22, Swift 6.1.2). Keep them with this doc; they are the only way to know whether §4 is still true. The catalog-wide run (§4 row 3, second run) is A.1's `player('visionos', visitor)` in a loop over `/api/v1/content?type=VIDEOS&limit=40`; the MWEB test (row 19) is `yt-dlp --js-runtimes node --extractor-args "youtube:player_client=mweb;po_token=mweb.gvs+$(curl -s 'https://app.fitrahtube.com/api/v1/dub-potoken?videoId=ID' -H 'X-Device-Id: …' | jq -r .poToken)" -f 140 --get-url ID` followed by A.1's `chunked()`.

### A.1 `probe.py`

Playability + CDN probe. `python3 probe.py VIDEO_ID [POTOKEN]`. Reproduces §4 rows 2–3, 7–8, 10 in one run (verified 2026-08-22: bare → `LOGIN_REQUIRED`; session → VISIONOS OK/HLS, 24×720p segments 200, full audio; IOS 403 at 1 MB; ANDROID itag 18 full 20 MB).

```python
#!/usr/bin/env python3
"""InnerTube playability probe — re-verifies §4 of docs/architecture/ios-app-plan.md.

Usage:  python3 probe.py VIDEO_ID [POTOKEN]
POTOKEN (optional): a session-bound pot minted by wkmint (A.2/A.3) for the visitorData this script prints.
Run it from the network you care about (iPhone hotspot, home Wi-Fi, a VPN exit)."""
import json, re, sys, urllib.error, urllib.request

VID = sys.argv[1]
POT = sys.argv[2] if len(sys.argv) > 2 else None
API = 'https://www.youtube.com/youtubei/v1/player?prettyPrint=false'
# name: (context.client, X-YouTube-Client-Name, User-Agent). Keep in sync with yt-dlp youtube/_base.py.
CLIENTS = {
    'visionos': ({'clientName': 'VISIONOS', 'clientVersion': '1.02', 'deviceMake': 'Apple',
                  'deviceModel': 'RealityDevice17,1', 'osName': 'visionOS', 'osVersion': '26.5.23O471'}, '101',
                 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15'),
    'ios': ({'clientName': 'IOS', 'clientVersion': '21.03.2', 'deviceMake': 'Apple', 'deviceModel': 'iPhone16,2',
             'osName': 'iPhone', 'osVersion': '18.7.2.22H124'}, '5',
            'com.google.ios.youtube/21.03.2 (iPhone16,2; U; CPU iOS 18_7_2 like Mac OS X;)'),
    'android': ({'clientName': 'ANDROID', 'clientVersion': '21.26.364', 'androidSdkVersion': 30,
                 'osName': 'Android', 'osVersion': '11'}, '3',
                'com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip'),
}


def player(name, visitor=None, pot=None):
    ctx, cid, ua = CLIENTS[name]
    ctx = dict(ctx, hl='en', gl='US')
    if visitor:
        ctx['visitorData'] = visitor
    body = {'context': {'client': ctx}, 'videoId': VID, 'contentCheckOk': True, 'racyCheckOk': True}
    if pot:
        body['serviceIntegrityDimensions'] = {'poToken': pot}
    headers = {'Content-Type': 'application/json', 'User-Agent': ua, 'X-YouTube-Client-Name': cid,
               'X-YouTube-Client-Version': ctx['clientVersion'], 'Origin': 'https://www.youtube.com'}
    if visitor:
        headers['X-Goog-Visitor-Id'] = visitor
    req = urllib.request.Request(API, data=json.dumps(body).encode(), headers=headers)
    with urllib.request.urlopen(req, timeout=25) as r:
        return json.load(r)


def get(url, ua, rng=None):
    """(status, bytes, total length parsed from Content-Range or None)."""
    headers = {'User-Agent': ua}
    if rng:
        headers['Range'] = rng
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=40) as r:
            tail = r.headers.get('Content-Range', '').rsplit('/', 1)[-1]
            return r.status, r.read(), int(tail) if tail.isdigit() else None
    except urllib.error.HTTPError as e:
        return e.code, b'', None


def chunked(url, ua, step):
    """Fetch a whole file in Range chunks, like a player does. A 403 part-way = CDN enforcement (the ~60 s cutoff)."""
    status, data, total = get(url, ua, f'bytes=0-{step - 1}')
    if status != 206 or not total:
        return f'first range -> HTTP {status}'
    codes, got = [status], len(data)
    for start in range(step, total, step):
        status, data, _ = get(url, ua, f'bytes={start}-{min(start + step - 1, total - 1)}')
        codes.append(status)
        got += len(data)
        if status != 206:
            break
    return f'{len(codes)} ranges -> {sorted(set(codes))}, {got / 1048576:.2f} MB of {total / 1048576:.2f} MB'


def summary(j):
    ps, sd = j.get('playabilityStatus', {}), j.get('streamingData', {})
    af = sd.get('adaptiveFormats', [])
    reason = f" ({ps['reason']})" if ps.get('reason') else ''
    return (f"{ps.get('status')}{reason} hls={'hlsManifestUrl' in sd} "
            f"urls={sum(1 for f in af if f.get('url'))}/{len(af)} sabr={'serverAbrStreamingUrl' in sd}")


# 1. A bare request establishes the session; reuse responseContext.visitorData for everything after.
first = player('visionos')
visitor = first.get('responseContext', {}).get('visitorData')
print('visitorData:', visitor or 'NONE (bot-checked?)')
print(f'{"visionos (bare)":20} {summary(first)}')

# 2. Every client with the session (and the optional pot), then the CDN checks that matter.
for name, (_, _, ua) in CLIENTS.items():
    j = player(name, visitor, POT)
    sd = j.get('streamingData', {})
    print(f'{name + " (session)":20} {summary(j)}')
    pot_q = f'&pot={POT}' if POT else ''
    if sd.get('hlsManifestUrl'):
        status, master, _ = get(sd['hlsManifestUrl'], ua)
        print(f'    HLS master -> HTTP {status}')
        if status == 200:
            variants = re.findall(r'#EXT-X-STREAM-INF:([^\n]*)\n([^\n]+)', master.decode())
            v720 = [u for attrs, u in variants if 'RESOLUTION=1280x720' in attrs]
            if v720:
                _, media, _ = get(v720[0], ua)
                segs = [l for l in media.decode().splitlines() if l and not l.startswith('#')]
                codes = [get(s, ua)[0] for s in segs[:24]]  # ~2 minutes of 720p
                print(f'    720p: {len(codes)} segments -> {sorted(set(codes))}')
    audio = next((f for f in sd.get('adaptiveFormats', []) if f.get('itag') == 140 and f.get('url')), None)
    if audio:
        print(f'    itag 140 audio, full file: {chunked(audio["url"] + pot_q, ua, 262144)}')
    muxed = next((f for f in sd.get('formats', []) if f.get('url')), None)
    if muxed:
        print(f'    itag {muxed["itag"]} {muxed.get("qualityLabel")} muxed, full file: {chunked(muxed["url"] + pot_q, ua, 1048576)}')
```

### A.2 `bgmint_entry.js`

BotGuard minter entry for a WKWebView at a youtube.com origin. Build: `npm i bgutils-js && npx esbuild bgmint_entry.js --bundle --format=iife --platform=browser --outfile=bgmint_bundle.js` (15 KB).

```js
// BotGuard poToken minter, to be injected into a WKWebView whose origin is youtube.com
// (load https://www.youtube.com/robots.txt first). Build once:
//   npm i bgutils-js && npx esbuild bgmint_entry.js --bundle --format=iife --platform=browser --outfile=bgmint_bundle.js
// Then: await window.__mint(visitorData, videoId)  ->  JSON { integrityPresent, ttl, sessionPot, videoPot }
import { BotGuardClient } from 'bgutils-js/botguard';
import { buildURL, parseLooseJSON, getHeaders } from 'bgutils-js/utils';
import { WebPoMinter } from 'bgutils-js/webpo';

window.__mint = async function (visitorData, videoId) {
  // 1. The YouTube home page carries the BotGuard challenge (window.ytAtN) and ytcfg (needed for EVENT_ID).
  const html = await (await fetch('https://www.youtube.com/', { credentials: 'include' })).text();
  const cfg = html.match(/ytcfg\.set\(({.+?})\);/s)?.[1];
  if (cfg) window.yt = window.yt || { config_: JSON.parse(cfg) };
  const m = html.match(/window\.ytAtN\(\s*({[\s\S]*?})\s*\)/);
  if (!m) throw new Error('no ytAtN challenge in page');
  const ch = parseLooseJSON(m[1]).R;
  if (!ch.bgChallenge) throw new Error('no bgChallenge');

  // 2. Load the BotGuard VM interpreter as a <script> (cross-origin fetch would be blocked by CORS).
  const interp = ch.bgChallenge.interpreterUrl.privateDoNotAccessOrElseTrustedResourceUrlWrappedValue;
  await new Promise((res, rej) => {
    const s = document.createElement('script');
    s.src = 'https:' + interp; s.onload = res; s.onerror = () => rej(new Error('interpreter load failed'));
    document.head.appendChild(s);
  });

  // 3. Run the VM, get an integrity token from WAA, build a minter. Real WebKit matters here:
  //    JavaScriptCore/jsdom snapshots yield tokens the CDN rejects for rqh=1 streams.
  const bg = await BotGuardClient.create({ program: ch.bgChallenge.program, globalName: ch.bgChallenge.globalName, globalObject: window });
  const webPoSignalOutput = [];
  const botguardResponse = await bg.snapshot({ webPoSignalOutput });
  const r = await fetch(buildURL('GenerateIT', true), { method: 'POST', headers: getHeaders(), body: JSON.stringify(['O43z0dpjhgX20SCx4KAo', botguardResponse]) });
  const [integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken] = await r.json();
  const minter = await WebPoMinter.create({ integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken }, webPoSignalOutput);

  // 4. Session-bound token (GVS) + optional video-bound token (player).
  const sessionPot = await minter.mintAsWebsafeString(visitorData);
  const videoPot = videoId ? await minter.mintAsWebsafeString(videoId) : null;
  return JSON.stringify({ integrityPresent: !!integrityToken, ttl: estimatedTtlSecs, sessionPot, videoPot });
};
```

### A.3 `wkmint.swift`

macOS harness that runs A.2 inside a real WKWebView and prints the token JSON. `swiftc -O wkmint.swift -o wkmint && ./wkmint "<visitorData>" VIDEO_ID`. Feed `sessionPot` to A.1 as the second argument.

```swift
// macOS command-line harness: mint a BotGuard poToken inside a real WKWebView.
// If the minter of §6.12 ever ships, the same flow runs in a hidden WKWebView on iOS, with this bundle in the app.
//   swiftc -O wkmint.swift -o wkmint && ./wkmint "<visitorData>" [videoId]
// Expects bgmint_bundle.js (built from bgmint_entry.js) in the working directory.
import Cocoa
import WebKit

let args = CommandLine.arguments
let visitorData = args[1]
let videoId = args.count > 2 ? args[2] : ""
let bundle = try! String(contentsOfFile: "bgmint_bundle.js", encoding: .utf8)
let app = NSApplication.shared
app.setActivationPolicy(.prohibited)

final class Delegate: NSObject, WKNavigationDelegate {
    var done = false
    func webView(_ wv: WKWebView, didFinish nav: WKNavigation!) {
        wv.evaluateJavaScript(bundle) { _, err in
            if let err { print("inject error: \(err)"); self.done = true; return }
            wv.callAsyncJavaScript("return await window.__mint(vd, vid);",
                                   arguments: ["vd": visitorData, "vid": videoId], in: nil, in: .page) { result in
                switch result {
                case .success(let v): print("RESULT \(v ?? "")")
                case .failure(let e): print("mint error: \(e)")
                }
                self.done = true
            }
        }
    }
    func webView(_ wv: WKWebView, didFailProvisionalNavigation nav: WKNavigation!, withError e: Error) { print("nav error: \(e)"); done = true }
}

let webView = WKWebView(frame: NSRect(x: 0, y: 0, width: 800, height: 600), configuration: WKWebViewConfiguration())
let window = NSWindow(contentRect: webView.frame, styleMask: .borderless, backing: .buffered, defer: false)
window.contentView = webView  // never shown; a hosted view keeps WebKit's process alive
let delegate = Delegate()
webView.navigationDelegate = delegate
// robots.txt gives the page a youtube.com origin (CORS to jnn-pa.googleapis.com) without loading the site.
webView.load(URLRequest(url: URL(string: "https://www.youtube.com/robots.txt")!))
let start = Date()
while !delegate.done && Date().timeIntervalSince(start) < 60 { RunLoop.main.run(until: Date().addingTimeInterval(0.25)) }
if !delegate.done { print("timeout") }
```

### A.4 `avtest.swift`

AVPlayer smoke test for an `hlsManifestUrl`. `swiftc -O avtest.swift -o avtest && ./avtest "<url>"`. Expect `readyToPlay`, rate 1.0, growing `buffered`, 0 stalls.

```swift
// macOS AVFoundation check: does AVPlayer play a YouTube HLS manifest natively (no proxy, default UA)?
//   swiftc -O avtest.swift -o avtest && ./avtest "<hlsManifestUrl from the visionos player response>"
import AVFoundation
import Foundation

let url = URL(string: CommandLine.arguments[1])!
let item = AVPlayerItem(asset: AVURLAsset(url: url))
let player = AVPlayer(playerItem: item)
player.isMuted = true
player.play()
let start = Date()
while Date().timeIntervalSince(start) < 20 {
    RunLoop.main.run(until: Date().addingTimeInterval(2))
    let status: String
    switch item.status {
    case .readyToPlay: status = "readyToPlay"
    case .failed: status = "failed \(item.error?.localizedDescription ?? "")"
    default: status = "unknown"
    }
    let buffered = item.loadedTimeRanges.first.map { CMTimeGetSeconds(CMTimeRangeGetEnd($0.timeRangeValue)) } ?? 0
    print(String(format: "t=%4.1fs status=%@ rate=%.1f currentTime=%.1fs buffered=%.1fs",
                 Date().timeIntervalSince(start), status, player.rate, CMTimeGetSeconds(player.currentTime()), buffered))
    if item.status == .failed { break }
}
if let ev = item.accessLog()?.events.last {
    print("accessLog: playbackType=\(ev.playbackType ?? "-") bytes=\(ev.numberOfBytesTransferred) stalls=\(ev.numberOfStalls)")
}
if let el = item.errorLog()?.events.last { print("errorLog: \(el.errorStatusCode) \(el.errorComment ?? "")") }
print("tracks:", item.tracks.compactMap { $0.assetTrack?.mediaType.rawValue })
```


## Appendix B — Sources

- yt-dlp: [`_base.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py) · [`_video.py`](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py) · [PR #17184 visionos](https://github.com/yt-dlp/yt-dlp/pull/17184) · [#17226 visionos benefits](https://github.com/yt-dlp/yt-dlp/issues/17226) · [#17456 android_vr 403](https://github.com/yt-dlp/yt-dlp/issues/17456) · [PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide) · [bgutil-ytdlp-pot-provider](https://github.com/Brainicism/bgutil-ytdlp-pot-provider)
- NewPipeExtractor: [`YoutubeStreamExtractor.java` (dev)](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java) · [`ClientsConstants.java` (dev)](https://github.com/TeamNewPipe/NewPipeExtractor/blob/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/ClientsConstants.java)
- Libraries: [YouTube.js](https://github.com/LuanRT/YouTube.js) · [BgUtils](https://github.com/LuanRT/BgUtils) · [SmartTubeIOS](https://github.com/milika/SmartTubeIOS) · [ReactTube](https://github.com/Duell10111/ReactTube)
- App Store: [Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) (1.2, 1.3, 2.3.1, 2.3.8, 2.5.2, 4.2, 4.8, 5.1.1, 5.1.4, 5.2.2, 5.2.3) · [Apple Developer Program License Agreement §3.3.1(B)](https://developer.apple.com/support/terms/apple-developer-program-license-agreement/) · [Upcoming requirements (Xcode 26 SDK, age ratings, privacy manifests)](https://developer.apple.com/news/upcoming-requirements/) · [Updated age ratings](https://developer.apple.com/news/?id=ks775ehf) · [Social-media questionnaire questions](https://developer.apple.com/news/?id=tlur8uvi) · [Smart Tube BDP listing](https://apps.apple.com/us/app/smart-tube-bdp/id6761388918) · [Yattee #906](https://github.com/yattee/yattee/issues/906) · [9to5Mac — Musi ruling](https://9to5mac.com/2026/03/17/streaming-app-musi-loses-app-store-case-as-judge-rules-apple-can-delist-apps-at-any-time/) · [TorrentFreak — Musi](https://torrentfreak.com/court-dismisses-musis-apple-lawsuit-sanctions-law-firm-for-baseless-claims/)
- YouTube policy: [Terms of Service](https://www.youtube.com/static?template=terms&hl=en&gl=US) · [API Services Developer Policies](https://developers.google.com/youtube/terms/developer-policies) · [Required Minimum Functionality (Referer / WKWebView setup)](https://developers.google.com/youtube/terms/required-minimum-functionality) · [IFrame API reference (error codes 2/5/100/101/150/153)](https://developers.google.com/youtube/iframe_api_reference) · [Player parameters (`rel`, `playsinline`, `origin`)](https://developers.google.com/youtube/player_parameters) · [Data API `videos` resource (`status.madeForKids`, `status.embeddable`)](https://developers.google.com/youtube/v3/docs/videos) · [Made-for-kids guide](https://developers.google.com/youtube/v3/guides/made_for_kids_status)
- Other: [Grayjay FAQ](https://grayjay.app/faq.html) · [NewPipe official](https://newpipe.net/)
