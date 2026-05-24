package com.albunyaan.tube.update

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

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
}
