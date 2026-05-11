package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKEND-AUTH-01 review-pipeline finding F10.
 *
 * Pre-fix UserRepository.findByUid was @Cacheable("userStatus") and returned a
 * cached Optional<User> wrapping a mutable User reference. Every cache hit
 * handed back the SAME instance, so any caller that mutated the returned User
 * (notably AuthService.recordLogin) corrupted the cache for the 60s TTL.
 *
 * Fix: findByUid delegates to a package-private @Cacheable {@code loadByUid}
 * and clones the result via {@code User.copy()} on the way out — every caller
 * receives an independent snapshot.
 */
class UserRepositoryDefensiveCopyIntegrationTest extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Test
    void findByUid_returnsDefensiveCopy_mutationsDontLeakBack() throws Exception {
        String uid = "u-defcopy";
        User seed = new User();
        seed.setUid(uid);
        seed.setEmail("defcopy@t.com");
        seed.setRole("moderator");
        seed.setStatusEnum(UserStatus.ACTIVE);
        seed.setBlockReason(null);
        seed.setCreatedAt(Timestamp.now());
        seed.setUpdatedAt(Timestamp.now());
        userRepository.save(seed);

        // First call — cache-miss, populates the cache.
        User first = userRepository.findByUid(uid).orElseThrow();
        assertEquals(UserStatus.ACTIVE, first.getStatusEnum());

        // Mutate the returned User aggressively. Pre-F10 this corrupted the cache.
        first.setStatusEnum(UserStatus.BLOCKED);
        first.setBlockReason("LEAKED-via-mutation");
        first.setRole("admin");
        first.setEmail("hijacked@t.com");

        // Second call — cache-hit. Must NOT reflect any of the mutations above.
        User second = userRepository.findByUid(uid).orElseThrow();
        assertEquals(UserStatus.ACTIVE, second.getStatusEnum(),
                "Second findByUid must NOT show mutation from first caller");
        assertNull(second.getBlockReason(),
                "Mutated blockReason on the first instance must not leak into the cache");
        assertEquals("moderator", second.getRole(),
                "Role mutation must not leak into the cache");
        assertEquals("defcopy@t.com", second.getEmail(),
                "Email mutation must not leak into the cache");

        // And: the two instances are NOT the same object.
        assertNotSame(first, second, "findByUid must return distinct User instances");
    }

    @Test
    void findByUid_empty_whenUserDoesNotExist() throws Exception {
        // Smoke check: a non-existent UID returns Optional.empty() — the
        // defensive copy via map(User::copy) must not NPE on the absent path.
        assertTrue(userRepository.findByUid("never-existed").isEmpty());
    }

    // ── F19 — loadByUid must remain package-private ─────────────────────────
    // The docstring says "package-private. Not for direct call by other
    // classes". Pre-F19 the access modifier was actually `public`, so an
    // external caller could trust the docstring, skip the defensive copy,
    // and re-introduce the F10 mutable-shared-reference bug. This test
    // pins the modifier so the next refactor doesn't drift back.

    @Test
    void loadByUid_isPackagePrivate_notPublic() throws NoSuchMethodException {
        java.lang.reflect.Method loadByUid =
                com.albunyaan.tube.repository.UserRepository.class
                        .getDeclaredMethod("loadByUid", String.class);
        int mods = loadByUid.getModifiers();
        assertFalse(java.lang.reflect.Modifier.isPublic(mods),
                "F19: loadByUid must NOT be public — package-private only. "
                + "External callers would bypass User.copy() defensive copy and "
                + "re-introduce the F10 mutable-shared-reference bug.");
        assertFalse(java.lang.reflect.Modifier.isProtected(mods),
                "F19: loadByUid must NOT be protected — package-private only.");
        assertFalse(java.lang.reflect.Modifier.isPrivate(mods),
                "F19: loadByUid must NOT be private — Spring's CGLIB cache "
                + "proxy needs package-private visibility to subclass and "
                + "intercept the @Cacheable call.");
    }
}
