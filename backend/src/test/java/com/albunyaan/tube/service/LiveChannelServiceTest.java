package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** D1 snapshot, the 60 s memo (§5), and D15's add-time seed. */
class LiveChannelServiceTest {

    static final String SUNNAH = "UCROKYPep-UuODNwyipe6JMw";
    final AtomicLong now = new AtomicLong(1_792_200_000_000L);
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    final LiveTabReader reader = mock(LiveTabReader.class);
    final LiveChannelStateRepository states = mock(LiveChannelStateRepository.class);
    final LiveChannelService service = new LiveChannelService(reader, states, clock);

    /** The boot load succeeded (LiveChannelPoller.afterSingletonsInstantiated). */
    @BeforeEach
    void bootLoad() {
        service.loadStates(List.of());
    }

    /** L1: before the states load, a seed writes nothing and a put refuses, so an empty snapshot never overwrites them. */
    @Test
    void nothingIsWrittenBeforeTheStatesLoad() throws Exception {
        LiveChannelService unloaded = new LiveChannelService(reader, states, clock);
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        unloaded.seed(SUNNAH);                                  // the add is never refused

        verify(reader, never()).read(any());                    // F3: no shared-IP read for a seed that cannot land
        assertThat(unloaded.state(SUNNAH)).isNull();
        assertThatThrownBy(() -> unloaded.put(LiveChannelState.unknown(SUNNAH))).isInstanceOf(IllegalStateException.class);
        verify(states, never()).save(any());
    }

    static LiveTabResult sunnahLive() {
        return LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("Rs7St51oDDc", "Sunnah")), 1);
    }

    @Test
    void aReadWithinSixtySecondsIsServedFromTheMemo() {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        service.livesNow(SUNNAH);
        now.addAndGet(59_000);
        service.livesNow(SUNNAH);
        now.addAndGet(2_000);
        service.livesNow(SUNNAH);

        verify(reader, times(2)).read(SUNNAH);
    }

    /** D16: a memo hit sends nothing, so it costs nothing against the cycle budget or the daily count. */
    @Test
    void aMemoHitCostsNoRequests() {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        assertThat(service.livesNow(SUNNAH).requests()).isEqualTo(1);
        assertThat(service.livesNow(SUNNAH).requests()).isZero();
    }

    @Test
    void anErrorIsNeverMemoized() {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.error(1, "x"));

        service.livesNow(SUNNAH);
        service.livesNow(SUNNAH);

        verify(reader, times(2)).read(SUNNAH);
    }

    /** D15: a channel add is visible at once; a failed read seeds ERROR instead of refusing the add. */
    @Test
    void aSeedWritesTheStateAndPublishesIt() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.LIVE);
        verify(states).save(any(LiveChannelState.class));
    }

    /** A seed keeps the ended links it does not list, and shows again one it lists live (owner ruling 2026-11-02). */
    @Test
    void aSeedKeepsEndedLinksItDoesNotList() {
        LiveChannelState known = LiveChannelState.unknown(SUNNAH);
        known.getEnded().addAll(List.of("Rs7St51oDDc", "endedlink01"));
        service.loadStates(List.of(known));
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(service.state(SUNNAH).getEnded()).containsExactly("endedlink01");
    }

    @Test
    void aFailedSeedRecordsAnError() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.error(1, "boom"));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getErrorCount()).isEqualTo(1);
        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.UNKNOWN);
    }

    /** D15 / pre-flight M9: a failed state write never refuses the add; the read stays in the snapshot. */
    @Test
    void aFailedStateWriteNeverRefusesTheAdd() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(sunnahLive());
        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(states).save(any());

        service.seed(SUNNAH);                                   // no exception

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.LIVE);
    }

    @Test
    void aBreakerSeedWritesNothing() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.breaker(0, "open"));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH)).isNull();
        verify(states, never()).save(any());
    }

    /** R7: a rate limit says nothing about the channel, so the seed records nothing (no UNKNOWN with a fresh checkedAt). */
    @Test
    void aRateLimitedSeedWritesNothing() throws Exception {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH)).isNull();
        verify(states, never()).save(any());
    }

    private void knownLive() {
        LiveChannelState known = LiveChannelState.unknown(SUNNAH);
        known.setStatus(LiveChannelState.LIVE);
        known.getLives().add(new LiveChannelState.Live("Rs7St51oDDc", "Sunnah"));
        service.loadStates(List.of(known));
    }

    /** R10: re-adding a channel that is served now, during a suspect stretch, must not hide its last lives. */
    @Test
    void aReSeedOfAServedChannelNeverHides() {
        knownLive();
        service.publish(Map.of(SUNNAH, new Channel(SUNNAH)), Map.of(), Map.of(), java.util.Set.of());
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(), 1));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(service.state(SUNNAH).getLives()).extracting(LiveChannelState.Live::getVideoId)
                .containsExactly("Rs7St51oDDc");
    }

    /** A channel that is not served now (removed earlier, re-added) shows nothing stale: an empty read hides it. */
    @Test
    void aSeedOfAKnownButUnservedChannelHides() {
        knownLive();
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(), 1));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    /**
     * Stage 9 B14 (Cubic r5 #6): a channel out of every LIVE category is not polled, so its stored lives may have ended
     * days ago. Re-added, only a read shows lives again: a seed YouTube does not answer (breaker, rate limit, error)
     * leaves it serving none. Its ended links stay ended.
     */
    @Test
    void aReAddedChannelYouTubeDoesNotAnswerServesNoneOfItsOldLives() {
        for (LiveTabResult noAnswer : List.of(LiveTabResult.breaker(0, "open"),
                LiveTabResult.rateLimited(1, "ReCaptchaException: 429"), LiveTabResult.error(1, "boom"))) {
            LiveChannelState frozen = LiveChannelState.unknown(SUNNAH);   // as the poller left it, days ago
            frozen.setStatus(LiveChannelState.LIVE);
            frozen.getLives().add(new LiveChannelState.Live("Rs7St51oDDc", "Sunnah"));
            frozen.getEnded().add("endedlink01");
            service.loadStates(List.of(frozen));
            when(reader.read(SUNNAH)).thenReturn(noAnswer);

            service.seed(SUNNAH);                                    // not an entry now: it is (re)entering

            LiveChannelState s = service.state(SUNNAH);
            assertThat(LiveCategoryService.servedLives(new Channel(SUNNAH), s, Map.of()))
                    .as(noAnswer.outcome().name()).isEmpty();
            assertThat(s.getEnded()).as(noAnswer.outcome().name()).containsExactly("endedlink01");
        }
    }

    @Test
    void aFirstSeedOfAnOfflineChannelHidesIt() {
        when(reader.read(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(), 1));

        service.seed(SUNNAH);

        assertThat(service.state(SUNNAH).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    // ---- Cubic r3 P2: a global budget for the public endpoint's candidate registry lookups ----

    /** Judges {@code n} distinct candidates; returns how many judges ran. */
    private int judgeDistinct(int from, int n) {
        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = from; i < from + n; i++) {
            service.candidateVerdict(SUNNAH, "v" + i, () -> { ran.incrementAndGet(); return true; });
        }
        return ran.get();
    }

    @Test
    void overTheLookupBudgetNothingIsJudged() {
        int budget = LiveChannelService.CANDIDATE_LOOKUPS_PER_MINUTE;
        assertThat(judgeDistinct(0, budget)).isEqualTo(budget);

        java.util.concurrent.atomic.AtomicInteger ran = new java.util.concurrent.atomic.AtomicInteger();
        Boolean v = service.candidateVerdict(SUNNAH, "over", () -> { ran.incrementAndGet(); return true; });

        assertThat(v).isNull();                                 // the endpoint's 503: no verdict, no lookup
        assertThat(ran.get()).isZero();
    }

    @Test
    void aCachedVerdictCostsNoBudget() {
        int budget = LiveChannelService.CANDIDATE_LOOKUPS_PER_MINUTE;
        service.candidateVerdict(SUNNAH, "cached", () -> true);
        for (int i = 0; i < 3 * budget; i++) {
            assertThat(service.candidateVerdict(SUNNAH, "cached", () -> { throw new AssertionError("judged again"); })).isTrue();
        }
        assertThat(judgeDistinct(0, budget - 1)).isEqualTo(budget - 1);   // the one first judge was the only spend
    }

    @Test
    void theLookupBudgetRefillsOverTime() {
        int budget = LiveChannelService.CANDIDATE_LOOKUPS_PER_MINUTE;
        judgeDistinct(0, budget);
        assertThat(judgeDistinct(budget, 1)).isZero();

        now.addAndGet(30_000);                                  // half a minute: half the budget back
        assertThat(judgeDistinct(1_000, budget)).isEqualTo(budget / 2);

        now.addAndGet(10 * 60_000);                             // a long idle never banks more than one minute's worth
        assertThat(judgeDistinct(2_000, 2 * budget)).isEqualTo(budget);
    }

    /** A failed lookup (null) is not cached: the next call judges again. */
    @Test
    void aFailedJudgementIsNotCached() {
        assertThat(service.candidateVerdict(SUNNAH, "x", () -> null)).isNull();
        assertThat(service.candidateVerdict(SUNNAH, "x", () -> false)).isFalse();
    }

    /**
     * Codex r6 C2: a judge whose inputs a publish replaced (the generation moved while it ran) gives no verdict (503), and
     * nothing is cached: the next call judges the new inputs.
     */
    @Test
    void aVerdictJudgedAcrossAPublishIsNoVerdictAndNotCached() {
        Boolean v = service.candidateVerdict(SUNNAH, "x", () -> {
            service.publish(Map.of(), Map.of(), Map.of(), java.util.Set.of());
            return false;
        });

        assertThat(v).isNull();
        assertThat(service.candidateVerdict(SUNNAH, "x", () -> true)).isTrue();
    }

    /** Cubic r6 K1 (c): the guard saw the id live: it leaves ENDED at once, persisted. Other ended ids stay. */
    @Test
    void anIdSeenLiveAgainLeavesEnded() throws Exception {
        LiveChannelState known = LiveChannelState.unknown(SUNNAH);
        known.getEnded().addAll(List.of("Rs7St51oDDc", "endedlink01"));
        service.loadStates(List.of(known));

        service.liveAgain(SUNNAH, "Rs7St51oDDc");
        service.liveAgain(SUNNAH, "notended001");                 // nothing to clear: no write
        service.liveAgain("UCunknown0000000000000000", "Rs7St51oDDc");

        assertThat(service.state(SUNNAH).getEnded()).containsExactly("endedlink01");
        verify(states, times(1)).save(any());
    }
}
