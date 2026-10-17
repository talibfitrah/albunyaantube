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
import kotlinx.coroutines.launch
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
import org.robolectric.shadows.ShadowLog
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

    // Cubic SyncManager.kt:607: offline, every row waits out the 15 s connect / 20 s read timeouts
    // in turn, all inside syncMutex, so pullAll, bind and unbind wait minutes. No connection ends
    // the drain; the backoff retries it.
    @Test fun aNetworkFailureEndsTheDrainAndRetriesOnTheBackoff() = runTest {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC5", "u", "n", null, user_id = "uid", dirty = true))
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        val subPuts = java.util.concurrent.atomic.AtomicInteger()
        val favouritePuts = java.util.concurrent.atomic.AtomicInteger()
        api.putSubResponse = { _, _ -> subPuts.incrementAndGet(); throw java.net.SocketTimeoutException("connect timed out") }
        api.putFavoriteResponse = { _, _ -> favouritePuts.incrementAndGet(); throw java.net.SocketTimeoutException("connect timed out") }

        sm.pushDirty("uid")
        val firstPass = favouritePuts.get()
        // The backoff's first wait is 0.5-1 s (SyncBackoff), on SyncManager's own IO scope.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val end = System.currentTimeMillis() + 5_000
            while (subPuts.get() < 2 && System.currentTimeMillis() < end) Thread.sleep(20)
        }
        sm.unbind()

        assertEquals("the drain stops at the first network failure", 0, firstPass)
        assertTrue("the backoff retries the drain", subPuts.get() >= 2)
        assertTrue(db.favoriteVideoDao().selectDirty("uid").any { it.videoId == "lec1" })
    }

    // Stage 5 F5: a bind for account A stuck in a slow pull held the lock while A signed out and B
    // signed in; A's dirty rows then went out under B's Bearer (FirebaseAuthInterceptor signs with
    // whoever is signed in when the request leaves), so A's favourites landed in B's account.
    @Test fun aSignOutDuringASlowBindStopsTheOldAccountsRequests() = runTest {
        db.accountBindingDao().upsert(AccountBindingEntity("A", 0L, true))
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "A", dirty = true))
        val pullStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pullGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pushes = java.util.concurrent.atomic.AtomicInteger()
        api.putFavoriteResponse = { id, req ->
            pushes.incrementAndGet()
            Response.success(FavoriteSyncDto(id, false, 1L, req.title, req.channelName, req.thumbnailUrl, req.durationSeconds, req.addedAt))
        }
        val slowPull = object : SyncApi by api {
            override suspend fun pull(
                subs: Long, playlists: Long, favorites: Long,
                subsId: String?, playlistsId: String?, favoritesId: String?,
            ): Response<SyncResponseDto> {
                pullStarted.complete(Unit)
                pullGate.await()
                return api.pull(subs, playlists, favorites, subsId, playlistsId, favoritesId)
            }
        }
        sm = SyncManager(slowPull, db, db.subscribedChannelDao(), db.savedPlaylistDao(),
                         db.favoriteVideoDao(), db.syncStateDao(), db.accountBindingDao(),
                         db.playlistVideoLinkDao())

        val bind = launch(kotlinx.coroutines.Dispatchers.IO) { sm.bind("A") }
        pullStarted.await()
        // A signs out (SyncModule calls unbind on SignedOut); B signs in. unbind waits for the lock.
        val unbind = launch(kotlinx.coroutines.Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { sm.unbind() }
        pullGate.complete(Unit)
        bind.join()
        unbind.join()

        assertEquals("A's rows must not go out once A has signed out", 0, pushes.get())
    }

    /** Row one fails with [failure]; the drain must go on and push row two (review m4, m6). */
    private suspend fun assertDrainGoesOnAfter(failure: () -> Response<SubscriptionSyncDto>) {
        db.subscribedChannelDao().upsert(SubscribedChannel("UC6", "u", "n", null, user_id = "uid", dirty = true))
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec2", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        api.putSubResponse = { _, _ -> failure() }
        api.putFavoriteResponse = { id, req ->
            Response.success(FavoriteSyncDto(id, false, 999L, req.title, req.channelName, req.thumbnailUrl, req.durationSeconds, req.addedAt))
        }

        sm.pushDirty("uid")
        sm.unbind()

        assertTrue("the failed row stays dirty", db.subscribedChannelDao().selectDirty("uid").any { it.channelId == "UC6" })
        assertFalse("the rows after it still push", db.favoriteVideoDao().selectDirty("uid").any { it.videoId == "lec2" })
    }

    // Round 4 (review m4): Moshi reads an empty or truncated 2xx body as EOFException, an
    // IOException. It is one bad row, not "offline": the drain must not stop at it every cycle.
    @Test fun anEmptyOrTruncatedBodyFailsOnlyThatRow() = runTest {
        assertDrainGoesOnAfter { throw java.io.EOFException("End of input") }
    }

    @Test fun aJsonEncodingExceptionFailsOnlyThatRow() = runTest {
        assertDrainGoesOnAfter { throw com.squareup.moshi.JsonEncodingException("Use JsonReader.setLenient(true)") }
    }

    @Test fun aFiveXxFailsOnlyThatRow() = runTest {
        assertDrainGoesOnAfter { Response.error(503, "".toResponseBody("application/json".toMediaType())) }
    }

    @Test fun a429FailsOnlyThatRow() = runTest {
        assertDrainGoesOnAfter { Response.error(429, "".toResponseBody("application/json".toMediaType())) }
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

    // fix 3: the class name alone hid where a failure came from; the log carries the throwable.
    @Test fun pushFailureLogsTheThrowable() = runTest {
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo("lec1", "Lecture", "Channel", null, 600, user_id = "uid", dirty = true))
        val failure = JsonDataException("Expected a long but was STRING")
        api.putFavoriteResponse = { _, _ -> throw failure }

        sm.pushDirty("uid")
        sm.unbind()

        assertTrue("the push failure log carries its throwable",
            ShadowLog.getLogsForTag("SyncManager").any { it.throwable === failure })
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
