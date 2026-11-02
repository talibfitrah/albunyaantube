package com.albunyaan.tube.service;

import com.albunyaan.tube.model.LiveChannelState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §12 U2 (rules half): R10, R12c, R15 and D3 on one channel. The cycle half is LiveChannelPollerTest. */
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
        assertThat(next.getNextPollAt()).isEqualTo(T0 + 20 * MIN + 24 * 60 * MIN);   // backoff cleared, the daily sweep
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

    /** R10: after an hour unproven, hides apply, but nothing is ever counted toward a removal (a count of 0 is no miss). */
    @Test
    void anUnprovenHideNeverCountsAMiss() {
        LivePollRules.Outcome o = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, empty(), false, true,
                Set.of("pqC-1yfFvu8"), T0);

        assertThat(o.next().getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(o.next().getMisses().values()).allMatch(m -> m.getCount() == 0);
        assertThat(o.ended()).isEmpty();
    }

    /**
     * R12c + R10 + R15: a live hidden by an unproven hide (after 1 h) stays tracked, so it still ends once proven
     * cycles return; the 15-min window opens at the first PROVEN miss, so a restart double-poll right then cannot end it.
     */
    @Test
    void aLiveHiddenByAnUnprovenHideStillEndsInLaterProvenCycles() {
        LivePollRules.Outcome hide = LivePollRules.apply(liveState("old1"), ZAD, empty(), false, true, Set.of(), T0);
        LivePollRules.Outcome stillUnproven = LivePollRules.apply(hide.next(), ZAD, empty(), false, true, Set.of(), T0 + 10 * MIN);
        LivePollRules.Outcome first = LivePollRules.apply(stillUnproven.next(), ZAD, empty(), true, true, Set.of(), T0 + 60 * MIN);
        LivePollRules.Outcome restart = LivePollRules.apply(first.next(), ZAD, empty(), true, true, Set.of(), T0 + 62 * MIN);
        LivePollRules.Outcome later = LivePollRules.apply(restart.next(), ZAD, empty(), true, true, Set.of(), T0 + 80 * MIN);

        assertThat(hide.next().getLives()).isEmpty();
        assertThat(stillUnproven.next().getMisses().get("old1").getCount()).isZero();
        assertThat(first.next().getMisses().get("old1").getCount()).isEqualTo(1);
        assertThat(restart.next().getMisses()).containsKey("old1");
        assertThat(later.next().getMisses()).doesNotContainKey("old1");   // ended: a channel live, so not in ended()
    }

    /** R12c: the seed (D15) applies unproven; a live it replaces stays tracked at count 0 like an unproven hide. */
    @Test
    void anUnprovenReplacementKeepsTheDepartedLiveTracked() {
        LiveChannelState next = LivePollRules.apply(liveState("old1"), ZAD, live("keep"), false, true, Set.of(), T0).next();

        assertThat(ids(next)).containsExactly("keep");
        assertThat(next.getMisses().get("old1").getCount()).isZero();
    }

    @Test
    void aSecondHideKeepsTheFirstOfflineSince() {
        LiveChannelState hidden = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, empty(), true, true, Set.of(), T0).next();
        LiveChannelState next = LivePollRules.apply(hidden, ZAD, empty(), true, true, Set.of(), T0 + 10 * MIN).next();

        assertThat(next.getOfflineSince()).isEqualTo(T0);
    }

    /**
     * D3: errors back off 10/20/40/80/160 min and change nothing else inside the hour; errorSince is the first error. Rev 8:
     * while lives are held, the next read never falls after the hour's end.
     */
    @Test
    void errorsKeepTheLivesAndBackOff() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LiveChannelState s = liveState("eaOtcUNHDB8");
        long[] expected = {10, 20, 40, 80, 160};
        for (int i = 0; i < expected.length; i++) {
            long now = T0 + i * MIN;
            LivePollRules.Outcome o = LivePollRules.apply(s, ZAD, LiveTabResult.error(1, "IOException: x"), true, true, links, now);
            s = o.next();
            assertThat(s.getNextPollAt()).isEqualTo(Math.min(now + expected[i] * MIN, T0 + 60 * MIN));
            assertThat(s.getMisses()).isEmpty();
            assertThat(o.ended()).isEmpty();
        }
        assertThat(s.getErrorCount()).isEqualTo(5);
        assertThat(s.getErrorSince()).isEqualTo(T0);
        assertThat(ids(s)).containsExactly("eaOtcUNHDB8");
        assertThat(s.getStatus()).isEqualTo(LiveChannelState.LIVE);
    }

    /**
     * Rev 8 (Cubic r8 #1): an empty Live tab is ERROR (D3). The streak keeps the last lives for under 60 min, then hides
     * them as R10's unproven hide does: nothing served, each hidden id tracked at count 0 (no miss), nothing ended. The
     * next OK read decides as usual.
     */
    @Test
    void anErrorStreakHidesTheLastLivesAfterAnHourAsR10Does() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LiveTabResult empty = LiveTabResult.error(1, "no items after 0 continuation pages");
        LivePollRules.Outcome first = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, empty, true, true, links, T0);
        LivePollRules.Outcome held = LivePollRules.apply(first.next(), ZAD, empty, true, true, links, T0 + 59 * MIN);
        LivePollRules.Outcome hidden = LivePollRules.apply(held.next(), ZAD, empty, true, true, links, T0 + 60 * MIN);

        assertThat(held.next().getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(held.next())).containsExactly("eaOtcUNHDB8");
        assertThat(hidden.next().getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(hidden.next().getLives()).isEmpty();
        assertThat(hidden.next().getOfflineSince()).isEqualTo(T0 + 60 * MIN);
        assertThat(hidden.next().getMisses()).containsOnlyKeys("eaOtcUNHDB8");
        assertThat(hidden.next().getMisses().get("eaOtcUNHDB8").getCount()).isZero();
        assertThat(hidden.next().getErrorCount()).isEqualTo(3);
        assertThat(hidden.ended()).isEmpty();

        LivePollRules.Outcome back = LivePollRules.apply(hidden.next(), ZAD, live("eaOtcUNHDB8"), true, true, Set.of(),
                T0 + 140 * MIN);
        assertThat(back.next().getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(back.next())).containsExactly("eaOtcUNHDB8");
        assertThat(back.next().getOfflineSince()).isNull();
        assertThat(back.next().getMisses()).isEmpty();
        assertThat(back.ended()).isEmpty();
    }

    /** Rev 8: while lives are held the next read lands at the hour's end (10, 30, 60), not the backoff's 70 min. */
    @Test
    void anErrorStreakHoldingLivesIsReadAgainWhenTheHourEnds() {
        LiveChannelState s = liveState("eaOtcUNHDB8");
        long now = T0;
        for (long expected : new long[]{10, 30, 60}) {
            s = LivePollRules.apply(s, ZAD, LiveTabResult.error(1, "x"), true, true, Set.of(), now).next();
            assertThat(s.getNextPollAt()).isEqualTo(T0 + expected * MIN);
            now = s.getNextPollAt();
        }
        assertThat(s.getStatus()).isEqualTo(LiveChannelState.LIVE);

        s = LivePollRules.apply(s, ZAD, LiveTabResult.error(1, "x"), true, true, Set.of(), now).next();
        assertThat(s.getStatus()).as("the hour still hides them (Rev 8)").isEqualTo(LiveChannelState.OFFLINE);
        assertThat(s.getNextPollAt()).isEqualTo(T0 + 140 * MIN);          // error 4: 80 min
        s = LivePollRules.apply(s, ZAD, LiveTabResult.error(1, "x"), true, true, Set.of(), T0 + 140 * MIN).next();
        assertThat(s.getNextPollAt()).isEqualTo(T0 + 300 * MIN);          // error 5: 160 min, the backoff keeps doubling
    }

    /** An ERROR streak serving nothing doubles up to the daily sweep, never stuck at 80 min (~18 reads a day). */
    @Test
    void anErrorStreakBacksOffUpToTheDailySweep() {
        LiveChannelState s = null;
        long now = T0;
        for (long expected : new long[]{10, 20, 40, 80, 160, 320, 640, 1280, 1440, 1440}) {
            s = LivePollRules.apply(s, ZAD, LiveTabResult.error(1, "IOException: x"), true, true, Set.of(), now).next();
            assertThat(s.getNextPollAt()).isEqualTo(now + expected * MIN);
            now = s.getNextPollAt();
        }
        s.setErrorCount(500);                                               // a long streak never overflows the shift
        s = LivePollRules.apply(s, ZAD, LiveTabResult.error(1, "IOException: x"), true, true, Set.of(), now).next();
        assertThat(s.getNextPollAt()).isEqualTo(now + 1440 * MIN);
    }

    /** Rev 8: the breaker path is unaffected. A refusal after the hour neither hides nor counts. */
    @Test
    void aBreakerRefusalAfterTheHourStillChangesNothing() {
        LiveChannelState erroring = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, LiveTabResult.error(1, "x"), true,
                true, Set.of(), T0).next();

        LivePollRules.Outcome o = LivePollRules.apply(erroring, ZAD, LiveTabResult.breaker(0, "open"), true, true,
                Set.of("pqC-1yfFvu8"), T0 + 70 * MIN);

        assertThat(o.next().getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(o.next())).containsExactly("eaOtcUNHDB8");
        assertThat(o.next().getErrorCount()).isEqualTo(1);
        assertThat(o.next().getMisses()).isEmpty();
        assertThat(o.ended()).isEmpty();
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
        LivePollRules.Outcome o = LivePollRules.apply(before, ZAD, LiveTabResult.breaker(0, "open"), true, true,
                Set.of("pqC-1yfFvu8"), T0);

        assertThat(ids(o.next())).containsExactly("eaOtcUNHDB8");
        assertThat(o.next().getErrorCount()).isZero();
        assertThat(o.next().getNextPollAt()).isEqualTo(before.getNextPollAt());   // unchanged (its LIVE tier)
        assertThat(o.next().getMisses()).isEmpty();
        assertThat(o.ended()).isEmpty();
    }

    /**
     * R7 / Task 2 hand-off: a rate limit is YouTube pushing back, not this channel. No miss, no end, no backoff.
     * Rev 9: checkedAt stays the last real read (the poller's L2 attempt time rotates the channel behind the others),
     * and inside the 25 h hold (HOLD_MS) since that read the lives are still served.
     */
    @Test
    void aRateLimitedReadInsideTheHoldChangesNothing() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LiveChannelState counted = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, live("eaOtcUNHDB8"), true, true, links, T0).next();

        LivePollRules.Outcome o = LivePollRules.apply(counted, ZAD,
                LiveTabResult.rateLimited(1, "ReCaptchaException: HTTP 429"), true, true, links, T0 + 1499 * MIN);

        assertThat(o.next().getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(o.next())).containsExactly("eaOtcUNHDB8");
        assertThat(o.next().getMisses().get("pqC-1yfFvu8").getCount()).isEqualTo(1);
        assertThat(o.next().getErrorCount()).isZero();
        assertThat(o.next().getNextPollAt()).isNull();
        assertThat(o.next().getCheckedAt()).isEqualTo(T0);
        assertThat(o.ended()).isEmpty();
    }

    /**
     * Rate limits that last 25 h (the 24 h sweep + 1 h) past the last real
     * read hide the lives as R10 does (tracked at count 0, never a miss, removal or archive). The next read that lists
     * the live serves it again.
     */
    @Test
    void aRateLimitedReadTwentyFiveHoursAfterTheLastReadHidesTheLives() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LiveChannelState counted = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, live("eaOtcUNHDB8"), true, true, links, T0).next();
        LiveTabResult limited = LiveTabResult.rateLimited(1, "ReCaptchaException: HTTP 429");
        LiveChannelState held = LivePollRules.apply(counted, ZAD, limited, true, true, links, T0 + 1490 * MIN).next();

        LivePollRules.Outcome hidden = LivePollRules.apply(held, ZAD, limited, true, true, links, T0 + 1500 * MIN);

        assertThat(hidden.next().getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(hidden.next().getLives()).isEmpty();
        assertThat(hidden.next().getOfflineSince()).isEqualTo(T0 + 1500 * MIN);
        assertThat(hidden.next().getMisses().get("eaOtcUNHDB8").getCount()).isZero();
        assertThat(hidden.next().getMisses().get("pqC-1yfFvu8").getCount()).isEqualTo(1);   // unchanged
        assertThat(hidden.next().getErrorCount()).isZero();
        assertThat(hidden.next().getNextPollAt()).isNull();
        assertThat(hidden.ended()).isEmpty();

        LivePollRules.Outcome back = LivePollRules.apply(hidden.next(), ZAD, live("eaOtcUNHDB8"), true, true, Set.of(),
                T0 + 1510 * MIN);
        assertThat(back.next().getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(back.next())).containsExactly("eaOtcUNHDB8");
        assertThat(back.next().getMisses()).doesNotContainKey("eaOtcUNHDB8");
    }

    /**
     * The time-only hold (no read) counts from the last real read, or from the ERROR streak's start. It
     * lasts 25 h (a LIVE channel is swept once a day), not R10's hour.
     */
    @Test
    void theTimeOnlyHoldEndsTwentyFiveHoursAfterTheLastProof() {
        LiveChannelState read = LivePollRules.apply(null, ZAD, live("eaOtcUNHDB8"), true, true, Set.of(), T0 - 1500 * MIN).next();
        LiveChannelState justRead = LivePollRules.apply(null, ZAD, live("eaOtcUNHDB8"), true, true, Set.of(), T0 - 1499 * MIN).next();
        LiveChannelState erroring = LivePollRules.apply(read, ZAD, LiveTabResult.error(1, "x"), true, true, Set.of(),
                T0 - 30 * MIN).next();                                         // errorSince T0 - 30 min

        assertThat(LivePollRules.expireHold(justRead, T0 - 59 * MIN)).isFalse();   // 24 h: a sweep gap, never hidden
        assertThat(LivePollRules.expireHold(read, T0)).isTrue();
        assertThat(read.getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(read.getLives()).isEmpty();
        assertThat(read.getMisses().get("eaOtcUNHDB8").getCount()).isZero();
        assertThat(LivePollRules.expireHold(justRead, T0)).isFalse();
        assertThat(ids(justRead)).containsExactly("eaOtcUNHDB8");
        assertThat(LivePollRules.expireHold(erroring, T0 + 1469 * MIN)).isFalse();   // 24 h 59 min into the streak
        assertThat(LivePollRules.expireHold(erroring, T0 + 1470 * MIN)).isTrue();
        assertThat(LivePollRules.expireHold(LiveChannelState.unknown(ZAD), T0)).isFalse();   // nothing held
    }

    /** D3 / RV M6: a terminated channel is an empty read with reason TERMINATED. */
    @Test
    void aTerminatedChannelIsAnEmptyReadWithAReason() {
        LiveChannelState next = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD,
                LiveTabResult.terminated(1, "This channel doesn't exist."), true, true, Set.of(), T0).next();

        assertThat(next.getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(next.getReason()).isEqualTo(LiveChannelState.TERMINATED);
    }

    /** R10: TERMINATED is an empty read, so an unproven cycle keeps the lives and counts nothing. */
    @Test
    void aTerminatedReadInAnUnprovenCycleKeepsTheLives() {
        LivePollRules.Outcome o = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD,
                LiveTabResult.terminated(1, "This channel doesn't exist."), false, false, Set.of("pqC-1yfFvu8"), T0);

        assertThat(o.next().getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(ids(o.next())).containsExactly("eaOtcUNHDB8");
        assertThat(o.next().getMisses()).isEmpty();
        assertThat(o.ended()).isEmpty();
    }

    @Test
    void aLaterOkReadClearsTheReason() {
        LiveChannelState gone = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD,
                LiveTabResult.terminated(1, "This channel doesn't exist."), true, true, Set.of(), T0).next();
        LiveChannelState next = LivePollRules.apply(gone, ZAD, empty(), true, true, Set.of(), T0 + 10 * MIN).next();

        assertThat(next.getReason()).isNull();
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
        assertThat(b.next().getMisses()).containsKey("old1");
        assertThat(c.next().getMisses()).doesNotContainKey("old1");
    }

    /** Owner 2026-11-02: an ended link is kept as ended (hidden, still in its category) and is not counted again. */
    @Test
    void anEndedLinkIsKeptAsEndedAndNotCountedAgain() {
        Set<String> links = Set.of("pqC-1yfFvu8");
        LivePollRules.Outcome a = LivePollRules.apply(liveState("eaOtcUNHDB8"), ZAD, live("eaOtcUNHDB8"), true, true, links, T0);
        LivePollRules.Outcome b = LivePollRules.apply(a.next(), ZAD, live("eaOtcUNHDB8"), true, true, links, T0 + 20 * MIN);
        LivePollRules.Outcome c = LivePollRules.apply(b.next(), ZAD, live("eaOtcUNHDB8"), true, true, links, T0 + 50 * MIN);

        assertThat(b.ended()).containsExactly("pqC-1yfFvu8");
        assertThat(b.next().getEnded()).containsExactly("pqC-1yfFvu8");
        assertThat(c.ended()).isEmpty();
        assertThat(c.next().getMisses()).doesNotContainKey("pqC-1yfFvu8");
        assertThat(c.next().getEnded()).containsExactly("pqC-1yfFvu8");
    }

    /** Only links are kept as ended: a channel entry's departed live just stops being tracked. */
    @Test
    void anEndedLiveThatIsNoLinkIsNotKept() {
        LivePollRules.Outcome a = LivePollRules.apply(liveState("old1", "keep"), ZAD, live("keep"), true, true, Set.of(), T0);
        LivePollRules.Outcome b = LivePollRules.apply(a.next(), ZAD, live("keep"), true, true, Set.of(), T0 + 20 * MIN);

        assertThat(b.next().getMisses()).doesNotContainKey("old1");   // ended: no longer tracked
        assertThat(b.ended()).isEmpty();                               // ended() lists links only
        assertThat(b.next().getEnded()).isEmpty();
    }

    /** Owner 2026-11-02: a read listing an ended id live shows it again, no admin action, and it is counted afresh. */
    @Test
    void aReadListingAnEndedIdLiveTakesItOutOfEnded() {
        LiveChannelState s = liveState("keep");
        s.getEnded().add("back");

        LivePollRules.Outcome o = LivePollRules.apply(s, ZAD, live("back", "keep"), false, false, Set.of("back"), T0);

        assertThat(o.next().getEnded()).isEmpty();
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

    // The apps renew stream URLs and ask the server after a rotation, so a LIVE channel is swept once a day. nextPollAt
    // after a real read, first match wins: a counted miss or a suspect keep -> every cycle; a read listing lives, or a
    // terminated channel -> 24 h; a read listing none -> 4 h (Cubic r2 #1: a channel going live is listed within hours).

    static final long HOUR = 60 * MIN;

    static LiveChannelState read(LiveChannelState prev, LiveTabResult r, Set<String> links, long at) {
        return LivePollRules.apply(prev, ZAD, r, true, true, links, at).next();
    }

    /** A1: a replaced live's id is counted (R15 needs reads at most 15 min apart): every cycle, even while LIVE. */
    @Test
    void aCountedMissIsReadEveryCycleEvenWhileLive() {
        LiveChannelState replaced = read(read(null, live("a"), Set.of(), T0 - 3 * HOUR), live("b"), Set.of("x"), T0);

        assertThat(replaced.getMisses().get("a").getCount()).isEqualTo(1);
        assertThat(replaced.getNextPollAt()).isNull();
    }

    /** A2: an empty unproven read keeps a LIVE channel's lives (R10 suspect): every cycle until decided. */
    @Test
    void aSuspectLiveChannelIsReadEveryCycle() {
        LiveChannelState started = read(null, live("a"), Set.of(), T0);

        LiveChannelState kept = LivePollRules.apply(started, ZAD, empty(), false, false, Set.of(), T0 + 3 * HOUR).next();

        assertThat(kept.getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(kept.getNextPollAt()).isNull();
    }

    /** Cubic r2 #1: a channel read with no lives is read again in 4 h; a live one, or a terminated one, in 24 h. */
    @Test
    void aChannelReadWithNoLivesIsReadAgainInFourHours() {
        LiveChannelState hidden = LiveChannelState.unknown(ZAD);
        hidden.setStatus(LiveChannelState.OFFLINE);
        hidden.getMisses().put("eaOtcUNHDB8", new LiveChannelState.Miss(0, T0 - HOUR));

        LiveChannelState offline = LivePollRules.apply(hidden, ZAD, empty(), false, true, Set.of(), T0).next();
        LiveChannelState gone = read(null, LiveTabResult.terminated(1, "AccountTerminatedException"), Set.of(), T0);
        LiveChannelState linked = LivePollRules.apply(null, ZAD, empty(), false, true, Set.of("x"), T0).next();

        assertThat(offline.getMisses()).containsKey("eaOtcUNHDB8");
        assertThat(linked.getMisses()).isEmpty();                    // unproven: no miss counted
        assertThat(read(null, live("a"), Set.of("a"), T0).getNextPollAt()).isEqualTo(T0 + 24 * HOUR);   // a live link
        assertThat(offline.getNextPollAt()).isEqualTo(T0 + 4 * HOUR);
        assertThat(gone.getNextPollAt()).isEqualTo(T0 + 24 * HOUR);
        assertThat(linked.getNextPollAt()).isEqualTo(T0 + 4 * HOUR);
    }

    /** Cubic r2 #2: a read listing a live stamps liveSeenAt (persisted: R10's proof survives a restart); no other read does. */
    @Test
    void onlyAReadListingALiveStampsLiveSeenAt() {
        LiveChannelState seen = read(null, live("a"), Set.of(), T0);
        LiveChannelState offline = read(seen, empty(), Set.of(), T0 + HOUR);
        LiveChannelState failed = LivePollRules.apply(offline, ZAD, LiveTabResult.error(1, "boom"), true, true, Set.of(), T0 + 2 * HOUR).next();

        assertThat(seen.getLiveSeenAt()).isEqualTo(T0);
        assertThat(offline.getLiveSeenAt()).isEqualTo(T0);
        assertThat(failed.getLiveSeenAt()).isEqualTo(T0);
        assertThat(failed.copy().getLiveSeenAt()).isEqualTo(T0);
        assertThat(read(null, empty(), Set.of(), T0).getLiveSeenAt()).isNull();
    }

    static final String NO_ITEMS = "no items after 5 continuation pages";

    /**
     * Cubic P3: an empty Live tab on a channel serving nothing is read again at the 4 h offline recheck, not the doubling
     * backoff and not the daily sweep: an offline entry is not listed, so no app request can bring a first stream sooner.
     */
    @Test
    void anEmptyLiveTabOnAChannelWithNoLivesIsRecheckedIn4Hours() {
        LiveChannelState never = LivePollRules.apply(null, ZAD, LiveTabResult.error(6, NO_ITEMS), true, true, Set.of(), T0).next();

        assertThat(never.getNextPollAt()).isEqualTo(T0 + LivePollRules.OFFLINE_RECHECK_MS);
        assertThat(never.getErrorCount()).isEqualTo(1);
    }

    /** Codex r3: a long streak of empty Live tabs stays on the 4 h recheck; the doubling backoff (error 9: a day) never wins. */
    @Test
    void aLongEmptyLiveTabStreakStaysOnTheFourHourRecheck() {
        LiveChannelState prev = LiveChannelState.unknown(ZAD);
        prev.setErrorCount(8);
        prev.setErrorSince(T0 - 48 * HOUR);

        LiveChannelState next = LivePollRules.apply(prev, ZAD, LiveTabResult.error(6, NO_ITEMS), true, true, Set.of(), T0).next();

        assertThat(next.getNextPollAt()).isEqualTo(T0 + LivePollRules.OFFLINE_RECHECK_MS);
        assertThat(next.getErrorCount()).isEqualTo(9);
    }

    @Test
    void anEmptyLiveTabHoldingLivesKeepsTheBackoff() {
        LiveChannelState next = LivePollRules.apply(liveState("a"), ZAD, LiveTabResult.error(6, NO_ITEMS), true, true, Set.of(), T0).next();

        assertThat(next.getNextPollAt()).isEqualTo(T0 + 10 * MIN);
    }

    /** Cubic r6 K1 (a): a channel with an ENDED link is read again in 4 h, not a day, though it lists other lives. */
    @Test
    void aChannelWithAnEndedLinkIsReadAgainInFourHours() {
        LiveChannelState s = liveState("other000001");
        s.getEnded().add("linkid00001");

        LiveChannelState next = LivePollRules.apply(s, ZAD, live("other000001"), true, true, Set.of("linkid00001"), T0).next();

        assertThat(next.getEnded()).containsExactly("linkid00001");
        assertThat(next.getNextPollAt()).isEqualTo(T0 + LivePollRules.OFFLINE_RECHECK_MS);
    }

    /** Codex r6 C3: a read applied at an earlier time than the stored one never moves checkedAt or liveSeenAt back. */
    @Test
    void anEarlierReadNeverMovesTheTimestampsBack() {
        LiveChannelState later = LivePollRules.apply(null, ZAD, live("eaOtcUNHDB8"), true, true, Set.of(), T0 + 5 * MIN).next();

        LiveChannelState next = LivePollRules.apply(later, ZAD, live("eaOtcUNHDB8"), true, true, Set.of(), T0).next();

        assertThat(next.getCheckedAt()).isEqualTo(T0 + 5 * MIN);
        assertThat(next.getLiveSeenAt()).isEqualTo(T0 + 5 * MIN);
    }
}
