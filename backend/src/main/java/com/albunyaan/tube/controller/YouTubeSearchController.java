package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.EnrichedSearchResult;
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
    private final com.albunyaan.tube.repository.ChannelRepository channelRepository;
    private final com.albunyaan.tube.repository.PlaylistRepository playlistRepository;
    private final com.albunyaan.tube.repository.VideoRepository videoRepository;

    public YouTubeSearchController(
            YouTubeService youtubeService,
            com.albunyaan.tube.repository.ChannelRepository channelRepository,
            com.albunyaan.tube.repository.PlaylistRepository playlistRepository,
            com.albunyaan.tube.repository.VideoRepository videoRepository
    ) {
        this.youtubeService = youtubeService;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
    }

    /**
     * Search for all content types (channels, playlists, videos) with enriched metadata
     */
    @GetMapping("/search/all")
    public ResponseEntity<EnrichedSearchAllResponse> searchAll(@RequestParam String query) {
        try {
            List<EnrichedSearchResult> channels = youtubeService.searchChannelsEnriched(query);
            List<EnrichedSearchResult> playlists = youtubeService.searchPlaylistsEnriched(query);
            List<EnrichedSearchResult> videos = youtubeService.searchVideosEnriched(query);

            EnrichedSearchAllResponse response = new EnrichedSearchAllResponse(channels, playlists, videos);
            return ResponseEntity.ok(response);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Search for channels with enriched metadata
     */
    @GetMapping("/search/channels")
    public ResponseEntity<List<EnrichedSearchResult>> searchChannels(@RequestParam String query) {
        try {
            List<EnrichedSearchResult> results = youtubeService.searchChannelsEnriched(query);
            return ResponseEntity.ok(results);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Check which YouTube IDs already exist in the registry
     */
    @PostMapping("/check-existing")
    public ResponseEntity<ExistingContentResponse> checkExisting(@RequestBody ExistingContentRequest request) {
        try {
            java.util.Set<String> existingChannels = new java.util.HashSet<>();
            java.util.Set<String> existingPlaylists = new java.util.HashSet<>();
            java.util.Set<String> existingVideos = new java.util.HashSet<>();

            // Check channels
            for (String ytId : request.getChannelIds()) {
                try {
                    if (channelRepository.findByYoutubeId(ytId).isPresent()) {
                        existingChannels.add(ytId);
                    }
                } catch (Exception ignored) {
                }
            }

            // Check playlists
            for (String ytId : request.getPlaylistIds()) {
                try {
                    if (playlistRepository.findByYoutubeId(ytId).isPresent()) {
                        existingPlaylists.add(ytId);
                    }
                } catch (Exception ignored) {
                }
            }

            // Check videos
            for (String ytId : request.getVideoIds()) {
                try {
                    if (videoRepository.findByYoutubeId(ytId).isPresent()) {
                        existingVideos.add(ytId);
                    }
                } catch (Exception ignored) {
                }
            }

            return ResponseEntity.ok(new ExistingContentResponse(existingChannels, existingPlaylists, existingVideos));
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Request body for checking existing content
     */
    public static class ExistingContentRequest {
        private List<String> channelIds = List.of();
        private List<String> playlistIds = List.of();
        private List<String> videoIds = List.of();

        public List<String> getChannelIds() {
            return channelIds;
        }

        public void setChannelIds(List<String> channelIds) {
            this.channelIds = channelIds;
        }

        public List<String> getPlaylistIds() {
            return playlistIds;
        }

        public void setPlaylistIds(List<String> playlistIds) {
            this.playlistIds = playlistIds;
        }

        public List<String> getVideoIds() {
            return videoIds;
        }

        public void setVideoIds(List<String> videoIds) {
            this.videoIds = videoIds;
        }
    }

    /**
     * Response for existing content check
     */
    public static class ExistingContentResponse {
        private final java.util.Set<String> existingChannels;
        private final java.util.Set<String> existingPlaylists;
        private final java.util.Set<String> existingVideos;

        public ExistingContentResponse(java.util.Set<String> existingChannels, java.util.Set<String> existingPlaylists, java.util.Set<String> existingVideos) {
            this.existingChannels = existingChannels;
            this.existingPlaylists = existingPlaylists;
            this.existingVideos = existingVideos;
        }

        public java.util.Set<String> getExistingChannels() {
            return existingChannels;
        }

        public java.util.Set<String> getExistingPlaylists() {
            return existingPlaylists;
        }

        public java.util.Set<String> getExistingVideos() {
            return existingVideos;
        }
    }

    /**
     * Response wrapper for enriched search all endpoint
     */
    public static class EnrichedSearchAllResponse {
        private final List<EnrichedSearchResult> channels;
        private final List<EnrichedSearchResult> playlists;
        private final List<EnrichedSearchResult> videos;

        public EnrichedSearchAllResponse(List<EnrichedSearchResult> channels, List<EnrichedSearchResult> playlists, List<EnrichedSearchResult> videos) {
            this.channels = channels;
            this.playlists = playlists;
            this.videos = videos;
        }

        public List<EnrichedSearchResult> getChannels() {
            return channels;
        }

        public List<EnrichedSearchResult> getPlaylists() {
            return playlists;
        }

        public List<EnrichedSearchResult> getVideos() {
            return videos;
        }
    }

    /**
     * Search for playlists with enriched metadata
     */
    @GetMapping("/search/playlists")
    public ResponseEntity<List<EnrichedSearchResult>> searchPlaylists(@RequestParam String query) {
        try {
            List<EnrichedSearchResult> results = youtubeService.searchPlaylistsEnriched(query);
            return ResponseEntity.ok(results);
        } catch (IOException e) {
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Search for videos with enriched metadata
     */
    @GetMapping("/search/videos")
    public ResponseEntity<List<EnrichedSearchResult>> searchVideos(@RequestParam String query) {
        try {
            List<EnrichedSearchResult> results = youtubeService.searchVideosEnriched(query);
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
