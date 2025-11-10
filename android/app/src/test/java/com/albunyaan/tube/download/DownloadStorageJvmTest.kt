package com.albunyaan.tube.download

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class DownloadStorageJvmTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun context(root: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = root
    }

    @Test
    fun ensureSpace_doesNotPruneFiles_noArtificialQuota() {
        // No longer tests quota enforcement since there is no artificial quota
        // Device storage is the natural limit; Android OS handles low storage warnings
        val root = tmp.newFolder("files")
        val storage = DownloadStorage(context(root))

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
