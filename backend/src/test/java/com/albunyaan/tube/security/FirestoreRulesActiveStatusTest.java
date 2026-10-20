package com.albunyaan.tube.security;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
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

    /** Hotfix guard: catalog collections must not be publicly readable straight from Firestore. */
    @Test
    void firestoreRules_catalogCollectionsReadableOnlyByModerators() throws Exception {
        String rules = loadRules();
        for (String c : new String[] {"categories", "channels", "playlists", "videos"}) {
            assertTrue(Pattern.compile("match\\s+/" + c + "/\\{\\w+\\}\\s*\\{[^}]*?allow\\s+read:\\s*if\\s+isModerator\\(\\)\\s*;")
                            .matcher(rules).find(),
                    "/" + c + "/ MUST be 'allow read: if isModerator();'");
        }
        assertFalse(Pattern.compile("allow\\s+read:\\s*if\\s+true").matcher(rules).find(),
                "No 'allow read: if true' (public Firestore read) may appear in firestore.rules");
    }

    /**
     * Security review H1: content docs are created only by the backend (Admin SDK, which bypasses
     * rules) after approval. A moderator token must not create a channel, playlist or video doc
     * directly, already APPROVED and in any category. Rules OR their allow statements, so no
     * statement in the block may grant create (or write) on any condition but false.
     */
    @Test
    void firestoreRules_contentCollectionsDenyClientCreates() throws Exception {
        String rules = loadRules();
        Pattern grantsCreate = Pattern.compile("allow\\s+(write\\b|[\\w, ]*\\bcreate\\b)[^;]*?if\\s+(?!false\\s*;)");
        for (String c : new String[] {"channels", "playlists", "videos"}) {
            Matcher m = Pattern.compile("match\\s+/" + c + "/\\{\\w+\\}\\s*\\{([^}]*)}").matcher(rules);
            assertTrue(m.find(), "/" + c + "/ match block not found");
            String block = m.group(1);
            assertTrue(Pattern.compile("allow\\s+create:\\s*if\\s+false\\s*;").matcher(block).find(),
                    "/" + c + "/ MUST be 'allow create: if false;'");
            assertFalse(grantsCreate.matcher(block).find(),
                    "/" + c + "/ must not grant create to any client: " + block.trim());
        }
    }

    private String loadRules() throws Exception {
        try (InputStream in = new ClassPathResource("firestore.rules").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
