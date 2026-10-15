package com.albunyaan.tube.data.sync

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AccountBindingEntity
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.local.SubscribedChannelDao
import com.albunyaan.tube.data.sync.dto.*
import com.squareup.moshi.JsonDataException
import kotlinx.coroutines.CancellationException
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
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao(),
                         db.playlistVideoLinkDao())
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

    // Stage 5 F1 (P1): a transport failure (offline) used to escape push() as
    // an IOException into the app scope, which has no handler, so the process
    // died and the committed dirty row crash-looped every offline launch.
    @Test fun transportFailureIsTransientAndLeavesRowDirty() = runTest {
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        api.putFavoriteResponse = { _, _ -> throw java.net.UnknownHostException("offline") }

        sm.pushDirty("uid")
        sm.unbind() // cancel the backoff retry so it can't outlive the db

        assertTrue("a transport failure must leave the row dirty for retry",
            db.favoriteVideoDao().selectDirty("uid").any { it.videoId == "lec1" })
    }

    @Test fun offlineBindWithDirtyRowDoesNotThrow() = runTest {
        db.accountBindingDao().upsert(AccountBindingEntity("uid", 0L, true))
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        api.pullResponse = { throw java.net.UnknownHostException("offline") }
        api.putFavoriteResponse = { _, _ -> throw java.net.UnknownHostException("offline") }

        sm.bind("uid")
        sm.unbind()

        assertTrue(db.favoriteVideoDao().selectDirty("uid").any { it.videoId == "lec1" })
    }

    // S7-N4: a 2xx body of the wrong shape makes Moshi throw JsonDataException, a
    // RuntimeException, out of api.*. It used to escape into a handler-less scope.
    @Test fun malformedPushBodyIsRetriedLikeTransportAndTheDrainGoesOn() = runTest {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC4", "u", "n", null, user_id = "uid", dirty = true))
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        api.putSubResponse = { _, _ -> throw JsonDataException("Expected a long but was STRING") }
        api.putFavoriteResponse = { id, req ->
            Response.success(FavoriteSyncDto(id, false, 999L, req.title, req.channelName, req.thumbnailUrl, req.durationSeconds, req.addedAt))
        }

        sm.pushDirty("uid")
        sm.unbind()

        assertTrue("the malformed row stays dirty for the backoff retry",
            db.subscribedChannelDao().selectDirty("uid").any { it.channelId == "UC4" })
        assertFalse("the rows after it still push",
            db.favoriteVideoDao().selectDirty("uid").any { it.videoId == "lec1" })
    }

    @Test fun malformedPullBodyNeverThrowsFromBindOrPull() = runTest {
        db.accountBindingDao().upsert(AccountBindingEntity("uid", 0L, true))
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        api.pullResponse = { throw JsonDataException("Expected BEGIN_OBJECT but was STRING") }

        sm.bind("uid")
        sm.pullAll("uid")
        sm.unbind()

        assertTrue(db.favoriteVideoDao().selectDirty("uid").any { it.videoId == "lec1" })
    }

    @Test fun databaseFailureNeverThrowsFromPush() = runTest {
        val failingSubs = object : SubscribedChannelDao by db.subscribedChannelDao() {
            override suspend fun selectDirty(uid: String): List<SubscribedChannel> =
                throw SQLiteException("disk I/O error")
        }
        sm = SyncManager(api, db, failingSubs, db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao(),
                         db.playlistVideoLinkDao())

        sm.pushDirty("uid")
    }

    @Test fun cancellationStillPropagates() = runTest {
        db.accountBindingDao().upsert(AccountBindingEntity("uid", 0L, true))
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        api.pullResponse = { throw CancellationException("pull cancelled") }
        api.putFavoriteResponse = { _, _ -> throw CancellationException("push cancelled") }

        assertEquals("pull cancelled", runCatching { sm.bind("uid") }.exceptionOrNull()?.message)
        assertEquals("push cancelled", runCatching { sm.pushDirty("uid") }.exceptionOrNull()?.message)
    }
}
