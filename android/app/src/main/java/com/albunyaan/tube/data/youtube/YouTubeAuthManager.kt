package com.albunyaan.tube.data.youtube

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "YouTubeAuthManager"
private const val SCOPE_YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"

// ---------------------------------------------------------------------------
// Result hierarchy
// ---------------------------------------------------------------------------

/**
 * Outcome of a YouTube OAuth 2.0 authorization attempt.
 *
 * - [Granted]      — we have an access token; proceed with API calls.
 * - [NeedsConsent] — user must complete the Google consent flow via the
 *                    supplied [PendingIntent]; call [YouTubeAuthManager.authorizeFromConsentResult]
 *                    with the Intent returned from that activity result.
 * - [Denied]       — authorization was refused or revoked.
 * - [Failed]       — an unexpected error occurred; inspect [error] for details.
 */
sealed interface AuthResult {
    data class Granted(val accessToken: String) : AuthResult
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthResult
    data object Denied : AuthResult
    data class Failed(val error: Throwable) : AuthResult
}

// ---------------------------------------------------------------------------
// Interface seam
// ---------------------------------------------------------------------------

/**
 * Obtains an OAuth 2.0 access token for [SCOPE_YOUTUBE_READONLY] incrementally
 * (i.e. only when the user initiates an import, never at sign-in).
 *
 * The interface exists so the repository/ViewModel layer can be unit-tested
 * against [AuthResult] fakes without touching the Google Identity Services SDK.
 *
 * DI binding is deferred to task B15; [GoogleYouTubeAuthManager] is the
 * production implementation.
 */
interface YouTubeAuthManager {
    /**
     * Request authorization.  Returns immediately if a token is already cached;
     * otherwise returns [AuthResult.NeedsConsent] whose [PendingIntent] must be
     * launched by the caller (e.g. from a Fragment) so the user can approve the
     * consent screen.
     */
    suspend fun authorize(): AuthResult

    /**
     * Complete authorization after the user finishes the consent flow.
     *
     * Call this with the [Intent] returned in [android.app.Activity.onActivityResult]
     * (or the ActivityResult launcher callback) after launching the PendingIntent
     * from [AuthResult.NeedsConsent].
     */
    suspend fun authorizeFromConsentResult(data: Intent?): AuthResult
}

// ---------------------------------------------------------------------------
// Production implementation
// ---------------------------------------------------------------------------

/**
 * Wraps [com.google.android.gms.auth.api.identity.AuthorizationClient] to
 * provide incremental `youtube.readonly` authorization.
 *
 * All Google Identity Services calls are confined to this class so the
 * [YouTubeAuthManager] interface stays unit-testable.
 *
 * Note: actual Hilt binding ([dagger.hilt.android.InstallIn] / [@Binds]) is
 * added in task B15 to avoid touching DI modules prematurely.
 */
@Singleton
class GoogleYouTubeAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : YouTubeAuthManager {

    private val authorizationClient by lazy {
        Identity.getAuthorizationClient(context)
    }

    private val authorizationRequest: AuthorizationRequest by lazy {
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_YOUTUBE_READONLY)))
            .build()
    }

    override suspend fun authorize(): AuthResult {
        return try {
            val result: AuthorizationResult = authorizationClient
                .authorize(authorizationRequest)
                .await()
            mapResult(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // preserve structured-concurrency contract
        } catch (e: Exception) {
            Log.w(TAG, "authorize() failed", e)
            AuthResult.Failed(e)
        }
    }

    override suspend fun authorizeFromConsentResult(data: Intent?): AuthResult {
        return try {
            val result: AuthorizationResult = authorizationClient
                .getAuthorizationResultFromIntent(data)
            mapResult(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "authorizeFromConsentResult() failed", e)
            AuthResult.Failed(e)
        }
    }

    // -----------------------------------------------------------------------

    private fun mapResult(result: AuthorizationResult): AuthResult {
        return when {
            result.hasResolution() -> {
                val pi = result.pendingIntent
                if (pi != null) {
                    AuthResult.NeedsConsent(pi)
                } else {
                    // hasResolution() true but no PendingIntent is unexpected — treat as denied
                    Log.w(TAG, "hasResolution() but pendingIntent is null — treating as Denied")
                    AuthResult.Denied
                }
            }
            result.accessToken != null -> AuthResult.Granted(result.accessToken!!)
            else -> {
                Log.w(TAG, "Authorization result had neither resolution nor access token")
                AuthResult.Denied
            }
        }
    }
}
