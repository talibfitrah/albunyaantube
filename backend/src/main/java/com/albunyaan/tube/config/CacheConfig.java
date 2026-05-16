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
    public static final String CACHE_NEWPIPE_CHANNEL_VIDEOS = "newpipeChannelVideos";
    public static final String CACHE_NEWPIPE_CHANNEL_PLAYLISTS = "newpipeChannelPlaylists";
    public static final String CACHE_NEWPIPE_PLAYLIST_VIDEOS = "newpipePlaylistVideos";

    // Category mapping cache (used by CategoryMappingService)
    public static final String CACHE_CATEGORY_NAME_MAPPING = "categoryNameMapping";

    // User status cache (60s TTL per D4; evicted on lifecycle mutation)
    public static final String CACHE_USER_STATUS = "userStatus";

    // Cubic R-final4 P2 added 30s archive-flag caches; Cubic R-final7 P0
    // reverted them. The TTL meant admin archive actions took up to 30s to
    // surface in sync DTOs — a real moderation-freshness gap. The perf
    // benefit was theoretical at single-digit RPS. If write load ever
    // justifies caching, the right design is @CacheEvict on the admin
    // archive endpoints, not a TTL.

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
                CACHE_NEWPIPE_VIDEO_VALIDATION,
                CACHE_NEWPIPE_CHANNEL_VIDEOS,
                CACHE_NEWPIPE_CHANNEL_PLAYLISTS,
                CACHE_NEWPIPE_PLAYLIST_VIDEOS,

                // User status cache (overridden below to 60s TTL per D4)
                CACHE_USER_STATUS

                // Note: workspace exclusions use a dedicated bean with 5-min TTL,
                // not the CacheManager (see bean below)
        );

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(1000)
                .recordStats());

        // Override default for userStatus only — 60s TTL, 5,000 max entries (D4)
        cacheManager.registerCustomCache(CACHE_USER_STATUS,
                Caffeine.newBuilder()
                        .expireAfterWrite(60, TimeUnit.SECONDS)
                        .maximumSize(5_000)
                        .recordStats()
                        .build());

        // Cubic R-final5 P2 — order-of-operations guard.
        //
        // CaffeineCacheManager builds caches lazily on first getCache() call,
        // using setCaffeine(default-builder) unless registerCustomCache has
        // pre-registered one. Spring guarantees the bean method runs to
        // completion BEFORE any @PostConstruct on dependent beans fires, so
        // every registerCustomCache above is durable by the time consumers
        // touch the manager — there is no race against startup reads.
        //
        // The risk cubic flagged is a future addition that inserts a read
        // between setCaffeine and registerCustomCache (or between registers).
        // Assertion below makes that drift explicit: if the assertion ever
        // fails, somebody removed a registration without updating the check.
        if (cacheManager.getCache(CACHE_USER_STATUS) == null) {
            throw new IllegalStateException(
                    "CacheConfig: custom-TTL cache missing after registration — "
                    + "a startup read may have lazily built it with the default 1h TTL.");
        }

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
     * Rate-limit cache for content report submissions.
     * Key: deviceId or IP address. Value: submission count within the TTL window.
     * Max 5 submissions per device per hour.
     */
    @Bean
    public com.github.benmanes.caffeine.cache.Cache<String, java.util.concurrent.atomic.AtomicInteger> reportRateLimitCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(10_000)
                .build();
    }
}
