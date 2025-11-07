package com.albunyaan.tube.service;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
public class DownloadTokenService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long TOKEN_VALIDITY_MS = 3600000;
    private static final int MIN_KEY_LENGTH = 32; // Minimum 32 characters for security

    private final String secretKey;

    public DownloadTokenService(@Value("${download.token.secret-key}") String secretKey) {
        this.secretKey = secretKey;
    }

    @PostConstruct
    public void validateSecretKey() {
        if (secretKey == null || secretKey.isEmpty()) {
            throw new IllegalStateException("Download token secret key must not be null or empty. " +
                    "Please set 'download.token.secret-key' in application properties.");
        }

        if (secretKey.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException("Download token secret key must be at least " + MIN_KEY_LENGTH +
                    " characters long for security. Current length: " + secretKey.length());
        }

        // Validate key contains only allowed characters (alphanumeric and common symbols)
        if (!secretKey.matches("^[a-zA-Z0-9!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]+$")) {
            throw new IllegalStateException("Download token secret key contains invalid characters. " +
                    "Only alphanumeric characters and common symbols are allowed.");
        }
    }

    public String generateToken(String videoId, String userId) {
        long expiresAt = System.currentTimeMillis() + TOKEN_VALIDITY_MS;
        String payload = videoId + "|" + userId + "|" + expiresAt;
        try {
            String signature = generateSignature(payload);
            String token = payload + "|" + signature;
            return Base64.getUrlEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate download token", e);
        }
    }

    public boolean validateToken(String token, String videoId) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 4) return false;
            
            String tokenVideoId = parts[0];
            String userId = parts[1];
            long expiresAt = Long.parseLong(parts[2]);
            String signature = parts[3];
            
            if (System.currentTimeMillis() > expiresAt) return false;
            if (!tokenVideoId.equals(videoId)) return false;
            
            String payload = tokenVideoId + "|" + userId + "|" + expiresAt;
            String expectedSignature = generateSignature(payload);
            return signature.equals(expectedSignature);
        } catch (Exception e) {
            return false;
        }
    }

    public long getExpirationTime(String videoId, String userId) {
        return System.currentTimeMillis() + TOKEN_VALIDITY_MS;
    }

    /**
     * Extract expiration time from an existing token.
     * Used when validating or using a token to get its actual expiration time.
     *
     * @param token The base64-encoded token
     * @return The expiration timestamp in milliseconds, or 0 if token is invalid
     */
    public long getExpirationTimeFromToken(String token) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 4) return 0;

            return Long.parseLong(parts[2]);
        } catch (Exception e) {
            return 0;
        }
    }

    private String generateSignature(String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}

