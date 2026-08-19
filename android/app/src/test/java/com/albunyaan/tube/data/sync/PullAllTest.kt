package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.sync.dto.*
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PullAllTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeSyncApi
    private lateinit var sm: SyncManager

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        api = FakeSyncApi()
        sm = SyncManager(api, db, db.subscribedChannelDao(), db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao(),
                         db.playlistVideoLinkDao())
    }
    @After fun tearDown() = db.close()

    @Test fun pullInsertsLiveRowsAndAdvancesCursor() = runTest {
        // Pre-R5 P0 the client computed cursor = max(items.updatedAt) locally.
        // R5 P0 switched to server-side cursor only — the server now returns
        // the cursor in SyncPageDto.nextCursor (mints it for every non-empty
        // page per SYNC-TAIL-01). Test updated to mirror the new contract:
        // server supplies nextCursor=100 on first page, then null+empty on
        // second to signal iterator exhausted. pullAll loops while ANY page
        // has nextCursor != null, so we must terminate explicitly with the
        // empty/null page or the do-while loop never exits.
        val sub = SubscriptionSyncDto("UC1", false, 100L, "u", "n", null, 0L)
        var call = 0
        api.pullResponse = {
            call++
            if (call == 1) Response.success(SyncResponseDto(
                SyncPageDto(listOf(sub), 100L),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null)))
            else Response.success(SyncResponseDto(
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null)))
        }

        sm.pullAll("uid")

        val rows = db.subscribedChannelDao().getAll("uid")
        assertEquals(1, rows.size)
        assertEquals(100L, rows[0].updated_at)
        assertEquals(100L, db.syncStateDao().cursorFor("uid", "subscriptions"))
    }

    @Test fun virtualTombstoneRemovesLocalRow() = runTest {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC2","u","n",null, user_id="uid"))
        val tomb = SubscriptionSyncDto("UC2", true, 200L, "", "", null, 0L)
        api.pullResponse = { Response.success(
            SyncResponseDto(
                SyncPageDto(listOf(tomb), null),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null))) }

        sm.pullAll("uid")

        assertEquals(0, db.subscribedChannelDao().count("uid"))   // count() excludes deleted=1
    }

    @Test fun paginationLoopsUntilNullCursor() = runTest {
        // SYNC-TAIL-01 contract: server mints nextCursor for every non-empty
        // page (partial tails included); only empty pages return null.
        // Three-stage simulation:
        //   call 1: page with p1, nextCursor=100  → client pulls again
        //   call 2: page with p2, nextCursor=200  → client pulls again
        //   call 3: empty page,  nextCursor=null  → client stops
        val p1 = SubscriptionSyncDto("UC1", false, 100L, "u", "n", null, 0L)
        val p2 = SubscriptionSyncDto("UC2", false, 200L, "u", "n", null, 0L)
        var call = 0
        api.pullResponse = {
            call++
            when (call) {
                1 -> Response.success(SyncResponseDto(
                    SyncPageDto(listOf(p1), 100L),
                    SyncPageDto(emptyList(), null),
                    SyncPageDto(emptyList(), null)))
                2 -> Response.success(SyncResponseDto(
                    SyncPageDto(listOf(p2), 200L),
                    SyncPageDto(emptyList(), null),
                    SyncPageDto(emptyList(), null)))
                else -> Response.success(SyncResponseDto(
                    SyncPageDto(emptyList(), null),
                    SyncPageDto(emptyList(), null),
                    SyncPageDto(emptyList(), null)))
            }
        }

        sm.pullAll("uid")

        assertEquals(2, db.subscribedChannelDao().count("uid"))
        assertEquals(200L, db.syncStateDao().cursorFor("uid", "subscriptions"))
    }

    /**
     * A server that keeps returning the same (nextCursor, nextCursorId) cannot make
     * progress, whatever the reason — e.g. a stored updatedAt carrying sub-millisecond
     * precision the millisecond cursor cannot express, so startAfter() never passes the
     * row. Pre-fix the do-while only asked "did any page mint a cursor", so this span
     * an unthrottled request loop (~3/s observed on a real device) that starved the
     * shared OkHttp client and left the app stuck on the splash screen.
     */
    @Test fun stopsWhenTheServerCursorDoesNotAdvance() = runTest {
        val stuck = SubscriptionSyncDto("UC-STUCK", false, 1787160466489L, "u", "n", null, 0L)
        var calls = 0
        api.pullResponse = {
            calls++
            // Escape hatch so a regression fails the assertion instead of hanging forever.
            if (calls > 20) Response.success(SyncResponseDto(
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null)))
            else Response.success(SyncResponseDto(
                SyncPageDto(listOf(stuck), 1787160466489L, "UC-STUCK"),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null)))
        }

        sm.pullAll("uid")

        // Two calls, not one: the first page genuinely advances the cursor off its
        // initial 0, and the second is the first one that repeats it. Anything above
        // two means the loop kept spinning on a cursor that never moves.
        assertEquals("must stop at the first non-advancing page", 2, calls)
    }
}
