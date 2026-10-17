package com.albunyaan.tube.service;

import com.albunyaan.tube.model.LiveChannelState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §12 U2 (rules half): R10, R12, R15 and D3 on one channel. The cycle half is LiveChannelPollerTest. */
class LivePollRulesTest {

    static final String ZAD = "UCOll3M-P7oKs5cSrQ9ytt6g";
    static final long T0 = 1_792_200_000_000L;
    static final long MIN = 60_000L;

    static LiveTabResult live(String... ids) {
        return LiveTabResult.ok(java.util.Arrays.stream(ids).map(i -> new LiveTabResult.LiveItem(i, "t-" + i)).toList(), 1);
    }

    static LiveTabResult empty() {
        return LiveTabResult.ok(List.of(), 1);
    }

    static LiveChannelState liveState(String... ids) {
        return LivePollRules.apply(null, ZAD, live(ids), true, true, Set.of(), T0 - 60 * MIN).next();
    }

    static List<String> ids(LiveChannelState s) {
        return s.getLives().stream().map(LiveChannelState.Live::getVideoId).toList();
    }

    @Test
    void aLiveReadSetsLiveAndClearsErrors() {
        LiveChannelState erroring = LivePollRules.apply(null, ZAD, LiveTabResult.error(1, "boom"), true, true, Set.of(), T0).next();

        LiveChannelState next = LivePollRules.apply(erroring, ZAD, live("eaOtcUNHDB8"), true, true, Set.of(), T0 + 20 * MIN).next();

        assertThat(next.getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(next)).containsExactly("eaOtcUNHDB8");
        assertThat(next.getErrorCount()).isZero();
        assertThat(next.getErrorSince()).isNull();
        assertThat(next.getNextPollAt()).isNull();
        assertThat(next.getCheckedAt()).isEqualTo(T0 + 20 * MIN);
    }

    @Test
    void anEmptyReadInAProvenCycleHidesAndStampsOfflineSince() {
        LiveChannelState next = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, empty(), true, true, Set.of(), T0).next();

        assertThat(next.getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(next.getLives()).isEmpty();
        assertThat(next.getOfflineSince()).isEqualTo(T0);
    }

    /** R10: an unproven cycle keeps the last lives (the admin sees "suspect"). */
    @Test
    void anEmptyReadInAnUnprovenCycleKeepsTheLives() {
        LiveChannelState next = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, empty(), false, false, Set.of(), T0).next();

        assertThat(next.getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(next)).containsExactly("eaOtcUNHDB8");
        assertThat(next.getMisses()).isEmpty();
    }

    /** R10: after an hour unproven, hides apply, but nothing is ever counted toward a removal. */
    @Test
    void anUnprovenHideNeverCountsAMiss() {
        LivePollRules.Outcome o = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, empty(), false, true,
                Set.of("pqC-1yfFvu8"), T0);

        assertThat(o.next().getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(o.next().getMisses()).isEmpty();
        assertThat(o.ended()).isEmpty();
    }

    /** D3: errors back off 10/20/40/80 min and change nothing else. */
    @Test
    void errorsKeepTheLivesAndBackOff() {
        LiveChannelState s = liveState("eaOtcUNHDB8");
        long[] expected = {10, 20, 40, 80, 80};
        for (int i = 0; i < expected.length; i++) {
            s = LivePollRules.apply(s, ZAD, LiveTabResult.error(1, "IOException: x"), true, true, Set.of(), T0).next();
            assertThat(s.getNextPollAt()).isEqualTo(T0 + expected[i] * MIN);
        }
        assertThat(s.getErrorCount()).isEqualTo(5);
        assertThat(s.getErrorSince()).isEqualTo(T0);
        assertThat(ids(s)).containsExactly("eaOtcUNHDB8");
        assertThat(s.getStatus()).isEqualTo(LiveChannelState.LIVE);
    }

    @Test
    void aLongErrorIsTruncatedTo200Chars() {
        LiveChannelState s = LivePollRules.apply(null, ZAD, LiveTabResult.error(1, "x".repeat(500)), true, true, Set.of(), T0).next();
        assertThat(s.getLastError()).hasSize(200);
    }

    /** D3 / RV M3: a breaker refusal changes nothing. */
    @Test
    void aBreakerResultChangesNothing() {
        LiveChannelState before = liveState("eaOtcUNHDB8");
        LivePollRules.Outcome o = LivePollRules.apply(before, ZAD, LiveTabResult.breaker(0, "open"), true, true, Set.of(), T0);

        assertThat(ids(o.next())).containsExactly("eaOtcUNHDB8");
        assertThat(o.next().getErrorCount()).isZero();
        assertThat(o.next().getNextPollAt()).isNull();
    }

    /**
     * R7 / Task 2 hand-off: a rate limit is YouTube pushing back, not this channel. No hide, no miss, no end, no
     * backoff; only checkedAt advances, so the oldest-first order rotates past it and it cannot starve the others.
     */
    @Test
    void aRateLimitedReadOnlyAdvancesCheckedAt() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LiveChannelState counted = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, live("eaOtcUNHDB8"), true, true, links, T0).next();

        LivePollRules.Outcome o = LivePollRules.apply(counted, ZAD,
                LiveTabResult.rateLimited(1, "ReCaptchaException: HTTP 429"), true, true, links, T0 + 20 * MIN);

        assertThat(o.next().getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(o.next())).containsExactly("eaOtcUNHDB8");
        assertThat(o.next().getMisses().get("pqC-1yfFvu8").getCount()).isEqualTo(1);
        assertThat(o.next().getErrorCount()).isZero();
        assertThat(o.next().getNextPollAt()).isNull();
        assertThat(o.next().getCheckedAt()).isEqualTo(T0 + 20 * MIN);
        assertThat(o.ended()).isEmpty();
    }

    /** D3 / RV M6: a terminated channel is an empty read with reason TERMINATED. */
    @Test
    void aTerminatedChannelIsAnEmptyReadWithAReason() {
        LiveChannelState next = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD,
                LiveTabResult.terminated(1, "This channel doesn't exist."), true, true, Set.of(), T0).next();

        assertThat(next.getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(next.getReason()).isEqualTo(LiveChannelState.TERMINATED);
    }

    /** R15: missing in 2 proven polls 10 min apart is not enough; the third (20 min) ends it. */
    @Test
    void aLinkMissingInProvenPollsEndsOnTheThirdCycle() {
        LiveChannelState s = liveState("eaOtcUNHDB8");
        Set<String> links = Set.of("pqC-1yfFvu8");

        LivePollRules.Outcome first = LivePollRules.apply(s, ZAD, live("eaOtcUNHDB8"), true, true, links, T0);
        LivePollRules.Outcome second = LivePollRules.apply(first.next(), ZAD, live("eaOtcUNHDB8"), true, true, links, T0 + 10 * MIN);
        LivePollRules.Outcome third = LivePollRules.apply(second.next(), ZAD, live("eaOtcUNHDB8"), true, true, links, T0 + 20 * MIN);

        assertThat(first.ended()).isEmpty();
        assertThat(second.ended()).isEmpty();
        assertThat(third.ended()).containsExactly("pqC-1yfFvu8");
        assertThat(third.next().getMisses()).doesNotContainKey("pqC-1yfFvu8");
    }

    /** R15 / RV I6: a deploy restart right after a cycle polls twice 1–2 min apart; that alone never removes. */
    @Test
    void aRestartDoublePollTwoMinutesApartDoesNotEnd() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LivePollRules.Outcome first = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, live("eaOtcUNHDB8"), true, true, links, T0);
        LivePollRules.Outcome second = LivePollRules.apply(first.next(), ZAD, live("eaOtcUNHDB8"), true, true, links, T0 + 2 * MIN);

        assertThat(second.ended()).isEmpty();
        assertThat(second.next().getMisses().get("pqC-1yfFvu8").getCount()).isEqualTo(2);
    }

    /** R10: unproven cycles in between neither count nor reset a miss. */
    @Test
    void unprovenCyclesInBetweenChangeNothing() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LivePollRules.Outcome a = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, live("eaOtcUNHDB8"), true, true, links, T0);
        LivePollRules.Outcome b = LivePollRules.apply(a.next(), ZAD, empty(), false, false, links, T0 + 10 * MIN);
        LivePollRules.Outcome c = LivePollRules.apply(b.next(), ZAD, live("eaOtcUNHDB8"), true, true, links, T0 + 20 * MIN);

        assertThat(b.next().getMisses().get("pqC-1yfFvu8").getCount()).isEqualTo(1);
        assertThat(c.ended()).containsExactly("pqC-1yfFvu8");
    }

    /** R12c: a live that left the tab keeps being counted after it left `lives`, and ends like a link. */
    @Test
    void aDepartedLiveIsTrackedUntilItEnds() {
        LivePollRules.Outcome a = LivePollRules.apply(liveState("old1", "keep"), ZAD, live("keep"), true, true, Set.of(), T0);
        LivePollRules.Outcome b = LivePollRules.apply(a.next(), ZAD, live("keep"), true, true, Set.of(), T0 + 10 * MIN);
        LivePollRules.Outcome c = LivePollRules.apply(b.next(), ZAD, live("keep"), true, true, Set.of(), T0 + 20 * MIN);

        assertThat(ids(a.next())).containsExactly("keep");
        assertThat(b.ended()).isEmpty();
        assertThat(c.ended()).containsExactly("old1");
    }

    /** R12: an id the poller archived that comes back as a live is restored and stops being counted. */
    @Test
    void aReappearingIdIsRestoredAndItsMissCleared() {
        LiveChannelState s = liveState("keep");
        s.getArchived().put("back", "VALID");
        s.getMisses().put("back", new LiveChannelState.Miss(1, T0 - 10 * MIN));

        LivePollRules.Outcome o = LivePollRules.apply(s, ZAD, live("back", "keep"), true, true, Set.of(), T0);

        assertThat(o.restored()).isEqualTo(Map.of("back", "VALID"));
        assertThat(o.next().getArchived()).doesNotContainKey("back");
        assertThat(o.next().getMisses()).doesNotContainKey("back");
    }

    /** R10: adding or replacing lives always applies. */
    @Test
    void aReplacementLiveAppliesAtOnce() {
        LiveChannelState next = LivePollRules.apply(liveState("pqC-1yfFvu8"), ZAD, live("eaOtcUNHDB8"), true, true, Set.of(), T0).next();

        assertThat(ids(next)).containsExactly("eaOtcUNHDB8");
        assertThat(next.getMisses()).containsKey("pqC-1yfFvu8");
    }

    @Test
    void copyIsDeep() {
        LiveChannelState s = liveState("a");
        s.getMisses().put("x", new LiveChannelState.Miss(1, T0));
        LiveChannelState c = s.copy();
        c.getLives().clear();
        c.getMisses().get("x").setCount(9);

        assertThat(ids(s)).containsExactly("a");
        assertThat(s.getMisses().get("x").getCount()).isEqualTo(1);
    }
}
