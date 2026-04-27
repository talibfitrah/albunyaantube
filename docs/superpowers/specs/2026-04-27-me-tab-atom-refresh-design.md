# Me Tab — ATOM Feed Refresh + Subscription Cap (Design v2)

**Status**: Proposal, awaiting approval
**Author**: Claude (paired with user)
**Date**: 2026-04-27
**Supersedes**: [`2026-04-27-me-tab-safe-refresh-design.md`](./2026-04-27-me-tab-safe-refresh-design.md)
**Decisions baked in**: A1 (ATOM feeds), B1 (no tier classifier), C2 (30 channels + unlimited playlists), D1 (Player bypasses bucket + cooldown), E2 (favorites row above Shorts), F2 (paginated videos grid, PAGE_SIZE = 20)

---

## 1. The Pivot

The previous design refreshed the Me feed by scraping YouTube's HTML channel pages via NewPipeExtractor. Dual-voice review (Codex + independent subagent) flagged that approach as needlessly risky when YouTube exposes a static ATOM feed per channel that has been stable for over a decade and is what NewPipe itself uses internally for `getFeedUrl()`.

**ATOM endpoint**: `https://www.youtube.com/feeds/videos.xml?channel_id=<UCxxxx>`

| Property | NewPipe scraping (old design) | ATOM polling (this design) |
|---|---|---|
| Per-fetch cost | ~3 requests (ChannelInfo + Videos tab + Shorts tab) | **1 request** (static XML) |
| Per-fetch latency | 2-5 s | 200-500 ms |
| JS execution | Required (NewPipe extracts via player JS for some fields) | **None** |
| CAPTCHA risk | High (subject to YouTube fingerprinting) | **Negligible** (10+ years of stable XML) |
| Items returned | Up to 30 (Videos + Shorts) | Up to 15 (most recent) |
| Metadata richness | Title, thumb, duration, views, upload date, isShort | Title, thumb, upload date, isShort (URL-pattern), description |
| Missing vs old | — | Duration, view count |
| Stability across YouTube changes | Frequent breakage with NewPipe upstream | ~10 years unchanged |

**The trade-off**: lose duration + view count in the Me feed grid. We hide both columns; tapping a video opens the player which still uses NewPipe and gets full metadata there.

**Net design size**: ~50% smaller than the previous draft. No tier classifier, no Room v3 migration, no channel_tier_state table, no complex cooldown escalation for feed refresh (still needed for NewPipe paths in Home/Search/Player).

---

## 2. Goals & Non-Goals

### Goals

1. Replace `NewPipeChannelFeedFetcher` with `AtomChannelFeedFetcher` for Me-tab feed refresh.
2. Cap subscriptions at **30 channels + unlimited playlists** (playlists are free — they don't refresh).
3. Move refresh to background `WorkManager` so opening the Me tab is instant.
4. Round-robin channel selection (no tier classifier — round-robin handles 30 channels in 6 ticks).
5. Apply rate-limiter + cooldown to **NewPipe paths only** (Home, Search, Player, Channel detail). ATOM has its own much-simpler backoff.
6. Support HTTP `If-None-Match` (ETag) and `If-Modified-Since` for ATOM — most ticks return `304 Not Modified` with zero body.
7. Custom `RateLimitedDownloader` for NewPipe paths catches `ReCaptchaException` and HTTP 429 at the right interception point.
8. **Surface favorited videos directly on the Me tab** as a horizontal row above Shorts, sourced from existing `favorite_videos` table. Zero refresh cost (favorites are user-curated snapshots).
9. **Paginate the latest-videos grid** with `PAGE_SIZE = 20`, matching every other long list in the app. Uses Room `PagingSource` from the existing `channel_video_cache` table.

### Non-Goals

- Push notifications (would need PubSubHubbub hub + backend or FCM).
- Backend involvement of any kind.
- Replacing NewPipe for player extraction or search (mandatory there).
- Showing duration/view count in the Me feed grid (deferred — can be added back via lazy NewPipe enrichment if telemetry shows users want it).
- Replacing the existing standalone Favorites screen (still reachable via Settings → Library → Favorites; the Me-tab row links to it via "See all").
- Pagination for the Favorites row itself — it's a horizontal "highlights" strip showing the most recent ~20 favorites; the full Favorites screen remains the canonical paginated list.

---

## 3. Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                    UI Layer                               │
│  MeFragment                                               │
│  ├── ConcatAdapter                                        │
│  │   ├── MeChipsAdapter        (chips: channels + lists) │
│  │   ├── MeFavoritesAdapter    (NEW: favorites row)      │
│  │   ├── MeShortsAdapter       (shorts row)              │
│  │   └── MeVideosPagingAdapter (NEW: paged videos grid)  │
│  └── MeViewModel                                          │
│      ├── observeFeed()                (cache, paged)     │
│      └── observeFavorites()           (NEW, from Room)   │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼ (read-only)
┌──────────────────────────────────────────────────────────┐
│                 Cache Layer (Room — unchanged)            │
│  channel_video_cache, channel_feed_refresh_state,         │
│  subscribed_channels, saved_playlists, favorite_videos    │
└──────────────────────────────────────────────────────────┘
                          ▲ (writes)
                          │
┌──────────────────────────────────────────────────────────┐
│              RefreshSubscriptionsWorker                   │
│  • single PeriodicWorkRequest, period = 60 min            │
│  • foreground OneTimeWorkRequest from MeFragment.onResume │
│    if cache stale (> 30 min since last successful tick)   │
│  • picks 5 channels via round-robin (oldest first)        │
│  • each fetch: AtomChannelFeedFetcher.fetchLatest(url)    │
│  • atomic withTimeout(8.minutes) overall budget           │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼
┌──────────────────────────────────────────────────────────┐
│              AtomChannelFeedFetcher                       │
│  • OkHttp GET feeds/videos.xml?channel_id=...             │
│  • XmlPullParser → List<ChannelFeedItem>                  │
│  • ETag/If-Modified-Since cache → 304 = empty list        │
│  • per-channel exponential backoff on 5xx/IOException     │
└──────────────────────────────────────────────────────────┘

(Separate subsystem — used by Home/Search/Player ONLY, not feed refresh:)

┌──────────────────────────────────────────────────────────┐
│           GlobalNewPipeRateLimiter (token bucket)         │
│           CooldownState (CAPTCHA / 429 escalation)        │
│           RateLimitedDownloader (NewPipe Downloader sub)  │
└──────────────────────────────────────────────────────────┘
```

---

## 4. Components

### 4.1 `AtomChannelFeedFetcher` (NEW — replaces `NewPipeChannelFeedFetcher` for Me feed)

**Location**: `data/me/AtomChannelFeedFetcher.kt`

**Implements**: existing `ChannelFeedFetcher` interface — no signature change. The repo (`MeFeedRepository`) and DI (`MeModule`) need only the impl swap.

**Behavior**:
1. Extract channel ID from `channelUrl` (`https://www.youtube.com/channel/UCxxxx` → `UCxxxx`). If extraction fails, throw `IllegalArgumentException` so the per-channel error path persists the failure and continues.
2. Build URL: `https://www.youtube.com/feeds/videos.xml?channel_id=$id`.
3. GET via injected `OkHttpClient` (the existing one in `NetworkModule`). Headers:
   - `If-None-Match: <stored etag>` if available
   - `If-Modified-Since: <stored last-modified>` if available
   - `Accept: application/atom+xml`
   - `User-Agent: <existing app UA>`
4. On HTTP 304: return `emptyList()`. Caller treats this as success-with-no-new-items.
5. On HTTP 200: parse with `XmlPullParserFactory.newInstance().newPullParser()`. Extract for each `<entry>`:
   - `yt:videoId` → `videoId` (11-char ID)
   - `title` → `title`
   - `media:thumbnail/@url` → `thumbnailUrl`
   - `published` (ISO-8601) → `uploadedAt` (millis)
   - `link/@href` contains `/shorts/` → `isShort = true`, else inferred from URL pattern
   - `durationSeconds = null`, `viewCount = null` (ATOM doesn't expose these — UI hides the columns)
6. Persist `etag` and `last-modified` headers per channel in `channel_feed_refresh_state` (existing table — schema additions in §6).
7. On HTTP 429 / 5xx / IOException: throw — caller persists the error in refresh state.

**Why not OkHttp's `Cache`**: we want explicit ETag handling so we can record "no changes" as a successful tick (advance `lastSuccessfulFetchAt`). OkHttp's HTTP cache is opaque about this.

**Concurrency**: stateless. Multiple parallel calls allowed — but the worker runs sequentially anyway.

**Tests**:
- Parses real ATOM samples (commit a few golden fixtures)
- Returns `emptyList()` on 304
- Throws on 5xx
- Survives malformed XML (returns whatever parsed before the error)
- Handles channel-with-no-uploads (empty feed, valid response)

### 4.2 `SubscriptionLimitGuard` (NEW)

**Location**: `data/subscriptions/SubscriptionLimitGuard.kt`

**Cap**: 30 channels. **Playlists are unlimited** — they don't refresh, they don't consume YouTube budget.

```kotlin
sealed class SubscribeResult {
    object Success : SubscribeResult()
    data class LimitReached(val current: Int, val cap: Int) : SubscribeResult()
}

@Singleton
class SubscriptionLimitGuard @Inject constructor(
    private val channels: SubscribedChannelDao,
    private val db: AppDatabase,
) {
    suspend fun trySubscribe(channel: SubscribedChannel): SubscribeResult
    // No equivalent for savePlaylist — playlists pass through SubscriptionRepository
    // unchanged with no cap check.
}

object SubscriptionLimits {
    const val CHANNEL_CAP: Int = 30
}
```

**Transaction**: count check + insert in same `withTransaction` block. Idempotent re-subscribe returns `Success` without incrementing.

**UX**: Subscribe button toggle on `ChannelDetailFragment` calls the guard; on `LimitReached` shows snackbar "You're following 30 channels (the limit). Unsubscribe one to follow this channel." Strings localized en/ar/nl.

### 4.3 `RefreshSubscriptionsWorker` (NEW)

**Location**: `data/me/work/RefreshSubscriptionsWorker.kt` (CoroutineWorker)

**Schedule** (single periodic, simpler than v1 design's two-periodic toggle — addresses Codex finding A1):

```kotlin
val periodicRequest = PeriodicWorkRequestBuilder<RefreshSubscriptionsWorker>(60, MINUTES)
    .setConstraints(Constraints.Builder().setRequiredNetworkType(CONNECTED).build())
    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, MINUTES)
    .build()

WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
    "me_refresh_periodic",
    ExistingPeriodicWorkPolicy.KEEP,  // never cancel + replace; lifecycle changes don't tear it down
    periodicRequest
)
```

Foreground burst (replaces the cancelled "two periodic" pattern from v1 design):
- `MeFragment.onResume()` → if `lastSuccessfulFetchAt` for any channel > 30 min old → enqueue `OneTimeWorkRequest<RefreshSubscriptionsWorker>` with unique name `"me_refresh_oneshot"` and policy `KEEP`.
- Pull-to-refresh → same `OneTimeWorkRequest`, but with extras `force = true`.

**Per-tick budget**: 5 channels (round-robin, oldest `lastSuccessfulFetchAt` first).

**Worker body** (simplified, no tier logic):
```kotlin
override suspend fun doWork(): Result = withTimeout(8.minutes) {
    val force = inputData.getBoolean("force", false)
    val budget = if (force) 30 else 5
    val candidates = subscriptionRepository.getSubscribedChannels()
        .sortedBy { refreshStateDao.get(it.channelId)?.lastSuccessfulFetchAt ?: 0L }
        .take(budget)
    if (candidates.isEmpty()) return@withTimeout Result.success()

    for (channel in candidates) {
        ensureActive()
        delay(Random.nextLong(800L, 2400L))  // jitter
        runCatching { runFetch(channel) }
            .onFailure { t ->
                if (t is CancellationException) throw t  // F6 discipline preserved
                refreshStateDao.upsert(failureRow(channel, t))
            }
    }
    Result.success()
}
```

**No semaphore needed**: ATOM fetches are sequential; no concurrent network pressure to manage.

**Wall-clock math** (addresses Codex finding A6):
- 5 channels × (500 ms ATOM fetch + 2.4 s jitter) ≈ **15 s per tick**
- `withTimeout(8.minutes)` is generous overhead for slow networks
- Way under WorkManager's 10-minute kill threshold

### 4.4 `RateLimitedDownloader` (NEW — for NewPipe paths only, not ATOM)

**Location**: `data/extractor/RateLimitedDownloader.kt`

**Purpose**: the missing interception point for `ReCaptchaException` / HTTP 429 detection (FINDING 3 from review).

**Implements**: `org.schabi.newpipe.extractor.downloader.Downloader` (same interface as the current `DownloaderImpl`).

**Wraps**: existing `DownloaderImpl` — delegates the actual HTTP work, adds two responsibilities:
1. Acquires token from `GlobalNewPipeRateLimiter` before each call (priority is set by the caller via thread-local or coroutine context).
2. Catches `ReCaptchaException` and HTTP 429 responses → calls `cooldownState.trip(reason)`.

```kotlin
class RateLimitedDownloader @Inject constructor(
    private val delegate: DownloaderImpl,
    private val rateLimiter: GlobalNewPipeRateLimiter,
    private val cooldownState: CooldownState,
) : Downloader() {

    override fun execute(request: Request): Response {
        val priority = currentPriority() // PLAYER bypasses below

        if (priority != Priority.PLAYER) {
            if (cooldownState.isTrippedSync()) {
                throw IOException("NewPipe cooldown active until ${cooldownState.untilMs()}")
            }
            runBlocking { rateLimiter.acquire(priority) }
        }

        try {
            val response = delegate.execute(request)
            if (response.responseCode() == 429 && priority != Priority.PLAYER) {
                runBlocking { cooldownState.trip(IOException("HTTP 429"), now()) }
                throw IOException("HTTP 429 — cooldown tripped")
            }
            return response
        } catch (e: ReCaptchaException) {
            if (priority != Priority.PLAYER) {
                runBlocking { cooldownState.trip(e, now()) }
            }
            throw e
        }
    }

    private fun currentPriority(): Priority = NewPipePriorityContext.current.get() ?: Priority.USER_FOREGROUND
}
```

**Priority resolution**: thread-local `NewPipePriorityContext` set by callers:
- `PlayerFragment` extraction → `withNewPipePriority(PLAYER) { ChannelInfo.getInfo(...) }`
- `HomeViewModel`, `FeaturedListViewModel`, `SearchExtractor` callers → `USER_FOREGROUND`
- (Me-tab refresh doesn't go through NewPipe at all post-A1, so no priority needed there.)

**D1 implementation**: Player bypasses **both** the rate limiter AND the cooldown check explicitly. Spelled out in code, not implicit.

### 4.5 `GlobalNewPipeRateLimiter` (NEW — same as v1 but scope is now NewPipe paths only)

| Knob | Value |
|---|---|
| Bucket capacity | 20 tokens |
| Refill rate | 1 token / 30 s |
| Player priority | bypasses bucket entirely |
| Acquire timeout | 10 s for Home/Search; 60 s never used (no background NewPipe paths) |

Smaller bucket than v1 because Me-tab refresh no longer consumes from it.

### 4.6 `CooldownState` (NEW — same as v1 but scope is NewPipe paths only)

| Trip count in last 24 h | Cooldown duration |
|---|---|
| 1 | 1 h |
| 2 | 4 h |
| 3 | 12 h |
| 4+ | 24 h |

Reset to 0 trips after 7 consecutive days of clean fetches.

**Player bypasses cooldown** (D1) — explicit.

**Persistence**: DataStore Preferences. Survives app restarts (FINDING — Codex critical, addressed).

### 4.7 `AppLifecycleTracker` (NEW)

Single `ProcessLifecycleOwner` observer registered once in `AlBunyaanApplication.onCreate()`. Exposes `isForeground: StateFlow<Boolean>`. Used only for telemetry now (the worker is single-periodic, no toggling).

### 4.8 `MeFavoritesAdapter` (NEW — favorites row in Me tab)

**Location**: `ui/me/MeFavoritesAdapter.kt`

**Purpose**: surfaces favorited videos as a horizontal scrollable strip on the Me tab, sandwiched between the chips row and the Shorts row.

**Source**: `FavoriteVideoDao.observeAll()` (already exists, returns `Flow<List<FavoriteVideo>>` ordered by `addedAt DESC`).

**Display contract**:
- Show the **20 most recently added** favorites in the row. (The full list lives on the existing Favorites screen.)
- Each tile: thumbnail + title (1-2 lines) + channel name. Same visual size as Shorts tiles, slightly wider (16:9).
- Tap → Player (with `videoId` extra). Existing `playVideo` path in `MeFragment` already handles this — no new navigation.
- Long-press → snackbar with "Remove from favorites" action (parity with the existing Favorites screen).
- A trailing "See all →" tile at the end of the row → navigates to the existing Favorites screen.
- Empty case: row is **hidden entirely** (`visibility = GONE`) when there are zero favorites — no "no favorites yet" placeholder. Keeps the Me tab clean for users who don't use the favorites feature.

**No refresh logic.** Favorites are mutated only by user toggles (heart button on Player, swipe-to-delete on Favorites screen). The Flow handles updates reactively.

### 4.9 `MeVideosPagingAdapter` + Room `PagingSource` (NEW — pagination for the videos grid)

**Location**:
- Adapter: `ui/me/MeVideosPagingAdapter.kt` (replaces current `MeVideosAdapter`)
- DAO: extend `ChannelVideoCacheDao` with a `PagingSource` query method
- Repo: extend `MeFeedRepository` with `pagedFeed(filterChannelId: String?): Flow<PagingData<ChannelVideoCache>>`

**Why paging here**: the Me feed already pulls from local Room — no network paging needed — but the *display* layer benefits from incremental rendering when the cache holds many items (heavy users with all 30 channels active can have ~600+ rows in the 14-day window).

**DAO addition**:
```kotlin
@Query("""
    SELECT * FROM channel_video_cache
    WHERE channelId IN (:channelIds)
      AND uploadedAt >= :cutoffMs
      AND (:filterChannelId IS NULL OR channelId = :filterChannelId)
    ORDER BY uploadedAt DESC
""")
fun pagingForChannels(
    channelIds: List<String>,
    cutoffMs: Long,
    filterChannelId: String?,
): PagingSource<Int, ChannelVideoCache>
```

**Page config**:
```kotlin
PagingConfig(
    pageSize = 20,                    // matches the rest of the app
    initialLoadSize = 40,             // fill phone screen + 1 prefetch
    prefetchDistance = 10,
    enablePlaceholders = false,       // Room cache loads fast; placeholders hurt UX here
)
```

**Filter behaviour**: when a chip is tapped (channel filter), the `pagedFeed(filterChannelId = X)` flow is re-collected with the new param. `PagingDataAdapter` automatically resets and re-fetches.

**Shorts row stays non-paged** (display cap of ~10 most recent shorts is enough — same horizontal-row pattern as today).

---

## 5. Failure Handling Matrix

| Signal | Fetch path | Action |
|---|---|---|
| ATOM HTTP 304 | ATOM | Treat as success (no new items). Advance `lastSuccessfulFetchAt`. |
| ATOM HTTP 200 + parsed items | ATOM | Cache rows, advance `lastSuccessfulFetchAt`. |
| ATOM HTTP 200 + zero entries | ATOM | Treat as success-with-empty (channel hasn't posted recently). Advance `lastSuccessfulFetchAt`. **Empty result protection from current code (FEED_WINDOW_MS) preserved.** |
| ATOM HTTP 429 | ATOM | Per-channel exponential backoff: skip this channel for 1 h, 4 h, 24 h on repeated trips. **Does NOT trip the NewPipe global cooldown** — different subsystem. |
| ATOM HTTP 5xx | ATOM | Per-channel backoff (5 min, 30 min, 2 h). |
| ATOM IOException (network) | ATOM | Skip this tick, retry next. No backoff. |
| ATOM malformed XML | ATOM | Persist parse error, skip channel until next tick. |
| ReCaptchaException | NewPipe (Home/Search) | RateLimitedDownloader trips global cooldown. Player path ignores. |
| HTTP 429 from NewPipe | NewPipe | RateLimitedDownloader trips global cooldown. Player path ignores. |
| ParsingException cluster (3 in 10 min) | NewPipe | Trip global cooldown. |
| Player extraction failure | NewPipe | Show "Couldn't load video" UI. **Never trips cooldown** — playback UX trumps detection hygiene (D1). |

**Counter reset rules** (addresses subagent finding A7):
- ATOM 200 with items > 0 → resets both `consecutiveErrorCount` and `consecutiveEmptyCount` to 0
- ATOM 304 → same as items > 0 reset
- ATOM 200 with empty entries → resets `consecutiveErrorCount` only; `consecutiveEmptyCount++`
- Any error → `consecutiveErrorCount++`

(Tier classifier is gone, but counters still useful for telemetry / per-channel backoff.)

---

## 6. Persistence Schema

### Existing `channel_feed_refresh_state` — additive columns (Room v3)

```kotlin
@Entity(tableName = "channel_feed_refresh_state")
data class ChannelFeedRefreshState(
    @PrimaryKey val channelId: String,
    val lastSuccessfulFetchAt: Long,
    val lastAttemptAt: Long,
    val lastErrorMessage: String?,
    // NEW for ATOM:
    val etag: String? = null,
    val lastModified: String? = null,
    val consecutiveErrorCount: Int = 0,
    val consecutiveEmptyCount: Int = 0,
    val backoffUntilMs: Long? = null,  // per-channel backoff after 429/5xx
)
```

**Migration `MIGRATION_2_3`** (much smaller than v1 design):
```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN etag TEXT")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN lastModified TEXT")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN consecutiveErrorCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN consecutiveEmptyCount INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE channel_feed_refresh_state ADD COLUMN backoffUntilMs INTEGER")
    }
}
```

### `CooldownState` — DataStore Preferences

```
cooldown_until_ms: Long?
cooldown_trip_count_24h: Int
cooldown_last_trip_ms: Long?
cooldown_last_clean_streak_start_ms: Long
```

Persisted (Codex finding addressed).

### No new tables. No tier table. Net schema delta vs current: 5 new columns.

---

## 7. Constants Reference

```kotlin
object MeRefreshTuning {
    // Cap
    const val CHANNEL_CAP: Int = 30  // playlists unlimited (D1)

    // Worker schedule (single periodic)
    const val PERIODIC_PERIOD_MIN: Long = 60L
    const val WORKER_OVERALL_TIMEOUT_MIN: Long = 8L
    const val PER_TICK_BUDGET: Int = 5
    const val PULL_TO_REFRESH_BUDGET: Int = 30
    const val FOREGROUND_STALE_THRESHOLD_MS: Long = 30L * 60_000L

    // Jitter
    const val INTER_FETCH_JITTER_MIN_MS: Long = 800L
    const val INTER_FETCH_JITTER_MAX_MS: Long = 2_400L

    // Per-channel backoff (ATOM)
    val ATOM_429_BACKOFFS: List<Long> = listOf(1L * 3600_000L, 4L * 3600_000L, 24L * 3600_000L)
    val ATOM_5XX_BACKOFFS: List<Long> = listOf(5L * 60_000L, 30L * 60_000L, 2L * 3600_000L)

    // Cache window (unchanged)
    const val FEED_WINDOW_MS: Long = 14L * 24L * 3600_000L
    const val MAX_ITEMS_PER_CHANNEL: Int = 30  // ATOM gives ~15, kept for cache headroom

    // NewPipe rate limiter (Home/Search only)
    const val BUCKET_CAPACITY: Int = 20
    const val BUCKET_REFILL_PERIOD_MS: Long = 30_000L
    const val FG_ACQUIRE_TIMEOUT_MS: Long = 10_000L

    // NewPipe cooldown
    val COOLDOWN_DURATIONS_BY_TRIP_COUNT: List<Duration> =
        listOf(1.hours, 4.hours, 12.hours, 24.hours)
    const val COOLDOWN_RESET_AFTER_CLEAN_DAYS: Int = 7
}
```

---

## 8. UI Changes

### `MeFragment` layout — final stack (top → bottom)

1. Chips row (existing — `MeChipsAdapter`): subscribed channels first, then saved playlists.
2. **NEW: Favorites row** (`MeFavoritesAdapter`). Hidden if `favoriteVideos.isEmpty()`.
3. Shorts row (existing — `MeShortsAdapter`).
4. **Latest videos grid** (paged — `MeVideosPagingAdapter`).

The whole stack stays inside one `RecyclerView` driven by `ConcatAdapter`, so the user gets one smooth vertical scroll past chips → favorites → shorts → into the paginated grid.

### `MeFragment` adapters (`MeVideosPagingAdapter`, `MeShortsAdapter`)
- **Hide duration label.** Was bound from `ChannelFeedItem.durationSeconds` — that field is now always null (ATOM doesn't expose it).
- **Hide view count.** Same reason.
- Layouts (`item_me_videos.xml`, `item_me_shorts.xml`, plus sw600/sw720 variants): set `visibility="gone"` on the duration + view-count TextViews. Or remove them entirely — they were nice-to-haves, the title + thumb + relative-time are the load-bearing fields.

### `MeFavoritesAdapter` layout
- New layout file: `item_me_favorites_row.xml` (the row container itself, registered with `ConcatAdapter`).
- New layout file: `item_me_favorite_video.xml` for each tile. 16:9 thumbnail + title + channel name + heart icon overlay.
- Trailing "See all →" tile uses a separate view type, navigates to existing Favorites screen.
- sw600dp + sw720dp variants: same structure, larger tile size (matches Shorts row scaling).
- Long-press → context menu with "Remove from favorites" (calls `favoritesRepository.remove(videoId)`).

### Pagination UX
- Phone: single column list, 20 items per page, prefetch 10 ahead. Scroll-bottom triggers next page (Paging library handles automatically).
- Tablet (sw600dp): 2-column grid via `GridLayoutManager`, span lookup unchanged.
- TV (sw720dp): 3-column grid, span lookup unchanged.
- No "loading more" spinner needed — Room paging is fast enough that placeholders aren't useful (`enablePlaceholders = false`).
- The `LoadStateAdapter` (Paging 3) is added to the `ConcatAdapter` tail to show a small footer if the underlying Flow ever emits an error (defensive — Room rarely errors).

### `ChannelDetailFragment` Subscribe button
- Toggle calls `subscriptionLimitGuard.trySubscribe()`.
- On `LimitReached` → snackbar via existing `Snackbar.make(binding.root, R.string.me_subscription_cap_reached, LENGTH_LONG)`.
- New strings (en/ar/nl):
  - `me_subscription_cap_reached`: "You're following 30 channels (the limit). Unsubscribe one to follow this channel."
  - Arabic + Dutch translations to be confirmed by user before code lands.

### Settings → "Manage subscriptions" (existing screen)
- New row count badge: "Subscribed channels: {n}/30".
- No change for playlists (no cap).

### Pull-to-refresh
- Now enqueues `OneTimeWorkRequest`, observes `WorkInfo` to clear the spinner.

### First-install UX (subagent finding)
- If `lastSuccessfulFetchAt` is null for all subs (fresh install or no subs yet) → show "Loading your feed..." placeholder above empty state for up to 60 s.
- After 60 s with still no data → fall through to current empty state.

### Cooldown UX
- Only visible if user pull-to-refreshes during cooldown (NewPipe paths only — ATOM doesn't have a global cooldown).
- Snackbar: "YouTube is asking us to wait. Try again in {humanizeDuration(untilMs)}."

---

## 9. Test Strategy

### Unit (jvm)

| Test | Subject |
|---|---|
| `AtomChannelFeedFetcherTest` | Parses golden ATOM fixtures (real samples), 304 returns empty, malformed XML doesn't crash, 5xx throws, channel-with-no-uploads valid empty feed |
| `AtomFeedParserTest` | Lower-level parser unit — separate the parser from the network |
| `SubscriptionLimitGuardTest` | Cap math at 30, idempotent re-subscribe, playlists not counted |
| `GlobalNewPipeRateLimiterTest` | Token bucket refill timing using **injected `TimeSource`** (subagent finding T2) |
| `CooldownStateTest` | Trip duration escalation, 7-day clean streak resets count, persistence across cold restart |
| `RateLimitedDownloaderTest` | ReCaptcha caught, 429 caught, Player priority bypasses both gates |

### Integration (Robolectric)

| Test | Subject |
|---|---|
| `MeFeedRepositoryTest` (extend existing) | Uses fake `AtomChannelFeedFetcher` instead of NewPipe fake; preserves all current concurrency tests including F3/F6; new test for `pagedFeed()` filter changes |
| `MeViewModelTest` (extend existing) | Refresh-on-init removed; cache-only observation; pull-to-refresh enqueues worker; `observeFavorites()` reflects DAO mutations |
| `MeFavoritesAdapterTest` | Empty list → row hidden; non-empty → "See all" tile present at end; long-press → snackbar |
| `MeVideosPagingTest` | Filter change resets paging; PAGE_SIZE = 20; large cache (~500 rows) loads page 1 in <100 ms |
| `RefreshSubscriptionsWorkerTest` | `WorkManagerTestInitHelper`, `TestDriver.setPeriodDelayMet()` to advance virtual time. Cancellation propagates correctly (F6 preserved through new layer). |
| `CooldownEscalationIntegrationTest` (subagent finding T1) | Trip 1h → retry → re-trip → 4h → retry → re-trip → 12h. WorkManager virtual time. |
| `AppDatabaseMigration2to3Test` | `MigrationTestHelper`. Backfill produces NULLs/zeros for new columns; existing rows untouched. |

### Instrumented (Espresso)

| Test | Subject |
|---|---|
| `MeTabSubscriptionCapTest` | Subscribe 30 channels, attempt 31st → snackbar shown |
| `MeTabPullToRefreshTest` | Trip cooldown via developer settings, pull-to-refresh, verify snackbar text |
| `MeRefreshDozeInstrumentedTest` (subagent finding T3) | API 31+ Pixel emulator: `adb shell dumpsys deviceidle force-idle` + `adb shell cmd jobscheduler run` → assert worker still completes within reasonable time. OEM-specific behaviour flagged as known risk in test docstring. |

### Manual QA

- Subscribe 30 channels → 31st blocked
- Save 100 playlists → all succeed (no playlist cap)
- ATOM endpoint hit verified via charles/mitmproxy on staging device
- 304 response observed for unchanged channels (network log confirms zero body bytes)
- Pull-to-refresh during NewPipe cooldown → snackbar shown
- Foreground app for 30 min → worker tick observed in dev log
- Background app for 90 min → worker tick observed
- Force-idle Doze → worker still completes within ~2 h
- Zero favorites → favorites row not visible on Me tab
- Add 5 favorites → row appears, sorted newest first
- Add 25 favorites → row shows 20 + "See all" tile; tile opens Favorites screen
- Favorite a video from Player → Me-tab favorites row updates reactively without re-opening Me
- Scroll Me feed grid past 40 items → page 3 loads smoothly, no jank
- Apply channel filter → grid resets to that channel's videos, paging works inside the filter

---

## 10. Rollout / Migration

### Phased delivery

| Phase | Scope | Days est. |
|---|---|---|
| **P1: ATOM fetcher** | `AtomChannelFeedFetcher` + parser + golden fixture tests | 0.75 d |
| **P2: Cap + UI** | `SubscriptionLimitGuard` + Subscribe button snackbar + Settings counter + i18n strings | 0.5 d |
| **P3: Worker** | `RefreshSubscriptionsWorker` (single periodic + onResume oneshot) + `AppLifecycleTracker` + decommission `MeViewModel.init refresh` + tests | 1 d |
| **P4: Hide duration/views** | Layout changes phone/sw600/sw720 + adapter binding cleanup | 0.25 d |
| **P5: NewPipe rate limiter + cooldown** | `GlobalNewPipeRateLimiter` + `CooldownState` + DataStore + UX wiring + dev settings | 0.75 d |
| **P6: `RateLimitedDownloader`** | Subclass `DownloaderImpl` + thread-local priority context + integrate Home/Search/Player + tests | 0.75 d |
| **P7: Room v3 migration** | `MIGRATION_2_3` (5-column ALTER) + migration test | 0.25 d |
| **P8: Favorites row** | `MeFavoritesAdapter` + layouts (phone/sw600/sw720) + i18n strings + tests | 0.5 d |
| **P9: Paged videos grid** | `MeVideosPagingAdapter` + `PagingSource` DAO query + `MeFeedRepository.pagedFeed()` + `MeViewModel` rewire + tests | 0.5 d |
| **P10: Telemetry + dev settings + Doze instrumented test** | Local event log + dev dialog + ADB-driven instrumented test | 0.5 d |

**Total: ~5.75 dev days** (was 4.75 — added 1 day for E2 + F2).

Plus full review pipeline (Stages 1-7 per `feedback_review_pipeline.md`) ≈ +1.5 d. Realistic shipping target **7-8 calendar days**.

### Migration safety

- Room v2 → v3 = additive columns only. NULL/0 defaults. Trivially reversible.
- No tier table to backfill, so no round-robin starvation issue (subagent finding A5 vanishes — round-robin uses existing `lastSuccessfulFetchAt` which has real values from current production).
- Branch hasn't merged to a stable release. No grandfathering needed (subagent finding A8 — dead-code bullet deleted).
- Behaviour change: opening Me tab no longer triggers a fetch directly. First periodic tick fires within ~30 s of install (worker `setInitialDelay(0)` + jitter). **First-install UX** ("Loading your feed..." placeholder) covers the gap.

### Rollback plan

If ATOM endpoint behaves unexpectedly in QA on real devices:
- `AtomChannelFeedFetcher` and `NewPipeChannelFeedFetcher` both implement `ChannelFeedFetcher`. Toggle the Hilt binding in `MeModule` to fall back. No data loss, no migration churn.
- Cap, rate limiter, cooldown, and worker layers all keep working with either fetcher.

---

## 11. What This Design Solves vs What Remains

### Findings from review now resolved

| Finding | Resolution |
|---|---|
| **F1 (CRITICAL)**: ATOM dismissed | A1 chosen — design is now ATOM-first |
| **F2 (HIGH)**: Tier classifier premature | B1 chosen — tier classifier removed |
| **F3 (CRITICAL)**: ReCaptcha interception undefined | §4.4 specifies `RateLimitedDownloader` subclass of `DownloaderImpl` |
| **F4 (HIGH)**: Player + cooldown collision | D1 chosen — Player bypasses both, explicit in code |
| **F5 (HIGH)**: Worker wall-clock budget | §4.3 — ATOM fetches are 500 ms, 5 channels in 15 s, `withTimeout(8.minutes)` overall |
| Codex A1: Two-periodic toggle race | §4.3 — single periodic + onResume oneshot |
| Codex high: Cooldown persistence | §4.6 — DataStore-backed |
| Subagent A4: Cancellation through new layer | §4.3 — explicit `if (t is CancellationException) throw t` preserves F6 |
| Subagent A5: Migration backfill ordering | Vanishes — no tier table to backfill |
| Subagent A7: Counter reset rules | §5 — explicit reset rules table |
| Subagent A8: Grandfathering dead code | Removed |
| Subagent T1-T4: Test gaps | §9 — `WorkManagerTestInitHelper.TestDriver`, injected `TimeSource`, `MigrationTestHelper`, Doze instrumented test |
| Subagent S1: 30-cap arbitrary | C2 chosen — channel-only cap, playlists unlimited (honest about cost) |
| Subagent S4: Backend escape hatch | Worker depends on `ChannelFeedFetcher` interface — swap to backend impl is one-line change |

### What still remains as risk

- **YouTube could remove the ATOM endpoint.** Probability: low (it's been there 10+ years). Mitigation: rollback to NewPipe fetcher (one-line Hilt swap).
- **YouTube could rate-limit the ATOM endpoint** more aggressively in future. Mitigation: per-channel exponential backoff already designed in (§5).
- **NewPipe-the-library could still get fingerprinted** for the Home/Search/Player paths — but that's now scoped to user-initiated actions, not background polling. The cooldown machinery handles graceful degradation when it happens.
- **Doze mode on aggressive OEMs (Samsung, Xiaomi, Huawei)** can still defer the periodic worker indefinitely. Mitigation: `MeFragment.onResume()` foreground burst means active users always get fresh data when they come back; cache-first UI means stale data is invisible. Documented as known limitation.

---

## 12. Open Questions

1. **Should the Me feed show duration/views ever?** v1 hides both. If telemetry shows users want them: add a lazy NewPipe enrichment pass for visible items only (~5 visible at a time, gated by rate limiter, cancellable on scroll). **Defer to v2 unless user-tested data demands it.**

2. **Pull-to-refresh budget**: this design says full 30. Reasonable for a manual user gesture. **Confirmed.**

3. **Worker initial delay**: 0 (fire immediately on install) vs 60 s (let app warm up first)? **Proposed: 30 s — short enough that fresh installs see data quickly, long enough to let DI graph settle.**

4. **i18n strings for the cap snackbar**: drafted in en. Need ar + nl translations confirmed.

---

## 13. Out of Scope (Future Work)

- Lazy NewPipe enrichment for duration/view count in Me feed (Q1)
- Push notifications via PubSubHubbub hub (would need backend or third-party hub like superfeedr)
- Per-channel notification toggles
- Importing subscriptions from YouTube Takeout
- Tier classifier (defer indefinitely; round-robin is fine at this scale)

---

## 14. Approval Checklist

- [x] Decision A: ATOM-based feed refresh (A1)
- [x] Decision B: No tier classifier in v1 (B1)
- [x] Decision C: 30 channels + unlimited playlists (C2)
- [x] Decision D: Player bypasses bucket and cooldown (D1)
- [x] Decision E: Favorites row above Shorts (E2)
- [x] Decision F: Paginated videos grid, PAGE_SIZE = 20 (F2)
- [x] User approves §4.4 `RateLimitedDownloader` design (only architecturally correct interception point) — locked 2026-04-27
- [x] User approves hiding duration/views in Me feed (UX trade-off for ATOM cost savings) — locked 2026-04-27
- [x] User approves first-install "Loading your feed..." placeholder (UX trade-off for no on-tab-open fetch) — locked 2026-04-27
- [x] User approves long-press → "Remove from favorites" snackbar action on the favorites row (vs tap-only with no inline remove) — locked 2026-04-27
- [ ] **CONTENT DELIVERY** (not a design gate — gathered during implementation): ar + nl translations of new strings: "Favorites", "See all", subscription cap snackbar. English drafts in spec; translations land in P8 (favorites row) + P2 (cap UI). User to provide before merge.

**All design decisions locked.** Implementation plan goes into `docs/superpowers/plans/2026-04-27-me-tab-atom-implementation.md` following the existing per-phase structure.
