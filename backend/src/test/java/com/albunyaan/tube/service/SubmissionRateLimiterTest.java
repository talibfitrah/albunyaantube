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
}
