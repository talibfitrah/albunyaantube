package com.albunyaan.tube.auth

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
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
        val token = try {
            runBlocking { user.getIdToken(false).await().token }
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
                runBlocking { user.getIdToken(true).await().token }
            } catch (_: Exception) {
                null
            }
            if (refreshed == null) {
                // Token refresh failed (network blip, account just disabled, etc.). Re-issuing
                // the same already-rejected signed request would just yield another 401 and
                // waste a round-trip; replay the request unsigned so the backend can emit a
                // fresh 401 with an accurate WWW-Authenticate envelope and upstream callers
                // can distinguish "token refresh failed" from "first 401 from server".
                return chain.proceed(chain.request())
            }
            return chain.proceed(signed.withBearer(refreshed))
        }
        return response
    }

    private fun Request.withBearer(token: String): Request =
        newBuilder().header("Authorization", "Bearer $token").build()

    private fun Response.isFirebaseUnauthorized(): Boolean =
        header("WWW-Authenticate")?.contains("Bearer", ignoreCase = true) == true
}
