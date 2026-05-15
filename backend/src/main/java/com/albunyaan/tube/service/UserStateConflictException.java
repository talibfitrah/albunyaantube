package com.albunyaan.tube.service;

/**
 * Cubic R6 P2 — typed replacement for the brittle {@code msg.contains(...)}
 * routing in {@link BulkUserService#classify(String)}.
 *
 * <p>Pre-fix: {@code AuthService} threw plain {@link IllegalStateException}s
 * with human-readable messages ("User is not in BLOCKED status: ..."), and
 * {@code BulkUserService.classify} string-matched the lowercased message text
 * to recover a stable reason code for the i18n keys
 * ({@code users.bulk.reason.blocked_cannot_delete}, etc.). Any rename of
 * those internal exception messages silently downgraded the failure to the
 * generic {@code invalid_state}, breaking the bulk-action UX without a test
 * signal.
 *
 * <p>Post-fix: the throw sites carry a {@link ReasonCode} enum. Bulk-action
 * classification reads {@link #getReasonCode()} directly; the message text
 * becomes a free-form developer aid, no longer load-bearing.
 *
 * <p>The class extends {@link IllegalStateException} so any pre-existing
 * {@code catch (IllegalStateException)} blocks (and the broad {@code Exception}
 * catch in {@link BulkUserService}) keep working without source changes.
 */
public class UserStateConflictException extends IllegalStateException {

    public enum ReasonCode {
        /** softDeleteUser called on a BLOCKED target — must unblock first. */
        BLOCKED_CANNOT_DELETE,
        /** blockUser called on a DELETED target — must recover first. */
        DELETED_CANNOT_BLOCK,
        /** recoverUser(unblock arm) called on a non-BLOCKED target. */
        NOT_BLOCKED,
        /** recoverUser(undelete arm) called on a non-DELETED target. */
        NOT_DELETED,
    }

    private final ReasonCode reasonCode;

    public UserStateConflictException(ReasonCode reasonCode, String message) {
        super(message);
        this.reasonCode = reasonCode;
    }

    public ReasonCode getReasonCode() {
        return reasonCode;
    }
}
