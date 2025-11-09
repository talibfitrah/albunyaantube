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
    fun ensureSpace_prunesOldestFileWhenOverQuota() {
        val root = tmp.newFolder("files")
        val storage = DownloadStorage(context(root), 1024) // 1 KB

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

        val first = storage.targetFile("first", true)
        val second = storage.targetFile("second", true)

        assertFalse(first.exists())
        assertTrue(third.exists())

        val totalSize = sequenceOf(second, third)
            .filter { it.exists() }
            .map { it.length() }
            .sum()
        assertTrue(totalSize <= 1024)
    }
}

