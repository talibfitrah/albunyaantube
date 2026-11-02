package com.albunyaan.tube.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.albunyaan.tube.controller.LiveChannelController;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.CatalogChanged;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LiveEndpointService;
import com.albunyaan.tube.service.LiveTabReader;
import com.albunyaan.tube.service.LiveTabResult;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.event.EventListener;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §12 U2 (cycle half): proven cycles, the 1 h unproven hide, budget, breaker, D14 eviction, boot load. */
class LiveChannelPollerTest {

    static final long T0 = 1_792_200_000_000L;
    static final long MIN = 60_000L;
    /** A LIVE channel is swept again 24 h after a read (4 h when the read listed none); unread, its lives are held 25 h. */
    static final long SWEEP = 24 * 60 * MIN;
    static final long HOLD = 25 * 60 * MIN;
    static final String CAT = "btiLw1LiEqiUHQJiaJKX";
    static final String A = "UCa0000000000000000000aa";
    static final String B = "UCb0000000000000000000bb";

    final VideoRepository videos = mock(VideoRepository.class);
    final LiveChannelStateRepository states = mock(LiveChannelStateRepository.class);
    final LiveTabReader reader = mock(LiveTabReader.class);
    final LiveCategoryService liveCategories = mock(LiveCategoryService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final PublicContentCacheService caches = mock(PublicContentCacheService.class);
    final YouTubeCircuitBreaker breaker = mock(YouTubeCircuitBreaker.class);
    final AtomicLong now = new AtomicLong(T0);
    LiveChannelService live;
    LiveChannelPoller poller;
    Clock clock;
    final List<Object> members = new ArrayList<>();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final ch.qos.logback.classic.Logger pollerLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LiveChannelPoller.class);

    /** The service's 60 s memo and the cycle read the same clock. */
    private void cycleAt(long t) {
        now.set(t);
        poller.cycle(t);
    }

    static Channel channel(String uc) {
        Channel c = new Channel(uc);
        c.setId("channel_" + uc);
        c.setName(uc);
        c.setStatus("APPROVED");
        c.setCategoryIds(new ArrayList<>(List.of(CAT)));
        return c;
    }

    static LiveTabResult live(String... ids) {
        return LiveTabResult.ok(Arrays.stream(ids).map(i -> new LiveTabResult.LiveItem(i, "t")).toList(), 1);
    }

    @BeforeEach
    void setUp() throws Exception {
        clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        live = new LiveChannelService(reader, states, clock);
        poller = new LiveChannelPoller(liveCategories, videos, states, live, audit, caches, breaker, clock, true, 1_000_000);
        poller.onDemand = Runnable::run;                              // on-demand reads run inline in tests
        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>(List.of(CAT)));
        when(liveCategories.members(CAT)).thenAnswer(i -> new LiveCategoryService.Members(List.copyOf(members)));
        when(videos.findByYoutubeIds(any())).thenReturn(Map.of());
        logs.start();
        pollerLog.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        pollerLog.detachAppender(logs);
    }

    private void entries(String... ucs) {
        for (String uc : ucs) members.add(channel(uc));
    }

    private List<ILoggingEvent> logged(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).toList();
    }

    /** A link of channel A in the LIVE category. */
    private void endingLink() throws Exception {
        Video link = new Video("linkid00001");
        link.setId("doc-link");
        link.setStatus("APPROVED");
        link.setChannelId(A);
        link.setCategoryIds(new ArrayList<>(List.of(CAT)));
        link.setValidationStatus(ValidationStatus.VALID);
        members.add(link);
        when(videos.findByYoutubeId("linkid00001")).thenReturn(Optional.of(link));
    }

    @Test
    void noLiveCategoryMeansNoYouTubeTraffic() throws Exception {
        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>());

        cycleAt(T0);

        verify(reader, never()).read(anyString());
    }

    /** C1 / R10: a parse break that reads every channel as "nothing LIVE" never removes or archives anything. */
    @Test
    void aPersistentParseBreakNeverRemovesOrArchives() throws Exception {
        entries(A, B);
        Video link = new Video("linkid00001");
        link.setId("doc-link");
        link.setStatus("APPROVED");
        link.setChannelId(A);
        link.setCategoryIds(new ArrayList<>(List.of(CAT)));
        members.add(link);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                             // proven: both LIVE

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        for (int i = 1; i <= 30; i++) {
            cycleAt(T0 + i * 10 * MIN);
        }

        verify(videos, never()).save(any());
        // Cubic r2 #2: inside the 2 h proven window an empty read hides (and R15 ends the link, hidden, never removed);
        // A's next read, its 4 h recheck after the last miss at T0 + 30 min, is past the window: suspect from then on
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(A).getEnded()).containsExactly("linkid00001");
        assertThat(live.suspectSince()).isEqualTo(T0 + 270 * MIN);
    }

    @Test
    void unprovenHidesWaitAnHour() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));

        cycleAt(T0 + SWEEP);                                       // its next read (the daily sweep)
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(logged(Level.ERROR)).hasSize(1);                  // the suspect line: an empty read of a LIVE channel
        cycleAt(T0 + SWEEP + 60 * MIN);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    /**
     * D16: the 90-request ceiling stops a cycle; the next one starts with the channels left unread. The reads
     * are ERRORs (due again in 10 min, not urgent), since a LIVE read would make a channel urgent and go first.
     */
    @Test
    void theRequestCeilingStopsTheCycleAndTheNextResumesOldestFirst() {
        for (int i = 0; i < 20; i++) entries(String.format("UC%022d", i));
        when(reader.read(anyString())).thenReturn(LiveTabResult.error(6, "x"));

        cycleAt(T0);
        verify(reader, times(15)).read(anyString());                  // 15 x 6 = 90

        cycleAt(T0 + 10 * MIN);                                        // the next cycle (spec cadence, no hold)
        verify(reader).read(String.format("UC%022d", 15));            // never-checked channels come first
    }

    /** D3 / RV M3: a breaker refusal mid-cycle stops it and is not counted against the channel. */
    @Test
    void aBreakerRefusalStopsTheCycleWithoutCounting() {
        entries(A, B);
        when(reader.read(A)).thenReturn(LiveTabResult.breaker(0, "open"));

        cycleAt(T0);

        verify(reader, never()).read(B);
        assertThat(live.state(A)).isNull();
    }

    /** R7: the first rate limit stops the cycle with one WARN; no error, no hide, and no read time (Rev 9). */
    @Test
    void aRateLimitStopsTheCycleWithOneWarning() {
        entries(A, B);
        when(reader.read(A)).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        cycleAt(T0);

        verify(reader, never()).read(B);
        LiveChannelState a = live.state(A);
        assertThat(a.getCheckedAt()).isNull();
        assertThat(a.getStatus()).isEqualTo(LiveChannelState.UNKNOWN);
        assertThat(a.getErrorCount()).isZero();
        assertThat(logged(Level.WARN)).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains(A, Instant.ofEpochMilli(T0).toString()));
    }

    /** A rate limit is not a read: stopping on a LIVE channel logs the stop, never the "nothing LIVE" suspect line. */
    @Test
    void aRateLimitStopOfALiveChannelIsNotASuspectRead() {
        entries(A);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(A)).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        cycleAt(T0 + SWEEP);

        assertThat(logged(Level.ERROR)).isEmpty();
        assertThat(logged(Level.WARN)).hasSize(1);
    }

    /** R7 / D16: a rate-limited channel goes to the back of the next cycle, so it can never starve the others. */
    @Test
    void aRateLimitedChannelIsReadLastInTheNextCycle() {
        entries(A, B);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // both read at T0; ties keep the entry order
        when(reader.read(A)).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));
        cycleAt(T0 + SWEEP);                                       // A first, rate-limited: the cycle stops before B
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0 + SWEEP + 10 * MIN);                                       // A was checked later than B, so B leads

        InOrder order = inOrder(reader);
        order.verify(reader).read(A);
        order.verify(reader).read(B);
        order.verify(reader).read(A);
        order.verify(reader).read(B);
        order.verify(reader).read(A);
        verify(reader, times(2)).read(B);
    }

    /**
     * Rev 9 (owner, 2026-11-01): while YouTube rate-limits, each cycle stops at its first read, so most channels go
     * unread. The rate-limited channel and the unread one both hide 25 h after their last real read; nothing is
     * removed or archived, and the next read that lists a live serves it again.
     */
    @Test
    void rateLimitsHideLivesUnprovenForTheHoldAndAReadServesThemAgain() throws Exception {
        entries(A, B);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        for (long t = T0 + SWEEP; t < T0 + HOLD; t += 10 * MIN) cycleAt(t); // from their next sweep: one rate-limited read per cycle
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        org.mockito.Mockito.clearInvocations(caches, reader);

        cycleAt(T0 + HOLD);
        verify(reader, times(1)).read(anyString());                   // still one try per cycle, no extra traffic
        assertThat(live.served()).doesNotContainKeys(A, B);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(B).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(B).getMisses().get("bbbbbbbbbbb").getCount()).isZero();
        verify(caches).evictPublicContentCaches();

        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0 + HOLD + 10 * MIN);                                 // the rate limit ends
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        assertThat(live.state(B).getMisses()).isEmpty();
        verify(videos, never()).save(any());
    }

    /** The ceiling leaves five unread; swept 24 h ago, they are held, never hidden (25 h hold). */
    @Test
    void aHealthyCycleNeverHidesTargetsTheCeilingLeftUnreadWithinTheHold() throws Exception {
        List<LiveChannelState> saved = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String uc = String.format("UC%022d", i);
            entries(uc);
            LiveChannelState s = LiveChannelState.unknown(uc);
            s.setStatus(LiveChannelState.LIVE);
            s.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
            s.setCheckedAt(T0 - SWEEP);
            saved.add(s);
        }
        when(states.findAll()).thenReturn(saved);
        poller.afterSingletonsInstantiated();
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "t")), 6));

        cycleAt(T0);

        verify(reader, times(15)).read(anyString());                  // 15 x 6 = 90: five channels unread
        assertThat(saved).allSatisfy(s -> assertThat(live.state(s.getChannelId()).getStatus()).isEqualTo(LiveChannelState.LIVE));
        assertThat(live.served()).hasSize(20);
    }

    /** A breaker refusal mid-cycle is a block too: the refused channel and the ones after it hide after the 25 h hold. */
    @Test
    void aBreakerRefusalMidCycleHidesTheUnreadLivesUnprovenForTheHold() {
        entries(A, B);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        when(reader.read(A)).thenReturn(LiveTabResult.breaker(0, "probe already in progress"));

        cycleAt(T0 + HOLD);

        verify(reader, times(1)).read(B);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(B).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    /** D3: errors back off 10, 20, 40, 80 min and on, doubling up to the daily sweep; a cycle inside it skips the channel. */
    @Test
    void targetsInBackoffAreSkipped() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.error(1, "x"));
        cycleAt(T0);                                                  // error 1: nextPollAt = T0 + 10 min
        cycleAt(T0 + 10 * MIN);                                       // due: error 2, nextPollAt = T0 + 30 min
        cycleAt(T0 + 20 * MIN);                                       // inside the backoff: skipped

        verify(reader, times(2)).read(anyString());
    }

    /**
     * Rev 8 (Cubic r8 #1): a channel whose Live tab empties reads ERROR (D3). Its last lives are served for under an hour
     * of the streak, then hidden as R10 hides them: served lists drop them, nothing is removed or archived. A later read
     * with the live serves it again.
     */
    @Test
    void anErrorStreakServesTheLastLivesUnderAnHourThenHidesThem() throws Exception {
        entries(A, B);
        endingLink();                                                 // A's link: only the ERROR rule could end it here
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa", "linkid00001"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        long S = T0 + SWEEP - 10 * MIN;                            // A's first ERROR is its next read (the sweep)
        when(reader.read(A)).thenReturn(LiveTabResult.error(1, "no items after 0 continuation pages"));

        for (int m = 10; m <= 60; m += 10) cycleAt(S + m * MIN);    // errors at 10, 20, 40 (errorSince S + 10)
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa", "linkid00001");
        org.mockito.Mockito.clearInvocations(caches);

        cycleAt(S + 70 * MIN);                                         // 60 min into the streak
        assertThat(live.served().getOrDefault(A, List.of())).isEmpty();
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(A).getMisses().values()).allMatch(m -> m.getCount() == 0);
        verify(caches).evictPublicContentCaches();

        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa", "linkid00001"));
        cycleAt(S + 150 * MIN);                                        // A's backoff (80 min) is over
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa", "linkid00001");
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.state(A).getMisses()).isEmpty();
        verify(reader, times(6)).read(A);                              // 0, 10, 20, 40, 70, 150
        verify(videos, never()).save(any());
    }

    /** Spec §12 U2: a single-channel category; any LIVE read resets the unproven clock, so the hour restarts. */
    @Test
    void aLiveReadResetsTheUnprovenClock() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        cycleAt(T0 + SWEEP);                                       // unproven since then (a suspect keep: every cycle)
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0 + SWEEP + 10 * MIN);                            // proven again: the clock resets
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        cycleAt(T0 + 2 * SWEEP + 10 * MIN);                        // its next read: unproven since then

        cycleAt(T0 + 2 * SWEEP + 60 * MIN);                        // 50 min unproven: still shown
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.suspectSince()).isEqualTo(T0 + 2 * SWEEP + 10 * MIN);
        cycleAt(T0 + 2 * SWEEP + 70 * MIN);                        // 60 min: hidden
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    /** One channel's Firestore failure never costs the others their cycle; the view and the eviction still run. */
    @Test
    void aFailedChannelDoesNotAbortTheCycle() throws Exception {
        entries(A, B);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        doThrow(new ExecutionException(new RuntimeException("firestore down")))
                .when(states).save(argThat(s -> A.equals(s.getChannelId())));

        cycleAt(T0);

        assertThat(live.state(A)).isNull();
        assertThat(live.state(B).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.served()).containsOnlyKeys(B);
        verify(caches).evictPublicContentCaches();
    }

    /** D7: the seed and a cycle compute-and-put one at a time, so a cycle never overwrites a newer add-time read. */
    @Test
    void aSeedWaitsForTheCyclesStateWrite() throws Exception {
        entries(A);
        CountDownLatch inSave = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(i -> {
            if (first.getAndSet(false)) {                            // only the cycle's write waits
                inSave.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return null;
        }).when(states).save(any());
        when(reader.read(A)).thenReturn(live("cyclelive01"), live("seedlive001"));
        Thread cycle = new Thread(() -> poller.cycle(T0));
        Thread seed = new Thread(() -> live.seed(A));
        try {
            cycle.start();
            assertThat(inSave.await(10, TimeUnit.SECONDS)).isTrue();  // the cycle is inside its state write
            now.addAndGet(61_000);                                    // past the memo: the seed reads afresh
            seed.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (seed.getState() != Thread.State.BLOCKED) {        // its read done, it waits for the cycle's put
                assertThat(seed.isAlive()).as("the seed wrote while the cycle held the state").isTrue();
                assertThat(System.nanoTime()).isLessThan(deadline);
                Thread.onSpinWait();
            }
        } finally {
            release.countDown();
            cycle.join(10_000);
            seed.join(10_000);
        }

        assertThat(live.state(A).getLives()).extracting(LiveChannelState.Live::getVideoId).containsExactly("seedlive001");
    }

    /** D13 / RV M12: a link without a channel id is never polled. */
    @Test
    void linksWithoutAChannelIdAreNotPolled() {
        Video orphan = new Video("PLkCnLrKN8Q");
        orphan.setId("doc-1");
        orphan.setStatus("APPROVED");
        orphan.setCategoryIds(new ArrayList<>(List.of(CAT)));
        members.add(orphan);

        cycleAt(T0);

        verify(reader, never()).read(anyString());
    }

    /** Stage 9 B21 (Cubic r9 #21): a cycle that changed the snapshot evicts, even when its view refresh then fails. */
    @Test
    void aChangedSnapshotEvictsEvenWhenTheViewRefreshFails() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        when(videos.findByYoutubeIds(any())).thenThrow(new IllegalStateException("Firestore unavailable"));

        cycleAt(T0);

        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);       // apply() landed
        verify(caches).evictPublicContentCaches();
    }

    /** D14: a retitled live keeps the caches; a new id evicts them. */
    @Test
    void onlyAServedIdChangeEvicts() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "Morning")), 1));
        cycleAt(T0);
        verify(caches, times(1)).evictPublicContentCaches();

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "Evening")), 1));
        cycleAt(T0 + SWEEP);
        verify(caches, times(1)).evictPublicContentCaches();

        when(reader.read(anyString())).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0 + 2 * SWEEP);
        verify(caches, times(2)).evictPublicContentCaches();
    }

    /** Review Focus 1: after a restart the snapshot serves the last lives before the first cycle. */
    @Test
    void bootLoadServesTheLastLivesBeforeTheFirstCycle() throws Exception {
        entries(A);
        LiveChannelState saved = LiveChannelState.unknown(A);
        saved.setStatus(LiveChannelState.LIVE);
        saved.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        when(states.findAll()).thenReturn(List.of(saved));

        poller.afterSingletonsInstantiated();

        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.entries()).containsKey(A);
        verify(reader, never()).read(anyString());
    }

    /** The load runs inside context refresh: a Firestore failure logs and leaves the snapshot empty, never aborts startup. */
    @Test
    void aFailedBootLoadNeverBlocksStartup() throws Exception {
        entries(A);
        when(states.findAll()).thenThrow(new java.util.concurrent.TimeoutException("firestore"));

        poller.afterSingletonsInstantiated();

        assertThat(live.snapshot()).isEmpty();
        assertThat(live.served()).isEmpty();
    }

    /** A Sunnah-like state: LIVE, plus a link the poller ended (hidden until it is live again). */
    /** A's stored state with an ENDED link, which is still in the category (C4 prunes ENDED ids that are not). */
    private LiveChannelState savedWithEndedLink() {
        linkLands("endedlink01");
        LiveChannelState saved = LiveChannelState.unknown(A);
        saved.setStatus(LiveChannelState.LIVE);
        saved.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        saved.getEnded().add("endedlink01");
        return saved;
    }

    /** L1 (scenario A): a failed boot load is retried by the next cycle; no seed or cycle writes a state before it. */
    @Test
    void aFailedBootLoadIsRetriedBeforeAnyStateWrite() throws Exception {
        entries(A);
        when(states.findAll()).thenThrow(new java.util.concurrent.TimeoutException("firestore"))
                .thenReturn(List.of(savedWithEndedLink()));
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        poller.afterSingletonsInstantiated();

        live.seed(A);                                                 // an admin add before the reload
        verify(states, never()).save(any());

        cycleAt(T0);

        verify(states).save(argThat(s -> s.getEnded().contains("endedlink01")));
        verify(states, never()).save(argThat(s -> s.getEnded().isEmpty()));
        assertThat(live.state(A).getEnded()).containsExactly("endedlink01");
    }

    /**
     * L1 (scenario B): an open breaker skips the YouTube reads, never the reload and the served view. Rev 9: lives read
     * inside the 25 h hold (HOLD_MS) are still served.
     */
    @Test
    void anOpenBreakerStillReloadsAndRefreshesTheServedView() throws Exception {
        entries(A);
        LiveChannelState saved = savedWithEndedLink();
        saved.setCheckedAt(T0 - 59 * MIN);
        when(states.findAll()).thenThrow(new java.util.concurrent.TimeoutException("firestore"))
                .thenReturn(List.of(saved));
        when(breaker.isOpen()).thenReturn(true);
        poller.afterSingletonsInstantiated();

        cycleAt(T0);

        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.entries()).containsKey(A);
        verify(reader, never()).read(anyString());
        verify(states, never()).save(any());
        verify(caches).evictPublicContentCaches();                     // pages cached while nothing was loaded
    }

    /**
     * With the breaker open no channel is read, so lives unproven for the 25 h hold are hidden
     * (time only, no YouTube call; never a removal, archive or miss). Once the breaker closes, the read that lists the
     * live serves it again with no admin action.
     */
    @Test
    void anOpenBreakerHidesLivesUnprovenForTheHoldAndAReadServesThemAgain() throws Exception {
        entries(A, B);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        when(breaker.isOpen()).thenReturn(true);
        org.mockito.Mockito.clearInvocations(reader);

        cycleAt(T0 + HOLD - 10 * MIN);
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        org.mockito.Mockito.clearInvocations(caches);

        cycleAt(T0 + HOLD);
        verify(reader, never()).read(anyString());
        assertThat(live.served()).doesNotContainKeys(A, B);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(A).getMisses().get("aaaaaaaaaaa").getCount()).isZero();
        verify(states).save(argThat(s -> A.equals(s.getChannelId()) && s.getLives().isEmpty()));
        verify(caches).evictPublicContentCaches();

        when(breaker.isOpen()).thenReturn(false);                      // the HALF_OPEN probe got through
        cycleAt(T0 + HOLD + 60 * MIN);
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.state(A).getMisses()).isEmpty();
        verify(videos, never()).save(any());
    }

    /** Rev 9: under an open breaker an ERROR streak's hold still counts from errorSince (Rev 8), not the last read. */
    @Test
    void anOpenBreakerCountsAnErrorStreaksHoldFromItsStart() {
        entries(A);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(A)).thenReturn(LiveTabResult.error(1, "IOException: x"));
        cycleAt(T0 + SWEEP);                                        // errorSince T0 + 24 h
        when(breaker.isOpen()).thenReturn(true);

        cycleAt(T0 + SWEEP + HOLD - 10 * MIN);                      // 48 h 50 since the read, 24 h 50 into the streak
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        cycleAt(T0 + SWEEP + HOLD);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    /** The public endpoint's status for channel A: its 404 is the apps' definitive "not live" stop. */
    private int endpointStatus() {
        return endpointStatus(A);
    }

    private int endpointStatus(String uc) {
        try {
            return new LiveChannelController(new LiveEndpointService(live, poller, videos)).channel(uc, null).getStatusCode().value();
        } catch (ResourceNotFoundException e) {
            return 404;
        }
    }

    /**
     * Stage 9 Cubic r2 P2: every channel add runs the guard, which seeds the channel before the write. Only a cycle republishes
     * the endpoint's entries, so until one lists the channel it answers 503, never the 404.
     */
    @Test
    void aChannelAddedSinceTheLastViewAnswers503UntilACycleListsIt() throws Exception {
        entries(A);
        when(states.findAll()).thenReturn(List.of());
        when(reader.read(A)).thenReturn(live());
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        poller.afterSingletonsInstantiated();                          // a published view without B

        live.seed(B);                                                  // the guard, on the add
        entries(B);                                                    // the add's write lands
        now.set(T0 + 9 * MIN);

        assertThat(endpointStatus(B)).isEqualTo(503);
        cycleAt(T0 + 10 * MIN);
        assertThat(endpointStatus(B)).isEqualTo(200);
    }

    /** An add that never landed (refused or failed after the guard's seed) is the definitive 404 again after two cycles. */
    @Test
    void aSeedWhoseAddNeverLandedIs404AgainAfterTwoCycles() throws Exception {
        entries(A);
        when(states.findAll()).thenReturn(List.of());
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));        // the call for A reads it on demand
        poller.afterSingletonsInstantiated();

        live.seed(B);

        assertThat(endpointStatus(B)).isEqualTo(503);
        now.set(T0 + 20 * MIN);
        assertThat(endpointStatus(B)).isEqualTo(404);
        assertThat(endpointStatus(A)).as("a listed channel is unaffected").isEqualTo(200);
    }

    private LiveChannelState savedLive() {
        LiveChannelState saved = LiveChannelState.unknown(A);
        saved.setStatus(LiveChannelState.LIVE);
        saved.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        return saved;
    }

    /** Stage 9 Cubic P2, window 1: the boot load succeeds, then its view refresh fails (logged, swallowed). */
    @Test
    void aBootLoadWhoseViewRefreshFailsAnswers503UntilACyclePublishes() throws Exception {
        entries(A);
        when(states.findAll()).thenReturn(List.of(savedLive()));
        when(liveCategories.liveCategoryIds()).thenThrow(new java.util.concurrent.TimeoutException("firestore"))
                .thenReturn(new LinkedHashSet<>(List.of(CAT)));
        when(breaker.isOpen()).thenReturn(true);                       // the cycle below needs no YouTube read

        poller.afterSingletonsInstantiated();

        assertThat(endpointStatus()).isEqualTo(503);                   // loaded, but no view published: never 404
        cycleAt(T0);
        assertThat(endpointStatus()).isEqualTo(200);
    }

    /** Stage 9 Cubic P2, window 2: after a failed boot load, the first cycle loads, then reads every Live tab, then publishes. */
    @Test
    void theFirstCycleAfterAFailedBootLoadAnswers503UntilItPublishes() throws Exception {
        entries(A);
        when(states.findAll()).thenThrow(new java.util.concurrent.TimeoutException("firestore"))
                .thenReturn(List.of(savedLive()));
        List<Integer> duringTheRead = new ArrayList<>();
        when(reader.read(A)).thenAnswer(i -> {
            duringTheRead.add(endpointStatus());                       // the states are loaded; the view is not published
            return live("aaaaaaaaaaa");
        });
        poller.afterSingletonsInstantiated();

        cycleAt(T0);

        assertThat(duringTheRead).containsExactly(503);
        assertThat(endpointStatus()).isEqualTo(200);
    }

    /** L2: 30 targets in backoff never take the 30 slots from a due target behind them. */
    @Test
    void backedOffTargetsDoNotTakeTheThirtySlots() {
        for (int i = 0; i < 30; i++) entries(String.format("UC%022d", i));
        String healthy = String.format("UC%022d", 30);
        entries(healthy);
        when(reader.read(anyString())).thenReturn(LiveTabResult.error(1, "x"));
        when(reader.read(healthy)).thenReturn(live("aaaaaaaaaaa"));

        cycleAt(T0);                                                  // the first 30 fail: backed off to T0 + 10 min
        cycleAt(T0 + 5 * MIN);

        verify(reader).read(healthy);
    }

    /** L2: an ERROR never advances checkedAt, so attempts, not successful checks, order the targets. */
    @Test
    void failingTargetsRotateBehindTargetsNotYetTried() {
        for (int i = 0; i < 30; i++) entries(String.format("UC%022d", i));
        String healthy = String.format("UC%022d", 30);
        entries(healthy);
        when(reader.read(anyString())).thenReturn(LiveTabResult.error(1, "x"));
        when(reader.read(healthy)).thenReturn(live("aaaaaaaaaaa"));

        cycleAt(T0);                                                  // the first 30 fail
        cycleAt(T0 + 10 * MIN);                                       // their backoff is over: all 31 are due

        verify(reader).read(healthy);
    }

    /**
     * Fix round 2: the snapshot loads while the singletons are instantiated, before Tomcat opens the port (finishRefresh)
     * and before @Scheduled tasks register (ContextRefreshedEvent); never on ApplicationReadyEvent.
     */
    @Test
    void theBootLoadRunsBeforeThePortOpens() {
        assertThat(SmartInitializingSingleton.class).isAssignableFrom(LiveChannelPoller.class);
        // Stage 9 B13: the poller listens to moderation (plain records); never to an application lifecycle event.
        assertThat(Arrays.stream(LiveChannelPoller.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(EventListener.class))
                .filter(m -> java.util.stream.Stream.of(m.getParameterTypes(), m.getAnnotation(EventListener.class).classes())
                        .flatMap(Arrays::stream).anyMatch(org.springframework.context.ApplicationEvent.class::isAssignableFrom))
                .map(java.lang.reflect.Method::getName).toList())
                .isEmpty();
    }

    // ---- Stage 9 B13 (P2): moderation reaches the live endpoint at the next tick, not the next cycle ----

    /** Every moderation path but the exclusions evicts the public caches (a channel removed, LIVE cleared, a take-down...). */
    @Test
    void aModerationEvictionReachesTheLiveEndpointAtTheNextTick() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        assertThat(endpointStatus()).isEqualTo(200);

        members.clear();                                              // e.g. the channel left the LIVE category
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();

        assertThat(endpointStatus()).isEqualTo(404);
        verify(reader, times(1)).read(anyString());                   // Firestore only: no YouTube read
    }

    /**
     * Cubic r5 IT: the poller's own eviction (Spring delivers {@code Evicted} synchronously, on the evicting thread) is no
     * moderation write: after the refresh, with nothing pending, a removed channel is the definitive 404, not 503.
     * The same holds after a cycle whose served change evicts.
     */
    @Test
    void afterTheRefreshARemovedChannelIs404WithNothingPending() throws Exception {
        doAnswer(i -> {
            poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
            return null;
        }).when(caches).evictPublicContentCaches();
        entries(A, B);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // serves A and B; its eviction is its own
        assertThat(poller.viewPending()).isFalse();

        members.removeIf(m -> m instanceof Channel c && A.equals(c.getYoutubeId()));   // B still served
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        assertThat(poller.viewPending()).isTrue();                    // the refresh is pending
        poller.refreshIfStale();                                      // evicts: A is no longer served

        assertThat(poller.viewPending()).isFalse();
        assertThat(endpointStatus(A)).isEqualTo(404);
        assertThat(endpointStatus(B)).isEqualTo(200);
    }

    /** An exclusion writes the channel doc and evicts nothing: a write to a served channel's doc triggers the refresh. */
    @Test
    void anExclusionOnAServedChannelReachesTheLiveEndpointAtTheNextTick() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", "bbbbbbbbbbb"));
        cycleAt(T0);

        ((Channel) members.get(0)).getExcludedItems().getLiveStreams().add("aaaaaaaaaaa");
        poller.onCatalogChanged(new CatalogChanged("CHANNEL", "channel_" + A, false));
        poller.refreshIfStale();

        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
    }

    /** Support: a write the endpoint never serves (another channel, a playlist), and a tick with nothing stale, read nothing. */
    @Test
    void aWriteTheEndpointDoesNotServeReadsNothing() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        org.mockito.Mockito.clearInvocations(liveCategories);

        poller.refreshIfStale();
        poller.onCatalogChanged(new CatalogChanged("CHANNEL", "channel_" + B, false));
        poller.onCatalogChanged(new CatalogChanged("PLAYLIST", "channel_" + A, false));
        poller.refreshIfStale();

        verify(liveCategories, never()).liveCategoryIds();
    }

    /**
     * Stage 9 B14 (re-review P3-4): every public-cache eviction marked the view stale, so admin activity re-read the
     * categories every 15 s even with nothing served (prod has no LIVE category). With no entry, no write can change
     * what the endpoint serves: nothing is read. The other half, a served channel's moderation reaching the endpoint at
     * the next tick, is aModerationEvictionReachesTheLiveEndpointAtTheNextTick and aTickNeverWaitsOnARunningCycle...
     */
    @Test
    void withNothingServedAnEvictionReadsNothing() throws Exception {
        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>());
        cycleAt(T0);
        org.mockito.Mockito.clearInvocations(liveCategories);

        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();

        verify(liveCategories, never()).liveCategoryIds();
    }

    /** The tick never waits on a running cycle (its YouTube reads take minutes): it skips, keeps the flag, refreshes after. */
    @Test
    void aTickNeverWaitsOnARunningCycleAndRefreshesAfterIt() throws Exception {
        entries(A);
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read(anyString())).thenAnswer(i -> {
            reading.countDown();
            release.await(10, TimeUnit.SECONDS);
            return live("aaaaaaaaaaa");
        });
        Thread cycle = new Thread(() -> poller.cycle(T0));
        try {
            cycle.start();
            assertThat(reading.await(10, TimeUnit.SECONDS)).isTrue(); // the cycle is inside its YouTube read
            poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
            long started = System.nanoTime();
            poller.refreshIfStale();
            assertThat(System.nanoTime() - started).as("the tick waited on the cycle").isLessThan(TimeUnit.SECONDS.toNanos(5));
        } finally {
            release.countDown();
            cycle.join(10_000);
        }
        org.mockito.Mockito.clearInvocations(liveCategories);
        poller.refreshIfStale();
        verify(liveCategories).liveCategoryIds();                     // the skipped tick kept the flag
    }

    // The daily cap (an elapsed-time token bucket) and urgent-first targets.

    private LiveChannelPoller pollerWithDailyCap(int daily) {
        return new LiveChannelPoller(liveCategories, videos, states, live, audit, caches, breaker, clock, true, daily);
    }

    private void cycleAt(LiveChannelPoller p, long t) {
        now.set(t);
        p.cycle(t);
    }

    /**
     * The cycles get 2/3 of 864/day (4 tokens a cycle), the on-demand reads 1/3 (2 per 10 min), and a day of
     * both never passes 864 plus one read's debt per bucket.
     */
    @Test
    void theDailyCapHoldsOverADayWithManyDueChannelsAndEndpointCalls() {
        for (int i = 0; i < 300; i++) entries(String.format("UC%022d", i));
        AtomicLong spent = new AtomicLong();
        when(reader.read(anyString())).thenAnswer(inv -> {
            spent.addAndGet(5);
            return LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "t")), 5);
        });
        LiveChannelPoller capped = pollerWithDailyCap(864);

        capped.onDemand = Runnable::run;

        cycleAt(capped, D0);                                          // one UTC day: no rollover mid-test
        verify(reader, times(1)).read(anyString());                   // 4 tokens: 4 -> -1 (debt)
        for (int i = 0; i < 144; i++) {
            if (i > 0) cycleAt(capped, D0 + i * 10 * MIN);
            now.set(D0 + i * 10 * MIN + 3 * MIN);
            for (int c = 0; c < 10; c++) capped.refreshOnDemand(String.format("UC%022d", (i * 10 + c) % 300));
        }

        // a read costs at most 6 requests (1 + LiveTabReader.MAX_CONTINUATIONS): the most debt each bucket can end in
        assertThat(spent.get()).isLessThanOrEqualTo(864 + 2 * 6).isGreaterThan(800);
    }

    /**
     * A suspect LIVE channel (LIVE with no next poll) and one with counted misses go before quiet channels tried up to
     * URGENT_HEAD_START_MS (60 min) longer ago; past that the quiet ones go first (see the starvation test). An approved
     * live link is no head start (final model): its channel waits its turn.
     */
    @Test
    void urgentChannelsAreReadBeforeQuietOnesWhenTokensAreShort() throws Exception {
        List<LiveChannelState> saved = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String uc = String.format("UC%022d", i);
            entries(uc);
            LiveChannelState s = LiveChannelState.unknown(uc);
            s.setStatus(LiveChannelState.OFFLINE);
            s.setCheckedAt(T0 - 40 * MIN);
            saved.add(s);
        }
        String liveUc = "UCl0000000000000000000ll";
        String missUc = "UCm0000000000000000000mm";
        String linkUc = "UCk0000000000000000000kk";
        entries(liveUc, missUc);
        LiveChannelState l = LiveChannelState.unknown(liveUc);
        l.setStatus(LiveChannelState.LIVE);
        l.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        l.setCheckedAt(T0 - 5 * MIN);
        LiveChannelState m = LiveChannelState.unknown(missUc);
        m.setStatus(LiveChannelState.OFFLINE);
        m.getMisses().put("bbbbbbbbbbb", new LiveChannelState.Miss(1, T0 - 5 * MIN));
        m.setCheckedAt(T0 - 5 * MIN);
        LiveChannelState k = LiveChannelState.unknown(linkUc);
        k.setStatus(LiveChannelState.OFFLINE);
        k.setCheckedAt(T0 - 5 * MIN);
        saved.addAll(List.of(l, m, k));
        Video link = new Video("linkid00001");
        link.setId("doc-link");
        link.setStatus("APPROVED");
        link.setChannelId(linkUc);
        link.setCategoryIds(new ArrayList<>(List.of(CAT)));
        members.add(link);
        when(states.findAll()).thenReturn(saved);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller capped = pollerWithDailyCap(864);
        capped.afterSingletonsInstantiated();

        cycleAt(capped, T0);

        verify(reader, times(4)).read(anyString());                   // one 10-minute share of the cycles' 576/day
        verify(reader).read(liveUc);
        verify(reader).read(missUc);
        verify(reader, never()).read(linkUc);
    }

    /** Tokens live in memory; a restart starts from one cycle's share, never a burst, however long the old one idled. */
    @Test
    void aRestartDoesNotBurst() throws Exception {
        for (int i = 0; i < 40; i++) entries(String.format("UC%022d", i));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller before = pollerWithDailyCap(864);
        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>());
        for (int i = 0; i < 144; i++) cycleAt(before, T0 + i * 10 * MIN);   // a day with nothing to read: its bucket fills
        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>(List.of(CAT)));

        cycleAt(pollerWithDailyCap(864), T0 + DAY_MS);

        verify(reader, times(4)).read(anyString());
    }

    static final long DAY_MS = 1440 * MIN;
    /** A UTC midnight, so a test's day rollovers fall where it puts them. */
    static final long D0 = (T0 / DAY_MS + 1) * DAY_MS;

    private List<String> messages(Level level) {
        return logged(level).stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> dailyLines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains("YouTube requests on")).toList();
    }

    /** An open breaker accrues nothing; the first cycle after it gets one cycle's share. */
    @Test
    void noTokensAccrueWhileTheBreakerIsOpen() {
        for (int i = 0; i < 40; i++) entries(String.format("UC%022d", i));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller p = pollerWithDailyCap(864);
        when(breaker.isOpen()).thenReturn(true);
        for (int i = 0; i < 12; i++) cycleAt(p, D0 + (60 + i * 10) * MIN);
        when(breaker.isOpen()).thenReturn(false);

        cycleAt(p, D0 + 180 * MIN);

        verify(reader, times(4)).read(anyString());
    }

    /** Final model: the cap is fixed. A rate limit stops the cycle (R7) and never cuts the cap or the tokens. */
    @Test
    void aRateLimitNeverChangesTheDailyCap() {
        for (int i = 0; i < 40; i++) entries(String.format("UC%022d", i));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        when(reader.read(String.format("UC%022d", 0)))
                .thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"), LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller p = pollerWithDailyCap(864);

        cycleAt(p, D0 + 60 * MIN);                                    // 4 tokens; the first read is rate-limited: 3 left
        cycleAt(p, D0 + 70 * MIN);                                    // 3 + 4 = 7 tokens
        verify(reader, times(1 + 7)).read(anyString());
        cycleAt(p, D0 + DAY_MS + 60 * MIN);

        assertThat(messages(Level.WARN)).singleElement().satisfies(m -> assertThat(m).doesNotContain("daily cap"));
        assertThat(dailyLines()).singleElement().satisfies(m -> assertThat(m).endsWith("(shared-IP allotment 864/day)"));
    }

    /** The cap is clamped to at least 1 a day. */
    @Test
    void theDailyCapIsClampedToOne() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller zero = pollerWithDailyCap(0);

        cycleAt(zero, D0 + 60 * MIN);
        cycleAt(zero, D0 + DAY_MS + 60 * MIN);

        assertThat(dailyLines().get(0)).endsWith("(shared-IP allotment 1/day)");
    }

    /** 300 channels added at once queue among themselves; a due urgent channel still goes first. */
    @Test
    void newChannelsNeverOutrankADueUrgentOne() throws Exception {
        for (int i = 0; i < 300; i++) entries(String.format("UC%022d", i));
        String liveUc = "UCl0000000000000000000ll";
        entries(liveUc);
        LiveChannelState l = LiveChannelState.unknown(liveUc);
        l.setStatus(LiveChannelState.LIVE);
        l.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        l.setCheckedAt(T0 - 5 * MIN);
        when(states.findAll()).thenReturn(List.of(l));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller p = pollerWithDailyCap(864);
        p.afterSingletonsInstantiated();

        cycleAt(p, T0);

        verify(reader).read(liveUc);
    }

    /** Count-0 misses (hidden while unproven) are no end in progress, so no head start. */
    @Test
    void countZeroMissesAreNotUrgent() throws Exception {
        String hidden = "UCz0000000000000000000zz";
        String quiet = "UCq0000000000000000000qq";
        entries(hidden, quiet);
        LiveChannelState z = LiveChannelState.unknown(hidden);
        z.setStatus(LiveChannelState.OFFLINE);
        z.getMisses().put("aaaaaaaaaaa", new LiveChannelState.Miss(0, T0 - 5 * MIN));
        z.setCheckedAt(T0 - 5 * MIN);
        LiveChannelState q = LiveChannelState.unknown(quiet);
        q.setStatus(LiveChannelState.OFFLINE);
        q.setCheckedAt(T0 - 30 * MIN);
        when(states.findAll()).thenReturn(List.of(z, q));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller p = pollerWithDailyCap(216);                // the cycles' 144/day: 1 token a cycle
        p.afterSingletonsInstantiated();

        cycleAt(p, T0);

        verify(reader).read(quiet);
        verify(reader, never()).read(hidden);
    }

    /** Under the cap too, a LIVE target left unread 25 h past its last proof hides. */
    @Test
    void aTargetLeftUnreadPastTheHoldHides() throws Exception {
        List<LiveChannelState> saved = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String uc = String.format("UC%022d", i);
            entries(uc);
            LiveChannelState s = LiveChannelState.unknown(uc);
            s.setStatus(LiveChannelState.LIVE);
            s.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
            s.setCheckedAt(T0 - HOLD - 60 * MIN);
            saved.add(s);
        }
        when(states.findAll()).thenReturn(saved);
        poller.afterSingletonsInstantiated();
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "t")), 6));

        cycleAt(T0);

        verify(reader, times(15)).read(anyString());                  // 15 x 6 = 90: five channels unread
        assertThat(saved).filteredOn(s -> live.state(s.getChannelId()).getStatus().equals(LiveChannelState.OFFLINE)).hasSize(5);
    }

    /**
     * Urgent load over the cap never starves a quiet channel. Tokens for one read a cycle, three channels with
     * pending misses (urgent, due every cycle): urgent goes first by at most 60 min, so the quiet one is read by T0 + 90.
     */
    @Test
    void aQuietChannelIsStillReadWhenUrgentChannelsExceedTheCap() throws Exception {
        List<LiveChannelState> saved = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String uc = String.format("UCm%021d", i);
            entries(uc);
            LiveChannelState m = LiveChannelState.unknown(uc);
            m.setStatus(LiveChannelState.OFFLINE);
            m.getMisses().put("ended" + i + "00000", new LiveChannelState.Miss(1, T0 - 5 * MIN));
            m.setCheckedAt(T0 - 5 * MIN);
            saved.add(m);
        }
        String quiet = "UCq0000000000000000000qq";
        entries(quiet);
        LiveChannelState q = LiveChannelState.unknown(quiet);
        q.setStatus(LiveChannelState.OFFLINE);
        q.setCheckedAt(T0 - 5 * MIN);
        saved.add(q);
        when(states.findAll()).thenReturn(saved);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));   // unproven: misses stay pending
        LiveChannelPoller capped = pollerWithDailyCap(216);                           // the cycles' 144/day: 1 token a cycle
        capped.afterSingletonsInstantiated();

        for (int i = 0; i <= 9; i++) cycleAt(capped, T0 + i * 10 * MIN);

        verify(reader, times(10)).read(anyString());
        verify(reader).read(quiet);
    }

    /** With sparse reads, a cycle that reads nothing neither starts nor clears R10's unproven clock. */
    @Test
    void aCycleWithNoReadsLeavesTheUnprovenClock() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // proven; A is next due in 24 h

        cycleAt(T0 + 10 * MIN);                                       // nothing due: no read

        verify(reader, times(1)).read(A);
        assertThat(live.suspectSince()).isNull();
    }

    /** A LIVE channel due on its sweep gets no head start; only a counted miss or a suspect keep does. */
    @Test
    void aLiveTierChannelIsNotUrgent() throws Exception {
        String liveUc = "UCl0000000000000000000ll";
        String dead = "UCd0000000000000000000dd";
        entries(liveUc, dead);
        LiveChannelState l = LiveChannelState.unknown(liveUc);
        l.setStatus(LiveChannelState.LIVE);
        l.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        l.setCheckedAt(T0 - SWEEP);
        l.setNextPollAt(T0);                                          // due: its sweep is over
        LiveChannelState d = LiveChannelState.unknown(dead);
        d.setStatus(LiveChannelState.OFFLINE);
        d.setCheckedAt(T0 - SWEEP - 30 * MIN);
        when(states.findAll()).thenReturn(List.of(l, d));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller p = pollerWithDailyCap(216);                // the cycles' 144/day: 1 token a cycle
        p.afterSingletonsInstantiated();

        cycleAt(p, T0);

        verify(reader).read(dead);                                    // tried longer ago, no head start for the LIVE one
        verify(reader, never()).read(liveUc);
    }

    // ---- Owner ruling 2026-11-02: an ended link is hidden and flagged, never removed or archived ----

    /** R15 ends the link: hidden (ENDED), still in its LIVE category, its doc untouched, audited once. */
    @Test
    void anEndedLinkIsHiddenNotRemovedAndAuditedOnce() throws Exception {
        entries(A);
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // proven miss 1
        cycleAt(T0 + 10 * MIN);                                       // proven miss 2, only 10 min after the first
        org.mockito.Mockito.clearInvocations(caches);

        cycleAt(T0 + 20 * MIN);                                       // ends
        assertThat(live.state(A).getEnded()).containsExactly("linkid00001");
        verify(audit).logSystem("live_link_hidden_ended", "video", "doc-link", "live-poller");
        verify(caches).evictPublicContentCaches();                    // the category page stops listing it

        cycleAt(T0 + 30 * MIN);
        cycleAt(T0 + SWEEP + 30 * MIN);                               // the next sweep: no second end, no second audit
        verify(audit, times(1)).logSystem(anyString(), anyString(), anyString(), anyString());
        verify(videos, never()).save(any());
        assertThat(live.state(A).getMisses()).doesNotContainKey("linkid00001");
    }

    /** A later read listing the ended id live shows it again with no admin action. */
    @Test
    void anEndedLinkLiveAgainIsServedAgain() throws Exception {
        entries(A);
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);     // ended
        org.mockito.Mockito.clearInvocations(caches);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", "linkid00001"));

        cycleAt(T0 + 20 * MIN + SWEEP);

        assertThat(live.state(A).getEnded()).isEmpty();
        verify(caches).evictPublicContentCaches();
    }

    // ---- Final model: the endpoint asks for an on-demand read after a rotation ----

    /** One read, off the request thread; its result lands in the served snapshot and evicts (D14). */
    @Test
    void anEndpointCallReadsTheChannelOnce() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(live("bbbbbbbbbbb"));   // the stream rotated
        org.mockito.Mockito.clearInvocations(caches);

        now.set(T0 + 15 * MIN);
        assertThat(endpointStatus()).isEqualTo(200);

        verify(reader, times(2)).read(A);
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        verify(caches).evictPublicContentCaches();
    }

    /** I8: an id the endpoint does not list never costs a read, nor does any id before the first cycle's view. */
    @Test
    void anUnlistedChannelOrNoViewReadsNothing() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        poller.refreshOnDemand(A);                                    // no cycle yet: no view
        verify(reader, never()).read(anyString());

        cycleAt(T0);
        now.set(T0 + 15 * MIN);
        poller.refreshOnDemand(B);
        poller.refreshOnDemand("UCzzzzzzzzzzzzzzzzzzzzzz");

        verify(reader, times(1)).read(anyString());
    }


    /** One read per incident: the apps retry at 15/30/60/120 s, so an endpoint call reads at most every 2 min. */
    @Test
    void onDemandReadsAreTwoMinutesApart() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // swept: next due in 24 h, which on demand ignores
        now.set(T0 + 15 * MIN);
        poller.refreshOnDemand(A);
        verify(reader, times(2)).read(A);

        now.set(T0 + 16 * MIN);
        poller.refreshOnDemand(A);
        verify(reader, times(2)).read(A);
        now.set(T0 + 17 * MIN + 10_000);
        poller.refreshOnDemand(A);
        verify(reader, times(3)).read(A);
    }

    /** ... but an ERROR backoff (D3) holds for an endpoint call too. */
    @Test
    void anOnDemandReadWaitsOutAnErrorBackoff() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.error(1, "boom"));
        cycleAt(T0);                                                  // ERROR 1: next poll T0 + 10 min

        now.set(T0 + 5 * MIN);
        poller.refreshOnDemand(A);

        verify(reader, times(1)).read(A);
    }

    /** The on-demand read builds a fresh view: an entry a moderation write removed never comes back. */
    @Test
    void anOnDemandReadNeverRepublishesARemovedEntry() {
        entries(A, B);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        members.removeIf(m -> m instanceof Channel c && B.equals(c.getYoutubeId()));   // B leaves the LIVE category
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();
        assertThat(live.entries()).doesNotContainKey(B);

        now.set(T0 + 15 * MIN);
        poller.refreshOnDemand(A);

        verify(reader, times(2)).read(A);
        assertThat(live.entries()).doesNotContainKey(B);
        assertThat(live.served()).doesNotContainKey(B);
    }

    /** The on-demand reads have their own bucket (a third of the cap): draining it leaves the sweep's share. */
    @Test
    void aDrainedOnDemandBucketLeavesTheSweepsReads() {
        for (int i = 0; i < 10; i++) entries(String.format("UC%022d", i));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller p = pollerWithDailyCap(864);
        p.onDemand = Runnable::run;
        cycleAt(p, T0);                                               // the cycles' 4 tokens: channels 0-3
        verify(reader, times(4)).read(anyString());

        now.set(T0 + 3 * MIN);
        for (int i = 0; i < 10; i++) p.refreshOnDemand(String.format("UC%022d", i));
        verify(reader, times(4 + 2)).read(anyString());               // the on-demand share: 2 tokens, then empty

        cycleAt(p, T0 + 10 * MIN);
        verify(reader, times(4 + 2 + 4)).read(anyString());           // the cycle's 4, untouched
    }

    @Test
    void anEmptyBucketOrAnOpenBreakerReadsNothing() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        LiveChannelPoller p = pollerWithDailyCap(144);                // on demand: 48/day, a third of a token at first
        p.onDemand = Runnable::run;
        cycleAt(p, T0);                                               // publishes A (the cycles' 96/day: no read yet)
        now.set(T0 + 15 * MIN);
        p.refreshOnDemand(A);
        verify(reader, never()).read(A);

        cycleAt(T0 + 20 * MIN);                                       // the main poller reads A
        now.set(T0 + 40 * MIN);
        when(breaker.isOpen()).thenReturn(true);
        poller.refreshOnDemand(A);
        verify(reader, times(1)).read(A);
    }

    /** A call while a cycle runs reads nothing (the app retries within 2 min) and nothing is queued. */
    @Test
    void aCallDuringACycleReadsNothing() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // A swept: next due in 24 h
        CountDownLatch inView = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(liveCategories.liveCategoryIds()).thenAnswer(inv -> {
            inView.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return new LinkedHashSet<>(List.of(CAT));
        });
        now.set(T0 + 15 * MIN);
        Thread cycle = new Thread(() -> poller.cycle(T0 + 15 * MIN));
        cycle.start();
        try {
            assertThat(inView.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            poller.refreshOnDemand(A);
        } finally {
            release.countDown();
            cycle.join(10_000);
        }

        verify(reader, times(1)).read(A);
    }

    /** The on-demand YouTube read runs outside the cycle's lock, so a cycle never waits on it. */
    @Test
    void aCycleNeverWaitsOnAnOnDemandRead() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read(A)).thenAnswer(inv -> {
            inRead.countDown();
            release.await(60, java.util.concurrent.TimeUnit.SECONDS);   // outlasts the wait below: a regression fails
            return live("bbbbbbbbbbb");
        });
        List<Thread> onDemandThreads = new java.util.concurrent.CopyOnWriteArrayList<>();
        poller.onDemand = r -> {
            Thread t = new Thread(r);
            onDemandThreads.add(t);
            t.start();
        };
        now.set(T0 + 15 * MIN);
        poller.refreshOnDemand(A);
        try {
            assertThat(inRead.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            CountDownLatch cycleDone = new CountDownLatch(1);
            new Thread(() -> {
                poller.cycle(T0 + 15 * MIN);
                cycleDone.countDown();
            }).start();
            assertThat(cycleDone.await(20, java.util.concurrent.TimeUnit.SECONDS))
                    .as("the cycle waited on the on-demand YouTube read").isTrue();
        } finally {
            release.countDown();
            for (Thread t : onDemandThreads) t.join(10_000);
        }
    }
    /** Runs each on-demand task on its own thread, so a test can hold a read open; joined by {@link #joinAll}. */
    private List<Thread> threadedOnDemand() {
        List<Thread> threads = new java.util.concurrent.CopyOnWriteArrayList<>();
        poller.onDemand = r -> {
            Thread t = new Thread(r);
            threads.add(t);
            t.start();
        };
        return threads;
    }

    private static void joinAll(List<Thread> threads) throws InterruptedException {
        for (Thread t : threads) t.join(10_000);
    }

    /**
     * B3: a cycle that read the channel while an on-demand read was in flight wins. The older on-demand result is never
     * applied (no lives flap, checkedAt never moves back), but its request still counts against the day.
     */
    @Test
    void anOnDemandResultOlderThanACyclesReadIsCountedButNeverApplied() throws Exception {
        entries(A);
        endingLink();                                                 // a proven miss: A is due every cycle (R15)
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"))
                .thenAnswer(inv -> {
                    inRead.countDown();
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                    return live("bbbbbbbbbbb");
                })
                .thenReturn(live("ccccccccccc"));
        cycleAt(D0);
        List<Thread> threads = threadedOnDemand();
        now.set(D0 + 5 * MIN);
        poller.refreshOnDemand(A);
        try {
            assertThat(inRead.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            cycleAt(D0 + 10 * MIN);                                   // the cycle reads A meanwhile
            now.set(D0 + 11 * MIN);
        } finally {
            release.countDown();
            joinAll(threads);
        }

        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("ccccccccccc");
        assertThat(live.state(A).getCheckedAt()).isEqualTo(D0 + 10 * MIN);
        cycleAt(D0 + DAY_MS);
        assertThat(dailyLines()).singleElement().satisfies(m -> assertThat(m)
                .startsWith("live poller: 3 YouTube requests on " + java.time.LocalDate.ofEpochDay(D0 / DAY_MS)));
    }

    /**
     * B3: an on-demand result is applied and counted at the time its lock was taken, never at the read's start; and a
     * cycle whose time was taken before a lock wait across midnight never reopens the day already closed.
     */
    @Test
    void anOnDemandResultLandsAtItsLockTimeAndTheDayNeverGoesBack() throws Exception {
        entries(A);
        CountDownLatch inRead = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa")).thenAnswer(inv -> {
            inRead.countDown();
            release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return live("bbbbbbbbbbb");
        });
        cycleAt(D0 - 20 * MIN);
        List<Thread> threads = threadedOnDemand();
        now.set(D0 - MIN);
        poller.refreshOnDemand(A);
        try {
            assertThat(inRead.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            now.set(D0 + MIN);                                        // the read ends after midnight
        } finally {
            release.countDown();
            joinAll(threads);
        }

        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        assertThat(live.state(A).getCheckedAt()).isEqualTo(D0 + MIN);
        assertThat(dailyLines()).singleElement().satisfies(m -> assertThat(m)
                .startsWith("live poller: 1 YouTube requests on " + java.time.LocalDate.ofEpochDay(D0 / DAY_MS - 1)));

        poller.cycle(D0 - 30_000);                                    // its time taken before midnight
        assertThat(dailyLines()).hasSize(1);
    }

    /** B7c: one on-demand read per channel in flight; a rejected task frees the channel for a later call. */
    @Test
    void onDemandReadsAreSingleFlightAndARejectedTaskFreesTheChannel() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        List<Runnable> queued = new ArrayList<>();
        poller.onDemand = queued::add;
        now.set(T0 + 15 * MIN);

        poller.refreshOnDemand(A);
        poller.refreshOnDemand(A);
        assertThat(queued).hasSize(1);
        queued.get(0).run();
        verify(reader, times(2)).read(A);

        poller.onDemand = r -> {
            throw new java.util.concurrent.RejectedExecutionException("shut down");
        };
        now.set(T0 + 20 * MIN);
        poller.refreshOnDemand(A);
        verify(reader, times(2)).read(A);
        poller.onDemand = Runnable::run;
        poller.refreshOnDemand(A);
        verify(reader, times(3)).read(A);
    }

    /** B7d: a rate-limited on-demand read changes no lives or misses, but counts as an attempt (the 2-min floor). */
    @Test
    void aRateLimitedOnDemandReadChangesNothingButHoldsTheFloor() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        now.set(T0 + 15 * MIN);
        poller.refreshOnDemand(A);
        verify(reader, times(2)).read(A);
        assertThat(live.state(A).getLives()).extracting(LiveChannelState.Live::getVideoId).containsExactly("aaaaaaaaaaa");
        assertThat(live.state(A).getMisses()).isEmpty();
        assertThat(live.state(A).getCheckedAt()).isEqualTo(T0);
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");

        now.set(T0 + 16 * MIN);
        poller.refreshOnDemand(A);
        verify(reader, times(2)).read(A);
    }

    /** B7e: a day whose requests pass the allotment closes with a WARN. */
    @Test
    void aDayOverTheAllotmentLogsAWarning() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 6));
        LiveChannelPoller p = pollerWithDailyCap(1);
        cycleAt(p, D0);                                               // a cycle's share: no read
        cycleAt(p, D0 + 36 * 60 * MIN);                               // a token after 1.5 days: one read of 6 requests
        verify(reader, times(1)).read(A);

        cycleAt(p, D0 + 2 * DAY_MS + 60 * MIN);

        assertThat(messages(Level.WARN)).singleElement().satisfies(m -> assertThat(m)
                .isEqualTo("live poller: 6 YouTube requests on " + java.time.LocalDate.ofEpochDay(D0 / DAY_MS + 1)
                        + " (shared-IP allotment 1/day)"));
    }

    // ---- Exact live links: GET /api/v1/live/links/{videoId} follows moderation and R15 from the in-memory view ----

    private int linkStatus(String videoId) {
        try {
            return new LiveChannelController(new LiveEndpointService(live, poller, videos)).link(videoId).getStatusCode().value();
        } catch (ResourceNotFoundException e) {
            return 404;
        }
    }

    /** Owner ruling: a link taken out of its LIVE category stops phones already playing it, though it stays approved. */
    @Test
    void aLinkThatLeavesItsCategoryIs404AtTheNextTick() throws Exception {
        endingLink();                                                 // a category of links only: no channel entry
        when(reader.read(anyString())).thenReturn(live("linkid00001"));
        cycleAt(T0);
        assertThat(linkStatus("linkid00001")).isEqualTo(204);

        members.clear();                                              // the link left the category
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();

        assertThat(linkStatus("linkid00001")).isEqualTo(404);
        verify(reader, times(1)).read(anyString());                   // Firestore only: no YouTube read
    }

    @Test
    void anUnapprovedLinkIs404AtTheNextTick() throws Exception {
        endingLink();
        when(reader.read(anyString())).thenReturn(live("linkid00001"));
        cycleAt(T0);

        ((Video) members.get(0)).setStatus("PENDING");
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();

        assertThat(linkStatus("linkid00001")).isEqualTo(404);
    }

    /** Cubic r6 K2: ENDED hides a link from its category's list, but is never a removal: the removal check stays 204. */
    @Test
    void anEndedLinkIsStill204() throws Exception {
        entries(A);
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // proven miss 1
        assertThat(linkStatus("linkid00001")).isEqualTo(204);
        cycleAt(T0 + 10 * MIN);
        cycleAt(T0 + 20 * MIN);                                       // ends

        assertThat(live.state(A).getEnded()).containsExactly("linkid00001");
        assertThat(linkStatus("linkid00001")).isEqualTo(204);
        assertThat(linkStatus("aaaaaaaaaaa")).isEqualTo(404);         // a channel's live is no link
    }

    /** A link of channel A in the LIVE category, not yet in any view. */
    private void linkLands(String id) {
        Video link = new Video(id);
        link.setId("doc-" + id);
        link.setStatus("APPROVED");
        link.setChannelId(A);
        link.setCategoryIds(new ArrayList<>(List.of(CAT)));
        link.setValidationStatus(ValidationStatus.VALID);
        members.add(link);
    }

    /**
     * Cubic r4 R4-1: the apps list a link as soon as the caches are evicted (LiveCategoryService reads Firestore), but the
     * endpoint knows only the last view. While a refresh is pending a link the view lacks is 503 (keep playing), never
     * the 404 (stop). Once it lands, a removed link is 404 and an added one 204.
     */
    @Test
    void aLinkTheViewLacksIs503WhileARefreshIsPendingThenTheRefreshDecides() throws Exception {
        entries(A);
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", "linkid00001"));
        cycleAt(T0);
        assertThat(linkStatus("linkid00001")).isEqualTo(204);

        members.removeIf(m -> m instanceof Video);                     // linkid00001 removed,
        linkLands("linkid00002");                                      // linkid00002 added
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        assertThat(linkStatus("linkid00002")).isEqualTo(503);
        assertThat(linkStatus("linkid00001")).isEqualTo(204);          // the last view still serves it

        poller.refreshIfStale();
        assertThat(linkStatus("linkid00001")).isEqualTo(404);
        assertThat(linkStatus("linkid00002")).isEqualTo(204);
    }

    /** R4-1 (a): the 15 s tick cannot refresh while a cycle runs, so a link the view lacks is 503 until it ends. */
    @Test
    void aLinkTheViewLacksIs503WhileACycleRuns() throws Exception {
        entries(A);
        when(states.findAll()).thenReturn(List.of());
        poller.afterSingletonsInstantiated();                          // a published view
        java.util.concurrent.atomic.AtomicInteger during = new java.util.concurrent.atomic.AtomicInteger();
        when(reader.read(anyString())).thenAnswer(i -> {
            during.set(linkStatus("linkid00002"));
            return live("aaaaaaaaaaa");
        });

        cycleAt(T0);

        assertThat(during.get()).isEqualTo(503);
        assertThat(linkStatus("linkid00002")).isEqualTo(404);
    }

    /**
     * R4-1 (b): with nothing served an eviction marks nothing stale (B14), so the first link of an otherwise empty LIVE
     * setup waited for the next cycle. The guard's check of the add (before its write) makes the write's eviction count.
     */
    @Test
    void theFirstLinkOfAnEmptySetupMarksTheViewStale() throws Exception {
        when(states.findAll()).thenReturn(List.of());
        poller.afterSingletonsInstantiated();                          // a LIVE category, nothing served

        live.liveAddChecked();                                         // the guard, on the add
        linkLands("linkid00002");                                      // the add's write lands
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        assertThat(linkStatus("linkid00002")).isEqualTo(503);

        poller.refreshIfStale();
        assertThat(linkStatus("linkid00002")).isEqualTo(204);
    }

    /** R4-1 on the channel endpoint: a channel the view lacks while a refresh is pending is 503, then the refresh decides. */
    @Test
    void aChannelTheViewLacksIs503WhileARefreshIsPending() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);

        entries(B);                                                    // e.g. a visibility flip: no guard seed
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        assertThat(endpointStatus(B)).isEqualTo(503);

        poller.refreshIfStale();
        assertThat(endpointStatus(B)).isEqualTo(200);
        assertThat(endpointStatus(C)).isEqualTo(404);
    }

    /** A LIVE flag cleared from the last category publishes no links. */
    @Test
    void noLiveCategoryServesNoLink() throws Exception {
        endingLink();
        when(reader.read(anyString())).thenReturn(live("linkid00001"));
        cycleAt(T0);

        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>());
        cycleAt(T0 + 10 * MIN);

        assertThat(linkStatus("linkid00001")).isEqualTo(404);
    }

    // ---- Cubic round 2 ----

    static final long HOUR = 60 * MIN;

    /** Cubic r2 #1: a channel read offline at 10:00 that streams 12:00-14:00 is listed by its 14:00 read, not a day later. */
    @Test
    void aChannelReadOfflineIsReadAgainInFourHours() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));     // live from T0 + 2 h

        for (long t = T0 + 10 * MIN; t <= T0 + 4 * HOUR; t += 10 * MIN) cycleAt(t);

        verify(reader, times(2)).read(A);
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
    }

    /**
     * Cubic r2 #1: 500 channels read offline want 3,000 reads a day, the cycles get 576. The cap holds, and oldest-first
     * spreads the reads: no channel starves, new ones included, while the ones read first come due every 4 h.
     */
    @Test
    void theDailyCapHoldsWithManyOfflineChannelsAndNoneStarves() {
        for (int i = 0; i < 500; i++) entries(String.format("UC%022d", i));
        java.util.Map<String, Integer> reads = new java.util.concurrent.ConcurrentHashMap<>();
        when(reader.read(anyString())).thenAnswer(inv -> {
            reads.merge(inv.getArgument(0), 1, Integer::sum);
            return LiveTabResult.ok(List.of(), 1);
        });
        LiveChannelPoller capped = pollerWithDailyCap(864);

        for (int i = 0; i < 144; i++) cycleAt(capped, D0 + i * 10 * MIN);
        int firstDay = reads.values().stream().mapToInt(Integer::intValue).sum();
        for (int i = 144; i < 3 * 144; i++) cycleAt(capped, D0 + i * 10 * MIN);

        int total = reads.values().stream().mapToInt(Integer::intValue).sum();
        assertThat(firstDay).isLessThanOrEqualTo(576 + 1);                // one read's debt at most
        assertThat(total).isLessThanOrEqualTo(3 * 576 + 1).isGreaterThan(3 * 576 - 10);
        assertThat(reads).hasSize(500);
        assertThat(reads.values()).allSatisfy(n -> assertThat(n).isGreaterThanOrEqualTo(3));   // 1728 reads / 500
    }

    private LiveChannelState offlineDueAt(String uc, long due) {
        LiveChannelState s = LiveChannelState.unknown(uc);
        s.setStatus(LiveChannelState.OFFLINE);
        s.setCheckedAt(due - 4 * HOUR);
        s.setNextPollAt(due);
        return s;
    }

    static final String C = "UCc0000000000000000000cc";
    static final String D = "UCd0000000000000000000dd";
    static final String E = "UCe0000000000000000000ee";

    /**
     * A reads live at T0, then has really ended; B and C read empty at T0 + 1 h, D at T0 + 1 h 50, E at T0 + 2 h. Nothing
     * else is due. At T0 + 2 h the window has lapsed, so A is re-read first (the proof probe, Cubic r5 R5-1): empty.
     */
    private void sparseReads() throws Exception {
        entries(A, B, C, D, E);
        when(states.findAll()).thenReturn(List.of(offlineDueAt(B, T0 + HOUR), offlineDueAt(C, T0 + HOUR),
                offlineDueAt(D, T0 + 110 * MIN), offlineDueAt(E, T0 + 120 * MIN)));
        poller.afterSingletonsInstantiated();
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"), LiveTabResult.ok(List.of(), 1));
        for (long t = T0; t <= T0 + 120 * MIN; t += 10 * MIN) {
            cycleAt(t);
            if (t == T0 + HOUR) assertThat(live.suspectSince()).as("two offline reads an hour after a live").isNull();
            if (t == T0 + 110 * MIN) assertThat(live.suspectSince()).as("1 h 50 after the live").isNull();
        }
        verify(reader, times(2)).read(A);                              // T0, and the probe at T0 + 2 h
        verify(reader, times(1)).read(E);
    }

    /** Cubic r2 #2: proven is a rolling 2 h window; a few empty reads within it of a live read stay proven. */
    @Test
    void emptyReadsWithinTwoHoursOfALiveReadStayProven() throws Exception {
        sparseReads();
    }

    /** Cubic r2 #2: past 2 h of reads that all came back empty, the system is unproven from the first such read. */
    @Test
    void twoHoursOfOnlyEmptyReadsIsUnproven() throws Exception {
        sparseReads();
        assertThat(live.suspectSince()).isEqualTo(T0 + 120 * MIN);
        cycleAt(T0 + 130 * MIN);                                      // A's suspect-keep read, empty: changes nothing
        assertThat(live.suspectSince()).isEqualTo(T0 + 120 * MIN);
    }

    /** Cubic r2 #2: an on-demand read that lists a live proves the system again. */
    @Test
    void anOnDemandLiveReadClearsTheUnprovenClock() throws Exception {
        sparseReads();
        now.set(T0 + 125 * MIN);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));

        assertThat(endpointStatus(A)).isEqualTo(200);

        verify(reader, times(3)).read(A);
        assertThat(live.suspectSince()).isNull();
    }

    /** Cubic r2 #2: the last proof is persisted on the states (liveSeenAt), so a restart keeps it. */
    @Test
    void theLastProofSurvivesARestart() throws Exception {
        entries(A, B);
        LiveChannelState a = savedLive();
        a.setCheckedAt(T0 - 30 * MIN);
        a.setNextPollAt(T0 + SWEEP);
        a.setLiveSeenAt(T0 - 30 * MIN);
        when(states.findAll()).thenReturn(List.of(a, offlineDueAt(B, T0)));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        poller.afterSingletonsInstantiated();

        cycleAt(T0);

        verify(reader).read(B);
        assertThat(live.suspectSince()).isNull();
        verify(states).save(argThat(s -> s.getChannelId().equals(B)));
    }

    /** Cubic r2 #2: a proof older than the window, or none (docs written before liveSeenAt), proves nothing (R10 as before). */
    @Test
    void aStaleOrMissingProofIsUnproven() throws Exception {
        entries(A, B);
        LiveChannelState a = savedLive();
        a.setCheckedAt(T0 - 3 * HOUR);
        a.setNextPollAt(T0 + SWEEP);
        a.setLiveSeenAt(T0 - 3 * HOUR);
        when(states.findAll()).thenReturn(List.of(a, offlineDueAt(B, T0)));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        poller.afterSingletonsInstantiated();
        cycleAt(T0);
        assertThat(live.suspectSince()).isEqualTo(T0);

        a.setLiveSeenAt(null);
        LiveChannelService restarted = new LiveChannelService(reader, states, clock);
        LiveChannelPoller p = new LiveChannelPoller(liveCategories, videos, states, restarted, audit, caches, breaker, clock, true, 1_000_000);
        when(states.findAll()).thenReturn(List.of(a, offlineDueAt(B, T0 + HOUR)));
        p.afterSingletonsInstantiated();
        now.set(T0 + HOUR);
        p.cycle(T0 + HOUR);
        assertThat(restarted.suspectSince()).isEqualTo(T0 + HOUR);
        assertThat(restarted.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);   // R10: no hide on no proof
    }

    /**
     * Cubic r5 R5-1: one 24/7 channel (swept daily) and one offline channel (rechecked every 4 h). Each recheck is past
     * the 2 h window, so its cycle first re-reads the 24/7 channel (the proof probe): proven all day, at most every 2 h.
     */
    @Test
    void aSmallSetupStaysProvenAllDayAndProbesAtMostEveryTwoHours() {
        entries(A, B);
        List<Long> readsOfA = new ArrayList<>();
        when(reader.read(B)).thenReturn(LiveTabResult.ok(List.of(), 1));
        when(reader.read(A)).thenAnswer(i -> {
            readsOfA.add(now.get());
            return live("aaaaaaaaaaa");
        });

        for (long t = T0; t <= T0 + SWEEP; t += 10 * MIN) {
            cycleAt(t);
            assertThat(live.suspectSince()).as("suspect at %d min", (t - T0) / MIN).isNull();
        }

        assertThat(logged(Level.ERROR)).isEmpty();
        assertThat(readsOfA).hasSizeGreaterThan(2);                               // probes did run
        for (int i = 1; i < readsOfA.size(); i++) {
            assertThat(readsOfA.get(i) - readsOfA.get(i - 1)).isGreaterThanOrEqualTo(120 * MIN);
        }
    }

    /** B: LIVE, last listed a live 3 h ago, not due (its daily sweep); A: offline, due now, with a link in the category. */
    private void lapsedProofWithADueLink() throws Exception {
        lapsedProofWithADueLink(T0 - 3 * HOUR);
    }

    private void lapsedProofWithADueLink(Long liveSeenAt) throws Exception {
        entries(A, B);
        endingLink();
        LiveChannelState b = LiveChannelState.unknown(B);
        b.setStatus(LiveChannelState.LIVE);
        b.getLives().add(new LiveChannelState.Live("bbbbbbbbbbb", "t"));
        b.setCheckedAt(T0 - 3 * HOUR);
        b.setNextPollAt(T0 - 3 * HOUR + SWEEP);
        b.setLiveSeenAt(liveSeenAt);
        when(states.findAll()).thenReturn(List.of(b, offlineDueAt(A, T0)));
        poller.afterSingletonsInstantiated();
        when(reader.read(A)).thenReturn(LiveTabResult.ok(List.of(), 1));
    }

    /** Cubic r5 R5-1: a probe that lists a live proves the cycle before its other reads are judged: A's miss counts. */
    @Test
    void aProbeThatFindsALiveLetsTheCyclesEmptyReadCountAMiss() throws Exception {
        lapsedProofWithADueLink();
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));

        cycleAt(T0);

        InOrder order = inOrder(reader);
        order.verify(reader).read(B);
        order.verify(reader).read(A);
        assertThat(live.suspectSince()).isNull();
        assertThat(live.state(A).getMisses().get("linkid00001").getCount()).isEqualTo(1);
    }

    /** Cubic r5 R5-1: a probe that lists nothing leaves the run unproven, as before: no miss counted. */
    @Test
    void anEmptyProbeLeavesTheRunUnproven() throws Exception {
        lapsedProofWithADueLink();
        when(reader.read(B)).thenReturn(LiveTabResult.ok(List.of(), 1));

        cycleAt(T0);

        verify(reader).read(B);
        assertThat(live.suspectSince()).isEqualTo(T0);
        assertThat(live.state(A).getMisses()).isEmpty();
    }

    /**
     * Cubic r5 R5-1: a probed channel that really ended is not probed every cycle. Offline channels due every cycle
     * keep the cycles reading; once B is hidden (an hour unproven), it is read at most every 2 h (its probes).
     */
    @Test
    void anEndedChannelIsProbedAtMostEveryTwoHours() throws Exception {
        List<LiveChannelState> saved = new ArrayList<>();
        LiveChannelState b = LiveChannelState.unknown(B);
        b.setStatus(LiveChannelState.LIVE);
        b.getLives().add(new LiveChannelState.Live("bbbbbbbbbbb", "t"));
        b.setCheckedAt(T0 - 3 * HOUR);
        b.setNextPollAt(T0 - 3 * HOUR + SWEEP);
        b.setLiveSeenAt(T0 - 3 * HOUR);
        saved.add(b);
        entries(B);
        for (int i = 0; i < 24; i++) {                                 // one offline recheck due every 10 min
            String uc = String.format("UC%022d", i);
            entries(uc);
            saved.add(offlineDueAt(uc, T0 + i * 10 * MIN));
        }
        when(states.findAll()).thenReturn(saved);
        poller.afterSingletonsInstantiated();
        List<Long> readsOfB = new ArrayList<>();
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        when(reader.read(B)).thenAnswer(i -> {
            if (now.get() > T0 + HOUR) readsOfB.add(now.get());
            return LiveTabResult.ok(List.of(), 1);
        });

        for (long t = T0; t <= T0 + 12 * HOUR; t += 10 * MIN) cycleAt(t);

        assertThat(live.state(B).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(readsOfB).isNotEmpty();
        for (int i = 1; i < readsOfB.size(); i++) {
            assertThat(readsOfB.get(i) - readsOfB.get(i - 1)).isGreaterThanOrEqualTo(120 * MIN);
        }
    }

    /** Cubic r5 R5-1: with no liveSeenAt anywhere (fresh install, old docs) nothing is probed; R10 as before. */
    @Test
    void noProbeWithoutAnyLiveSeen() throws Exception {
        lapsedProofWithADueLink(null);
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));

        cycleAt(T0);

        verify(reader, never()).read(B);
        assertThat(live.suspectSince()).isEqualTo(T0);
    }

    /** Cubic r5 R5-1: the probe spends the cycle's budget like any read; with one token, the due channel waits. */
    @Test
    void aProbeSpendsTheCycleBudget() throws Exception {
        lapsedProofWithADueLink();
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        LiveChannelPoller oneToken = pollerWithDailyCap(216);               // 144 cycle reads a day: one per cycle

        cycleAt(oneToken, T0);

        verify(reader).read(B);
        verify(reader, never()).read(A);
    }

    // Cubic r2 #3: the apps send ?live=<current id> every 60 s while playing.

    static final String CANDIDATE = "bbbbbbbbbbb";

    private List<String> heartbeat(LiveChannelController c) {
        return c.channel(A, CANDIDATE).getBody().lives().stream().map(LiveChannelController.Live::id).toList();
    }

    /** A candidate the snapshot does not list is judged once per published view: one registry lookup, one read ask. */
    @Test
    void heartbeatsJudgeACandidateOncePerView() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        LiveChannelPoller asked = spy(poller);
        LiveChannelController c = new LiveChannelController(new LiveEndpointService(live, asked, videos));
        org.mockito.Mockito.clearInvocations(videos);

        now.set(T0 + MIN);
        assertThat(heartbeat(c)).containsExactly(CANDIDATE);
        now.set(T0 + 2 * MIN + 30_000);
        assertThat(heartbeat(c)).containsExactly(CANDIDATE);

        verify(videos, times(1)).findByYoutubeIds(java.util.Set.of(CANDIDATE));
        verify(asked, times(1)).refreshStale(A);
        verify(asked, never()).refreshOnDemand(A);                   // read a minute ago: it would miss it again
    }

    /**
     * Cubic r9: a judged candidate the reads keep missing (not on the Live tab's first page, say) asks for one read per
     * STALE_ASK_MS of the channel's last, not one per heartbeat: each read's state write clears the verdict.
     */
    @Test
    void aCandidateTheReadsMissAsksForAReadEveryThirtyMinutesAtMost() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        LiveChannelController c = new LiveChannelController(new LiveEndpointService(live, poller, videos));

        for (long t = 31; t <= 90; t++) {                             // a heartbeat every minute for an hour
            now.set(T0 + t * MIN);
            live.clearVerdicts();                                     // any channel's state write clears them all
            assertThat(heartbeat(c)).containsExactly(CANDIDATE);
        }

        verify(reader, times(3)).read(A);                             // the cycle, T0 + 31 and T0 + 61 (unthrottled: ~30)
    }

    /** Codex r6: a heartbeat's read queued behind others, run after a cycle read the channel, reads nothing. */
    @Test
    void aQueuedHeartbeatReadAfterACycleReadIsDropped() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live());            // off air: the 4 h recheck reads it again
        cycleAt(T0);
        List<Runnable> queued = new ArrayList<>();
        poller.onDemand = queued::add;                                // the on-demand worker is busy
        now.set(T0 + 4 * HOUR - 3 * MIN);
        poller.refreshStale(A);                                       // stale: queued
        assertThat(queued).hasSize(1);

        cycleAt(T0 + 4 * HOUR);                                       // the recheck reads A meanwhile
        now.set(T0 + 4 * HOUR + 3 * MIN);
        queued.forEach(Runnable::run);

        verify(reader, times(2)).read(A);                             // the two cycles only
    }

    /** Codex r5: a failed read (no new checkedAt) counts as the channel's last too: still one per 30 min. */
    @Test
    void failedReadsCountTowardsTheThirtyMinutes() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));
        LiveChannelController c = new LiveChannelController(new LiveEndpointService(live, poller, videos));

        for (long t = 31; t <= 90; t++) {
            now.set(T0 + t * MIN);
            live.clearVerdicts();
            heartbeat(c);
        }

        verify(reader, times(3)).read(A);                             // the cycle, T0 + 31 and T0 + 61
    }

    /** A moderation event, or any published view, re-judges: a take-down answers [] at the next heartbeat. */
    @Test
    void aModerationEventOrAPublishReJudgesTheCandidate() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        LiveChannelController c = new LiveChannelController(new LiveEndpointService(live, poller, videos));
        now.set(T0 + MIN);
        assertThat(heartbeat(c)).containsExactly(CANDIDATE);

        Video doc = new Video(CANDIDATE);
        doc.setStatus("REJECTED");
        when(videos.findByYoutubeIds(java.util.Set.of(CANDIDATE))).thenReturn(Map.of(CANDIDATE, doc));
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        assertThat(heartbeat(c)).isEmpty();                                // before the 15 s tick

        when(videos.findByYoutubeIds(java.util.Set.of(CANDIDATE))).thenReturn(Map.of());
        poller.onCatalogChanged(new CatalogChanged("VIDEO", "doc-b", false));
        assertThat(heartbeat(c)).containsExactly(CANDIDATE);

        when(videos.findByYoutubeIds(java.util.Set.of(CANDIDATE))).thenReturn(Map.of(CANDIDATE, doc));
        poller.refreshView();                                               // a published view
        assertThat(heartbeat(c)).isEmpty();
    }

    /** A channel removed from its LIVE category is the definitive 404 at the next tick, a cached verdict or not. */
    @Test
    void aRemovedChannelIs404ForAHeartbeatAtTheNextTick() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        LiveChannelController c = new LiveChannelController(new LiveEndpointService(live, poller, videos));
        now.set(T0 + MIN);
        assertThat(heartbeat(c)).containsExactly(CANDIDATE);

        members.clear();
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();

        org.junit.jupiter.api.Assertions.assertThrows(ResourceNotFoundException.class, () -> heartbeat(c));
    }

    // ---- Codex / Cubic round 6 ----

    /** Spends the endpoint's candidate lookup budget at the current time on made-up ids. */
    private void spendLookupBudget() {
        int i = 0;
        while (live.candidateVerdict(A, "junk" + i++, () -> true) != null) {
            assertThat(i).isLessThan(10_000);
        }
    }

    /** Codex r6 C1: once a take-down's refresh drops a live, its heartbeat is [] with the budget spent: no 503, no lookup. */
    @Test
    void aTakenDownLiveIsOfflineForAHeartbeatWithTheBudgetSpent() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", CANDIDATE));
        cycleAt(T0);
        Video doc = new Video(CANDIDATE);
        doc.setStatus("REJECTED");
        when(videos.findByYoutubeIds(any())).thenReturn(Map.of(CANDIDATE, doc));
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();
        spendLookupBudget();
        org.mockito.Mockito.clearInvocations(videos);

        var answer = new LiveChannelController(new LiveEndpointService(live, poller, videos)).channel(A, CANDIDATE);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody().lives()).isEmpty();
        verify(videos, never()).findByYoutubeIds(any());
    }

    /** Codex r6 C1: an exclusion is on the in-memory channel doc: [] with the budget spent, no lookup. */
    @Test
    void anExcludedLiveIsOfflineForAHeartbeatWithTheBudgetSpent() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", CANDIDATE));
        cycleAt(T0);
        ((Channel) members.get(0)).getExcludedItems().getLiveStreams().add(CANDIDATE);
        poller.onCatalogChanged(new CatalogChanged("CHANNEL", "channel_" + A, false));
        poller.refreshIfStale();
        spendLookupBudget();
        org.mockito.Mockito.clearInvocations(videos);

        var answer = new LiveChannelController(new LiveEndpointService(live, poller, videos)).channel(A, CANDIDATE);

        assertThat(answer.getStatusCode().value()).isEqualTo(200);
        assertThat(answer.getBody().lives()).isEmpty();
        verify(videos, never()).findByYoutubeIds(any());
    }

    /**
     * Codex r6 C2: a view published while a candidate is judged (here: an exclusion landing during the registry lookup)
     * makes that judgement no verdict (503), never a cached answer; the next heartbeat judges the new view.
     */
    @Test
    void aPublishDuringAJudgementIs503AndNeverCached() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        LiveChannelController c = new LiveChannelController(new LiveEndpointService(live, poller, videos));
        when(videos.findByYoutubeIds(java.util.Set.of(CANDIDATE))).thenAnswer(i -> {
            ((Channel) members.get(0)).getExcludedItems().getLiveStreams().add(CANDIDATE);
            poller.refreshView();
            return Map.of();
        });

        assertThat(c.channel(A, CANDIDATE).getStatusCode().value()).isEqualTo(503);
        assertThat(heartbeat(c)).isEmpty();
    }

    /** Codex r6 C3: a scheduled cycle that waited on the lock takes its time once it holds it, never before. */
    @Test
    void aScheduledCycleTakesItsTimeAfterTheLock() throws Exception {
        entries(A);
        when(states.findAll()).thenReturn(List.of());
        poller.afterSingletonsInstantiated();                          // a published view; A never read
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(liveCategories.liveCategoryIds()).thenAnswer(i -> {
            if (first.getAndSet(false)) {                              // the refresh holds the lock here
                holding.countDown();
                release.await(10, TimeUnit.SECONDS);
            }
            return new LinkedHashSet<>(List.of(CAT));
        });
        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        Thread refresh = new Thread(poller::refreshIfStale);
        Thread cycle = new Thread(poller::scheduledCycle);
        refresh.start();
        try {
            assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
            now.set(T0);
            cycle.start();
            long deadline = System.currentTimeMillis() + 10_000;
            while (cycle.getState() != Thread.State.WAITING && System.currentTimeMillis() < deadline) Thread.sleep(5);
            assertThat(cycle.getState()).isEqualTo(Thread.State.WAITING);   // parked on the lock
            now.set(T0 + 3 * MIN);
        } finally {
            release.countDown();
            refresh.join(10_000);
            cycle.join(10_000);
        }

        assertThat(live.state(A).getCheckedAt()).isEqualTo(T0 + 3 * MIN);
    }

    /** Codex r6 C4: an ended link taken out of its category leaves no tombstone; a view with nothing to prune writes nothing. */
    @Test
    void anEndedLinkRemovedFromItsCategoryIsPrunedFromEnded() throws Exception {
        entries(A);
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);     // ended
        assertThat(live.state(A).getEnded()).containsExactly("linkid00001");
        members.removeIf(m -> m instanceof Video);
        org.mockito.Mockito.clearInvocations(states);

        poller.onPublicContentEvicted(new PublicContentCacheService.Evicted());
        poller.refreshIfStale();
        poller.refreshView();

        assertThat(live.state(A).getEnded()).isEmpty();
        verify(states, times(1)).save(argThat(s -> s.getEnded().isEmpty()));
        verify(states, times(1)).save(any());
    }

    /**
     * Cubic r6 K1 (b): a phone playing an ENDED link keeps playing (K2), and its heartbeat asks for a read of the link's
     * channel, a poll target though no entry: a stream live again is shown again within minutes, not 4-24 h.
     */
    @Test
    void anEndedLinksHeartbeatReadsItsChannelAndAStreamLiveAgainIsShownAgain() throws Exception {
        endingLink();                                                 // a category of links only: A is no entry
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);     // ended
        assertThat(live.state(A).getEnded()).containsExactly("linkid00001");
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", "linkid00001"));

        now.set(T0 + 25 * MIN);                                       // 5 min after the last read: no ask (Cubic r8)
        assertThat(linkStatus("linkid00001")).isEqualTo(204);
        verify(reader, times(3)).read(A);

        now.set(T0 + 50 * MIN);                                       // past STALE_ASK_MS: one read
        assertThat(linkStatus("linkid00001")).isEqualTo(204);

        verify(reader, times(4)).read(A);
        assertThat(live.state(A).getEnded()).isEmpty();
    }

    /** Cubic r8: a replay of an ENDED link watched for an hour asks for two reads, not one every two minutes. */
    @Test
    void anEndedLinksHeartbeatAsksAtMostEveryThirtyMinutes() throws Exception {
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);     // ended; last read at T0 + 20 min
        assertThat(live.state(A).getEnded()).containsExactly("linkid00001");

        for (long t = 21; t <= 80; t++) {                             // a heartbeat every minute for an hour
            now.set(T0 + t * MIN);
            assertThat(linkStatus("linkid00001")).isEqualTo(204);
        }

        verify(reader, times(5)).read(A);                             // 3 cycle reads + T0+50 + T0+80
    }
}
