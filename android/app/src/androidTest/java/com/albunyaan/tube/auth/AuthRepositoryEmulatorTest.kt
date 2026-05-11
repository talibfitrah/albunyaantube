package com.albunyaan.tube.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Plan B (ANDROID-AUTH-01) T7: end-to-end sign-up / sign-in / sign-out
 * against a locally-running Firebase Auth Emulator.
 *
 * Requirements to run:
 *   1. Firebase emulator suite running on the host machine:
 *        cd backend && firebase emulators:start --only auth
 *      (Port 9099 by default.)
 *   2. local.properties in android/ sets the emulator override:
 *        auth.emulator.host=10.0.2.2          # Android emulator → host
 *        auth.emulator.port=9099
 *      (Or your laptop's LAN IP if testing on a physical device.)
 *   3. Run with: ./gradlew :app:connectedDebugAndroidTest --tests "com.albunyaan.tube.auth.AuthRepositoryEmulatorTest"
 *
 * Bootstrap: AlBunyaanApplication.applyAuthEmulatorOverrideIfConfigured()
 * already redirected FirebaseAuth.getInstance() to the emulator at app start.
 *
 * Cleanup: emulator state is wiped via its REST endpoint between tests so
 * each test starts from an empty user set.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@LargeTest
class AuthRepositoryEmulatorTest {

    @get:Rule
    var hiltRule = HiltAndroidRule(this)

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var firebaseAuth: FirebaseAuth

    @Before
    fun setUp() {
        hiltRule.inject()
        // Sanity: caller wiring must point at the emulator. If not, skip
        // rather than silently hit production Firebase.
        org.junit.Assume.assumeTrue(
            "Auth emulator override not configured — set auth.emulator.host in local.properties",
            com.albunyaan.tube.BuildConfig.AUTH_EMULATOR_HOST.isNotBlank(),
        )
        clearEmulatorUsers()
    }

    @After
    fun tearDown() {
        firebaseAuth.signOut()
        clearEmulatorUsers()
    }

    @Test
    fun signUpWithEmail_createsUser_andEmitsSignedIn() = runBlocking {
        val email = unique("signup")
        val result = authRepository.signUpWithEmail(email, "secretpassword")

        assertTrue("signup failed: ${result.exceptionOrNull()}", result.isSuccess)
        val user = result.getOrNull()
        assertNotNull(user)
        assertEquals(email, user!!.email)
        assertEquals(user.uid, firebaseAuth.currentUser?.uid)
    }

    @Test
    fun signInWithEmail_withWrongPassword_returnsFailure() = runBlocking {
        val email = unique("wrongpw")
        authRepository.signUpWithEmail(email, "correctpassword")
        firebaseAuth.signOut()

        val bad = authRepository.signInWithEmail(email, "WRONG-PASSWORD")
        assertTrue(bad.isFailure)
    }

    @Test
    fun signOut_clearsCurrentUser() = runBlocking {
        val email = unique("signout")
        authRepository.signUpWithEmail(email, "secretpassword")
        assertNotNull(firebaseAuth.currentUser)

        authRepository.signOut()

        assertNull(firebaseAuth.currentUser)
    }

    @Test
    fun signUp_thenSignOut_thenSignIn_roundTrip() = runBlocking {
        val email = unique("roundtrip")
        val signUp = authRepository.signUpWithEmail(email, "secretpassword")
        assertTrue(signUp.isSuccess)
        val originalUid = signUp.getOrNull()?.uid

        authRepository.signOut()
        assertNull(firebaseAuth.currentUser)

        val signIn = authRepository.signInWithEmail(email, "secretpassword")
        assertTrue(signIn.isSuccess)
        assertEquals(originalUid, signIn.getOrNull()?.uid)
    }

    private fun unique(prefix: String): String =
        "$prefix-${System.currentTimeMillis()}@example.test"

    /**
     * Firebase Auth emulator exposes a REST endpoint to wipe all accounts.
     * Best-effort — if it fails (emulator not on the expected port, project
     * id mismatch), the next test may inherit users; tests use unique emails
     * so it usually doesn't matter, but a clean slate avoids surprises.
     */
    private fun clearEmulatorUsers() {
        val host = com.albunyaan.tube.BuildConfig.AUTH_EMULATOR_HOST
        val port = com.albunyaan.tube.BuildConfig.AUTH_EMULATOR_PORT
        val projectId = firebaseAuth.app.options.projectId ?: return
        val url = URL("http://$host:$port/emulator/v1/projects/$projectId/accounts")
        try {
            (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 2000
                readTimeout = 2000
                responseCode  // trigger the request
                disconnect()
            }
        } catch (_: Exception) {
            // Emulator unreachable — assumeTrue() in setUp would have caught
            // misconfiguration; runtime emulator crashes leave residue we
            // accept.
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun preflight() {
            // Surface the device's view of the emulator endpoint for
            // debugging when the test fails with "default app not initialized"
            // or similar opaque errors.
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            android.util.Log.i(
                "AuthEmulatorTest",
                "Target package: ${ctx.packageName}, " +
                    "AUTH_EMULATOR_HOST=${com.albunyaan.tube.BuildConfig.AUTH_EMULATOR_HOST}, " +
                    "AUTH_EMULATOR_PORT=${com.albunyaan.tube.BuildConfig.AUTH_EMULATOR_PORT}",
            )
        }
    }
}
