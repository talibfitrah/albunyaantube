package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.SearchIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** F13: `/content` with a 1-character `q` serves the plain list (ruling F14), so it is cached like the plain list. */
@SpringJUnitConfig(PublicContentCacheKeyTest.Config.class)
class PublicContentCacheKeyTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager();
        }

        @Bean
        ChannelRepository channelRepository() throws Exception {
            ChannelRepository channels = mock(ChannelRepository.class);
            when(channels.findApprovedBySubscribersDescWithCursor(anyInt(), isNull()))
                    .thenReturn(new ChannelRepository.PaginatedResult<>(List.of(), null, false));
            return channels;
        }

        @Bean
        PlaylistRepository playlistRepository() {
            return mock(PlaylistRepository.class);
        }

        @Bean
        CategoryRepository categoryRepository() {
            return mock(CategoryRepository.class);
        }

        @Bean
        VideoRepository videoRepository() {
            return mock(VideoRepository.class);
        }

        @Bean
        PublicContentService publicContentService(ChannelRepository channels, PlaylistRepository playlists,
                                                  VideoRepository videos, CategoryRepository categories) {
            return new PublicContentService(channels, playlists, videos,
                    categories, mock(CategoryContentOrderRepository.class), Runnable::run,
                    mock(SearchIndex.class));
        }
    }

    @Autowired private PublicContentService service;
    @Autowired private ChannelRepository channels;
    @Autowired private PlaylistRepository playlists;
    @Autowired private CategoryRepository categories;
    @Autowired private VideoRepository videos;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void freshCacheAndCounts() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        clearInvocations(channels, playlists, categories);
    }

    private static String cursor(String decoded) {
        return Base64.getEncoder().encodeToString(decoded.getBytes());
    }

    @Test
    void aOneCharacterQueryIsServedFromThePlainListCache() throws Exception {
        // The controller falls through to getContent for q under 2 characters; q no longer reaches the cache key.
        service.getContent("CHANNELS", null, 20, null, null, null, null);
        service.getContent("CHANNELS", null, 20, null, null, null, null);
        verify(channels, times(1)).findApprovedBySubscribersDescWithCursor(20, null);
    }

    @Test
    void aCursorPastTheIntRangeNeverPoisonsTheFirstPage() throws Exception {
        // B1: the key read "3000000000" as invalid ("0", the first page) while the page read it as offset
        // Integer.MAX_VALUE and answered empty: one anonymous GET blanked "See All" for everyone for 1 h.
        Playlist p1 = new Playlist("PLone"), p2 = new Playlist("PLtwo");
        p1.setId("p1");
        p1.setStatus("APPROVED");
        p2.setId("p2");
        p2.setStatus("APPROVED");
        when(playlists.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(List.of(p1, p2));

        service.getContent("HOME", cursor("3000000000"), 20, "kids", null, null, null);

        assertEquals(2, service.getContent("HOME", null, 20, "kids", null, null, null).getData().size());
    }

    @Test
    void aNegativeHomeFeedCursorNeverSharesTheKeyOfARealOne() throws Exception {
        // The home key clamped the order to 0, so "-5:c1" (served from before c1) cached under "0:c1"'s key.
        when(categories.findAll()).thenReturn(List.of(category("c1", 0), category("c2", 1), category("c3", 2)));
        Channel channel = new Channel("UCany");
        channel.setId("ch1");
        channel.setStatus("APPROVED");
        when(channels.findByCategoryIds(anyList(), anyInt())).thenReturn(List.of(channel));

        service.getHomeFeed(cursor("-5:c1"), 1, 10, null);

        assertEquals("c2", service.getHomeFeed(cursor("0:c1"), 1, 10, null).getData().get(0).getId());
    }

    @Test
    void malformedOrOutOfRangeCursorsAreServedButNeverCached() throws Exception {
        for (String bad : List.of("%%%", cursor("abc"), cursor("-5"), cursor("3000000000"))) {
            service.getContent("HOME", bad, 20, "kids", null, null, null);
            service.getContent("HOME", bad, 20, "kids", null, null, null);
        }
        verify(categories, times(8)).findByParentId("kids"); // every request ran

        for (String bad : List.of("%%%", cursor("x:c1"), cursor("3000000000:c1"))) {
            service.getHomeFeed(bad, 1, 10, null);
            service.getHomeFeed(bad, 1, 10, null);
        }
        verify(categories, times(6)).findAll();

        // Positive control: a page the server itself links to is still cached.
        service.getContent("HOME", cursor("20"), 20, "kids2", null, null, null);
        service.getContent("HOME", cursor("20"), 20, "kids2", null, null, null);
        verify(categories, times(1)).findByParentId("kids2");
        service.getHomeFeed(cursor("0:c1"), 1, 10, null);
        service.getHomeFeed(cursor("0:c1"), 1, 10, null);
        verify(categories, times(7)).findAll();
    }

    // ---- X1: a literal "null" query value never shares the key of an omitted parameter ----

    @Test
    void homeWithCategoryNullNeverBlanksTheUnfilteredHomeFeed() throws Exception {
        // SpEL rendered a null category as "null": the unknown-category empty feed was cached under the unfiltered key.
        when(categories.findAll()).thenReturn(List.of(category("c1", 0)));
        Channel channel = new Channel("UCany");
        channel.setId("ch1");
        channel.setStatus("APPROVED");
        when(channels.findByCategoryIds(anyList(), anyInt())).thenReturn(List.of(channel));

        assertTrue(service.getHomeFeed(null, 5, 10, "null").getData().isEmpty(), "precondition: an unknown category");

        assertEquals(List.of("c1"), service.getHomeFeed(null, 5, 10, null).getData().stream().map(c -> c.getId()).toList());
    }

    @Test
    void contentWithCategoryNullNeverBlanksTheUnfilteredTab() throws Exception {
        newestVideosPageHoldsOneVideo();
        when(videos.findApprovedByCategoryAndUploadedAtDescWithCursor(eq("null"), anyInt(), isNull()))
                .thenReturn(new VideoRepository.PaginatedResult<>(List.of(), null, false));

        assertTrue(service.getContent("VIDEOS", null, 20, "null", null, null, null).getData().isEmpty(), "precondition");

        assertEquals(1, service.getContent("VIDEOS", null, 20, null, null, null, null).getData().size());
    }

    @Test
    void contentWithSortNullNeverReplacesTheUnsortedTab() throws Exception {
        // sort=null takes the legacy path (empty here), whose page must not answer the unsorted request.
        newestVideosPageHoldsOneVideo();

        assertTrue(service.getContent("VIDEOS", null, 20, null, null, null, "null").getData().isEmpty(), "precondition");

        assertEquals(1, service.getContent("VIDEOS", null, 20, null, null, null, null).getData().size());
    }

    private void newestVideosPageHoldsOneVideo() throws Exception {
        Video video = new Video("vNewest");
        video.setId("v1");
        video.setStatus("APPROVED");
        when(videos.findApprovedByUploadedAtDescWithCursor(anyInt(), isNull()))
                .thenReturn(new VideoRepository.PaginatedResult<>(List.of(video), null, false));
    }

    private static Category category(String id, int displayOrder) {
        Category c = new Category();
        c.setId(id);
        c.setName(id);
        c.setDisplayOrder(displayOrder);
        return c;
    }
}
