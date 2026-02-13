package com.albunyaan.tube.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * BACKEND-PERF-01: Caffeine Cache Configuration
 *
 * Configures in-memory caching strategy with different TTLs for different data types.
 * Uses Caffeine for high-performance caching.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Cache names with specific TTLs
     */
    public static final String CACHE_CATEGORIES = "categories";
    public static final String CACHE_CHANNELS = "channels";
    public static final String CACHE_PLAYLISTS = "playlists";
    public static final String CACHE_VIDEOS = "videos";
    public static final String CACHE_PUBLIC_CONTENT = "public-content";
    public static final String CACHE_PUBLIC_CONTENT_SEARCH = "public-content-search";
    public static final String CACHE_CATEGORY_TREE = "category-tree";
    public static final String CACHE_YOUTUBE_CHANNEL_SEARCH = "youtubeChannelSearch";
    public static final String CACHE_YOUTUBE_PLAYLIST_SEARCH = "youtubePlaylistSearch";
    public static final String CACHE_YOUTUBE_VIDEO_SEARCH = "youtubeVideoSearch";
    public static final String CACHE_WORKSPACE_EXCLUSIONS = "workspaceExclusions";

    // NewPipe extractor caches (used by SearchOrchestrator, ChannelOrchestrator)
    public static final String CACHE_NEWPIPE_SEARCH_RESULTS = "newpipeSearchResults";
    public static final String CACHE_NEWPIPE_CHANNEL_INFO = "newpipeChannelInfo";
    public static final String CACHE_NEWPIPE_PLAYLIST_INFO = "newpipePlaylistInfo";
    public static final String CACHE_NEWPIPE_VIDEO_INFO = "newpipeVideoInfo";
    public static final String CACHE_NEWPIPE_CHANNEL_VALIDATION = "newpipeChannelValidation";
    public static final String CACHE_NEWPIPE_PLAYLIST_VALIDATION = "newpipePlaylistValidation";
    public static final String CACHE_NEWPIPE_VIDEO_VALIDATION = "newpipeVideoValidation";

    // Category mapping cache (used by CategoryMappingService)
    public static final String CACHE_CATEGORY_NAME_MAPPING = "categoryNameMapping";

    // Dashboard stats cache (5-minute TTL for admin dashboard by-category stats)
    public static final String CACHE_DASHBOARD_CATEGORY_STATS = "dashboardCategoryStats";

    /**
     * Configure Caffeine CacheManager with default settings.
     *
     * IMPORTANT: All cache names used by @Cacheable annotations MUST be registered here
     * or the application will throw "Cannot find cache named ..." at runtime.
     *
     * To add a new cache:
     * 1. Add a constant for the cache name above
     * 2. Add it to the cacheManager constructor below
     * 3. Update any @Cacheable annotations to use the constant
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                // Category caches
                CACHE_CATEGORY_TREE,
                CACHE_CATEGORIES,
                CACHE_CATEGORY_NAME_MAPPING,

                // Content caches
                CACHE_CHANNELS,
                CACHE_PLAYLISTS,
                CACHE_VIDEOS,
                CACHE_PUBLIC_CONTENT,
                CACHE_PUBLIC_CONTENT_SEARCH,

                // YouTube admin search caches
                CACHE_YOUTUBE_CHANNEL_SEARCH,
                CACHE_YOUTUBE_PLAYLIST_SEARCH,
                CACHE_YOUTUBE_VIDEO_SEARCH,

                // NewPipe extractor caches (SearchOrchestrator, ChannelOrchestrator)
                CACHE_NEWPIPE_SEARCH_RESULTS,
                CACHE_NEWPIPE_CHANNEL_INFO,
                CACHE_NEWPIPE_PLAYLIST_INFO,
                CACHE_NEWPIPE_VIDEO_INFO,
                CACHE_NEWPIPE_CHANNEL_VALIDATION,
                CACHE_NEWPIPE_PLAYLIST_VALIDATION,
                CACHE_NEWPIPE_VIDEO_VALIDATION

                // Note: workspace exclusions and dashboard category stats use dedicated beans
                // with 5-min TTL, not the CacheManager (see beans below)
        );

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(1000)
                .recordStats());

        return cacheManager;
    }

    /**
     * Dedicated cache for workspace exclusions with shorter TTL (5 minutes).
     * Stores a single CachedExclusions entry (from ExclusionsWorkspaceController)
     * containing the aggregated list of all exclusions and a truncation flag,
     * avoiding repeated full Firestore collection scans on every request.
     *
     * Invalidated by: ChannelController (exclusion mutations),
     * RegistryController (playlist exclusion mutations),
     * ExclusionsWorkspaceController (create/delete).
     */
    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1)  // Only one cached entry (CachedExclusions wrapper)
                .recordStats()
                .build();
    }

    /**
     * Dedicated cache for dashboard category stats with 5-minute TTL.
     * This prevents N+1 aggregation queries from exhausting Firestore quota
     * when dashboard is refreshed frequently or has many categories.
     *
     * Key: "all" (single cached result for all categories)
     * Value: Map<String, CategoryStats> (category ID to stats)
     */
    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, java.util.Map<String, ?>> dashboardCategoryStatsCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(1)  // Only one cached result
                .recordStats()
                .build();
    }
}

