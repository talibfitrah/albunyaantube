# Me Tab — Safe Refresh & Subscription Cap (Design)

**Status**: SUPERSEDED 2026-04-27 by [`2026-04-27-me-tab-atom-refresh-design.md`](./2026-04-27-me-tab-atom-refresh-design.md) — autoplan dual-voice review (Codex + independent subagent) flagged the ATOM-feed alternative as a 10x simpler safer path. User approved A1/B1/C2/D1.
**Author**: Claude (paired with user)
**Date**: 2026-04-27
**Successor to**: [`2026-04-14-me-tab-design.md`](./2026-04-14-me-tab-design.md)
**Successor**: [`2026-04-27-me-tab-atom-refresh-design.md`](./2026-04-27-me-tab-atom-refresh-design.md)
**Kept for context, not for implementation.**

---

## 1. Goals & Non-Goals

### Goals

1. **Cap subscriptions at 30 combined** (channels + saved playlists) to bound total YouTube exposure per device.
2. **Move refresh off the UI thread / off tab-open** — Me tab opens render cache instantly; all NewPipe calls happen in background workers.
3. **Tier channels by activity** so hot channels refresh fast, dormant channels barely refresh at all.
4. **Adapt to YouTube signals** — cooldown on CAPTCHA / 429, mark broken channels dormant after repeated failures.
5. **Single global rate budget** shared across Me / Home / Search / Player so they don't stack into burst signatures.

### Non-Goals

- Push notifications for new uploads (would require backend or PubSubHubbub integration — separate effort).
- Backend involvement of any kind. Strictly on-device.
- Bullet-proof anti-bot — that ceiling is set by NewPipeExtractor itself, not by this design.
- Replacing NewPipeExtractor.

### Constraints

- 30-item combined cap (hard) — **channels + playlists ≤ 30**.
- All state on-device (Room + DataStore). No new backend endpoints.
- No new permissions beyond what `WorkManager` already requires.
- Compatible with Android 7.0 (API 24) minimum.
- Must coexist with existing NewPipe consumers: `HomeViewModel`, `FeaturedListViewModel`, `SearchExtractor` flows, `PlayerFragment`.

---

## 2. Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│                    UI Layer                               │
│  MeFragment ── MeViewModel ── observeFeed() (cache only) │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼ (read-only)
┌──────────────────────────────────────────────────────────┐
│                   Cache Layer (Room)                      │
│  channel_video_cache, channel_feed_refresh_state,         │
│  channel_tier_state (NEW), global_cooldown (NEW)          │
└──────────────────────────────────────────────────────────┘
                          ▲ (writes)
                          │
┌──────────────────────────────────────────────────────────┐
│              RefreshSubscriptionsWorker                   │
│  • runs every 30 min (foreground) / 2 h (background)      │
│  • picks N channels via ChannelTierClassifier             │
│  • each fetch acquires GlobalNewPipeRateLimiter token     │
│  • respects CooldownState — returns early if tripped      │
│  • updates ChannelTierState on success/failure            │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼ (token-gated)
┌──────────────────────────────────────────────────────────┐
│           GlobalNewPipeRateLimiter (token bucket)         │
│           shared by ALL NewPipe call sites                │
└──────────────────────────────────────────────────────────┘
                          │
                          ▼
                 NewPipeChannelFeedFetcher
                 + Home / Search / Player NewPipe calls
```

---

## 3. Components

### 3.1 `SubscriptionLimitGuard` (NEW)

**Purpose**: Enforce the 30 combined cap before any subscribe / save.

**Location**: `data/subscriptions/SubscriptionLimitGuard.kt`

**Interface**:
```kotlin
sealed class SubscribeResult {
    object Success : SubscribeResult()
    data class LimitReached(val current: Int, val cap: Int) : SubscribeResult()
}

@Singleton
class SubscriptionLimitGuard @Inject constructor(
    private val channels: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
    private val db: AppDatabase,
) {
    suspend fun trySubscribe(channel: SubscribedChannel): SubscribeResult
    suspend fun trySavePlaylist(playlist: SavedPlaylist): SubscribeResult
}
```

**Behavior**:
- Counts `channels.count() + playlists.count()` inside the same transaction as the insert.
- If already subscribed/saved → returns `Success` (re-subscribing same id is idempotent).
- If `count + 1 > CAP` → returns `LimitReached(current=count, cap=30)`.
- Otherwise inserts within the same transaction and returns `Success`.

**Why one guard for both**: a single source of truth means the cap can never be circumvented by inserting through the existing `SubscriptionRepository.subscribe()` and `savePlaylist()`. We delete those methods and route everyone through the guard.

**UX hook**: detail-screen toggle wires `SubscribeResult.LimitReached` → `Snackbar.make(... "You've reached the 30-item limit. Remove an item from Me to add this one.")`.

**Constants**:
```kotlin
companion object {
    const val SUBSCRIPTION_CAP_TOTAL: Int = 30
}
```

---

### 3.2 `ChannelTierClassifier` (NEW)

**Purpose**: Decide which N channels to refresh on each worker tick.

**Location**: `data/me/ChannelTierClassifier.kt`

**Tiers**:

| Tier | Definition | Min interval between refreshes |
|---|---|---|
| **HOT** | most recent upload < 3 days old | 30 min (foreground) / 60 min (bg) |
| **WARM** | most recent upload 3-14 days | 2 h (fg) / 3 h (bg) |
| **COLD** | most recent upload 14-30 days OR no observed uploads yet | 8 h |
| **DORMANT** | most recent upload > 30 days OR ≥3 consecutive empty/error fetches | 168 h (1 week) |

**Tier promotion / demotion rules** (re-evaluated after every successful fetch):
- Successful fetch with items → look at `max(items.uploadedAt)` and reclassify.
- Successful fetch with 0 items → increment `consecutiveEmptyCount`. At ≥3 → demote to DORMANT regardless of last upload date.
- Failed fetch (timeout, parse error, CAPTCHA) → increment `consecutiveErrorCount`. At ≥3 → demote to DORMANT until manually un-coldd OR weekly retry succeeds.
- User pull-to-refresh → all channels treated as eligible for one-shot fetch (still rate-limited, still cooldown-respected).
- Newly subscribed channel → starts in COLD (unknown tier) for first fetch, then reclassifies.

**API**:
```kotlin
@Singleton
class ChannelTierClassifier @Inject constructor(
    private val tierStateDao: ChannelTierStateDao,
    private val refreshStateDao: ChannelFeedRefreshStateDao,
) {
    suspend fun pickChannelsToRefresh(
        budget: Int,
        appInForeground: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<SubscribedChannel>

    suspend fun reclassify(
        channelId: String,
        outcome: FetchOutcome,
    )
}

sealed class FetchOutcome {
    data class Success(val items: List<ChannelFeedFetcher.ChannelFeedItem>) : FetchOutcome()
    object EmptyResult : FetchOutcome()
    data class Error(val cause: Throwable) : FetchOutcome()
}
```

**Pick algorithm**:
1. Filter to channels whose `lastSuccessfulFetchAt + tierMinInterval(tier, foreground)` < `now`.
2. Sort by `lastSuccessfulFetchAt ASC` (oldest first) — round-robin within eligible set.
3. Take first `min(budget, eligible.size)`.
4. If fewer than `budget` are eligible, just return what's eligible — never burst to "fill" the budget.

---

### 3.3 `RefreshSubscriptionsWorker` (NEW)

**Purpose**: Periodic background refresh, replacing today's `MeViewModel.init { refreshFeed() }`.

**Location**: `data/me/work/RefreshSubscriptionsWorker.kt` (CoroutineWorker)

**Schedule**:

| App state | Period | Initial backoff | Constraints |
|---|---|---|---|
| Foreground (app open, any screen) | every 30 min | exp 1m → 30m | NETWORK_CONNECTED |
| Backgrounded | every 2 h | exp 5m → 4h | NETWORK_CONNECTED |
| Pull-to-refresh | one-shot, ASAP | exp 1m → 5m | NETWORK_CONNECTED |

Foreground vs background is implemented by enqueueing **two separate periodic works** (`me_refresh_fg`, `me_refresh_bg`) and toggling them via a `ProcessLifecycleOwner` observer:

```kotlin
ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) { /* enqueue fg, cancel bg */ }
    override fun onStop(owner: LifecycleOwner) { /* enqueue bg, cancel fg */ }
})
```

**Per-tick budget**:

| Mode | Channels per tick |
|---|---|
| Foreground tick | **5** |
| Background tick | **5** |
| Pull-to-refresh | **all 30** (rate-limiter still bounds wall-clock spread) |

**Worker body** (pseudocode):

```kotlin
override suspend fun doWork(): Result {
    if (cooldownState.isTripped(now())) return Result.success() // honour cooldown
    val foreground = AppLifecycleTracker.isForeground.value
    val candidates = tierClassifier.pickChannelsToRefresh(
        budget = if (mode == PULL_TO_REFRESH) 30 else 5,
        appInForeground = foreground,
    )
    if (candidates.isEmpty()) return Result.success()

    for (channel in candidates) {
        ensureActive() // respect cancellation
        rateLimiter.acquire() // suspending — yields to higher-priority callers
        delay(Random.nextLong(800L, 2400L)) // jitter
        val outcome = runFetch(channel)
        tierClassifier.reclassify(channel.channelId, outcome)
        if (outcome is Error && isCooldownSignal(outcome.cause)) {
            cooldownState.trip(reason = outcome.cause)
            return Result.retry()
        }
    }
    return Result.success()
}
```

**Concurrency**: `Semaphore(1)` inside the worker — one fetch at a time. The rate limiter is the second wall.

**Pull-to-refresh wiring**: `MeViewModel.refreshFeed(force=true)` → enqueues `OneTimeWorkRequest` tagged `me_refresh_oneshot`. UI shows the SwipeRefresh spinner until the worker reports `WorkInfo.State.SUCCEEDED|FAILED`.

---

### 3.4 `GlobalNewPipeRateLimiter` (NEW)

**Purpose**: Single token bucket, shared across **all** NewPipe call sites.

**Location**: `data/extractor/GlobalNewPipeRateLimiter.kt`

**Configuration**:

| Knob | Value | Rationale |
|---|---|---|
| Bucket capacity | 30 tokens | Matches subscription cap; full refresh fits in one bucket |
| Refill rate | 1 token / 30 s | 120 tokens/h sustained — well within polite range |
| Player priority | bypasses bucket | Player needs YouTube to actually play; user-facing UX trumps refresh hygiene |
| Acquire timeout | 60 s for refresh, 10 s for Home/Search | Refresh can wait; user-initiated calls fail fast |

**API**:
```kotlin
@Singleton
class GlobalNewPipeRateLimiter @Inject constructor() {
    suspend fun acquire(priority: Priority, timeout: Duration = Duration.INFINITE): Boolean
}

enum class Priority {
    PLAYER,        // bypasses bucket entirely
    USER_FOREGROUND, // Home, Search, ChannelDetail — short timeout
    BACKGROUND_REFRESH, // Me refresh worker — long timeout
}
```

**Implementation**: standard suspending token bucket built on `Mutex` + `Channel<Unit>`.

**Integration touchpoints** (callers must be migrated):
- `NewPipeChannelFeedFetcher.fetchLatest` → `acquire(BACKGROUND_REFRESH)` before each fetch
- `HomeViewModel` NewPipe paths → `acquire(USER_FOREGROUND)`
- `FeaturedListViewModel` → `acquire(USER_FOREGROUND)`
- `SearchExtractor` callers → `acquire(USER_FOREGROUND)`
- `PlayerFragment` extraction → `acquire(PLAYER)` (no-op pass-through)

---

### 3.5 `CooldownState` (NEW)

**Purpose**: Global "stop poking YouTube" gate after CAPTCHA / 429 clusters.

**Location**: `data/extractor/CooldownState.kt`

**Persistence**: DataStore Preferences (`global_cooldown.preferences_pb`).

**Trip conditions** (any one):
- `ReCaptchaException` thrown anywhere
- `IOException` with HTTP 429 status (parse from `OkHttpClient` response code)
- `≥3 ParsingException` in 10 min window across all callers
- Manual trip (developer settings toggle, for testing)

**Cooldown durations** (exponential, persisted):

| Trip count in last 24 h | Duration |
|---|---|
| 1 | 1 h |
| 2 | 4 h |
| 3 | 12 h |
| 4+ | 24 h |

Reset to 0 trips after 7 consecutive days of clean fetches.

**API**:
```kotlin
@Singleton
class CooldownState @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val isTrippedFlow: Flow<Boolean>
    suspend fun isTripped(now: Long): Boolean
    suspend fun trip(reason: Throwable, now: Long = System.currentTimeMillis())
    suspend fun reset()
    suspend fun untilMs(): Long? // when cooldown expires, null if not tripped
}
```

**UI behavior when tripped**:
- Me tab still renders cache normally.
- Pull-to-refresh shows snackbar: "YouTube is asking us to wait. Try again in {humanizeDuration(untilMs() - now)}".
- No exception bubbles to user.
- Background workers see `Result.success()` (don't burn retry budget).

---

### 3.6 `AppLifecycleTracker` (NEW)

**Purpose**: One source of truth for "is the app foregrounded?". Drives worker scheduling and rate-limiter priority decisions.

**Location**: `app/AppLifecycleTracker.kt`

```kotlin
@Singleton
class AppLifecycleTracker @Inject constructor() : DefaultLifecycleObserver {
    val isForeground: StateFlow<Boolean>
    init { ProcessLifecycleOwner.get().lifecycle.addObserver(this) }
    override fun onStart(owner: LifecycleOwner) { _isForeground.value = true }
    override fun onStop(owner: LifecycleOwner) { _isForeground.value = false }
}
```

Wired in `AlBunyaanApplication.onCreate()`.

---

## 4. Constants Reference

All in one place to make tuning easy.

```kotlin
object MeRefreshTuning {
    // Cap
    const val SUBSCRIPTION_CAP_TOTAL = 30

    // Worker
    const val FG_REFRESH_PERIOD_MIN = 30L
    const val BG_REFRESH_PERIOD_MIN = 120L
    const val PER_TICK_BUDGET = 5
    const val PULL_TO_REFRESH_BUDGET = 30

    // Tier intervals (ms)
    const val HOT_INTERVAL_FG_MS = 30L * 60_000L
    const val HOT_INTERVAL_BG_MS = 60L * 60_000L
    const val WARM_INTERVAL_FG_MS = 2L * 60L * 60_000L
    const val WARM_INTERVAL_BG_MS = 3L * 60L * 60_000L
    const val COLD_INTERVAL_MS = 8L * 60L * 60_000L
    const val DORMANT_INTERVAL_MS = 7L * 24L * 60L * 60_000L

    // Tier classification thresholds (ms)
    const val HOT_RECENCY_MS = 3L * 24L * 60L * 60_000L
    const val WARM_RECENCY_MS = 14L * 24L * 60L * 60_000L
    const val COLD_RECENCY_MS = 30L * 24L * 60L * 60_000L

    // Demotion thresholds
    const val EMPTY_FETCHES_TO_DORMANT = 3
    const val ERROR_FETCHES_TO_DORMANT = 3

    // Rate limiter
    const val BUCKET_CAPACITY = 30
    const val BUCKET_REFILL_PERIOD_MS = 30_000L
    const val FG_ACQUIRE_TIMEOUT_MS = 10_000L
    const val BG_ACQUIRE_TIMEOUT_MS = 60_000L

    // Jitter
    const val INTER_FETCH_JITTER_MIN_MS = 800L
    const val INTER_FETCH_JITTER_MAX_MS = 2_400L

    // Per-channel
    const val PER_CHANNEL_TIMEOUT_MS = 15_000L  // unchanged from current
    const val MAX_ITEMS_PER_CHANNEL = 30        // unchanged
    const val FEED_WINDOW_MS = 14L * 24L * 60L * 60_000L // unchanged

    // Cooldown
    val COOLDOWN_DURATIONS_BY_TRIP_COUNT = listOf(
        1.hours,
        4.hours,
        12.hours,
        24.hours,
    )
    const val COOLDOWN_RESET_AFTER_CLEAN_DAYS = 7
    const val COOLDOWN_PARSING_TRIPS_WINDOW_MS = 10L * 60_000L
    const val COOLDOWN_PARSING_TRIPS_THRESHOLD = 3
}
```

---

## 5. Persistence Schema (Room v3)

### New tables

```sql
CREATE TABLE channel_tier_state (
    channelId TEXT NOT NULL PRIMARY KEY,
    tier TEXT NOT NULL,                 -- HOT | WARM | COLD | DORMANT
    consecutiveEmptyCount INTEGER NOT NULL DEFAULT 0,
    consecutiveErrorCount INTEGER NOT NULL DEFAULT 0,
    lastClassifiedAt INTEGER NOT NULL,
    mostRecentUploadAt INTEGER          -- nullable; null until first successful fetch
);

CREATE INDEX idx_channel_tier_state_tier ON channel_tier_state(tier);
```

### Migration `MIGRATION_2_3`

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE channel_tier_state (
                channelId TEXT NOT NULL PRIMARY KEY,
                tier TEXT NOT NULL,
                consecutiveEmptyCount INTEGER NOT NULL DEFAULT 0,
                consecutiveErrorCount INTEGER NOT NULL DEFAULT 0,
                lastClassifiedAt INTEGER NOT NULL,
                mostRecentUploadAt INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_channel_tier_state_tier ON channel_tier_state(tier)")

        // Backfill: every existing subscription starts in COLD so the next
        // worker tick fetches it and assigns a real tier.
        db.execSQL("""
            INSERT INTO channel_tier_state (channelId, tier, consecutiveEmptyCount, consecutiveErrorCount, lastClassifiedAt, mostRecentUploadAt)
            SELECT channelId, 'COLD', 0, 0, ${'$'}{System.currentTimeMillis()}, NULL
            FROM subscribed_channels
        """.trimIndent())
    }
}
```

### Cooldown state — DataStore (no schema change)

Stored as a single `CooldownStatePB` proto-style record in `global_cooldown.preferences_pb`:

```
cooldown_until_ms: Long?
cooldown_trip_count_24h: Int
cooldown_last_trip_ms: Long?
cooldown_last_clean_streak_start_ms: Long
```

---

## 6. Failure Handling Matrix

| Signal | Per-channel action | Global action |
|---|---|---|
| `ReCaptchaException` | Mark error, no demotion (it's a global signal) | **Trip cooldown (1h+)** |
| HTTP 429 (parsed from OkHttp) | Mark error | **Trip cooldown** |
| `ContentNotAvailableException` | `consecutiveErrorCount++`; demote to DORMANT at 3 | none |
| `ParsingException` | `consecutiveErrorCount++` | If 3 within 10 min → trip cooldown |
| `TimeoutCancellationException` (per-channel 15s) | `consecutiveErrorCount++` | none |
| `IOException` (network) | Mark error, no demotion (network is transient) | none |
| Empty result + last upload < 14d | `consecutiveEmptyCount++`; demote to DORMANT at 3 | none |
| Empty result + last upload > 14d | Treat as success, demote to DORMANT directly | none |

User-visible error reporting: **never as a toast / dialog**. Always silent — the cache keeps showing prior data. Pull-to-refresh shows snackbar only on cooldown trip ("YouTube asking us to wait, try again in X").

---

## 7. UX Surfaces

### Detail screens (Subscribe / Save toggles)

```kotlin
when (val result = subscriptionLimitGuard.trySubscribe(channel)) {
    is Success -> updateUiAsSubscribed()
    is LimitReached -> showSnackbar(
        "You've reached the 30-item limit. Remove an item from Me to add this one."
    )
}
```

### Me tab

- Open Me → cache renders instantly. No spinner unless pull-to-refresh.
- Pull-to-refresh → spinner until one-shot worker completes.
- New "Manage subscriptions" entry in Settings → list with unsubscribe / remove buttons (already exists in current Me design but should also offer "Wake up dormant" button per row).
- Empty cooldown banner is **opt-in**: only shown if pull-to-refresh while cooldown is tripped.

### Developer settings

(Stays in `DeveloperSettingsDialog`, hidden from end users.)

- "Force tier classification refresh"
- "Trip global cooldown (1 h)"
- "Reset cooldown"
- "Show rate-limiter stats" (capacity, current tokens, last refill)
- "Show worker schedule" (next fg / bg tick)

---

## 8. Telemetry Hooks

(Wire to existing `PlaybackMetricsCollector`-style event log; no new analytics SDK.)

| Event | When | Fields |
|---|---|---|
| `me_refresh_started` | Worker starts a tick | mode (FG/BG/ONESHOT), candidates count |
| `me_refresh_finished` | Worker tick finishes | mode, success_count, empty_count, error_count, duration_ms |
| `me_channel_fetched` | Per-channel after fetch | channelId, tier_before, tier_after, items_count, latency_ms |
| `cooldown_tripped` | CooldownState.trip() | reason, trip_count_24h, until_ms |
| `cooldown_cleared` | Cooldown expires & next worker runs | duration_ms |
| `rate_limiter_starved` | acquire() times out | priority, waited_ms |
| `subscription_cap_hit` | LimitReached returned | current_count |

These are local-only; surface in developer settings as a rolling log.

---

## 9. Test Strategy

### Unit (jvm)

- `SubscriptionLimitGuardTest` — cap math, idempotent re-subscribe, cross-table count
- `ChannelTierClassifierTest` — promotion/demotion under each `FetchOutcome`, eligibility filtering
- `GlobalNewPipeRateLimiterTest` — bucket refill timing, priority bypass, acquire timeout
- `CooldownStateTest` — trip duration escalation, 7-day clean streak resets count
- `RefreshSubscriptionsWorkerTest` — uses `WorkManagerTestInitHelper`, fakes `ChannelFeedFetcher`, asserts:
  - Cooldown short-circuits worker
  - Per-tick budget honoured
  - Outcome propagates to tier classifier
  - Cancellation propagates correctly (extends F6 fix from Stage 7)

### Integration (Robolectric)

- `MeFeedRepositoryTest` (existing) — extend for cooldown short-circuit on `observeFeed()` (no-op, cache-only) and pull-to-refresh path
- `MeViewModelTest` (existing) — extend for cooldown snackbar trigger on pull-to-refresh

### Instrumented (Espresso)

- New: `MeTabSubscriptionCapTest` — try to subscribe a 31st item, verify snackbar
- New: `MeTabPullToRefreshTest` — trip cooldown via developer settings, pull-to-refresh, verify snackbar text

### Manual QA checklist (added to release notes)

- Subscribe 30 channels → 31st blocked
- Subscribe 25 channels + save 5 playlists → 31st of either blocked
- Unsubscribe → next add works
- Pull-to-refresh while cooldown tripped → snackbar shown
- Foreground app for 10 min → observe ≥1 worker tick (developer log)
- Background app overnight → observe ≥3 worker ticks
- Force CAPTCHA via dev setting → cooldown trips, no UI crash

---

## 10. Rollout / Migration

### Phased delivery

| Phase | Scope | Days est. |
|---|---|---|
| **P1: Cap** | `SubscriptionLimitGuard` + UI snackbars + tests | 0.5 d |
| **P2: Rate limiter** | `GlobalNewPipeRateLimiter` + integrate Me/Home/Search/Player + tests | 1 d |
| **P3: Worker** | `RefreshSubscriptionsWorker` + `AppLifecycleTracker` + decommission `MeViewModel.init refresh` + tests | 1.5 d |
| **P4: Tier classifier** | `ChannelTierClassifier` + Room v3 migration + tests | 1 d |
| **P5: Cooldown** | `CooldownState` + DataStore + UX wiring + dev settings | 0.75 d |
| **P6: Telemetry + dev settings polish** | Local event log + dev dialog entries | 0.5 d |

Total: **~5.25 dev days**, plus full review pipeline (Stages 1-7 per `feedback_review_pipeline.md`) ≈ +2 d. Realistic shipping target **7-8 calendar days**.

### Migration safety

- Room v2 → v3 covered by `MIGRATION_2_3`. Backfill seeds every existing subscription as `COLD` so the first worker tick fetches it.
- Existing users with > 30 subscriptions (none expected — feature just shipped) are **grandfathered**: cap is enforced on new adds only. Existing subs are fully refreshable until removed; the user can drop below 30 at their own pace.
- Rolling out behind no flag — this is the new default behavior. The existing `feature/ANDROID-PERSONAL-01-me-tab` branch hasn't merged to a stable release yet, so users haven't formed habits around the old "instant refresh on tab open" behavior.
- One subtle change: opening the Me tab **no longer triggers a fetch**. The first tick after install runs ~30 s later (worker `setInitialDelay(0)` with internal jitter). For users on first install this means the empty state shows for up to ~60 s before the first batch lands. Mitigation: show a subtle "Loading your feed..." placeholder above empty/cache state if `lastSuccessfulFetchAt` is null for all subs.

### Rollback plan

If P3 worker integration causes issues in QA:
- Keep `SubscriptionLimitGuard`, `GlobalNewPipeRateLimiter`, and `MIGRATION_2_3` (P1/P2/P4 schema).
- Revert `MeViewModel` to call `refresh()` in `init` as today, but route through the new rate limiter (still gives us most of the safety win).
- Tier classifier becomes advisory data, not a scheduling input.

---

## 11. Open Questions

1. **Should saved playlists count toward the cap?** (Current proposal: yes — they're equally a UX surface. Alternative: separate caps, e.g. 25 channels + 10 playlists.) **Decision needed.**

2. **Pull-to-refresh budget**: full 30 in one go, or capped at 15? Full 30 is "what users expect" but burns the bucket. **Proposed: full 30, with rate-limiter spread over ~2 minutes — visible to user as a slow refresh spinner.**

3. **Worker on Android < 8 (API 24/25)**: `WorkManager` is supported, but `setRequiredNetworkType` constraint behaviour differs slightly. **Proposed: same constants, accept slightly less precise scheduling on older devices.**

4. **Player priority bypass — should it really bypass the bucket completely?** A user spamming play/pause across 5 videos in 30 s could send 15 NewPipe calls. **Proposed: yes bypass; the alternative (player throttled) breaks playback UX, which is worse than a temporary detection signal during heavy use.**

5. **Foreground tier interval (HOT = 30 min)** — could be tightened to 15 min since the user is clearly engaged. Tradeoff: more requests during the time YouTube is most likely to fingerprint a session. **Proposed: leave at 30 min, tighten in a future iteration if telemetry shows headroom.**

6. **Should we prefetch on app launch** (once per day max) to warm the cache for users who only open Me intermittently? **Proposed: no in v1 — keeps the design simple. Add later if telemetry shows users hit empty cache often.**

---

## 12. Out of Scope (Future Work)

- Push notifications for new uploads (would need PubSubHubbub + backend or FCM)
- WebSub / atom feed subscription as a complement to scraping
- Per-channel notification toggles ("notify me when X uploads")
- Importing subscriptions from YouTube Takeout
- Cross-device sync of subscription list (would need backend)
- Tiered cap (e.g. premium users get 50)

---

## 13. Approval Checklist

Before promoting to implementation plan:

- [ ] User confirms 30 combined cap is final (vs split caps)
- [ ] User confirms pull-to-refresh budget (full 30 vs 15)
- [ ] User confirms no backend involvement
- [ ] User confirms tier intervals (Q5 above)
- [ ] User signs off on rollout plan, especially the "first install: 60s empty state" UX

Once approved, the implementation plan goes into `docs/superpowers/plans/2026-04-27-me-tab-safe-refresh-implementation.md` following the same per-phase structure as the existing `2026-04-14-me-tab-implementation.md`.
