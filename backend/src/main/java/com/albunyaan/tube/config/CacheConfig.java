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
    public static final String CACHE_CATEGORY_TREE = "category-tree";
    public static final String CACHE_YOUTUBE_CHANNEL_SEARCH = "youtubeChannelSearch";
    public static final String CACHE_YOUTUBE_PLAYLIST_SEARCH = "youtubePlaylistSearch";
    public static final String CACHE_YOUTUBE_VIDEO_SEARCH = "youtubeVideoSearch";

    /**
     * Configure Caffeine CacheManager with default settings
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                CACHE_CATEGORY_TREE,
                CACHE_CATEGORIES,
                CACHE_CHANNELS,
                CACHE_PLAYLISTS,
                CACHE_VIDEOS,
                CACHE_YOUTUBE_CHANNEL_SEARCH,
                CACHE_YOUTUBE_PLAYLIST_SEARCH,
                CACHE_YOUTUBE_VIDEO_SEARCH
        );

        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS)
                .maximumSize(1000)
                .recordStats());

        return cacheManager;
    }
}
