package com.albunyaan.tube.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadTokenServiceTest {
    private DownloadTokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new DownloadTokenService();
    }

    @Test
    void generateToken_shouldGenerateValidToken() {
        String token = tokenService.generateToken("video-123", "user-123");
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }

    @Test
    void validateToken_shouldReturnTrue_whenTokenIsValid() {
        String token = tokenService.generateToken("video-123", "user-123");
        assertTrue(tokenService.validateToken(token, "video-123"));
    }

    @Test
    void validateToken_shouldReturnFalse_whenVideoIdMismatch() {
        String token = tokenService.generateToken("video-123", "user-123");
        assertFalse(tokenService.validateToken(token, "different-video"));
    }

    @Test
    void validateToken_shouldReturnFalse_whenTokenIsInvalid() {
        assertFalse(tokenService.validateToken("invalid-token", "video-123"));
    }

    @Test
    void validateToken_shouldReturnFalse_whenTokenIsEmpty() {
        assertFalse(tokenService.validateToken("", "video-123"));
    }

    @Test
    void getExpirationTime_shouldReturnFutureTimestamp() {
        long expirationTime = tokenService.getExpirationTime("video-123", "user-123");
        assertTrue(expirationTime > System.currentTimeMillis());
    }

    @Test
    void generateToken_shouldGenerateDifferentTokens_forDifferentUsers() {
        String token1 = tokenService.generateToken("video-123", "user-1");
        String token2 = tokenService.generateToken("video-123", "user-2");
        assertNotEquals(token1, token2);
    }

    @Test
    void generateToken_shouldGenerateDifferentTokens_forDifferentVideos() {
        String token1 = tokenService.generateToken("video-1", "user-123");
        String token2 = tokenService.generateToken("video-2", "user-123");
        assertNotEquals(token1, token2);
    }

    @Test
    void validateToken_shouldHandleMalformedToken() {
        assertFalse(tokenService.validateToken("malformed|token", "video-123"));
        assertFalse(tokenService.validateToken("YWJj", "video-123"));
    }
}

