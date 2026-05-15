package com.albunyaan.tube.share

import android.net.Uri
import com.albunyaan.tube.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object ShareMetadataPublisher {
    /**
     * Provider of the OkHttpClient used to publish share metadata. Wired by
     * {@code AlBunyaanApplication.onCreate()} to point at the Hilt-managed
     * client from NetworkModule, which carries the X-Device-Id, Bearer-token,
     * and account-status interceptors. The default fallback is a bare client
     * for unit-test convenience; production paths always overwrite it.
     *
     * Previously this object used its own `OkHttpClient()` without
     * interceptors — every share-metadata POST then 400'd on the backend's
     * X-Device-Id requirement (cubic round-3 P0). Going through the Hilt
     * client ensures both X-Device-Id and Bearer headers are attached
     * automatically.
     */
    @Volatile var httpClientProvider: () -> OkHttpClient = { OkHttpClient() }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun publish(
        type: String,
        id: String,
        title: String?,
        imageUrl: String?,
        description: String?
    ) {
        val shareBaseUrl = BuildConfig.SHARE_BASE_URL.trimEnd('/')
        if (shareBaseUrl.isBlank() || id.isBlank()) return

        withTimeoutOrNull(PUBLISH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                runCatching {
                    val url = Uri.parse(shareBaseUrl)
                        .buildUpon()
                        .appendPath("api")
                        .appendPath("share-metadata")
                        .appendPath(type)
                        .appendPath(id)
                        .build()
                        .toString()
                    val payload = JSONObject().apply {
                        title?.trim()?.takeIf { it.isNotEmpty() }?.let { put("title", it) }
                        imageUrl?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) }?.let {
                            put("image", it)
                        }
                        description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
                    }
                    if (payload.length() == 0) return@runCatching

                    val request = Request.Builder()
                        .url(url)
                        .post(payload.toString().toRequestBody(jsonMediaType))
                        .build()
                    httpClientProvider().newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            android.util.Log.w(TAG, "Share metadata publish failed: HTTP ${response.code}")
                        }
                    }
                }.onFailure { error ->
                    android.util.Log.w(TAG, "Share metadata publish failed", error)
                }
            }
        }
    }

    private const val TAG = "ShareMetadataPublisher"
    private const val PUBLISH_TIMEOUT_MS = 900L
}
