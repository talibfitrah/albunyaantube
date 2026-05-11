package com.albunyaan.tube.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan B (ANDROID-AUTH-01) T2: covers every entry in [Throwable.toAuthErrorCode],
 * plus the unknown-error fallback.
 */
class AuthErrorMapperTest {

    @Test fun `invalid email maps to INVALID_EMAIL`() =
        assertEquals(AuthErrorCode.INVALID_EMAIL, FirebaseAuthException("ERROR_INVALID_EMAIL", "x").toAuthErrorCode())

    @Test fun `wrong password maps to WRONG_PASSWORD`() =
        assertEquals(AuthErrorCode.WRONG_PASSWORD, FirebaseAuthException("ERROR_WRONG_PASSWORD", "x").toAuthErrorCode())

    @Test fun `user not found maps to USER_NOT_FOUND`() =
        assertEquals(AuthErrorCode.USER_NOT_FOUND, FirebaseAuthException("ERROR_USER_NOT_FOUND", "x").toAuthErrorCode())

    /** Plan A's BLOCKED status disables the Firebase user; this is how it surfaces at sign-in. */
    @Test fun `user disabled maps to USER_DISABLED`() =
        assertEquals(AuthErrorCode.USER_DISABLED, FirebaseAuthException("ERROR_USER_DISABLED", "x").toAuthErrorCode())

    @Test fun `email already in use maps to EMAIL_ALREADY_IN_USE`() =
        assertEquals(AuthErrorCode.EMAIL_ALREADY_IN_USE, FirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE", "x").toAuthErrorCode())

    @Test fun `weak password maps to WEAK_PASSWORD`() =
        assertEquals(AuthErrorCode.WEAK_PASSWORD, FirebaseAuthException("ERROR_WEAK_PASSWORD", "x").toAuthErrorCode())

    @Test fun `invalid credential maps to INVALID_CREDENTIAL`() =
        assertEquals(AuthErrorCode.INVALID_CREDENTIAL, FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "x").toAuthErrorCode())

    @Test fun `invalid user token maps to INVALID_CREDENTIAL`() =
        assertEquals(AuthErrorCode.INVALID_CREDENTIAL, FirebaseAuthException("ERROR_INVALID_USER_TOKEN", "x").toAuthErrorCode())

    @Test fun `unknown auth code maps to UNKNOWN`() =
        assertEquals(AuthErrorCode.UNKNOWN, FirebaseAuthException("ERROR_BRAND_NEW_CODE", "x").toAuthErrorCode())

    @Test fun `FirebaseNetworkException maps to NETWORK`() =
        assertEquals(AuthErrorCode.NETWORK, FirebaseNetworkException("offline").toAuthErrorCode())

    @Test fun `FirebaseTooManyRequestsException maps to TOO_MANY_REQUESTS`() =
        assertEquals(AuthErrorCode.TOO_MANY_REQUESTS, FirebaseTooManyRequestsException("rate limited").toAuthErrorCode())

    @Test fun `arbitrary throwable maps to UNKNOWN`() =
        assertEquals(AuthErrorCode.UNKNOWN, RuntimeException("???").toAuthErrorCode())
}
