package com.albunyaan.tube.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/**
 * Unit tests for [UpdateChecker.isNewerVersion].
 *
 * Covers the semver-2.0.0 precedence rules that power the in-app update prompt.
 * The pre-fix implementation dropped alphabetic prerelease identifiers and would
 * return false for `1.0.0-rc.1` vs `1.0.0-beta.1` (both collapsed to `[1]`), so
 * users on `-beta.N` would never see a `-rc.N` release. This test class locks in
 * the correct semver ordering and guards against that regression.
 */
class UpdateCheckerTest {

    @Test
    fun `strictly greater patch is newer`() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", "1.0.0"))
    }

    @Test
    fun `equal versions are not newer`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun `strictly lower is not newer`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.1"))
    }

    @Test
    fun `numeric segments compare numerically not lexically`() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.10", "1.0.2"))
    }

    @Test
    fun `release beats any prerelease of the same core`() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.0.0-rc.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.0.0-beta.10"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0", "1.0.0-alpha.1"))
    }

    @Test
    fun `prerelease never beats release of the same core`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-rc.1", "1.0.0"))
    }

    @Test
    fun `rc is newer than beta for same core - regression test`() {
        // This is the exact bug caught in code review: the old splitVersion stripped
        // the 'rc' and 'beta' identifiers, collapsed both to [1], and returned false.
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-rc.1", "1.0.0-beta.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-rc.1", "1.0.0-beta.5"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-rc.1", "1.0.0-beta.99"))
    }

    @Test
    fun `beta is newer than alpha for same core`() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-beta.1", "1.0.0-alpha.1"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-beta.1", "1.0.0-alpha.99"))
    }

    @Test
    fun `within same channel compare by numeric suffix`() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-beta.10", "1.0.0-beta.2"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-beta.3", "1.0.0-beta.2"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta.2", "1.0.0-beta.10"))
    }

    @Test
    fun `equal prereleases are not newer`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta.1", "1.0.0-beta.1"))
    }

    @Test
    fun `longer prerelease identifier list wins when shared parts equal`() {
        // Per semver 11.4.4: "A larger set of pre-release fields has a higher
        // precedence than a smaller set, if all of the preceding identifiers are equal."
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-beta.1.1", "1.0.0-beta.1"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta.1", "1.0.0-beta.1.1"))
    }

    @Test
    fun `numeric identifier has lower precedence than alphanumeric`() {
        // Per semver 11.4.3: "Numeric identifiers always have lower precedence than
        // alphanumeric identifiers."
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-alpha", "1.0.0-1"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-1", "1.0.0-alpha"))
    }

    @Test
    fun `handles missing patch gracefully`() {
        // Malformed but tolerated: missing segments should parse as 0, never throw.
        assertTrue(UpdateChecker.isNewerVersion("1.1", "1.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.1"))
    }

    @Test
    fun `empty prerelease treated as absent`() {
        // A trailing dash with nothing after it should not be treated as a prerelease.
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0-"))
    }

    @Test
    fun `non-numeric core segments do not crash`() {
        // Tolerant parser: garbage like `1.x.0` should not throw, just compare as 0.
        // We only care that the call returns a boolean.
        UpdateChecker.isNewerVersion("1.x.0", "1.0.0")
        UpdateChecker.isNewerVersion("1.0.0", "1.x.0")
    }

    // Regression tests for codex-flagged H1: build metadata (+build.N) must be
    // ignored for precedence per semver 2.0.0 §10. The pre-fix splitVersion only
    // stripped on '-', so `1.0.0+build.1` folded the `+build.1` into the core and
    // the comparator wrongly saw it as a newer version.
    @Test
    fun `build metadata is ignored for precedence on core versions`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0+build.1", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0+build.1"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0+build.1", "1.0.0+build.2"))
    }

    @Test
    fun `build metadata is ignored for precedence on prereleases`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta.1+sha.abc", "1.0.0-beta.1"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta.1", "1.0.0-beta.1+sha.abc"))
        // Different build metadata, same prerelease — still equal for precedence.
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta.1+a", "1.0.0-beta.1+b"))
    }

    @Test
    fun `build metadata does not mask genuine newer versions`() {
        // A real upgrade must still be detected even if either side has build metadata.
        assertTrue(UpdateChecker.isNewerVersion("1.0.1+build.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", "1.0.0+build.99"))
        assertTrue(UpdateChecker.isNewerVersion("1.0.0-rc.1+sha.x", "1.0.0-beta.9+sha.y"))
    }

    @Test
    fun `listReleases returns up to limit releases sorted newest-first with APK assets only`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [
              {"tag_name":"v1.0.0-beta.14","name":"beta-14","body":"","prerelease":true,
               "published_at":"2026-05-24T10:00:00Z",
               "assets":[{"name":"app.apk","browser_download_url":"https://example/14.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]},
              {"tag_name":"v1.0.0-beta.13","name":"beta-13","body":"","prerelease":true,
               "assets":[{"name":"app.apk","browser_download_url":"https://example/13.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]},
              {"tag_name":"v1.0.0-beta.12","name":"beta-12","body":"","prerelease":true,
               "assets":[]},
              {"tag_name":"v1.0.0-beta.11","name":"beta-11","body":"","prerelease":true,
               "assets":[{"name":"app.apk","browser_download_url":"https://example/11.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]}
            ]
        """.trimIndent()))
        server.start()

        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        checker.apiBaseUrlForTest = server.url("/").toString()
        val result = checker.listReleases(limit = 5)

        val info = result.getOrThrow()
        assertEquals(3, info.size)  // beta-12 dropped (no APK asset)
        assertEquals("1.0.0-beta.14", info[0].versionName)
        assertEquals("1.0.0-beta.13", info[1].versionName)
        assertEquals("1.0.0-beta.11", info[2].versionName)
        assertEquals(java.time.Instant.parse("2026-05-24T10:00:00Z"), info[0].publishedAt)
        assertNull(info[1].publishedAt)  // beta-13 has no published_at in this fixture
        server.shutdown()
    }

    @Test
    fun `listReleases filters out pre-releases when running build is stable`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""
            [
              {"tag_name":"v1.0.0-beta.15","name":"beta-15","body":"","prerelease":true,
               "assets":[{"name":"app.apk","browser_download_url":"https://example/15.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]},
              {"tag_name":"v1.0.0","name":"stable","body":"","prerelease":false,
               "assets":[{"name":"app.apk","browser_download_url":"https://example/stable.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]}
            ]
        """.trimIndent()))
        server.start()

        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        checker.apiBaseUrlForTest = server.url("/").toString()
        checker.currentVersionForTest = "1.0.0"  // stable, no '-' suffix

        val info = checker.listReleases(limit = 5).getOrThrow()
        assertEquals(1, info.size)
        assertEquals("1.0.0", info[0].versionName)  // beta-15 dropped, only stable kept

        server.shutdown()
    }

    @Test
    fun `listReleases returns empty on Play Store install`() = runTest {
        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn true }
        )
        val result = checker.listReleases(limit = 5)
        assertTrue(result.getOrThrow().isEmpty())
    }

    // codex C-3: HTTP non-2xx must surface as Result.failure so ReleaseCatalogCache
    // does not sticky an empty snapshot for the full TTL on a transient outage.
    @Test
    fun `listReleases on HTTP error returns Result_failure`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("upstream"))
        server.start()

        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        checker.apiBaseUrlForTest = server.url("/").toString()

        val result = checker.listReleases(limit = 5)
        assertTrue("HTTP 503 must produce Result.failure", result.isFailure)
        server.shutdown()
    }

    // codex C-3 contract: HTTP 2xx with an empty body or empty JSON array IS a
    // genuine "no releases" — still Result.success(emptyList()), distinguishable
    // from failure only via Result.isSuccess.
    @Test
    fun `listReleases on empty JSON array returns Result_success with empty list`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]"))
        server.start()

        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        checker.apiBaseUrlForTest = server.url("/").toString()

        val result = checker.listReleases(limit = 5)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
        server.shutdown()
    }

    // codex C-2 upper-bound: per_page is capped at GitHub's documented 100 per
    // request even when limit*6 would exceed 100. Currently picker only uses
    // limit=5 (per_page=30) but the cap will matter the first time anything
    // calls listReleases with limit ≥ 17.
    @Test
    fun `listReleases per_page is capped at GITHUB_MAX_PER_PAGE`() = kotlinx.coroutines.test.runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("[]"))
        server.start()

        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        checker.apiBaseUrlForTest = server.url("/").toString()

        checker.listReleases(limit = 20).getOrThrow()  // 20*6 = 120 → clamp to 100
        val request = server.takeRequest()
        assertTrue(
            "Expected per_page=100 (cap), got ${request.path}",
            request.path?.contains("per_page=100") == true
        )
        server.shutdown()
    }

    // codex stage-6 MEDIUM: limit input validation. `require` rejects zero,
    // negative, and over-100 limits at the contract boundary so callers get a
    // clear IllegalArgumentException instead of GitHub silently returning
    // per_page=0 / 30 / clamping at 100.
    @Test
    fun `listReleases rejects limit equals 0`() = runTest {
        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        try {
            checker.listReleases(limit = 0)
            assertFalse("Expected IllegalArgumentException for limit=0", true)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `listReleases rejects negative limit`() = runTest {
        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        try {
            checker.listReleases(limit = -1)
            assertFalse("Expected IllegalArgumentException for limit=-1", true)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `listReleases rejects limit over GITHUB_MAX_PER_PAGE`() = runTest {
        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        try {
            checker.listReleases(limit = 101)
            assertFalse("Expected IllegalArgumentException for limit=101", true)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // codex C-4 (testing T3): cancelWhenCoroutineCancels registers an
    // invokeOnCompletion hook on the calling coroutine. The hook MUST invoke
    // Call.cancel() when the coroutine is cancelled, AND MUST NOT invoke it
    // when the coroutine completes normally. A regression dropping the
    // `cause != null` guard would cancel every call (including successful
    // ones) — call.cancel() on a completed call is a no-op, so it wouldn't
    // crash, but it would mask real cancellation bugs in future regression
    // tests. A regression dropping the `invokeOnCompletion` entirely would
    // re-introduce the IO-thread leak the hook was added to fix.
    @Test
    fun `cancelWhenCoroutineCancels invokes Call cancel on coroutine cancellation`() = runTest {
        val call = mock<Call>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            call.cancelWhenCoroutineCancels()
            kotlinx.coroutines.awaitCancellation()
        }
        job.cancel()
        job.join()
        verify(call).cancel()
    }

    @Test
    fun `cancelWhenCoroutineCancels does NOT invoke Call cancel on normal completion`() = runTest {
        val call = mock<Call>()
        val job = launch {
            call.cancelWhenCoroutineCancels()
            // returns immediately → normal completion
        }
        job.join()
        verify(call, never()).cancel()
    }

    // codex C-2: per_page is bumped to limit*6 (capped at 100) so a feed with up
    // to limit-1 prereleases-on-stable-build or no-APK entries at the top still
    // returns at least one usable result. Pre-fix: per_page=limit, .filter strips
    // them, .take(limit) yields zero.
    @Test
    fun `listReleases with 5 prereleases at top of feed still returns the stable release on stable build`() = runTest {
        val server = MockWebServer()
        // 6 entries: top 5 are prereleases (filtered out on stable build), entry 6 is stable.
        // Old code: per_page=5 would never see entry 6 → empty result.
        val sb = StringBuilder("[")
        for (i in 19 downTo 15) {
            sb.append("""{"tag_name":"v1.0.0-beta.$i","name":"beta-$i","body":"","prerelease":true,""")
            sb.append("""  "assets":[{"name":"app.apk","browser_download_url":"https://example/$i.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]},""")
        }
        sb.append("""{"tag_name":"v1.0.0","name":"stable","body":"","prerelease":false,""")
        sb.append("""  "assets":[{"name":"app.apk","browser_download_url":"https://example/stable.apk","size":1024,"content_type":"application/vnd.android.package-archive"}]}""")
        sb.append("]")
        server.enqueue(MockResponse().setBody(sb.toString()))
        server.start()

        val checker = UpdateChecker(
            okHttpClient = OkHttpClient(),
            installSource = mock { on { isPlayStore() } doReturn false }
        )
        checker.apiBaseUrlForTest = server.url("/").toString()
        checker.currentVersionForTest = "1.0.0"  // stable

        val info = checker.listReleases(limit = 5).getOrThrow()
        assertEquals(1, info.size)
        assertEquals("1.0.0", info[0].versionName)

        // Verify the actual per_page sent (limit=5 → per_page=30, capped at 100).
        val request = server.takeRequest()
        assertTrue(
            "Expected per_page in URL, got ${request.path}",
            request.path?.contains("per_page=30") == true
        )

        server.shutdown()
    }
}
