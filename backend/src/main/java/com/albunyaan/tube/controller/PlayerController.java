package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.NextUpDto;
import com.albunyaan.tube.service.PlayerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * BACKEND-DL-02: Player Controller
 * 
 * Handles player-related API endpoints.
 */
@RestController
@RequestMapping("/api/player")
public class PlayerController {

    private final PlayerService playerService;

    public PlayerController(PlayerService playerService) {
        this.playerService = playerService;
    }

    /**
     * Get next-up video recommendations
     * 
     * @param videoId Current video ID
     * @param userId  User ID (optional, for personalized recommendations in future)
     * @return List of recommended videos
     */
    @GetMapping("/next-up/{videoId}")
    public ResponseEntity<NextUpDto> getNextUp(
            @PathVariable String videoId,
            @RequestParam(required = false) String userId)
            throws java.util.concurrent.ExecutionException, InterruptedException {

        NextUpDto nextUp = playerService.getNextUpRecommendations(videoId, userId);
        return ResponseEntity.ok(nextUp);
    }
}
