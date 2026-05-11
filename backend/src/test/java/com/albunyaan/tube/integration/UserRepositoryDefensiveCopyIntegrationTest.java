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

    // ── F21 — save() evicts the userStatus cache so post-save reads are fresh ─
    //
    // Pre-F10 the cache was self-coherent because findByUid handed back the
    // SAME mutable reference: AuthService.recordLogin (read → mutate → save)
    // ALSO mutated the cached object. F10 fixed the mutable-shared-reference
    // bug with a defensive copy, but in doing so created a cache-coherence
    // regression: callers that read, mutate the COPY, and save have an
    // up-to-date Firestore document but a stale cache entry for the 60s TTL.
    // F21 fixes the regression by adding @CacheEvict to save().

    @Test
    void recordLogin_evictsCacheSoNextReadIsFresh() throws Exception {
        // Seed a user with no lastLoginAt.
        String uid = "u-recordlogin";
        User seed = new User();
        seed.setUid(uid);
        seed.setEmail("login@t.com");
        seed.setRole("moderator");
        seed.setStatusEnum(UserStatus.ACTIVE);
        seed.setCreatedAt(Timestamp.now());
        seed.setUpdatedAt(Timestamp.now());
        userRepository.save(seed);

        // First read populates the cache with lastLoginAt == null.
        User firstRead = userRepository.findByUid(uid).orElseThrow();
        assertNull(firstRead.getLastLoginAt(),
                "Sanity: seeded user has no lastLoginAt");

        // recordLogin: read → mutate → save. Post-save the cache for `uid`
        // must be evicted so the next read returns the fresh lastLoginAt.
        User toUpdate = userRepository.findByUid(uid).orElseThrow();
        toUpdate.recordLogin();
        userRepository.save(toUpdate);

        // Second read — F21 guarantees this hits Firestore (cache evicted)
        // and returns the fresh lastLoginAt. Pre-F21 the stale Optional
        // wrapping a User with lastLoginAt == null would be returned.
        User afterLogin = userRepository.findByUid(uid).orElseThrow();
        assertNotNull(afterLogin.getLastLoginAt(),
                "F21: post-save findByUid must return fresh lastLoginAt — "
                + "save() must @CacheEvict to prevent the F10 defensive-copy "
                + "regression from leaving the cache stale.");
    }

    @Test
    void save_evictsCacheForAffectedUid_butLeavesOtherUidsUntouched() throws Exception {
        // Pin the @CacheEvict key = "#user.uid" semantics: saving uid-A must
        // evict only uid-A's entry, not uid-B's.
        String uidA = "u-save-evict-A";
        String uidB = "u-save-evict-B";

        User a = new User();
        a.setUid(uidA);
        a.setEmail("a@t.com");
        a.setRole("user");
        a.setStatusEnum(UserStatus.ACTIVE);
        a.setCreatedAt(Timestamp.now());
        a.setUpdatedAt(Timestamp.now());
        userRepository.save(a);

        User b = new User();
        b.setUid(uidB);
        b.setEmail("b@t.com");
        b.setRole("user");
        b.setStatusEnum(UserStatus.ACTIVE);
        b.setCreatedAt(Timestamp.now());
        b.setUpdatedAt(Timestamp.now());
        userRepository.save(b);

        // Prime both cache entries.
        userRepository.findByUid(uidA);
        userRepository.findByUid(uidB);
        var cache = cacheManager.getCache("userStatus");
        assertNotNull(cache);
        assertNotNull(cache.get(uidA), "Sanity: uid-A primed");
        assertNotNull(cache.get(uidB), "Sanity: uid-B primed");

        // Save uid-A only.
        User aReread = userRepository.findByUid(uidA).orElseThrow();
        aReread.setDisplayName("changed");
        userRepository.save(aReread);

        // F21: uid-A's entry must be evicted; uid-B's must remain.
        assertNull(cache.get(uidA),
                "F21: save(A) MUST evict A's cache entry");
        assertNotNull(cache.get(uidB),
                "F21: save(A) must NOT touch B's cache entry — "
                + "the @CacheEvict key is bound to #user.uid");
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
