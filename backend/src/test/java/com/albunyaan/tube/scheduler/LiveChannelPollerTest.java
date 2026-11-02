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
import static org.mockito.ArgumentMatchers.eq;
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

    /**
     * Rev 9 (owner, 2026-11-01): while YouTube rate-limits, each cycle stops at its first read, so most channels go
     * unread. The rate-limited channel and the unread one both hide an hour after their last real read; nothing is
     * removed or archived, and the next read that lists a live serves it again.
     */
    @Test
    void rateLimitsHideLivesUnprovenForAnHourAndAReadServesThemAgain() throws Exception {
        entries(A, B);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        for (int m = 10; m <= 50; m += 10) cycleAt(T0 + m * MIN);   // one rate-limited read per cycle
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        org.mockito.Mockito.clearInvocations(caches, reader);

        cycleAt(T0 + 60 * MIN);
        verify(reader, times(1)).read(anyString());                   // still one try per cycle, no extra traffic
        assertThat(live.served()).doesNotContainKeys(A, B);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(B).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(B).getMisses().get("bbbbbbbbbbb").getCount()).isZero();
        verify(caches).evictPublicContentCaches();

        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0 + 70 * MIN);                                        // the rate limit ends
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        assertThat(live.state(B).getMisses()).isEmpty();
        verify(sortOrder, never()).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());
        verify(videos, never()).save(any());
    }

    /**
     * Rev 9 / Cubic rev9-r1 #1: only a block hides an unread channel. A restart after 2 h down with more targets than one
     * cycle reads (the 90-request ceiling) leaves the unreached ones served while YouTube answers.
     */
    @Test
    void aHealthyCycleNeverHidesTheTargetsTheCeilingLeftUnread() throws Exception {
        List<LiveChannelState> saved = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String uc = String.format("UC%022d", i);
            entries(uc);
            LiveChannelState s = LiveChannelState.unknown(uc);
            s.setStatus(LiveChannelState.LIVE);
            s.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
            s.setCheckedAt(T0 - 120 * MIN);
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

    /** Rev 9: a breaker refusal mid-cycle is a block too: the refused channel and the ones after it hide after the hour. */
    @Test
    void aBreakerRefusalMidCycleHidesTheUnreadLivesUnprovenForAnHour() {
        entries(A, B);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        when(reader.read(A)).thenReturn(LiveTabResult.breaker(0, "probe already in progress"));

        cycleAt(T0 + 60 * MIN);

        verify(reader, times(1)).read(B);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(B).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
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
        when(reader.read(A)).thenReturn(LiveTabResult.error(1, "no items after 0 continuation pages"));

        for (int m = 10; m <= 60; m += 10) cycleAt(T0 + m * MIN);   // errors at 10, 20, 40 (errorSince T0 + 10)
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa", "linkid00001");
        org.mockito.Mockito.clearInvocations(caches);

        cycleAt(T0 + 70 * MIN);                                        // 60 min into the streak
        assertThat(live.served().getOrDefault(A, List.of())).isEmpty();
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(A).getMisses().values()).allMatch(m -> m.getCount() == 0);
        verify(caches).evictPublicContentCaches();

        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa", "linkid00001"));
        cycleAt(T0 + 150 * MIN);                                       // A's backoff (80 min) is over
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa", "linkid00001");
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.state(A).getMisses()).isEmpty();
        verify(reader, times(6)).read(A);                              // 0, 10, 20, 40, 70, 150
        verify(sortOrder, never()).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());
        verify(videos, never()).save(any());
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
        verify(videos, times(1)).saveLiftingArchive(own);                // B25: the poller's own restore lifts on purpose
        verify(videos, never()).save(any());
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

    /** Stage 9 Cubic r3: an order row can survive the doc dropping the LIVE id; the link still leaves the list. */
    @Test
    void anEndedLinkWhoseOrderRowSurvivesStillLeavesTheLiveList() throws Exception {
        entries(A);
        endingLink();
        when(videos.findByYoutubeId("linkid00001")).thenAnswer(inv -> {
            Video orphan = new Video("linkid00001");
            orphan.setId("doc-link");
            orphan.setStatus("APPROVED");
            orphan.setChannelId(A);
            orphan.setCategoryIds(new ArrayList<>());               // the doc no longer lists CAT
            orphan.setValidationStatus(ValidationStatus.VALID);
            return Optional.of(orphan);
        });
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        cycleAt(T0 + 10 * MIN);
        cycleAt(T0 + 20 * MIN);

        verify(sortOrder).removeContentFromCategoryAndUpdate(CAT, "doc-link", "video");
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

        verify(videos).saveLiftingArchive(own);                          // B25: the poller's restore lifts on purpose
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

    /**
     * L1 (scenario B): an open breaker skips the YouTube reads, never the reload and the served view. Rev 9: lives read
     * under an hour ago are still served.
     */
    @Test
    void anOpenBreakerStillReloadsAndRefreshesTheServedView() throws Exception {
        entries(A);
        LiveChannelState saved = savedWithRestoreRecord();
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
     * Rev 9 (owner, 2026-11-01): with the breaker open no channel is read, so lives unproven for an hour are hidden
     * (time only, no YouTube call; never a removal, archive or miss). Once the breaker closes, the read that lists the
     * live serves it again with no admin action.
     */
    @Test
    void anOpenBreakerHidesLivesUnprovenForAnHourAndAReadServesThemAgain() throws Exception {
        entries(A, B);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        when(reader.read(B)).thenReturn(live("bbbbbbbbbbb"));
        cycleAt(T0);
        when(breaker.isOpen()).thenReturn(true);
        org.mockito.Mockito.clearInvocations(reader);

        cycleAt(T0 + 50 * MIN);
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        org.mockito.Mockito.clearInvocations(caches);

        cycleAt(T0 + 60 * MIN);
        verify(reader, never()).read(anyString());
        assertThat(live.served()).doesNotContainKeys(A, B);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        assertThat(live.state(A).getMisses().get("aaaaaaaaaaa").getCount()).isZero();
        verify(states).save(argThat(s -> A.equals(s.getChannelId()) && s.getLives().isEmpty()));
        verify(caches).evictPublicContentCaches();

        when(breaker.isOpen()).thenReturn(false);                      // the HALF_OPEN probe got through
        cycleAt(T0 + 300 * MIN);
        assertThat(live.served().get(A)).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.served().get(B)).extracting(LiveTabResult.LiveItem::id).containsExactly("bbbbbbbbbbb");
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.state(A).getMisses()).isEmpty();
        verify(sortOrder, never()).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());
        verify(videos, never()).save(any());
    }

    /** Rev 9: under an open breaker an ERROR streak's hold still counts from errorSince (Rev 8), not the last read. */
    @Test
    void anOpenBreakerCountsAnErrorStreaksHoldFromItsStart() {
        entries(A);
        when(reader.read(A)).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(A)).thenReturn(LiveTabResult.error(1, "IOException: x"));
        cycleAt(T0 + 30 * MIN);                                        // errorSince T0 + 30
        when(breaker.isOpen()).thenReturn(true);

        cycleAt(T0 + 80 * MIN);                                        // 80 min since the read, 50 into the streak
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.LIVE);
        cycleAt(T0 + 90 * MIN);
        assertThat(live.state(A).getStatus()).isEqualTo(LiveChannelState.OFFLINE);
    }

    /** The public endpoint's status for channel A: its 404 is the apps' definitive "not live" stop. */
    private int endpointStatus() {
        return endpointStatus(A);
    }

    private int endpointStatus(String uc) {
        try {
            return new LiveChannelController(live).channel(uc).getStatusCode().value();
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

    // ---- Stage 9 B13: R12 archives only what was in a LIVE category, and restores it there ----

    /** P2: a departed live's doc in no LIVE category is not the poller's to archive; its VOD never returns to restore it. */
    @Test
    void anEndedLiveWhoseDocIsInNoLiveCategoryIsNeverArchived() throws Exception {
        entries(A);
        Video uncategorised = new Video("vodnull0001");
        uncategorised.setId("doc-null");
        uncategorised.setStatus("APPROVED");
        uncategorised.setCategoryIds(null);
        Video empty = new Video("vodempty001");
        empty.setId("doc-empty");
        empty.setStatus("APPROVED");
        empty.setCategoryIds(new ArrayList<>());
        for (Video v : List.of(uncategorised, empty)) {
            when(videos.findByYoutubeId(v.getYoutubeId())).thenReturn(Optional.of(v));
            when(videos.findById(v.getId())).thenReturn(Optional.of(v));
        }
        when(reader.read(anyString())).thenReturn(live("vodnull0001", "vodempty001"));
        cycleAt(T0);                                                  // both live on A
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        for (int i = 1; i <= 3; i++) cycleAt(T0 + i * 10 * MIN);      // both depart, then end (2 proven misses, >= 15 min)

        assertThat(uncategorised.getValidationStatus()).isNull();
        assertThat(empty.getValidationStatus()).isNull();
        verify(videos, never()).save(any());
        assertThat(live.state(A).getArchived()).isEmpty();
        verify(audit, never()).logSystem(eq("live_link_removed_ended"), anyString(), anyString(), anyString());
    }

    /** The removal runs in every LIVE category (an order row can outlive the doc's id); the audit names only the doc's. */
    @Test
    void anEndedLinkIsAuditedOnlyForTheLiveCategoryItWasIn() throws Exception {
        String other = "otherLiveCategory0000";
        when(liveCategories.liveCategoryIds()).thenReturn(new LinkedHashSet<>(List.of(CAT, other)));
        when(liveCategories.members(other)).thenReturn(new LiveCategoryService.Members(List.of()));
        entries(A);
        endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));

        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);

        verify(sortOrder).removeContentFromCategoryAndUpdate(other, "doc-link", "video");
        verify(audit, times(1)).logSystem("live_link_removed_ended", "video", "doc-link", "live-poller");
    }

    /** P3: a link the poller archived goes back into the LIVE category it left once it is live again (through the guard). */
    @Test
    void aRestoredLinkGoesBackIntoTheLiveCategoryItLeft() throws Exception {
        entries(A);
        Video left = endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);     // ended: out of CAT, archived
        assertThat(left.getValidationStatus()).isEqualTo(ValidationStatus.ARCHIVED);

        when(videos.findByYoutubeId("linkid00001")).thenReturn(Optional.of(left));
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", "linkid00001"));
        cycleAt(T0 + 30 * MIN);                                       // live again: restored

        assertThat(left.getValidationStatus()).isEqualTo(ValidationStatus.VALID);
        verify(sortOrder).addMultipleContentToCategory(eq(CAT),
                argThat(items -> items.size() == 1 && "doc-link".equals(items.get(0)[0]) && "video".equals(items.get(0)[1])));
    }

    /**
     * Stage 9 B14 (re-review P3-3, Cubic r5 #7): a failure after a restore saved its doc loses that link's rejoin for good
     * (the next cycle finds the doc no longer ARCHIVED). Both ids are logged, so an admin puts it back by hand.
     */
    @Test
    void aRestoredLinkWhoseRejoinIsLostIsNamedForTheAdmin() throws Exception {
        entries(A);
        Video left = endingLink();
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        for (int i = 0; i <= 2; i++) cycleAt(T0 + i * 10 * MIN);     // ended: out of CAT, archived
        when(videos.findByYoutubeId("linkid00001")).thenReturn(Optional.of(left));
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa", "linkid00001"));
        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(states).save(any());

        cycleAt(T0 + 30 * MIN);                                       // restored, then the state write fails

        assertThat(left.getValidationStatus()).isEqualTo(ValidationStatus.VALID);
        verify(sortOrder, never()).addMultipleContentToCategory(anyString(), any());
        assertThat(logged(Level.WARN)).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("doc-link", CAT, "add it by hand"));
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
}
