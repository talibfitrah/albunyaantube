package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec R29 / §6.3: 20 solves a minute and 300 an hour per signed-in user, then 429 with Retry-After in seconds. */
class PlaybackSolveRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-17T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final PlaybackSolveRateLimiter limiter = new PlaybackSolveRateLimiter(clock);

    private void advance(Duration d) { now.set(now.get().plus(d)); }

    @Test void twentyInAMinutePassAndTheTwentyFirstWaitsForTheOldest() {
        for (int i = 0; i < 20; i++) { assertThat(limiter.tryAcquire("uid")).isNull(); advance(Duration.ofSeconds(1)); }
        Long retryAfter = limiter.tryAcquire("uid");
        assertThat(retryAfter).isEqualTo(40L);    // the first slot (t=0) frees at t=60; now is t=20
    }

    @Test void aMinuteLaterTheNextTwentyPass() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("uid");
        advance(Duration.ofSeconds(61));
        for (int i = 0; i < 20; i++) assertThat(limiter.tryAcquire("uid")).isNull();
    }

    @Test void threeHundredInAnHourPassAndTheNextWaitsForTheHourToSlide() {
        for (int burst = 0; burst < 15; burst++) {
            for (int i = 0; i < 20; i++) assertThat(limiter.tryAcquire("uid")).isNull();
            advance(Duration.ofSeconds(61));
        }
        Long retryAfter = limiter.tryAcquire("uid");
        assertThat(retryAfter).isNotNull();
        assertThat(retryAfter).isEqualTo(2685L);   // now is t=915; the oldest (t=0) frees at t=3600
    }

    @Test void usersAreIndependent() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a")).isEqualTo(60L);
        assertThat(limiter.tryAcquire("b")).isNull();
    }

    @Test void retryAfterIsTheMaxOfHourAndMinuteWaits() {
        for (int burst = 0; burst < 14; burst++) {                // 280 hits, the first burst at t=0 (the oldest)
            for (int i = 0; i < 20; i++) assertThat(limiter.tryAcquire("u")).isNull();
            advance(Duration.ofSeconds(burst == 13 ? 3570 - 13 * 61 : 61));
        }                                                         // now t=3570 (59.5 min)
        for (int i = 0; i < 20; i++) limiter.tryAcquire("u");     // 300 total, 20 in the minute
        advance(Duration.ofSeconds(10));                          // t=3580
        // hour frees at t=3600 (20 s); minute frees at t=3630 (50 s)
        assertThat(limiter.tryAcquire("u")).isEqualTo(50L);
    }

    @Test void refusedRequestsDoNotConsumeASlot() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("u");
        for (int i = 0; i < 50; i++) assertThat(limiter.tryAcquire("u")).isNotNull();
        advance(Duration.ofSeconds(61));
        for (int i = 0; i < 20; i++) assertThat(limiter.tryAcquire("u")).isNull();
        assertThat(limiter.tryAcquire("u")).isNotNull();
    }

    @Test void theExactSixtySecondBoundaryFreesTheSlot() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("u");
        advance(Duration.ofSeconds(59));
        assertThat(limiter.tryAcquire("u")).isEqualTo(1L);
        advance(Duration.ofSeconds(1));                           // exactly t=60
        assertThat(limiter.tryAcquire("u")).isNull();
    }

    @Test void concurrentCallsOnOneUidAdmitExactlyTwenty() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(50);
        var start = new java.util.concurrent.CountDownLatch(1);
        var admitted = new java.util.concurrent.atomic.AtomicInteger();
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int i = 0; i < 50; i++) futures.add(pool.submit(() -> {
            start.await();
            if (limiter.tryAcquire("u") == null) admitted.incrementAndGet();
            return null;
        }));
        start.countDown();
        for (var f : futures) f.get(10, java.util.concurrent.TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(admitted.get()).isEqualTo(20);
    }

    @Test void nullUidIsRejectedClearly() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limiter.tryAcquire(null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("uid");
    }
}
