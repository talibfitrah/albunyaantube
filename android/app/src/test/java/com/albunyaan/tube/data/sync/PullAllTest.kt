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
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao())
    }
    @After fun tearDown() = db.close()

    @Test fun pullInsertsLiveRowsAndAdvancesCursor() = runTest {
        val sub = SubscriptionSyncDto("UC1", false, 100L, "u", "n", null, 0L)
        api.pullResponse = { Response.success(
            SyncResponseDto(
                SyncPageDto(listOf(sub), null),
                SyncPageDto(emptyList(), null),
                SyncPageDto(emptyList(), null))) }

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
        val p1 = SubscriptionSyncDto("UC1", false, 100L, "u", "n", null, 0L)
        val p2 = SubscriptionSyncDto("UC2", false, 200L, "u", "n", null, 0L)
        // First call: page with nextCursor=100 (means more pages); second call: nextCursor=null.
        var call = 0
        api.pullResponse = {
            call++
            if (call == 1) Response.success(
                SyncResponseDto(SyncPageDto(listOf(p1), 100L), SyncPageDto(emptyList(), null), SyncPageDto(emptyList(), null)))
            else Response.success(
                SyncResponseDto(SyncPageDto(listOf(p2), null), SyncPageDto(emptyList(), null), SyncPageDto(emptyList(), null)))
        }

        sm.pullAll("uid")

        assertEquals(2, db.subscribedChannelDao().count("uid"))
        assertEquals(200L, db.syncStateDao().cursorFor("uid", "subscriptions"))
    }
}
