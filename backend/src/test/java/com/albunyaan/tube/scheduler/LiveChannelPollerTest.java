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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
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

    final VideoRepository videos = mock(VideoRepository.class);
    final LiveChannelStateRepository states = mock(LiveChannelStateRepository.class);
    final LiveTabReader reader = mock(LiveTabReader.class);
    final LiveCategoryService liveCategories = mock(LiveCategoryService.class);
    final SortOrderService sortOrder = mock(SortOrderService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final PublicContentCacheService caches = mock(PublicContentCacheService.class);
    final YouTubeCircuitBreaker breaker = mock(YouTubeCircuitBreaker.class);
    final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(T0);
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
        return LiveTabResult.ok(java.util.Arrays.stream(ids).map(i -> new LiveTabResult.LiveItem(i, "t")).toList(), 1);
    }

    @BeforeEach
    void setUp() throws Exception {
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return java.time.Instant.ofEpochMilli(now.get()); }
        };
        live = new LiveChannelService(reader, states, clock);
        poller = new LiveChannelPoller(liveCategories, videos, states, live, sortOrder, audit, caches, breaker, clock, true);
        when(liveCategories.liveCategoryIds()).thenReturn(new java.util.LinkedHashSet<>(List.of(CAT)));
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

    @Test
    void noLiveCategoryMeansNoYouTubeTraffic() throws Exception {
        when(liveCategories.liveCategoryIds()).thenReturn(new java.util.LinkedHashSet<>());

        cycleAt(T0);

        verify(reader, never()).read(anyString());
    }

    @Test
    void anOpenBreakerSkipsTheCycle() {
        entries("UCa0000000000000000000aa");
        when(breaker.isOpen()).thenReturn(true);

        cycleAt(T0);

        verify(reader, never()).read(anyString());
    }

    /** C1 / R10: a parse break that reads every channel as "nothing LIVE" never removes or archives anything. */
    @Test
    void aPersistentParseBreakNeverRemovesOrArchives() throws Exception {
        entries("UCa0000000000000000000aa", "UCb0000000000000000000bb");
        Video link = new Video("linkid00001");
        link.setId("doc-link");
        link.setStatus("APPROVED");
        link.setChannelId("UCa0000000000000000000aa");
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
        assertThat(live.state("UCa0000000000000000000aa").getStatus()).isEqualTo(LiveChannelState.OFFLINE);   // hidden after 1 h
        assertThat(live.suspectSince()).isEqualTo(T0 + 10 * MIN);
    }

    @Test
    void unprovenHidesWaitAnHour() {
        entries("UCa0000000000000000000aa");
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));

        cycleAt(T0 + 10 * MIN);
        assertThat(live.state("UCa0000000000000000000aa").getStatus()).isEqualTo(LiveChannelState.LIVE);
        cycleAt(T0 + 70 * MIN);
        assertThat(live.state("UCa0000000000000000000aa").getStatus()).isEqualTo(LiveChannelState.OFFLINE);
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
        entries("UCa0000000000000000000aa", "UCb0000000000000000000bb");
        when(reader.read("UCa0000000000000000000aa")).thenReturn(LiveTabResult.breaker(0, "open"));

        cycleAt(T0);

        verify(reader, never()).read("UCb0000000000000000000bb");
        assertThat(live.state("UCa0000000000000000000aa")).isNull();
    }

    /** R7: the first rate limit stops the cycle with one WARN; only that channel's checkedAt moves (no error, no hide). */
    @Test
    void aRateLimitStopsTheCycleWithOneWarning() {
        entries("UCa0000000000000000000aa", "UCb0000000000000000000bb");
        when(reader.read("UCa0000000000000000000aa")).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));

        cycleAt(T0);

        verify(reader, never()).read("UCb0000000000000000000bb");
        LiveChannelState a = live.state("UCa0000000000000000000aa");
        assertThat(a.getCheckedAt()).isEqualTo(T0);
        assertThat(a.getStatus()).isEqualTo(LiveChannelState.UNKNOWN);
        assertThat(a.getErrorCount()).isZero();
        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.WARN).singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains("UCa0000000000000000000aa", Instant.ofEpochMilli(T0).toString()));
    }

    /** R7 / D16: a rate-limited channel goes to the back of the next cycle, so it can never starve the others. */
    @Test
    void aRateLimitedChannelIsReadLastInTheNextCycle() {
        String a = "UCa0000000000000000000aa";
        String b = "UCb0000000000000000000bb";
        entries(a, b);
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // both read at T0; ties keep the entry order
        when(reader.read(a)).thenReturn(LiveTabResult.rateLimited(1, "ReCaptchaException: 429"));
        cycleAt(T0 + 10 * MIN);                                       // a first, rate-limited: the cycle stops before b
        when(reader.read(a)).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0 + 20 * MIN);                                       // a was checked later than b, so b leads

        InOrder order = inOrder(reader);
        order.verify(reader).read(a);
        order.verify(reader).read(b);
        order.verify(reader).read(a);
        order.verify(reader).read(b);
        order.verify(reader).read(a);
        verify(reader, times(2)).read(b);
    }

    /** D3: errors back off 10, 20, 40, 80 min; a cycle inside the backoff skips the channel. */
    @Test
    void targetsInBackoffAreSkipped() {
        entries("UCa0000000000000000000aa");
        when(reader.read(anyString())).thenReturn(LiveTabResult.error(1, "x"));
        cycleAt(T0);                                                  // error 1: nextPollAt = T0 + 10 min
        cycleAt(T0 + 10 * MIN);                                       // due: error 2, nextPollAt = T0 + 30 min
        cycleAt(T0 + 20 * MIN);                                       // inside the backoff: skipped

        verify(reader, times(2)).read(anyString());
    }

    /** Spec §12 U2: a single-channel category; any LIVE read resets the unproven clock, so the hour restarts. */
    @Test
    void aLiveReadResetsTheUnprovenClock() throws Exception {
        entries("UCa0000000000000000000aa");
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        cycleAt(T0 + 10 * MIN);                                       // unproven since T0 + 10
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0 + 20 * MIN);                                       // proven again: the clock resets
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        cycleAt(T0 + 30 * MIN);                                       // unproven since T0 + 30

        cycleAt(T0 + 80 * MIN);                                       // 50 min unproven: still shown
        assertThat(live.state("UCa0000000000000000000aa").getStatus()).isEqualTo(LiveChannelState.LIVE);
        assertThat(live.suspectSince()).isEqualTo(T0 + 30 * MIN);
        cycleAt(T0 + 90 * MIN);                                       // 60 min: hidden
        assertThat(live.state("UCa0000000000000000000aa").getStatus()).isEqualTo(LiveChannelState.OFFLINE);
        verify(sortOrder, never()).removeContentFromCategoryAndUpdate(anyString(), anyString(), anyString());
    }

    /** R12 (poller level): a returning live restores only the poller's own archive; an admin's archive is left alone. */
    @Test
    void aReturningLiveRestoresOnlyThePollersOwnArchive() throws Exception {
        entries("UCa0000000000000000000aa");
        LiveChannelState s = LiveChannelState.unknown("UCa0000000000000000000aa");
        s.getArchived().put("ownarchive1", "ERROR");
        live.loadStates(List.of(s));
        Video own = new Video("ownarchive1");
        own.setId("doc-own");
        own.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        Video admins = new Video("adminarch01");
        admins.setId("doc-admin");
        admins.setValidationStatus(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        when(videos.findByYoutubeId("ownarchive1")).thenReturn(java.util.Optional.of(own));
        when(videos.findByYoutubeId("adminarch01")).thenReturn(java.util.Optional.of(admins));
        when(reader.read(anyString())).thenReturn(live("ownarchive1", "adminarch01"));

        cycleAt(T0);

        assertThat(own.getValidationStatus()).isEqualTo(com.albunyaan.tube.model.ValidationStatus.ERROR);
        assertThat(admins.getValidationStatus()).isEqualTo(com.albunyaan.tube.model.ValidationStatus.ARCHIVED);
        verify(videos, times(1)).save(any());
        verify(audit).logSystem("live_stream_restored", "video", "doc-own", "live-poller");
    }

    /**
     * R12: the restore record is durable before a doc is archived. A failed state write archives nothing (the id stays
     * tracked), and the next cycle archives it with the record written first.
     */
    @Test
    void theStateIsWrittenBeforeTheDocIsArchived() throws Exception {
        entries("UCa0000000000000000000aa");
        Video link = new Video("linkid00001");
        link.setId("doc-link");
        link.setStatus("APPROVED");
        link.setChannelId("UCa0000000000000000000aa");
        link.setCategoryIds(new ArrayList<>(List.of(CAT)));
        members.add(link);
        Video left = new Video("linkid00001");                         // the doc after it left the LIVE category
        left.setId("doc-link");
        left.setStatus("APPROVED");
        left.setCategoryIds(new ArrayList<>());
        left.setValidationStatus(ValidationStatus.VALID);
        when(videos.findByYoutubeId("linkid00001")).thenReturn(Optional.of(link));
        when(videos.findById("doc-link")).thenReturn(Optional.of(left));
        when(reader.read(anyString())).thenReturn(live("aaaaaaaaaaa"));
        cycleAt(T0);                                                  // proven miss 1
        cycleAt(T0 + 10 * MIN);                                       // proven miss 2, only 10 min after the first

        doThrow(new ExecutionException(new RuntimeException("firestore down"))).when(states).save(any());
        cycleAt(T0 + 20 * MIN);                                       // ends, but the state write fails
        verify(videos, never()).save(any());
        assertThat(left.getValidationStatus()).isEqualTo(ValidationStatus.VALID);

        doNothing().when(states).save(any());
        cycleAt(T0 + 30 * MIN);                                       // still tracked: ends again and archives
        InOrder order = inOrder(states, videos);
        order.verify(states).save(argThat(s -> "VALID".equals(s.getArchived().get("linkid00001"))));
        order.verify(videos).save(left);
        assertThat(left.getValidationStatus()).isEqualTo(ValidationStatus.ARCHIVED);
        assertThat(live.state("UCa0000000000000000000aa").getArchived()).containsEntry("linkid00001", "VALID");
        verify(audit).logSystem("live_stream_archived_ended", "video", "doc-link", "live-poller");
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
        entries("UCa0000000000000000000aa");
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
        entries("UCa0000000000000000000aa");
        LiveChannelState saved = LiveChannelState.unknown("UCa0000000000000000000aa");
        saved.setStatus(LiveChannelState.LIVE);
        saved.getLives().add(new LiveChannelState.Live("aaaaaaaaaaa", "t"));
        when(states.findAll()).thenReturn(List.of(saved));

        poller.loadAtBoot();

        assertThat(live.served().get("UCa0000000000000000000aa")).extracting(LiveTabResult.LiveItem::id).containsExactly("aaaaaaaaaaa");
        assertThat(live.entries()).containsKey("UCa0000000000000000000aa");
        verify(reader, never()).read(anyString());
    }
}
