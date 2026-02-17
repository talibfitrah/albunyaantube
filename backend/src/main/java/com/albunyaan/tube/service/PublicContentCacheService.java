package com.albunyaan.tube.service;

import com.albunyaan.tube.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

/**
 * Service for evicting public content caches.
 *
 * Called after any content mutation (approve, reject, delete, reorder, add, category change)
 * to ensure the Android app sees changes immediately instead of waiting for the 1-hour TTL.
 */
@Service
public class PublicContentCacheService {

    private static final Logger log = LoggerFactory.getLogger(PublicContentCacheService.class);

    /**
     * Evict all public-facing content caches.
     *
     * This clears:
     * - public-content: paginated content served to the Android app
     * - public-content-search: search results served to the Android app
     * - category-tree: category listings (public + admin)
     */
    @CacheEvict(value = {
            CacheConfig.CACHE_PUBLIC_CONTENT,
            CacheConfig.CACHE_PUBLIC_CONTENT_SEARCH,
            CacheConfig.CACHE_CATEGORY_TREE
    }, allEntries = true)
    public void evictPublicContentCaches() {
        log.debug("Evicted public content caches: {}, {}, {}",
                CacheConfig.CACHE_PUBLIC_CONTENT,
                CacheConfig.CACHE_PUBLIC_CONTENT_SEARCH,
                CacheConfig.CACHE_CATEGORY_TREE);
    }
}
