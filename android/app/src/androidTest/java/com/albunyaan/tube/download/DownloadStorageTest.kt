package com.albunyaan.tube.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadStorageTest {

    private lateinit var storage: DownloadStorage
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = DownloadStorage(context)
    }

    @Test
    fun ensureSpace_doesNotPruneFiles_noArtificialQuota() {
        // No longer tests quota enforcement since there is no artificial quota
        // Device storage is the natural limit; Android OS handles low storage warnings
        val firstTemp = storage.createTempFile("first")
        firstTemp.outputStream().use { it.write(ByteArray(600)) }
        storage.commit("first", true, firstTemp)

        val secondTemp = storage.createTempFile("second")
        secondTemp.outputStream().use { it.write(ByteArray(400)) }
        storage.commit("second", true, secondTemp)

        val thirdTemp = storage.createTempFile("third")
        thirdTemp.outputStream().use { it.write(ByteArray(800)) }

        storage.ensureSpace(800) // No-op in current implementation

        val third = storage.commit("third", true, thirdTemp)

        // All files should exist (no pruning)
        val first = storage.targetFile("first", true)
        val second = storage.targetFile("second", true)

        assertTrue("First file should exist (no quota pruning)", first.exists())
        assertTrue("Second file should exist", second.exists())
        assertTrue("Third file should exist", third.exists())
    }
}
