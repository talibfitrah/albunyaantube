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

    // Dashboard stats cache (5-minute TTL for admin dashboard by-category stats)
    public static final String CACHE_DASHBOARD_CATEGORY_STATS = "dashboardCategoryStats";

    // User status cache (60s TTL per D4; evicted on lifecycle mutation)
    public static final String CACHE_USER_STATUS = "userStatus";

    // Cubic R-final4 P2 — archive-flag caches for ArchiveProjector write-path
    // single-row lookups. SyncService upsert / tombstone methods each call
    // `projector.projectSubscription/projectPlaylist/projectVideo`, which
    // calls `channels/playlists/videos.isArchivedById(row.id())` — one
    // Firestore round-trip per write. With three short-TTL caches the
    // 2x write cost collapses to ~1x on cache-hit. 30s TTL accepts up to
    // ~30s of stale archive state on sync DTOs, which is fine because:
    //   - Archive flips are admin/validation driven, not user-driven
    //   - The DTO consequence is "user re-fetches an archived item once
    //     more before the next sync sees archived=true"; harmless
    public static final String CACHE_CHANNEL_ARCHIVE_FLAG = "channelArchiveFlag";
    public static final String CACHE_PLAYLIST_ARCHIVE_FLAG = "playlistArchiveFlag";
    public static final String CACHE_VIDEO_ARCHIVE_FLAG = "videoArchiveFlag";

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
                CACHE_USER_STATUS,

                // Archive-flag caches (overridden below to 30s TTL each)
                CACHE_CHANNEL_ARCHIVE_FLAG,
                CACHE_PLAYLIST_ARCHIVE_FLAG,
                CACHE_VIDEO_ARCHIVE_FLAG

                // Note: workspace exclusions and dashboard category stats use dedicated beans
                // with 5-min TTL, not the CacheManager (see beans below)
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

        // Cubic R-final4 P2 — archive-flag caches with 30s TTL.
        // Sized generously (10k entries each) because sync hot-paths are
        // user-driven and can churn through many distinct IDs.
        for (String name : java.util.List.of(
                CACHE_CHANNEL_ARCHIVE_FLAG,
                CACHE_PLAYLIST_ARCHIVE_FLAG,
                CACHE_VIDEO_ARCHIVE_FLAG)) {
            cacheManager.registerCustomCache(name,
                    Caffeine.newBuilder()
                            .expireAfterWrite(30, TimeUnit.SECONDS)
                            .maximumSize(10_000)
                            .recordStats()
                            .build());
        }

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
        if (cacheManager.getCache(CACHE_USER_STATUS) == null
                || cacheManager.getCache(CACHE_CHANNEL_ARCHIVE_FLAG) == null
                || cacheManager.getCache(CACHE_PLAYLIST_ARCHIVE_FLAG) == null
                || cacheManager.getCache(CACHE_VIDEO_ARCHIVE_FLAG) == null) {
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

