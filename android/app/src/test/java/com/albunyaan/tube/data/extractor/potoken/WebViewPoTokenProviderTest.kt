package com.albunyaan.tube.data.extractor.potoken

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class WebViewPoTokenProviderTest {

    @Test
    fun `callers that failed on the same generator rebuild it once, not once each`() {
        // Cold start: several callers mint on one generator; it fails for all of them. The first to
        // retry rebuilds it. The others must reuse that fresh generator — rebuilding again would
        // close it under the first caller and cost another WebView/BotGuard init each.
        val bothMinting = CountDownLatch(2)
        val created = AtomicInteger()
        val factory = object : PoTokenGenerator.Factory {
            override suspend fun newPoTokenGenerator(context: Context): PoTokenGenerator {
                val n = created.incrementAndGet()
                return object : PoTokenGenerator {
                    override suspend fun generatePoToken(identifier: String): String {
                        if (n == 1 && identifier == "vid") {
                            bothMinting.countDown()
                            bothMinting.await(5, TimeUnit.SECONDS)
                            throw PoTokenException("renderer gone")
                        }
                        return "tok$n"
                    }
                    override fun isExpired() = false
                    override fun close() {}
                }
            }
        }
        val provider = WebViewPoTokenProvider(
            ApplicationProvider.getApplicationContext(),
            generatorFactory = factory,
            fetchVisitorData = { "visitor" },
        )

        val results = arrayOfNulls<String>(2)
        (0..1).map { i -> thread { results[i] = provider.getIosClientPoToken("vid")?.playerRequestPoToken } }
            .forEach { it.join(10_000) }

        assertEquals(listOf("tok2", "tok2"), results.toList())
        assertEquals("generator must be rebuilt exactly once", 2, created.get())
    }

    @Test
    fun `a caller giving up does not close the generator another caller already rebuilt`() {
        // A exhausts its attempts on a video-specific mint error; meanwhile B rebuilt the shared
        // generator and is minting on it. A's give-up must not tear B's healthy generator down.
        val aOnThird = CountDownLatch(1)
        val bMintingOnFourth = CountDownLatch(1)
        val closed = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()
        val created = AtomicInteger()
        val factory = object : PoTokenGenerator.Factory {
            override suspend fun newPoTokenGenerator(context: Context): PoTokenGenerator {
                val n = created.incrementAndGet()
                return object : PoTokenGenerator {
                    override suspend fun generatePoToken(identifier: String): String = when {
                        identifier == "visitor" -> "s$n"
                        identifier == "good" && n == 4 -> "tok4".also { bMintingOnFourth.countDown() }
                        identifier == "bad" && n == 3 -> {
                            aOnThird.countDown()
                            bMintingOnFourth.await(5, TimeUnit.SECONDS)
                            throw PoTokenException("mint failed")
                        }
                        else -> throw PoTokenException("mint failed")
                    }
                    override fun isExpired() = false
                    override fun close() { closed[n] = true }
                }
            }
        }
        val provider = WebViewPoTokenProvider(
            ApplicationProvider.getApplicationContext(),
            generatorFactory = factory,
            fetchVisitorData = { "visitor" },
        )

        var aResult: Any? = "unset"
        val a = thread { aResult = provider.getIosClientPoToken("bad") }
        aOnThird.await(5, TimeUnit.SECONDS)
        var bResult: String? = null
        val b = thread { bResult = provider.getIosClientPoToken("good")?.playerRequestPoToken }
        listOf(a, b).forEach { it.join(10_000) }

        assertEquals(null, aResult)
        assertEquals("tok4", bResult)
        assertEquals("B's rebuilt generator must stay open", null, closed[4])
    }
}
