package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.HomeCategoryDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.SearchIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spec §4.1: a LIVE category's HOME and VIDEOS lists and its /home section come from the expansion. */
class PublicContentServiceLiveTest {

    static final String CAT = "btiLw1LiEqiUHQJiaJKX";
    final CategoryRepository categories = mock(CategoryRepository.class);
    final ChannelRepository channels = mock(ChannelRepository.class);
    final LiveCategoryService liveCategories = mock(LiveCategoryService.class);
    PublicContentService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new PublicContentService(channels, mock(PlaylistRepository.class), mock(VideoRepository.class), categories,
                mock(CategoryContentOrderRepository.class), Runnable::run, mock(SearchIndex.class), liveCategories);
        when(categories.findByParentId(CAT)).thenReturn(List.of());
        when(liveCategories.isLive(CAT)).thenReturn(true);
        when(liveCategories.items(CAT)).thenReturn(IntStream.range(0, 5)
                .mapToObj(i -> ContentItemDto.video("live0000000" + i, "t", null, 0, null, null, null, null, "c", null)).toList());
    }

    @Test
    void homeAndVideosPageTheExpandedList() throws Exception {
        for (String type : List.of("HOME", "VIDEOS")) {
            CursorPageDto<ContentItemDto> first = service.getContent(type, null, 3, CAT, null, null, null);
            CursorPageDto<ContentItemDto> second = service.getContent(type, first.getPageInfo().getNextCursor(), 3, CAT, null, null, null);

            assertThat(first.getData()).extracting(ContentItemDto::getId).containsExactly("live00000000", "live00000001", "live00000002");
            assertThat(second.getData()).extracting(ContentItemDto::getId).containsExactly("live00000003", "live00000004");
            assertThat(second.getPageInfo().getNextCursor()).isNull();
        }
    }

    @Test
    void channelsStillListTheChannelCards() throws Exception {
        when(channels.findApprovedByCategoryAndSubscribersDescWithCursor(anyString(), anyInt(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new ChannelRepository.PaginatedResult<>(List.of(), null, false));

        service.getContent("CHANNELS", null, 10, CAT, null, null, null);

        verify(liveCategories, never()).items(anyString());
    }

    @Test
    void theHomeSectionCountsTheExpandedItems() throws Exception {
        Category live = new Category("Channels Live", null);
        live.setId(CAT);
        live.setType(Category.TYPE_LIVE);
        live.setDisplayOrder(17);
        when(categories.findAll()).thenReturn(List.of(live));

        CursorPageDto<HomeCategoryDto> feed = service.getHomeFeed(null, 5, 2, null);

        HomeCategoryDto section = feed.getData().get(0);
        assertThat(section.getItems()).hasSize(2);
        assertThat(section.getTotalContentCount()).isEqualTo(5);
    }
}
