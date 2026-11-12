package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.scheduler.LiveChannelPoller;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LiveTabReader;
import com.albunyaan.tube.service.LiveTabResult;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.SortOrderService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Spec §12 IT3 (owner ruling 2026-11-02): proven misses hide a link and audit it, never touching its doc or category. */
class LiveChannelPollerIT extends BaseIntegrationTest {

    @MockBean LiveTabReader reader;
    @Autowired LiveCategoryService liveCategories;
    @Autowired VideoRepository videos;
    @Autowired LiveChannelStateRepository states;
    @Autowired LiveChannelService live;
    @Autowired SortOrderService sortOrder;
    @Autowired AuditLogService audit;
    @Autowired PublicContentCacheService caches;
    @Autowired CategoryContentOrderRepository orders;
    final YouTubeCircuitBreaker closedBreaker = mock(YouTubeCircuitBreaker.class); // isOpen() is false

    static final String LIVE_CAT = "it3-live";
    static final long T0 = 1_792_200_000_000L, MIN = 60_000L, DAY = 1440 * MIN;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{"categories", "channels", "playlists", "videos", "users", "audit_logs",
                "category_content_order", "live_channel_state", "contentReports", "adminNotifications"};
    }

    private LiveChannelPoller freshPoller() {
        return new LiveChannelPoller(liveCategories, videos, states, live, audit, caches, closedBreaker, Clock.systemUTC(),
                false, 1_000_000);
    }

    private void seed(String uc, String linkId, List<String> categoryIds) throws Exception {
        firestore.collection("categories").document(LIVE_CAT)
                .set(Map.of("name", "Live", "type", "LIVE", "topLevel", true)).get(5, TimeUnit.SECONDS);
        firestore.collection("channels").document("channel_" + uc)
                .set(Map.of("youtubeId", uc, "name", uc, "status", "APPROVED", "categoryIds", List.of(LIVE_CAT)))
                .get(5, TimeUnit.SECONDS);
        firestore.collection("videos").document("doc-" + linkId)
                .set(Map.of("youtubeId", linkId, "title", "link", "status", "APPROVED", "channelId", uc,
                        "categoryIds", categoryIds, "validationStatus", "ERROR"))
                .get(5, TimeUnit.SECONDS);
        sortOrder.addContentToCategory(LIVE_CAT, "doc-" + linkId, "video");
    }

    /** The next cycle reads again: the service's memo runs on the wall clock, not the cycle's. */
    private void forgetMemo() {
        Object target = AopTestUtils.getUltimateTargetObject(live);
        ((Map<?, ?>) ReflectionTestUtils.getField(target, "memo")).clear();
    }

    private static LiveTabResult liveRead(String id) {
        return LiveTabResult.ok(List.of(new LiveTabResult.LiveItem(id, "t")), 1);
    }

    @Test
    void anEndedLinkIsHiddenStaysInItsCategoryAndIsAudited() throws Exception {
        String uc = "UCit3a00000000000000000a";
        seed(uc, "it3link001a", List.of(LIVE_CAT));
        when(reader.read(anyString())).thenReturn(liveRead("it3otherliv"));
        AuditLogService countedAudit = mock(AuditLogService.class, AdditionalAnswers.delegatesTo(audit));   // real writes
        LiveChannelPoller poller = new LiveChannelPoller(liveCategories, videos, states, live, countedAudit, caches,
                closedBreaker, Clock.systemUTC(), false, 1_000_000);

        poller.cycle(T0);
        poller.cycle(T0 + 10 * MIN);
        poller.cycle(T0 + 20 * MIN);
        verify(countedAudit).logSystem("live_link_hidden_ended", "video", "doc-it3link001a", "live-poller");
        verifyNoMoreInteractions(countedAudit);                  // exactly one audit asked for: no late second row

        Video doc = videos.findById("doc-it3link001a").orElseThrow();
        assertThat(doc.getCategoryIds()).containsExactly(LIVE_CAT);
        assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
        assertThat(orders.findByCategoryIdOrderByPosition(LIVE_CAT)).extracting(o -> o.getContentId()).contains("doc-it3link001a");
        assertThat(states.findAll()).anySatisfy(s -> assertThat(s.getEnded()).containsExactly("it3link001a"));
        assertThat(liveCategories.items(LIVE_CAT)).extracting(i -> i.getId()).doesNotContain("it3link001a");
        live.loadStates(states.findAll());                       // Cubic r2 #2: R10's proof survives a restart
        assertThat(live.state(uc).getLiveSeenAt()).isEqualTo(T0 + 20 * MIN);
        for (int i = 0; i < 100 && countDocuments("audit_logs") < 1; i++) Thread.sleep(100);  // AuditLogService is @Async
        assertThat(countDocuments("audit_logs")).isEqualTo(1);
    }

    /** D14 (spec §12 IT2's eviction line, pre-flight D14): a retitled live keeps the caches; a new served id evicts. */
    @Test
    void onlyAServedIdChangeEvictsThePublicCaches() throws Exception {
        String uc = "UCit3d00000000000000000d";
        seed(uc, "it3link001d", List.of(LIVE_CAT));
        PublicContentCacheService spyCaches = mock(PublicContentCacheService.class);   // counts evictions only
        LiveChannelPoller poller = new LiveChannelPoller(liveCategories, videos, states, live, audit, spyCaches,
                closedBreaker, Clock.systemUTC(), false, 1_000_000);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("it3link001d", "Morning")), 1));
        poller.cycle(T0);
        verify(spyCaches, times(1)).evictForLiveChange();

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("it3link001d", "Evening")), 1));
        forgetMemo();                                            // the service's 60 s memo runs on the wall clock here
        poller.cycle(T0 + DAY);                                  // the next sweep
        verify(spyCaches, times(1)).evictForLiveChange();

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("it3link001d", "Evening"),
                new LiveTabResult.LiveItem("it3live002d", "Second")), 1));
        forgetMemo();
        poller.cycle(T0 + 2 * DAY);
        verify(spyCaches, times(2)).evictForLiveChange();
        verify(spyCaches, never()).evictPublicContentCaches();   // a live change never announces a content change
    }

    /** Owner ruling 2026-11-02: an ended link a later read lists live is served again, with no admin action. */
    @Test
    void anEndedLinkLiveAgainIsServedAgain() throws Exception {
        String uc = "UCit3e00000000000000000e";
        seed(uc, "it3link001e", List.of(LIVE_CAT));
        when(reader.read(anyString())).thenReturn(liveRead("it3otherliv"));
        LiveChannelPoller poller = freshPoller();
        for (int i = 0; i <= 2; i++) poller.cycle(T0 + i * 10 * MIN);
        assertThat(liveCategories.items(LIVE_CAT)).extracting(i -> i.getId()).doesNotContain("it3link001e");

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("it3otherliv", "t"),
                new LiveTabResult.LiveItem("it3link001e", "t")), 1));
        forgetMemo();
        poller.cycle(T0 + 20 * MIN + DAY);                       // the next sweep

        assertThat(liveCategories.items(LIVE_CAT)).extracting(i -> i.getId()).contains("it3link001e");
        assertThat(states.findAll()).allSatisfy(s -> assertThat(s.getEnded()).isEmpty());
    }

    /** State docs the old model wrote carry archived/archivedFrom (prod: empty maps): they load, with no warning each time. */
    @Test
    void anOldStateDocWithArchiveFieldsLoads() throws Exception {
        firestore.collection("live_channel_state").document("UCit3h00000000000000000h")
                .set(Map.of("status", "OFFLINE", "archived", Map.of("it3link001h", "VALID"),
                        "archivedFrom", Map.of("it3link001h", List.of(LIVE_CAT))))
                .get(5, TimeUnit.SECONDS);
        var mapper = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("com.google.cloud.firestore.encoding");
        var warnings = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        warnings.start();
        mapper.addAppender(warnings);
        try {
            assertThat(states.findAll()).singleElement().satisfies(s -> {
                assertThat(s.getChannelId()).isEqualTo("UCit3h00000000000000000h");
                assertThat(s.getStatus()).isEqualTo("OFFLINE");
                assertThat(s.getEnded()).isEmpty();
            });
        } finally {
            mapper.detachAppender(warnings);
        }
        assertThat(warnings.list).extracting(e -> e.getFormattedMessage()).isEmpty();
    }

    @Test
    void unprovenCyclesChangeNothing() throws Exception {
        String uc = "UCit3c00000000000000000c";
        seed(uc, "it3link001c", List.of(LIVE_CAT));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        live.loadStates(states.findAll());                       // no proof (liveSeenAt) left by another test's states
        LiveChannelPoller poller = freshPoller();

        for (int i = 0; i < 4; i++) poller.cycle(T0 + i * 10 * MIN);

        verify(reader, atLeastOnce()).read(uc);                  // the cycles did read: nothing changed because of R10
        // the first read, listing no live, put the channel on its 4 h recheck
        assertThat(states.findAll()).singleElement().satisfies(s -> assertThat(s.getCheckedAt()).isEqualTo(T0));
        Video doc = videos.findById("doc-it3link001c").orElseThrow();
        assertThat(doc.getCategoryIds()).containsExactly(LIVE_CAT);
        assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
    }
}
