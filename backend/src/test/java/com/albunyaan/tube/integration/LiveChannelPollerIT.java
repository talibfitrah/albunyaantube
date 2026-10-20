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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §12 IT3: proven misses remove a link, archive its uncategorised doc, and audit; unproven cycles change nothing. */
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
    static final long T0 = 1_792_200_000_000L, MIN = 60_000L;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{"categories", "channels", "playlists", "videos", "users", "audit_logs",
                "category_content_order", "live_channel_state"};
    }

    private LiveChannelPoller freshPoller() {
        return new LiveChannelPoller(liveCategories, videos, states, live, sortOrder, audit, caches, closedBreaker,
                Clock.systemUTC(), false);
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

    private static LiveTabResult liveRead(String id) {
        return LiveTabResult.ok(List.of(new LiveTabResult.LiveItem(id, "t")), 1);
    }

    @Test
    void anEndedLinkLeavesTheCategoryIsArchivedAndAudited() throws Exception {
        String uc = "UCit3a00000000000000000a";
        seed(uc, "it3link001a", List.of(LIVE_CAT));
        when(reader.read(anyString())).thenReturn(liveRead("it3otherliv"));
        LiveChannelPoller poller = freshPoller();

        poller.cycle(T0);
        poller.cycle(T0 + 10 * MIN);
        poller.cycle(T0 + 20 * MIN);

        Video doc = videos.findById("doc-it3link001a").orElseThrow();
        assertThat(doc.getCategoryIds()).doesNotContain(LIVE_CAT);
        assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ARCHIVED);
        assertThat(orders.findByCategoryIdOrderByPosition(LIVE_CAT)).isEmpty();
        for (int i = 0; i < 50 && countDocuments("audit_logs") < 2; i++) Thread.sleep(100);   // AuditLogService is @Async
        assertThat(countDocuments("audit_logs")).isEqualTo(2);
        assertThat(states.findAll()).anySatisfy(s -> assertThat(s.getArchived()).containsEntry("it3link001a", "ERROR"));
    }

    @Test
    void aLinkHoldingAnotherCategoryLeavesButIsNotArchived() throws Exception {
        String uc = "UCit3b00000000000000000b";
        firestore.collection("categories").document("it3-plain").set(Map.of("name", "Plain")).get(5, TimeUnit.SECONDS);
        seed(uc, "it3link001b", List.of(LIVE_CAT, "it3-plain"));
        when(reader.read(anyString())).thenReturn(liveRead("it3otherliv"));
        LiveChannelPoller poller = freshPoller();

        poller.cycle(T0);
        poller.cycle(T0 + 10 * MIN);
        poller.cycle(T0 + 20 * MIN);

        Video doc = videos.findById("doc-it3link001b").orElseThrow();
        assertThat(doc.getCategoryIds()).containsExactly("it3-plain");
        assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
    }

    /** D14 (spec §12 IT2's eviction line, pre-flight D14): a retitled live keeps the caches; a new served id evicts. */
    @Test
    void onlyAServedIdChangeEvictsThePublicCaches() throws Exception {
        String uc = "UCit3d00000000000000000d";
        seed(uc, "it3link001d", List.of(LIVE_CAT));
        PublicContentCacheService spyCaches = mock(PublicContentCacheService.class);   // counts evictions only
        LiveChannelPoller poller = new LiveChannelPoller(liveCategories, videos, states, live, sortOrder, audit, spyCaches,
                closedBreaker, Clock.systemUTC(), false);
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("it3link001d", "Morning")), 1));
        poller.cycle(T0);
        verify(spyCaches, times(1)).evictPublicContentCaches();

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("it3link001d", "Evening")), 1));
        live.readFresh(uc);                                      // the service's 60 s memo runs on the wall clock here
        poller.cycle(T0 + 10 * MIN);
        verify(spyCaches, times(1)).evictPublicContentCaches();

        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("it3link001d", "Evening"),
                new LiveTabResult.LiveItem("it3live002d", "Second")), 1));
        live.readFresh(uc);
        poller.cycle(T0 + 20 * MIN);
        verify(spyCaches, times(2)).evictPublicContentCaches();
    }

    @Test
    void unprovenCyclesChangeNothing() throws Exception {
        String uc = "UCit3c00000000000000000c";
        seed(uc, "it3link001c", List.of(LIVE_CAT));
        when(reader.read(anyString())).thenReturn(LiveTabResult.ok(List.of(), 1));
        LiveChannelPoller poller = freshPoller();

        for (int i = 0; i < 4; i++) poller.cycle(T0 + i * 10 * MIN);

        verify(reader, atLeastOnce()).read(uc);                  // the cycles did read: nothing changed because of R10
        assertThat(states.findAll()).singleElement().satisfies(s -> assertThat(s.getCheckedAt()).isEqualTo(T0 + 30 * MIN));
        Video doc = videos.findById("doc-it3link001c").orElseThrow();
        assertThat(doc.getCategoryIds()).containsExactly(LIVE_CAT);
        assertThat(doc.getValidationStatus()).isEqualTo(ValidationStatus.ERROR);
    }
}
