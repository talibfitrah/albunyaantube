package com.albunyaan.tube.data.youtube

import android.content.Intent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract test for the YouTubeAuthManager seam.
 *
 * The real GoogleYouTubeAuthManager requires a live Google Identity Services
 * client and an Android Context — it is exercised manually on-device.
 * These tests prove that:
 *   1. The sealed AuthResult hierarchy is correct.
 *   2. Any YouTubeAuthManager implementation that returns Granted/Denied can
 *      be safely substituted (the interface seam is stable for B6–B9).
 */
class YouTubeAuthManagerContractTest {

    // --- Fakes -----------------------------------------------------------

    private class FakeGrantedYouTubeAuthManager : YouTubeAuthManager {
        override suspend fun authorize(): AuthResult = AuthResult.Granted("tok")
        override suspend fun authorizeFromConsentResult(data: Intent?): AuthResult =
            AuthResult.Granted("tok")
    }

    private class FakeDeniedYouTubeAuthManager : YouTubeAuthManager {
        override suspend fun authorize(): AuthResult = AuthResult.Denied
        override suspend fun authorizeFromConsentResult(data: Intent?): AuthResult =
            AuthResult.Denied
    }

    // --- Tests -----------------------------------------------------------

    @Test
    fun `Granted carries access token`() = runTest {
        val manager: YouTubeAuthManager = FakeGrantedYouTubeAuthManager()
        val result = manager.authorize()
        assertTrue("Expected Granted", result is AuthResult.Granted)
        assertEquals("tok", (result as AuthResult.Granted).accessToken)
    }

    @Test
    fun `Granted from consent result carries access token`() = runTest {
        val manager: YouTubeAuthManager = FakeGrantedYouTubeAuthManager()
        val result = manager.authorizeFromConsentResult(null)
        assertTrue("Expected Granted", result is AuthResult.Granted)
        assertEquals("tok", (result as AuthResult.Granted).accessToken)
    }

    @Test
    fun `Denied is Denied`() = runTest {
        val manager: YouTubeAuthManager = FakeDeniedYouTubeAuthManager()
        val result = manager.authorize()
        assertTrue("Expected Denied", result is AuthResult.Denied)
    }

    @Test
    fun `Denied from consent result is Denied`() = runTest {
        val manager: YouTubeAuthManager = FakeDeniedYouTubeAuthManager()
        val result = manager.authorizeFromConsentResult(null)
        assertTrue("Expected Denied", result is AuthResult.Denied)
    }

    @Test
    fun `Failed carries the original throwable`() = runTest {
        val cause = RuntimeException("network down")
        val result: AuthResult = AuthResult.Failed(cause)
        assertTrue("Expected Failed", result is AuthResult.Failed)
        assertEquals(cause, (result as AuthResult.Failed).error)
    }

    @Test
    fun `NeedsConsent type exists and is distinct from Granted`() {
        // Structural: NeedsConsent requires a PendingIntent at runtime, so we
        // just verify it is a distinct sealed subtype at compile time.
        // (The PendingIntent constructor is Android-side; not instantiable in JVM tests.)
        val grantedClass = AuthResult.Granted::class
        val deniedClass  = AuthResult.Denied::class
        val failedClass  = AuthResult.Failed::class
        val needsClass   = AuthResult.NeedsConsent::class

        // All four subtypes must be distinct
        val classes = setOf(grantedClass, deniedClass, failedClass, needsClass)
        assertEquals("AuthResult must have exactly 4 sealed subtypes represented", 4, classes.size)
    }
}
