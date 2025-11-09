package com.albunyaan.tube.download

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class DownloadStorage(
    private val context: Context,
    private val quotaBytes: Long
) {

    private val rootDir: File = File(context.filesDir, "downloads").apply { mkdirs() }
    private val currentSize = AtomicLong(calculateCommittedSize(rootDir))

    fun targetFile(downloadId: String, audioOnly: Boolean): File {
        val suffix = if (audioOnly) "m4a" else "mp4"
        return File(rootDir, "$downloadId.$suffix")
    }

    fun ensureSpace(requiredBytes: Long) {
        if (requiredBytes <= 0) return
        val committedSize = calculateCommittedSize(rootDir)
        currentSize.set(committedSize)
        if (committedSize + requiredBytes <= quotaBytes) return
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
        target.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
        val tempLength = tempFile.length()
        val previousSize = if (target.exists()) target.length() else 0L
        var removedPrevious = false
        if (target.exists() && target.delete()) {
            currentSize.addAndGet(-previousSize)
            removedPrevious = true
        }

        val moved = tempFile.renameTo(target)
        val finalFile = when {
            moved || (target.exists() && !tempFile.exists() && target.length() == tempLength) -> target
            target.exists() && !tempFile.exists() -> target
            !tempFile.exists() -> throw IllegalStateException("Temporary download file missing before commit for $downloadId")
            else -> {
                runCatching {
                    tempFile.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }.getOrElse { error ->
                    // Attempt to clean up partial file before rethrowing
                    target.delete()
                    throw IllegalStateException("Unable to persist download file", error)
                }
                if (!tempFile.delete()) {
                    tempFile.deleteOnExit()
                }
                if (!removedPrevious && previousSize > 0) {
                    currentSize.addAndGet(-previousSize)
                }
                target
            }
        }

        if (!finalFile.exists()) {
            throw IllegalStateException("Unable to locate committed download file for $downloadId")
        }

        finalFile.setLastModified(System.currentTimeMillis())
        val addedBytes = max(finalFile.length(), tempLength)
        currentSize.addAndGet(addedBytes)
        return finalFile
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

    fun metadataFor(downloadId: String, audioOnly: Boolean): DownloadFileMetadata? {
        val file = targetFile(downloadId, audioOnly)
        if (!file.exists()) return null
        return DownloadFileMetadata(
            sizeBytes = file.length(),
            completedAtMillis = file.lastModified(),
            mimeType = if (audioOnly) AUDIO_MIME else VIDEO_MIME
        )
    }

    fun listAllDownloads(): Map<String, java.io.File> {
        if (!rootDir.exists()) return emptyMap()
        return rootDir.listFiles()
            ?.filterNot { it.isDirectory || it.name.endsWith(TEMP_SUFFIX) }
            ?.associate { file ->
                // Extract downloadId from filename (e.g., "videoId_timestamp.mp4" -> "videoId_timestamp")
                val downloadId = file.nameWithoutExtension
                downloadId to file
            }
            ?: emptyMap()
    }

    private fun prune(requiredBytes: Long) {
        val files = rootDir.listFiles()
            ?.filterNot { it.isDirectory || it.name.endsWith(TEMP_SUFFIX) }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (file in files) {
            if (currentSize.get() + requiredBytes <= quotaBytes) break
            val size = calculateEntrySize(file)
            if (deleteFileSafely(file)) {
                currentSize.addAndGet(-size)
            }
        }
        currentSize.set(calculateCommittedSize(rootDir))
    }

    private fun calculateCommittedSize(dir: File): Long {
        if (!dir.exists()) return 0
        return dir.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(TEMP_SUFFIX) }
            .map { it.length() }
            .sum()
    }

    private fun calculateEntrySize(file: File): Long {
        return when {
            !file.exists() -> 0
            file.isFile -> file.length()
            file.isDirectory -> file.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
            else -> 0
        }
    }

    private fun deleteFileSafely(file: File): Boolean {
        if (!file.exists()) return true
        if (file.isFile) {
            if (file.delete()) return true
            file.setWritable(true)
            return file.delete()
        }
        if (file.isDirectory) {
            var allChildrenDeleted = true
            file.listFiles()?.forEach { child ->
                if (!deleteFileSafely(child)) {
                    allChildrenDeleted = false
                }
            }
            if (!allChildrenDeleted) return false
            if (file.delete()) return true
            file.setWritable(true)
            return file.delete()
        }
        return false
    }

    private companion object {
        private const val AUDIO_MIME = "audio/mp4"
        private const val VIDEO_MIME = "video/mp4"
        private const val TEMP_SUFFIX = ".tmp"
    }
}

