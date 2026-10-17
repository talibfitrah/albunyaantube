package com.albunyaan.tube.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /** Hotfix guard: catalog collections must not be readable straight from Firestore except by moderators. */
    @Test
    void firestoreRules_catalogCollectionsReadableOnlyByModerators() throws Exception {
        String rules = loadRules();
        for (String c : new String[] {"categories", "channels", "playlists", "videos"}) {
            assertEquals(List.of("isModerator()"), readConditions(rules, c),
                    "/" + c + "/: every allow covering read, get or list MUST be 'if isModerator()'");
        }
        assertEquals(List.of(), publicReads(rules), "no read, get or list rule may be 'if true' anywhere");
    }

    /** The guard trips on the slips the gstack review named (HG appendix): later get/list rules and combined verbs. */
    @ParameterizedTest
    @ValueSource(strings = {
            "allow get: if true;",
            "allow list: if isAuthenticated();",
            "allow read, write: if true;",
            "allow read: if isAuthenticated();"})
    void theGuardCatchesASecondReadRuleInACatalogBlock(String slip) throws Exception {
        String rules = loadRules().replaceFirst("(match\\s+/channels/\\{\\w+\\}\\s*\\{)", "$1\n      " + slip);
        assertNotEquals(List.of("isModerator()"), readConditions(rules, "channels"), slip);
    }

    static List<String> readConditions(String rules, String collection) {
        Matcher block = Pattern.compile("match\\s+/" + collection + "/\\{\\w+\\}\\s*\\{([^}]*)\\}").matcher(rules);
        assertTrue(block.find(), "no /" + collection + "/ block");
        return reads(block.group(1), false);
    }

    static List<String> publicReads(String rules) {
        return reads(rules, true);
    }

    private static List<String> reads(String text, boolean onlyTrue) {
        List<String> out = new ArrayList<>();
        Matcher allow = Pattern.compile("allow\\s+([a-z,\\s]+?)\\s*:\\s*if\\s+([^;]+);").matcher(text);
        while (allow.find()) {
            boolean read = Arrays.stream(allow.group(1).split(",")).map(String::trim)
                    .anyMatch(v -> v.equals("read") || v.equals("get") || v.equals("list"));
            String condition = allow.group(2).trim();
            if (read && (!onlyTrue || condition.equals("true"))) out.add(condition);
        }
        return out;
    }

    private String loadRules() throws Exception {
        try (InputStream in = new ClassPathResource("firestore.rules").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
