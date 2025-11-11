package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.NextUpDto;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BACKEND-DL-02: Unit tests for PlayerService
 */
@ExtendWith(MockitoExtension.class)
class PlayerServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @InjectMocks
    private PlayerService playerService;

    private Video currentVideo;
    private List<Video> sampleVideos;

    @BeforeEach
    void setUp() {
        currentVideo = new Video("YT-current");
        currentVideo.setId("current-id");
        currentVideo.setTitle("Current Video");
        currentVideo.setCategoryIds(Arrays.asList("cat-tafsir"));
        currentVideo.setChannelId("CH-123");
        currentVideo.setChannelTitle("Test Channel");
        currentVideo.setStatus("APPROVED");
        currentVideo.setDurationSeconds(300);
        currentVideo.setThumbnailUrl("https://example.com/thumb.jpg");

        // Create sample videos
        Video sameCategoryVideo1 = new Video("YT-cat-1");
        sameCategoryVideo1.setId("cat-1");
        sameCategoryVideo1.setTitle("Same Category 1");
        sameCategoryVideo1.setCategoryIds(Arrays.asList("cat-tafsir"));
        sameCategoryVideo1.setChannelId("CH-456");
        sameCategoryVideo1.setChannelTitle("Other Channel");
        sameCategoryVideo1.setStatus("APPROVED");
        sameCategoryVideo1.setDurationSeconds(360);
        sameCategoryVideo1.setThumbnailUrl("https://example.com/thumb1.jpg");

        Video sameCategoryVideo2 = new Video("YT-cat-2");
        sameCategoryVideo2.setId("cat-2");
        sameCategoryVideo2.setTitle("Same Category 2");
        sameCategoryVideo2.setCategoryIds(Arrays.asList("cat-tafsir"));
        sameCategoryVideo2.setChannelId("CH-789");
        sameCategoryVideo2.setChannelTitle("Another Channel");
        sameCategoryVideo2.setStatus("APPROVED");
        sameCategoryVideo2.setDurationSeconds(240);
        sameCategoryVideo2.setThumbnailUrl("https://example.com/thumb2.jpg");

        Video sameChannelVideo = new Video("YT-chan-1");
        sameChannelVideo.setId("chan-1");
        sameChannelVideo.setTitle("Same Channel Video");
        sameChannelVideo.setCategoryIds(Arrays.asList("cat-hadith"));
        sameChannelVideo.setChannelId("CH-123");
        sameChannelVideo.setChannelTitle("Test Channel");
        sameChannelVideo.setStatus("APPROVED");
        sameChannelVideo.setDurationSeconds(180);
        sameChannelVideo.setThumbnailUrl("https://example.com/thumb3.jpg");

        Video pendingVideo = new Video("YT-pending");
        pendingVideo.setId("pending-id");
        pendingVideo.setTitle("Pending Video");
        pendingVideo.setCategoryIds(Arrays.asList("cat-tafsir"));
        pendingVideo.setStatus("PENDING");

        sampleVideos = Arrays.asList(
                currentVideo,
                sameCategoryVideo1,
                sameCategoryVideo2,
                sameChannelVideo,
                pendingVideo
        );
    }

    @Test
    void getNextUpRecommendations_shouldReturnSameCategoryVideos() throws Exception {
        // Arrange
        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        // Stub the correct repository method that's actually called
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList(sampleVideos.get(1), sampleVideos.get(2))); // Same category videos
        when(videoRepository.findByChannelIdAndStatus(eq("CH-123"), eq("APPROVED"), anyInt()))
                .thenReturn(Arrays.asList(sampleVideos.get(3))); // Same channel video

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        assertNotNull(result);
        // 2 from same category + 1 from same channel (to fill up to limit)
        assertEquals(3, result.getItems().size());
        assertTrue(result.getItems().stream().anyMatch(v -> v.getId().equals("YT-cat-1")));
        assertTrue(result.getItems().stream().anyMatch(v -> v.getId().equals("YT-cat-2")));
        assertTrue(result.getItems().stream().anyMatch(v -> v.getId().equals("YT-chan-1")));
    }

    @Test
    void getNextUpRecommendations_shouldExcludeCurrentVideo() throws Exception {
        // Arrange
        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        // Return current video in category results to verify it gets filtered out
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList(currentVideo, sampleVideos.get(1), sampleVideos.get(2)));

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        assertFalse(result.getItems().stream().anyMatch(v -> v.getId().equals("YT-current")));
    }

    @Test
    void getNextUpRecommendations_shouldExcludePendingVideos() throws Exception {
        // Arrange
        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        // Note: findByCategoryIdWithLimit only returns APPROVED videos, so pending is already filtered by repo
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList(sampleVideos.get(1), sampleVideos.get(2))); // Only approved videos

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        assertFalse(result.getItems().stream().anyMatch(v -> v.getId().equals("YT-pending")));
    }

    @Test
    void getNextUpRecommendations_shouldIncludeSameChannelVideos_whenNotEnoughCategoryVideos() throws Exception {
        // Arrange - only 1 same-category video
        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        // Return only 1 video from category
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList(sampleVideos.get(1))); // Only 1 same category video
        // Return additional video from same channel
        when(videoRepository.findByChannelIdAndStatus(eq("CH-123"), eq("APPROVED"), anyInt()))
                .thenReturn(Arrays.asList(sampleVideos.get(3))); // Same channel video

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        assertEquals(2, result.getItems().size());
        assertTrue(result.getItems().stream().anyMatch(v -> v.getId().equals("YT-cat-1")));
        assertTrue(result.getItems().stream().anyMatch(v -> v.getId().equals("YT-chan-1")));
    }

    @Test
    void getNextUpRecommendations_shouldReturnEmpty_whenVideoNotFound() throws Exception {
        // Arrange
        when(videoRepository.findByYoutubeId("nonexistent")).thenReturn(Optional.empty());

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("nonexistent", "user-123");

        // Assert
        assertNotNull(result);
        assertTrue(result.getItems().isEmpty());
        assertNull(result.getNextCursor());
    }

    @Test
    void getNextUpRecommendations_shouldReturnEmpty_whenNoRecommendations() throws Exception {
        // Arrange
        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        // Return no videos from category or channel
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList()); // No category videos
        when(videoRepository.findByChannelIdAndStatus(eq("CH-123"), eq("APPROVED"), anyInt()))
                .thenReturn(Arrays.asList()); // No channel videos

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        assertNotNull(result);
        assertTrue(result.getItems().isEmpty());
    }

    @Test
    void getNextUpRecommendations_shouldSetCorrectVideoItemFields() throws Exception {
        // Arrange
        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList(sampleVideos.get(1)));

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        NextUpDto.VideoItem firstItem = result.getItems().get(0);
        assertNotNull(firstItem.getId());
        assertNotNull(firstItem.getTitle());
        assertNotNull(firstItem.getChannelName());
        assertTrue(firstItem.getDurationSeconds() > 0);
        assertNotNull(firstItem.getThumbnailUrl());
        assertNotNull(firstItem.getCategory());
    }

    @Test
    void getNextUpRecommendations_shouldHandleNullChannelTitle() throws Exception {
        // Arrange
        Video videoWithoutChannel = new Video("YT-no-channel");
        videoWithoutChannel.setId("no-channel");
        videoWithoutChannel.setTitle("No Channel Video");
        videoWithoutChannel.setCategoryIds(Arrays.asList("cat-tafsir"));
        videoWithoutChannel.setChannelTitle(null); // NULL channel title
        videoWithoutChannel.setStatus("APPROVED");
        videoWithoutChannel.setDurationSeconds(120);
        videoWithoutChannel.setThumbnailUrl("https://example.com/thumb.jpg");

        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList(videoWithoutChannel));

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        NextUpDto.VideoItem item = result.getItems().stream()
                .filter(v -> v.getId().equals("YT-no-channel"))
                .findFirst()
                .orElse(null);

        assertNotNull(item);
        assertEquals("Unknown", item.getChannelName());
    }

    @Test
    void getNextUpRecommendations_shouldHandleNullDuration() throws Exception {
        // Arrange
        Video videoWithoutDuration = new Video("YT-no-duration");
        videoWithoutDuration.setId("no-duration");
        videoWithoutDuration.setTitle("No Duration Video");
        videoWithoutDuration.setCategoryIds(Arrays.asList("cat-tafsir"));
        videoWithoutDuration.setChannelTitle("Test Channel");
        videoWithoutDuration.setStatus("APPROVED");
        videoWithoutDuration.setDurationSeconds(null); // NULL duration
        videoWithoutDuration.setThumbnailUrl("https://example.com/thumb.jpg");

        when(videoRepository.findByYoutubeId("YT-current")).thenReturn(Optional.of(currentVideo));
        when(videoRepository.findByCategoryIdWithLimit(eq("cat-tafsir"), anyInt()))
                .thenReturn(Arrays.asList(videoWithoutDuration));

        // Act
        NextUpDto result = playerService.getNextUpRecommendations("YT-current", "user-123");

        // Assert
        NextUpDto.VideoItem item = result.getItems().stream()
                .filter(v -> v.getId().equals("YT-no-duration"))
                .findFirst()
                .orElse(null);

        assertNotNull(item);
        assertEquals(0, item.getDurationSeconds());
    }
}

