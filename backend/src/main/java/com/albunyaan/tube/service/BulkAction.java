package com.albunyaan.tube.service;

/**
 * Plan F (ADMIN-USER-01) — bulk user-management action discriminator.
 * RECOVER deliberately permits admin targets (re-activating a soft-deleted admin).
 * BLOCK / DELETE / REVOKE_SESSIONS refuse admin targets — see F5.
 */
public enum BulkAction {
    BLOCK,
    DELETE,
    RECOVER,
    REVOKE_SESSIONS
}
