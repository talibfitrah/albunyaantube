package com.albunyaan.tube.auth

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plan B (ANDROID-AUTH-01) T3: observes 403 responses for the Plan A
 * account-lifecycle envelope, signs the user out locally, and emits a
 * one-shot event the UI consumes (T6) to show a terminal dialog.
 *
 * Plan A backend envelope (see `GlobalExceptionHandler`):
 *     { "code": "ACCOUNT_BLOCKED" | "ACCOUNT_DELETED", "message": "..." }
 *
 * Only fires on /api/admin/... in practice — /api/v1/... is exempt from
 * Plan A's FirebaseAuthFilter server-side, so the server never emits these
 * codes there.
 */
@Singleton
class AccountStatusInterceptor @Inject constructor(
    private val firebaseAuth: FirebaseAuth,
    private val emitter: AccountStatusEmitter,
    moshi: Moshi,
) : Interceptor {
    // AUTH-INTERCEPT-DECOUPLE-01 — the previous Provider<AccountRepository>
    // dependency is gone. AccountRepositoryImpl now collects accountStatusEvents
    // directly (wired via AccountModule) and clears its state on any terminal
    // event we emit below. Decoupling removes the Hilt construction cycle
    // (AccountStatusInterceptor → OkHttp → AccountService → AccountRepository
    // → AccountStatusInterceptor) — Provider broke it lazily but cold-start
    // network calls fired from Application.onCreate could still block on
    // provider.get() while the DI graph was still building.

    private val adapter = moshi.adapter(ApiErrorEnvelope::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (response.code != 403) return response

        // Cubic R7 P0 — restrict to our backend's admin/v1 namespaces.
        //
        // Pre-fix the interceptor matched the ACCOUNT_BLOCKED/ACCOUNT_DELETED
        // envelope on ANY 403 the OkHttp pipeline ever produced. A future
        // third-party endpoint, proxy, or any path returning the matching
        // JSON shape would forcibly log the user out across the entire app.
        // Pinning the URL prefix to /api/admin/ and /api/v1/ scopes the
        // sign-out to our backend's lifecycle envelopes only.
        val path = request.url.encodedPath
        // Cubic R9 P1 — /api/account/* must be in the allow-list. AccountController
        // is the sole path the splash hits via AccountRepositoryImpl.fetchMe(); a
        // blocked user signing in cold previously got 403 returned as HttpException
        // → SplashRouter saw accountStatus == null → bounced to sign-in silently,
        // FirebaseAuth.signOut() never ran, the terminal-dialog event was never
        // emitted. The user looped back through splash → /me 403 → sign-in with no
        // UX. Adding /api/account/ restores the R7 P0 envelope-pinning intent.
        //
        // Cubic R-final4 P2 — /api/share-metadata/* must also be in the
        // allow-list. R-final2 P1 switched FirebaseAuthFilter to method-based
        // fail-closed (POST/PUT/PATCH/DELETE always rejected for
        // blocked/deleted users) and explicitly cited share-metadata as the
        // route that motivated the broader gating. A blocked user issuing a
        // share-metadata POST now reliably gets the ACCOUNT_BLOCKED envelope
        // from the server, but without this prefix the client silently
        // ignored it — the user stayed in a "blocked but app behaves normal"
        // state until their next admin/v1/account request.
        if (!path.startsWith("/api/admin/") &&
            !path.startsWith("/api/v1/") &&
            !path.startsWith("/api/account/") &&
            !path.startsWith("/api/share-metadata/")) {
            return response
        }

        // peekBody(N) bounds the read so a malicious / huge response body
        // cannot OOM us; 1024 bytes is comfortably larger than Plan A's
        // 2-field error envelope.
        val peeked = try {
            response.peekBody(MAX_PEEK_BYTES).string()
        } catch (e: IOException) {
            Log.d(TAG, "could not peek 403 body: ${e.message}")
            return response
        }

        val envelope = try {
            adapter.fromJson(peeked)
        } catch (_: JsonDataException) {
            null
        } catch (_: IOException) {
            null
        }

        val event = when (envelope?.code) {
            "ACCOUNT_BLOCKED" -> AccountStatusEvent.Blocked
            "ACCOUNT_DELETED" -> AccountStatusEvent.Deleted
            else -> return response
        }

        // Cubic R7 P2 — comment correction.
        //
        // signOut runs before emit so the in-process AccountRepository state
        // reset and FirebaseAuth.signOut() complete before the UI consumes
        // the AccountStatusEvent. The previous comment claimed the
        // AuthStateListener "flips authState" before the dialog event
        // dispatches; in fact AuthStateListener posts to the main looper
        // and may not have run by the time MainActivity collects the
        // emitter flow. Ordering here is a best-effort to minimise the
        // "blocked but still signed in" window; the UI must still tolerate
        // racing emissions (T6 already does — it routes terminal events
        // regardless of the live authState).
        firebaseAuth.signOut()
        // AUTH-INTERCEPT-DECOUPLE-01 — emit-only. AccountRepositoryImpl's
        // bus collector (wired in AccountModule) calls its own signOut on
        // terminal events, replacing the Provider-based imperative call.
        emitter.emit(event)
        return response
    }

    @JsonClass(generateAdapter = true)
    data class ApiErrorEnvelope(val code: String?, val message: String?)

    companion object {
        private const val TAG = "AccountStatusInterceptor"
        private const val MAX_PEEK_BYTES = 1024L
    }
}
