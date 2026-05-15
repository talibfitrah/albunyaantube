package com.albunyaan.tube.auth

import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException

/**
 * Plan B (ANDROID-AUTH-01) T2: maps [Throwable] from the Firebase SDK to a
 * stable [AuthErrorCode] the UI can switch on.
 *
 * Kept as a top-level function (not a class) because there is no state. Tested
 * separately via [AuthErrorMapperTest].
 */
fun Throwable.toAuthErrorCode(): AuthErrorCode = when (this) {
    is FirebaseNetworkException -> AuthErrorCode.NETWORK
    is FirebaseTooManyRequestsException -> AuthErrorCode.TOO_MANY_REQUESTS
    is FirebaseAuthException -> when (errorCode) {
        "ERROR_INVALID_EMAIL" -> AuthErrorCode.INVALID_EMAIL
        "ERROR_WRONG_PASSWORD" -> AuthErrorCode.WRONG_PASSWORD
        "ERROR_USER_NOT_FOUND" -> AuthErrorCode.USER_NOT_FOUND
        "ERROR_USER_DISABLED" -> AuthErrorCode.USER_DISABLED
        "ERROR_EMAIL_ALREADY_IN_USE" -> AuthErrorCode.EMAIL_ALREADY_IN_USE
        "ERROR_WEAK_PASSWORD" -> AuthErrorCode.WEAK_PASSWORD
        "ERROR_INVALID_CREDENTIAL", "ERROR_INVALID_USER_TOKEN" -> AuthErrorCode.INVALID_CREDENTIAL
        // Firebase Auth surfaces transient network failures as a string
        // errorCode here rather than a FirebaseNetworkException, so without
        // this mapping the user would see the generic "Something went wrong"
        // instead of the retry-hint mapped onto AuthErrorCode.NETWORK
        // (cubic R5 P2).
        "ERROR_NETWORK_REQUEST_FAILED" -> AuthErrorCode.NETWORK
        else -> AuthErrorCode.UNKNOWN
    }
    else -> AuthErrorCode.UNKNOWN
}
