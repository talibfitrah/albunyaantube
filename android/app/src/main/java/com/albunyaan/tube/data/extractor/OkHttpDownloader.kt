package com.albunyaan.tube.data.extractor

import java.io.IOException
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization

class OkHttpDownloader(
    private val client: OkHttpClient
) : Downloader() {

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

        when (request.httpMethod().uppercase(Locale.US)) {
            "HEAD" -> builder.head()
            "GET" -> builder.get()
            "POST" -> {
                val data = request.dataToSend() ?: ByteArray(0)
                val mediaType = request.headers()[CONTENT_TYPE]?.firstOrNull()?.toMediaTypeOrNull()
                builder.post(data.toRequestBody(mediaType))
            }
            else -> {
                val data = request.dataToSend()?.toRequestBody(null)
                builder.method(request.httpMethod(), data)
            }
        }

        val call = client.newCall(builder.build())
        val response = call.execute()
        response.use {
            val responseBody = it.body?.string() ?: ""
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

    companion object {
        private const val ACCEPT_LANGUAGE = "Accept-Language"
        private const val CONTENT_TYPE = "Content-Type"
    }
}

