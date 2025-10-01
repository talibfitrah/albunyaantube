package com.albunyaan.tube.download

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class DownloadStorage(
    private val context: Context,
    private val quotaBytes: Long
) {

    private val rootDir: File = File(context.filesDir, "downloads").apply { mkdirs() }
    private val currentSize = AtomicLong(calculateDirectorySize(rootDir))

    fun targetFile(downloadId: String, audioOnly: Boolean): File {
        val suffix = if (audioOnly) "m4a" else "mp4"
        return File(rootDir, "$downloadId.$suffix")
    }

    fun ensureSpace(requiredBytes: Long) {
        if (requiredBytes <= 0) return
        if (currentSize.get() + requiredBytes <= quotaBytes) return
        prune(requiredBytes)
    }

    fun createTempFile(downloadId: String): File {
        val temp = File(rootDir, "$downloadId.tmp")
        if (temp.exists()) temp.delete()
        temp.parentFile?.mkdirs()
        return temp
    }

    fun commit(downloadId: String, audioOnly: Boolean, tempFile: File): File {
        val target = targetFile(downloadId, audioOnly)
        if (target.exists()) {
            val previousSize = target.length()
            if (target.delete()) {
                currentSize.addAndGet(-previousSize)
            }
        }
        if (!tempFile.renameTo(target)) {
            throw IllegalStateException("Unable to rename temp file to target")
        }
        currentSize.addAndGet(target.length())
        return target
    }

    fun discardTemp(tempFile: File) {
        if (tempFile.exists()) {
            tempFile.delete()
        }
    }

    fun delete(downloadId: String, audioOnly: Boolean) {
        val file = targetFile(downloadId, audioOnly)
        if (file.exists()) {
            val size = file.length()
            if (file.delete()) {
                currentSize.addAndGet(-size)
            }
        }
    }

    private fun prune(requiredBytes: Long) {
        val files = rootDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        for (file in files) {
            if (currentSize.get() + requiredBytes <= quotaBytes) break
            val size = file.length()
            if (file.delete()) {
                currentSize.addAndGet(-size)
            }
        }
    }

    private fun calculateDirectorySize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }
}
