# Me Tab — Design Spec

**Ticket:** ANDROID-PERSONAL-01
**Branch:** feature/ANDROID-PERSONAL-01-me-tab
**Status:** Draft (autonomous execution per user mandate)

## 1. Summary

Replace the Downloads bottom-nav tab with a YouTube-style **"Me"** tab that aggregates content from the user's subscribed channels and saved playlists. Downloads moves into Settings (a new row), so the feature is preserved, not deleted. Subscriptions and saved playlists are persisted locally (Room) — no backend sync.

## 2. Goals

1. User can subscribe/unsubscribe from any channel, save/unsave any playlist, favorite any video — local persistence only.
2. Bottom nav's 5th tab becomes "Me"; Downloads moves to a Settings row.
3. Me screen shows:
   - Top horizontal chip row of subscribed channels + saved playlists (tappable = filter feed).
   - Horizontal "Shorts" strip aggregated across subscriptions (≤14 days old).
   - Vertical feed of long-form videos aggregated across subscriptions (≤14 days old), newest first.
4. Rate-limit friendly: never hammer YouTube. Bounded concurrency, per-channel cache TTL.
5. No regression on phone / tablet (sw600dp) / TV (sw720dp). RTL-correct. 60fps.

## 3. Non-goals / deferred

- **Community posts.** Investigated: NewPipeExtractor 0.24.8 supports channel tabs (Videos, Shorts, Livestreams, Playlists, Albums, Channels). A `Community` / `Posts` tab is not part of the stable extractor surface for YouTube in 0.24.8 — attempting it requires custom tab extraction that is out of scope for this ticket. **Deferred to a follow-up**, documented in `docs/TRUE_PROJECT_STATUS.md`. The screen is laid out to accept a posts section later without restructuring.
- Backend sync of subscriptions. Local-only by user request.
- Cross-device sync, import from YouTube account, OPML — out of scope.
- Reordering chips, grouping subscriptions — v1 uses insertion order (most-recently-subscribed first).
- "Saved Videos" (favorites) surface on the Me screen. Favorites stay addressable via a new Settings row (sibling of Downloads). Keeps Me screen focused on the feed pattern in screenshots 3–4.

## 4. Data model

Extend `AppDatabase` (currently v1, entities = [`FavoriteVideo`]) with three new entities. Bump to **v2** with a proper `Migration(1, 2)` that creates the three new tables. Keep `FavoriteVideo` untouched — backward compatible.

### 4.1 `SubscribedChannel`

```kotlin
@Entity(tableName = "subscribed_channels")
data class SubscribedChannel(
    @PrimaryKey val channelId: String,   // YouTube channel ID (UC…)
    val channelUrl: String,              // canonical URL
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long = System.currentTimeMillis()
)
```

### 4.2 `SavedPlaylist`

```kotlin
@Entity(tableName = "saved_playlists")
data class SavedPlaylist(
    @PrimaryKey val playlistId: String,
    val playlistUrl: String,
    val name: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val savedAt: Long = System.currentTimeMillis()
)
```

### 4.3 `ChannelVideoCache`

Cache of per-channel feed items. One row per video. Index on `channelId` and `uploadedAt`.

```kotlin
@Entity(
    tableName = "channel_video_cache",
    indices = [Index("channelId"), Index("uploadedAt")]
)
data class ChannelVideoCache(
    @PrimaryKey val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,         // null for live / unknown
    val viewCount: Long?,
    val uploadedAt: Long?,              // epoch ms — parsed best-effort
    val isShort: Boolean,               // from Shorts tab or isShortFormContent()
    val fetchedAt: Long = System.currentTimeMillis()
)
```

Per-channel refresh state is tracked on `SubscribedChannel` via a second table to avoid bloating the parent entity:

### 4.4 `ChannelFeedRefreshState`

```kotlin
@Entity(tableName = "channel_feed_refresh_state")
data class ChannelFeedRefreshState(
    @PrimaryKey val channelId: String,
    val lastSuccessfulFetchAt: Long,
    val lastAttemptAt: Long,
    val lastErrorMessage: String?       // null if last fetch succeeded
)
```

TTL: a channel's feed is "fresh" for **30 minutes** after a successful fetch. On Me screen open, stale channels refresh in the background; fresh ones are rendered from cache immediately.

## 5. Architecture

```
┌─────────────┐   ┌───────────────┐   ┌────────────────────────┐
│ MeFragment  │◄──│ MeViewModel   │◄──│ MeFeedRepository       │
│  (UI)       │   │  (StateFlow)  │   │  - orchestrates fetch  │
└─────────────┘   └───────────────┘   │  - cache TTL check     │
                                      │  - bounded concurrency │
                                      └───────┬────────────────┘
                                              │
                         ┌────────────────────┼────────────────────┐
                         ▼                    ▼                    ▼
                ┌──────────────────┐ ┌─────────────────┐ ┌─────────────────────┐
                │ SubscriptionRepo │ │ NewPipeExtractor│ │ Cache DAOs          │
                │  (channel/pl)    │ │  Client         │ │ (videos, refresh)   │
                └──────────────────┘ └─────────────────┘ └─────────────────────┘
                           ▼                                      ▼
                    Room (AppDatabase v2, shared)
```

### 5.1 SubscriptionRepository

Single source of truth for channel-subscribed / playlist-saved / video-favorited state. Methods:

- `observeSubscribedChannels(): Flow<List<SubscribedChannel>>`
- `observeSavedPlaylists(): Flow<List<SavedPlaylist>>`
- `isChannelSubscribed(channelId): Flow<Boolean>`
- `isPlaylistSaved(playlistId): Flow<Boolean>`
- `subscribe(channel: SubscribedChannel)` / `unsubscribe(channelId)`
- `savePlaylist(playlist: SavedPlaylist)` / `unsavePlaylist(playlistId)`

Video favorites remain in the existing `FavoritesRepository`.

### 5.2 MeFeedRepository

Orchestrates per-channel feed fetches with safety rails:

- `observeFeed(): Flow<MeFeedState>` — aggregates cache across all subscribed channels. Emits immediately from cache; suspends while refreshing stale entries.
- `refresh(force: Boolean = false)` — identifies stale channels (per `ChannelFeedRefreshState`), fetches with bounded concurrency.

**Fetch strategy:**
- Semaphore-bounded parallelism: **max 4 concurrent channel fetches**.
- Inter-fetch dispatcher delay: **250ms** stagger between launch of each fetch.
- Per fetch: `ChannelInfo.getInfo(url)` → pick `VIDEOS` tab → `initialPage` only (no pagination beyond page 1 — we only need "latest"). For shorts, try `SHORTS` tab if present; if not, filter Videos-tab items by `isShortFormContent()`.
- On failure (ContentNotAvailableException, PrivateContentException, GeographicRestrictionException, ExtractionException, IOException): record in `ChannelFeedRefreshState.lastErrorMessage`; don't crash; skip this channel for aggregation.
- Upload-date parsing: use `DateWrapper` from `StreamInfoItem.getUploadDate()` when available (`offsetDateTime.toInstant().toEpochMilli()`). If only `textualUploadDate` present, leave `uploadedAt = null`; those items render but sort behind dated ones and cannot participate in the 14-day filter (so we conservatively exclude them).
- **Cap per channel per refresh:** keep only the 30 newest items per channel in cache; prune older in a transaction.

**Global cap on first load:** if user has >50 subscribed channels, refresh only the 50 most-recently-subscribed (or most-recently-seen) on a single call; older channels refresh on next app open. Prevents a single Me-tab open from burning through YouTube quota.

### 5.3 MeViewModel

Exposes:

```kotlin
sealed class MeUiState {
    object Loading
    data class Empty(val message: String)            // no subscriptions
    data class Content(
        val chips: List<ChipItem>,                   // channels + playlists
        val shorts: List<VideoItem>,                 // ≤14d, isShort=true
        val videos: List<VideoItem>,                 // ≤14d, isShort=false
        val filterChipId: String?                    // null = no filter
    )
    data class Error(val message: String, val canRetry: Boolean)
}
```

Filter chip behaviour: tapping a channel chip filters `shorts` + `videos` to that channel only. Tapping a playlist chip navigates to PlaylistDetailFragment (playlists don't have a "latest video" equivalent — same interaction as the existing Playlists tab). Tapping the active chip clears the filter.

### 5.4 Hilt wiring

New `MeModule.kt` under `di/`:

- `@Provides` `SubscribedChannelDao`, `SavedPlaylistDao`, `ChannelVideoCacheDao`, `ChannelFeedRefreshStateDao` — delegated from `AppDatabase`.
- `@Provides @Singleton` `SubscriptionRepository`.
- `@Provides @Singleton` `MeFeedRepository` — dependencies: DAOs + `NewPipeExtractorClient` + `@IoDispatcher CoroutineDispatcher`.

Existing `DatabaseModule` is extended (not replaced) to expose new DAOs via the same `AppDatabase` singleton.

## 6. UI

### 6.1 Screen composition (phone)

`fragment_me.xml` — `CoordinatorLayout`:

- `MaterialToolbar` with title "Me" (localized: `nav_me`).
- `SwipeRefreshLayout` → `RecyclerView` (vertical) using `ConcatAdapter`:
  - `ChipsRowAdapter` (1 item → horizontal RV of chips)
  - `ShortsSectionAdapter` (header + 1 item → horizontal RV of shorts, nested scroll)
  - `VideosAdapter` (N items, vertical stream)
- Empty state: a single CTA layout with text + a button that goes to Channels tab.

`paddingBottom="@dimen/bottom_nav_height"` on the RecyclerView so last video clears the BottomNavigationView on phone (0dp on tablet/TV per existing dimens).

### 6.2 Layout variants

| Variant | Change vs. phone |
|---|---|
| `layout/` | As above. |
| `layout-sw600dp/` | Videos adapter uses 2-column StaggeredGridLayoutManager to match existing Videos/Playlists tablet treatment. Shorts strip unchanged (already horizontal). Chips strip unchanged. |
| `layout-sw720dp/` | 3-column grid for videos; larger chip avatar (72dp instead of 56dp). |

All three variants share identical view IDs so the Kotlin is one path.

### 6.3 Pagination on large screens

Per `CLAUDE.md` and the established pattern in `PlaylistsFragmentNew/ChannelsFragmentNew/VideosFragmentNew`: after `submitList()` completes, `post { if (!rv.canScrollVertically(1)) viewModel.loadMore() }`. MeFragment adopts the same pattern. (Though "loadMore" in the Me tab means fetching page 2 of the aggregated feed; v1 caps at 30-per-channel × up-to-50-channels = up to 1500 items, which is already more than a single scroll can exhaust — so the auto-loadMore is likely a no-op guard rather than a hot path.)

### 6.4 Subscribe/save interaction points

- **ChannelDetailFragment**: new subscribe toggle in the toolbar or hero area (reuse MaterialButton with outlined/filled toggle states). On tap → `SubscriptionRepository.subscribe(...)`.
- **PlaylistDetailFragment**: same pattern with a save/bookmark toggle.
- **Video favorites**: existing `FavoritesFragment` and the favorite action in the player remain unchanged.
- **Quick path on list items (out of scope for v1)**: long-press on Channel/Playlist cards to subscribe. Deferred unless trivial.

### 6.5 Nav icon

New drawable `res/drawable/ic_nav_me.xml` — project-local vector, **no vector-level `android:tint`**, `fillColor="#FFFFFFFF"` (let `itemIconTint` control color). Explicitly follows the CLAUDE.md rule that forbids `ic_stat_*` / `@android:drawable/*` for nav bar icons. Icon design: a filled-circle silhouette of a person (YouTube-style "You").

### 6.6 Strings

Add to every locale in `strings.xml` (en, ar, nl):

- `nav_me` = "Me" / "أنت" / "Jij"
- `me_empty_title`, `me_empty_subtitle`, `me_empty_cta`
- `me_section_shorts`, `me_section_videos`
- `me_refresh_error`
- `me_filter_cleared_content_desc` (a11y)
- `settings_downloads_title`, `settings_favorites_title`
- `channel_subscribe` / `channel_unsubscribe`
- `playlist_save` / `playlist_unsave`

## 7. Navigation & Settings changes

### 7.1 `bottom_nav_menu.xml`

Replace `@+id/downloadsFragment` item with:

```xml
<item
    android:id="@+id/meFragment"
    android:icon="@drawable/ic_nav_me"
    android:title="@string/nav_me" />
```

Order within the 5-item set: Home, Channels, Playlists, Videos, **Me** (replacing Downloads in the 5th slot).

### 7.2 `main_tabs_nav.xml`

- Add `<fragment android:id="@+id/meFragment" ...>` destination.
- Keep `@+id/downloadsFragment` destination in place — still navigable from Settings.
- Add action `action_settingsFragment_to_downloadsFragment`.
- Add action `action_settingsFragment_to_favoritesFragment` (currently only reachable from Downloads).

### 7.3 `fragment_settings.xml` (all three variants)

Add a new section **"Library"** above the existing **"Downloads"** settings section (the Downloads section becomes a single-row entry that opens DownloadsFragment):

```
▸ Library
    • Downloads           → downloadsFragment
    • Favorites           → favoritesFragment
▸ Downloads   (existing preferences: Download Quality, WiFi-only — kept)
```

Rename the **Downloads-entry row** differently from the **Downloads preferences section** to avoid confusion:

- New row label: "Downloads library" (string `settings_downloads_library`)
- Existing section header: keep "Downloads"

This avoids name collision and mirrors YouTube's split between the feature surface and its settings.

### 7.4 Edge-to-edge & double-inset compliance

`fragment_main_shell.xml` stays untouched (CLAUDE.md explicitly forbids removing `fitsSystemWindows`). The Me tab is a child of the existing nav host → automatically inherits insets. No new surface needs its own inset neutralization. Verified.

## 8. Rate-limit & safety strategy

| Control | Value | Rationale |
|---|---|---|
| Per-channel cache TTL | 30 min | Balance freshness vs. YT load. YouTube's own feed ≈ similar cadence. |
| Max concurrent channel fetches | 4 | Empirically safe; matches OkHttp's default dispatcher per-host cap. |
| Inter-launch stagger | 250 ms | Prevents a 50-fetch burst on cold Me-tab open. |
| Max channels refreshed per Me-open | 50 | Hard cap so a huge subscription list doesn't trigger rate limits. |
| Initial-page only | — | We only need "latest", never full channel history. |
| Pagination per channel | Page 1 only | Same reason. |
| Upload-date filter | 14 days | From prompt; trims the feed naturally. |
| Per-channel cache prune | Keep newest 30 | Bounds Room growth. |
| Failure handling | Record + skip | No retry storms; next Me-open will retry. |
| Dispatcher | `Dispatchers.IO` | NewPipe calls are blocking network. |

Shorts fetch: only if a subscribed channel is known to *have* shorts (best effort via Shorts tab probe). If a channel's Shorts tab is empty or the tab is not exposed, skip — don't count it as a failure.

## 9. Testing

| Layer | Test |
|---|---|
| DAOs | Room in-memory instrumented tests for subscribe/unsubscribe, save/unsave, cache upsert/prune. |
| `SubscriptionRepository` | Unit test with Room in-memory. |
| `MeFeedRepository` | Unit test with a fake `NewPipeExtractorClient`. Verify: cache hit path, stale refresh path, failure isolation (one channel throws → others still aggregated), 14-day filter, bounded concurrency (`Semaphore` held). |
| `MeViewModel` | `runTest` with `StandardTestDispatcher`. Verify: Loading → Content; filter chip state. |
| Fragment | Optional Espresso smoke test for empty state + chip render. Not required for ≥95% confidence given existing Fragment test coverage patterns. |
| Build | `./gradlew assembleDebug` + `./gradlew test` + `./gradlew lintDebug` must all pass. |

## 10. Migration

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE subscribed_channels (
            channelId TEXT NOT NULL PRIMARY KEY,
            channelUrl TEXT NOT NULL,
            name TEXT NOT NULL,
            avatarUrl TEXT,
            subscribedAt INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE saved_playlists (
            playlistId TEXT NOT NULL PRIMARY KEY,
            playlistUrl TEXT NOT NULL,
            name TEXT NOT NULL,
            thumbnailUrl TEXT,
            uploaderName TEXT,
            savedAt INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE channel_video_cache (
            videoId TEXT NOT NULL PRIMARY KEY,
            channelId TEXT NOT NULL,
            channelName TEXT NOT NULL,
            title TEXT NOT NULL,
            thumbnailUrl TEXT,
            durationSeconds INTEGER,
            viewCount INTEGER,
            uploadedAt INTEGER,
            isShort INTEGER NOT NULL,
            fetchedAt INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX idx_cvc_channelId ON channel_video_cache(channelId)")
        db.execSQL("CREATE INDEX idx_cvc_uploadedAt ON channel_video_cache(uploadedAt)")
        db.execSQL("""CREATE TABLE channel_feed_refresh_state (
            channelId TEXT NOT NULL PRIMARY KEY,
            lastSuccessfulFetchAt INTEGER NOT NULL,
            lastAttemptAt INTEGER NOT NULL,
            lastErrorMessage TEXT)""")
    }
}
```

Supplied to `DatabaseModule` via `.addMigrations(MIGRATION_1_2)` on the `Room.databaseBuilder`.

## 11. Risks & mitigations

| Risk | Mitigation |
|---|---|
| NewPipe channel-tab API differs from what I expect | Isolated behind `MeFeedRepository` — swap implementation without touching UI. Fallback: single-tab fetch (Videos only), defer Shorts. |
| Upload-date parsing unreliable | Best-effort; items without dates are conservatively excluded from the 14-day feed but kept in cache. |
| Subscription list grows huge | 50-channel-per-open cap + 30-min TTL keep network bounded. |
| Room migration fails on upgrade | Supplied migration is additive (pure CREATE TABLE). No existing table touched. Users on v1 → v2 get empty new tables. |
| BottomNav icon invisible on Samsung S25 Ultra | Explicit project-local vector with no vector tint, per CLAUDE.md rule. |
| Me feed feels laggy | Cache-first render; network in background. SwipeRefreshLayout for manual re-fetch. |
| RTL breakage | Use `textAlignment="viewStart"`, `layoutDirection="locale"`, mirror-safe drawables. Arabic emulator verification required. |
| Downloads feature appears broken after move | Downloads code untouched; only nav entry point moved. Users reach it via Settings → Library → Downloads library. Release note required. |

## 12. Definition of Done

- [ ] Room v2 migration + new entities + DAOs unit-tested.
- [ ] SubscriptionRepository + MeFeedRepository unit-tested with fake extractor.
- [ ] Subscribe/save toggles wired on Channel/Playlist detail screens.
- [ ] MeFragment renders empty + populated + filtered + error states.
- [ ] Layouts present in `layout/`, `layout-sw600dp/`, `layout-sw720dp/`; identical IDs.
- [ ] Bottom nav swaps Downloads → Me; Downloads reachable from Settings.
- [ ] `./gradlew assembleDebug` + `./gradlew test` + `./gradlew lintDebug` green.
- [ ] Self-review via `code-review:code-review` agent; findings addressed.
- [ ] `docs/TRUE_PROJECT_STATUS.md` + `docs/PROJECT_STATUS.md` updated.
- [ ] Atomic commits on `feature/ANDROID-PERSONAL-01-me-tab`. **No merge**; user reviews first.
- [ ] Remaining gap: manual visual QA across emulators / real devices — explicitly listed for the user at end.
