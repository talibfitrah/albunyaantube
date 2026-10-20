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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
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
    final SearchIndex searchIndex = mock(SearchIndex.class);
    PublicContentService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new PublicContentService(channels, mock(PlaylistRepository.class), mock(VideoRepository.class), categories,
                mock(CategoryContentOrderRepository.class), Runnable::run, searchIndex, liveCategories);
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

    /** L3: q= on a LIVE category searches the expanded list the unfiltered VIDEOS request serves (1.0.3 sends q). */
    @Test
    void aSearchInALiveCategorySearchesTheExpandedList() throws Exception {
        when(liveCategories.items(CAT)).thenReturn(List.of(
                ContentItemDto.video("makkahlive1", "Makkah Live", null, 0, null, null, null, null, "Quran TV", null),
                ContentItemDto.video("zadlive0001", "بث مباشر القرآن الكريم", null, 0, null, null, null, null, "Zad TV", null),
                ContentItemDto.video("kidslive001", "Kids Live", null, 0, null, null, null, null, "One4kids", null)));
        when(searchIndex.searchPage(anyString(), anyString(), anyInt(), any(), anyLong()))
                .thenReturn(new SearchIndex.Hits(List.of(), "none", 0));   // the catalog index holds no current live

        PublicContentService.SearchPage makkah = service.searchContent("VIDEOS", null, 20, CAT, null, null, "makkah");
        PublicContentService.SearchPage quran = service.searchContent("VIDEOS", null, 20, CAT, null, null, "القران");
        PublicContentService.SearchPage zad = service.searchContent("VIDEOS", null, 20, CAT, null, null, "zad tv");
        PublicContentService.SearchPage live = service.searchContent("VIDEOS", null, 1, CAT, null, null, "live");
        PublicContentService.SearchPage none = service.searchContent("VIDEOS", null, 20, CAT, null, null, "nothing");

        assertThat(makkah.page().getData()).extracting(ContentItemDto::getId).containsExactly("makkahlive1");
        assertThat(makkah.match()).isEqualTo("exact");
        assertThat(quran.page().getData()).extracting(ContentItemDto::getId).containsExactly("zadlive0001");
        assertThat(zad.page().getData()).extracting(ContentItemDto::getId).containsExactly("zadlive0001");
        assertThat(live.page().getData()).extracting(ContentItemDto::getId).containsExactly("makkahlive1");
        CursorPageDto<ContentItemDto> next = service.searchContent("VIDEOS", live.page().getPageInfo().getNextCursor(), 1,
                CAT, null, null, "live").page();
        assertThat(next.getData()).extracting(ContentItemDto::getId).containsExactly("kidslive001");
        assertThat(next.getPageInfo().getNextCursor()).isNull();
        assertThat(none.page().getData()).isEmpty();
        assertThat(none.match()).isEqualTo("none");
    }

    /** L3 / F1, F6: as the index's exact tier, a stem matches (ال stripped) and a stopword is optional. */
    @Test
    void aLiveSearchMatchesStemsAndSkipsStopwords() throws Exception {
        when(liveCategories.items(CAT)).thenReturn(List.of(
                ContentItemDto.video("makkahlive1", "Makkah Live", null, 0, null, null, null, null, "Quran TV", null),
                ContentItemDto.video("zadlive0001", "بث مباشر القرآن الكريم", null, 0, null, null, null, null, "Zad TV", null)));

        assertThat(liveSearch("قرآن")).containsExactly("zadlive0001");
        assertThat(liveSearch("كريم")).containsExactly("zadlive0001");
        assertThat(liveSearch("the makkah")).containsExactly("makkahlive1");
        assertThat(liveSearch("the")).isEmpty();                     // all stopwords: each one is needed
    }

    private List<String> liveSearch(String q) throws Exception {
        return service.searchContent("VIDEOS", null, 20, CAT, null, null, q).page().getData().stream()
                .map(ContentItemDto::getId).toList();
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
