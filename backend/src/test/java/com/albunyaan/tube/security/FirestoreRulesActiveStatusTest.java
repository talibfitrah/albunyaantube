package com.albunyaan.tube.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKEND-AUTH-01 review-pipeline finding F6:
 *
 * Regression guard for {@code firestore.rules}. The {@code isAdmin()} and
 * {@code isModerator()} helpers MUST require {@code status == 'active'} so
 * that a blocked / soft-deleted admin's still-valid ID token cannot bypass
 * the AccountStatusFilter via the direct Firestore SDK during the (up to
 * 1h) token TTL window.
 *
 * The project has no Firebase rules-test SDK wired in (the Java backend
 * doesn't depend on @firebase/rules-unit-testing). Until that exists, this
 * test asserts the active-status check is textually present in the helpers.
 * Weak but better than nothing — a silent regression on this rule would be
 * a privilege escalation hazard.
 */
class FirestoreRulesActiveStatusTest {

    private static final Pattern IS_ADMIN_BLOCK = Pattern.compile(
            "function\\s+isAdmin\\s*\\(\\)\\s*\\{[^}]*?status\\s*==\\s*'active'[^}]*?}",
            Pattern.DOTALL);

    private static final Pattern IS_MODERATOR_BLOCK = Pattern.compile(
            "function\\s+isModerator\\s*\\(\\)\\s*\\{[^}]*?status\\s*==\\s*'active'[^}]*?}",
            Pattern.DOTALL);

    @Test
    void firestoreRules_isAdminHelperChecksActiveStatus() throws Exception {
        String rules = loadRules();
        assertTrue(IS_ADMIN_BLOCK.matcher(rules).find(),
                "isAdmin() rule MUST require status == 'active'. " +
                "Without this, a blocked admin's ID token still passes isAdmin() until token expiry.");
    }

    @Test
    void firestoreRules_isModeratorHelperChecksActiveStatus() throws Exception {
        String rules = loadRules();
        assertTrue(IS_MODERATOR_BLOCK.matcher(rules).find(),
                "isModerator() rule MUST require status == 'active'. " +
                "Without this, a blocked moderator's ID token still passes isModerator() until token expiry.");
    }

    private String loadRules() throws Exception {
        try (InputStream in = new ClassPathResource("firestore.rules").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
