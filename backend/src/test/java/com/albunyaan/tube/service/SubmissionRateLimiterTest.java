package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;

import java.time.*;

import static org.junit.jupiter.api.Assertions.*;

class SubmissionRateLimiterTest {

    @Test void allowsUpToLimit() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) {
            assertNull(rl.tryAcquire("uid"));
        }
    }

    @Test void rejectsAtLimit() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) rl.tryAcquire("uid");
        Long retry = rl.tryAcquire("uid");
        assertNotNull(retry);
        assertEquals(86400L, retry);   // 24h since first hit (all 50 stamped at the same fixed clock)
    }

    @Test void slidingWindowReleasesOldHits() {
        java.util.concurrent.atomic.AtomicReference<Instant> nowRef =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-05-12T10:00:00Z"));
        Clock mut = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return nowRef.get(); }
        };
        var rl = new SubmissionRateLimiter(mut);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) rl.tryAcquire("uid");
        nowRef.set(nowRef.get().plus(Duration.ofHours(25)));
        assertNull(rl.tryAcquire("uid"));
    }

    @Test void perUidIsolation() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        for (int i = 0; i < SubmissionRateLimiter.LIMIT; i++) rl.tryAcquire("uid-A");
        assertNull(rl.tryAcquire("uid-B"));
    }

    @Test void tryAcquireCount_consumesAllSlots_whenWithinLimit() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        // Consume 24 slots in one call (bulk submit with 25 rows after interceptor's 1).
        assertNull(rl.tryAcquire("uid", 24));
        // Only 26 slots left of 50.
        for (int i = 0; i < 26; i++) {
            assertNull(rl.tryAcquire("uid"));
        }
        // 51st acquire fails.
        assertNotNull(rl.tryAcquire("uid"));
    }

    @Test void tryAcquireCount_allOrNothing_whenExceedingLimit() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        // Pre-fill 30 of 50 slots.
        for (int i = 0; i < 30; i++) rl.tryAcquire("uid");
        // Asking for 21 more (would put us at 51) → rejected, NO slots consumed.
        Long retry = rl.tryAcquire("uid", 21);
        assertNotNull(retry);
        // The original 30 are still all that was consumed — 20 slots remain.
        // tryAcquire(uid, 20) must succeed.
        assertNull(rl.tryAcquire("uid", 20));
    }

    @Test void tryAcquireCount_rejectsBlankUid_throwsIAE() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        assertThrows(IllegalArgumentException.class, () -> rl.tryAcquire("", 5));
        assertThrows(IllegalArgumentException.class, () -> rl.tryAcquire(null, 5));
    }

    @Test void tryAcquireCount_rejectsZeroOrNegative_throwsIAE() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-12T10:00:00Z"), ZoneOffset.UTC);
        var rl = new SubmissionRateLimiter(fixed);
        assertThrows(IllegalArgumentException.class, () -> rl.tryAcquire("uid", 0));
        assertThrows(IllegalArgumentException.class, () -> rl.tryAcquire("uid", -1));
    }
}
