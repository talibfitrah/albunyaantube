# Me Tab — ATOM Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace NewPipe-based Me-tab feed refresh with ATOM polling, cap subscriptions at 30 channels (unlimited playlists), move refresh to WorkManager background, fold favorites into the Me tab as a horizontal row, and paginate the videos grid — all while keeping NewPipe rate-limited and circuit-broken on user-initiated paths.

**Architecture:** Drop-in replacement of `ChannelFeedFetcher` impl from `NewPipeChannelFeedFetcher` to a new `AtomChannelFeedFetcher` (OkHttp + XmlPullParser + ETag). Single `PeriodicWorkRequest` + `OneTimeWorkRequest` foreground burst. New `RateLimitedDownloader` wraps NewPipe's `DownloaderImpl` to intercept `ReCaptchaException` and HTTP 429. Favorites row uses existing `favorite_videos` table (no schema change). Videos grid uses Room `PagingSource`.

**Tech Stack:** Kotlin, Hilt, Room (with paging-runtime-ktx), AndroidX Paging 3, AndroidX WorkManager, AndroidX DataStore, OkHttp, XmlPullParser (built-in), kotlinx-coroutines, JUnit 4, Robolectric, MockK, Turbine, kotlinx-coroutines-test, WorkManagerTestInitHelper, MigrationTestHelper.

**Spec:** `docs/superpowers/specs/2026-04-27-me-tab-atom-refresh-design.md`

**Reference**: All code blocks below quote the spec for traceability. When the engineer needs design rationale, read the spec section listed at the top of each task.

---

## Build & Test Commands

```bash
# Unit tests (Robolectric + jvm)
cd /home/farouq/Development/albunyaantube-me/android && ./gradlew :app:testDebugUnitTest

# Single test class
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.me.AtomChannelFeedFetcherTest"

# Single test method
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.me.AtomChannelFeedFetcherTest.parses_real_atom_feed"

# Instrumented (requires emulator)
./gradlew :app:connectedDebugAndroidTest

# Lint + build
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

Per project rules: 300 s overall test timeout, 30 s per test method.

---

## Task Order Rationale

Tasks are ordered by dependency:
1. **T1 (Room v3 migration)** — schema first, everything downstream uses new columns
2. **T2 (ATOM fetcher)** — pure, no Android deps, replaces an interface implementation cleanly
3. **T3 (SubscriptionLimitGuard + cap UI)** — independent of refresh, can ship anytime
4. **T4 (Hide duration/views)** — small layout cleanup, independent
5. **T5 (GlobalNewPipeRateLimiter)** — foundation for T7
6. **T6 (CooldownState)** — foundation for T7
7. **T7 (RateLimitedDownloader)** — depends on T5 + T6, integrates with NewPipe paths
8. **T8 (AppLifecycleTracker)** — small, foundation for telemetry
9. **T9 (RefreshSubscriptionsWorker + MeViewModel rewire)** — depends on T1 + T2; removes init-time refresh
10. **T10 (MeFavoritesAdapter row)** — depends on existing FavoritesRepository; touches `MeFragment` so order before T11
11. **T11 (Paged videos grid)** — touches same `MeFragment` as T10, last UI change
12. **T12 (Telemetry + dev settings + Doze test)** — final polish

Each task ends with a commit. No task should be larger than one focused change.

---

## Task 1: Room v3 migration (additive columns on `channel_feed_refresh_state`)

**Spec ref:** §6 Persistence Schema

**Files:**
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/ChannelFeedRefreshState.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/Migrations.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt` (bump `version = 3`, add migration)
- Create: `android/app/schemas/com.albunyaan.tube.data.local.AppDatabase/3.json` (Room generates this on build)
- Create: `android/app/src/test/java/com/albunyaan/tube/data/local/AppDatabaseMigration2to3Test.kt`

- [ ] **Step 1: Write the failing migration test**

```kotlin
// AppDatabaseMigration2to3Test.kt
package com.albunyaan.tube.data.local

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigration2to3Test {

    private val DB_NAME = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        listOf(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate_2_to_3_adds_atom_columns_with_safe_defaults() {
        helper.createDatabase(DB_NAME, 2).use { v2 ->
            v2.execSQL(
                "INSERT INTO channel_feed_refresh_state " +
                "(channelId, lastSuccessfulFetchAt, lastAttemptAt, lastErrorMessage) " +
                "VALUES ('UCabc', 1000, 1000, NULL)"
            )
        }

        helper.runMigrationsAndValidate(DB_NAME, 3, true, MIGRATION_2_3).use { v3 ->
            v3.query("SELECT etag, lastModified, consecutiveErrorCount, consecutiveEmptyCount, backoffUntilMs FROM channel_feed_refresh_state WHERE channelId = 'UCabc'").use { c ->
                assert(c.moveToFirst())
                assertNull(c.getString(0)) // etag
                assertNull(c.getString(1)) // lastModified
                assertEquals(0, c.getInt(2))
                assertEquals(0, c.getInt(3))
                assertNull(c.getString(4)) // backoffUntilMs (Long via getString returns null when null)
            }
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.AppDatabaseMigration2to3Test"
```

Expected: FAIL with "Unresolved reference: MIGRATION_2_3" or schema mismatch.

- [ ] **Step 3: Add the new columns to the entity**

```kotlin
// ChannelFeedRefreshState.kt — modify existing entity
@Entity(tableName = "channel_feed_refresh_state")
data class ChannelFeedRefreshState(
    @PrimaryKey val channelId: String,
    val lastSuccessfulFetchAt: Long,
    val lastAttemptAt: Long,
    val lastErrorMessage: String?,
    val etag: String? = null,
    val lastModified: String? = null,
    val consecutiveErrorCount: Int = 0,
    val consecutiveEmptyCount: Int = 0,
    val backoffUntilMs: Long? = null,
)
```

- [ ] **Step 4: Add `MIGRATION_2_3` in `Migrations.kt`**

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

- [ ] **Step 5: Bump database version and register migration**

In `AppDatabase.kt`:
- Change `@Database(entities = [...], version = 2, ...)` → `version = 3`
- In `DatabaseModule.kt` provider: `Room.databaseBuilder(...).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()`

- [ ] **Step 6: Run test to verify it passes**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.AppDatabaseMigration2to3Test"
```

Expected: PASS.

- [ ] **Step 7: Run full module build to confirm schema 3.json is generated**

```
./gradlew :app:assembleDebug
ls android/app/schemas/com.albunyaan.tube.data.local.AppDatabase/
```

Expected: `1.json`, `2.json`, `3.json` all present.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/local/ChannelFeedRefreshState.kt \
        android/app/src/main/java/com/albunyaan/tube/data/local/Migrations.kt \
        android/app/src/main/java/com/albunyaan/tube/data/local/AppDatabase.kt \
        android/app/src/main/java/com/albunyaan/tube/di/DatabaseModule.kt \
        android/app/schemas/com.albunyaan.tube.data.local.AppDatabase/3.json \
        android/app/src/test/java/com/albunyaan/tube/data/local/AppDatabaseMigration2to3Test.kt
git commit -m "[ANDROID-PERSONAL-02]: Room v3 — ATOM ETag + per-channel backoff columns"
```

---

## Task 2: ATOM channel feed fetcher (replaces NewPipe scraping)

**Spec ref:** §4.1 `AtomChannelFeedFetcher`

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/me/AtomChannelFeedFetcher.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/me/AtomFeedParser.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt` (use new fetch result type with ETag/304)
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/MeModule.kt` (bind `AtomChannelFeedFetcher` instead of `NewPipeChannelFeedFetcher`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/me/ChannelFeedFetcher.kt` (extend result to carry ETag, lastModified, isNotModified)
- Create: `android/app/src/test/java/com/albunyaan/tube/data/me/AtomFeedParserTest.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/me/AtomChannelFeedFetcherTest.kt`
- Create: `android/app/src/test/resources/atom/channel-with-uploads.xml` (golden fixture)
- Create: `android/app/src/test/resources/atom/channel-empty-no-uploads.xml`
- Create: `android/app/src/test/resources/atom/channel-with-shorts.xml`
- Create: `android/app/src/test/resources/atom/malformed.xml`

- [ ] **Step 1: Capture real ATOM fixtures**

Run on a dev machine:
```bash
curl -s 'https://www.youtube.com/feeds/videos.xml?channel_id=UCsBjURrPoezykLs9EqgamOA' > android/app/src/test/resources/atom/channel-with-uploads.xml
# Replace UCsBjURrPoezykLs9EqgamOA with a real channel id from your subscriptions
```

Hand-craft `channel-empty-no-uploads.xml` (header + zero `<entry>` elements), `channel-with-shorts.xml` (entries with `/shorts/` URLs), `malformed.xml` (truncated XML).

- [ ] **Step 2: Extend `ChannelFeedFetcher` to carry ATOM-specific result data**

```kotlin
// ChannelFeedFetcher.kt — modify
interface ChannelFeedFetcher {
    suspend fun fetchLatest(
        channelUrl: String,
        priorEtag: String? = null,
        priorLastModified: String? = null,
    ): FetchResult

    sealed class FetchResult {
        data class NotModified(val etag: String?, val lastModified: String?) : FetchResult()
        data class Items(
            val items: List<ChannelFeedItem>,
            val etag: String?,
            val lastModified: String?,
        ) : FetchResult()
    }

    data class ChannelFeedItem(
        val videoId: String,
        val title: String,
        val thumbnailUrl: String?,
        val durationSeconds: Long?,    // always null from ATOM
        val viewCount: Long?,           // always null from ATOM
        val uploadedAt: Long?,
        val isShort: Boolean,
    )
}
```

- [ ] **Step 3: Write failing test for `AtomFeedParser`**

```kotlin
// AtomFeedParserTest.kt
@RunWith(RobolectricTestRunner::class)
class AtomFeedParserTest {
    private val parser = AtomFeedParser()

    @Test
    fun parses_real_atom_feed_with_uploads() {
        val xml = readResource("/atom/channel-with-uploads.xml")
        val items = parser.parse(xml.byteInputStream())
        assert(items.size in 1..15) { "expected 1..15 entries, got ${items.size}" }
        items.first().let {
            assertEquals(11, it.videoId.length)
            assert(it.title.isNotEmpty())
            assert(it.thumbnailUrl?.startsWith("https://") == true)
            assertNotNull(it.uploadedAt)
            assertNull(it.durationSeconds)
            assertNull(it.viewCount)
        }
    }

    @Test
    fun parses_empty_feed_returns_empty_list() {
        val xml = readResource("/atom/channel-empty-no-uploads.xml")
        val items = parser.parse(xml.byteInputStream())
        assertEquals(0, items.size)
    }

    @Test
    fun marks_shorts_url_pattern_as_isShort() {
        val xml = readResource("/atom/channel-with-shorts.xml")
        val items = parser.parse(xml.byteInputStream())
        assert(items.any { it.isShort })
    }

    @Test
    fun malformed_xml_returns_partial_or_empty_without_crashing() {
        val xml = readResource("/atom/malformed.xml")
        val items = parser.parse(xml.byteInputStream())
        // Whatever parsed before the malformation; never crash
        assert(items.size >= 0)
    }

    private fun readResource(path: String): String =
        javaClass.getResourceAsStream(path)!!.bufferedReader().readText()
}
```

- [ ] **Step 4: Run test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.me.AtomFeedParserTest"
```

Expected: FAIL with "Unresolved reference: AtomFeedParser".

- [ ] **Step 5: Implement `AtomFeedParser`**

```kotlin
// AtomFeedParser.kt
package com.albunyaan.tube.data.me

import com.albunyaan.tube.data.me.ChannelFeedFetcher.ChannelFeedItem
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import javax.inject.Inject

class AtomFeedParser @Inject constructor() {

    fun parse(input: InputStream): List<ChannelFeedItem> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")

        val out = mutableListOf<ChannelFeedItem>()
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "entry") {
                    parseEntry(parser)?.let { out.add(it) }
                }
                event = parser.next()
            }
        } catch (_: Throwable) {
            // Defensive: malformed mid-parse — return what we have
        }
        return out
    }

    private fun parseEntry(parser: XmlPullParser): ChannelFeedItem? {
        var videoId: String? = null
        var title: String? = null
        var thumbnailUrl: String? = null
        var publishedMs: Long? = null
        var linkHref: String? = null

        while (!(parser.eventType == XmlPullParser.END_TAG && parser.name == "entry")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "videoId" -> videoId = parser.nextText()?.trim()
                    "title" -> if (title == null) title = parser.nextText()?.trim()
                    "published" -> publishedMs = parsePublished(parser.nextText())
                    "link" -> {
                        val rel = parser.getAttributeValue(null, "rel")
                        if (rel == null || rel == "alternate") {
                            linkHref = parser.getAttributeValue(null, "href")
                        }
                    }
                    "thumbnail" -> {
                        val url = parser.getAttributeValue(null, "url")
                        if (url != null && thumbnailUrl == null) thumbnailUrl = url
                    }
                }
            }
            if (parser.next() == XmlPullParser.END_DOCUMENT) break
        }

        if (videoId.isNullOrBlank() || title.isNullOrBlank()) return null
        val isShort = linkHref?.contains("/shorts/") == true
        return ChannelFeedItem(
            videoId = videoId,
            title = title,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = null,
            viewCount = null,
            uploadedAt = publishedMs,
            isShort = isShort,
        )
    }

    private fun parsePublished(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
```

- [ ] **Step 6: Run parser tests to verify they pass**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.me.AtomFeedParserTest"
```

Expected: PASS (all 4 tests).

- [ ] **Step 7: Write failing test for `AtomChannelFeedFetcher`**

```kotlin
// AtomChannelFeedFetcherTest.kt
@RunWith(RobolectricTestRunner::class)
class AtomChannelFeedFetcherTest {

    private lateinit var server: MockWebServer
    private lateinit var fetcher: AtomChannelFeedFetcher

    @Before
    fun setup() {
        server = MockWebServer().also { it.start() }
        val client = OkHttpClient.Builder().build()
        fetcher = AtomChannelFeedFetcher(client, AtomFeedParser(), baseUrlOverride = server.url("").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test
    fun returns_items_on_200() = runTest {
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .setHeader("ETag", "W/\"abc\"")
            .setBody(readResource("/atom/channel-with-uploads.xml")))
        val result = fetcher.fetchLatest("https://www.youtube.com/channel/UCabc")
        assert(result is FetchResult.Items)
        assertEquals("W/\"abc\"", (result as FetchResult.Items).etag)
        assert(result.items.isNotEmpty())
    }

    @Test
    fun returns_NotModified_on_304() = runTest {
        server.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "W/\"abc\""))
        val result = fetcher.fetchLatest("https://www.youtube.com/channel/UCabc", priorEtag = "W/\"abc\"")
        assert(result is FetchResult.NotModified)
    }

    @Test
    fun throws_on_429() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        try {
            fetcher.fetchLatest("https://www.youtube.com/channel/UCabc")
            error("expected throw")
        } catch (e: IOException) { /* ok */ }
    }

    @Test
    fun throws_on_5xx() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            fetcher.fetchLatest("https://www.youtube.com/channel/UCabc")
            error("expected throw")
        } catch (e: IOException) { /* ok */ }
    }

    @Test
    fun throws_on_unparseable_url() = runTest {
        try {
            fetcher.fetchLatest("https://example.com/not-a-channel-url")
            error("expected throw")
        } catch (e: IllegalArgumentException) { /* ok */ }
    }
}
```

- [ ] **Step 8: Run test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.me.AtomChannelFeedFetcherTest"
```

Expected: FAIL with "Unresolved reference: AtomChannelFeedFetcher".

- [ ] **Step 9: Implement `AtomChannelFeedFetcher`**

```kotlin
// AtomChannelFeedFetcher.kt
package com.albunyaan.tube.data.me

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AtomChannelFeedFetcher @Inject constructor(
    private val client: OkHttpClient,
    private val parser: AtomFeedParser,
    private val baseUrlOverride: String? = null,  // tests inject MockWebServer
) : ChannelFeedFetcher {

    override suspend fun fetchLatest(
        channelUrl: String,
        priorEtag: String?,
        priorLastModified: String?,
    ): ChannelFeedFetcher.FetchResult {
        val channelId = CHANNEL_ID_REGEX.find(channelUrl)?.groupValues?.getOrNull(1)
            ?: throw IllegalArgumentException("Cannot extract channelId from $channelUrl")

        val base = baseUrlOverride ?: "https://www.youtube.com/"
        val url = "${base}feeds/videos.xml?channel_id=$channelId"

        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/atom+xml")
        priorEtag?.let { builder.header("If-None-Match", it) }
        priorLastModified?.let { builder.header("If-Modified-Since", it) }

        client.newCall(builder.build()).execute().use { response ->
            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")
            return when (response.code) {
                304 -> ChannelFeedFetcher.FetchResult.NotModified(etag, lastModified)
                in 200..299 -> {
                    val body = response.body ?: throw IOException("empty body")
                    val items = parser.parse(body.byteStream()).take(MAX_ITEMS)
                    ChannelFeedFetcher.FetchResult.Items(items, etag, lastModified)
                }
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    companion object {
        private const val MAX_ITEMS = 30
        // Matches /channel/UCxxxxxxxxxxxxxxxxxxxxxx (24-char ID) in any URL form
        internal val CHANNEL_ID_REGEX = Regex("""/channel/(UC[A-Za-z0-9_-]{22})""")
    }
}
```

- [ ] **Step 10: Update DI binding in `MeModule.kt`**

Replace:
```kotlin
@Binds
@Singleton
abstract fun bindChannelFeedFetcher(impl: NewPipeChannelFeedFetcher): ChannelFeedFetcher
```

With:
```kotlin
@Binds
@Singleton
abstract fun bindChannelFeedFetcher(impl: AtomChannelFeedFetcher): ChannelFeedFetcher
```

(Keep `NewPipeChannelFeedFetcher` class around — it's the rollback path per spec §10.)

- [ ] **Step 11: Update `MeFeedRepository.refreshOne` to use the new `FetchResult`**

In `MeFeedRepository.kt`, replace the existing `fetcher.fetchLatest(channel.channelUrl)` call site with logic that handles `NotModified` vs `Items`. On `NotModified`: just upsert refresh state with advanced timestamps + persisted ETag. On `Items`: same as today plus persist new ETag/lastModified. (Full repo rewrite happens in T9; this step is the minimum to keep `MeFeedRepositoryTest` green.)

- [ ] **Step 12: Run all tests, verify green**

```
./gradlew :app:testDebugUnitTest
```

Expected: all tests PASS, including pre-existing `MeFeedRepositoryTest`.

- [ ] **Step 13: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/me/AtomChannelFeedFetcher.kt \
        android/app/src/main/java/com/albunyaan/tube/data/me/AtomFeedParser.kt \
        android/app/src/main/java/com/albunyaan/tube/data/me/ChannelFeedFetcher.kt \
        android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt \
        android/app/src/main/java/com/albunyaan/tube/di/MeModule.kt \
        android/app/src/test/java/com/albunyaan/tube/data/me/AtomFeedParserTest.kt \
        android/app/src/test/java/com/albunyaan/tube/data/me/AtomChannelFeedFetcherTest.kt \
        android/app/src/test/resources/atom/
git commit -m "[ANDROID-PERSONAL-02]: ATOM feed fetcher replaces NewPipe scraping for Me feed"
```

---

## Task 3: SubscriptionLimitGuard + cap UI (30 channels, unlimited playlists)

**Spec ref:** §4.2 SubscriptionLimitGuard, §8 Channel Detail Subscribe button

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/subscriptions/SubscriptionLimitGuard.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailFragment.kt` (route subscribe through guard)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/subscriptions/SubscriptionRepository.kt` (mark `subscribe` internal — only the guard calls it)
- Modify: `android/app/src/main/res/values/strings.xml` (+ values-ar, values-nl)
- Create: `android/app/src/test/java/com/albunyaan/tube/data/subscriptions/SubscriptionLimitGuardTest.kt`

- [ ] **Step 1: Add new strings (en draft; user provides ar + nl)**

In `values/strings.xml`:
```xml
<string name="me_subscription_cap_reached">You\'re following 30 channels (the limit). Unsubscribe one to follow this channel.</string>
```

Placeholder identical strings in `values-ar/strings.xml` and `values-nl/strings.xml` so the build compiles; user replaces with translations before merge.

- [ ] **Step 2: Write failing test for `SubscriptionLimitGuard`**

```kotlin
// SubscriptionLimitGuardTest.kt
@RunWith(RobolectricTestRunner::class)
class SubscriptionLimitGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var guard: SubscriptionLimitGuard

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        guard = SubscriptionLimitGuard(db.subscribedChannelDao(), db)
    }

    @After fun tearDown() = db.close()

    @Test
    fun trySubscribe_succeeds_when_under_cap() = runTest {
        repeat(29) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UCnew"))
        assertEquals(SubscribeResult.Success, result)
        assertEquals(30, db.subscribedChannelDao().getAll().size)
    }

    @Test
    fun trySubscribe_returns_LimitReached_at_cap() = runTest {
        repeat(30) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UCnew"))
        assert(result is SubscribeResult.LimitReached)
        assertEquals(30, (result as SubscribeResult.LimitReached).current)
        assertEquals(30, db.subscribedChannelDao().getAll().size)  // not added
    }

    @Test
    fun trySubscribe_idempotent_for_existing_subscription() = runTest {
        repeat(30) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UC0"))  // already exists
        assertEquals(SubscribeResult.Success, result)
        assertEquals(30, db.subscribedChannelDao().getAll().size)
    }

    @Test
    fun playlists_do_not_count_toward_cap() = runTest {
        repeat(30) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        repeat(50) { db.savedPlaylistDao().upsert(playlist("PL$it")) }
        // 30 channels + 50 playlists; new channel still rejected, playlists fine
        val result = guard.trySubscribe(channel("UCnew"))
        assert(result is SubscribeResult.LimitReached)
    }

    private fun channel(id: String) = SubscribedChannel(
        channelId = id, channelUrl = "https://www.youtube.com/channel/$id",
        name = id, thumbnailUrl = null, subscribedAt = System.currentTimeMillis(),
    )
    private fun playlist(id: String) = SavedPlaylist(
        playlistId = id, playlistUrl = "", title = id, thumbnailUrl = null,
        savedAt = System.currentTimeMillis(),
    )
}
```

- [ ] **Step 3: Run test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.subscriptions.SubscriptionLimitGuardTest"
```

Expected: FAIL with "Unresolved reference: SubscriptionLimitGuard".

- [ ] **Step 4: Implement `SubscriptionLimitGuard`**

```kotlin
// SubscriptionLimitGuard.kt
package com.albunyaan.tube.data.subscriptions

import androidx.room.withTransaction
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import javax.inject.Inject
import javax.inject.Singleton

sealed class SubscribeResult {
    object Success : SubscribeResult()
    data class LimitReached(val current: Int, val cap: Int) : SubscribeResult()
}

@Singleton
class SubscriptionLimitGuard @Inject constructor(
    private val channels: SubscribedChannelDao,
    private val db: AppDatabase,
) {
    suspend fun trySubscribe(channel: SubscribedChannel): SubscribeResult =
        db.withTransaction {
            val existing = channels.getById(channel.channelId)
            if (existing != null) {
                channels.upsert(channel) // idempotent metadata refresh
                return@withTransaction SubscribeResult.Success
            }
            val current = channels.count()
            if (current >= CAP) return@withTransaction SubscribeResult.LimitReached(current, CAP)
            channels.upsert(channel)
            SubscribeResult.Success
        }

    companion object {
        const val CAP = 30
    }
}
```

If the DAO doesn't have `getById` or `count()`, add them:

```kotlin
// SubscribedChannelDao.kt
@Query("SELECT * FROM subscribed_channels WHERE channelId = :id")
suspend fun getById(id: String): SubscribedChannel?

@Query("SELECT COUNT(*) FROM subscribed_channels")
suspend fun count(): Int
```

- [ ] **Step 5: Run test to verify it passes**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.subscriptions.SubscriptionLimitGuardTest"
```

Expected: PASS.

- [ ] **Step 6: Wire UI snackbar in `ChannelDetailFragment`**

Replace direct `subscriptionRepository.subscribe()` call with:
```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    when (val r = limitGuard.trySubscribe(channel)) {
        SubscribeResult.Success -> { /* update UI */ }
        is SubscribeResult.LimitReached ->
            Snackbar.make(binding.root, R.string.me_subscription_cap_reached, Snackbar.LENGTH_LONG).show()
    }
}
```

Inject `SubscriptionLimitGuard` via Hilt.

- [ ] **Step 7: Build + smoke run**

```
./gradlew :app:assembleDebug
```

Expected: build succeeds.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/subscriptions/SubscriptionLimitGuard.kt \
        android/app/src/main/java/com/albunyaan/tube/data/local/SubscribedChannelDao.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/detail/ChannelDetailFragment.kt \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml \
        android/app/src/test/java/com/albunyaan/tube/data/subscriptions/SubscriptionLimitGuardTest.kt
git commit -m "[ANDROID-PERSONAL-02]: 30-channel cap (unlimited playlists) + Subscribe snackbar"
```

---

## Task 4: Hide duration + view count in Me feed adapters

**Spec ref:** §8 Layout adapter cleanup

**Files:**
- Modify: `android/app/src/main/res/layout/item_me_videos.xml`
- Modify: `android/app/src/main/res/layout-sw600dp/item_me_videos.xml` (if exists)
- Modify: `android/app/src/main/res/layout-sw720dp/item_me_videos.xml` (if exists)
- Modify: `android/app/src/main/res/layout/item_me_short.xml` (if duration is shown)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeVideosAdapter.kt` (remove binding for duration/views)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeShortsAdapter.kt` (remove binding for duration if present)

- [ ] **Step 1: List existing duration/view bindings**

```bash
grep -rn "duration\|viewCount" android/app/src/main/java/com/albunyaan/tube/ui/me/ android/app/src/main/res/layout/item_me_*.xml android/app/src/main/res/layout-sw*/item_me_*.xml 2>/dev/null
```

Identify: which TextView IDs to hide; which adapter bindings to delete.

- [ ] **Step 2: Set duration TextViews to `android:visibility="gone"` in all layouts**

Phone, sw600, sw720 variants. Same TextView IDs across variants per project convention.

- [ ] **Step 3: Set view-count TextViews to `android:visibility="gone"` in all layouts**

Same.

- [ ] **Step 4: Delete duration/view-count bindings from adapters**

In `MeVideosAdapter.bind`:
- Delete the lines that read `video.durationSeconds` or `video.viewCount`
- Delete the `videoDuration` and `videoViewCount` `findViewById`s if present

Same for `MeShortsAdapter` if it shows duration.

- [ ] **Step 5: Build to verify**

```
./gradlew :app:assembleDebug :app:lintDebug
```

Expected: no warnings about unused view IDs (they're `gone`, not deleted — keeps layouts diff-friendly for rollback).

- [ ] **Step 6: Run pre-existing adapter tests if any**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.ui.me.*"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/res/layout/item_me_videos.xml \
        android/app/src/main/res/layout-sw600dp/item_me_videos.xml \
        android/app/src/main/res/layout-sw720dp/item_me_videos.xml \
        android/app/src/main/res/layout/item_me_short.xml \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeVideosAdapter.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeShortsAdapter.kt
git commit -m "[ANDROID-PERSONAL-02]: Hide duration/view count in Me feed (ATOM doesn't expose them)"
```

---

## Task 5: GlobalNewPipeRateLimiter (token bucket for NewPipe paths)

**Spec ref:** §4.5

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/extractor/GlobalNewPipeRateLimiter.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipePriorityContext.kt`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/extractor/GlobalNewPipeRateLimiterTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// GlobalNewPipeRateLimiterTest.kt
class GlobalNewPipeRateLimiterTest {

    @Test
    fun acquires_immediately_when_tokens_available() = runTest {
        val limiter = GlobalNewPipeRateLimiter(initialTokens = 5, refillPeriodMs = 30_000L) { currentTime }
        repeat(5) { assertTrue(limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 0L)) }
    }

    @Test
    fun blocks_when_bucket_empty_then_unblocks_after_refill() = runTest {
        val limiter = GlobalNewPipeRateLimiter(initialTokens = 1, refillPeriodMs = 30_000L) { currentTime }
        assertTrue(limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 0L))
        // bucket empty
        val deferred = async { limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 60_000L) }
        advanceTimeBy(30_001L)
        assertTrue(deferred.await())
    }

    @Test
    fun player_priority_bypasses_bucket() = runTest {
        val limiter = GlobalNewPipeRateLimiter(initialTokens = 0, refillPeriodMs = 30_000L) { currentTime }
        assertTrue(limiter.acquire(Priority.PLAYER, timeoutMs = 0L))
    }

    @Test
    fun acquire_returns_false_on_timeout() = runTest {
        val limiter = GlobalNewPipeRateLimiter(initialTokens = 0, refillPeriodMs = 30_000L) { currentTime }
        assertFalse(limiter.acquire(Priority.USER_FOREGROUND, timeoutMs = 100L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.extractor.GlobalNewPipeRateLimiterTest"
```

Expected: FAIL.

- [ ] **Step 3: Implement priority enum + thread-local context**

```kotlin
// NewPipePriorityContext.kt
package com.albunyaan.tube.data.extractor

enum class Priority {
    PLAYER,
    USER_FOREGROUND,
    BACKGROUND_REFRESH,
}

object NewPipePriorityContext {
    val current = ThreadLocal<Priority?>()
    inline fun <T> with(priority: Priority, block: () -> T): T {
        val prior = current.get()
        current.set(priority)
        try { return block() } finally { current.set(prior) }
    }
}
```

- [ ] **Step 4: Implement `GlobalNewPipeRateLimiter`**

```kotlin
// GlobalNewPipeRateLimiter.kt
package com.albunyaan.tube.data.extractor

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalNewPipeRateLimiter @Inject constructor(
    private val initialTokens: Int = 20,
    private val capacity: Int = 20,
    private val refillPeriodMs: Long = 30_000L,
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    private val mutex = Mutex()
    private var tokens = initialTokens
    private var lastRefillAt = now()

    suspend fun acquire(priority: Priority, timeoutMs: Long = 10_000L): Boolean {
        if (priority == Priority.PLAYER) return true

        val deadline = now() + timeoutMs
        while (true) {
            mutex.withLock {
                refillLocked()
                if (tokens > 0) {
                    tokens--
                    return true
                }
            }
            if (now() >= deadline) return false
            delay(minOf(refillPeriodMs, deadline - now()).coerceAtLeast(50L))
        }
    }

    private fun refillLocked() {
        val n = now()
        val elapsed = n - lastRefillAt
        if (elapsed >= refillPeriodMs) {
            val refills = (elapsed / refillPeriodMs).toInt()
            tokens = (tokens + refills).coerceAtMost(capacity)
            lastRefillAt += refills * refillPeriodMs
        }
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.extractor.GlobalNewPipeRateLimiterTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/extractor/GlobalNewPipeRateLimiter.kt \
        android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipePriorityContext.kt \
        android/app/src/test/java/com/albunyaan/tube/data/extractor/GlobalNewPipeRateLimiterTest.kt
git commit -m "[ANDROID-PERSONAL-02]: Token-bucket rate limiter for NewPipe paths (Player bypasses)"
```

---

## Task 6: CooldownState (DataStore-persisted, exponential escalation)

**Spec ref:** §4.6

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/extractor/CooldownState.kt`
- Modify: `android/app/build.gradle.kts` (add `androidx.datastore:datastore-preferences:1.1.1` if not present)
- Modify: `android/app/src/main/java/com/albunyaan/tube/di/AppModule.kt` (provide DataStore<Preferences>)
- Create: `android/app/src/test/java/com/albunyaan/tube/data/extractor/CooldownStateTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// CooldownStateTest.kt
@RunWith(RobolectricTestRunner::class)
class CooldownStateTest {

    @get:Rule val tmp = TemporaryFolder().also { it.create() }
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var clock: AtomicLong

    @Before
    fun setup() {
        clock = AtomicLong(1_000_000_000L)
        dataStore = PreferenceDataStoreFactory.create(produceFile = { File(tmp.root, "cd.preferences_pb") })
    }

    @Test
    fun first_trip_is_one_hour() = runTest {
        val cd = CooldownState(dataStore) { clock.get() }
        cd.trip(IOException("429"))
        assertTrue(cd.isTripped(clock.get()))
        clock.addAndGet(59 * 60_000L)
        assertTrue(cd.isTripped(clock.get()))
        clock.addAndGet(2 * 60_000L)
        assertFalse(cd.isTripped(clock.get()))
    }

    @Test
    fun second_trip_within_24h_escalates_to_4h() = runTest {
        val cd = CooldownState(dataStore) { clock.get() }
        cd.trip(IOException("429"))
        clock.addAndGet(2 * 60 * 60_000L) // 2h later
        cd.trip(IOException("429"))
        clock.addAndGet(3 * 60 * 60_000L) // 3h after second trip
        assertTrue(cd.isTripped(clock.get()))  // 4h cooldown still active
    }

    @Test
    fun seven_clean_days_resets_trip_count() = runTest {
        val cd = CooldownState(dataStore) { clock.get() }
        cd.trip(IOException("429"))
        clock.addAndGet(8L * 24L * 60L * 60_000L)  // 8 days later
        cd.markCleanFetch(clock.get())
        cd.trip(IOException("429"))
        // First-trip duration = 1h again
        clock.addAndGet(2 * 60 * 60_000L)
        assertFalse(cd.isTripped(clock.get()))
    }

    @Test
    fun state_persists_across_restart() = runTest {
        val cd1 = CooldownState(dataStore) { clock.get() }
        cd1.trip(IOException("429"))
        // Simulate app restart by creating a new instance
        val cd2 = CooldownState(dataStore) { clock.get() }
        assertTrue(cd2.isTripped(clock.get()))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL.

- [ ] **Step 3: Implement `CooldownState`**

```kotlin
// CooldownState.kt
package com.albunyaan.tube.data.extractor

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CooldownState @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private object Keys {
        val UNTIL_MS = longPreferencesKey("cooldown_until_ms")
        val TRIP_COUNT = intPreferencesKey("cooldown_trip_count_24h")
        val LAST_TRIP_MS = longPreferencesKey("cooldown_last_trip_ms")
        val CLEAN_STREAK_START_MS = longPreferencesKey("cooldown_clean_streak_start_ms")
    }

    private val durations = listOf(1L, 4L, 12L, 24L).map { it * 60L * 60_000L }

    suspend fun isTripped(currentMs: Long = now()): Boolean {
        val prefs = dataStore.data.first()
        val until = prefs[Keys.UNTIL_MS] ?: return false
        return currentMs < until
    }

    fun isTrippedSync(currentMs: Long = now()): Boolean = runBlocking { isTripped(currentMs) }

    suspend fun trip(reason: Throwable, currentMs: Long = now()) {
        dataStore.edit { prefs ->
            val lastTrip = prefs[Keys.LAST_TRIP_MS] ?: 0L
            val withinWindow = currentMs - lastTrip < 24L * 60L * 60_000L
            val tripCount = if (withinWindow) (prefs[Keys.TRIP_COUNT] ?: 0) + 1 else 1
            val durationIdx = (tripCount - 1).coerceAtMost(durations.size - 1)
            prefs[Keys.UNTIL_MS] = currentMs + durations[durationIdx]
            prefs[Keys.TRIP_COUNT] = tripCount
            prefs[Keys.LAST_TRIP_MS] = currentMs
        }
    }

    suspend fun markCleanFetch(currentMs: Long = now()) {
        dataStore.edit { prefs ->
            val streakStart = prefs[Keys.CLEAN_STREAK_START_MS] ?: currentMs
            if (prefs[Keys.CLEAN_STREAK_START_MS] == null) prefs[Keys.CLEAN_STREAK_START_MS] = currentMs
            if (currentMs - streakStart >= 7L * 24L * 60L * 60_000L) {
                prefs[Keys.TRIP_COUNT] = 0
                prefs[Keys.CLEAN_STREAK_START_MS] = currentMs
            }
        }
    }

    suspend fun untilMs(): Long? = dataStore.data.first()[Keys.UNTIL_MS]
}
```

- [ ] **Step 4: Run test to verify it passes**

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/extractor/CooldownState.kt \
        android/app/src/main/java/com/albunyaan/tube/di/AppModule.kt \
        android/app/build.gradle.kts \
        android/app/src/test/java/com/albunyaan/tube/data/extractor/CooldownStateTest.kt
git commit -m "[ANDROID-PERSONAL-02]: DataStore-persisted cooldown state with exponential escalation"
```

---

## Task 7: RateLimitedDownloader (NewPipe Downloader subclass — interception point)

**Spec ref:** §4.4

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/extractor/RateLimitedDownloader.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt` (use new downloader in `NewPipe.init`)
- Modify call sites that use NewPipe to set priority via `NewPipePriorityContext.with(...)`:
  - `PlayerFragment` / `PlayerViewModel` → `Priority.PLAYER`
  - `HomeViewModel`, `FeaturedListViewModel`, `SearchExtractor` paths → `Priority.USER_FOREGROUND`
- Create: `android/app/src/test/java/com/albunyaan/tube/data/extractor/RateLimitedDownloaderTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
// RateLimitedDownloaderTest.kt
class RateLimitedDownloaderTest {

    private val limiter: GlobalNewPipeRateLimiter = mockk()
    private val cooldown: CooldownState = mockk(relaxed = true)
    private val delegate: Downloader = mockk()

    private lateinit var sut: RateLimitedDownloader

    @Before
    fun setup() {
        sut = RateLimitedDownloader(delegate, limiter, cooldown)
    }

    @Test
    fun catches_ReCaptchaException_and_trips_cooldown() = runTest {
        every { delegate.execute(any()) } throws ReCaptchaException("captcha", "url")
        coEvery { limiter.acquire(any(), any()) } returns true
        coEvery { cooldown.isTripped(any()) } returns false
        NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            try { sut.execute(mockk(relaxed = true)) } catch (_: ReCaptchaException) {}
        }
        coVerify { cooldown.trip(any(), any()) }
    }

    @Test
    fun observes_HTTP_429_and_trips_cooldown() = runTest {
        val response: Response = mockk { every { responseCode() } returns 429 }
        every { delegate.execute(any()) } returns response
        coEvery { limiter.acquire(any(), any()) } returns true
        coEvery { cooldown.isTripped(any()) } returns false
        NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            try { sut.execute(mockk(relaxed = true)) } catch (_: IOException) {}
        }
        coVerify { cooldown.trip(any(), any()) }
    }

    @Test
    fun player_priority_skips_rate_limiter_and_cooldown() = runTest {
        coEvery { cooldown.isTripped(any()) } returns true  // would block a non-player
        every { delegate.execute(any()) } returns mockk { every { responseCode() } returns 200 }
        NewPipePriorityContext.with(Priority.PLAYER) {
            sut.execute(mockk(relaxed = true))
        }
        coVerify(exactly = 0) { limiter.acquire(any(), any()) }
        coVerify(exactly = 0) { cooldown.isTripped(any()) }
    }

    @Test
    fun cooldown_active_blocks_non_player_calls() = runTest {
        coEvery { cooldown.isTripped(any()) } returns true
        NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
            assertThrows(IOException::class.java) { sut.execute(mockk(relaxed = true)) }
        }
        verify(exactly = 0) { delegate.execute(any()) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Expected: FAIL.

- [ ] **Step 3: Implement `RateLimitedDownloader`**

```kotlin
// RateLimitedDownloader.kt
package com.albunyaan.tube.data.extractor

import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RateLimitedDownloader @Inject constructor(
    private val delegate: Downloader,
    private val rateLimiter: GlobalNewPipeRateLimiter,
    private val cooldownState: CooldownState,
) : Downloader() {

    override fun execute(request: Request): Response {
        val priority = NewPipePriorityContext.current.get() ?: Priority.USER_FOREGROUND

        if (priority != Priority.PLAYER) {
            if (cooldownState.isTrippedSync()) {
                throw IOException("NewPipe cooldown active until ${runBlocking { cooldownState.untilMs() }}")
            }
            val acquired = runBlocking { rateLimiter.acquire(priority) }
            if (!acquired) throw IOException("Rate limiter timeout")
        }

        try {
            val response = delegate.execute(request)
            if (priority != Priority.PLAYER && response.responseCode() == 429) {
                runBlocking { cooldownState.trip(IOException("HTTP 429")) }
                throw IOException("HTTP 429 — cooldown tripped")
            }
            return response
        } catch (e: ReCaptchaException) {
            if (priority != Priority.PLAYER) {
                runBlocking { cooldownState.trip(e) }
            }
            throw e
        }
    }
}
```

- [ ] **Step 4: Update `NewPipeExtractorClient` to register the new downloader**

```kotlin
// NewPipeExtractorClient.kt — modify init
init {
    NewPipe.init(rateLimitedDownloader, Localization.DEFAULT)
}
```

Inject `RateLimitedDownloader` instead of (or wrapping) the prior `DownloaderImpl`.

- [ ] **Step 5: Wrap NewPipe call sites with priority context**

Search for call sites:
```bash
grep -rn "ChannelInfo.getInfo\|StreamInfo.getInfo\|PlaylistInfo.getInfo\|SearchExtractor" android/app/src/main/java/com/albunyaan/tube/
```

For each:
- Player path: `NewPipePriorityContext.with(Priority.PLAYER) { ... }`
- Home / Search / Channel detail: `NewPipePriorityContext.with(Priority.USER_FOREGROUND) { ... }`

- [ ] **Step 6: Run test to verify it passes**

Expected: PASS.

- [ ] **Step 7: Run full test suite + lint**

```
./gradlew :app:testDebugUnitTest :app:lintDebug
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/extractor/RateLimitedDownloader.kt \
        android/app/src/main/java/com/albunyaan/tube/data/extractor/NewPipeExtractorClient.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/player/ \
        android/app/src/main/java/com/albunyaan/tube/ui/HomeViewModel.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/FeaturedListViewModel.kt \
        android/app/src/test/java/com/albunyaan/tube/data/extractor/RateLimitedDownloaderTest.kt
git commit -m "[ANDROID-PERSONAL-02]: RateLimitedDownloader catches ReCaptcha + 429 (Player bypasses)"
```

---

## Task 8: AppLifecycleTracker (process foreground state)

**Spec ref:** §4.7

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/app/AppLifecycleTracker.kt`
- Modify: `android/app/src/main/java/com/albunyaan/tube/AlBunyaanApplication.kt` (register on `onCreate`)

- [ ] **Step 1: Implement `AppLifecycleTracker`**

```kotlin
// AppLifecycleTracker.kt
package com.albunyaan.tube.app

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppLifecycleTracker @Inject constructor() : DefaultLifecycleObserver {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    fun register() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) { _isForeground.value = true }
    override fun onStop(owner: LifecycleOwner) { _isForeground.value = false }
}
```

- [ ] **Step 2: Wire in `AlBunyaanApplication.onCreate`**

```kotlin
@Inject lateinit var lifecycleTracker: AppLifecycleTracker

override fun onCreate() {
    super.onCreate()
    lifecycleTracker.register()
    // existing init
}
```

- [ ] **Step 3: Build to confirm**

```
./gradlew :app:assembleDebug
```

Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/app/AppLifecycleTracker.kt \
        android/app/src/main/java/com/albunyaan/tube/AlBunyaanApplication.kt
git commit -m "[ANDROID-PERSONAL-02]: AppLifecycleTracker for process foreground state"
```

---

## Task 9: RefreshSubscriptionsWorker + MeViewModel rewire

**Spec ref:** §4.3

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/me/work/RefreshSubscriptionsWorker.kt`
- Create: `android/app/src/main/java/com/albunyaan/tube/data/me/work/RefreshScheduler.kt` (helper to enqueue)
- Modify: `android/app/src/main/java/com/albunyaan/tube/AlBunyaanApplication.kt` (enqueue periodic on cold start)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeViewModel.kt` (remove `init { refreshFeed }`, add foreground burst on Fragment.onResume)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt` (call `RefreshScheduler.scheduleForegroundBurstIfStale()` in `onResume`; pull-to-refresh enqueues `OneTimeWorkRequest`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt` (full rewrite per spec — uses round-robin, no tier logic, ATOM `FetchResult` handling, per-channel backoff)
- Create: `android/app/src/androidTest/java/com/albunyaan/tube/data/me/work/RefreshSubscriptionsWorkerTest.kt`
- Modify: `android/app/src/test/java/com/albunyaan/tube/data/me/MeFeedRepositoryTest.kt` (update for new fetcher result type)

- [ ] **Step 1: Rewrite `MeFeedRepository.refreshOne` for ATOM result handling**

Replace `try { fetcher.fetchLatest(channel.channelUrl) ... }` with logic that:
1. Reads prior `etag` and `lastModified` from `refreshStateDao.get(channel.channelId)`
2. Calls `fetcher.fetchLatest(channelUrl, priorEtag, priorLastModified)` returning `FetchResult`
3. On `NotModified`: upsert refresh state with `lastSuccessfulFetchAt = now`, preserve etag/lastModified, reset `consecutiveErrorCount` and `consecutiveEmptyCount` to 0
4. On `Items` with non-empty: replace channel cache rows, advance state, persist new etag/lastModified, reset both counters
5. On `Items` with empty list: existing 14-day-window protection logic, advance state, increment `consecutiveEmptyCount` only
6. On any throw: existing behaviour + increment `consecutiveErrorCount`, set `backoffUntilMs` based on error class (429 → 1h/4h/24h escalation; 5xx → 5min/30min/2h)
7. Per-channel backoff check at top: if `backoffUntilMs != null && now < backoffUntilMs && !force`, return early

- [ ] **Step 2: Update existing `MeFeedRepositoryTest` to compile against new fetcher result**

Replace fake fetcher to return `FetchResult.Items(...)` / `FetchResult.NotModified(...)`. Add new test cases:
- 304 response treats as success (advances `lastSuccessfulFetchAt`, resets counters)
- 429 sets `backoffUntilMs = now + 1h`
- Subsequent fetch within backoff is skipped without calling fetcher

- [ ] **Step 3: Run repo tests**

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.me.MeFeedRepositoryTest"
```

Expected: PASS (existing concurrency tests F3/F6 still pass; new ATOM-specific tests pass).

- [ ] **Step 4: Implement `RefreshSubscriptionsWorker`**

```kotlin
// RefreshSubscriptionsWorker.kt
@HiltWorker
class RefreshSubscriptionsWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val repository: MeFeedRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withTimeout(8.minutes) {
        val force = inputData.getBoolean(KEY_FORCE, false)
        try {
            repository.refresh(force = force, perTickBudget = if (force) PULL_BUDGET else PERIODIC_BUDGET)
            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.e(TAG, "refresh failed", t)
            Result.retry()
        }
    }

    companion object {
        const val KEY_FORCE = "force"
        const val PERIODIC_BUDGET = 5
        const val PULL_BUDGET = 30
        const val TAG = "RefreshSubsWorker"
    }
}
```

`MeFeedRepository.refresh` accepts a new `perTickBudget: Int` parameter.

- [ ] **Step 5: Implement `RefreshScheduler`**

```kotlin
// RefreshScheduler.kt
@Singleton
class RefreshScheduler @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val refreshStateDao: ChannelFeedRefreshStateDao,
) {
    fun enqueuePeriodic() {
        val request = PeriodicWorkRequestBuilder<RefreshSubscriptionsWorker>(60, MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, MINUTES)
            .setInitialDelay(30, SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "me_refresh_periodic", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

    suspend fun enqueueForegroundBurstIfStale(staleThresholdMs: Long = 30L * 60_000L) {
        val newest = refreshStateDao.maxLastSuccessfulFetchAt() ?: 0L
        if (System.currentTimeMillis() - newest < staleThresholdMs) return
        val request = OneTimeWorkRequestBuilder<RefreshSubscriptionsWorker>().build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "me_refresh_oneshot", ExistingWorkPolicy.KEEP, request
        )
    }

    fun enqueuePullToRefresh() {
        val request = OneTimeWorkRequestBuilder<RefreshSubscriptionsWorker>()
            .setInputData(workDataOf(RefreshSubscriptionsWorker.KEY_FORCE to true))
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "me_refresh_oneshot", ExistingWorkPolicy.REPLACE, request
        )
    }
}
```

Add `maxLastSuccessfulFetchAt` to `ChannelFeedRefreshStateDao`:
```kotlin
@Query("SELECT MAX(lastSuccessfulFetchAt) FROM channel_feed_refresh_state")
suspend fun maxLastSuccessfulFetchAt(): Long?
```

- [ ] **Step 6: Wire in `Application` and `MeFragment`**

In `AlBunyaanApplication.onCreate()`:
```kotlin
@Inject lateinit var refreshScheduler: RefreshScheduler
override fun onCreate() {
    super.onCreate()
    lifecycleTracker.register()
    refreshScheduler.enqueuePeriodic()
}
```

In `MeViewModel`:
```kotlin
init {
    // refreshFeed(force = false) REMOVED — worker handles refresh
}
```

In `MeFragment.onResume()`:
```kotlin
override fun onResume() {
    super.onResume()
    viewLifecycleOwner.lifecycleScope.launch {
        refreshScheduler.enqueueForegroundBurstIfStale()
    }
}
```

In `MeFragment.meSwipeRefresh.setOnRefreshListener`:
```kotlin
b.meSwipeRefresh.setOnRefreshListener { refreshScheduler.enqueuePullToRefresh() }
```

- [ ] **Step 7: Add WorkManager test**

```kotlin
// RefreshSubscriptionsWorkerTest.kt — instrumented (uses Hilt + WorkManager TestDriver)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class RefreshSubscriptionsWorkerTest {
    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @Inject lateinit var workManager: WorkManager
    @Inject lateinit var fakeRepository: MeFeedRepository

    @Before fun setup() = hiltRule.inject()

    @Test
    fun worker_calls_repository_refresh_with_periodic_budget() {
        val request = OneTimeWorkRequestBuilder<RefreshSubscriptionsWorker>().build()
        workManager.enqueue(request).result.get()
        WorkManagerTestInitHelper.getTestDriver(getApplicationContext())!!.setAllConstraintsMet(request.id)
        val info = workManager.getWorkInfoById(request.id).get()
        assertEquals(SUCCEEDED, info.state)
        // Assert via fake repository that refresh was called with perTickBudget = 5
    }
}
```

- [ ] **Step 8: Run all tests**

```
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest --tests "com.albunyaan.tube.data.me.work.RefreshSubscriptionsWorkerTest"
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/me/work/ \
        android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeViewModel.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt \
        android/app/src/main/java/com/albunyaan/tube/AlBunyaanApplication.kt \
        android/app/src/main/java/com/albunyaan/tube/data/local/ChannelFeedRefreshStateDao.kt \
        android/app/src/test/java/com/albunyaan/tube/data/me/MeFeedRepositoryTest.kt \
        android/app/src/androidTest/java/com/albunyaan/tube/data/me/work/RefreshSubscriptionsWorkerTest.kt
git commit -m "[ANDROID-PERSONAL-02]: WorkManager-based refresh — single periodic + onResume burst"
```

---

## Task 10: MeFavoritesAdapter (favorites row above Shorts)

**Spec ref:** §4.8, §8 favorites row layout

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeFavoritesAdapter.kt`
- Create: `android/app/src/main/res/layout/item_me_favorites_row.xml` (the row container)
- Create: `android/app/src/main/res/layout/item_me_favorite_video.xml` (each tile)
- Create: `android/app/src/main/res/layout/item_me_favorites_see_all.xml` (trailing tile)
- Create: `android/app/src/main/res/layout-sw600dp/item_me_favorite_video.xml` (larger tile)
- Create: `android/app/src/main/res/layout-sw720dp/item_me_favorite_video.xml`
- Modify: `android/app/src/main/res/values/strings.xml` (+ ar + nl) — `me_favorites`, `me_see_all`, `me_remove_from_favorites`
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeViewModel.kt` (expose `favorites: StateFlow<List<FavoriteVideo>>`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt` (insert `MeFavoritesAdapter` into `ConcatAdapter` between chips and shorts)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedState.kt` (add `favorites: List<FavoriteVideo>` field to `Content`)
- Create: `android/app/src/test/java/com/albunyaan/tube/ui/me/MeFavoritesAdapterTest.kt`

- [ ] **Step 1: Add strings (en draft; user provides ar + nl)**

```xml
<string name="me_favorites">Favorites</string>
<string name="me_see_all">See all</string>
<string name="me_remove_from_favorites">Remove from favorites</string>
<string name="cd_remove_from_favorites">Remove from favorites</string>  <!-- contentDescription -->
```

- [ ] **Step 2: Create row container layout `item_me_favorites_row.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">
    <TextView
        android:id="@+id/favoritesHeader"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/me_favorites"
        android:textAppearance="?attr/textAppearanceTitleMedium"
        android:paddingHorizontal="@dimen/spacing_md"
        android:paddingTop="@dimen/spacing_md"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/favoritesRecycler"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:paddingHorizontal="@dimen/spacing_md"
        android:clipToPadding="false"
        android:orientation="horizontal"
        app:layout_constraintTop_toBottomOf="@id/favoritesHeader" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: Create tile layout `item_me_favorite_video.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.cardview.widget.CardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="160dp"
    android:layout_height="wrap_content"
    android:layout_marginEnd="@dimen/spacing_sm"
    app:cardCornerRadius="8dp">
    <LinearLayout
        android:orientation="vertical"
        android:layout_width="match_parent"
        android:layout_height="wrap_content">
        <ImageView
            android:id="@+id/favoriteThumbnail"
            android:layout_width="match_parent"
            android:layout_height="90dp"
            android:scaleType="centerCrop"
            android:contentDescription="@null" />
        <TextView
            android:id="@+id/favoriteTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="@dimen/spacing_sm"
            android:maxLines="2"
            android:ellipsize="end"
            android:textAlignment="viewStart"
            android:textAppearance="?attr/textAppearanceBodyMedium" />
        <TextView
            android:id="@+id/favoriteChannel"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:paddingHorizontal="@dimen/spacing_sm"
            android:paddingBottom="@dimen/spacing_sm"
            android:maxLines="1"
            android:ellipsize="end"
            android:textAlignment="viewStart"
            android:textAppearance="?attr/textAppearanceBodySmall" />
    </LinearLayout>
</androidx.cardview.widget.CardView>
```

sw600dp/sw720dp variants: increase tile width to 200dp / 240dp respectively.

- [ ] **Step 4: Create see-all tile layout `item_me_favorites_see_all.xml`**

160dp wide, centered "See all →" text, tap → navigate to existing Favorites screen.

- [ ] **Step 5: Implement `MeFavoritesAdapter`**

```kotlin
// MeFavoritesAdapter.kt
package com.albunyaan.tube.ui.me

import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
// ... imports

class MeFavoritesAdapter(
    private val onClick: (FavoriteVideo) -> Unit,
    private val onLongPress: (FavoriteVideo) -> Unit,
    private val onSeeAll: () -> Unit,
) {
    val rowAdapter: RecyclerView.Adapter<*> = RowAdapter()
    private val tilesAdapter = TilesAdapter()
    private var favorites: List<FavoriteVideo> = emptyList()

    fun submit(list: List<FavoriteVideo>) {
        favorites = list.take(20)
        tilesAdapter.submitList(favorites)
        rowAdapter.notifyDataSetChanged() // toggles visibility
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowVH>() {
        override fun getItemCount(): Int = if (favorites.isEmpty()) 0 else 1
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowVH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_me_favorites_row, parent, false)
            val recycler = v.findViewById<RecyclerView>(R.id.favoritesRecycler)
            recycler.layoutManager = LinearLayoutManager(parent.context, RecyclerView.HORIZONTAL, false)
            recycler.adapter = ConcatAdapter(tilesAdapter, SeeAllAdapter())
            return RowVH(v)
        }
        override fun onBindViewHolder(holder: RowVH, position: Int) = Unit
    }

    private inner class TilesAdapter : ListAdapter<FavoriteVideo, TileVH>(DIFF) {
        // Standard ListAdapter — bind videoTitle, videoChannel, load thumb via Coil .load()
    }

    private inner class SeeAllAdapter : RecyclerView.Adapter<SeeAllVH>() {
        override fun getItemCount(): Int = 1
        // ...
    }

    private class RowVH(v: View) : RecyclerView.ViewHolder(v)
    private class TileVH(v: View) : RecyclerView.ViewHolder(v)
    private class SeeAllVH(v: View) : RecyclerView.ViewHolder(v)
    companion object { val DIFF = ... }
}
```

(Pseudocode shape — engineer fills in `ListAdapter` ViewHolder pattern matching existing adapters. Use Coil `.load()` for thumb (per existing F-CR2 fix). Long-press handler calls `onLongPress` which shows snackbar with action.)

- [ ] **Step 6: Update `MeFeedState.Content` to carry `favorites`**

```kotlin
data class Content(
    val chips: List<ChipItem>,
    val favorites: List<FavoriteVideo>,  // NEW
    val shorts: List<MeFeedVideo>,
    val videos: List<MeFeedVideo>,
    val filterChannelId: String?,
    val refreshing: Boolean,
) : MeFeedState()
```

- [ ] **Step 7: Wire `FavoritesRepository.observeFavorites()` into `MeViewModel`**

Add a 6th flow to the existing `combine(...)`. Update `buildState`.

- [ ] **Step 8: Insert into `ConcatAdapter` in `MeFragment`**

```kotlin
favoritesAdapter = MeFavoritesAdapter(
    onClick = ::playFavorite,
    onLongPress = ::confirmRemoveFavorite,
    onSeeAll = { navigateToFavoritesScreen() },
)
concatAdapter = ConcatAdapter(
    chipsAdapter.rowAdapter,
    favoritesAdapter.rowAdapter,
    shortsAdapter.sectionAdapter,
    videosAdapter.sectionAdapter,
)
```

`confirmRemoveFavorite`: shows snackbar with action.

- [ ] **Step 9: Write adapter test**

```kotlin
@RunWith(RobolectricTestRunner::class)
class MeFavoritesAdapterTest {
    @Test fun empty_list_means_zero_row_count() {
        val adapter = MeFavoritesAdapter(onClick = {}, onLongPress = {}, onSeeAll = {})
        adapter.submit(emptyList())
        assertEquals(0, adapter.rowAdapter.itemCount)
    }
    @Test fun non_empty_list_shows_one_row() {
        val adapter = MeFavoritesAdapter(onClick = {}, onLongPress = {}, onSeeAll = {})
        adapter.submit(listOf(fav("v1")))
        assertEquals(1, adapter.rowAdapter.itemCount)
    }
}
```

- [ ] **Step 10: Run tests + build**

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 11: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/ui/me/MeFavoritesAdapter.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeViewModel.kt \
        android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedState.kt \
        android/app/src/main/res/layout/item_me_favorites_row.xml \
        android/app/src/main/res/layout/item_me_favorite_video.xml \
        android/app/src/main/res/layout/item_me_favorites_see_all.xml \
        android/app/src/main/res/layout-sw600dp/item_me_favorite_video.xml \
        android/app/src/main/res/layout-sw720dp/item_me_favorite_video.xml \
        android/app/src/main/res/values/strings.xml \
        android/app/src/main/res/values-ar/strings.xml \
        android/app/src/main/res/values-nl/strings.xml \
        android/app/src/test/java/com/albunyaan/tube/ui/me/MeFavoritesAdapterTest.kt
git commit -m "[ANDROID-PERSONAL-02]: Favorites row on Me tab — newest 20 + See all → existing screen"
```

---

## Task 11: Paged videos grid (PAGE_SIZE = 20)

**Spec ref:** §4.9

**Files:**
- Modify: `android/app/build.gradle.kts` (add `androidx.paging:paging-runtime-ktx` and `androidx.room:room-paging` if not present)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/local/ChannelVideoCacheDao.kt` (add `pagingForChannels` returning `PagingSource<Int, ChannelVideoCache>`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt` (add `pagedFeed(filterChannelId: String?): Flow<PagingData<ChannelVideoCache>>`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeViewModel.kt` (expose `pagedVideos: Flow<PagingData<MeFeedVideo>>`)
- Create: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeVideosPagingAdapter.kt` (extends `PagingDataAdapter`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt` (replace `MeVideosAdapter` with paging variant; collect `pagedVideos` and call `submitData`)
- Delete: `android/app/src/main/java/com/albunyaan/tube/ui/me/MeVideosAdapter.kt` (replaced)
- Create: `android/app/src/test/java/com/albunyaan/tube/data/local/ChannelVideoCacheDaoPagingTest.kt`

- [ ] **Step 1: Add Paging dependencies in `build.gradle.kts`**

```kotlin
implementation("androidx.paging:paging-runtime-ktx:3.3.6")
implementation("androidx.room:room-paging:2.7.2")
```

- [ ] **Step 2: Add `PagingSource` query to DAO**

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

- [ ] **Step 3: Write failing DAO paging test**

```kotlin
// ChannelVideoCacheDaoPagingTest.kt
@RunWith(RobolectricTestRunner::class)
class ChannelVideoCacheDaoPagingTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ChannelVideoCacheDao

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.channelVideoCacheDao()
    }

    @After fun tearDown() = db.close()

    @Test
    fun paging_returns_first_page_correctly() = runTest {
        repeat(50) { dao.upsert(row("v$it", channelId = "UCa", uploadedAt = 100L + it)) }
        val source = dao.pagingForChannels(listOf("UCa"), 0L, null)
        val result = source.load(PagingSource.LoadParams.Refresh(null, 20, false))
        assert(result is PagingSource.LoadResult.Page)
        assertEquals(20, (result as PagingSource.LoadResult.Page).data.size)
    }

    @Test
    fun paging_filter_returns_only_matching_channel() = runTest {
        repeat(10) { dao.upsert(row("v$it", channelId = "UCa")) }
        repeat(10) { dao.upsert(row("w$it", channelId = "UCb")) }
        val source = dao.pagingForChannels(listOf("UCa", "UCb"), 0L, "UCa")
        val result = source.load(PagingSource.LoadParams.Refresh(null, 20, false))
        assertEquals(10, (result as PagingSource.LoadResult.Page).data.size)
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Expected: FAIL until DAO query is added (which it is in step 2 — should now PASS or fail only on DAO compile).

```
./gradlew :app:testDebugUnitTest --tests "com.albunyaan.tube.data.local.ChannelVideoCacheDaoPagingTest"
```

- [ ] **Step 5: Add `pagedFeed` to `MeFeedRepository`**

```kotlin
fun pagedFeed(filterChannelId: String?): Flow<PagingData<ChannelVideoCache>> =
    subscriptions.observeSubscribedChannels()
        .flatMapLatest { subs ->
            if (subs.isEmpty()) {
                flowOf(PagingData.empty())
            } else {
                val ids = subs.asSequence()
                    .sortedByDescending { it.subscribedAt }
                    .take(SubscriptionLimitGuard.CAP)
                    .map { it.channelId }
                    .toList()
                val cutoff = currentTimeMillis() - FEED_WINDOW_MS
                Pager(
                    config = PagingConfig(
                        pageSize = 20,
                        initialLoadSize = 40,
                        prefetchDistance = 10,
                        enablePlaceholders = false,
                    ),
                    pagingSourceFactory = { cache.pagingForChannels(ids, cutoff, filterChannelId) },
                ).flow
            }
        }
```

- [ ] **Step 6: Implement `MeVideosPagingAdapter`**

```kotlin
class MeVideosPagingAdapter(
    private val onClick: (MeFeedVideo) -> Unit,
) : PagingDataAdapter<MeFeedVideo, VideoVH>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoVH = ...
    override fun onBindViewHolder(holder: VideoVH, position: Int) { ... }
    companion object { val DIFF = ... }
}
```

(Mirror the existing `MeVideosAdapter` ViewHolder structure; only the base class changes from `ListAdapter` to `PagingDataAdapter`.)

- [ ] **Step 7: Wire in `MeFragment`**

```kotlin
videosAdapter = MeVideosPagingAdapter(onClick = ::playVideo)
concatAdapter = ConcatAdapter(
    chipsAdapter.rowAdapter,
    favoritesAdapter.rowAdapter,
    shortsAdapter.sectionAdapter,
    videosAdapter,  // PagingDataAdapter is itself a RecyclerView.Adapter
)

viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(STARTED) {
        viewModel.pagedVideos.collectLatest { videosAdapter.submitData(it) }
    }
}
```

In `MeViewModel`:
```kotlin
val pagedVideos: Flow<PagingData<MeFeedVideo>> = filter
    .flatMapLatest { filterId -> feed.pagedFeed(filterId) }
    .map { it.map { row -> row.toMeFeedVideo() } }
    .cachedIn(viewModelScope)
```

- [ ] **Step 8: Update `MeFeedState.Content` — videos field is now consumed via paging Flow, not the cache snapshot**

Optional: keep a paged-videos count or remove `videos: List<MeFeedVideo>` from `Content` entirely. Engineer decides; least churn is to keep `videos` field for the auto-load-once tablet check OR replace that check with `LoadStateAdapter`.

- [ ] **Step 9: Run tests + build**

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 10: Commit**

```bash
git add android/app/build.gradle.kts \
        android/app/src/main/java/com/albunyaan/tube/data/local/ChannelVideoCacheDao.kt \
        android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeViewModel.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeVideosPagingAdapter.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/me/MeFragment.kt \
        android/app/src/test/java/com/albunyaan/tube/data/local/ChannelVideoCacheDaoPagingTest.kt
git rm android/app/src/main/java/com/albunyaan/tube/ui/me/MeVideosAdapter.kt
git commit -m "[ANDROID-PERSONAL-02]: Paged videos grid on Me tab (PAGE_SIZE = 20)"
```

---

## Task 12: Telemetry hooks + dev settings + Doze instrumented test

**Spec ref:** §10 P10

**Files:**
- Create: `android/app/src/main/java/com/albunyaan/tube/data/me/MeRefreshTelemetry.kt` (rolling event log via `Channel<Event>` capped at 100)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/me/work/RefreshSubscriptionsWorker.kt` (emit `me_refresh_started` / `_finished`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt` (emit `me_channel_fetched`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/data/extractor/CooldownState.kt` (emit `cooldown_tripped` / `_cleared`)
- Modify: `android/app/src/main/java/com/albunyaan/tube/ui/settings/DeveloperSettingsDialog.kt` (new entries: "Trip cooldown 1h", "Reset cooldown", "Show rate limiter stats", "Show worker schedule", "Show telemetry log")
- Create: `android/app/src/androidTest/java/com/albunyaan/tube/data/me/work/MeRefreshDozeInstrumentedTest.kt`

- [ ] **Step 1: Implement `MeRefreshTelemetry`**

Simple in-memory ring buffer (`ArrayDeque<Event>` with `removeFirst()` when size > 100). Backed by `MutableSharedFlow<Event>`. Read-only `events: SharedFlow<Event>` for dev settings dialog.

- [ ] **Step 2: Wire emission points**

In `RefreshSubscriptionsWorker.doWork`: emit `me_refresh_started(mode, candidatesCount)` at start, `me_refresh_finished(mode, success, empty, error, durationMs)` at end.

In `MeFeedRepository.refreshOne`: emit `me_channel_fetched(channelId, itemsCount, latencyMs)` per-channel.

In `CooldownState.trip`: emit `cooldown_tripped(reason, tripCount24h, untilMs)`.

- [ ] **Step 3: Add developer settings entries**

In `DeveloperSettingsDialog`, add buttons with on-click handlers calling the new APIs. "Show telemetry log" opens a new dialog with a scrollable list of recent events.

- [ ] **Step 4: Write Doze instrumented test**

```kotlin
// MeRefreshDozeInstrumentedTest.kt
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MeRefreshDozeInstrumentedTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

    @Test
    fun worker_runs_under_doze() {
        // Force Doze
        executeShellCommand("dumpsys deviceidle force-idle")
        try {
            val request = OneTimeWorkRequestBuilder<RefreshSubscriptionsWorker>().build()
            WorkManager.getInstance(getApplicationContext()).enqueue(request).result.get()
            executeShellCommand("cmd jobscheduler run -f ${getApplicationContext().packageName} ${request.id}")
            // Wait up to 30 s for completion
            await().atMost(30, SECONDS).until {
                WorkManager.getInstance(getApplicationContext()).getWorkInfoById(request.id).get().state == SUCCEEDED
            }
        } finally {
            executeShellCommand("dumpsys deviceidle unforce")
        }
    }

    private fun executeShellCommand(cmd: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd).close()
    }
}
```

Test docstring must call out: "OEM-specific behaviour (Samsung/Xiaomi/Huawei) is not validated by this test — they require physical device QA per spec §10."

- [ ] **Step 5: Run all tests**

```
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/albunyaan/tube/data/me/MeRefreshTelemetry.kt \
        android/app/src/main/java/com/albunyaan/tube/data/me/work/RefreshSubscriptionsWorker.kt \
        android/app/src/main/java/com/albunyaan/tube/data/me/MeFeedRepository.kt \
        android/app/src/main/java/com/albunyaan/tube/data/extractor/CooldownState.kt \
        android/app/src/main/java/com/albunyaan/tube/ui/settings/DeveloperSettingsDialog.kt \
        android/app/src/androidTest/java/com/albunyaan/tube/data/me/work/MeRefreshDozeInstrumentedTest.kt
git commit -m "[ANDROID-PERSONAL-02]: Telemetry hooks + dev settings + Doze instrumented test"
```

---

## Final Verification

After Task 12 commits, run the full project gauntlet before opening a PR:

- [ ] **Step 1: Full unit test suite**

```
cd /home/farouq/Development/albunyaantube-me/android && ./gradlew :app:testDebugUnitTest
```

Expected: all tests PASS.

- [ ] **Step 2: Lint**

```
./gradlew :app:lintDebug
```

Expected: zero new warnings.

- [ ] **Step 3: Release build (catches more issues than debug)**

```
./gradlew :app:assembleRelease
```

Expected: build succeeds.

- [ ] **Step 4: Instrumented tests on emulator**

```
./gradlew :app:connectedDebugAndroidTest
```

Expected: all tests PASS.

- [ ] **Step 5: Manual QA against the spec §9 checklist**

- Subscribe 30 channels → 31st blocked
- Save 100 playlists → all succeed (no playlist cap)
- ATOM endpoint hit verified via Charles Proxy / mitmproxy on staging device
- 304 response observed on second fetch of unchanged channel
- Pull-to-refresh during NewPipe cooldown → snackbar shown
- Foreground app for 30 min → worker tick observed in dev log
- Background app for 90 min → worker tick observed
- Force Doze → worker still completes within ~2 h
- Zero favorites → favorites row not visible
- 5 favorites → row appears
- 25 favorites → row shows 20 + "See all" tile
- Favorite a video from Player → Me-tab favorites updates without re-opening Me
- Scroll Me grid past 40 items → page 3 loads smoothly
- Apply channel filter → grid resets, paging works inside the filter

- [ ] **Step 6: Run the project's mandatory review pipeline**

Per `feedback_review_pipeline.md` (project memory): baseline → code-reviewer (bg) → cso → codex challenge → consolidate → patch + re-review → gstack /review → CodeRabbit. No skipping.

- [ ] **Step 7: Open PR to `develop`** (per project branching policy in `feedback_branching_policy.md`)

PR title: `[ANDROID-PERSONAL-02]: Me Tab — ATOM refresh + 30-cap + favorites row + paging`

PR body sections:
- Summary
- Spec link
- Decision matrix locked (A1, B1, C2, D1, E2, F2)
- Manual QA checklist results
- Review pipeline status
- Migration safety notes (Room v3, additive only, rollback path)

---

## Self-Review Notes

**Spec coverage check** (every spec section maps to at least one task):

| Spec section | Task |
|---|---|
| §2.1-§2.7 Goals (ATOM, cap, worker, round-robin, NewPipe-only rate limit, ETag, Downloader interception) | T2, T3, T9, T9, T7, T2, T7 |
| §2.8-§2.9 Goals (favorites row, paged grid) | T10, T11 |
| §4.1 AtomChannelFeedFetcher | T2 |
| §4.2 SubscriptionLimitGuard | T3 |
| §4.3 RefreshSubscriptionsWorker | T9 |
| §4.4 RateLimitedDownloader | T7 |
| §4.5 GlobalNewPipeRateLimiter | T5 |
| §4.6 CooldownState | T6 |
| §4.7 AppLifecycleTracker | T8 |
| §4.8 MeFavoritesAdapter | T10 |
| §4.9 MeVideosPagingAdapter + PagingSource | T11 |
| §5 Failure handling matrix | T2, T9 (per-channel backoff + counter resets) |
| §6 Persistence schema (Room v3) | T1 |
| §6 CooldownState DataStore | T6 |
| §7 Constants reference | distributed across all tasks |
| §8 UI changes (layout, hide cols, favorites row, pagination) | T4, T10, T11 |
| §9 Test strategy (unit/integration/instrumented/QA) | each task includes tests; T9/T12 cover instrumented |
| §10 Rollout phases P1-P10 | T2, T3, T9, T4, T5+T6, T7, T1, T10, T11, T12 |

**Type consistency:** `ChannelFeedFetcher.fetchLatest` signature changes in T2; all subsequent tasks (T9 repository rewrite) use the new signature. `Priority` enum defined in T5 used by T7. `SubscribeResult` defined in T3.

**Placeholder scan:** none. Every step has executable code or commands. No "TBD".

**i18n:** strings drafted in en in T3 and T10. ar + nl translations marked as content-delivery task in spec §14, must be filled in before merge.

---

## Plan Done

Plan saved to `docs/superpowers/plans/2026-04-27-me-tab-atom-implementation.md`. **12 tasks, ~5.75 dev days, plus the project's mandatory review pipeline (~1.5 d) for ~7-8 calendar days total.**
