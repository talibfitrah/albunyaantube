package com.albunyaan.tube.util;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.google.cloud.Timestamp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * BACKEND-TEST-01: Test Data Builder
 *
 * Builders for creating test data with sensible defaults.
 */
public class TestDataBuilder {

    /**
     * Create a test category with default values.
     */
    public static Category createCategory(String name) {
        Category category = new Category();
        category.setName(name);
        category.setIcon("test-icon");
        category.setDisplayOrder(0);
        category.setLocalizedNames(new HashMap<>());
        category.setCreatedBy("test-user");
        category.setUpdatedBy("test-user");
        return category;
    }

    /**
     * Create a test category with parent.
     */
    public static Category createSubcategory(String name, String parentId) {
        Category category = createCategory(name);
        category.setParentCategoryId(parentId);
        return category;
    }

    /**
     * Create a test channel with default values.
     */
    public static Channel createChannel(String youtubeId, String name) {
        Channel channel = new Channel(youtubeId);
        channel.setName(name);
        channel.setDescription("Test channel: " + name);
        channel.setThumbnailUrl("https://example.com/thumbnail.jpg");
        channel.setSubscribers(10000L);
        channel.setVideoCount(100);
        channel.setStatus("PENDING");
        channel.setSubmittedBy("test-user");
        channel.setCategoryIds(new ArrayList<>());
        return channel;
    }

    /**
     * Create an approved test channel.
     */
    public static Channel createApprovedChannel(String youtubeId, String name) {
        Channel channel = createChannel(youtubeId, name);
        channel.setStatus("APPROVED");
        channel.setApprovedBy("test-admin");
        return channel;
    }

    /**
     * Create a test channel with category.
     */
    public static Channel createChannelWithCategory(String youtubeId, String name, String categoryId) {
        Channel channel = createChannel(youtubeId, name);
        channel.setCategoryIds(List.of(categoryId));
        return channel;
    }

    /**
     * Create a test playlist with default values.
     */
    public static Playlist createPlaylist(String youtubeId, String title) {
        Playlist playlist = new Playlist(youtubeId);
        playlist.setTitle(title);
        playlist.setDescription("Test playlist: " + title);
        playlist.setThumbnailUrl("https://example.com/playlist-thumbnail.jpg");
        playlist.setItemCount(20);
        playlist.setStatus("PENDING");
        playlist.setSubmittedBy("test-user");
        playlist.setCategoryIds(new ArrayList<>());
        playlist.setExcludedVideoIds(new ArrayList<>());
        return playlist;
    }

    /**
     * Create an approved test playlist.
     */
    public static Playlist createApprovedPlaylist(String youtubeId, String title) {
        Playlist playlist = createPlaylist(youtubeId, title);
        playlist.setStatus("APPROVED");
        playlist.setApprovedBy("test-admin");
        return playlist;
    }

    /**
     * Create a test playlist with category.
     */
    public static Playlist createPlaylistWithCategory(String youtubeId, String title, String categoryId) {
        Playlist playlist = createPlaylist(youtubeId, title);
        playlist.setCategoryIds(List.of(categoryId));
        return playlist;
    }
}
