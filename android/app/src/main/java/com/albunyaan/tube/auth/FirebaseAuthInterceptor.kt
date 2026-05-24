package com.albunyaan.tube.auth

import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /**
     * Host that the Firebase Bearer is allowed to leak to. Computed once from
     * [BuildConfig.API_BASE_URL] (the backend the token is actually for). Any
     * request whose URL host does NOT match this is passed through unsigned —
     * stops the token leaking to third parties (api.github.com, raw.githubuser
     * content.com, image CDNs, etc.) which would otherwise return 401 because
     * a Firebase JWT means nothing to them. Overridable in tests so unit tests
     * targeting MockWebServer (localhost) still exercise the signing path.
     *
     * Scope is per-HOST, not per-origin: [HttpUrl.host] strips the port and
     * scheme, so `http://10.0.2.2:8080` and `http://10.0.2.2:9090` both match.
     * Treat as intentional — one machine, two services on different ports
     * share the trust boundary in this app's deployment model. If a colocated
     * service on a different port ever ships in production, narrow the check
     * to host+port at that point.
     *
     * Fail-fast on malformed URL: if [BuildConfig.API_BASE_URL] is set without
     * a scheme (e.g. `10.0.2.2:8080/` in local.properties), [toHttpUrlOrNull]
     * returns null. Pre-fix that silently produced `apiHost = ""`, which never
     * matches any real request → the interceptor stops attaching Bearer to
     * EVERY endpoint and the whole authenticated surface 401s with no obvious
     * cause. Crashing at init makes the misconfig visible immediately (cubic
     * R1 P2).
     */
    // @Volatile: production sets this once during Hilt singleton construction
    // (single-threaded, happens-before via class init), but tests reassign it
    // from the test thread before sharing the interceptor with the OkHttp
    // dispatcher pool. @Volatile makes the publication explicit so a Kotlin/JIT
    // reordering doesn't surface a stale value on the dispatcher thread
    // (cubic R3 P3 defensive).
    @Volatile
    @VisibleForTesting
    internal var apiHost: String = BuildConfig.API_BASE_URL.toHttpUrlOrNull()?.host
        ?: error(
            "BuildConfig.API_BASE_URL is not a valid URL: '${BuildConfig.API_BASE_URL}'. " +
                "Check local.properties — the value must include a scheme (http:// or https://)."
        )

    override fun intercept(chain: Interceptor.Chain): Response {
        // Host scope: never attach the Bearer to anything but our backend.
        if (chain.request().url.host != apiHost) {
            return chain.proceed(chain.request())
        }
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
            // Cubic R-final5 P2 — singleflight the force-refresh.
            // Pre-fix N parallel 401s each entered getIdToken(true) and
            // serialised on the SDK's internal task bridge anyway, but each
            // dispatcher thread blocked separately. The Mutex coalesces all
            // concurrent refreshes; once one succeeds the cached token
            // returned by the next withLock holder is the just-refreshed
            // value, so the second/third callers don't pay the network round
            // trip again.
            val refreshed = try {
                runBlocking {
                    withTimeoutOrNull(TOKEN_REFRESH_TIMEOUT_MS) {
                        refreshMutex.withLock {
                            // Cubic round 1 P1: cross-account leak guard.
                            // If the active Firebase user changed between
                            // outer capture (line 43) and this 401 retry
                            // (sign-out then sign-in as a different account),
                            // do NOT replay the original request with the new
                            // user's bearer. Return null to propagate as a
                            // refresh failure (handled below by surfacing the
                            // original 401 per Cubic R7 P1). Force-refreshing
                            // would otherwise silently rebind the request to
                            // the wrong identity, leaking one account's
                            // request to another user's auth.
                            if (auth.currentUser?.uid != user.uid) {
                                null
                            } else {
                                // Re-check inside the lock — a prior holder may
                                // have just refreshed; getIdToken(false) returns
                                // the cached fresh token instantly.
                                val cached = user.getIdToken(false).await().token
                                if (cached != null && cached != token) {
                                    cached
                                } else {
                                    user.getIdToken(true).await().token
                                }
                            }
                        }
                    }
                }
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

        /**
         * Cubic R-final5 P2 — process-wide singleflight gate for the
         * force-refresh path. Static so all interceptor instances (only
         * one in practice via @Singleton, but the SDK caller could
         * theoretically have parallel chains) coalesce.
         */
        private val refreshMutex = Mutex()
    }
}
