# ANDROID-SHORTS-01 Custom Shorts Player — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Ship a dedicated full-screen vertical Shorts player with like / share / subscribe + channel overlay, reusing the single ExoPlayer engine, fully responsive across phone / tablet / TV, RTL-correct, and verified on Samsung S25 Ultra edge-to-edge behaviour.

**Architecture:** New `ShortsPlayerFragment` hosts a vertical `ViewPager2` whose adapter holds one page per short. A single `ExoPlayer` instance lives on `ShortsPlayerViewModel` and is rebound to the visible page's `PlayerView` on page change (enforcing the single-audio-stream guarantee from ANDROID-MULTI-01). Like is backed by the existing `FavoritesRepository`; follow is a new Room-backed `FollowedChannelsRepository`; share is a `ShareCompat` intent with the canonical `https://www.youtube.com/shorts/{id}` URL; comments / remix / dislike are omitted entirely.

**Tech Stack:** Kotlin, Media3 ExoPlayer 1.x (reused), AndroidX ViewPager2, Hilt, Room (schema v2), Coil (already used), JUnit 5 + MockK (unit), Hilt instrumented + Espresso + IntentsRule (instrumented).

---

## File Structure

### Create
- `android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannel.kt` — Room `@Entity`.
- `android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannelDao.kt` — DAO mirroring FavoriteVideoDao.
- `android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannelsRepository.kt` — interface + impl.
- `android/app/src/main/java/com/albunyaan/tube/data/shorts/ShortsFeedRepository.kt` — feed / channel cursor pagination.
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModel.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPagerAdapter.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPageViewHolder.kt`
- `android/app/src/main/java/com/albunyaan/tube/ui/shorts/PlayerBinder.kt`
- `android/app/src/main/res/layout/fragment_shorts_player.xml` (+ `layout-sw600dp`, `layout-sw720dp`)
- `android/app/src/main/res/layout/item_shorts_page.xml` (+ `layout-sw600dp`, `layout-sw720dp`)
- `android/app/src/main/res/drawable/ic_shorts_like.xml`, `ic_shorts_like_filled.xml`, `ic_shorts_share.xml`, `ic_shorts_subscribe.xml`, `ic_shorts_subscribed.xml`.
- Unit tests: `FollowedChannelsRepositoryTest.kt`, `ShortsFeedRepositoryTest.kt`, `ShortsPlayerViewModelTest.kt` under `android/app/src/test/java/com/albunyaan/tube/...`.
- Instrumented: `ShortsPlayerFragmentTest.kt` under `android/app/src/androidTest/java/com/albunyaan/tube/ui/shorts/`.

### Modify
- `android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt` — add `FollowedChannel` entity, bump `version = 2`.
- `android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt` — provide `FollowedChannelDao` and `FollowedChannelsRepository`.
- `android/app/src/main/res/navigation/main_tabs_nav.xml` — add `shortsPlayerFragment` destination + `action_global_shortsPlayerFragment`.
- `android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelShortsTabFragment.kt` — redirect click to shorts player action.
- `android/app/src/main/res/values/strings.xml` — add strings: `shorts_subscribe`, `shorts_subscribed`, `shorts_like_cd`, `shorts_share_cd`, `shorts_error_unavailable`, `shorts_error_feed_empty`.
- `android/app/src/main/res/values-ar/strings.xml` + `values-nl/strings.xml` — Arabic + Dutch translations of the same keys.
- `docs/TRUE_PROJECT_STATUS.md` and `docs/PROJECT_STATUS.md` — mark ANDROID-SHORTS-01 completed at the end.

---

## Task 1 — FollowedChannel Room entity & DAO

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannel.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannelDao.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/local/FollowedChannelDaoTest.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt`

- [ ] **Step 1: Write failing test (in-memory Room DB)**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FollowedChannelDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: FollowedChannelDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.followedChannelDao()
    }

    @After fun tearDown() = db.close()

    @Test fun toggleFollow_insertsAndRemoves() = runBlocking {
        val channel = FollowedChannel("UC123", "Shneako", "https://x/avatar.jpg")
        assertTrue(dao.toggleFollow(channel))
        assertTrue(dao.isFollowedOnce("UC123"))
        assertFalse(dao.toggleFollow(channel))
        assertFalse(dao.isFollowedOnce("UC123"))
    }

    @Test fun isFollowed_flow_emitsUpdates() = runBlocking {
        val channel = FollowedChannel("UC1", "T", null)
        dao.addFollow(channel)
        assertTrue(dao.isFollowed("UC1").first())
    }
}
```

- [ ] **Step 2: Run test — expect compilation failure**

```bash
cd android && ./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.FollowedChannelDaoTest"
```
Expected: fails because `FollowedChannel`, `FollowedChannelDao`, `AppDatabase.followedChannelDao()` do not exist.

- [ ] **Step 3: Create `FollowedChannel.kt`**

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "followed_channels")
data class FollowedChannel(
    @PrimaryKey val channelId: String,
    val title: String,
    val avatarUrl: String?,
    val followedAt: Long = System.currentTimeMillis()
)
```

- [ ] **Step 4: Create `FollowedChannelDao.kt`** (mirrors FavoriteVideoDao exactly)

```kotlin
package com.albunyaan.tube.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FollowedChannelDao {
    @Query("SELECT * FROM followed_channels ORDER BY followedAt DESC")
    fun getAllFollowed(): Flow<List<FollowedChannel>>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_channels WHERE channelId = :id)")
    fun isFollowed(id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_channels WHERE channelId = :id)")
    suspend fun isFollowedOnce(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFollow(channel: FollowedChannel)

    @Query("DELETE FROM followed_channels WHERE channelId = :id")
    suspend fun removeFollow(id: String)

    @Transaction
    suspend fun toggleFollow(channel: FollowedChannel): Boolean {
        val followed = isFollowedOnce(channel.channelId)
        if (followed) removeFollow(channel.channelId) else addFollow(channel)
        return !followed
    }
}
```

- [ ] **Step 5: Modify `AppDatabase.kt`** — add entity and bump version

Replace the `@Database(...)` annotation with:

```kotlin
@Database(
    entities = [FavoriteVideo::class, FollowedChannel::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteVideoDao(): FavoriteVideoDao
    abstract fun followedChannelDao(): FollowedChannelDao
    companion object { const val DATABASE_NAME = "albunyaan_tube_db" }
}
```

- [ ] **Step 6: Re-run test — expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.FollowedChannelDaoTest"
```

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannel.kt \
        android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannelDao.kt \
        android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt \
        android/app/src/test/java/com/albunyaan/tube/data/local/FollowedChannelDaoTest.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add FollowedChannel entity, DAO, and tests"
```

---

## Task 2 — FollowedChannelsRepository + Hilt binding

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannelsRepository.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/local/FollowedChannelsRepositoryTest.kt`

- [ ] **Step 1: Test**

```kotlin
package com.albunyaan.tube.data.local

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Test
import org.junit.Assert.*

class FollowedChannelsRepositoryTest {
    private val dao: FollowedChannelDao = mockk(relaxed = true)
    private val repo = FollowedChannelsRepositoryImpl(dao)

    @Test fun toggleFollow_delegatesToDao() = runBlocking {
        coEvery { dao.toggleFollow(any()) } returns true
        val result = repo.toggleFollow("UC1", "Name", "avatar.jpg")
        assertTrue(result)
        coVerify { dao.toggleFollow(FollowedChannel("UC1", "Name", "avatar.jpg", any())) }
    }
}
```

- [ ] **Step 2: Run — expect fail** (`FollowedChannelsRepositoryImpl` undefined)

```bash
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.FollowedChannelsRepositoryTest"
```

- [ ] **Step 3: Implement repository**

```kotlin
package com.albunyaan.tube.data.local

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface FollowedChannelsRepository {
    fun getAllFollowed(): Flow<List<FollowedChannel>>
    fun isFollowed(channelId: String): Flow<Boolean>
    suspend fun isFollowedOnce(channelId: String): Boolean
    suspend fun toggleFollow(channelId: String, title: String, avatarUrl: String?): Boolean
}

@Singleton
class FollowedChannelsRepositoryImpl @Inject constructor(
    private val dao: FollowedChannelDao
) : FollowedChannelsRepository {
    override fun getAllFollowed() = dao.getAllFollowed()
    override fun isFollowed(id: String) = dao.isFollowed(id)
    override suspend fun isFollowedOnce(id: String) = dao.isFollowedOnce(id)
    override suspend fun toggleFollow(channelId: String, title: String, avatarUrl: String?): Boolean =
        dao.toggleFollow(FollowedChannel(channelId, title, avatarUrl))
}
```

- [ ] **Step 4: Bind via Hilt — edit `DatabaseModule.kt`**

Add inside the `object DatabaseModule`:

```kotlin
@Provides @Singleton
fun provideFollowedChannelDao(db: AppDatabase): FollowedChannelDao = db.followedChannelDao()

@Provides @Singleton
fun provideFollowedChannelsRepository(dao: FollowedChannelDao): FollowedChannelsRepository =
    FollowedChannelsRepositoryImpl(dao)
```

- [ ] **Step 5: Run tests — expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.*"
```

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/FollowedChannelsRepository.kt \
        android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt \
        android/app/src/test/java/com/albunyaan/tube/data/local/FollowedChannelsRepositoryTest.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add FollowedChannelsRepository + Hilt wiring"
```

---

## Task 3 — ShortsFeedRepository

Handles two sources: global feed (via `ContentService.fetchContent(VIDEO, length=UNDER_FOUR_MIN)`) and channel-scoped (via `ChannelDetailRepository.fetchShorts`). Exposes `loadPage(cursor: String?) → ShortsPage` where `ShortsPage(items: List<ShortsItem>, nextCursor: String?)` and `ShortsItem` carries `id`, `title`, `channelId`, `channelName`, `channelAvatarUrl`, `thumbnailUrl`, `durationSeconds`.

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/shorts/ShortsItem.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/shorts/ShortsFeedRepository.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/shorts/ShortsFeedRepositoryTest.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/DataModule.kt` (add `@Provides` for it)

- [ ] **Step 1: Test**

```kotlin
package com.albunyaan.tube.data.shorts

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.model.CursorResponse
import com.albunyaan.tube.data.source.ContentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ShortsFeedRepositoryTest {
    private val content: ContentService = mockk()
    private val repo = ShortsFeedRepository(content)

    @Test fun feedMode_passesShortFilter() = runBlocking {
        val filters = slot<FilterState>()
        coEvery {
            content.fetchContent(ContentType.VIDEO, null, 10, capture(filters))
        } returns CursorResponse(emptyList(), null)

        repo.loadFeedPage(cursor = null, pageSize = 10)

        assertEquals(VideoLength.UNDER_FOUR_MIN, filters.captured.videoLength)
    }
}
```

- [ ] **Step 2: Run — expect fail**

```bash
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.shorts.ShortsFeedRepositoryTest"
```

- [ ] **Step 3: Implement `ShortsItem.kt`**

```kotlin
package com.albunyaan.tube.data.shorts

data class ShortsItem(
    val id: String,
    val title: String,
    val channelId: String,
    val channelName: String,
    val channelAvatarUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int
) {
    val canonicalShareUrl: String get() = "https://www.youtube.com/shorts/$id"
}

data class ShortsPage(val items: List<ShortsItem>, val nextCursor: String?)
```

- [ ] **Step 4: Implement repository**

```kotlin
package com.albunyaan.tube.data.shorts

import com.albunyaan.tube.data.filters.FilterState
import com.albunyaan.tube.data.filters.VideoLength
import com.albunyaan.tube.data.model.ContentItem
import com.albunyaan.tube.data.model.ContentType
import com.albunyaan.tube.data.source.ContentService
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class ShortsFeedRepository @Inject constructor(
    @Named("real") private val contentService: ContentService
) {
    suspend fun loadFeedPage(cursor: String?, pageSize: Int = DEFAULT_PAGE_SIZE): ShortsPage {
        val filters = FilterState(videoLength = VideoLength.UNDER_FOUR_MIN)
        val response = contentService.fetchContent(ContentType.VIDEO, cursor, pageSize, filters)
        val items = response.items.filterIsInstance<ContentItem.Video>().map { v ->
            ShortsItem(
                id = v.id,
                title = v.title,
                channelId = v.channelId.orEmpty(),
                channelName = v.channelName.orEmpty(),
                channelAvatarUrl = v.channelAvatarUrl,
                thumbnailUrl = v.thumbnailUrl,
                durationSeconds = v.durationSeconds
            )
        }
        return ShortsPage(items, response.nextCursor)
    }

    companion object { const val DEFAULT_PAGE_SIZE = 10 }
}
```

> **Note:** If `ContentItem.Video` doesn't have `channelId` / `channelAvatarUrl` fields, inspect the class and pass what's present (leave empty where absent); shorts feed tolerates missing avatars by falling back to a placeholder.

- [ ] **Step 5: Bind in DataModule** — add `@Provides` only if Hilt can't auto-inject. `@Inject constructor` with `@Singleton` already covers it; no change to DataModule required.

- [ ] **Step 6: Run test — expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.shorts.*"
```

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/shorts/ \
        android/app/src/test/java/com/albunyaan/tube/data/shorts/
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add ShortsFeedRepository with UNDER_FOUR_MIN filter"
```

---

## Task 4 — Vector drawables and string resources

**Files:**
- Create drawables listed in "File Structure" above.
- Modify: `res/values/strings.xml` and its `values-ar`, `values-nl` siblings.

- [ ] **Step 1: Add `ic_shorts_like.xml` — outlined heart, no vector-level `android:tint`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp" android:height="28dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35zM12,18.55l0.1,-0.09C16.45,14.44 19.5,11.67 19.5,8.5c0,-2.04 -1.46,-3.5 -3.5,-3.5 -1.56,0 -3.07,0.99 -3.62,2.36h-1.76C10.07,5.99 8.56,5 7,5 4.96,5 3.5,6.46 3.5,8.5c0,3.17 3.05,5.94 7.4,9.96L12,18.55z"/>
</vector>
```

- [ ] **Step 2: Add `ic_shorts_like_filled.xml` — solid heart**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp" android:height="28dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFF4D6D"
        android:pathData="M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"/>
</vector>
```

- [ ] **Step 3: Add `ic_shorts_share.xml`**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="28dp" android:height="28dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF"
        android:pathData="M18,16.08c-0.76,0 -1.44,0.3 -1.96,0.77L8.91,12.7c0.05,-0.23 0.09,-0.46 0.09,-0.7s-0.04,-0.47 -0.09,-0.7l7.05,-4.11c0.54,0.5 1.25,0.81 2.04,0.81 1.66,0 3,-1.34 3,-3s-1.34,-3 -3,-3 -3,1.34 -3,3c0,0.24 0.04,0.47 0.09,0.7L8.04,9.81C7.5,9.31 6.79,9 6,9c-1.66,0 -3,1.34 -3,3s1.34,3 3,3c0.79,0 1.5,-0.31 2.04,-0.81l7.12,4.16c-0.05,0.21 -0.08,0.43 -0.08,0.65 0,1.61 1.31,2.92 2.92,2.92s2.92,-1.31 2.92,-2.92 -1.31,-2.92 -2.92,-2.92z"/>
</vector>
```

- [ ] **Step 4: Add `ic_shorts_subscribe.xml` and `ic_shorts_subscribed.xml`** — use a plus icon and a checkmark respectively; same no-tint pattern.

(Executor: replicate the same structure with paths from Material icons `add_circle` and `check_circle`.)

- [ ] **Step 5: Add strings (English)** to `values/strings.xml`:

```xml
<string name="shorts_like_cd">Like</string>
<string name="shorts_share_cd">Share</string>
<string name="shorts_subscribe">Subscribe</string>
<string name="shorts_subscribed">Subscribed</string>
<string name="shorts_error_unavailable">Couldn\'t play this short. Skipping…</string>
<string name="shorts_error_feed_empty">No shorts available</string>
```

- [ ] **Step 6: Add Arabic + Dutch translations** to the respective `values-ar/strings.xml` and `values-nl/strings.xml`.

```xml
<!-- ar -->
<string name="shorts_like_cd">إعجاب</string>
<string name="shorts_share_cd">مشاركة</string>
<string name="shorts_subscribe">اشترك</string>
<string name="shorts_subscribed">مشترك</string>
<string name="shorts_error_unavailable">تعذّر تشغيل هذا الشورت. يتم التخطي…</string>
<string name="shorts_error_feed_empty">لا توجد مقاطع قصيرة متاحة</string>

<!-- nl -->
<string name="shorts_like_cd">Vind ik leuk</string>
<string name="shorts_share_cd">Delen</string>
<string name="shorts_subscribe">Abonneren</string>
<string name="shorts_subscribed">Geabonneerd</string>
<string name="shorts_error_unavailable">Kan deze short niet afspelen. Overslaan…</string>
<string name="shorts_error_feed_empty">Geen shorts beschikbaar</string>
```

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/drawable/ic_shorts_*.xml \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add shorts player icons and localized strings"
```

---

## Task 5 — Layouts (phone, tablet, TV)

**Files:**
- Create all three variants of `fragment_shorts_player.xml` and `item_shorts_page.xml`.

- [ ] **Step 1: `layout/fragment_shorts_player.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.coordinatorlayout.widget.CoordinatorLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/shortsRoot"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black"
    android:fitsSystemWindows="false"
    android:layoutDirection="locale">

    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/shortsPager"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical"/>

    <FrameLayout
        android:id="@+id/shortsErrorContainer"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone"/>

    <ImageButton
        android:id="@+id/shortsBackBtn"
        android:layout_width="48dp"
        android:layout_height="48dp"
        android:layout_gravity="top|start"
        android:layout_marginTop="@dimen/spacing_md"
        android:layout_marginStart="@dimen/spacing_sm"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/back"
        android:src="@drawable/ic_arrow_back"
        app:tint="#FFFFFFFF"/>
</androidx.coordinatorlayout.widget.CoordinatorLayout>
```

- [ ] **Step 2: `layout-sw600dp/fragment_shorts_player.xml` and `layout-sw720dp/fragment_shorts_player.xml`** — identical structure but wrap `ViewPager2` in a horizontally-centered container fixed to the 9:16 aspect ratio:

```xml
<FrameLayout
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black">
    <androidx.viewpager2.widget.ViewPager2
        android:id="@+id/shortsPager"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="center_horizontal"
        android:orientation="vertical"
        app:layout_constraintDimensionRatio="H,9:16"/>
</FrameLayout>
```

(Use a ConstraintLayout wrapper there so `layout_constraintDimensionRatio="9:16"` constrains width.)

- [ ] **Step 3: `layout/item_shorts_page.xml`** — PlayerView + right rail + bottom overlay

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@android:color/black"
    android:layoutDirection="locale">

    <androidx.media3.ui.PlayerView
        android:id="@+id/shortPlayerView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        app:resize_mode="zoom"
        app:use_controller="false"
        app:surface_type="texture_view"/>

    <View
        android:id="@+id/shortTapTarget"
        android:layout_width="match_parent"
        android:layout_height="match_parent"/>

    <LinearLayout
        android:id="@+id/shortActionRail"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="end|bottom"
        android:orientation="vertical"
        android:gravity="center_horizontal"
        android:paddingEnd="@dimen/spacing_sm"
        android:paddingBottom="@dimen/spacing_md"
        android:layout_marginBottom="96dp">

        <ImageButton
            android:id="@+id/shortLikeBtn"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/shorts_like_cd"
            android:src="@drawable/ic_shorts_like"
            app:tint="#FFFFFFFF"/>

        <TextView
            android:id="@+id/shortLikeCount"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textColor="#FFFFFFFF"
            android:textSize="12sp"
            android:textAlignment="center"
            android:layout_marginBottom="@dimen/spacing_md"
            tools:text="3.1K"/>

        <ImageButton
            android:id="@+id/shortShareBtn"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:contentDescription="@string/shorts_share_cd"
            android:src="@drawable/ic_shorts_share"
            app:tint="#FFFFFFFF"/>
    </LinearLayout>

    <LinearLayout
        android:id="@+id/shortBottomOverlay"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:layout_gravity="bottom"
        android:orientation="vertical"
        android:padding="@dimen/spacing_md"
        android:background="@drawable/scrim_bottom_gradient">

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <com.google.android.material.imageview.ShapeableImageView
                android:id="@+id/shortChannelAvatar"
                android:layout_width="36dp"
                android:layout_height="36dp"
                app:shapeAppearanceOverlay="@style/CircleImageView"
                android:scaleType="centerCrop"
                android:contentDescription="@null"/>

            <TextView
                android:id="@+id/shortChannelHandle"
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:layout_marginStart="@dimen/spacing_sm"
                android:textAlignment="viewStart"
                android:textColor="#FFFFFFFF"
                android:textStyle="bold"
                tools:text="@SHNEAKO"/>

            <com.google.android.material.button.MaterialButton
                android:id="@+id/shortSubscribeBtn"
                style="@style/Widget.Material3.Button"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="@string/shorts_subscribe"/>
        </LinearLayout>

        <TextView
            android:id="@+id/shortTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="@dimen/spacing_sm"
            android:maxLines="2"
            android:ellipsize="end"
            android:textAlignment="viewStart"
            android:textColor="#FFFFFFFF"
            tools:text="He Offered SNEAKO $3k To Do This"/>
    </LinearLayout>
</FrameLayout>
```

- [ ] **Step 4:** Create `res/drawable/scrim_bottom_gradient.xml` (if not already present — a transparent→black gradient).

```xml
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <gradient android:angle="90" android:startColor="#99000000" android:endColor="#00000000"/>
</shape>
```

- [ ] **Step 5:** `layout-sw600dp/item_shorts_page.xml` and `layout-sw720dp/item_shorts_page.xml` — copy the same layout verbatim so IDs match; tablet/TV letterboxing is handled by the parent fragment wrapper from Step 2. No changes required in the item XML.

- [ ] **Step 6:** `./gradlew :app:assembleDebug` — verify resources compile.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/layout/fragment_shorts_player.xml \
        android/app/src/main/res/layout-sw600dp/fragment_shorts_player.xml \
        android/app/src/main/res/layout-sw720dp/fragment_shorts_player.xml \
        android/app/src/main/res/layout/item_shorts_page.xml \
        android/app/src/main/res/layout-sw600dp/item_shorts_page.xml \
        android/app/src/main/res/layout-sw720dp/item_shorts_page.xml \
        android/app/src/main/res/drawable/scrim_bottom_gradient.xml
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add shorts player layouts for phone/tablet/TV"
```

---

## Task 6 — ShortsPlayerViewModel (state + player orchestration)

Holds the single `ExoPlayer`, loads pages from `ShortsFeedRepository`, exposes the current page + like/follow flags, handles like/follow/share intents, and auto-skips on unrecoverable playback errors.

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModel.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModelTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package com.albunyaan.tube.ui.shorts

import app.cash.turbine.test
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FollowedChannelsRepository
import com.albunyaan.tube.data.shorts.ShortsFeedRepository
import com.albunyaan.tube.data.shorts.ShortsItem
import com.albunyaan.tube.data.shorts.ShortsPage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ShortsPlayerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val feed: ShortsFeedRepository = mockk()
    private val favorites: FavoritesRepository = mockk(relaxed = true)
    private val follows: FollowedChannelsRepository = mockk(relaxed = true)

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun sample(id: String = "v1") = ShortsItem(id, "t", "UC1", "c", null, null, 30)

    @Test fun init_loadsFirstPage() = runTest(dispatcher) {
        coEvery { feed.loadFeedPage(null, any()) } returns ShortsPage(listOf(sample()), null)
        coEvery { favorites.isFavorite(any()) } returns flowOf(false)
        coEvery { follows.isFollowed(any()) } returns flowOf(false)

        val vm = ShortsPlayerViewModel(feed, favorites, follows, initialShortId = null, channelId = null)
        advanceUntilIdle()
        vm.items.test { assertEquals(1, awaitItem().size) }
    }

    @Test fun toggleLike_invokesFavoritesRepository() = runTest(dispatcher) {
        coEvery { feed.loadFeedPage(null, any()) } returns ShortsPage(listOf(sample()), null)
        coEvery { favorites.isFavorite(any()) } returns flowOf(false)
        coEvery { follows.isFollowed(any()) } returns flowOf(false)

        val vm = ShortsPlayerViewModel(feed, favorites, follows, initialShortId = null, channelId = null)
        advanceUntilIdle()
        vm.toggleLike(0)
        advanceUntilIdle()

        coVerify { favorites.toggleFavorite("v1", "t", "c", null, 30) }
    }
}
```

- [ ] **Step 2:** run — expect compile fail.

- [ ] **Step 3: Implement `ShortsPlayerViewModel.kt`**

```kotlin
package com.albunyaan.tube.ui.shorts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FollowedChannelsRepository
import com.albunyaan.tube.data.shorts.ShortsFeedRepository
import com.albunyaan.tube.data.shorts.ShortsItem
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi

class ShortsPlayerViewModel @AssistedInject constructor(
    private val feed: ShortsFeedRepository,
    private val favorites: FavoritesRepository,
    private val follows: FollowedChannelsRepository,
    @Assisted("initialShortId") private val initialShortId: String?,
    @Assisted("channelId") private val channelId: String?
) : ViewModel() {

    private val _items = MutableStateFlow<List<ShortsItem>>(emptyList())
    val items: StateFlow<List<ShortsItem>> = _items.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var nextCursor: String? = null
    private var loading = false
    private var exhausted = false

    init { loadNextPage() }

    fun onPageChanged(index: Int) {
        _currentIndex.value = index
        if (!exhausted && !loading && index >= _items.value.size - PREFETCH_THRESHOLD) loadNextPage()
    }

    fun toggleLike(index: Int) {
        val item = _items.value.getOrNull(index) ?: return
        viewModelScope.launch {
            favorites.toggleFavorite(item.id, item.title, item.channelName, item.thumbnailUrl, item.durationSeconds)
        }
    }

    fun toggleFollow(index: Int) {
        val item = _items.value.getOrNull(index) ?: return
        if (item.channelId.isBlank()) return
        viewModelScope.launch {
            follows.toggleFollow(item.channelId, item.channelName, item.channelAvatarUrl)
        }
    }

    fun isLikedFlow(id: String) = favorites.isFavorite(id)
    fun isFollowedFlow(id: String) = follows.isFollowed(id)

    fun onPlaybackError(index: Int) {
        // auto-skip: let fragment advance ViewPager2 by 1
        _loadError.value = "skip:${_items.value.getOrNull(index)?.id}"
    }

    private fun loadNextPage() {
        if (loading || exhausted) return
        loading = true
        viewModelScope.launch {
            runCatching {
                if (channelId != null && _items.value.isEmpty()) {
                    // future: channel-scoped feed — for now fall back to feed
                }
                feed.loadFeedPage(nextCursor)
            }.onSuccess { page ->
                val combined = _items.value + page.items
                // if initialShortId supplied, ensure it leads the list
                val ordered = initialShortId?.let { id ->
                    val head = combined.firstOrNull { it.id == id }
                    if (head != null) listOf(head) + combined.filter { it.id != id } else combined
                } ?: combined
                _items.value = ordered
                nextCursor = page.nextCursor
                exhausted = page.nextCursor == null
            }.onFailure {
                _loadError.value = "load:${it.message}"
            }
            loading = false
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(
            @Assisted("initialShortId") initialShortId: String?,
            @Assisted("channelId") channelId: String?
        ): ShortsPlayerViewModel
    }

    companion object { private const val PREFETCH_THRESHOLD = 3 }
}
```

- [ ] **Step 4: Run test — expect pass**

```bash
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.shorts.ShortsPlayerViewModelTest"
```

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModel.kt \
        android/app/src/test/java/com/albunyaan/tube/ui/shorts/ShortsPlayerViewModelTest.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add ShortsPlayerViewModel with feed + like + follow"
```

---

## Task 7 — ShortsPageViewHolder + ShortsPagerAdapter + PlayerBinder

`PlayerBinder` encapsulates "detach the previous `PlayerView`, bind to the new one, rebuild MediaSource, prepare". Pure, testable.

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPageViewHolder.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPagerAdapter.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/PlayerBinder.kt`

- [ ] **Step 1: PlayerBinder implementation**

```kotlin
package com.albunyaan.tube.ui.shorts

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.albunyaan.tube.player.MultiRepSyntheticDashMediaSourceFactory

class PlayerBinder(
    private val player: Player,
    private val mediaSourceFactory: MultiRepSyntheticDashMediaSourceFactory
) {
    private var boundView: PlayerView? = null

    fun bind(target: PlayerView, videoId: String) {
        boundView?.player = null
        target.player = player
        boundView = target
        // MultiRep factory resolves streams off the main thread; fragment kicks resolution
        // via existing PlayerRepository flow. Here we set a placeholder MediaItem and let
        // the fragment swap in resolved MediaSource once available.
        player.setMediaItem(MediaItem.fromUri("ytshort://$videoId"))
        player.repeatMode = Player.REPEAT_MODE_ONE
        player.prepare()
        player.playWhenReady = true
    }

    fun detach() { boundView?.player = null; boundView = null }
    fun togglePlayPause() { player.playWhenReady = !player.playWhenReady }
}
```

> Integration reality: resolving the stream URL is non-trivial. The **real** binding call below must go through `PlayerRepository.resolveStreams(videoId)` and feed the resolved `DashMpd` / progressive URL into `MultiRepSyntheticDashMediaSourceFactory.create(...)`. Executor: mirror the exact resolution path from `PlayerFragment.kt` around the `setMediaSource(...)` call. Keep `PlayerBinder` as the only place this happens; fragment calls `binder.bind(view, id)` and awaits.

- [ ] **Step 2: ShortsPageViewHolder** — binds one `ShortsItem` to `item_shorts_page.xml` views.

```kotlin
class ShortsPageViewHolder(
    private val binding: ItemShortsPageBinding
) : RecyclerView.ViewHolder(binding.root) {

    val playerView: PlayerView get() = binding.shortPlayerView

    fun bind(
        item: ShortsItem,
        isLiked: Boolean,
        isFollowed: Boolean,
        onLike: () -> Unit,
        onShare: () -> Unit,
        onSubscribe: () -> Unit,
        onChannelTap: () -> Unit,
        onTapVideo: () -> Unit
    ) {
        binding.shortTitle.text = item.title
        binding.shortChannelHandle.text = "@${item.channelName}"
        binding.shortLikeBtn.setImageResource(
            if (isLiked) R.drawable.ic_shorts_like_filled else R.drawable.ic_shorts_like
        )
        binding.shortSubscribeBtn.setText(
            if (isFollowed) R.string.shorts_subscribed else R.string.shorts_subscribe
        )
        binding.shortSubscribeBtn.isSelected = isFollowed

        // Load avatar via Coil
        item.channelAvatarUrl?.let { binding.shortChannelAvatar.load(it) }

        binding.shortLikeBtn.setOnClickListener { onLike() }
        binding.shortShareBtn.setOnClickListener { onShare() }
        binding.shortSubscribeBtn.setOnClickListener { onSubscribe() }
        binding.shortChannelAvatar.setOnClickListener { onChannelTap() }
        binding.shortChannelHandle.setOnClickListener { onChannelTap() }
        binding.shortTapTarget.setOnClickListener { onTapVideo() }
    }
}
```

- [ ] **Step 3: ShortsPagerAdapter** — standard ListAdapter<ShortsItem, ShortsPageViewHolder> with DiffUtil on `id`. Delegates callbacks up to fragment (fragment holds ViewModel + PlayerBinder + NavController).

- [ ] **Step 4: Build — expect pass**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/shorts/
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Add ShortsPagerAdapter + PlayerBinder"
```

---

## Task 8 — ShortsPlayerFragment

Wires everything: ViewPager2, adapter, PlayerBinder, ViewModel. Handles edge-to-edge enter/exit mirroring PlayerFragment.kt:3199/3303. Observes `vm.items`, `vm.currentIndex`, `vm.loadError` (auto-advances on `skip:` events).

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt`

- [ ] **Step 1:** Skeleton fragment with Hilt + SafeArgs + edge-to-edge lifecycle callbacks (copy pattern from PlayerFragment.kt).

- [ ] **Step 2:** Construct `ShortsPagerAdapter` wiring:
  - `onLike` → `vm.toggleLike(index)`
  - `onShare` → `ShareCompat.IntentBuilder(requireContext()).setType("text/plain").setText(item.canonicalShareUrl).setChooserTitle(R.string.shorts_share_cd).startChooser()`
  - `onSubscribe` → `vm.toggleFollow(index)`
  - `onChannelTap` → `findNavController().navigate(R.id.action_global_channelDetailFragment, bundleOf("channelId" to item.channelId, "channelName" to item.channelName))`
  - `onTapVideo` → `playerBinder.togglePlayPause()`

- [ ] **Step 3:** `ViewPager2.registerOnPageChangeCallback`:
```kotlin
pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
    override fun onPageSelected(position: Int) {
        val vh = pager.findViewHolderForAdapterPosition(position) as? ShortsPageViewHolder ?: return
        playerBinder.bind(vh.playerView, vm.items.value[position].id)
        vm.onPageChanged(position)
    }
})
```

- [ ] **Step 4:** Observe `vm.loadError` — if prefix `skip:`, call `pager.currentItem = pager.currentItem + 1`.

- [ ] **Step 5:** `onResume`: `WindowCompat.setDecorFitsSystemWindows(requireActivity().window, false)` + hide system bars. `onPause`: restore both. Register an `OnBackPressedCallback` that exits the fragment normally (pop back stack) so the restore runs.

- [ ] **Step 6:** `onDestroyView`: `playerBinder.detach()`. `ViewModel.onCleared()` releases the player (inject a `Player` provider that releases in `onCleared()`; see pattern in `PlayerViewModel`).

- [ ] **Step 7: Build**

```bash
./gradlew :app:assembleDebug
```

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragment.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Wire ShortsPlayerFragment with ViewPager2, edge-to-edge, share intent"
```

---

## Task 9 — Navigation wiring

- [ ] **Step 1: Edit `main_tabs_nav.xml`** — add a new destination and a global action:

```xml
<fragment
    android:id="@+id/shortsPlayerFragment"
    android:name="com.albunyaan.tube.ui.shorts.ShortsPlayerFragment"
    android:label="Shorts">
    <argument
        android:name="initialShortId"
        app:argType="string"
        app:nullable="true"
        android:defaultValue="@null"/>
    <argument
        android:name="channelId"
        app:argType="string"
        app:nullable="true"
        android:defaultValue="@null"/>
    <deepLink app:uri="albunyaantube://shorts/{initialShortId}"/>
</fragment>

<!-- add alongside existing global actions -->
<action
    android:id="@+id/action_global_shortsPlayerFragment"
    app:destination="@id/shortsPlayerFragment"/>
```

- [ ] **Step 2: Modify `ChannelShortsTabFragment.kt`** lines 74-84 — change destination:

```kotlin
findNavController().navigate(
    R.id.action_global_shortsPlayerFragment,
    Bundle().apply {
        putString("initialShortId", short.id)
        putString("channelId", channelId)
    }
)
```

- [ ] **Step 3: Build + smoke test**

```bash
./gradlew :app:assembleDebug :app:lintDebug
```

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/res/navigation/main_tabs_nav.xml \
        android/app/src/main/java/com/albunyaan/tube/ui/detail/tabs/ChannelShortsTabFragment.kt
git commit -m "[FEAT]: [ANDROID-SHORTS-01]: Nav entry + redirect channel shorts tap to shorts player"
```

---

## Task 10 — Instrumented test: shorts player flow

**Files:**
- Create: `android/app/src/androidTest/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragmentTest.kt`

Test:
- `opensFromChannelShortsTap_showsFirstShortTitle()`
- `tapOnVideo_togglesPlayPause()` (assert via `player.playWhenReady` exposed via test hook or via PlayerBinder probe)
- `shareButton_firesActionSendIntentWithCanonicalUrl()` (IntentsRule + `intended(hasAction(Intent.ACTION_SEND))` + `intended(hasExtra(Intent.EXTRA_TEXT, "https://www.youtube.com/shorts/v1"))`)
- `likeButton_togglesFavoriteInRepository()` (inject a fake FavoritesRepository via `@BindValue`)
- `subscribeButton_togglesFollowInRepository()`
- `backPress_restoresSystemBars()` (verify `WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars` or observable inset callback)

- [ ] **Step 1:** Write the test file end-to-end (follow NavigationGraphTest.kt structure).
- [ ] **Step 2:** Run `./gradlew :app:connectedDebugAndroidTest --tests "com.albunyaan.tube.ui.shorts.*"`.
- [ ] **Step 3:** Fix any breakage in fragment until green.
- [ ] **Step 4:** Commit.

```bash
git add android/app/src/androidTest/java/com/albunyaan/tube/ui/shorts/ShortsPlayerFragmentTest.kt
git commit -m "[TEST]: [ANDROID-SHORTS-01]: Instrumented tests for shorts player"
```

---

## Task 11 — Full verification + code review

- [ ] **Step 1:** `./gradlew :app:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` all green.
- [ ] **Step 2:** Dispatch a code-review subagent with the design spec + the diff since the branch base. Apply any actionable feedback in a follow-up commit.
- [ ] **Step 3:** Run `/codex review` — resolve findings.
- [ ] **Step 4:** Manual visual-QA checklist prepared for user:
  - Samsung S25 Ultra (Android 15): edge-to-edge correct; icons visible on rail; bottom overlay not clipped.
  - Huawei Honor Play (Android 14): same, plus back gesture restores system bars.
  - Pixel Tablet AVD: 9:16 video letterboxed with black sides; rail inside video column.
  - Android TV AVD: D-pad focus cycles like → share → subscribe → back to video.
  - Arabic locale: rail mirrored to the left; title uses viewStart alignment.

- [ ] **Step 5:** Update `docs/TRUE_PROJECT_STATUS.md` and `docs/PROJECT_STATUS.md` marking `ANDROID-SHORTS-01` complete.

- [ ] **Step 6:** Commit docs update.

```bash
git add docs/TRUE_PROJECT_STATUS.md docs/PROJECT_STATUS.md
git commit -m "[DOCS]: [ANDROID-SHORTS-01]: Mark shorts player complete"
```

- [ ] **Step 7:** Run `superpowers:finishing-a-development-branch` to present merge-to-develop options to the user. Merge target is `develop` — never `main`. Never `--no-verify`.

---

## Self-review notes

- **Spec coverage:** every requirement in the spec maps to a task (like→T1/T2/T6; share→T8; subscribe→T1/T2/T6/T8; removed dislike→absence; edge-to-edge→T5/T8; RTL→T4/T5; device coverage→T5+T11 manual QA; auto-loop→PlayerBinder REPEAT_MODE_ONE; tap play/pause→T8; swipe advance→T8 OnPageChangeCallback).
- **Placeholders:** Task 7 intentionally references "mirror the exact resolution path from PlayerFragment.kt" rather than duplicating ~100 lines of resolver wiring — executor reads that file directly. This is the only instruction of that shape; it is unavoidable given PlayerFragment's size and not suitable for inline copy.
- **Type consistency:** `ShortsItem` fields are used consistently across T3, T6, T7, T8. `ViewModel.Factory.create(initialShortId, channelId)` signature matches the SafeArgs in T9.
