package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.sync.dto.SubscriptionSyncDto
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RaceTests {

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

    @Test fun subscribeThenUnsubscribeBeforePushPushesOnlyDelete() = runTest {
        // user subscribes then unsubscribes before push fires:
        // - subscribe: insert row with dirty=1
        // - unsubscribe: softDelete sets deleted=1, dirty=1
        db.subscribedChannelDao().upsert(SubscribedChannel("UC1","u","n",null, user_id="uid", dirty=true))
        db.subscribedChannelDao().softDelete("uid", "UC1")   // deleted=1, dirty=1

        var deleteCalls = 0
        var putCalls = 0
        api.deleteSubResponse = { id ->
            deleteCalls++
            Response.success(SubscriptionSyncDto(id, true, 100L, "", "", null, 0L))
        }
        api.putSubResponse = { id, req ->
            putCalls++
            Response.success(SubscriptionSyncDto(id, false, 100L, req.channelUrl, req.name, req.avatarUrl, req.subscribedAt))
        }

        sm.pushDirty("uid")

        assertEquals("Only DELETE should be pushed since row state is deleted=1", 1, deleteCalls)
        assertEquals("PUT must not be pushed", 0, putCalls)
    }
}
