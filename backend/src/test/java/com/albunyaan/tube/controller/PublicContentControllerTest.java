package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.service.PublicContentService;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for PublicContentController.
 * Tests edge cases and error handling for video endpoints.
 * FIXED: Import GlobalExceptionHandler to handle exceptions properly in tests
 */
@WebMvcTest(PublicContentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
public class PublicContentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicContentService contentService;

    @MockBean
    private FirebaseAuth firebaseAuth;

    private Video testVideo;

    @BeforeEach
    void setUp() {
        testVideo = new Video();
        testVideo.setId("test-video-id");
        testVideo.setYoutubeId("EnfgPg0Ey3I");
        testVideo.setTitle("Test Video");
        testVideo.setDescription("Test description");
        testVideo.setThumbnailUrl("https://example.com/thumb.jpg");
        testVideo.setDurationSeconds(240);
        testVideo.setStatus("APPROVED");
        testVideo.setValidationStatus(ValidationStatus.VALID);
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Success")
    void testGetVideoDetails_Success() throws Exception {
        // Given
        when(contentService.getVideoDetails(anyString()))
                .thenReturn(testVideo);

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", "EnfgPg0Ey3I")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.youtubeId").value("EnfgPg0Ey3I"))
                .andExpect(jsonPath("$.title").value("Test Video"))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Video Not Found (404)")
    void testGetVideoDetails_NotFound() throws Exception {
        // Given
        when(contentService.getVideoDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Video", "nonexistent-id"));

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", "nonexistent-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Invalid Video ID Format")
    void testGetVideoDetails_InvalidIdFormat() throws Exception {
        // Given - use invalid YouTube ID format (contains invalid chars)
        String invalidId = "invalid@#$%";
        when(contentService.getVideoDetails(invalidId))
                .thenThrow(new ResourceNotFoundException("Video", invalidId));

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", invalidId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Pending Video (Not Approved)")
    void testGetVideoDetails_PendingVideo() throws Exception {
        // Given - Pending video should not be accessible via public API
        when(contentService.getVideoDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Video", "pending-video-id"));

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", "pending-video-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Unavailable Video")
    void testGetVideoDetails_UnavailableVideo() throws Exception {
        // Given - Unavailable video should not be accessible
        when(contentService.getVideoDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Video", "unavailable-video-id"));

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", "unavailable-video-id")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/videos/{videoId} - Special Characters in Video ID")
    void testGetVideoDetails_SpecialCharacters() throws Exception {
        // Given
        String specialVideoId = "test-id-with-dashes_and_underscores";
        testVideo.setYoutubeId(specialVideoId);
        when(contentService.getVideoDetails(specialVideoId))
                .thenReturn(testVideo);

        // When & Then
        mockMvc.perform(get("/api/v1/videos/{videoId}", specialVideoId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.youtubeId").value(specialVideoId));
    }

    @Test
    @DisplayName("GET /api/v1/content - Success with default parameters")
    void testGetContent_DefaultParameters() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/content")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/content - With category filter")
    void testGetContent_WithCategoryFilter() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), anyInt(), eq("islamic-lectures"), any(), any(), any()))
                .thenReturn(page);

        // When & Then
        mockMvc.perform(get("/api/v1/content")
                        .param("category", "islamic-lectures")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/content - Limit validation (max 50)")
    void testGetContent_LimitValidation() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), eq(50), any(), any(), any(), any()))
                .thenReturn(page);

        // When & Then - Request 100, should be capped at 50
        mockMvc.perform(get("/api/v1/content")
                        .param("limit", "100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/content - Limit validation (min 1)")
    void testGetContent_LimitValidation_Minimum() throws Exception {
        // Given
        CursorPageDto page = new CursorPageDto(Collections.emptyList(), null);
        when(contentService.getContent(
                anyString(), any(), eq(1), any(), any(), any(), any()))
                .thenReturn(page);

        // When & Then - Request 0, should be set to 1
        mockMvc.perform(get("/api/v1/content")
                        .param("limit", "0")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/categories - Success")
    void testGetCategories_Success() throws Exception {
        // Given
        when(contentService.getCategories())
                .thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/api/v1/categories")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/channels/{channelId} - Success")
    void testGetChannelDetails_Success() throws Exception {
        // Given
        ChannelDetailsDto channelDto = new ChannelDetailsDto();
        channelDto.setId("test-channel");
        channelDto.setName("Test Channel");
        when(contentService.getChannelDetails(anyString()))
                .thenReturn(channelDto);

        // When & Then
        mockMvc.perform(get("/api/v1/channels/{channelId}", "test-channel")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-channel"));
    }

    @Test
    @DisplayName("GET /api/v1/channels/{channelId} - Channel Not Found")
    void testGetChannelDetails_NotFound() throws Exception {
        // Given
        when(contentService.getChannelDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Channel", "nonexistent"));

        // When & Then
        mockMvc.perform(get("/api/v1/channels/{channelId}", "nonexistent")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/playlists/{playlistId} - Success")
    void testGetPlaylistDetails_Success() throws Exception {
        // Given
        PlaylistDetailsDto playlistDto = new PlaylistDetailsDto();
        playlistDto.setId("test-playlist");
        playlistDto.setName("Test Playlist");
        when(contentService.getPlaylistDetails(anyString()))
                .thenReturn(playlistDto);

        // When & Then
        mockMvc.perform(get("/api/v1/playlists/{playlistId}", "test-playlist")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-playlist"));
    }

    @Test
    @DisplayName("GET /api/v1/playlists/{playlistId} - Playlist Not Found")
    void testGetPlaylistDetails_NotFound() throws Exception {
        // Given
        when(contentService.getPlaylistDetails(anyString()))
                .thenThrow(new ResourceNotFoundException("Playlist", "nonexistent"));

        // When & Then
        mockMvc.perform(get("/api/v1/playlists/{playlistId}", "nonexistent")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /api/v1/search - Success with query")
    void testSearch_Success() throws Exception {
        // Given
        when(contentService.search(anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "islamic lectures")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/search - Empty query")
    void testSearch_EmptyQuery() throws Exception {
        // Given
        when(contentService.search(eq(""), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/v1/search - With type filter")
    void testSearch_WithTypeFilter() throws Exception {
        // Given
        when(contentService.search(anyString(), eq("VIDEOS"), anyInt()))
                .thenReturn(Collections.emptyList());

        // When & Then
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "test")
                        .param("type", "VIDEOS")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/search - Limit validation")
    void testSearch_LimitValidation() throws Exception {
        // Given
        when(contentService.search(anyString(), any(), eq(50)))
                .thenReturn(Collections.emptyList());

        // When & Then - Request 100, should be capped at 50
        mockMvc.perform(get("/api/v1/search")
                        .param("q", "test")
                        .param("limit", "100")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
