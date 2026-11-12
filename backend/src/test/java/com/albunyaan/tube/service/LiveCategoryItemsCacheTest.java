package com.albunyaan.tube.service;

import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** A LIVE category's expansion (about eight reads) is cached until the next live or content eviction. */
@SpringJUnitConfig(LiveCategoryItemsCacheTest.Config.class)
class LiveCategoryItemsCacheTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean CacheManager cacheManager() { return new ConcurrentMapCacheManager(); }
        @Bean CategoryContentOrderRepository orders() { return mock(CategoryContentOrderRepository.class); }
        @Bean PublicContentCacheService caches(ApplicationEventPublisher events) { return new PublicContentCacheService(events); }

        @Bean
        LiveCategoryService liveCategoryService(CategoryContentOrderRepository orders) {
            return new LiveCategoryService(mock(CategoryRepository.class), orders, mock(ChannelRepository.class),
                    mock(PlaylistRepository.class), mock(VideoRepository.class), mock(LiveChannelService.class));
        }
    }

    @Autowired private LiveCategoryService live;
    @Autowired private CategoryContentOrderRepository orders;
    @Autowired private PublicContentCacheService caches;

    @Test
    void theExpansionIsReadOncePerLiveEviction() throws Exception {
        live.items("cat");
        live.items("cat");
        verify(orders, times(1)).findByCategoryIdOrderByPosition("cat");

        caches.evictForLiveChange();
        live.items("cat");
        verify(orders, times(2)).findByCategoryIdOrderByPosition("cat");
    }
}
