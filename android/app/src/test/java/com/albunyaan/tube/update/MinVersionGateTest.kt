package com.albunyaan.tube.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric only for the gate's SharedPreferences cache; the compare and parse are pure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MinVersionGateTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        context = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() = server.shutdown()

    private fun gate() = MinVersionGate(OkHttpClient(), context).apply {
        urlForTest = server.url("/android-remote-config.json").toString()
    }

    // --- isBelowMinimum: numeric-segment compare, iOS SemVer semantics ---

    @Test fun `compare is numeric per segment, not lexicographic`() {
        assertTrue(isBelowMinimum("1.0.9", "1.0.10"))
        assertFalse(isBelowMinimum("1.0.10", "1.0.9"))
    }

    @Test fun `equal versions are not below`() {
        assertFalse(isBelowMinimum("1.0.7", "1.0.7"))
    }

    @Test fun `missing segments count as zero`() {
        assertFalse(isBelowMinimum("1.0", "1.0.0"))
        assertFalse(isBelowMinimum("1.0.0", "1.0"))
        assertTrue(isBelowMinimum("1.0", "1.0.1"))
        assertFalse(isBelowMinimum("1.0.0.1", "1.0"))
        assertTrue(isBelowMinimum("1.0.6", "1.0.6.1"))
    }

    @Test fun `older and newer installs`() {
        assertTrue(isBelowMinimum("1.0.6", "1.0.7"))
        assertFalse(isBelowMinimum("1.0.8", "1.0.7"))
        assertFalse(isBelowMinimum("2.0", "1.9.9"))
    }

    @Test fun `null or blank on either side fails open`() {
        assertFalse(isBelowMinimum(null, "1.0.7"))
        assertFalse(isBelowMinimum("", "1.0.7"))
        assertFalse(isBelowMinimum("1.0.6", null))
        assertFalse(isBelowMinimum("1.0.6", "  "))
    }

    @Test fun `each segment is its leading digits or zero`() {
        assertTrue(isBelowMinimum("1.0.6-beta", "1.0.7"))
        assertFalse(isBelowMinimum("1.0.7-beta", "1.0.7"))
        // A garbage floor parses to all zeros and never exceeds a real version.
        assertFalse(isBelowMinimum("1.0.6", "garbage"))
        assertFalse(isBelowMinimum("1.0.6", "x.y.z"))
    }

    // --- parseMinAppVersion ---

    @Test fun `parses a good config`() {
        assertEquals("1.0.7", parseMinAppVersion("""{ "schemaVersion": 1, "minAppVersion": "1.0.7" }"""))
    }

    @Test fun `garbage, missing, non-string and blank values parse to null`() {
        assertNull(parseMinAppVersion("not json"))
        assertNull(parseMinAppVersion("[]"))
        assertNull(parseMinAppVersion("""{ "schemaVersion": 1 }"""))
        assertNull(parseMinAppVersion("""{ "minAppVersion": 107 }"""))
        assertNull(parseMinAppVersion("""{ "minAppVersion": null }"""))
        assertNull(parseMinAppVersion("""{ "minAppVersion": "  " }"""))
    }

    @Test fun `non-numeric-dotted values parse to null, a trimmed one parses`() {
        assertNull(parseMinAppVersion("""{ "minAppVersion": "abc" }"""))
        assertNull(parseMinAppVersion("""{ "minAppVersion": "1.0.x" }"""))
        assertNull(parseMinAppVersion("""{ "minAppVersion": "" }"""))
        assertNull(parseMinAppVersion("""{ "minAppVersion": "1.0.7-beta" }"""))
        assertEquals("1.0.7", parseMinAppVersion("""{ "minAppVersion": " 1.0.7 " }"""))
    }

    // --- fetchAndDecide / decideFromCache ---

    @Test fun `200 with a higher floor caches it and decides required`() = runTest {
        server.enqueue(MockResponse().setBody("""{ "schemaVersion": 1, "minAppVersion": "1.0.7" }"""))
        val gate = gate()

        assertTrue(gate.fetchAndDecide("1.0.6"))
        assertTrue("cached for the next launch", gate().decideFromCache("1.0.6"))
        assertFalse(gate.decideFromCache("1.0.7"))
    }

    @Test fun `500 with no cache fails open`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{ "minAppVersion": "9.9.9" }"""))
        assertFalse(gate().fetchAndDecide("1.0.6"))
    }

    @Test fun `garbage body with no cache fails open`() = runTest {
        server.enqueue(MockResponse().setBody("<html>oops</html>"))
        assertFalse(gate().fetchAndDecide("1.0.6"))
    }

    @Test fun `oversize body with no cache fails open`() = runTest {
        val padding = " ".repeat(5 * 1024)
        server.enqueue(MockResponse().setBody("""{ "minAppVersion": "9.9.9" $padding}"""))
        assertFalse(gate().fetchAndDecide("1.0.6"))
    }

    @Test fun `failed fetch keeps a cached higher floor`() = runTest {
        server.enqueue(MockResponse().setBody("""{ "minAppVersion": "1.0.7" }"""))
        assertTrue(gate().fetchAndDecide("1.0.6"))

        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(gate().fetchAndDecide("1.0.6"))
        server.enqueue(MockResponse().setBody("garbage"))
        assertTrue(gate().fetchAndDecide("1.0.6"))
    }

    @Test fun `malformed fetched value keeps a cached higher floor`() = runTest {
        server.enqueue(MockResponse().setBody("""{ "minAppVersion": "1.0.7" }"""))
        assertTrue(gate().fetchAndDecide("1.0.6"))
        server.enqueue(MockResponse().setBody("""{ "minAppVersion": "abc" }"""))
        assertTrue(gate().fetchAndDecide("1.0.6"))
        server.enqueue(MockResponse().setBody("""{ "minAppVersion": "1.0.x" }"""))
        assertTrue(gate().fetchAndDecide("1.0.6"))
    }

    @Test fun `a good lower value overwrites the cache`() = runTest {
        server.enqueue(MockResponse().setBody("""{ "minAppVersion": "1.0.7" }"""))
        assertTrue(gate().fetchAndDecide("1.0.6"))
        server.enqueue(MockResponse().setBody("""{ "minAppVersion": "1.0.5" }"""))
        assertFalse(gate().fetchAndDecide("1.0.6"))
    }

    @Test fun `no value ever seen never blocks`() {
        assertFalse(gate().decideFromCache("0.0.1"))
    }
}
