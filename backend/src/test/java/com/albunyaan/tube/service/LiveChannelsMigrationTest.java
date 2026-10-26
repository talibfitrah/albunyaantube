package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.scheduler.LiveChannelPoller;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** F2: the migration's state writes (steps 3 and 7) need the loaded snapshot, so unloaded states block the run. */
class LiveChannelsMigrationTest {

    static final String CAT = "btiLw1LiEqiUHQJiaJKX";
    static final String SUNNAH = "UCROKYPep-UuODNwyipe6JMw";

    final CategoryRepository categories = mock(CategoryRepository.class);
    final ChannelRepository channels = mock(ChannelRepository.class);
    final CategoryContentOrderRepository orders = mock(CategoryContentOrderRepository.class);
    final SortOrderService sortOrder = mock(SortOrderService.class);
    final LiveChannelService live = mock(LiveChannelService.class);          // loaded() is false
    final LiveCategoryService liveCategories = mock(LiveCategoryService.class);
    final AuditLogRepository auditLogs = mock(AuditLogRepository.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final PublicContentCacheService caches = mock(PublicContentCacheService.class);
    final LiveChannelsMigration migration = new LiveChannelsMigration(categories, channels, mock(PlaylistRepository.class),
            mock(VideoRepository.class), orders, sortOrder, live, liveCategories, mock(LiveChannelPoller.class), auditLogs,
            audit, caches, Clock.systemUTC());

    @Test
    void unloadedLiveStatesBlockTheMigration() throws Exception {
        Category category = new Category("Channels Live", null);
        category.setId(CAT);
        when(categories.findById(CAT)).thenReturn(Optional.of(category));
        Channel sunnah = new Channel(SUNNAH);
        sunnah.setId("channel_" + SUNNAH);
        sunnah.setStatus("APPROVED");
        when(channels.findByYoutubeId(SUNNAH)).thenReturn(Optional.of(sunnah));
        when(live.readFresh(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("Rs7St51oDDc", "Sunnah")), 1));
        when(liveCategories.members(CAT)).thenReturn(new LiveCategoryService.Members(List.of()));

        for (String mode : List.of("DRY_RUN", "APPLY")) {
            Map<String, Object> out = migration.run(new LiveChannelsMigration.Plan(mode, CAT,
                    List.of(new LiveChannelsMigration.PlanChannel(SUNNAH, 0)), List.of()), "admin-uid", "admin@test.com");

            assertThat(out.get("blockers")).asList()
                    .containsExactly("live channel states not loaded yet; retry after the next poller cycle");
        }
        verify(live, never()).seedWith(any(), any());
        verify(live, never()).put(any());
        verify(categories, never()).save(any());
        verifyNoInteractions(auditLogs, orders, sortOrder, audit, caches);
    }

    /**
     * Stage 9 B16 (Cubic r6 P3): D8, as the category update checks it: the migration never makes a nested category, or
     * one with subcategories, LIVE. Every later edit of such a category would fail LIVE_CATEGORY_SHAPE.
     */
    @Test
    void aNestedCategoryOrOneWithSubcategoriesIsNeverMadeLive() throws Exception {
        Category nested = new Category("Channels Live", "parent-1");
        nested.setId(CAT);
        Category parent = new Category("Lessons", null);
        parent.setId("lessons");
        when(categories.findById(CAT)).thenReturn(Optional.of(nested));
        when(categories.findById("lessons")).thenReturn(Optional.of(parent));
        when(categories.findByParentId("lessons")).thenReturn(List.of(new Category("Fiqh", "lessons")));
        Channel sunnah = new Channel(SUNNAH);
        sunnah.setId("channel_" + SUNNAH);
        sunnah.setStatus("APPROVED");
        when(channels.findByYoutubeId(SUNNAH)).thenReturn(Optional.of(sunnah));
        when(live.readFresh(SUNNAH)).thenReturn(LiveTabResult.ok(List.of(new LiveTabResult.LiveItem("Rs7St51oDDc", "Sunnah")), 1));
        when(live.loaded()).thenReturn(true);
        when(liveCategories.members(any())).thenReturn(new LiveCategoryService.Members(List.of()));

        for (String categoryId : List.of(CAT, "lessons")) {
            for (String mode : List.of("DRY_RUN", "APPLY")) {
                Map<String, Object> out = migration.run(new LiveChannelsMigration.Plan(mode, categoryId,
                        List.of(new LiveChannelsMigration.PlanChannel(SUNNAH, 0)), List.of()), "admin-uid", "admin@test.com");

                assertThat(out.get("blockers")).as(categoryId + " " + mode).asList()
                        .containsExactly("D8: a live category can't have a parent category or subcategories");
            }
        }
        verify(categories, never()).save(any());
        verifyNoInteractions(auditLogs, orders, sortOrder, audit, caches);
    }
}
