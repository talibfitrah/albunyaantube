package com.albunyaan.tube.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan B (ANDROID-AUTH-01) T3: attaches `Authorization: Bearer <id-token>` to
 * every outgoing request when a [FirebaseAuth] user is signed in.
 *
 * PERFORMANCE: uses `runBlocking` to bridge Firebase's `Task<GetTokenResult>`
 * to the synchronous OkHttp interceptor contract. **Never on the main thread**
 * — OkHttp dispatcher threads only. The Firebase SDK caches the ID token
 * (~1h TTL); concurrent `getIdToken(false)` calls share the cached value when
 * the cache is warm, but a true cache miss still serializes through the SDK's
 * internal refresh path. Real-world saturation behavior is measured by the
 * T7 emulator integration tests against actual Firebase Auth; the unit tests
 * here verify the interceptor logic, not the SDK's internals.
 *
 * 401 retry: when the backend rejects a stale token (`WWW-Authenticate: Bearer`),
 * we force-refresh and retry **once**. A second 401 propagates to the caller —
 * the token is genuinely bad and the user must re-authenticate.
 *
 * Plan A backend interaction: only /api/admin/... checks tokens via
 * [com.albunyaan.tube.security.FirebaseAuthFilter] (on the server). /api/v1/...
 * is exempt server-side, so this interceptor still attaches the header (no
 * harm) but the server ignores it for public endpoints.
 */
@Singleton
class FirebaseAuthInterceptor @Inject constructor(
    private val auth: FirebaseAuth,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val user = auth.currentUser ?: return chain.proceed(chain.request())

        // Defensive: if Firebase throws during token fetch (network blip mid
        // refresh, account just disabled, etc.) do NOT leak the raw exception
        // through the OkHttp chain — send the request unsigned. The backend
        // will respond 401/403 which propagates cleanly through the rest of
        // the stack.
        // Cubic R7 P0 — bound the blocking duration on the OkHttp dispatcher
        // thread. Pre-fix, `getIdToken(false)` could pile up dispatcher threads
        // under post-sign-in cache misses (each thread blocked on the same
        // task bridge). Capping at TOKEN_FETCH_TIMEOUT_MS ensures a single
        // saturated SDK call cannot stall the entire dispatcher pool. On
        // timeout we send unsigned and let the backend's 401 trigger the
        // explicit refresh path below — same outcome, predictable bound.
        val token = try {
            runBlocking { withTimeoutOrNull(TOKEN_FETCH_TIMEOUT_MS) { user.getIdToken(false).await().token } }
        } catch (ie: InterruptedException) {
            // Don't swallow interrupts (cubic R5 P2). Propagate so OkHttp's
            // dispatcher can abort cleanly instead of proceeding unsigned
            // and burning a 401 round-trip on a cancelled call.
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Auth interceptor interrupted").initCause(ie) as java.io.InterruptedIOException
        } catch (_: Exception) {
            null
        } ?: return chain.proceed(chain.request())

        val signed = chain.request().withBearer(token)
        val response = chain.proceed(signed)

        if (response.code == 401 && response.isFirebaseUnauthorized()) {
            response.close()
            val refreshed = try {
                runBlocking { withTimeoutOrNull(TOKEN_REFRESH_TIMEOUT_MS) { user.getIdToken(true).await().token } }
            } catch (_: Exception) {
                null
            }
            if (refreshed == null) {
                // Cubic R7 P1 — return the original 401 instead of replaying
                // unsigned.
                //
                // Pre-fix the interceptor stripped Authorization and replayed
                // the request. Public endpoints (those mounted at /api/v1/*
                // without status enforcement) silently accepted the
                // unauthenticated request and returned 200, masking the auth
                // failure to upstream callers. The 401 envelope is the truth:
                // the user's token is stale or revoked and the UI must route
                // to sign-in. Re-execute the signed request — it will 401
                // again — to surface the failure clean rather than fake a
                // success.
                return chain.proceed(signed)
            }
            return chain.proceed(signed.withBearer(refreshed))
        }
        return response
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun Response.isFirebaseUnauthorized(): Boolean =
        header("WWW-Authenticate")?.contains("Bearer", ignoreCase = true) == true

    companion object {
        // Cubic R7 P0 — dispatcher-thread blocking caps. Generous on the
        // happy-path getIdToken(false) (cached → returns near-instantly),
        // longer on the force-refresh retry since it always hits the network.
        private const val TOKEN_FETCH_TIMEOUT_MS = 3_000L
        private const val TOKEN_REFRESH_TIMEOUT_MS = 5_000L
    }
}
