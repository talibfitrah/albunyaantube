# Me Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Downloads bottom-nav tab with a subscription-feed "Me" tab; re-home Downloads + Favorites into Settings.

**Architecture:** Local persistence (Room v2) for subscribed channels / saved playlists / per-channel video cache. `MeFeedRepository` orchestrates rate-limit-friendly per-channel fetches via on-device NewPipeExtractor with bounded concurrency + 30-min cache TTL. Single `MeFragment` with `ConcatAdapter` drives chips row + shorts strip + videos stream, with identical view IDs across phone/sw600dp/sw720dp layouts.

**Tech Stack:** Kotlin, Hilt, Room 2.x, Kotlin Coroutines/Flow, NewPipeExtractor (on-device), AndroidX Navigation, Material Components (BottomNavigationView / NavigationRailView).

**Spec:** `docs/superpowers/specs/2026-04-14-me-tab-design.md`

---

## File Structure

### Created

| Path | Responsibility |
|---|---|
| `android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannel.kt` | Room entity. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannelDao.kt` | DAO. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylist.kt` | Room entity. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/SavedPlaylistDao.kt` | DAO. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/ChannelVideoCache.kt` | Room entity. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/ChannelVideoCacheDao.kt` | DAO. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/ChannelFeedRefreshState.kt` | Room entity. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/ChannelFeedRefreshStateDao.kt` | DAO. |
| `android/app/src/main/java/com/albunyaan/tube/data/local/Migrations.kt` | `MIGRATION_1_2`. |
| `android/app/src/main/java/com/albunyaan/tube/data/subscriptions/SubscriptionRepository.kt` | Subscription + saved-playlist CRUD. |
| `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt` | Feed orchestration, cache, bounded fetch. |
| `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedModels.kt` | `MeFeedItem`, `MeFeedState`, `ChipItem`. |
| `android/app/src/main/java/com/albunyaan/tube/di/MeModule.kt` | Hilt wiring for Me feature. |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt` | Screen. |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/MeViewModel.kt` | UI state + filter. |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/MeChipsAdapter.kt` | Horizontal chips row. |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/MeShortsAdapter.kt` | Shorts section (header + horizontal RV). |
| `android/app/src/main/java/com/albunyaan/tube/ui/me/MeVideosAdapter.kt` | Vertical videos list. |
| `android/app/src/main/res/drawable/ic_nav_me.xml` | Project-local nav icon (no tint). |
| `android/app/src/main/res/layout/fragment_me.xml` | Phone layout. |
| `android/app/src/main/res/layout-sw600dp/fragment_me.xml` | Tablet layout. |
| `android/app/src/main/res/layout-sw720dp/fragment_me.xml` | TV layout. |
| `android/app/src/main/res/layout/item_me_chip.xml` | Chip item. |
| `android/app/src/main/res/layout/item_me_shorts_section.xml` | Shorts section. |
| `android/app/src/main/res/layout/item_me_short.xml` | Single short card. |
| `android/app/src/main/res/layout/item_me_video.xml` | Single video row. |
| `android/app/src/main/res/layout/item_me_empty.xml` | Empty state. |
| `android/app/src/main/res/layout/settings_item_downloads_library.xml` | Settings row: Downloads. |
| `android/app/src/main/res/layout/settings_item_favorites.xml` | Settings row: Favorites. |
| Unit tests under `android/app/src/test/...` | One per repository/ViewModel. |

### Modified

| Path | Change |
|---|---|
| `android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt` | Version 2, new entities, new DAO getters. |
| `android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt` | Add migration, provide new DAOs. |
| `android/app/src/main/res/menu/bottom_nav_menu.xml` | Swap Downloads → Me. |
| `android/app/src/main/res/navigation/main_tabs_nav.xml` | Add `meFragment` destination; add Settings→Downloads/Favorites actions. |
| `android/app/src/main/res/layout/fragment_settings.xml` + 2 variants | Add "Library" section (Downloads library + Favorites rows). |
| `android/app/src/main/java/com/albunyaan/tube/ui/settings/SettingsFragment.kt` | Wire row clicks. |
| `android/app/src/main/java/com/albunyaan/tube/ui/channel/ChannelDetailFragment.kt` | Subscribe toggle. |
| Channel detail layouts (3 variants) | Subscribe button. |
| `android/app/src/main/java/com/albunyaan/tube/ui/playlist/PlaylistDetailFragment.kt` | Save toggle. |
| Playlist detail layouts (3 variants) | Save button. |
| `android/app/src/main/res/values/strings.xml` (+ ar, nl) | New strings. |
| `docs/TRUE_PROJECT_STATUS.md` + `docs/PROJECT_STATUS.md` | Mark milestone. |

---

## Task 0: Baseline

**Files:** none.

- [ ] **Step 1: Confirm clean baseline build.**

Run:
```
cd /home/farouq/Development/albunyaantube-me/android
./gradlew --offline assembleDebug 2>&1 | tail -40
```
Expected: BUILD SUCCESSFUL. If not, investigate before touching code.

- [ ] **Step 2: Confirm test baseline.**

Run:
```
./gradlew --offline testDebugUnitTest 2>&1 | tail -40
```
Expected: BUILD SUCCESSFUL (or skipped). Note any existing failing tests — do NOT "fix" unrelated work.

---

## Task 1: Room entities + DAOs

**Files:**
- Create: 8 files under `data/local/` (see File Structure).

- [ ] **Step 1: Create `SubscribedChannel.kt`.**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "subscribed_channels")
data class SubscribedChannel(
    @PrimaryKey val channelId: String,
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 2: Create `SubscribedChannelDao.kt`.**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscribedChannelDao {
    @Query("SELECT * FROM subscribed_channels ORDER BY subscribedAt DESC")
    fun observeAll(): Flow<List<SubscribedChannel>>

    @Query("SELECT * FROM subscribed_channels ORDER BY subscribedAt DESC")
    suspend fun getAll(): List<SubscribedChannel>

    @Query("SELECT EXISTS(SELECT 1 FROM subscribed_channels WHERE channelId = :id)")
    fun observeIsSubscribed(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(channel: SubscribedChannel)

    @Query("DELETE FROM subscribed_channels WHERE channelId = :id")
    suspend fun delete(id: String)
}
```

- [ ] **Step 3: Create `SavedPlaylist.kt` and `SavedPlaylistDao.kt`.**

```kotlin
// SavedPlaylist.kt
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

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

```kotlin
// SavedPlaylistDao.kt
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaylistDao {
    @Query("SELECT * FROM saved_playlists ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedPlaylist>>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE playlistId = :id)")
    fun observeIsSaved(id: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: SavedPlaylist)

    @Query("DELETE FROM saved_playlists WHERE playlistId = :id")
    suspend fun delete(id: String)
}
```

- [ ] **Step 4: Create `ChannelVideoCache.kt` and DAO.**

```kotlin
// ChannelVideoCache.kt
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val durationSeconds: Long?,
    val viewCount: Long?,
    val uploadedAt: Long?,
    val isShort: Boolean,
    val fetchedAt: Long = System.currentTimeMillis()
)
```

```kotlin
// ChannelVideoCacheDao.kt
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelVideoCacheDao {

    @Query("""SELECT * FROM channel_video_cache
              WHERE uploadedAt IS NOT NULL AND uploadedAt >= :minUploadedAt
              ORDER BY uploadedAt DESC""")
    fun observeRecent(minUploadedAt: Long): Flow<List<ChannelVideoCache>>

    @Query("""SELECT * FROM channel_video_cache
              WHERE channelId = :channelId
              ORDER BY uploadedAt DESC""")
    suspend fun getForChannel(channelId: String): List<ChannelVideoCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ChannelVideoCache>)

    @Query("DELETE FROM channel_video_cache WHERE channelId = :channelId")
    suspend fun deleteForChannel(channelId: String)

    @Transaction
    suspend fun replaceForChannel(channelId: String, rows: List<ChannelVideoCache>) {
        deleteForChannel(channelId)
        upsertAll(rows)
    }

    @Query("""DELETE FROM channel_video_cache
              WHERE channelId NOT IN (SELECT channelId FROM subscribed_channels)""")
    suspend fun pruneUnsubscribed()
}
```

- [ ] **Step 5: Create `ChannelFeedRefreshState.kt` and DAO.**

```kotlin
// ChannelFeedRefreshState.kt
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channel_feed_refresh_state")
data class ChannelFeedRefreshState(
    @PrimaryKey val channelId: String,
    val lastSuccessfulFetchAt: Long,
    val lastAttemptAt: Long,
    val lastErrorMessage: String?
)
```

```kotlin
// ChannelFeedRefreshStateDao.kt
package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChannelFeedRefreshStateDao {
    @Query("SELECT * FROM channel_feed_refresh_state WHERE channelId = :id")
    suspend fun get(id: String): ChannelFeedRefreshState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ChannelFeedRefreshState)

    @Query("DELETE FROM channel_feed_refresh_state WHERE channelId = :id")
    suspend fun delete(id: String)
}
```

- [ ] **Step 6: Create `Migrations.kt`.**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS subscribed_channels (
                channelId TEXT NOT NULL PRIMARY KEY,
                channelUrl TEXT NOT NULL,
                name TEXT NOT NULL,
                avatarUrl TEXT,
                subscribedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS saved_playlists (
                playlistId TEXT NOT NULL PRIMARY KEY,
                playlistUrl TEXT NOT NULL,
                name TEXT NOT NULL,
                thumbnailUrl TEXT,
                uploaderName TEXT,
                savedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS channel_video_cache (
                videoId TEXT NOT NULL PRIMARY KEY,
                channelId TEXT NOT NULL,
                channelName TEXT NOT NULL,
                title TEXT NOT NULL,
                thumbnailUrl TEXT,
                durationSeconds INTEGER,
                viewCount INTEGER,
                uploadedAt INTEGER,
                isShort INTEGER NOT NULL,
                fetchedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_video_cache_channelId ON channel_video_cache(channelId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_channel_video_cache_uploadedAt ON channel_video_cache(uploadedAt)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS channel_feed_refresh_state (
                channelId TEXT NOT NULL PRIMARY KEY,
                lastSuccessfulFetchAt INTEGER NOT NULL,
                lastAttemptAt INTEGER NOT NULL,
                lastErrorMessage TEXT)"""
        )
    }
}
```

Note on index names: Room autogenerates indices named `index_<table>_<column>` — matching names keeps the generated schema stable for `exportSchema=true`.

- [ ] **Step 7: Update `AppDatabase.kt` to v2.**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteVideo::class,
        SubscribedChannel::class,
        SavedPlaylist::class,
        ChannelVideoCache::class,
        ChannelFeedRefreshState::class,
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteVideoDao(): FavoriteVideoDao
    abstract fun subscribedChannelDao(): SubscribedChannelDao
    abstract fun savedPlaylistDao(): SavedPlaylistDao
    abstract fun channelVideoCacheDao(): ChannelVideoCacheDao
    abstract fun channelFeedRefreshStateDao(): ChannelFeedRefreshStateDao

    companion object {
        const val DATABASE_NAME = "albunyaan_tube_db"
    }
}
```

- [ ] **Step 8: Update `DatabaseModule.kt` with migration + new DAO providers.**

Read the existing file first to preserve structure. Add:

```kotlin
// inside the Room builder chain:
.addMigrations(MIGRATION_1_2)

// Add providers (same @Provides @Singleton pattern as existing favorite DAO):
@Provides @Singleton fun provideSubscribedChannelDao(db: AppDatabase) = db.subscribedChannelDao()
@Provides @Singleton fun provideSavedPlaylistDao(db: AppDatabase) = db.savedPlaylistDao()
@Provides @Singleton fun provideChannelVideoCacheDao(db: AppDatabase) = db.channelVideoCacheDao()
@Provides @Singleton fun provideChannelFeedRefreshStateDao(db: AppDatabase) = db.channelFeedRefreshStateDao()
```

- [ ] **Step 9: Build.**

Run:
```
./gradlew --offline assembleDebug 2>&1 | tail -40
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit.**

```
git add -- android/app/src/main/java/com/albunyaan/tube/data/local android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt
git commit -m "[FEAT]: [ANDROID-PERSONAL-01]: Room v2 schema for subscriptions"
```

---

## Task 2: DAO unit tests (Room in-memory instrumented)

**Files:**
- Test: `android/app/src/androidTest/java/com/albunyaan/tube/data/local/SubscriptionDaoTest.kt`
- Test: `android/app/src/androidTest/java/com/albunyaan/tube/data/local/ChannelVideoCacheDaoTest.kt`

If the project has no `androidTest` Hilt harness, fall back to Robolectric in `test/`. Check `android/app/build.gradle` — if `android.enableUnitTestIncludeAndroidResources` is enabled and Robolectric is on the classpath, prefer `test/` (faster).

- [ ] **Step 1: Inspect existing DAO test pattern.**

Run:
```
ls android/app/src/test/java/com/albunyaan/tube/data/local 2>/dev/null
ls android/app/src/androidTest/java/com/albunyaan/tube/data/local 2>/dev/null
```
If an existing `FavoriteVideoDao` test exists, mirror its setup exactly.

- [ ] **Step 2: Write `SubscriptionDaoTest.kt`.**

Using whichever style matches the existing DAO test. Cover:
  - upsert + observeAll orders by subscribedAt DESC
  - observeIsSubscribed flips after upsert/delete
  - delete is idempotent
  - same three cases for SavedPlaylistDao

Sample test body (adapt to project's style):

```kotlin
@RunWith(AndroidJUnit4::class)
class SubscriptionDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var channels: SubscribedChannelDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        channels = db.subscribedChannelDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun upsertAndObserve() = runTest {
        val c = SubscribedChannel("UCabc", "https://youtube.com/channel/UCabc", "Test", null, 1_000L)
        channels.upsert(c)
        assertEquals(listOf(c), channels.getAll())
    }

    @Test fun observeIsSubscribedFlips() = runTest {
        val flow = channels.observeIsSubscribed("UCabc")
        flow.test {
            assertFalse(awaitItem())
            channels.upsert(SubscribedChannel("UCabc", "u", "n", null, 0L))
            assertTrue(awaitItem())
            channels.delete("UCabc")
            assertFalse(awaitItem())
        }
    }
}
```

- [ ] **Step 3: Write `ChannelVideoCacheDaoTest.kt`.**

Cover:
  - `replaceForChannel` deletes old + inserts new in one tx
  - `observeRecent(minUploadedAt)` excludes rows with null `uploadedAt`
  - `observeRecent` excludes rows older than `minUploadedAt`

- [ ] **Step 4: Run tests.**

```
./gradlew --offline testDebugUnitTest 2>&1 | tail -60
```
Expected: PASS for both new tests. Fix until green.

- [ ] **Step 5: Commit.**

```
git add android/app/src/test android/app/src/androidTest
git commit -m "[TEST]: [ANDROID-PERSONAL-01]: DAO tests for subscriptions + video cache"
```

---

## Task 3: SubscriptionRepository

**Files:**
- Create: `data/subscriptions/SubscriptionRepository.kt`
- Test: `test/.../SubscriptionRepositoryTest.kt`

- [ ] **Step 1: Create `SubscriptionRepository.kt`.**

```kotlin
package com.albunyaan.tube.data.subscriptions

import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SavedPlaylistDao
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SubscriptionRepository @Inject constructor(
    private val channels: SubscribedChannelDao,
    private val playlists: SavedPlaylistDao,
) {
    fun observeSubscribedChannels(): Flow<List<SubscribedChannel>> = channels.observeAll()
    fun observeSavedPlaylists(): Flow<List<SavedPlaylist>> = playlists.observeAll()

    fun isChannelSubscribed(id: String): Flow<Boolean> = channels.observeIsSubscribed(id)
    fun isPlaylistSaved(id: String): Flow<Boolean> = playlists.observeIsSaved(id)

    suspend fun subscribe(channel: SubscribedChannel) = channels.upsert(channel)
    suspend fun unsubscribe(channelId: String) = channels.delete(channelId)

    suspend fun savePlaylist(playlist: SavedPlaylist) = playlists.upsert(playlist)
    suspend fun unsavePlaylist(playlistId: String) = playlists.delete(playlistId)
}
```

- [ ] **Step 2: Write `SubscriptionRepositoryTest.kt`.**

Unit test with in-memory Room (same pattern as DAO tests). One test per method — symmetric pairs (subscribe/unsubscribe, save/unsave).

- [ ] **Step 3: Build + test.**

```
./gradlew --offline :app:testDebugUnitTest 2>&1 | tail -30
```

- [ ] **Step 4: Commit.**

```
git add android/app/src/main/java/com/albunyaan/tube/data/subscriptions android/app/src/test/java/com/albunyaan/tube/data/subscriptions
git commit -m "[FEAT]: [ANDROID-PERSONAL-01]: SubscriptionRepository"
```

---

## Task 4: MeFeedModels

**Files:**
- Create: `data/me/MeFeedModels.kt`

- [ ] **Step 1: Create the file.**

```kotlin
package com.albunyaan.tube.data.me

sealed class ChipItem {
    abstract val id: String
    abstract val label: String
    abstract val imageUrl: String?

    data class Channel(
        override val id: String,
        override val label: String,
        override val imageUrl: String?,
        val channelUrl: String,
    ) : ChipItem()

    data class Playlist(
        override val id: String,
        override val label: String,
        override val imageUrl: String?,
        val playlistUrl: String,
    ) : ChipItem()
}

data class MeFeedVideo(
    val videoId: String,
    val channelId: String,
    val channelName: String,
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long?,
    val viewCount: Long?,
    val uploadedAt: Long,
    val isShort: Boolean,
)

sealed class MeFeedState {
    data object Loading : MeFeedState()
    data object Empty : MeFeedState()
    data class Content(
        val chips: List<ChipItem>,
        val shorts: List<MeFeedVideo>,
        val videos: List<MeFeedVideo>,
        val refreshing: Boolean,
        val filterChannelId: String?,
    ) : MeFeedState()
    data class Error(val message: String) : MeFeedState()
}
```

- [ ] **Step 2: Compile.**

```
./gradlew --offline :app:compileDebugKotlin 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```
git add android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedModels.kt
git commit -m "[FEAT]: [ANDROID-PERSONAL-01]: Me feed models"
```

---

## Task 5: MeFeedRepository

**Files:**
- Create: `data/me/MeFeedRepository.kt`
- Test: `test/.../MeFeedRepositoryTest.kt`

- [ ] **Step 1: Inspect `NewPipeExtractorClient` for the channel-tab / initial-page surface.**

Run:
```
```

Open `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt` and look for methods that return a `ChannelInfo` or that extract a list of `StreamInfoItem` given a channel URL. Use the existing method if one exists; otherwise, call `ChannelInfo.getInfo(service, url)` directly (see `newpipe-extractor.md`).

- [ ] **Step 2: Define a lean facade interface for testability.**

Inside `MeFeedRepository.kt` introduce:

```kotlin
interface ChannelFeedFetcher {
    /** Return the latest page of videos for a channel. Shorts flag is best-effort. */
    suspend fun fetchLatest(channelUrl: String): List<ChannelFeedItem>

    data class ChannelFeedItem(
        val videoId: String,
        val title: String,
        val thumbnailUrl: String?,
        val durationSeconds: Long?,
        val viewCount: Long?,
        val uploadedAt: Long?,
        val isShort: Boolean,
    )
}
```

Implement it in a separate inner `NewPipeChannelFeedFetcher` class (bound in `MeModule`):

```kotlin
@Singleton
class NewPipeChannelFeedFetcher @Inject constructor(
    private val client: NewPipeExtractorClient,
) : ChannelFeedFetcher {
    override suspend fun fetchLatest(channelUrl: String): List<ChannelFeedItem> = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl)
        info.relatedItems
            .filterIsInstance<StreamInfoItem>()
            .map { it.toChannelFeedItem() }
    }

    private fun StreamInfoItem.toChannelFeedItem(): ChannelFeedFetcher.ChannelFeedItem {
        val uploaded = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli()
        return ChannelFeedFetcher.ChannelFeedItem(
            videoId = extractVideoId(url),
            title = name.orEmpty(),
            thumbnailUrl = thumbnails.firstOrNull()?.url,
            durationSeconds = if (duration > 0) duration else null,
            viewCount = if (viewCount >= 0) viewCount else null,
            uploadedAt = uploaded,
            isShort = isShortFormContent,
        )
    }

    private fun extractVideoId(url: String): String =
        Regex("""(?:v=|youtu\.be/|shorts/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1).orEmpty()
}
```

(If `NewPipeExtractorClient` already exposes a richer channel method, prefer it — wire it through the facade.)

- [ ] **Step 3: Create `MeFeedRepository.kt`.**

```kotlin
package com.albunyaan.tube.data.me

import com.albunyaan.tube.data.local.ChannelFeedRefreshState
import com.albunyaan.tube.data.local.ChannelFeedRefreshStateDao
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Singleton
class MeFeedRepository @Inject constructor(
    private val subs: SubscriptionRepository,
    private val cache: ChannelVideoCacheDao,
    private val refresh: ChannelFeedRefreshStateDao,
    private val fetcher: ChannelFeedFetcher,
    @Named("io") private val io: CoroutineDispatcher,
) {
    companion object {
        const val CACHE_TTL_MS = 30L * 60_000L
        const val FEED_WINDOW_MS = 14L * 24L * 60L * 60L * 1_000L // 14 days
        const val MAX_CONCURRENT = 4
        const val STAGGER_MS = 250L
        const val MAX_CHANNELS_PER_REFRESH = 50
        const val MAX_ITEMS_PER_CHANNEL = 30
    }

    private val semaphore = Semaphore(MAX_CONCURRENT)

    /** Cache-backed feed stream. Does NOT trigger fetching — call [refresh] for that. */
    fun observeFeed(): Flow<List<ChannelVideoCache>> {
        val cutoff = System.currentTimeMillis() - FEED_WINDOW_MS
        return cache.observeRecent(cutoff).distinctUntilChanged()
    }

    /** Refresh subscribed channels whose cache is older than TTL. */
    suspend fun refresh(force: Boolean = false): Unit = withContext(io) {
        val channels = subs.observeSubscribedChannels().let { flow ->
            // snapshot once
            var latest: List<SubscribedChannel> = emptyList()
            flow.map { it }.also { latest = firstOrEmpty(flow) }
            latest
        }
        val now = System.currentTimeMillis()
        val candidates = channels
            .asSequence()
            .sortedByDescending { it.subscribedAt }
            .take(MAX_CHANNELS_PER_REFRESH)
            .toList()

        coroutineScope {
            candidates.mapIndexed { index, channel ->
                async {
                    delay(STAGGER_MS * index)
                    semaphore.withPermit { refreshOne(channel, now, force) }
                }
            }.forEach { it.await() }
        }
    }

    private suspend fun refreshOne(channel: SubscribedChannel, now: Long, force: Boolean) {
        val state = refresh.get(channel.channelId)
        val fresh = state != null && (now - state.lastSuccessfulFetchAt) < CACHE_TTL_MS
        if (fresh && !force) return
        try {
            val rows = fetcher.fetchLatest(channel.channelUrl)
                .filter { it.uploadedAt != null }
                .sortedByDescending { it.uploadedAt }
                .take(MAX_ITEMS_PER_CHANNEL)
                .map { it.toCacheRow(channel, now) }
            cache.replaceForChannel(channel.channelId, rows)
            refresh.upsert(
                ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = now,
                    lastAttemptAt = now,
                    lastErrorMessage = null,
                )
            )
        } catch (t: Throwable) {
            refresh.upsert(
                ChannelFeedRefreshState(
                    channelId = channel.channelId,
                    lastSuccessfulFetchAt = state?.lastSuccessfulFetchAt ?: 0L,
                    lastAttemptAt = now,
                    lastErrorMessage = t.message ?: t::class.java.simpleName,
                )
            )
        }
    }

    private fun ChannelFeedFetcher.ChannelFeedItem.toCacheRow(
        channel: SubscribedChannel,
        now: Long,
    ) = ChannelVideoCache(
        videoId = videoId,
        channelId = channel.channelId,
        channelName = channel.name,
        title = title,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = viewCount,
        uploadedAt = uploadedAt,
        isShort = isShort,
        fetchedAt = now,
    )

    private suspend fun firstOrEmpty(flow: Flow<List<SubscribedChannel>>): List<SubscribedChannel> {
        var latest: List<SubscribedChannel> = emptyList()
        kotlinx.coroutines.flow.first(flow).also { latest = it }
        return latest
    }
}
```

Note: the `firstOrEmpty` helper is a belt-and-braces one-shot snapshot. If you have `kotlinx.coroutines.flow.first` already imported in the file, replace the body with `flow.first()` directly.

- [ ] **Step 4: Provide an `@Named("io")` dispatcher** — look in existing Hilt modules; if not present, add to `MeModule.kt` in Task 6.

- [ ] **Step 5: Write `MeFeedRepositoryTest.kt`.**

Fake the fetcher; use in-memory Room for DAOs. Tests:
  - Cache hit: if `ChannelFeedRefreshState.lastSuccessfulFetchAt` is recent, `refresh()` does NOT call fetcher.
  - Force refresh bypasses cache.
  - Fetcher throws for channel A, succeeds for channel B → channel B is upserted, channel A's error recorded.
  - Items older than 14 days are absent from `observeFeed()`.
  - Null `uploadedAt` items are dropped from the cache write path.

- [ ] **Step 6: Build + test.**

```
./gradlew --offline :app:testDebugUnitTest 2>&1 | tail -40
```

- [ ] **Step 7: Commit.**

```
git add android/app/src/main/java/com/albunyaan/tube/data/me android/app/src/test/java/com/albunyaan/tube/data/me
git commit -m "[FEAT]: [ANDROID-PERSONAL-01]: MeFeedRepository with bounded-concurrency fetch"
```

---

## Task 6: MeModule (Hilt)

**Files:**
- Create: `di/MeModule.kt`

- [ ] **Step 1: Write the module.**

```kotlin
package com.albunyaan.tube.di

import com.albunyaan.tube.data.me.ChannelFeedFetcher
import com.albunyaan.tube.data.me.NewPipeChannelFeedFetcher
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
abstract class MeModule {
    @Binds @Singleton
    abstract fun bindChannelFeedFetcher(impl: NewPipeChannelFeedFetcher): ChannelFeedFetcher

    companion object {
        @Provides @Singleton @Named("io")
        fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
    }
}
```

If an `@Named("io")` dispatcher is already provided elsewhere, remove the companion here to avoid duplicate bindings — `./gradlew assembleDebug` will tell you.

- [ ] **Step 2: Build.**

```
./gradlew --offline assembleDebug 2>&1 | tail -30
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```
git add android/app/src/main/java/com/albunyaan/tube/di/MeModule.kt
git commit -m "[FEAT]: [ANDROID-PERSONAL-01]: Hilt module for Me feature"
```

---

## Task 7: Strings + nav icon + nav menu

**Files:**
- Modify: `res/values/strings.xml`, `res/values-ar/strings.xml`, `res/values-nl/strings.xml`
- Create: `res/drawable/ic_nav_me.xml`
- Modify: `res/menu/bottom_nav_menu.xml`

- [ ] **Step 1: Add strings.**

Add to all three locales:

```xml
<string name="nav_me">Me</string>                  <!-- ar: أنت  nl: Jij -->
<string name="me_empty_title">Subscribe to channels to see your feed</string>
<string name="me_empty_subtitle">Your subscribed channels will appear here with the latest videos and shorts.</string>
<string name="me_empty_cta">Browse channels</string>
<string name="me_section_shorts">Shorts</string>
<string name="me_section_videos">Latest videos</string>
<string name="me_refresh_error">Couldn\'t refresh your feed. Pull to try again.</string>
<string name="channel_subscribe">Subscribe</string>
<string name="channel_unsubscribe">Subscribed</string>
<string name="playlist_save">Save</string>
<string name="playlist_unsave">Saved</string>
<string name="settings_library_header">Library</string>
<string name="settings_downloads_library">Downloads library</string>
<string name="settings_favorites_title">Favorites</string>
```

Arabic + Dutch translations: keep semantic fidelity; if unsure, copy English and TODO-comment — NO wait, "no placeholders" rule. Translate now. Use best-effort natural Arabic and Dutch.

- [ ] **Step 2: Create `ic_nav_me.xml`.**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"/>
</vector>
```

**Critical:** no `android:tint` on root. `fillColor` is `#FFFFFFFF` — `itemIconTint` on the NavigationBarView handles colorization.

- [ ] **Step 3: Update `bottom_nav_menu.xml`.**

Replace the Downloads `<item>` with:

```xml
<item
    android:id="@+id/meFragment"
    android:icon="@drawable/ic_nav_me"
    android:title="@string/nav_me" />
```

- [ ] **Step 4: Build.**

```
./gradlew --offline assembleDebug 2>&1 | tail -20
```
Expect BUILD FAILURE if `@+id/meFragment` isn't in the nav graph yet — that's fine, we'll fix in Task 8.

- [ ] **Step 5: Don't commit yet** — wait for Task 8 to land the destination.

---

## Task 8: Nav graph + MeFragment skeleton

**Files:**
- Modify: `res/navigation/main_tabs_nav.xml`
- Create: `ui/me/MeFragment.kt` (skeleton)
- Create: `res/layout/fragment_me.xml` (skeleton)

- [ ] **Step 1: Add `meFragment` destination in `main_tabs_nav.xml`.**

```xml
<fragment
    android:id="@+id/meFragment"
    android:name="com.albunyaan.tube.ui.me.MeFragment"
    android:label="@string/nav_me"
    tools:layout="@layout/fragment_me" />
```

Also add actions from Settings:

```xml
<fragment android:id="@+id/settingsFragment" ...>
    <action android:id="@+id/action_settingsFragment_to_downloadsFragment"
            app:destination="@id/downloadsFragment" />
    <action android:id="@+id/action_settingsFragment_to_favoritesFragment"
            app:destination="@id/favoritesFragment" />
    <!-- keep existing aboutFragment action -->
</fragment>
```

(Do NOT remove the existing downloadsFragment destination — it stays reachable.)

- [ ] **Step 2: Create `MeFragment.kt` skeleton.**

```kotlin
package com.albunyaan.tube.ui.me

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.albunyaan.tube.R

class MeFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_me, container, false)
}
```

(We'll add Hilt + ViewModel wiring in Task 10.)

- [ ] **Step 3: Create minimal `fragment_me.xml` in `layout/`.**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/meRecycler"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:clipToPadding="false"
        android:paddingBottom="@dimen/bottom_nav_height" />
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

Skeleton sw600dp and sw720dp variants can wait — single layout enough for compile.

- [ ] **Step 4: Build.**

```
./gradlew --offline assembleDebug 2>&1 | tail -20
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit tasks 7 + 8.**

```
git add android/app/src/main/res android/app/src/main/java/com/albunyaan/tube/ui/me
git commit -m "[FEAT]: [ANDROID-PERSONAL-01]: Me tab nav entry, icon, strings, skeleton"
```

---

## Task 9: MeViewModel

**Files:**
- Create: `ui/me/MeViewModel.kt`
- Test: `test/.../MeViewModelTest.kt`

- [ ] **Step 1: Write the ViewModel.**

```kotlin
package com.albunyaan.tube.ui.me

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeFeedVideo
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MeViewModel @Inject constructor(
    private val subs: SubscriptionRepository,
    private val feed: MeFeedRepository,
) : ViewModel() {

    private val filter = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<MeFeedState> = combine(
        subs.observeSubscribedChannels(),
        subs.observeSavedPlaylists(),
        feed.observeFeed(),
        filter,
        refreshing,
    ) { channels, playlists, cached, filterId, isRefreshing ->
        if (channels.isEmpty() && playlists.isEmpty()) {
            MeFeedState.Empty
        } else {
            val chips = buildChips(channels, playlists)
            val filtered = if (filterId == null) cached else cached.filter { it.channelId == filterId }
            val shorts = filtered.asSequence().filter { it.isShort }.map { it.toVideo() }.toList()
            val videos = filtered.asSequence().filterNot { it.isShort }.map { it.toVideo() }.toList()
            MeFeedState.Content(chips, shorts, videos, isRefreshing, filterId)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeFeedState.Loading)

    init {
        refreshFeed(force = false)
    }

    fun setFilter(channelId: String?) { filter.value = channelId }

    fun refreshFeed(force: Boolean = true) {
        viewModelScope.launch {
            refreshing.value = true
            try { feed.refresh(force) } finally { refreshing.value = false }
        }
    }

    private fun buildChips(
        channels: List<SubscribedChannel>,
        playlists: List<SavedPlaylist>,
    ): List<ChipItem> = buildList {
        channels.forEach {
            add(ChipItem.Channel(it.channelId, it.name, it.avatarUrl, it.channelUrl))
        }
        playlists.forEach {
            add(ChipItem.Playlist(it.playlistId, it.name, it.thumbnailUrl, it.playlistUrl))
        }
    }

    private fun com.albunyaan.tube.data.local.ChannelVideoCache.toVideo() = MeFeedVideo(
        videoId = videoId,
        channelId = channelId,
        channelName = channelName,
        title = title,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = viewCount,
        uploadedAt = uploadedAt ?: 0L,
        isShort = isShort,
    )
}
```

- [ ] **Step 2: Unit tests.**

Using fakes for both repositories. Cover:
  - Empty state: both subs flows empty → `MeFeedState.Empty`.
  - Content state: chips contain channels + playlists in order.
  - Filter: setting `filterChannelId` scopes shorts + videos to that channel.
  - `refreshFeed()` flips `refreshing` while in flight.

- [ ] **Step 3: Build + test.**

- [ ] **Step 4: Commit.**

```
git commit -am "[FEAT]: [ANDROID-PERSONAL-01]: MeViewModel"
```

---

## Task 10: MeFragment + adapters (full)

**Files:**
- Modify: `ui/me/MeFragment.kt`
- Create: `ui/me/MeChipsAdapter.kt`, `ui/me/MeShortsAdapter.kt`, `ui/me/MeVideosAdapter.kt`
- Create: layouts listed in File Structure

- [ ] **Step 1: Create item layouts.**

`item_me_chip.xml`:

```xml
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginEnd="@dimen/spacing_sm"
    app:cardCornerRadius="28dp">
    <LinearLayout
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:gravity="center_vertical"
        android:padding="@dimen/spacing_sm"
        android:orientation="horizontal">
        <com.google.android.material.imageview.ShapeableImageView
            android:id="@+id/chipAvatar"
            android:layout_width="32dp"
            android:layout_height="32dp"
            app:shapeAppearanceOverlay="@style/ShapeAppearance.Material3.Corner.Full" />
        <TextView
            android:id="@+id/chipLabel"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginStart="@dimen/spacing_sm"
            android:textAlignment="viewStart"
            android:textAppearance="?attr/textAppearanceTitleSmall" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

`item_me_short.xml`: 120dp × 200dp card with thumbnail + title.
`item_me_video.xml`: horizontal row, 16:9 thumbnail + title/meta.
`item_me_empty.xml`: centered icon + title + subtitle + "Browse channels" button.
`item_me_shorts_section.xml`: header label + nested horizontal RV (id `meShortsRecycler`).

(Write these out fully in the actual file — the plan shows the pattern; exhaustive XML is in the spec.)

- [ ] **Step 2: Create the three adapters.**

- `MeChipsAdapter(onClick: (ChipItem) -> Unit, onClearFilter: () -> Unit)` — `ListAdapter<ChipItem, VH>` with `DiffUtil`. Selected state tracked via a `selectedId: String?` setter that triggers `notifyItemChanged` on old/new selected items.
- `MeShortsAdapter(onClick: (MeFeedVideo) -> Unit)` — `ListAdapter<MeFeedVideo, VH>`.
- `MeVideosAdapter(onClick: (MeFeedVideo) -> Unit)` — `ListAdapter<MeFeedVideo, VH>`.

Each adapter uses Glide (or whatever image loader is wired in `DataModule`) for thumbnails.

- [ ] **Step 3: Update `fragment_me.xml` to the full layout.**

Replace skeleton with:

```xml
<androidx.coordinatorlayout.widget.CoordinatorLayout ...>
    <com.google.android.material.appbar.AppBarLayout ...>
        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/meToolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            app:title="@string/nav_me" />
    </com.google.android.material.appbar.AppBarLayout>

    <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        android:id="@+id/meSwipeRefresh"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:layout_behavior="@string/appbar_scrolling_view_behavior">
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/meRecycler"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:paddingBottom="@dimen/bottom_nav_height" />
    </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 4: Duplicate layouts into `layout-sw600dp/` and `layout-sw720dp/`** with identical IDs. For these variants set `meRecycler` padding bottom `0dp` (tablet/TV don't have bottom nav).

- [ ] **Step 5: Wire `MeFragment` (full).**

```kotlin
@AndroidEntryPoint
class MeFragment : Fragment(R.layout.fragment_me) {
    private val viewModel: MeViewModel by viewModels()
    private var _binding: FragmentMeBinding? = null
    private val binding get() = _binding!!

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentMeBinding.bind(view)

        val chipsAdapter = MeChipsAdapter(
            onClick = { chip ->
                when (chip) {
                    is ChipItem.Channel -> viewModel.setFilter(
                        if (viewModel.state.value.filterIdOrNull() == chip.id) null else chip.id
                    )
                    is ChipItem.Playlist -> navigateToPlaylist(chip.id)
                }
            },
        )
        val shortsAdapter = MeShortsAdapter { navigateToPlayer(it) }
        val videosAdapter = MeVideosAdapter { navigateToPlayer(it) }

        val concat = ConcatAdapter(chipsAdapter.asRow(), shortsAdapter.asSection(R.string.me_section_shorts), videosAdapter.withHeader(R.string.me_section_videos))
        binding.meRecycler.adapter = concat

        // Multi-device layout
        val isTablet = resources.getBoolean(R.bool.isTablet)     // existing qualifier boolean if present
        binding.meRecycler.layoutManager = if (isTablet) {
            GridLayoutManager(requireContext(), if (resources.getBoolean(R.bool.isLargeTablet)) 3 else 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int) =
                        if (concat.getItemViewType(position) == videoViewType) 1 else spanCount
                }
            }
        } else LinearLayoutManager(requireContext())

        binding.meSwipeRefresh.setOnRefreshListener { viewModel.refreshFeed(force = true) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { s -> render(s, chipsAdapter, shortsAdapter, videosAdapter) }
            }
        }

        // Auto-loadMore when content fits without scrolling
        binding.meRecycler.post {
            if (!binding.meRecycler.canScrollVertically(1)) {
                // No pagination server-side; safe no-op. Keep pattern for future.
            }
        }
    }

    private fun render(
        state: MeFeedState,
        chips: MeChipsAdapter,
        shorts: MeShortsAdapter,
        videos: MeVideosAdapter,
    ) {
        when (state) {
            MeFeedState.Loading -> binding.meSwipeRefresh.isRefreshing = true
            MeFeedState.Empty -> showEmpty()
            is MeFeedState.Content -> {
                binding.meSwipeRefresh.isRefreshing = state.refreshing
                chips.selectedId = state.filterChannelId
                chips.submitList(state.chips)
                shorts.submitList(state.shorts)
                videos.submitList(state.videos)
                binding.meRecycler.post {
                    if (!binding.meRecycler.canScrollVertically(1)) viewModel.refreshFeed(false)
                }
            }
            is MeFeedState.Error -> showError(state.message)
        }
    }

    override fun onDestroyView() { _binding = null; super.onDestroyView() }
}
```

**Note:** the above is a sketch; precise APIs (`asRow`, `asSection`, `withHeader`) won't exist — collapse these into the three concrete adapter classes and a single `ConcatAdapter` composition in the fragment.

- [ ] **Step 6: Build.**

```
./gradlew --offline assembleDebug 2>&1 | tail -30
```

- [ ] **Step 7: Commit.**

```
git commit -am "[FEAT]: [ANDROID-PERSONAL-01]: Me tab UI — adapters, layouts, fragment"
```

---

## Task 11: Subscribe / Save toggles on detail screens

**Files:**
- Modify: `ChannelDetailFragment.kt` + its layouts (3 variants).
- Modify: `PlaylistDetailFragment.kt` + its layouts (3 variants).

- [ ] **Step 1: Inspect existing detail layouts.**

Open `fragment_channel_detail.xml` (all 3 variants) and find an anchor near the channel header (avatar + name + subscriber count).

- [ ] **Step 2: Add subscribe button.**

```xml
<com.google.android.material.button.MaterialButton
    android:id="@+id/subscribeButton"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:layout_marginStart="@dimen/spacing_md"
    android:textAlignment="viewStart"
    style="?attr/materialButtonStyle" />
```

Text/style toggles in code based on subscription state.

- [ ] **Step 3: Wire in `ChannelDetailFragment`.**

Inject `SubscriptionRepository` via a lightweight ViewModel extension, OR extend the existing `ChannelDetailViewModel` (preferred). Observe `isChannelSubscribed(id)` → toggle button label + style + add click listener that calls `subscribe`/`unsubscribe`.

- [ ] **Step 4: Mirror the pattern for PlaylistDetailFragment.**

- [ ] **Step 5: Build.**

- [ ] **Step 6: Commit.**

```
git commit -am "[FEAT]: [ANDROID-PERSONAL-01]: Subscribe/Save toggles on detail screens"
```

---

## Task 12: Settings restructure (Library row + Downloads + Favorites entries)

**Files:**
- Modify: `fragment_settings.xml` (3 variants).
- Create: `settings_item_downloads_library.xml`, `settings_item_favorites.xml`.
- Modify: `SettingsFragment.kt`.

- [ ] **Step 1: Create the two new `settings_item_*` layouts** following the existing `settings_item_*` pattern (look at `settings_item_language.xml` or similar — match styling precisely).

- [ ] **Step 2: Insert a "Library" section in each `fragment_settings.xml` variant**, above the existing Downloads preferences section.

```xml
<TextView
    android:id="@+id/settingsLibraryHeader"
    ... text="@string/settings_library_header" ... />
<include layout="@layout/settings_item_downloads_library"
         android:id="@+id/itemDownloadsLibrary" />
<include layout="@layout/settings_item_favorites"
         android:id="@+id/itemFavorites" />
```

- [ ] **Step 3: Wire clicks in `SettingsFragment.kt`.**

```kotlin
binding.itemDownloadsLibrary.root.setOnClickListener {
    findNavController().navigate(R.id.action_settingsFragment_to_downloadsFragment)
}
binding.itemFavorites.root.setOnClickListener {
    findNavController().navigate(R.id.action_settingsFragment_to_favoritesFragment)
}
```

- [ ] **Step 4: Build.**

- [ ] **Step 5: Commit.**

```
git commit -am "[FEAT]: [ANDROID-PERSONAL-01]: Settings Library section with Downloads + Favorites rows"
```

---

## Task 13: Verification pass

- [ ] **Step 1: Full build.**
```
./gradlew --offline clean assembleDebug 2>&1 | tail -60
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Unit tests.**
```
./gradlew --offline :app:testDebugUnitTest 2>&1 | tail -80
```
Expected: all tests pass.

- [ ] **Step 3: Lint (informational).**
```
./gradlew --offline :app:lintDebug 2>&1 | tail -60
```
Expected: no new errors introduced.

- [ ] **Step 4: Manual-QA list.**

Write a note in docs/TRUE_PROJECT_STATUS.md listing what the user must visually verify:
- Me tab empty state on phone / tablet / TV / RTL (Arabic).
- Me tab populated with real subscriptions.
- Subscribe/unsubscribe flow from channel detail.
- Save/unsave flow from playlist detail.
- Settings → Downloads library opens Downloads.
- Settings → Favorites opens Favorites.
- Samsung S25 Ultra (Android 15) — nav icon visible, no double inset.

- [ ] **Step 5: Commit status update.**

```
git add docs/TRUE_PROJECT_STATUS.md docs/PROJECT_STATUS.md
git commit -m "[DOCS]: [ANDROID-PERSONAL-01]: Status update — Me tab landed, manual QA list"
```

---

## Task 14: Self-review

- [ ] **Step 1: Run `code-review:code-review` over the diff against `develop`.**
- [ ] **Step 2: Address findings in new atomic commits.**
- [ ] **Step 3: Stop before merging.** User reviews the branch before it lands on develop.

---

## Spec coverage matrix

| Spec section | Task(s) |
|---|---|
| §4 Data model | Task 1 |
| §4 Migration | Task 1, Step 6 |
| §5.1 SubscriptionRepository | Task 3 |
| §5.2 MeFeedRepository (bounded concurrency, TTL, 14d) | Task 5 |
| §5.2 Community posts deferred | N/A (documented in spec §3) |
| §5.3 MeViewModel | Task 9 |
| §5.4 Hilt | Task 6 |
| §6 UI (fragment, chips, shorts, videos) | Tasks 8, 10 |
| §6.2 Layout variants | Task 10, Step 4 |
| §6.3 Auto-loadMore | Task 10, Step 5 |
| §6.4 Subscribe/Save toggles | Task 11 |
| §6.5 Nav icon | Task 7 |
| §6.6 Strings en/ar/nl | Task 7 |
| §7 Nav menu swap | Task 7, Step 3 |
| §7 Nav graph destination + Settings actions | Task 8 |
| §7 Settings Library section | Task 12 |
| §9 Testing | Tasks 2, 3, 5, 9 |
| §10 Migration | Task 1, Step 6 |
| §12 Definition of Done | Tasks 13, 14 |
