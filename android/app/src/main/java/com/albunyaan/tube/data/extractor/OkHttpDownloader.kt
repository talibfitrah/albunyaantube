package com.albunyaan.tube.data.extractor

import android.util.Log
import com.albunyaan.tube.BuildConfig
import java.io.File
import java.io.IOException
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization

class OkHttpDownloader(
    private val client: OkHttpClient,
    private val debugCaptureDir: File? = null
) : Downloader() {
    private val captureEnabled = BuildConfig.DEBUG && debugCaptureDir != null
    private val videoIdRegex = Regex("\"videoId\"\\s*:\\s*\"([a-zA-Z0-9_-]{11})\"")

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())

        val headers = request.headers()
        headers.forEach { (name, values) ->
            val headerValue = values.joinToString(",")
            builder.header(name, headerValue)
        }

        if (!headers.keys.any { it.equals(ACCEPT_LANGUAGE, ignoreCase = true) }) {
            request.localization()?.let { localization ->
                val languageTag = buildLanguageTag(localization)
                builder.header(ACCEPT_LANGUAGE, languageTag)
            }
        }

        val requestBodyBytes = request.dataToSend()
        when (request.httpMethod().uppercase(Locale.US)) {
            "HEAD" -> builder.head()
            "GET" -> builder.get()
            "POST" -> {
                val data = requestBodyBytes ?: ByteArray(0)
                val mediaType = request.headers()[CONTENT_TYPE]?.firstOrNull()?.toMediaTypeOrNull()
                builder.post(data.toRequestBody(mediaType))
            }
            else -> {
                val data = requestBodyBytes?.toRequestBody(null)
                builder.method(request.httpMethod(), data)
            }
        }

        val captureInfo = if (captureEnabled) {
            buildCaptureInfo(request, requestBodyBytes)
        } else null

        captureInfo?.let { info ->
            writeDebugCapture(
                info = info,
                suffix = "request.txt",
                content = buildString {
                    appendLine("URL: ${info.url}")
                    appendLine("METHOD: ${info.method}")
                    appendLine("HEADERS:")
                    info.headers.forEach { (name, values) ->
                        appendLine("$name: ${values.joinToString(",")}")
                    }
                    appendLine("BODY:")
                    appendLine(info.body ?: "")
                }
            )
        }

        val call = client.newCall(builder.build())
        val response = call.execute()
        response.use {
            val responseBody = it.body?.string() ?: ""
            captureInfo?.let { info ->
                writeDebugCapture(
                    info = info,
                    suffix = "response.json",
                    content = responseBody
                )
                Log.d(TAG, "Captured youtubei response for ${info.videoId ?: "unknown"}")
            }
            return Response(
                it.code,
                it.message,
                it.headers.toMultimap(),
                responseBody,
                it.request.url.toString()
            )
        }
    }

    private fun buildLanguageTag(localization: Localization): String {
        val language = localization.languageCode
        val country = localization.countryCode
        return if (country.isBlank()) language else "$language-$country"
    }

    private fun buildCaptureInfo(request: Request, bodyBytes: ByteArray?): CaptureInfo? {
        val url = request.url()
        if (!isYoutubeiPlayerRequest(url)) return null

        val body = bodyBytes?.toString(Charsets.UTF_8)
        val videoId = body?.let { videoIdRegex.find(it)?.groupValues?.getOrNull(1) }
        if (videoId == null) return null

        return CaptureInfo(
            url = url,
            method = request.httpMethod().uppercase(Locale.US),
            headers = request.headers(),
            body = body,
            videoId = videoId
        )
    }

    private fun isYoutubeiPlayerRequest(url: String): Boolean {
        if (!url.contains("youtubei")) return false
        return url.contains("/player") || url.contains("reel/reel_item_watch")
    }

    private fun writeDebugCapture(info: CaptureInfo, suffix: String, content: String) {
        val dir = File(requireNotNull(debugCaptureDir), "npe_capture")
        if (!dir.exists() && !dir.mkdirs()) return

        val safeVideoId = info.videoId ?: "unknown"
        val filename = "${safeVideoId}_${info.timestamp}_${suffix}"
        runCatching {
            File(dir, filename).writeText(content)
        }.onFailure {
            Log.w(TAG, "Failed to write capture file $filename: ${it.message}")
        }
    }

    private data class CaptureInfo(
        val url: String,
        val method: String,
        val headers: Map<String, List<String>>,
        val body: String?,
        val videoId: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    companion object {
        private const val TAG = "NpeCapture"
        private const val ACCEPT_LANGUAGE = "Accept-Language"
        private const val CONTENT_TYPE = "Content-Type"
    }
}
