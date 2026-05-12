package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.sync.dto.*
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
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
class PushDirtyTest {

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

    @Test fun successfulPutClearsDirtyAndSetsUpdatedAt() = runTest {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC1", "u", "n", null, user_id = "uid", dirty = true))
        api.putSubResponse = { id, req ->
            Response.success(SubscriptionSyncDto(id, false, 999L, req.channelUrl, req.name, req.avatarUrl, req.subscribedAt))
        }

        sm.pushDirty("uid")

        val r = db.subscribedChannelDao().getById("uid", "UC1")!!
        assertFalse(r.dirty)
        assertEquals(999L, r.updated_at)
    }

    @Test fun deleteOn404TreatedAsSuccess() = runTest {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC2", "u", "n", null, user_id = "uid", deleted = true, dirty = true))
        api.deleteSubResponse = { _ ->
            Response.error(404, "".toResponseBody("application/json".toMediaType()))
        }

        sm.pushDirty("uid")

        // Verify dirty has been cleared (push completed despite 404).
        val dirtyRows = db.subscribedChannelDao().selectDirty("uid")
        assertTrue("404 on tombstone DELETE must clear dirty (idempotent)",
            dirtyRows.none { it.channelId == "UC2" })
    }

    @Test fun fiveXxBreaksLoopWithoutClearingDirty() = runTest {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC3", "u", "n", null, user_id = "uid", dirty = true))
        api.putSubResponse = { _, _ ->
            Response.error(503, "".toResponseBody("application/json".toMediaType()))
        }

        sm.pushDirty("uid")

        val r = db.subscribedChannelDao().getById("uid", "UC3")!!
        assertTrue("5xx must leave row dirty for retry", r.dirty)
    }
}
