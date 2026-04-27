package com.albunyaan.tube.data.local

import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T11: paging-source unit tests for [ChannelVideoCacheDao.pagingForChannels].
 * Mirrors the Robolectric + in-memory Room boilerplate of
 * [ChannelVideoCacheDaoTest] so the two coexist without setup drift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ChannelVideoCacheDaoPagingTest {

    private lateinit var db: AppDatabase
    private lateinit var cache: ChannelVideoCacheDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        cache = db.channelVideoCacheDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        id: String,
        channel: String,
        uploadedAt: Long?,
        isShort: Boolean = false,
    ) = ChannelVideoCache(
        videoId = id,
        channelId = channel,
        channelName = "Ch $channel",
        title = "t-$id",
        thumbnailUrl = null,
        durationSeconds = null,
        viewCount = null,
        uploadedAt = uploadedAt,
        isShort = isShort,
        fetchedAt = 0L,
    )

    private suspend fun loadFirstPage(
        channelIds: List<String>,
        filterChannelId: String?,
        loadSize: Int = 20,
    ): PagingSource.LoadResult.Page<Int, ChannelVideoCache> {
        val source = cache.pagingForChannels(channelIds, filterChannelId)
        val result = source.load(
            PagingSource.LoadParams.Refresh(
                key = null,
                loadSize = loadSize,
                placeholdersEnabled = false,
            )
        )
        assertTrue("expected Page, got $result", result is PagingSource.LoadResult.Page)
        return result as PagingSource.LoadResult.Page<Int, ChannelVideoCache>
    }

    @Test
    fun `paging returns first page correctly`() = runTest {
        // Seed 50 rows for UCa with strictly decreasing uploadedAt so the
        // ORDER BY produces a deterministic ranking.
        val rows = (0 until 50).map { i ->
            row("v$i", "UCa", uploadedAt = 100_000L - i.toLong())
        }
        cache.upsertAll(rows)

        val page = loadFirstPage(
            channelIds = listOf("UCa"),
            filterChannelId = null,
            loadSize = 20,
        )

        assertEquals(20, page.data.size)
        // Highest uploadedAt first.
        assertEquals("v0", page.data.first().videoId)
        assertEquals("v19", page.data.last().videoId)
    }

    @Test
    fun `paging filter returns only matching channel`() = runTest {
        val rows = buildList {
            (0 until 10).forEach { i ->
                add(row("a$i", "UCa", uploadedAt = 1_000L + i.toLong()))
            }
            (0 until 10).forEach { i ->
                add(row("b$i", "UCb", uploadedAt = 2_000L + i.toLong()))
            }
        }
        cache.upsertAll(rows)

        // Subscribed to both channels but filter pin to UCa only.
        val page = loadFirstPage(
            channelIds = listOf("UCa", "UCb"),
            filterChannelId = "UCa",
            loadSize = 20,
        )

        assertEquals(10, page.data.size)
        assertTrue(
            "every row should belong to UCa",
            page.data.all { it.channelId == "UCa" },
        )
    }

    /**
     * ANDROID-PERSONAL-02 round 4: the upload-date cutoff was removed so
     * users see their full subscription history (matches YouTube's
     * subscription feed). What remains: ordering must be newest-first
     * across the whole window.
     */
    @Test
    fun `paging orders newest-first across full history`() = runTest {
        // Seed two age groups: "old" with uploadedAt 1_000..1_009, "new"
        // with uploadedAt 5_000..5_009. With no cutoff both groups are
        // visible; the ORDER BY uploadedAt DESC must rank "new9" first
        // and "old0" last.
        val rows = buildList {
            (0 until 10).forEach { i ->
                add(row("old$i", "UCa", uploadedAt = 1_000L + i.toLong()))
            }
            (0 until 10).forEach { i ->
                add(row("new$i", "UCa", uploadedAt = 5_000L + i.toLong()))
            }
        }
        cache.upsertAll(rows)

        val page = loadFirstPage(
            channelIds = listOf("UCa"),
            filterChannelId = null,
            loadSize = 20,
        )

        assertEquals(20, page.data.size)
        assertEquals("new9", page.data.first().videoId)
        assertEquals("old0", page.data.last().videoId)
    }

    @Test
    fun `paging excludes rows with null uploadedAt`() = runTest {
        cache.upsertAll(
            listOf(
                row("hasDate", "UCa", uploadedAt = 1_000L),
                row("noDate", "UCa", uploadedAt = null),
            )
        )

        val page = loadFirstPage(
            channelIds = listOf("UCa"),
            filterChannelId = null,
            loadSize = 20,
        )

        assertEquals(1, page.data.size)
        assertEquals("hasDate", page.data.first().videoId)
    }

    /**
     * Regression: the Me tab renders Shorts in a dedicated horizontal row
     * above the long-form videos grid. The DAO's paging query must exclude
     * `isShort = 1` rows or every Short would render twice (once in the
     * Shorts row, once in the grid below). Stage-2 holistic review catch.
     */
    @Test
    fun `paging excludes shorts rows`() = runTest {
        cache.upsertAll(
            listOf(
                row("longA", "UCa", uploadedAt = 3_000L, isShort = false),
                row("shortB", "UCa", uploadedAt = 2_000L, isShort = true),
                row("longC", "UCa", uploadedAt = 1_000L, isShort = false),
            )
        )

        val page = loadFirstPage(
            channelIds = listOf("UCa"),
            filterChannelId = null,
            loadSize = 20,
        )

        assertEquals(2, page.data.size)
        assertEquals(listOf("longA", "longC"), page.data.map { it.videoId })
        assertTrue(page.data.none { it.isShort })
    }
}
