package com.albunyaan.tube.controller;

import com.albunyaan.tube.service.YouTubeService;
import com.google.api.services.youtube.model.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

/**
 * FIREBASE-MIGRATE-04: YouTube Search Controller
 *
 * Provides YouTube search and preview functionality for admin interface.
 * Used to search and select content before adding to master list.
 */
@RestController
@RequestMapping("/api/admin/youtube")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class YouTubeSearchController {

    private final YouTubeService youtubeService;

    public YouTubeSearchController(YouTubeService youtubeService) {
        this.youtubeService = youtubeService;
    }

    /**
     * Search for channels
     */
    @GetMapping("/search/channels")
    public ResponseEntity<List<SearchResult>> searchChannels(@RequestParam String query) {
        try {
            List<SearchResult> results = youtubeService.searchChannels(query);
            return ResponseEntity.ok(results);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Search for playlists
     */
    @GetMapping("/search/playlists")
    public ResponseEntity<List<SearchResult>> searchPlaylists(@RequestParam String query) {
        try {
            List<SearchResult> results = youtubeService.searchPlaylists(query);
            return ResponseEntity.ok(results);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Search for videos
     */
    @GetMapping("/search/videos")
    public ResponseEntity<List<SearchResult>> searchVideos(@RequestParam String query) {
        try {
            List<SearchResult> results = youtubeService.searchVideos(query);
            return ResponseEntity.ok(results);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get channel details
     */
    @GetMapping("/channels/{channelId}")
    public ResponseEntity<Channel> getChannelDetails(@PathVariable String channelId) {
        try {
            Channel channel = youtubeService.getChannelDetails(channelId);
            if (channel == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(channel);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get videos from a channel
     */
    @GetMapping("/channels/{channelId}/videos")
    public ResponseEntity<List<SearchResult>> getChannelVideos(
            @PathVariable String channelId,
            @RequestParam(required = false) String pageToken
    ) {
        try {
            List<SearchResult> videos = youtubeService.getChannelVideos(channelId, pageToken);
            return ResponseEntity.ok(videos);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get playlists from a channel
     */
    @GetMapping("/channels/{channelId}/playlists")
    public ResponseEntity<List<Playlist>> getChannelPlaylists(
            @PathVariable String channelId,
            @RequestParam(required = false) String pageToken
    ) {
        try {
            List<Playlist> playlists = youtubeService.getChannelPlaylists(channelId, pageToken);
            return ResponseEntity.ok(playlists);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get playlist details
     */
    @GetMapping("/playlists/{playlistId}")
    public ResponseEntity<Playlist> getPlaylistDetails(@PathVariable String playlistId) {
        try {
            Playlist playlist = youtubeService.getPlaylistDetails(playlistId);
            if (playlist == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(playlist);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get videos in a playlist
     */
    @GetMapping("/playlists/{playlistId}/videos")
    public ResponseEntity<List<PlaylistItem>> getPlaylistVideos(
            @PathVariable String playlistId,
            @RequestParam(required = false) String pageToken
    ) {
        try {
            List<PlaylistItem> items = youtubeService.getPlaylistVideos(playlistId, pageToken);
            return ResponseEntity.ok(items);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get video details
     */
    @GetMapping("/videos/{videoId}")
    public ResponseEntity<Video> getVideoDetails(@PathVariable String videoId) {
        try {
            Video video = youtubeService.getVideoDetails(videoId);
            if (video == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(video);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }
}
