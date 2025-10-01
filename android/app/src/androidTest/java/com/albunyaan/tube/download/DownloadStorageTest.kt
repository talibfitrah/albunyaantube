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
        storage = DownloadStorage(context, 1024) // 1 KB quota
    }

    @Test
    fun ensureSpace_prunesOldestFilesWhenOverQuota() {
        val firstTemp = storage.createTempFile("first")
        firstTemp.outputStream().use { it.write(ByteArray(600)) }
        storage.commit("first", true, firstTemp)

        val secondTemp = storage.createTempFile("second")
        secondTemp.outputStream().use { it.write(ByteArray(400)) }
        storage.commit("second", true, secondTemp)

        val thirdTemp = storage.createTempFile("third")
        thirdTemp.outputStream().use { it.write(ByteArray(800)) }

        storage.ensureSpace(800)

        val third = storage.commit("third", true, thirdTemp)

        assertFalse(storage.targetFile("first", true).exists())
        assertTrue(storage.targetFile("second", true).exists())
        assertTrue(third.exists())
    }
}
