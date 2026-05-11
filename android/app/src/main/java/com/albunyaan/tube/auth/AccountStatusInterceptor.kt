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

    private val adapter = moshi.adapter(ApiErrorEnvelope::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code != 403) return response

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

        // Order matters: signOut BEFORE emit. The Firebase AuthStateListener
        // posts to the main looper and flips authState to SignedOut; if we
        // emit() first, T6's MainActivity collector receives the dialog event
        // while authState still reads SignedIn, creating a transient "blocked
        // but logged in" UI state. Doing signOut first lets the listener fire
        // before the dialog renders.
        firebaseAuth.signOut()
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
