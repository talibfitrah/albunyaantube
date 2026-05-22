package com.albunyaan.tube.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AccountBindingEntity
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SubscribedChannel
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
 * Plan D T22 — verifies SyncManager.bind() decision matrix.
 *
 * Uses a hand-rolled FakeSyncApi (the project uses Mockito for synchronous
 * mocks; coroutine mocks are easier with a fake than mockito-kotlin's
 * whenever-on-suspend support).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SyncManagerBindTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeSyncApi
    private lateinit var sm: SyncManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = FakeSyncApi()
        sm = SyncManager(
            api,
            db,
            db.subscribedChannelDao(),
            db.savedPlaylistDao(),
            db.favoriteVideoDao(),
            db.syncStateDao(),
            db.accountBindingDao(),
            db.playlistVideoLinkDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun nullBinding_firstSignIn_runsMerge() = runTest {
        sm.bind("uid-A")

        val b = db.accountBindingDao().get()!!
        assertEquals("uid-A", b.user_id)
        assertTrue(b.initial_merge_done)
    }

    @Test
    fun sameUid_resumes_withoutWipe() = runTest {
        db.accountBindingDao().upsert(AccountBindingEntity("uid-A", 0L, true))
        db.subscribedChannelDao().upsert(SubscribedChannel("UC1", "u", "n", null, user_id = "uid-A"))

        sm.bind("uid-A")

        assertEquals(1, db.subscribedChannelDao().count("uid-A"))
    }

    @Test
    fun differentUid_wipesOldAndReMerges() = runTest {
        db.accountBindingDao().upsert(AccountBindingEntity("uid-A", 0L, true))
        db.subscribedChannelDao().upsert(SubscribedChannel("UC_OLD", "u", "n", null, user_id = "uid-A"))

        sm.bind("uid-B")

        assertEquals(0, db.subscribedChannelDao().count("uid-A"))
        val b = db.accountBindingDao().get()!!
        assertEquals("uid-B", b.user_id)
    }

    @Test
    fun sameUid_initialMergeNotDone_reEntersMerge() = runTest {
        db.accountBindingDao().upsert(AccountBindingEntity("uid-A", 0L, false))
        // anon row left behind from interrupted merge
        db.subscribedChannelDao().upsert(SubscribedChannel("UC_ANON", "u", "n", null, user_id = ""))

        sm.bind("uid-A")

        // anon row tagged, merge marked done
        assertEquals(1, db.subscribedChannelDao().count("uid-A"))
        val b = db.accountBindingDao().get()!!
        assertTrue(b.initial_merge_done)
    }
}
