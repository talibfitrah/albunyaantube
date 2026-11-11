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

    /** Stage 9 B13: published on every eviction; the live endpoint's view refreshes at the poller's next tick. */
    public record Evicted() {}

    /** Published when content (not just live status) changed; {@link PublicContentService} drops its kept tab order. */
    public record ContentChanged() {}

    private final org.springframework.context.ApplicationEventPublisher events;

    public PublicContentCacheService(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    /**
     * Evict all public-facing content caches.
     *
     * This clears:
     * - public-content: paginated content served to the Android app
     * - category-tree: category listings (public + admin)
     * - newpipe-search-results: moderator YouTube-search responses (the
     *   {@code alreadyKnown} annotation is baked into the cached value, so
     *   any registry mutation can leave it stale).
     */
    @CacheEvict(value = {
            CacheConfig.CACHE_PUBLIC_CONTENT,
            CacheConfig.CACHE_CATEGORY_TREE,
            CacheConfig.CACHE_NEWPIPE_SEARCH_RESULTS
    }, allEntries = true)
    public void evictPublicContentCaches() {
        events.publishEvent(new Evicted());
        events.publishEvent(new ContentChanged());
        log.debug("Evicted public content caches: {}, {}, {}",
                CacheConfig.CACHE_PUBLIC_CONTENT,
                CacheConfig.CACHE_CATEGORY_TREE,
                CacheConfig.CACHE_NEWPIPE_SEARCH_RESULTS);
    }

    /**
     * Same eviction for a live-status change, which does not change the tab order: no {@link ContentChanged},
     * so the kept order survives (a rebuild costs 2-3x the catalog in reads).
     */
    @CacheEvict(value = {
            CacheConfig.CACHE_PUBLIC_CONTENT,
            CacheConfig.CACHE_CATEGORY_TREE,
            CacheConfig.CACHE_NEWPIPE_SEARCH_RESULTS
    }, allEntries = true)
    public void evictForLiveChange() {
        events.publishEvent(new Evicted());
    }
}
