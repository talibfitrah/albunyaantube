package com.albunyaan.tube.data.account

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.disk.DiskCache
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.FavoriteVideo
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ANDROID-ACCT-DEL-01 — proves the local half of account deletion actually
 * erases data rather than merely being called. Room, the downloaded media
 * files and the per-install device id are the three stores that survive a
 * plain sign-out today.
 */
@RunWith(RobolectricTestRunner::class)
class LocalAccountDataWiperTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var imageLoader: ImageLoader
    private lateinit var wiper: LocalAccountDataWiper

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // A REAL disk cache at the same location DataModule uses
        // (DataModule.kt:409-414) so the assertions below are about bytes that
        // actually left the disk, not about a mock being called.
        imageLoader = ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "coil_image_cache"))
                    .build()
            }
            .build()
        wiper = LocalAccountDataWiper(context, db, imageLoader)
    }

    @After
    fun tearDown() = db.close()

    /**
     * Thumbnails of the previous owner's channels, playlists and videos are
     * personal data about them. Nothing else in the app clears them, so without
     * this the next person to sign in on the device browses the previous
     * owner's library artwork.
     */
    @Test
    fun `wipe clears the cached thumbnails`() = runTest {
        val disk = imageLoader.diskCache!!
        val editor = disk.openEditor("https://i.ytimg.com/vi/private/hq.jpg")!!
        disk.fileSystem.write(editor.data) { writeUtf8("previous owner's thumbnail bytes") }
        editor.commit()
        assertTrue("Precondition: something is actually cached", disk.size > 0L)

        wiper.wipe()

        assertEquals(0L, disk.size)
    }

    @Test
    fun `wipe empties room`() = runTest {
        val dao = db.favoriteVideoDao()
        dao.addFavorite(
            FavoriteVideo(
                videoId = "vid1",
                title = "t",
                channelName = "c",
                thumbnailUrl = null,
                durationSeconds = 1,
                user_id = "uid1",
            )
        )
        assertEquals(1, dao.getAll("uid1").size)

        wiper.wipe()

        assertTrue(dao.getAll("uid1").isEmpty())
    }

    @Test
    fun `wipe deletes downloaded files`() = runTest {
        val downloads = File(context.filesDir, "downloads")
        File(downloads, "metadata").mkdirs()
        val media = File(downloads, "abc.mp4").apply { writeText("payload") }
        val meta = File(downloads, "metadata/abc.meta").apply { writeText("title=x") }
        assertTrue(media.exists() && meta.exists())

        wiper.wipe()

        assertFalse(media.exists())
        assertFalse(meta.exists())
    }

    @Test
    fun `wipe leaves the downloads layout usable for the next account`() = runTest {
        File(File(context.filesDir, "downloads"), "metadata").mkdirs()

        wiper.wipe()

        assertTrue(File(context.filesDir, "downloads/metadata").isDirectory)
    }

    @Test
    fun `wipe rotates the per-install device id`() = runTest {
        val prefs = context.getSharedPreferences("device_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("device_id", "old-device-id").commit()

        wiper.wipe()

        assertNull(prefs.getString("device_id", null))
    }
}
