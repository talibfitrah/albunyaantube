# Phase 2 research — Stream resolution (videoId → playable media)

Scope: everything between "the user taps a video" and "the player has URLs/manifest to play":
NewPipeExtractor usage, the resolver/prefetch stack, caching, client rotation, rate limiting +
cooldown, poToken/nsig infrastructure, format/audio selection, live streams, error taxonomy, and
the backend endpoints consulted during resolution. Every claim cites `android/app/src/main/**`
(or gradle) file:line. Behavioural contract for an iOS "InnerTubeKit" — no Swift design here.

Excluded (other phases): downloads/Cast/AirPlay (Phase 3 — `DownloadWorker` resolves via the same
`PlayerRepository`, noted but not specified), accounts (Phase 4), and ExoPlayer-internal playback
behaviour (ABR, buffer policy, track selection at play time) except where it dictates *what gets
resolved and when*.

Library: NewPipeExtractor **v0.26.5** (`android/app/build.gradle.kts:416`). Comments in
`WebViewPoTokenProvider.kt:19` and `DubAudioResolver.kt:30` still say "0.26.2" — stale, the
dependency is 0.26.5.

---

## 0. TL;DR for the implementer

1. **All stream resolution is on-device.** There is NO backend stream/watch endpoint. The backend
   is consulted only for (a) a HEAD availability gate before each extraction (§5.2) and (b) a
   videoId-bound poToken for dub audio (`/api/v1/dub-potoken`, §12.3).
2. **One resolve pipeline, four layers**: `NewPipeExtractorClient.resolveStreams` (extraction +
   30-min LRU cache) ← `GlobalStreamResolver` (single-flight + availability gate + priority
   escalation) ← `PlayerRepository`/`StreamPrefetchService` (callers) ← ViewModels/Binders.
3. **Resolution is triggered at tap time, not at player-open time.** Every list screen calls
   `triggerPrefetch(videoId)` on tap before navigating (§7.1); the player then *joins* that
   in-flight job (§8.1). Port this or cold-open latency doubles.
4. **Two YouTube clients, failure-driven rotation**: IOS first (full adaptive ladder), ANDROID as
   fallback (muxed 360p itag 18 only). Rotate ONLY on extraction failure, never on refresh (§3.4).
5. **Track selection is done twice**: at resolve time everything is kept (all muxed + video-only +
   audio streams, sorted); at source-build time `DashSourceBuilder.decide()` picks synthetic
   multi-representation DASH → progressive muxed → video-only+audio (§10). Live prefers HLS.
6. **Errors collapse deliberately.** NewPipe's rich exception taxonomy (ContentNotAvailable /
   AgeRestricted / GeoBlocked …) is NOT branched on anywhere — every extraction failure becomes
   retry-then-`Error`; the only distinct user-facing state is `ContentUnavailable`, which comes
   from the app's own backend 410 gate, not from NewPipe (§15).

---

## 1. Component map

| Layer | File | Role |
|---|---|---|
| NewPipe init + extraction | `data/extractor/NewPipeExtractorClient.kt` | `NewPipe.init`, `resolveStreams`, metadata fetch, stream cache |
| Client rotation | `data/extractor/YoutubeClientRotator.kt` | per-video IOS→ANDROID fallback state |
| Downloader chain | `data/extractor/OkHttpDownloader.kt`, `RateLimitedDownloader.kt` | NewPipe `Downloader` impl + gate wrapper |
| Priority plumbing | `data/extractor/NewPipePriorityContext.kt` | ThreadLocal lane tag read by the downloader |
| Rate limit / cooldown | `data/extractor/GlobalNewPipeRateLimiter.kt`, `CooldownState.kt`, `player/ExtractionRateLimiter.kt` | three separate mechanisms (§6) |
| Models | `data/extractor/StreamModels.kt`, `ResolvedStreamsExt.kt`, `ExtractorMetadata.kt` | `ResolvedStreams`, tracks, TTLs, UA contract |
| Single-flight resolver | `player/GlobalStreamResolver.kt` | join/escalate, availability gate, `ContentUnavailableException` |
| Repository | `player/PlayerRepository.kt` | thin interface, default `Priority.PLAYER` |
| Tap prefetch | `player/StreamPrefetchService.kt`, `PredictivePrefetchController.kt` | §7 |
| Source decision | `player/DashSourceBuilder.kt`, `MultiRepresentationMpdGenerator.kt`, `SyntheticDashMpdRegistry.kt`, `SegmentDataSourceFactoryProvider.kt`, `SegmentPreBuffer.kt`, `MpdTtlWatcher.kt` | §10–11 |
| Dub audio (2nd innertube path) | `data/extractor/DubAudioEnumerator.kt`, `DubAudioResolver.kt`, `nsig/NsigSolver.kt`, `nsig/NsigWebView.kt` | §12 |
| poToken | `data/extractor/potoken/*` | §13 |
| Consumers | `ui/player/PlayerViewModel.kt`, `ui/player/PlayerFragment.kt`, `ui/shorts/PlayerBinder.kt` | §8, §11 |
| DI | `di/DataModule.kt:198-300` | wiring, cache TTLs |

**Dead code**: `data/extractor/AndroidVrStreamResolver.kt` is constructed nowhere in production
(grep: only its own declaration at `:41` and unit tests reference it). The ANDROID_VR client was
retired 2026-08-18 — YouTube extended GVS poToken enforcement to it, so its URLs served ~60 s then
403'd (`NewPipeExtractorClient.kt:112-126`, `StreamModels.kt:190-194`). `ExtractionClient.ANDROID_VR`
survives only "for the parsing helpers still under test" (`StreamModels.kt:191-194`). **Do not port.**

---

## 2. NewPipe initialization and the downloader chain

- Init happens in `NewPipeExtractorClient.init { initializeNewPipe() }` (`:79-81`), guarded by a
  class-level lock: `NewPipe.init(downloader, localization, contentCountry)` +
  `NewPipe.setupLocalization(...)` only when the current downloader differs (`:910-916`).
- Localization is **hardcoded `Locale.US` / ContentCountry "US"** (`:68-69`).
- `YoutubeStreamExtractor.setPoTokenProvider(provider)` is registered once, globally, at init
  (`:924-930`) — "belt-and-braces": NewPipe 0.26.5 resolves sustainable URLs without a token, the
  provider is a fallback minted lazily (`:921-923`).
- `applyIosFetchSetting()` (`YoutubeStreamExtractor.setFetchIosClient`) is applied at init and
  re-applied before every extraction so the runtime toggle works without restart (`:931-932`,
  `:958-962`). This is **global static state** on the extractor — last write wins (`:948-956`).

Downloader chain (`di/DataModule.kt:198-257`): NewPipe's `Downloader` is
`RateLimitedDownloader(delegate = OkHttpDownloader, GlobalNewPipeRateLimiter, CooldownState)`
(`:249-257`); `OkHttpDownloader` maps NewPipe `Request` → OkHttp, adds `Accept-Language` from the
request's localization when absent (`OkHttpDownloader.kt:34-39`), supports HEAD/GET/POST/other
(`:42-54`), and in debug builds captures youtubei `/player` request/response bodies to
`cacheDir/npe_capture` (`:56-96,105-125`).

`RateLimitedDownloader.execute` (`RateLimitedDownloader.kt:101-159`) reads the current lane from
`NewPipePriorityContext.currentOrDefault()` and gates as described in §6.3. It is the ONLY place
429/ReCaptcha are detected: a 429 response or `ReCaptchaException` from any non-PLAYER lane calls
`cooldownState.trip(...)` then rethrows as `IOException("HTTP 429 — cooldown tripped")` /
rethrows the `ReCaptchaException` (`:148-158`).

`NewPipePriorityContext` is a ThreadLocal with LIFO `with(priority) { ... }` scoping
(`NewPipePriorityContext.kt:64-97`); the unset default is `USER_FOREGROUND` — deliberately the
non-bypassing choice (`:77-82`). Lanes (`:32-37`): `PLAYER`, `VISIBLE_INTERACTIVE`,
`USER_FOREGROUND`, `BACKGROUND_REFRESH`.

---

## 3. Core extraction — `NewPipeExtractorClient.resolveStreams(videoId, forceRefresh)`

File: `data/extractor/NewPipeExtractorClient.kt:89-236`. Runs on `Dispatchers.IO` (`:89`).

### 3.1 Input validation

`videoId` must match `^[a-zA-Z0-9_-]{11}` else return null (`:90`, pattern `:1045`).

### 3.2 Stream cache (resolved-URL cache)

- LRU `LinkedHashMap` (accessOrder=true), **max 50 entries** (`:70-76`, `:1044`), guarded by a lock.
- **TTL 30 min** (`STREAM_CACHE_TTL_MILLIS`, `:1025`), against `SystemClock.elapsedRealtime()`
  (monotonic; `:61`). Entries carry a `timebaseVersion` — mismatched versions are treated as
  expired (`:668-672`, `:1048-1062`).
- Reuse rule `isStreamCacheUsable`: `timebaseMatches && age <= 30min && !isLive` (`:1038-1043`).
  **Live results are NEVER cached** — a live manifest is only usable at the live edge; a 5-min-old
  one 403'd 3.7 s into playback on-device (`:1030-1037`, write-skip at `:161-164`).
- `forceRefresh=true` bypasses the read but does **not** evict first — the old entry survives as
  fallback if the fresh fetch fails, and is overwritten only on success (`:85-88`, `:108-109`).
- Cache metrics: hit/miss reported per lookup (`:104`, `:110`).
- `clearStreamCache()` exists for ops/debug (`:980-990`) and is invoked on
  `onTrimMemory(level >= 80)` only (`AlBunyaanApplication.kt:280-288`) — level 60 deliberately
  does not clear it (`:281-284`).

### 3.3 Extraction sequence

1. `streamLinkHandlerFactory.fromId(videoId)` → `youtubeService.getStreamExtractor(handler)`
   (`:128-129`; factories from `YoutubeStreamLinkHandlerFactory.getInstance()` etc., `:65-67`).
2. Client selection under the class lock (§3.4), applied via `setFetchIosClient` (`:139-148`).
3. `extractor.fetchPage()` **then** `StreamInfo.getInfo(extractor)` — comment: "CRITICAL: Must call
   fetchPage() before getInfo() to get ALL video formats!" (`:149-151`).
4. Map to `ResolvedStreams` (§4). A null mapping (no tracks) resets the rotator and returns null
   (`:154-160`).
5. On success: cache (non-live), `clientRotator.reset(videoId)`, metrics (`:161-168`).

### 3.4 Client rotation — failure-driven, not refresh-driven

`YoutubeClientRotator` (`YoutubeClientRotator.kt`): rotation order `[IOS, ANDROID]` (`:20`),
per-video state evicted after **30 min** inactivity (`:19`, `:71-77`).

- `initialClient(isIosEnabled)` = IOS if the iOS-fetch flag is on, else ANDROID (`:29-30`).
- `currentClient(videoId)` returns the armed fallback if a prior failure advanced it, else the
  initial client — it does NOT advance (`:59-65`). A post-seek URL refresh therefore stays on the
  same client; "rotating IOS→ANDROID on every refresh collapsed the adaptive ladder to muxed 360p
  (the ANDROID client returns only itag 18)" (`NewPipeExtractorClient.kt:130-138`).
- On genuine extraction failure the catch arms `clientRotator.nextClient(videoId)` (advance;
  exhausted → null and state removed) so the *caller's* retry uses the next client
  (`NewPipeExtractorClient.kt:223-228`; `YoutubeClientRotator.kt:39-50`).
- Any success (including "client responded but mapping failed") resets rotation (`:158`, `:166`,
  `:210`).
- All of this is behind `featureFlags.isClientRotationEnabled` (default ON, §19); when off, the
  initial client is always used (`:144-147`).

### 3.5 The visitorData retry

If extraction throws `ExtractionException` whose message contains `"visitorData"` (case-insensitive),
ONE transparent retry with a fresh extractor is made — YouTube sometimes omits visitorData on the
first request from a fresh session (`:171-222`). The retry honours the armed rotation client
(`:190-195`), caches on success, rethrows `CancellationException` (`:214-218`), and swallows its own
failure to fall through to normal error handling (`:219-221`).

### 3.6 Error exit contract

`CancellationException` is rethrown (`:169-170`). `IOException` / `ExtractionException` propagate
as-is; anything else is wrapped in `ExtractionException("Unexpected stream extraction failure", t)`
(`:230-234`). Failure metrics are reported first (`:229`).

---

## 4. `ResolvedStreams` — what a resolve produces

Mapping: `StreamInfo.toResolvedStreams` (`NewPipeExtractorClient.kt:451-652`). Model:
`data/extractor/StreamModels.kt`.

### 4.1 Video tracks

- Input = `videoStreams + videoOnlyStreams`, deduped by URL (`distinctBy { it.content }`, `:453`),
  blank-URL entries dropped (`:467`).
- Quality label: `"${height}p"` + fps suffix when `fps > 30` (e.g. `1080p60`); fallback
  `"${width}x${height}"`; last resort NewPipe's own label (`:470-474`).
- `SyntheticDashMetadata` (itag, init/index byte ranges, approxDurationMs, codec) is captured for
  every **video-only** stream whose `ItagItem` carries valid ranges — regardless of
  `DeliveryMethod` (NewPipe tags YouTube adaptive formats as DASH yet still populates SegmentBase
  ranges; the old PROGRESSIVE_HTTP gate silently dropped the whole adaptive ladder → 360p fallback)
  (`:479-499`). Validity: all four range values ≥ 0 and start ≤ end
  (`StreamModels.kt:22-24`).
- **No further dedup** — multiple bitrates at the same height are kept for step-down flexibility
  (`:514-516`). Sort: height desc, then bitrate desc (`:517-520`).
- `VideoTrack` fields: url, mimeType, width/height/bitrate/fps (positive-or-nil), qualityLabel,
  isVideoOnly, syntheticDashMetadata, codec (`StreamModels.kt:27-45`).

### 4.2 Audio tracks

- Every `audioStreams` entry with non-blank URL (`:526-527`); `SyntheticDashMetadata` captured
  unconditionally when ranges are valid (`:529-544`).
- Language (`audioLocale.toLanguageTag()`), `audioTrackName`, and `audioTrackType` are read
  **defensively** — each wrapped in try/catch, missing field → null (`:546-581`). TrackType maps to
  the app enum `AudioTrackKind { ORIGINAL, DUBBED, DESCRIPTIVE, DUBBED_AUTO, UNKNOWN }`
  (`:567-575`, `StreamModels.kt:69`).
- `AudioTrack.source` defaults to `VR_NATIVE`; `WEB_DUB` marks dub tracks minted via the web client
  (§12) (`StreamModels.kt:60-61,76`).
- **Fallback audio derivation** (`deriveFallbackAudioTracks`, `:1006-1021`, applied at `:595`): if
  YouTube exposes zero separate audio streams, fabricate one from the best **muxed** video track
  (embedded audio). If no muxed track exists, return empty — never fabricate from a video-only
  track (it would be silent; documented bug history at `:994-1005`).

### 4.3 Subtitles

Each `subtitles` entry with a non-blank language tag and a URL-type content becomes
`SubtitleTrack(url, languageCode, languageName ?: code, format = extension, isAutoGenerated)`
(`:628-637`).

### 4.4 Manifests, live, duration, client

- `hlsUrl` / `dashUrl` taken when non-blank (`:601-603`).
- `isLive = streamType == LIVE_STREAM || AUDIO_LIVE_STREAM` (`:624-626`).
- `durationSeconds` = positive duration or nil (`:599`).
- If both video and audio track lists are empty → mapping returns null (`:597`).
- `extractionClient` records which innertube client minted the URLs: `NEWPIPE_IOS` or
  `NEWPIPE_ANDROID` (`:448-449`). This **determines the mandatory segment-fetch User-Agent** —
  a UA mismatch 403s googlevideo (`StreamModels.kt:110-119,184-214`). UAs:
  Android/Chrome-mobile `HttpConstants.YOUTUBE_USER_AGENT` (`util/HttpConstants.kt:16-17`), YouTube
  iOS app `com.google.ios.youtube/19.29.1 (iPhone16,2; …)` (`:32-33`), MWEB UA for dubs (`:41-43`).
  Playback picks the matching factory via `SegmentDataSourceFactoryProvider.forClient`
  (`player/SegmentDataSourceFactoryProvider.kt:29-48`); live streams skip the segment cache
  (`cache = !isLive`, `:29-32`).

### 4.5 URL TTLs (distinct from the resolve cache)

`StreamModels.kt:121-181`:

- `URL_TTL_MS = 1 h` (conservative; YouTube allows ~6 h) — `areUrlsExpired()` also treats a
  timebase mismatch or clock-went-backwards as expired (`:127`, `:150-154`).
- Live proactive refresh threshold `LIVE_PROACTIVE_REFRESH_MS = 50 min` with
  `shouldProactivelyRefresh()` / `timeUntilProactiveRefreshMs()` (`:136`, `:162-180`) — consumed by
  the live refresh scheduler (§8.5).

### 4.6 Selection state types

`PlaybackSelection(streamId, video?, audio, resolved, userQualityCapHeight?, selectionOrigin)`
(`StreamModels.kt:255-289`); `QualitySelectionOrigin { AUTO, MANUAL, AUTO_RECOVERY }` (`:219-226`);
`QualityConstraintMode { CAP, LOCK }` — MANUAL ⇒ LOCK, AUTO/AUTO_RECOVERY ⇒ CAP (`:235-253`,
`:262-281`).

---

## 5. `GlobalStreamResolver` — single-flight + availability gate

File: `player/GlobalStreamResolver.kt`. Singleton; own scope `SupervisorJob() + Dispatchers.IO`
(`:79-80`). Default timeout **20 s** (`:83`).

### 5.1 Single-flight and priority escalation

- In-flight jobs keyed by videoId in a `ConcurrentHashMap<String, InFlight(deferred, priority)>`
  (`:166-172`).
- A caller with `forceRefresh=false` whose priority rank ≤ the in-flight job's rank **joins** it
  (`:268-299`; double-checked under lock `:314-324`). Ranks: PLAYER=2,
  VISIBLE_INTERACTIVE=USER_FOREGROUND=1, BACKGROUND_REFRESH=0 (`:184-189`).
- A **higher**-priority caller cancels the existing job and restarts it at its own priority
  ("escalation"); lower-priority awaiters get null (they must be null-tolerant) (`:218-232`,
  `:326-345`).
- `forceRefresh=true` cancels any in-flight job and starts fresh (`:306-313`).
- Completion handler removes the job with `remove(key, value)` so an old job's cleanup can't yank a
  replacement (`:410-419`).
- Await semantics: `withTimeoutOrNull(timeoutMs)`; a `CancellationException` is rethrown only if the
  *caller's* context is cancelled, otherwise converted to null (`:424-439`, `:272-297`);
  `ContentUnavailableException` always propagates to every awaiter (`:286-294`, `:440-447`); other
  exceptions → null (`:448-450`).
- Utilities: `isResolveInFlight`, `cancelResolve`, `cancelAll` (called on app background via
  `StreamPrefetchService.clearAll`), `getInFlightCount` (`:465-502`).
- The production provider lambda forces `withContext(Dispatchers.IO)` **before**
  `NewPipePriorityContext.with(priority)` so the ThreadLocal lands on the IO thread NewPipe runs on
  (`:136-158`). First caller's priority sets the whole job's lane; joiners inherit it (`:243-250`).

### 5.2 Backend availability gate (the ONLY backend involvement in resolution)

Runs as the FIRST step **inside** each new resolve job — exactly one HEAD per extraction, shared by
all joiners (`:351-394`):

- `sourceChannelId` present → `verifyAvailable(CHANNEL, sourceChannelId)` (channel-sourced videos
  aren't individually registered); else `verifyAvailable(VIDEO, videoId)` (`:373-380`).
- Endpoints: `@HEAD api/v1/channels/{id}` / `api/v1/playlists/{id}` / `api/v1/videos/{id}`
  (`data/source/api/ContentApi.kt:46-53`).
- Semantics (`RetrofitContentService.kt:106-121`, contract at `ContentService.kt:34-58`):
  **2xx → available**; **404 → available (fail-open**, so unregistered downstream content still
  resolves via NewPipe); **410 → unavailable (admin-blocked/REJECTED/ARCHIVED — hard stop)**;
  other non-2xx → `HttpException` thrown.
- Transport/HTTP errors in the gate are **fail-open** (log + proceed) so offline users can still
  play cached videos (`GlobalStreamResolver.kt:366-386`).
- `available == false` → throw `ContentUnavailableException(videoId)`
  (`:387-393`; class at `player/PlayerRepository.kt:26-27`).

### 5.3 `PlayerRepository`

Thin passthrough (`DefaultPlayerRepository`, `PlayerRepository.kt:84-100`) with
`priority: Priority = Priority.PLAYER` as the default — documented as earned ONLY by live playback;
background callers must override to `USER_FOREGROUND`/lower (`:40-46`, `:66-74`).

---

## 6. Rate limiting, cooldown, retries — three separate mechanisms

### 6.1 `ExtractionRateLimiter` (app-side permit ledger, per videoId)

File: `player/ExtractionRateLimiter.kt`. Checked by *callers* before triggering a resolve; records
the attempt **before** extraction so failures can't storm (`:16-17`, `:149-151`).

Request kinds (`:95-104`): `MANUAL`, `AUTO_RECOVERY`, `PREFETCH`, `PROACTIVE_TTL_REFRESH`.

Constants (`:49-90`): min interval between same-video-same-kind attempts **30 s**; per-video window
**5 min**; `MAX_ATTEMPTS_PER_VIDEO = 3`; global window **60 s** with `MAX_GLOBAL_ATTEMPTS = 10`
(MANUAL+PREFETCH share it); AUTO_RECOVERY reserved **2**/window and **bypasses the global limit
and accounting entirely** (`:394-399`); PROACTIVE_TTL_REFRESH reserved **2**/video/window with its
own global ceiling **10/min** and does not consume the shared per-video budget (`:269-271`,
`:401-418`); MANUAL exponential backoff 2 s→4 s→8 s→16 s→32 s cap 60 s on consecutive attempts
(`:83-87`, `:437-440`); first AUTO_RECOVERY attempt skips the min-interval (`:190-198`); PREFETCH is
blocked once `attempts >= MAX-1` "preserving budget for manual/recovery" (`:246-254`).

Results: `Allowed` / `Delayed(delayMs, reason)` / `Blocked(reason, retryAfterMs)` (`:137-147`).
`onExtractionSuccess` / `resetForVideo` clear the MANUAL backoff counter (`:323-343`). Stale records
cleaned every 10 min (`:89`, `:442-462`).

### 6.2 `GlobalNewPipeRateLimiter` (token bucket, HTTP-level, background only)

File: `data/extractor/GlobalNewPipeRateLimiter.kt`. Capacity **20 tokens**, refill **1/30 s**,
foreground reserve **5 tokens**; only `BACKGROUND_REFRESH` consumes — every other priority returns
true immediately (`:80-115`, `:135-148`). Background acquire is non-blocking (timeout 0 ms default,
`:89-93`, `:139`). Rationale for the bypass: a static token clock silently locks out casual
channel browsing (~2 tokens/min steady state) (`:14-25`).

### 6.3 `CooldownState` (persisted abuse cooldown) + gating in the downloader

File: `data/extractor/CooldownState.kt`. Trips on 429/ReCaptcha (from `RateLimitedDownloader`);
escalation by trip count within 24 h: **1 h → 4 h → 12 h → 24 h** (`:24-28`, `:64-66`, `:100-129`).
7 consecutive clean days reset the count (`:30-34`, `:139-157`). Persisted in DataStore Preferences —
survives restarts (`:36-38`). Dev-settings `clearAll()` (`:168-175`).

Gate application (`RateLimitedDownloader.kt:101-158`):

| Priority | Bucket | Cooldown read | Trips cooldown on 429/ReCaptcha |
|---|---|---|---|
| PLAYER | bypass | bypass | **never** (a player 429 usually = stale URL, not abuse) |
| VISIBLE_INTERACTIVE / USER_FOREGROUND | bypass | bypass | yes (one-way signal for future background work) |
| BACKGROUND_REFRESH | consume (non-blocking) | checked before acquire AND re-checked after (TOCTOU fix) | yes |

A tripped cooldown for BACKGROUND_REFRESH throws
`IOException("NewPipe cooldown active until …")` before any network I/O (`:109-137`). History: in
beta.4/5 the cooldown gated user taps too and a stale persisted trip locked users out of every
channel tap — hence the bypass (`:17-38`).

### 6.4 Who declares which lane

- Player live resolve: `PLAYER` (`PlayerRepository.kt:55`).
- `awaitOrConsumePrefetch` join from the player path: `PLAYER` — so a stalled background prefetch
  is escalated-and-replaced instead of holding playback (`StreamPrefetchService.kt:261-278`).
- Tap prefetch: `BACKGROUND_REFRESH` (`StreamPrefetchService.kt:189-202`).
- Player queue prefetch: `BACKGROUND_REFRESH` (`PlayerViewModel.kt:1736-1741`).
- Channel detail: `VISIBLE_INTERACTIVE` (`data/channel/NewPipeChannelDetailRepository.kt:141,211,243,503,553`).
- Playlist detail: `USER_FOREGROUND` (`data/playlist/NewPipePlaylistDetailRepository.kt:163,186,252,513`).
- Me-tab deep pagination: `USER_FOREGROUND` (`data/me/ChannelDeepPaginator.kt:24,71`).
- Metadata hydration (`fetchVideoMetadata`) sets no lane → ThreadLocal default `USER_FOREGROUND`
  (`NewPipePriorityContext.kt:77-82`).

---

## 7. Prefetch — WHEN resolution happens

### 7.1 Tap prefetch (the primary trigger)

Every video tap calls `prefetchService.triggerPrefetch(video.id, scope)` **before navigating**:
Videos tab (`ui/VideosFragmentNew.kt:180`), Home (`ui/HomeFragment.kt:151`), Search
(`ui/SearchFragment.kt:286`), Featured (`ui/FeaturedListFragment.kt:68`), Playlist detail rows +
"play all" (`ui/detail/PlaylistDetailFragment.kt:271,741`), Channel Videos tab
(`ui/detail/tabs/ChannelVideosTabFragment.kt:58`), Channel Live tab
(`ui/detail/tabs/ChannelLiveTabFragment.kt:57`), Me screen (`ui/me/MeFragment.kt:567`).

### 7.2 `DefaultStreamPrefetchService` behaviour

File: `player/StreamPrefetchService.kt`.

- Purpose: hide ~2–5 s of extraction latency behind the navigation animation (`:44-52`).
- Dedup: skip if already prefetching or already cached (`:170-174`).
- Rate limit: `rateLimiter.acquire(videoId, PREFETCH)` — anything but `Allowed` silently drops the
  prefetch (`:176-181`).
- Resolve via `globalResolver` with `timeoutMs = PREFETCH_TIMEOUT_MS = 8000`, caller "prefetch",
  priority BACKGROUND_REFRESH, on an internal scope that survives fragment destruction
  (`:116-119`, `:143-144`, `:187-202`). The caller-supplied scope parameter is ignored (`:165-168`).
- Result cache: max **5** entries, FIFO eviction, **TTL 30 s** (bounds the archive-bypass window —
  an admin archive landing after prefetch must not play; expired entries force a re-resolve through
  the availability gate) (`:120`, `:122-133`, `:203-213`, `:296-322`). Entries are consume-once
  (`:317-321`).
- After a successful prefetch: `onExtractionSuccess`, then **MPD pre-generation** when
  `isMpdPrefetchEnabled` — eligibility check + `generateMpd(qualityCap = null)` + registry write —
  and optionally **segment preload** of the lowest-bitrate video track when
  `isSegmentPreloadEnabled` (`:214-219`, `:401-448`). `SegmentPreBuffer` warms ~3 s / ~500 B-per-ms
  of the URL through the shared Media3 cache using the UA-correct factory; skipped on low-RAM
  devices; failures non-fatal (`player/SegmentPreBuffer.kt:27-77`).
- Consumption: `awaitOrConsumePrefetch` — (1) fresh cached result (TTL-checked), else (2) if a
  prefetch/in-flight resolve exists, join it via the global resolver for up to
  `AWAIT_TIMEOUT_MS = 3000` at PLAYER priority, then re-check the cache, else null (`:248-294`).
  `consumePrefetch` is the non-blocking variant (`:334-336`).
- `clearPrefetchState()` (safe during playback) vs `clearAll()` (also
  `globalResolver.cancelAll()` — only when app is backgrounded and playback stopped) (`:359-387`).

### 7.3 `PredictivePrefetchController` (scroll-driven, OFF by default)

Attach-time prefetch of every list cell as it enters the window
(`player/PredictivePrefetchController.kt:22-43`). Wired in Videos/Home/Search/Playlist/Channel-tab
fragments behind `featureFlags.isPredictivePrefetchEnabled` (e.g. `ui/VideosFragmentNew.kt:74-81`).
Build default **false**: "fires for every visible cell, which on a Me-feed … with 100 items burns
the global 10/min cap in seconds and locks foreground taps out for ~57 s. Re-enable only after the
controller is scoped (Me-only) and strictly capped (≤2 in flight)"
(`android/app/build.gradle.kts:110-118`).

### 7.4 Queue prefetch (up-next)

`PlayerViewModel.prefetchNextItems()` (`ui/player/PlayerViewModel.kt:1703-1772`): first
`maxPrefetchItems = 2` queue items (`:196`), per-item `rateLimiter.acquire(PREFETCH)` with skip on
Delayed/Blocked (`:1718-1730`), resolve via `repository.resolveStreams(priority =
BACKGROUND_REFRESH, sourceChannelId)` (`:1736-1741`), own cache max 4 entries (2×2, `:1748-1752`)
with **TTL 30 s** (`PREFETCH_CACHE_TTL_MS`, `:1796-1803`) consumed by `consumeFreshPrefetchCache`
(`:1382-1400`). `ContentUnavailableException` during prefetch = silently skip the item (`:1758-1764`).

---

## 8. Player consumption — `PlayerViewModel`

### 8.1 Entry and retry ladder

`loadVideo(...)` builds the item from nav args (no backend fetch), kicks metadata hydration, and
calls `resolveStreamFor` (`:782-841`); duplicate availability gates were removed — the resolver's
chokepoint is the single HEAD (`:829-836`). `resolveStreamFor` cancels the prior resolve job, marks
metrics, sets `StreamState.Loading` synchronously, launches `resolveWithRetry(item, maxAttempts =
3, forceRefresh)` (`:1325-1339`).

`resolveWithRetry` (`:1408-1567`), in order:

1. Unless forceRefresh: `prefetchService.awaitOrConsumePrefetch(streamId)` — a
   `ContentUnavailableException` here means the backend returned 410; halt retries, auto-skip in
   playlist mode else `StreamState.ContentUnavailable` (`:1410-1432`). A valid result → `Ready`
   immediately (`:1433-1443`).
2. Unless forceRefresh: local queue-prefetch cache (TTL-checked) → `Ready` (`:1448-1462`).
3. Loop attempts 1..3: `repository.resolveStreams(streamId, forceRefresh = forceRefresh && attempt
   == 1, sourceChannelId)` wrapped in `withTimeout(EXTRACTOR_TIMEOUT_MS = 20 s)` (`:1470-1478`,
   const `:1778`). Note forceRefresh applies to **attempt 1 only**; retries may serve the cache.
   - `ContentUnavailableException` → no retry (deterministic), auto-skip or `ContentUnavailable`
     (`:1478-1495`).
   - Other throwable → exponential backoff **1 s / 2 s / 4 s** between attempts
     (`RETRY_BASE_DELAY_MS = 1000`, `:1503-1508`, `:1777`); after the last attempt → auto-skip or
     `StreamState.Error(R.string.player_stream_error)` (`:1509-1518`).
   - Null result → same backoff; terminal → `Error(R.string.player_stream_unavailable)`
     (`:1521-1537`).
   - Resolved but no selection derivable → `Error(player_stream_unavailable)` (`:1539-1545`).
   - Success → `rateLimiter.onExtractionSuccess`, `Ready(streamId, selection)`, fire-and-forget dub
     enumeration, schedule live refresh, apply any pending quality cap (`:1548-1566`).

### 8.2 Playlist auto-skip

`handleStreamResolutionFailure` (`:1349-1365`): playlist mode only; at most
`MAX_CONSECUTIVE_SKIPS = 3` consecutive unplayable items auto-advance (toast via `VideoSkipped`
event); beyond that the error state shows (`:1783`, `:2152` event).

### 8.3 Manual and automatic re-resolution

- `retryCurrentStream()` — user retry button; re-resolve **without** forceRefresh (`:1225-1231`).
- `forceRefreshCurrentStream()` = MANUAL kind; `forceRefreshForAutoRecovery()` = AUTO_RECOVERY;
  `forceRefreshForProactiveTtl()` = PROACTIVE_TTL_REFRESH (`:1242-1266`). All flow through
  `forceRefreshCurrentStreamWithKind` (`:1271-1315`): `rateLimiter.acquire` → Allowed = refresh
  now; Delayed = schedule after `delayMs` with streamId re-validation and permit re-acquire;
  Blocked = return false. The internal refresh unregisters the video's MPD and evicts the queue
  prefetch entry BEFORE resolving so a fast resolve can't remarry stale URLs (`:1316-1324`).

### 8.4 Metadata hydration (second NewPipe use from the player)

If nav args are incomplete (blank title/"Video"/no thumb/desc/views/duration/channel),
`extractorClient.fetchVideoMetadata([streamId])` fills the gaps under the same 20 s timeout
(`:1143-1193`). See §14.

### 8.5 Live streams

- Resolution marks `isLive`; live results are never cached (§3.2).
- After Ready, `scheduleLiveStreamRefresh` arms a delayed job at
  `timeUntilProactiveRefreshMs()` (50-min threshold, §4.5) (`:1575-1609`); it verifies the same
  stream is still playing, then `performLiveStreamRefresh`: `repository.resolveStreams(forceRefresh
  = true, sourceChannelId)`, rebuild a selection preserving quality height + audio language →
  emit `PlayerUiEvent.LiveStreamRefreshReady(streamId, newSelection)` for a seamless swap, and
  re-arm (`:1611-1655`). `ContentUnavailableException` mid-broadcast → `ContentUnavailable`
  (`:1640-1647`); other errors are logged and left to reactive (error-driven) refresh (`:1648-1654`).
- Source choice for live prefers **HLS** over the server DASH manifest: measured on-device
  2026-08-18, the live DASH segment BaseURLs carry no poToken → ~78 s then 403 loop, while HLS
  soaked 200 s with zero 403s (`player/DashSourceBuilder.kt:98-112`). `HlsPoisonRegistry` tracks
  videos whose HLS 403'd early and blocks HLS re-selection for a TTL (default 30 min per KDoc)
  (`player/HlsPoisonRegistry.kt:9-34`).

### 8.6 Sticky audio language

`stickyAudioLanguage` (session-global, `:220-225`) is applied to every selection via
`toSelectionWithPreferredAudio` (`:2208-2217`): highest-bitrate track matching the language, else
the default selection. Shorts keep a per-video equivalent (`PlayerBinder.kt:349-368`).

---

## 9. Default quality/audio selection at resolve time

`ResolvedStreams.toDefaultSelection()` (`PlayerViewModel.kt:2160-2206`):

- **Adaptive manifest present (hlsUrl or dashUrl)**: 720p muxed → 480p muxed → 720p any → 480p any
  → overall max by (height, bitrate).
- **Progressive-only**: 480p muxed → 360p muxed → 720p muxed → smallest muxed ≥ 240p → 480p any →
  360p any → overall max. Rationale: progressive cannot ABR, so start conservative (`:2156-2159`).
- Audio: highest-bitrate audio track, else an `AudioTrack` fabricated from the chosen video track;
  if neither exists selection is null (`:2196-2205`).

Quality menu (`buildQualityOptions`, `:1806-1848`): when the prepared source is actually adaptive,
offer the full ladder deduped by height (muxed preferred, then highest bitrate), sorted height
desc; when progressive, offer exactly the ONE track `decide()` would serve (highest muxed, else
highest video-only) — "offering the full list is misleading" (`:1809-1815`).

Cold-start ABR seeding (`player/ColdStartQualityChooser.kt:15-79`): tiers 2160/1080/720/480/360 by
network class + smallest-width breakpoints (600/720 dp) + persisted last-successful height;
cellular bitrate ceilings 2.5 Mbps (fast) / 1.2 Mbps (slow) because reported link speed is a
technology estimate, not throughput (`:66-75`). (Consumed by the player's track selector — playback
side, listed here because it caps what the resolved ladder is allowed to start at.)

---

## 10. Source decision — `DashSourceBuilder.decide` (pure function)

`player/DashSourceBuilder.kt:97-199`:

1. **Live**: `Hls(hlsUrl)` if present, else `ServerDash(dashUrl)`, else `None("LIVE_NO_MANIFEST")`
   (`:98-112`).
2. **VOD, unless forceProgressive**: try `MultiRepresentationMpdGenerator.generateMpd(resolved)`;
   success → `LocalDash(base64 data: URI)` (`:129-152`).
3. **Progressive fallback**: highest **muxed** track (maxBy height — "avoid silently picking 360p
   itag 18 over 720p itag 22") → `Progressive(videoUrl, audio = null)` (`:155-168`).
4. No video tracks at all → `None("NO_VIDEO_TRACK")` (`:170-173`); else best video-only + best
   audio (bitrate) as a two-source progressive merge; video-only alone if no audio; else
   `None("NO_PLAYABLE_STREAM")` (`:179-198`).

`forceProgressive = true` is the sticky retry after an adaptive source failed; it skips step 2
(`:88-96`).

MPD generation (`player/MultiRepresentationMpdGenerator.kt`): LibreTube-style "include everything"
— one video AdaptationSet per container (mp4 holds avc1+av01, webm holds vp9) with ALL
representations, audio grouped by (language, role, container) with the highest-bitrate
representative per group, DASH full profile, SegmentBase byte ranges from `SyntheticDashMetadata`
so no extra network calls (`:13-37`, `:145-186`). Eligibility: positive duration + ≥1 valid-range
audio + ≥1 valid-range video-only track (`:88-108`); optional `qualityCapHeight` filter
(`:136-143`). Failure reasons are machine-readable strings (`NO_DURATION`, `NO_ELIGIBLE_AUDIO`,
`NO_ELIGIBLE_VIDEO`, `NO_VIDEO_AFTER_CAP:0`, `NO_AUDIO_WITH_RANGES`,
`MPD_GENERATION_ERROR:*`). Primary audio = ORIGINAL else highest bitrate (`:183-186`).

Registry & TTL: prefetch-registered MPDs live in `SyntheticDashMpdRegistry` with
`MPD_TTL_MS = 15 min` (`player/SyntheticDashMpdRegistry.kt:43`); `MpdTtlWatcher` fires at **90 %**
of TTL and triggers `forceRefreshForProactiveTtl()` (`player/MpdTtlWatcher.kt:18,23-36`;
`PlayerFragment.kt:1699-1714`). Known gap, documented as deliberate: the synchronous LocalDash path
does NOT register its MPD, so a cold non-prefetched open has no TTL watcher and relies on reactive
403 re-resolve (`DashSourceBuilder.kt:276-285`).

Subtitle side-loading: formats map vtt/webvtt→TEXT_VTT, ttml→APPLICATION_TTML, srt→APPLICATION_SUBRIP,
srv1/2/3 and unknown → skipped (`:211-221`); side-loaded per-track `SingleSampleMediaSource` with
load-errors-as-end-of-stream; **never side-load captions on live** (IllegalMergeException risk)
(`:323-335`, `:416-448`). Audio-only mode plays the highest-bitrate audio track progressively
(`:388-395`).

---

## 11. Reactive recovery triggers (what causes a re-resolve)

- **Single recovery path**: `PlayerFragment.requestStreamRefreshAndResume(reason)`
  (`ui/player/PlayerFragment.kt:2159-2231`) — saves position/playWhenReady, claims a possible
  seek-transient (rebuild same adaptive manifest without spending the degrade budget), calls
  `viewModel.forceRefreshForAutoRecovery()`; if rate-limited it does NOT stop the player (toast
  `player_refresh_rate_limited`, overlay only if not actually playing); if allowed it unregisters
  the MPD (`unregisterBoth`) before refresh to prevent 403 loops, resets prepared-source state,
  stops the player, toasts `player_status_resolving`.
- **Stall watchdog**: armed on BUFFERING after first READY; VOD **6 s**, live **45 s**
  (`:2237-2248`, `STALL_WATCHDOG_VOD_MS/LIVE_MS` `:4265,4270`) → recovery path above.
- **Proactive TTL** (§10) → `forceRefreshForProactiveTtl()`.
- **Terminal give-up**: `surfaceRecoveryExhausted()` flips Ready → `RecoveryExhausted` so a retry
  button shows instead of an endless spinner (`:2147-2149`; state `PlayerViewModel.kt:2104`).
- **Shorts** (`ui/shorts/PlayerBinder.kt`): resolve on bind via
  `playerRepository.resolveStreams(videoId, forceRefresh, sourceChannelId)` in `runCatching` —
  failure emits to a failure flow (pager skips) (`:397-440`). **Stale-URL guard**: a cached
  progressive result (no hls/dash) with `areUrlsExpired()` is re-resolved fresh once before use
  (`:415-433`). Per-video sticky audio language filters the resolved audio tracks before source
  build (`:441-460` region, `:349-368`). Its own `MpdTtlWatcher` + stall watchdog route to
  `forceRefreshCurrent(expectedVideoId)` which ignores fires for a different short (`:374-396`).
  Note: shorts binds ride the repository's default `Priority.PLAYER` (`PlayerRepository.kt:55`).

---

## 12. Dub audio — the second, hand-rolled innertube path

NewPipe 0.26.5 fetches streams via ANDROID/iOS clients only, which don't expose dubs; the app
hand-rolls an MWEB `/player` call (`DubAudioResolver.kt:30-37`).

### 12.1 Enumeration (cheap: no poToken, no nsig)

`DubAudioEnumerator.enumerate(videoId)` (`data/extractor/DubAudioEnumerator.kt:33-38`):

- Bootstrap a session once: GET `https://www.youtube.com/?themeRefresh=1` with cookie `SOCS=CAI`,
  regex `visitorData` out of the body, capture `VISITOR_INFO1_LIVE` (`:82-103`, `:157-163`).
- POST `https://www.youtube.com/youtubei/v1/player?prettyPrint=false` through
  `NewPipe.getDownloader()` (so the priority/cooldown gates apply) with client MWEB
  (name "MWEB", version `2.20250120.00.00`, header `X-Youtube-Client-Name: 2`, MWEB UA, Origin
  m.youtube.com) and body containing `videoId`, `visitorData`, `signatureTimestamp`
  (`:46-76`, `:108-122`, `:131-153`). `videoId` validated as 11-char id at the boundary (`:52-56`).
- Parse `streamingData.adaptiveFormats[*].audioTrack.id` ("en.4" → "en"), displayName,
  `audioIsDefault`/"original" suffix → `DubLanguage(code, display, isOriginal)`; **≤1 language ⇒
  empty list** (no picker) (`:181-199`). Never throws (`:29-32`).

Triggered fire-and-forget after every Ready when the resolve exposes <2 distinct languages, cached
per videoId (`PlayerViewModel.kt:99-121`); results appended as **lazy** `WEB_DUB` placeholder
tracks (`url == ""`) via `withDubLanguages` — original dropped (it IS the native track), existing
languages skipped, no-op under 2 languages (`ResolvedStreamsExt.kt:61-81`). The audio picker groups
by language, one representative (highest bitrate) per language, ORIGINAL-first then alphabetical
(`ResolvedStreamsExt.kt:36-59`).

### 12.2 Resolution of a picked dub (expensive: nsig + poToken)

`DubAudioResolver.resolveDubAudio(videoId, lang)` (`DubAudioResolver.kt:51-105`):

1. `nsigSolver.signatureTimestamp()` — MUST come from the same player JS used for the nsig solve,
   else every segment 403s (`:54-57`; `NsigSolver.kt:104-112`).
2. MWEB player response → highest-bitrate direct-URL candidate for the language, with DASH
   SegmentBase metadata parsed when present (`:58-68`, `:255-290`).
3. **nsig**: deobfuscate the URL's `n=` throttling parameter by running the full (~2.7 MB) player
   JS in a WebView — NewPipe's Rhino extractor mis-extracts on current players
   (`:69-75`; `NsigSolver.kt:14-27`). Player JS fetched once per process via `iframe_api` (rotating
   hashes ⇒ one player reused for both sts and transform) (`NsigSolver.kt:121-136`). 3
   recreate-and-retry attempts per solve (`:64-91`).
4. **poToken**: a videoId-bound GVS pot appended as `&pot=`. Release uses ONLY the backend
   endpoint **`GET {API_BASE}/api/v1/dub-potoken?videoId=`** (sps=3, sustains); the on-device
   WebView pot is sps=2 (1 MB preview cap) and is a DEBUG-ONLY fallback (plus a local bgutil
   sidecar on `localhost:4416` in debug) (`:76-90`, `:112-145`, `:292-294`).
5. Any failure → null → caller keeps the native original audio; never breaks playback (`:47-50`).

`resolveAllDubAudio` prewarms every language at once (one MWEB fetch, one pot, one solve per
distinct `n`) so a later pick is instant (`:147-193`); the ViewModel prewarms on globe-light and
re-attaches the sticky dub after any URL-refresh re-resolve (`PlayerViewModel.kt:123-160`).

### 12.3 Playback of a dub

Preferred: inject the dub into the synthetic MPD as its own language-tagged AdaptationSet (one
DASH source); fallback: `MergingMediaSource` of the native source + a progressive web-UA audio leg
(`DashSourceBuilder.kt:246-260`, `:343-385`). Segment UA for dub legs = MWEB UA via
`forWebDub()` (`SegmentDataSourceFactoryProvider.kt:50-60`; `HttpConstants.kt:36-43`).

---

## 13. poToken infrastructure

`data/extractor/potoken/WebViewPoTokenProvider.kt` implements NewPipe's `PoTokenProvider`:

- Returns the same WebView-minted token from `getWebClientPoToken` / `getAndroidClientPoToken` /
  `getIosClientPoToken`; `getWebEmbedClientPoToken` = null (`:47-53`).
- Never runs on the main thread (deadlock → returns null, tokenless degrade) (`:55-63`).
- 3 attempts with full WebView recreation (Android ≤ 28 renderer often LMK'd), 300 ms × attempt
  backoff; `BadWebViewException` disables tokens for the session; interruption (caller cancelled)
  degrades to tokenless; a "cooldown" message aborts immediately (`:71-135`, `:203-214`).
- Generator warm-up: fetch visitorData via `YoutubeParsingHelper.getVisitorDataFromInnertube`,
  create `PoTokenWebView` (BotGuard), mint one streaming token first; the per-video **player**
  token is reused as the streaming (GVS) token because YouTube's
  `html5_generate_content_po_token` experiment requires videoId-binding, not visitorData-binding
  (`:139-198`).
- NOT on the critical path: NewPipe currently resolves sustainable URLs with no token at all; on
  device the media source was created 6.2 s before a failing mint attempt (`:158-165`).

DI: one shared instance consumed by both `NewPipeExtractorClient` and `DubAudioResolver`
(`di/DataModule.kt:259-274`).

---

## 14. Metadata fetch path (non-stream NewPipe extraction in this subsystem)

`ExtractorClient` interface: `fetchVideoMetadata` / `fetchChannelMetadata` /
`fetchPlaylistMetadata`, batch by ids (`data/extractor/ExtractorClient.kt:3-9`). Implementation in
`NewPipeExtractorClient`:

- Shared fetch skeleton: per-id cache read → misses loaded serially → cache write; failures per-id
  are swallowed (metrics only), cancellation rethrown (`:256-304`).
- `MetadataCache`: **TTL 15 min, max 200 entries per bucket**, oldest-pruned
  (`di/DataModule.kt:172-173`; `data/extractor/cache/MetadataCache.kt`).
- Video: `StreamInfo.getInfo(extractor)` (no explicit `fetchPage()` here, unlike the stream path);
  `streamType == NONE` → null; maps title/uploader/description/best thumbnail (largest by
  height-else-width, `:384-395`)/duration>0/viewCount≥0 (`:306-337`).
- Channel: `getChannelExtractor` + `fetchPage()` + `ChannelInfo.getInfo`; subscriberCount only when
  ≥ 0; `videoCount` always null (`:339-360`).
- Playlist: `getPlaylistExtractor` + `fetchPage()` + `PlaylistInfo.getInfo`; `streamCount` clamped
  into Int (`:362-382`, `:654-658`).
- Id expansion before LinkHandler creation: bare `UC…` → `channel/UC…`, other bare names → `c/…`;
  playlist `PL/UU/OL…` → `playlist?list=…`; candidates tried in order, first parseable wins
  (`:397-446`).
- Consumer in this subsystem: player metadata hydration (§8.4). (Channel/playlist detail and the
  Me feed have their own repositories — separate Phase-2 briefs.)

---

## 15. Error taxonomy → user-facing states

### 15.1 What Android actually distinguishes

| Condition | Where classified | User-facing result |
|---|---|---|
| Backend 410 on the HEAD gate | `GlobalStreamResolver.kt:387-393` → `ContentUnavailableException` | `StreamState.ContentUnavailable`: overlay title `content_unavailable_title` = "Content not available", message `content_unavailable_message` = "This content is no longer available. It may have been removed by an admin or by YouTube.", retry/refresh buttons **hidden** (`PlayerFragment.kt:2075-2085`; strings `res/values/strings.xml:207-208`). No retries — outcome deterministic (`PlayerViewModel.kt:1478-1495`). Playlist mode: auto-skip up to 3. |
| Any extraction failure / timeout after 3 attempts | `resolveWithRetry` catch-all (`:1494-1518`) | `StreamState.Error(player_stream_error)` = "Playback unavailable. Please try again later." (`strings.xml:52`), overlay with Retry + Refresh buttons (`PlayerFragment.kt:2064-2073`). |
| Resolver returned null (timeout/no result) after 3 attempts, or no selection derivable | `:1521-1545` | `StreamState.Error(player_stream_unavailable)` = "This video is not available for playback." (`strings.xml:53`). |
| Loading | — | status text `player_status_resolving` = "Resolving stream…" (`strings.xml:51`; `PlayerFragment.kt:2059-2061`). |
| Recovery budget exhausted / refresh blocked while not playing | `PlayerFragment.kt:2093-2103,2147-2149` | `RecoveryExhausted` overlay, `player_recovery_exhausted_message` = "Unable to recover playback automatically. Please try again." (`strings.xml:101-103`). |
| Refresh rate-limited while playback continues | `PlayerFragment.kt:2186-2202` | toast `player_refresh_rate_limited` = "Please wait a moment before refreshing again". |

### 15.2 What is deliberately NOT distinguished

grep across `android/app/src/main` finds **zero** catch sites for NewPipe's
`ContentNotAvailableException`, `AgeRestrictedContentException`, `GeographicRestrictionException`,
`PrivateContentException`, `PaidContentException`, or `AccountTerminatedException` — they all
surface as `ExtractionException` subclasses and collapse into the generic retry→Error path above.
`ReCaptchaException` is handled only inside `RateLimitedDownloader` (cooldown trip + rethrow,
§6.3). The pattern-based classifier in `player/StreamRequestTelemetry.kt:40-77`
(`FailureType { URL_EXPIRED, GEO_RESTRICTED, RATE_LIMITED, UNKNOWN_403, HTTP_ERROR,
NETWORK_ERROR }`) is **telemetry-only** — it feeds failure logs, not UI. Defect-adjacent: an
age-restricted or geo-blocked video therefore burns 3 retries + backoff and then shows the generic
"Playback unavailable" message. Recorded factually; iOS behaviour is an open question (Q3).

---

## 16. Backend endpoints used by this subsystem

| Endpoint | Purpose | Citation |
|---|---|---|
| `HEAD /api/v1/videos/{id}` / `channels/{id}` / `playlists/{id}` | availability gate (2xx ok, 404 fail-open, 410 hard block) | `ContentApi.kt:46-53`, §5.2 |
| `GET /api/v1/dub-potoken?videoId=` | videoId-bound GVS poToken for dub audio (release path) | `DubAudioResolver.kt:119-132` |

There is **no** backend stream/watch/resolve endpoint: grep of `data/source/ContentService.kt` and
`RetrofitContentService.kt` for stream/watch finds only doc comments (`ContentService.kt:38,69`).
All media resolution is on-device.

---

## 17. NewPipeExtractor contracts relied on (guide + code)

From `docs/library-guides/newpipe-extractor.md` and the call sites:

1. `NewPipe.init(downloader, localization, contentCountry)` once, with a custom `Downloader`
   implementation (guide "Initialization"; `NewPipeExtractorClient.kt:910-916`). The `Downloader`
   abstract's `execute(Request): Response` is synchronous and may throw
   `IOException`/`ReCaptchaException` (`OkHttpDownloader.kt:24-25`).
2. `ServiceList.YouTube` service accessor and `Youtube*LinkHandlerFactory.getInstance().fromId(id)`
   for URL-less extraction (`:64-67`, `:128`, `:309`, guide "LinkHandler").
3. **`fetchPage()` before reading extractor data** (guide "Best Practices" #1). The stream path
   calls it explicitly before `StreamInfo.getInfo(extractor)` to get ALL formats (`:149-151`);
   channel/playlist metadata do too (`:343`, `:366`).
4. `-1`/`0` sentinel checks for unavailable numeric data (guide #5): viewCount ≥ 0, duration > 0,
   subscriberCount ≥ 0 (`:326-328`, `:350`).
5. `StreamType` enum incl. `LIVE_STREAM`/`AUDIO_LIVE_STREAM`/`NONE` (`:624-626`, `:321`).
6. `Image` lists with width/height for best-thumbnail choice (`:384-395`).
7. YouTube-specific statics NOT in the guide but load-bearing:
   `YoutubeStreamExtractor.setFetchIosClient(Boolean)` (global client toggle, `:960-966`),
   `YoutubeStreamExtractor.setPoTokenProvider` (`:925`),
   `PoTokenProvider`/`PoTokenResult`/`InnertubeClientRequestInfo`/`YoutubeParsingHelper`
   (`WebViewPoTokenProvider.kt:9-13,166-180`).
8. Per-stream fields beyond the guide: `stream.itagItem`, `initStart/initEnd/indexStart/indexEnd`,
   `deliveryMethod`, `codec`, `isVideoOnly()`, `audioLocale`/`audioTrackName`/`audioTrackType`
   (`:487-499`, `:534-581`).
9. Exceptions: only the base `ExtractionException` and `ReCaptchaException` are branched on
   (§15.2); the finer taxonomy in the guide is unused.

For iOS none of this library exists — InnerTubeKit must reimplement: innertube `/player` calls per
client (iOS client for the adaptive ladder, fallback client for muxed), visitorData bootstrap,
SegmentBase range extraction, HLS/DASH manifest URLs, live detection, subtitle listing, and the
UA-binding rule (§4.4). The dub path (§12) is already a from-scratch innertube client and is the
closest in-repo blueprint.

---

## 18. Feature flags affecting resolution

`player/PlaybackFeatureFlags.kt` — runtime override (dev settings) → build-time default; overrides
cleared on app version change (`:100-116`). Defaults from `android/app/build.gradle.kts`:

| Flag | Default | Effect | Citation |
|---|---|---|---|
| `isIosFetchEnabled` | **ON** | iOS client fetch; "the android client is SABR-gutted to a single 360p muxed stream, so iOS must stay on" | gradle `:79-87`; flags `:159-160` |
| `isClientRotationEnabled` | ON | per-video IOS→ANDROID failure rotation (§3.4) | gradle `:107`; flags `:183-184` |
| `isMpdPrefetchEnabled` | ON | MPD pre-generation during tap prefetch | gradle `:101-105`; flags `:148-149` |
| `isPredictivePrefetchEnabled` | **OFF** | scroll-attach prefetch (rate-limit hazard, §7.3) | gradle `:110-118`; flags `:186-187` |
| `isSegmentPreloadEnabled` | ON | pre-buffer lowest track after prefetch | gradle `:119-123`; flags `:189-190` |
| `isTtlWatcherEnabled` | ON | proactive MPD TTL refresh | gradle `:121-123`; flags `:195-196` |
| `isNeverFreezeAbrEnabled` | ON | (playback-side ABR guard) | gradle `:120-123`; flags `:192-193` |

---

## 19. Timeout / TTL / budget summary (single reference table)

| Constant | Value | Citation |
|---|---|---|
| Resolved-streams cache TTL / size | 30 min / 50 LRU, live never cached | `NewPipeExtractorClient.kt:1025,1038-1044` |
| Stream URL TTL / live proactive threshold | 1 h / 50 min | `StreamModels.kt:127,136` |
| Global resolver default timeout | 20 s | `GlobalStreamResolver.kt:83` |
| Player extraction timeout | 20 s | `PlayerViewModel.kt:1778` |
| Player retry ladder | 3 attempts, 1/2/4 s backoff | `:1776-1777,1503-1508` |
| Tap-prefetch timeout / player await | 8 s / 3 s | `StreamPrefetchService.kt:118-119` |
| Prefetch caches (service / VM queue) | 5 FIFO / 4, TTL 30 s each | `:120,133`; `PlayerViewModel.kt:196,1803` |
| Metadata cache | 15 min / 200 per bucket | `DataModule.kt:172-173` |
| MPD registry TTL / watcher fire point | 15 min / 90 % | `SyntheticDashMpdRegistry.kt:43`; `MpdTtlWatcher.kt:18` |
| ExtractionRateLimiter | 30 s min interval; 3/5 min per video; 10/min global; AUTO_RECOVERY 2 reserved+bypass; proactive 2/video + 10/min global; manual backoff 2→60 s | `ExtractionRateLimiter.kt:49-90` |
| Token bucket (background HTTP) | 20 cap, 1/30 s refill, 5 reserve, non-blocking | `GlobalNewPipeRateLimiter.kt:135-140` |
| Cooldown escalation | 1/4/12/24 h in 24 h window; 7-day clean reset | `CooldownState.kt:24-34,64-66` |
| Client rotation state TTL | 30 min | `YoutubeClientRotator.kt:19` |
| Stall watchdog | VOD 6 s / live 45 s | `PlayerFragment.kt:4265,4270` |
| poToken attempts / nsig attempts | 3 / 3 | `WebViewPoTokenProvider.kt:208-213`; `NsigSolver.kt:141` |
| Auto-skip cap (playlist) | 3 consecutive | `PlayerViewModel.kt:1783` |

---

## 20. Defects and oddities noted factually (no iOS decision made here)

- **defect: NewPipe exception taxonomy unused** — age-restricted/geo-blocked/private videos retry
  3× then show a generic error (§15.2).
- **defect(-ish): shorts binds ride PLAYER priority** even for pages the pager binds off-screen —
  `PlayerBinder` calls `playerRepository.resolveStreams` with the interface default
  (`PlayerBinder.kt:404`; default at `PlayerRepository.kt:55`), bypassing every gate; the KDoc's
  "only live playback earns PLAYER" rule is honoured by the regular player but shorts page binds
  are indistinguishable from active watching here.
- **stale comments**: "NewPipeExtractor 0.26.2" in `WebViewPoTokenProvider.kt:19` and
  `DubAudioResolver.kt:30` vs actual v0.26.5 (`build.gradle.kts:416`).
- **dead code**: `AndroidVrStreamResolver` + `ExtractionClient.ANDROID_VR` production paths (§1).
- **known gap (documented as deliberate)**: cold non-prefetched opens get no MPD-TTL watcher —
  reactive 403 recovery only (`DashSourceBuilder.kt:276-285`).
- **shared-handle race, documented**: client-selection synchronization covers the setting apply
  only, not concurrent `fetchPage()`s; accepted because single-flight makes same-video concurrency
  rare (`NewPipeExtractorClient.kt:136-138,312-315`).
- **TOCTOU window, documented + accepted**: one request can slip past a cooldown trip between the
  post-acquire re-check and `delegate.execute` (`RateLimitedDownloader.kt:76-88`).
- **hardcoded innertube constants** in the dub path: MWEB client version `2.20250120.00.00`,
  fallback `SIGNATURE_TIMESTAMP = 20606` (`DubAudioEnumerator.kt:139-162`) — will rot as YouTube
  updates.
- **Localization pinned to US** for all extraction (`NewPipeExtractorClient.kt:68-69`) — metadata
  (e.g. hydrated titles/descriptions) always comes back in YouTube's en-US variants regardless of
  app locale.

---

## 21. Open questions

**Q1 — What does iOS extract WITH?** Android leans on NewPipeExtractor for the innertube protocol
(client payloads, throttling params handled by the library, format parsing). iOS has no equivalent
dependency in scope; the dub path (§12) proves a from-scratch innertube client is viable but it is
audio-only and MWEB-specific. Decision needed: pure-Swift InnerTubeKit mirroring NewPipe's iOS
client fetch (§3), or a different extraction source. This brief documents WHAT must come out of it
(§4's `ResolvedStreams`), not HOW.

**Q2 — poToken/nsig on iOS.** Android's poToken is a WKWebView-equivalent BotGuard run (§13) and is
currently *not* on the critical path for main playback (`WebViewPoTokenProvider.kt:158-165`), but
it IS mandatory for dub audio (server pot, §12.2) and history shows YouTube ratchets enforcement
(ANDROID_VR retirement §1). Does iOS Phase 2 include the dub-audio feature at all, and if so does
it reuse `/api/v1/dub-potoken` + a WKWebView nsig solver?

**Q3 — Error granularity.** Mirror Android's collapse of NewPipe's taxonomy into generic
retry→Error (§15.2), or classify age-restricted/geo-blocked/private during extraction (iOS will be
parsing playabilityStatus itself, so the information is available for free)? Android's behaviour
wastes 3 retries + ~7 s of backoff on deterministic failures.

**Q4 — Availability-gate parity.** The HEAD gate's 404-fail-open / 410-hard-block semantics and the
`sourceChannelId` → CHANNEL-check switch (§5.2) are backend curation policy. Confirm iOS uses the
identical HEAD endpoints and that "fail-open on transport errors" (offline playback of cached
videos) is wanted on iOS, where there is no download cache in Phase 2.

**Q5 — Priority lanes and the shorts PLAYER default.** Port the 4-lane system + cooldown as-is, or
simplify? If ported, does iOS fix the shorts default-priority leak (§20) or replicate it? Note the
whole lane system only has teeth for BACKGROUND_REFRESH traffic (§6.3) — on iOS Phase 2 the only
BACKGROUND_REFRESH producers would be tap-prefetch and queue-prefetch.

**Q6 — Predictive prefetch.** Ship the tap-prefetch design (§7.1–7.2) but the scroll-attach
controller is OFF on Android with a written warning (§7.3). Does iOS implement it at all (dormant
code) or drop it until the Android-side capping work lands?

**Q7 — Client rotation freshness.** The IOS→ANDROID order, "ANDROID = muxed 360p itag 18 only" and
"iOS returns the full ladder once poToken'd" are empirical claims dated 2026-08-18 in comments
(§3.4, gradle `:79-87`). Before freezing InnerTubeKit's client table, re-probe live YouTube — this
area has flipped twice in the repo's own history (VR fast path added, then removed).

**Q8 — US-pinned localization.** Mirror `Locale.US`/"US" (§2) for parity, or use the device locale
now that iOS is a fresh implementation? Affects hydrated metadata language and possibly geo
behaviour; Android leaves no rationale comment for the pin.

**Q9 — Live refresh cadence.** The 50-min proactive live re-resolve + seamless-swap event (§8.5)
presumes URLs minted at resolve time expire ~1 h. If iOS plays live via the HLS manifest URL
(Android's preferred live source, §8.5), the manifest itself refreshes segments — confirm whether
the proactive re-resolve is still needed on iOS or is an Android artifact of its progressive/DASH
fallback paths.

**Q10 — `forceRefresh` only on attempt 1.** In `resolveWithRetry`, retries 2–3 of a force-refresh
drop the flag (`PlayerViewModel.kt:1474-1477`), so they can be served by the very cache the refresh
meant to bypass (the cache was only overwritten if attempt 1 succeeded — but attempt 1 failing is
why we're retrying, and the stale entry survives per §3.2). Deliberate fallback or defect? iOS must
pick one and document it.
