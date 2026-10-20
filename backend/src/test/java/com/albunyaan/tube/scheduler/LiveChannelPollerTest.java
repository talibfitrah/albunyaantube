package com.albunyaan.tube.scheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LiveTabReader;
import com.albunyaan.tube.service.LiveTabResult;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.SortOrderService;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §12 U2 (cycle half): proven cycles, the 1 h unproven hide, budget, breaker, D14 eviction, boot load. */
class LiveChannelPollerTest {

    static final long T0 = 1_792_200_000_000L;
    static final long MIN = 60_000L;
    static final String CAT = "btiLw1LiEqiUHQJiaJKX";
    static final String A = "UCa0000000000000000000aa";
    static final String B = "UCb0000000000000000000bb";

    final VideoRepository videos = mock(VideoRepository.class);
    final LiveChannelStateRepository states = mock(LiveChannelStateRepository.class);
    final LiveTabReader reader = mock(LiveTabReader.class);
    final LiveCategoryService liveCategories = mock(LiveCategoryService.class);
    final SortOrderService sortOrder = mock(SortOrderService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final PublicContentCacheService caches = mock(PublicContentCacheService.class);
    final YouTubeCircuitBreaker breaker = mock(YouTubeCircuitBreaker.class);
    final AtomicLong now = new AtomicLong(T0);
    LiveChannelService live;
    LiveChannelPoller poller;
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
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(now.get()); }
        };
        live = new LiveChannelService(reader, states, clock);
        poller = new LiveChannelPoller(liveCategories, videos, states, live, sortOrder, audit, caches, breaker, clock, true);
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

    /** A link of channel A in the LIVE category, and the same doc after it left the category (as findById sees it). */
    private Video endingLink() throws Exception {
        Video link = new Video("linkid00001");
        link.setId("doc-link");
        link.setStatus("APPROVED");
        link.setChannelId(A);
        link.setCategoryIds(new ArrayList<>(List.of(CAT)));
        link.setValidationStatus(ValidationStatus.VALID);
        members.add(link);
        Video left = new Video("linkid00001");
        left.setId("doc-link");
        left.setStatus("APPROVED");
        left.setCategoryIds(new ArrayList<>());
        left.setValidationStatus(ValidationStatus.VALID);
        when(videos.findByYoutubeId("linkid00001")).thenReturn(Optional.of(link));
        when(videos.findById("doc-link")).thenReturn(Optional.of(left));
        return left;
    }

    @Test
    void noLiveCategoryMeansNoYouTubeTraffic() throws Exception {
        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>());

        cycleAt(T0);

        verify(reader, never()).read(anyString());
    }

    @Test
    void anOpenBreakerSkipsTheCycle() {
        entries(A);
        when(breaker.isOpen()).thenReturn(true);

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
        for (int i = 1; i <= 8; i++) {
            cycleAt(T0 + i * 10 * MIN);
        }

        verify(sortOrder, never()).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());
        verify(videos, never()).save(any());
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);   // hidden after 1 h
        assertThat(live.suspectSince()).isEqualTo(T0 + 10 * MIN);
    }

    @Test
    void unprovenHidesWaitAnHour() {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));

        cycleAt(T0 + 10 * MIN);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(logged(Level.ERROR)).hasSize(1);                  // the suspect line: an empty read of a LIVE channel
        cycleAt(T0 + 70 * MIN);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    /** D16: the 90-request ceiling stops a cycle; the next one starts with the channels left unread. */
    @Test
    void theRequestCeilingStopsTheCycleAndTheNextResumesOldestFirst() {
        for (int i = 0; i < 20; i++) entries(String.format("UC%022d", i));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "t")), 6));

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

    /** R7: the first rate limit stops the cycle with one WARN; only that channel's checkedAt moves (no error, no hide). */
    @Test
    void aRateLimitStopsTheCycleWithOneWarning() {
        entries(A, B);
        when(reader.read(A)).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        cycleAt(T0);

        verify(reader, never()).read(B);
        LiveChannelState a = live.state(A);
        assertThat(a.getCheckedAt()).isEqualTo(T0);
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

        cycleAt(T0 + 10 * MIN);

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
        cycleAt(T0 + 10 * MIN);                                       // A first, rate-limited: the cycle stops before B
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0 + 20 * MIN);                                       // A was checked later than B, so B leads

        InOrder order = inOrder(reader);
        order.verify(reader).read(A);
        order.verify(reader).read(B);
        order.verify(reader).read(A);
        order.verify(reader).read(B);
        order.verify(reader).read(A);
        verify(reader, times(2)).read(B);
    }

    /** D3: errors back off 10, 20, 40, 80 min; a cycle inside the backoff skips the channel. */
    @Test
    void targetsInBackoffAreSkipped() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.error(1, "x"));
        cycleAt(T0);                                                  // error 1: nextPollAt = T0 + 10 min
        cycleAt(T0 + 10 * MIN);                                       // due: error 2, nextPollAt = T0 + 30 min
        cycleAt(T0 + 20 * MIN);                                       // inside the backoff: skipped

        verify(reader, times(2)).read(anyString());
    }

    /** Spec §12 U2: a single-channel category; any LIVE read resets the unproven clock, so the hour restarts. */
    @Test
    void aLiveReadResetsTheUnprovenClock() throws Exception {
        entries(A);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        cycleAt(T0 + 10 * MIN);                                       // unproven since T0 + 10
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0 + 20 * MIN);                                       // proven again: the clock resets
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        cycleAt(T0 + 30 * MIN);                                       // unproven since T0 + 30

        cycleAt(T0 + 80 * MIN);                                       // 50 min unproven: still shown
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.suspectSince()).isEqualTo(T0 + 30 * MIN);
        cycleAt(T0 + 90 * MIN);                                       // 60 min: hidden
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        verify(sortOrder, never()).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());
    }

    /** R12 (poller level): a returning live restores only the poller's own archive; an admin's archive is left alone. */
    @Test
    void aReturningLiveRestoresOnlyThePollersOwnArchive() throws Exception {
        entries(A);
        LiveChannelState s = LiveChannelState.unknown(A);
        s.getArchived().put("ownarchive1", "ERROR");
        live.loadStates(List.of(s));
        Video own = new Video("ownarchive1");
        own.setId("doc-own");
        own.setValidationStatus(ValidationStatus.ARCHIVED);
        Video admins = new Video("adminarch01");
        admins.setId("doc-admin");
        admins.setValidationStatus(ValidationStatus.ARCHIVED);
        when(videos.findByYoutubeId("ownarchive1")).thenReturn(Optional.of(own));
        when(videos.findByYoutubeId("adminarch01")).thenReturn(Optional.of(admins));
        when(reader.read(anyString())).thenReturn(live("ownarchive1", "adminarch01"));

        cycleAt(T0);

        assertThat(own.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
        assertThat(admins.getValidationStatus()).isEqualTo(ValidationStatus.ARCHIVED);
        verify(videos, times(1)).save(any());
        verify(audit).logSystem("live_stream_restored", "video", "doc-own", "live-poller");
    }

    /** R12: an add-time seed that sees an archived id live keeps its restore record, so the next cycle restores the doc. */
    @Test
    void aSeedKeepsTheRestoreRecordForTheNextCycle() throws Exception {
        entries(A);
        LiveChannelState s = LiveChannelState.unknown(A);
        s.getArchived().put("ownarchive1", "ERROR");
        live.loadStates(List.of(s));
        Video own = new Video("ownarchive1");
        own.setId("doc-own");
        own.setValidationStatus(ValidationStatus.ARCHIVED);
        when(videos.findByYoutubeId("ownarchive1")).thenReturn(Optional.of(own));
        when(reader.read(anyString())).thenReturn(live("ownarchive1"));

        live.seed(A);
        cycleAt(T0);

        assertThat(own.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
        verify(audit).logSystem("live_stream_restored", "video", "doc-own", "live-poller");
    }

    /**
     * R12: nothing outside the state doc changes for an ended id until the state, with its restore record, is written.
     * A failed state write leaves the membership and the doc untouched; the id stays tracked and the next cycle ends it.
     */
    @Test
    void theStateIsWrittenBeforeTheLinkLeavesOrIsArchived() throws Exception {
        entries(A);
        Video left = endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // proven miss 1
        cycleAt(T0 + 10 * MIN);                                       // proven miss 2, only 10 min after the first

        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(states).save(any());
        cycleAt(T0 + 20 * MIN);                                       // ends, but the state write fails
        verify(sortOrder, never()).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());
        verify(videos, never()).save(any());
        assertThat(left.getValidationStatus()).isEqualTo(ValidationStatus.VALID);

        doNothing().when(states).save(any());
        cycleAt(T0 + 30 * MIN);                                       // still tracked: ends again, state first
        InOrder order = inOrder(states, sortOrder, videos);
        order.verify(states).save(argThat(s -> "VALID".equals(s.getArchived().get("linkid00001"))));
        order.verify(sortOrder).removeContentFromCategoryAndUpdate(CAT, "doc-link", "video");
        order.verify(videos).save(left);
        assertThat(left.getValidationStatus()).isEqualTo(ValidationStatus.ARCHIVED);
        assertThat(live.state(A).getArchived()).containsEntry("linkid00001", "VALID");
        verify(audit).logSystem("live_link_removed_ended", "video", "doc-link", "live-poller");
        verify(audit).logSystem("live_stream_archived_ended", "video", "doc-link", "live-poller");
    }

    /** Concern 2: an archive that fails after its restore record was written is logged with both ids for an admin. */
    @Test
    void aFailedArchiveNamesTheDocForTheAdmin() throws Exception {
        entries(A);
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(videos).save(any());

        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);

        assertThat(logged(Level.WARN)).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage()).contains("doc-link", "linkid00001"));
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

    /** A restore that landed changes what the public sees, so it evicts even when the channel's state write then fails. */
    @Test
    void aRestoreThatLandedEvictsEvenWhenTheStateWriteFails() throws Exception {
        entries(A);
        LiveChannelState s = LiveChannelState.unknown(A);
        s.getArchived().put("ownarchive1", "ERROR");
        live.loadStates(List.of(s));
        Video own = new Video("ownarchive1");
        own.setId("doc-own");
        own.setValidationStatus(ValidationStatus.ARCHIVED);
        when(videos.findByYoutubeId("ownarchive1")).thenReturn(Optional.of(own));
        when(reader.read(anyString())).thenReturn(live("ownarchive1"));
        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(states).save(any());

        cycleAt(T0);

        verify(videos).save(own);
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

    /** D14: a retitled live keeps the caches; a new id evicts them. */
    @Test
    void onlyAServedIdChangeEvicts() {
        entries(A);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "Morning")), 1));
        cycleAt(T0);
        verify(caches, times(1)).evictPublicContentCaches();

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("aaaaaaaaaaa", "Evening")), 1));
        cycleAt(T0 + 10 * MIN);
        verify(caches, times(1)).evictPublicContentCaches();

        when(reader.read(anyString())).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0 + 20 * MIN);
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

    /** A Sunnah-like state: LIVE, plus the R12 restore record of a doc the poller archived. */
    private LiveChannelState savedWithRestoreRecord() {
        LiveChannelState saved = LiveChannelState.unknown(A);
        saved.setStatus(LiveChannelState.LIVE);
        saved.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        saved.getArchived().put("ownarchive1", "ERROR");
        return saved;
    }

    /** L1 (scenario A): a failed boot load is retried by the next cycle; no seed or cycle writes a state before it. */
    @Test
    void aFailedBootLoadIsRetriedBeforeAnyStateWrite() throws Exception {
        entries(A);
        when(states.findAll()).thenThrow(new java.util.concurrent.TimeoutException("firestore"))
                .thenReturn(List.of(savedWithRestoreRecord()));
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        poller.afterSingletonsInstantiated();

        live.seed(A);                                                 // an admin add before the reload
        verify(states, never()).save(any());

        cycleAt(T0);

        verify(states).save(argThat(s -> "ERROR".equals(s.getArchived().get("ownarchive1"))));
        verify(states, never()).save(argThat(s -> s.getArchived().isEmpty()));
        assertThat(live.state(A).getArchived()).containsEntry("ownarchive1", "ERROR");
    }

    /** L1 (scenario B): an open breaker skips the YouTube reads, never the reload and the served view. */
    @Test
    void anOpenBreakerStillReloadsAndRefreshesTheServedView() throws Exception {
        entries(A);
        when(states.findAll()).thenThrow(new java.util.concurrent.TimeoutException("firestore"))
                .thenReturn(List.of(savedWithRestoreRecord()));
        when(breaker.isOpen()).thenReturn(true);
        poller.afterSingletonsInstantiated();

        cycleAt(T0);

        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.entries()).containsKey(A);
        verify(reader, never()).read(anyString());
        verify(states, never()).save(any());
        verify(caches).evictPublicContentCaches();                     // pages cached while nothing was loaded
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
        assertThat(Arrays.stream(LiveChannelPoller.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(EventListener.class)).map(java.lang.reflect.Method::getName).toList())
                .isEmpty();
    }
}
