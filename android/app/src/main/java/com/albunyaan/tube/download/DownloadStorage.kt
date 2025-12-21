package com.albunyaan.tube.download

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Download storage manager using app-private internal storage.
 *
 * **Storage Strategy Decision:**
 * - Uses app-private storage (`context.filesDir/downloads`) instead of public Downloads folder
 * - Benefits: No WRITE_EXTERNAL_STORAGE permission needed, automatic cleanup on uninstall,
 *   isolated from other apps, works on all Android versions without scoped storage issues
 * - Trade-offs: Files not visible in system file managers or media library, requires in-app
 *   management (delete button, expiry cleanup)
 *
 * **File Lifecycle:**
 * - 30-day automatic expiry via [DownloadExpiryPolicy] and [DownloadExpiryWorker]
 * - Manual delete via in-app Delete button (calls [delete])
 * - Completion timestamp stored in metadata files as source of truth for expiry
 *
 * **Format:**
 * - Video: `{downloadId}.mp4`
 * - Audio: `{downloadId}.m4a`
 * - Temp: `{downloadId}.tmp` (deleted on completion or failure)
 * - Metadata: `metadata/{downloadId}.meta` (stores completedAtMillis)
 */
class DownloadStorage(
    private val context: Context
) {

    private val rootDir: File = File(context.filesDir, "downloads").apply { mkdirs() }
    private val metadataDir: File = File(rootDir, "metadata").apply { mkdirs() }
    private val currentSize = AtomicLong(calculateCommittedSize(rootDir))

    fun targetFile(downloadId: String, audioOnly: Boolean): File {
        val suffix = if (audioOnly) "m4a" else "mp4"
        return File(rootDir, "$downloadId.$suffix")
    }

    fun ensureSpace(requiredBytes: Long) {
        if (requiredBytes <= 0) return
        val committedSize = calculateCommittedSize(rootDir)
        currentSize.set(committedSize)
        // No artificial quota - device storage is the natural limit
        // Android OS will handle low storage warnings
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

        val completedAt = System.currentTimeMillis()
        finalFile.setLastModified(completedAt)

        // Persist completion timestamp in metadata file (source of truth for expiry)
        saveCompletionTimestamp(downloadId, completedAt)

        val addedBytes = max(finalFile.length(), tempLength)
        currentSize.addAndGet(addedBytes)
        return finalFile
    }

    /**
     * Save completion timestamp to metadata file.
     * This is the source of truth for download expiry, not file.lastModified().
     */
    private fun saveCompletionTimestamp(downloadId: String, completedAtMillis: Long) {
        // Load existing metadata to preserve title/thumbnailUrl if already set
        val props = loadMetadataProperties(downloadId)
        props.setProperty(META_KEY_COMPLETED_AT, completedAtMillis.toString())
        saveMetadataProperties(downloadId, props)
    }

    /**
     * Save extended metadata (title, thumbnailUrl) for display after app restart.
     * Called when download is enqueued, before completion.
     */
    fun saveExtendedMetadata(downloadId: String, title: String, thumbnailUrl: String?) {
        val props = loadMetadataProperties(downloadId)
        props.setProperty(META_KEY_TITLE, title)
        thumbnailUrl?.let { props.setProperty(META_KEY_THUMBNAIL_URL, it) }
        saveMetadataProperties(downloadId, props)
    }

    private fun loadMetadataProperties(downloadId: String): Properties {
        val metadataFile = File(metadataDir, "$downloadId.meta")
        val props = Properties()
        if (metadataFile.exists()) {
            runCatching {
                metadataFile.inputStream().use { input ->
                    props.load(input)
                }
            }
        }
        return props
    }

    private fun saveMetadataProperties(downloadId: String, props: Properties) {
        val metadataFile = File(metadataDir, "$downloadId.meta")
        runCatching {
            metadataFile.outputStream().use { out ->
                props.store(out, "Download metadata")
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to save metadata for downloadId=$downloadId, " +
                "metadataFile=${metadataFile.absolutePath}", e)
        }
    }

    /**
     * Get completion timestamp from metadata file.
     * Falls back to file.lastModified() if metadata not found.
     *
     * @return Completion timestamp in millis, or null if download not found
     */
    fun getCompletionTimestamp(downloadId: String): Long? {
        // Try metadata file first (source of truth)
        val metadataFile = File(metadataDir, "$downloadId.meta")
        if (metadataFile.exists()) {
            runCatching {
                val props = Properties()
                metadataFile.inputStream().use { input ->
                    props.load(input)
                }
                props.getProperty(META_KEY_COMPLETED_AT)?.toLongOrNull()
            }.getOrNull()?.let { return it }
        }

        // Fall back to file.lastModified() for legacy downloads
        val videoFile = targetFile(downloadId, audioOnly = false)
        if (videoFile.exists() && videoFile.lastModified() > 0) {
            return videoFile.lastModified()
        }
        val audioFile = targetFile(downloadId, audioOnly = true)
        if (audioFile.exists() && audioFile.lastModified() > 0) {
            return audioFile.lastModified()
        }

        return null
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
        // Clean up metadata file
        val metadataFile = File(metadataDir, "$downloadId.meta")
        metadataFile.delete()
    }

    /**
     * Delete a download by ID, trying both audio and video extensions.
     * Also cleans up associated metadata file.
     * Used by expiry worker when file type is unknown.
     * @return true if any file was deleted
     */
    fun delete(downloadId: String): Boolean {
        var deleted = false
        // Try video first
        val videoFile = targetFile(downloadId, audioOnly = false)
        if (videoFile.exists()) {
            val size = videoFile.length()
            if (videoFile.delete()) {
                currentSize.addAndGet(-size)
                deleted = true
            }
        }
        // Also try audio
        val audioFile = targetFile(downloadId, audioOnly = true)
        if (audioFile.exists()) {
            val size = audioFile.length()
            if (audioFile.delete()) {
                currentSize.addAndGet(-size)
                deleted = true
            }
        }
        // Clean up metadata file
        val metadataFile = File(metadataDir, "$downloadId.meta")
        metadataFile.delete()
        return deleted
    }

    fun metadataFor(downloadId: String, audioOnly: Boolean): DownloadFileMetadata? {
        val file = targetFile(downloadId, audioOnly)
        if (!file.exists()) return null
        val props = loadMetadataProperties(downloadId)
        return DownloadFileMetadata(
            sizeBytes = file.length(),
            completedAtMillis = props.getProperty(META_KEY_COMPLETED_AT)?.toLongOrNull()
                ?: file.lastModified(),
            mimeType = if (audioOnly) AUDIO_MIME else VIDEO_MIME,
            title = props.getProperty(META_KEY_TITLE),
            thumbnailUrl = props.getProperty(META_KEY_THUMBNAIL_URL)
        )
    }

    /**
     * Get extended metadata (title, thumbnailUrl) from stored metadata file.
     * Used to restore download info after app restart.
     *
     * @return Pair of (title, thumbnailUrl) or null if metadata not found
     */
    fun getExtendedMetadata(downloadId: String): Pair<String, String?>? {
        val props = loadMetadataProperties(downloadId)
        val title = props.getProperty(META_KEY_TITLE) ?: return null
        val thumbnailUrl = props.getProperty(META_KEY_THUMBNAIL_URL)
        return Pair(title, thumbnailUrl)
    }

    /**
     * Determine if a download is audio-only based on stored metadata or existing files.
     * Used when restoring downloads where only the downloadId is known.
     */
    fun isAudioOnlyDownload(downloadId: String): Boolean {
        // Check if audio file exists
        val audioFile = targetFile(downloadId, audioOnly = true)
        if (audioFile.exists()) return true

        // Check if video file exists
        val videoFile = targetFile(downloadId, audioOnly = false)
        if (videoFile.exists()) return false

        // Default to video if unknown (temp files or no file found)
        return false
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

    fun getAvailableDeviceStorage(): Long {
        return rootDir.usableSpace
    }

    fun getTotalDeviceStorage(): Long {
        return rootDir.totalSpace
    }

    fun getCurrentDownloadSize(): Long {
        return currentSize.get()
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
        private const val TAG = "DownloadStorage"
        private const val AUDIO_MIME = "audio/mp4"
        private const val VIDEO_MIME = "video/mp4"
        private const val TEMP_SUFFIX = ".tmp"
        private const val META_KEY_COMPLETED_AT = "completedAtMillis"
        private const val META_KEY_TITLE = "title"
        private const val META_KEY_THUMBNAIL_URL = "thumbnailUrl"
    }
}
