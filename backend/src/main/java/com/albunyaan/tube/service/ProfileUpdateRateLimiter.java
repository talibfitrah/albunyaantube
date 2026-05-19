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
 * Plan G B5 — per-uid sliding-window rate limiter for profile updates.
 * 10 updates per hour. In-memory only; will not survive process restart
 * (acceptable for pre-release scale; migrate to Redis if multi-instance).
 *
 * <p>Mirrors {@link SubmissionRateLimiter} exactly: Caffeine cache for
 * automatic eviction of cold uids, atomic {@code compute} to close the
 * eviction-race window documented in Plan E.
 */
@Component
public class ProfileUpdateRateLimiter {

    public static final int LIMIT = 10;
    public static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Cache<String, Deque<Instant>> hits;

    public ProfileUpdateRateLimiter(Clock clock) {
        this.clock = clock;
        this.hits = Caffeine.newBuilder()
                .expireAfterAccess(WINDOW.toMinutes(), TimeUnit.MINUTES)
                .maximumSize(100_000)
                .build();
    }

    /**
     * Returns {@code null} if the request is allowed; otherwise returns
     * the number of seconds until the oldest hit in the current window ages
     * out (i.e. the earliest the caller may retry).
     *
     * @throws IllegalArgumentException if {@code uid} is null or blank —
     *     same guard as {@link SubmissionRateLimiter#tryAcquire} (cubic R7 P2).
     */
    public Long tryAcquire(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("tryAcquire requires a non-blank uid");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);

        Long[] retryAfter = new Long[]{null};
        hits.asMap().compute(uid, (key, existing) -> {
            Deque<Instant> dq = existing != null ? existing : new ArrayDeque<>();
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) dq.pollFirst();
            if (dq.size() >= LIMIT) {
                Instant oldest = dq.peekFirst();
                retryAfter[0] = oldest.plus(WINDOW).getEpochSecond() - now.getEpochSecond();
            } else {
                dq.addLast(now);
            }
            return dq;
        });
        return retryAfter[0];
    }

    /**
     * Plan G cubic R6 P2 — refund the specific slot identified by
     * {@code acquiredAt} for {@code uid}.
     *
     * <p>The R5 version used {@code dq.pollLast()} which popped the
     * MOST RECENT slot for the uid — incorrect when two concurrent
     * acquires for the same uid interleave (T1 acquires at t=10, T2 at
     * t=20, T1 fails → pollLast pops T2's t=20 instead of T1's t=10).
     * The net slot count came out right but the wrong timestamp was
     * preserved, letting the user marginally over-acquire across the
     * window boundary. Now we remove the exact {@code Instant} we
     * recorded when this request acquired its slot.
     *
     * <p>Safe under concurrent {@link #tryAcquire(String)} calls because
     * the deque mutation runs inside the same per-key {@code compute}
     * lock.
     */
    public void release(String uid, Instant acquiredAt) {
        if (uid == null || uid.isBlank() || acquiredAt == null) return;
        hits.asMap().computeIfPresent(uid, (key, dq) -> {
            dq.removeFirstOccurrence(acquiredAt);
            return dq;
        });
    }

    /**
     * Plan G cubic R6 P2 — acquire a slot AND return its timestamp so the
     * caller can release that specific slot later via
     * {@link #release(String, Instant)}.
     *
     * <p>Returns {@code AcquireOutcome.limited(retryAfterSec)} when the
     * caller is over the budget, or {@code AcquireOutcome.acquired(slot)}
     * on success. The two-method shape (this + the older
     * {@link #tryAcquire(String)}) lets the existing interceptor migrate
     * without breaking other consumers; new code should call this method.
     */
    public AcquireOutcome acquire(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("acquire requires a non-blank uid");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);

        Long[] retryAfter = new Long[]{null};
        hits.asMap().compute(uid, (key, existing) -> {
            Deque<Instant> dq = existing != null ? existing : new ArrayDeque<>();
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) dq.pollFirst();
            if (dq.size() >= LIMIT) {
                Instant oldest = dq.peekFirst();
                retryAfter[0] = oldest.plus(WINDOW).getEpochSecond() - now.getEpochSecond();
            } else {
                dq.addLast(now);
            }
            return dq;
        });
        return retryAfter[0] == null
                ? AcquireOutcome.acquired(now)
                : AcquireOutcome.limited(retryAfter[0]);
    }

    /** Two-shape outcome for {@link #acquire(String)}. */
    public sealed interface AcquireOutcome {
        record Acquired(Instant slot) implements AcquireOutcome {}
        record Limited(long retryAfterSec) implements AcquireOutcome {}
        static Acquired acquired(Instant slot) { return new Acquired(slot); }
        static Limited limited(long retryAfterSec) { return new Limited(retryAfterSec); }
    }
}
