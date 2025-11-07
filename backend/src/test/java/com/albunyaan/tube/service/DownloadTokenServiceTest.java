package com.albunyaan.tube.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DownloadTokenServiceTest {
    private DownloadTokenService tokenService;

    @BeforeEach
    void setUp() {
        // Use a test secret key (min 32 characters required)
        String testSecretKey = "test-secret-key-for-download-tokens-12345678";
        tokenService = new DownloadTokenService(testSecretKey);
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

    @Test
    void getExpirationTimeFromToken_shouldReturnCorrectTimestamp() {
        String token = tokenService.generateToken("video-123", "user-123");
        long expirationTime = tokenService.getExpirationTimeFromToken(token);

        assertNotEquals(0, expirationTime, "Valid token should have non-zero expiration time");
        assertTrue(expirationTime > System.currentTimeMillis(),
            "Expiration time should be in the future");

        // Should be approximately 1 hour from now (TOKEN_VALIDITY_MS = 3600000ms)
        long expectedExpiration = System.currentTimeMillis() + 3600000;
        long difference = Math.abs(expirationTime - expectedExpiration);
        assertTrue(difference < 1000,
            "Expiration time should be approximately 1 hour from now (within 1 second)");
    }

    @Test
    void getExpirationTimeFromToken_shouldReturnZero_whenTokenIsInvalid() {
        long expirationTime = tokenService.getExpirationTimeFromToken("invalid-token");
        assertEquals(0, expirationTime, "Invalid token should return 0 expiration time");
    }

    @Test
    void getExpirationTimeFromToken_shouldReturnZero_whenTokenIsMalformed() {
        long expirationTime = tokenService.getExpirationTimeFromToken("YWJj");
        assertEquals(0, expirationTime, "Malformed token should return 0 expiration time");
    }

    @Test
    void validateToken_shouldRejectTokenWithExpiredTimestamp() {
        // Create a token manually with an expired timestamp
        long expiredTimestamp = System.currentTimeMillis() - 10000; // 10 seconds ago
        String videoId = "video-123";
        String userId = "user-123";

        // Generate expected signature for expired token
        String payload = videoId + "|" + userId + "|" + expiredTimestamp;
        try {
            java.lang.reflect.Method signatureMethod = tokenService.getClass()
                .getDeclaredMethod("generateSignature", String.class);
            signatureMethod.setAccessible(true);
            String signature = (String) signatureMethod.invoke(tokenService, payload);

            // Construct expired token
            String expiredToken = payload + "|" + signature;
            String encodedToken = java.util.Base64.getUrlEncoder()
                .encodeToString(expiredToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Verify that the expired token is rejected
            assertFalse(tokenService.validateToken(encodedToken, videoId),
                "Token with expired timestamp should fail validation");
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
    }

    @Test
    void tokenLifecycle_shouldWorkCorrectly() {
        String videoId = "video-123";
        String userId = "user-123";

        // Generate token
        String token = tokenService.generateToken(videoId, userId);
        assertNotNull(token, "Token generation should succeed");

        // Verify token is valid immediately after creation
        assertTrue(tokenService.validateToken(token, videoId),
            "Token should be valid immediately after creation");

        // Verify expiration time is in the future
        long expirationTime = tokenService.getExpirationTimeFromToken(token);
        assertTrue(expirationTime > System.currentTimeMillis(),
            "Token expiration should be in the future");

        // Verify expiration time matches getExpirationTime()
        long expectedExpiration = tokenService.getExpirationTime(videoId, userId);
        long difference = Math.abs(expirationTime - expectedExpiration);
        assertTrue(difference < 1000,
            "Token expiration should match getExpirationTime() (within 1 second)");
    }

    @Test
    void validateToken_shouldEnforceExpirationCheck() {
        String token = tokenService.generateToken("video-123", "user-123");
        long expirationTime = tokenService.getExpirationTimeFromToken(token);

        // Verify token is currently valid
        assertTrue(tokenService.validateToken(token, "video-123"),
            "Fresh token should be valid");

        // Verify expiration time is reasonable (approximately 1 hour = 3600000ms)
        long currentTime = System.currentTimeMillis();
        long timeUntilExpiration = expirationTime - currentTime;
        assertTrue(timeUntilExpiration > 3599000 && timeUntilExpiration <= 3600000,
            "Time until expiration should be approximately 1 hour");
    }
}

