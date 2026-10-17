package com.albunyaan.tube.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Per-uid sliding window for {@code POST /api/playback/solve} (spec R29): 20 a minute and 300 an hour, one deque per
 * uid checked against both. The {@link ProfileUpdateRateLimiter} shape: Caffeine for cold-uid eviction, an atomic
 * {@code compute} per uid. In memory only: a restart forgets it, which only ever errs towards allowing.
 */
@Component
public class PlaybackSolveRateLimiter {

    public static final int PER_MINUTE = 20;
    public static final int PER_HOUR = 300;
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final Clock clock;
    // ponytail: per-instance memory, so N backend instances allow N x the limit; single instance in prod, Redis if that changes
    private final Cache<String, Deque<Instant>> hits = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(100_000)
            .build();

    public PlaybackSolveRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** {@code null} = allowed and counted; otherwise the whole seconds until a slot frees (at least 1). */
    public Long tryAcquire(String uid) {
        Instant now = clock.instant();
        Long[] retryAfter = {null};
        hits.asMap().compute(uid, (key, existing) -> {
            Deque<Instant> dq = existing != null ? existing : new ArrayDeque<>();
            while (!dq.isEmpty() && !dq.peekFirst().isAfter(now.minus(HOUR))) dq.pollFirst();
            Instant oldestInMinute = dq.stream().filter(t -> t.isAfter(now.minus(MINUTE))).findFirst().orElse(null);
            long inMinute = dq.stream().filter(t -> t.isAfter(now.minus(MINUTE))).count();
            if (dq.size() >= PER_HOUR) {
                retryAfter[0] = secondsUntil(dq.peekFirst().plus(HOUR), now);
            } else if (inMinute >= PER_MINUTE) {
                retryAfter[0] = secondsUntil(oldestInMinute.plus(MINUTE), now);
            } else {
                dq.addLast(now);
            }
            return dq;
        });
        return retryAfter[0];
    }

    private static long secondsUntil(Instant then, Instant now) {
        return Math.max(1, (Duration.between(now, then).toMillis() + 999) / 1000);
    }
}
