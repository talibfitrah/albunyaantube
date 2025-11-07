package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.NextUpDto;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * BACKEND-DL-02: Player Service
 * 
 * Handles player-related functionality including next-up recommendations.
 */
@Service
public class PlayerService {

    private final VideoRepository videoRepository;
    private static final int DEFAULT_NEXT_UP_LIMIT = 10;

    public PlayerService(VideoRepository videoRepository) {
        this.videoRepository = videoRepository;
    }

    /**
     * Get next-up video recommendations
     *
     * Basic recommendation logic:
     * 1. Find videos from same category
     * 2. If not enough, find videos from same channel
     * 3. Only return APPROVED videos
     */
    public NextUpDto getNextUpRecommendations(String videoId, String userId)
            throws java.util.concurrent.ExecutionException, InterruptedException {
        // Get current video to determine category and channel
        Optional<Video> currentVideoOpt = videoRepository.findByYoutubeId(videoId);
        
        if (currentVideoOpt.isEmpty()) {
            return new NextUpDto(List.of(), null);
        }

        Video currentVideo = currentVideoOpt.get();
        
        // Find videos from same category (using categoryIds)
        List<Video> recommendations = videoRepository.findAll().stream()
                .filter(v -> "APPROVED".equals(v.getStatus()))
                .filter(v -> !v.getYoutubeId().equals(videoId)) // Exclude current video
                .filter(v -> currentVideo.getCategoryIds() != null &&
                            !currentVideo.getCategoryIds().isEmpty() &&
                            v.getCategoryIds() != null &&
                            !v.getCategoryIds().isEmpty() &&
                            v.getCategoryIds().stream().anyMatch(currentVideo.getCategoryIds()::contains))
                .limit(DEFAULT_NEXT_UP_LIMIT)
                .collect(Collectors.toList());

        // If not enough, add videos from same channel
        if (recommendations.size() < DEFAULT_NEXT_UP_LIMIT && currentVideo.getChannelId() != null) {
            List<Video> channelVideos = videoRepository.findAll().stream()
                    .filter(v -> "APPROVED".equals(v.getStatus()))
                    .filter(v -> !v.getYoutubeId().equals(videoId))
                    .filter(v -> currentVideo.getChannelId().equals(v.getChannelId()))
                    .filter(v -> !recommendations.contains(v)) // Avoid duplicates
                    .limit(DEFAULT_NEXT_UP_LIMIT - recommendations.size())
                    .collect(Collectors.toList());

            recommendations.addAll(channelVideos);
        }

        // Convert to DTO
        List<NextUpDto.VideoItem> items = recommendations.stream()
                .map(v -> new NextUpDto.VideoItem(
                        v.getYoutubeId(),
                        v.getTitle(),
                        v.getChannelTitle() != null ? v.getChannelTitle() : "Unknown",
                        v.getDurationSeconds() != null ? v.getDurationSeconds() : 0,
                        v.getThumbnailUrl(),
                        v.getCategoryIds() != null && !v.getCategoryIds().isEmpty()
                            ? v.getCategoryIds().get(0) : null
                ))
                .collect(Collectors.toList());

        // For now, no pagination (nextCursor = null)
        return new NextUpDto(items, null);
    }
}

