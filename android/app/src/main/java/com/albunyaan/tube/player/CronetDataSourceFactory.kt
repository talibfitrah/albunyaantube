package com.albunyaan.tube.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cronet.CronetDataSource
import com.albunyaan.tube.util.HttpConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import org.chromium.net.CronetEngine
import org.chromium.net.CronetProvider
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(UnstableApi::class)
@Singleton
class CronetDataSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "CronetDataSourceFactory"
    }

    // Built lazily; null means Cronet is unavailable → fall back to DefaultHttpDataSource.
    private val engine: CronetEngine? by lazy { buildEngine() }

    fun createForAndroidUA(): DataSource.Factory =
        create(HttpConstants.YOUTUBE_USER_AGENT)

    fun createForIosUA(): DataSource.Factory =
        create(HttpConstants.YOUTUBE_IOS_USER_AGENT)

    private fun create(userAgent: String): DataSource.Factory {
        val e = engine ?: return DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)
        return CronetDataSource.Factory(e, Executors.newCachedThreadPool())
            .setUserAgent(userAgent)
            .setConnectionTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
    }

    private fun buildEngine(): CronetEngine? {
        return try {
            val providers = CronetProvider.getAllProviders(context)
            // Prefer Google Play Services provider; skip the fallback-only provider.
            val provider = providers
                .filter { it.isEnabled && it.name != CronetProvider.PROVIDER_NAME_FALLBACK }
                .firstOrNull()
                ?: return null
            Log.i(TAG, "Cronet provider: ${provider.name}")
            provider.createBuilder().build()
        } catch (e: Exception) {
            Log.w(TAG, "Cronet unavailable, will use DefaultHttpDataSource", e)
            null
        }
    }
}
