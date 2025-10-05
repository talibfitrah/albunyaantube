package com.albunyaan.tube.service;

import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Service
public class DownloadTokenService {
    private static final String SECRET_KEY = "albunyaan-download-secret-key-change-in-production";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long TOKEN_VALIDITY_MS = 3600000;

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

    private String generateSignature(String data) throws NoSuchAlgorithmException, InvalidKeyException {
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec secretKeySpec = new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
