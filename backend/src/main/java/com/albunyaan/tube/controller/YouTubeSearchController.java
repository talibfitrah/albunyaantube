package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.YouTubeSearchResponse;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.YouTubeSearchService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/youtube")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
@Validated
public class YouTubeSearchController {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeSearchController.class);

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final YouTubeSearchService youtubeSearchService;

    public YouTubeSearchController(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            YouTubeSearchService youtubeSearchService
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.youtubeSearchService = youtubeSearchService;
    }

    @GetMapping("/search")
    public YouTubeSearchResponse search(
            @RequestParam @NotBlank @Size(max = 200) String q,
            @RequestParam(required = false, defaultValue = "ALL") YouTubeContentType type,
            @RequestParam(required = false) @Size(max = 2048) String pageToken) {
        return youtubeSearchService.search(q, type, pageToken);
    }

    /**
     * Check which YouTube IDs already exist in the registry.
     *
     * The admin UI searches YouTube directly from the browser; this endpoint is
     * only the backend existence check that prevents duplicate submissions.
     */
    @PostMapping("/check-existing")
    public ResponseEntity<ExistingContentResponse> checkExisting(@RequestBody ExistingContentRequest request) {
        try {
            Set<String> existingChannels = new HashSet<>();
            Set<String> existingPlaylists = new HashSet<>();
            Set<String> existingVideos = new HashSet<>();

            for (String ytId : request.getChannelIds()) {
                try {
                    if (channelRepository.findByYoutubeId(ytId).isPresent()) {
                        existingChannels.add(ytId);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to check channel existence for {}: {}", ytId, e.getMessage());
                }
            }

            for (String ytId : request.getPlaylistIds()) {
                try {
                    if (playlistRepository.findByYoutubeId(ytId).isPresent()) {
                        existingPlaylists.add(ytId);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to check playlist existence for {}: {}", ytId, e.getMessage());
                }
            }

            for (String ytId : request.getVideoIds()) {
                try {
                    if (videoRepository.findByYoutubeId(ytId).isPresent()) {
                        existingVideos.add(ytId);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to check video existence for {}: {}", ytId, e.getMessage());
                }
            }

            return ResponseEntity.ok(new ExistingContentResponse(existingChannels, existingPlaylists, existingVideos));
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    public static class ExistingContentRequest {
        private List<String> channelIds = List.of();
        private List<String> playlistIds = List.of();
        private List<String> videoIds = List.of();

        public List<String> getChannelIds() {
            return channelIds;
        }

        public void setChannelIds(List<String> channelIds) {
            this.channelIds = channelIds != null ? channelIds : List.of();
        }

        public List<String> getPlaylistIds() {
            return playlistIds;
        }

        public void setPlaylistIds(List<String> playlistIds) {
            this.playlistIds = playlistIds != null ? playlistIds : List.of();
        }

        public List<String> getVideoIds() {
            return videoIds;
        }

        public void setVideoIds(List<String> videoIds) {
            this.videoIds = videoIds != null ? videoIds : List.of();
        }
    }

    public static class ExistingContentResponse {
        private final Set<String> existingChannels;
        private final Set<String> existingPlaylists;
        private final Set<String> existingVideos;

        public ExistingContentResponse(Set<String> existingChannels, Set<String> existingPlaylists, Set<String> existingVideos) {
            this.existingChannels = existingChannels;
            this.existingPlaylists = existingPlaylists;
            this.existingVideos = existingVideos;
        }

        public Set<String> getExistingChannels() {
            return existingChannels;
        }

        public Set<String> getExistingPlaylists() {
            return existingPlaylists;
        }

        public Set<String> getExistingVideos() {
            return existingVideos;
        }
    }
}
