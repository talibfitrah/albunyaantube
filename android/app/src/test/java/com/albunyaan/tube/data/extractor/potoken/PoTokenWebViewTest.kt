package com.albunyaan.tube.data.extractor.potoken

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cold-start regression: the player resolve, metadata hydration and a (cancelled) prefetch all
 * mint a poToken for the SAME video at once. Pending requests were keyed by the video id, so each
 * request overwrote the previous one's deferred — only one completed, the rest hung for the 10 s
 * generate timeout and then force-recreated the WebView, pushing the first playback past 20 s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class PoTokenWebViewTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(testDispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `concurrent requests for the same identifier each get their token`() = runTest {
        val generator = newUninitializedGenerator()
        val webView = PoTokenWebView::class.java.getDeclaredField("webView")
            .apply { isAccessible = true }.get(generator) as WebView

        val first = async { generator.generatePoToken("vid") }
        runCurrent()
        val firstScript = shadowOf(webView).lastEvaluatedJavascript
        val second = async { generator.generatePoToken("vid") }
        runCurrent()
        val secondScript = shadowOf(webView).lastEvaluatedJavascript

        // Play the JavaScript bridge: each script reports back under the key it was given.
        generator.onObtainPoTokenResult(callbackKey(firstScript), "1,2,3")
        generator.onObtainPoTokenResult(callbackKey(secondScript), "4,5,6")
        advanceUntilIdle()

        assertEquals(u8ToBase64("1,2,3"), first.await())
        assertEquals(u8ToBase64("4,5,6"), second.await())
    }

    @Test
    fun `close fails pending requests immediately instead of leaving them to time out`() = runTest {
        // A provider retry recreates the shared generator (close + new) while other callers still
        // wait on it; they must fail fast and retry, not hang for GENERATE_TIMEOUT_MS.
        val generator = newUninitializedGenerator()
        val pending = async { runCatching { generator.generatePoToken("vid") } }
        runCurrent()

        generator.close()
        runCurrent() // no virtual time passes

        assertTrue("pending request must complete on close()", pending.isCompleted)
        assertTrue(pending.await().exceptionOrNull() is PoTokenException)
    }

    @Test
    fun `a request on an already closed generator fails immediately`() = runTest {
        // e.g. the renderer died (close() via onRenderProcessGone) while the generator is still the
        // provider's current one: a new mint must fail fast, not wait GENERATE_TIMEOUT_MS.
        val generator = newUninitializedGenerator()
        generator.close()

        val request = async { runCatching { generator.generatePoToken("vid") } }
        runCurrent() // no virtual time passes

        assertTrue("request on a closed generator must complete", request.isCompleted)
        assertTrue(request.await().exceptionOrNull() is PoTokenException)
    }

    private fun newUninitializedGenerator(): PoTokenWebView =
        PoTokenWebView::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(ApplicationProvider.getApplicationContext<Context>())

    /** The value of the JS variable the script passes as the first arg of onObtainPoTokenResult. */
    private fun callbackKey(script: String): String {
        val variable = Regex("""onObtainPoTokenResult\((\w+),""").find(script)!!.groupValues[1]
        return Regex("""\b$variable = "([^"]*)"""").find(script)!!.groupValues[1]
    }
}
