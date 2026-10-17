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
        assertThat(retryAfter).isBetween(1L, 3600L - 15 * 61 + 1);   // the oldest (t=0) frees at t=3600
    }

    @Test void usersAreIndependent() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("a");
        assertThat(limiter.tryAcquire("a")).isNotNull();
        assertThat(limiter.tryAcquire("b")).isNull();
    }
}
