package com.albunyaan.tube.auth

/**
 * Plan B (ANDROID-AUTH-01): one-shot account-status signal emitted by the
 * [AccountStatusInterceptor] (T3) when the backend returns a Plan A 403 envelope.
 *
 * Consumed by `MainActivity` (T6) to show the user a terminal dialog and
 * navigate back to sign-in. Emitted on a buffered, DROP_OLDEST [kotlinx.coroutines.flow.SharedFlow]
 * so the interceptor thread never blocks on the consumer.
 */
sealed interface AccountStatusEvent {
    data object Blocked : AccountStatusEvent
    data object Deleted : AccountStatusEvent
}
