package com.albunyaan.tube.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * In-memory Room DB tests for [FollowedChannelDao].
 *
 * Uses Robolectric with SDK 33 to exercise real Room behaviour in JVM unit tests.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class FollowedChannelDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: FollowedChannelDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.followedChannelDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun toggleFollow_insertsAndRemoves() = runBlocking {
        val channel = FollowedChannel("UC123", "Shneako", "https://x/avatar.jpg")
        assertTrue(dao.toggleFollow(channel))
        assertTrue(dao.isFollowedOnce("UC123"))
        assertFalse(dao.toggleFollow(channel))
        assertFalse(dao.isFollowedOnce("UC123"))
    }

    @Test
    fun isFollowed_flow_emitsUpdates() = runBlocking {
        val channel = FollowedChannel("UC1", "T", null)
        dao.addFollow(channel)
        assertTrue(dao.isFollowed("UC1").first())
    }
}
