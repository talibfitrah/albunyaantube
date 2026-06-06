package com.albunyaan.tube.data.extractor.potoken

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.albunyaan.tube.data.extractor.OkHttpDownloader
import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfo

/**
 * On-device verification + regression test for the poToken fix (the HTTP 403 "Resolving stream…"
 * loop). Requires a real WebView and live network, so it runs only on a connected device/emulator,
 * not in the JVM unit suite.
 *
 * Mirrors the production config: the **iOS client** (full HD/4K adaptive ladder) plus the
 * WebView-minted GVS poToken from [WebViewPoTokenProvider]. Without the token YouTube returns HTTP
 * 403 on the iOS stream URLs (the bug). The test fails closed on three independent signals, each of
 * which would regress if the wiring breaks:
 *  - the stream URL must carry the `pot=` poToken (proves the token reached the extractor and was
 *    applied — a web-only provider against extractor 0.26.2 silently drops it),
 *  - the full ladder must be present (>=720p; android-client fallback is SABR-capped to 360p),
 *  - the URL must fetch 200/206 (a missing/invalid token yields 403).
 */
@RunWith(AndroidJUnit4::class)
class PoTokenStreamResolutionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val downloader = OkHttpDownloader(OkHttpClient(), context.cacheDir)
        NewPipe.init(downloader, Localization.fromLocale(Locale.US), ContentCountry("US"))
        // Production config: iOS client + WebView-minted poToken.
        YoutubeStreamExtractor.setFetchIosClient(true)
        YoutubeStreamExtractor.setPoTokenProvider(WebViewPoTokenProvider(context))
    }

    @Test
    fun iosClientStreamCarriesPoTokenAndFetches200() = runBlocking {
        // V2Brp_esIVI is one of the user-reported failing videos (Mishary Alafasy adhkar).
        val info = StreamInfo.getInfo("https://www.youtube.com/watch?v=V2Brp_esIVI")

        val url = (info.videoStreams + info.videoOnlyStreams + info.audioStreams)
            .firstOrNull { it.content.isNotBlank() }
            ?.content
        assertTrue("Expected at least one playable stream URL", url != null)

        assertTrue(
            "Stream URL must carry a GVS poToken (pot=) — its absence means the token was dropped " +
                "before reaching the extractor (the 0.26.2 wiring bug)",
            url!!.contains("pot="),
        )

        val maxHeight = (info.videoStreams + info.videoOnlyStreams)
            .mapNotNull { it.height.takeIf { h -> h > 0 } }
            .maxOrNull() ?: 0
        assertTrue(
            "Expected the full iOS-client ladder (>=720p) but got ${maxHeight}p — this looks like " +
                "the SABR-capped android fallback, meaning the iOS client failed",
            maxHeight >= 720,
        )

        val code = OkHttpClient().newCall(
            Request.Builder().url(url).header("Range", "bytes=0-1").build()
        ).execute().use { it.code }
        assertTrue(
            "Stream URL must return 200/206 with a valid poToken (got HTTP $code — 403 means the " +
                "poToken was missing or invalid)",
            code == 200 || code == 206,
        )
    }
}
