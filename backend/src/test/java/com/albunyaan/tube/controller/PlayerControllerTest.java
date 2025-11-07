package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.NextUpDto;
import com.albunyaan.tube.service.PlayerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BACKEND-DL-02: Unit tests for PlayerController
 */
@ExtendWith(MockitoExtension.class)
class PlayerControllerTest {

    @Mock
    private PlayerService playerService;

    @InjectMocks
    private PlayerController playerController;

    @Test
    void getNextUp_shouldReturnRecommendations() throws Exception {
        // Arrange
        NextUpDto.VideoItem item1 = new NextUpDto.VideoItem(
                "YT-1", "Video 1", "Channel 1", 300, "https://thumb1.jpg", "Tafsir"
        );
        NextUpDto.VideoItem item2 = new NextUpDto.VideoItem(
                "YT-2", "Video 2", "Channel 2", 360, "https://thumb2.jpg", "Tafsir"
        );
        NextUpDto nextUpDto = new NextUpDto(Arrays.asList(item1, item2), null);

        when(playerService.getNextUpRecommendations("YT-current", "user-123"))
                .thenReturn(nextUpDto);

        // Act
        ResponseEntity<NextUpDto> response = playerController.getNextUp("YT-current", "user-123");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().getItems().size());
        verify(playerService).getNextUpRecommendations("YT-current", "user-123");
    }

    @Test
    void getNextUp_shouldHandleNullUserId() throws Exception {
        // Arrange
        NextUpDto nextUpDto = new NextUpDto(List.of(), null);
        when(playerService.getNextUpRecommendations("YT-current", null))
                .thenReturn(nextUpDto);

        // Act
        ResponseEntity<NextUpDto> response = playerController.getNextUp("YT-current", null);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(playerService).getNextUpRecommendations("YT-current", null);
    }

    @Test
    void getNextUp_shouldReturnEmptyList_whenNoRecommendations() throws Exception {
        // Arrange
        NextUpDto emptyDto = new NextUpDto(List.of(), null);
        when(playerService.getNextUpRecommendations("YT-unknown", "user-123"))
                .thenReturn(emptyDto);

        // Act
        ResponseEntity<NextUpDto> response = playerController.getNextUp("YT-unknown", "user-123");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().getItems().isEmpty());
    }

    @Test
    void getNextUp_shouldPassCorrectParameters() throws Exception {
        // Arrange
        NextUpDto nextUpDto = new NextUpDto(List.of(), null);
        when(playerService.getNextUpRecommendations(anyString(), anyString()))
                .thenReturn(nextUpDto);

        // Act
        playerController.getNextUp("video-123", "user-456");

        // Assert
        verify(playerService).getNextUpRecommendations("video-123", "user-456");
    }
}

