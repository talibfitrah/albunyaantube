package com.albunyaan.tube.service;

import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.SearchIndex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
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
        PublicContentService publicContentService(ChannelRepository channels) {
            return new PublicContentService(channels, mock(PlaylistRepository.class), mock(VideoRepository.class),
                    mock(CategoryRepository.class), mock(CategoryContentOrderRepository.class), Runnable::run,
                    mock(SearchIndex.class));
        }
    }

    @Autowired private PublicContentService service;
    @Autowired private ChannelRepository channels;

    @Test
    void aOneCharacterQueryIsServedFromThePlainListCache() throws Exception {
        // The controller falls through to getContent for q under 2 characters; q no longer reaches the cache key.
        service.getContent("CHANNELS", null, 20, null, null, null, null);
        service.getContent("CHANNELS", null, 20, null, null, null, null);
        verify(channels, times(1)).findApprovedBySubscribersDescWithCursor(20, null);
    }
}
