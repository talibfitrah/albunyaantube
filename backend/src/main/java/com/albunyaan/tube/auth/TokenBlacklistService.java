package com.albunyaan.tube.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TokenBlacklistService {

    private static final String KEY_PREFIX = "auth:blacklist:";
    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);

    private final StringRedisTemplate redisTemplate;
    private final Map<String, Instant> inMemoryBlacklist;

    public TokenBlacklistService(@Nullable StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        if (redisTemplate == null) {
            logger.warn("StringRedisTemplate bean not found. Falling back to in-memory token blacklist. "
                    + "This should only be used for local development.");
            this.inMemoryBlacklist = new ConcurrentHashMap<>();
        } else {
            this.inMemoryBlacklist = null;
        }
    }

    public void blacklist(String jti, Instant expiresAt) {
        if (!StringUtils.hasText(jti) || expiresAt == null) {
            return;
        }
        var ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        if (redisTemplate != null) {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
        } else if (inMemoryBlacklist != null) {
            inMemoryBlacklist.put(jti, expiresAt);
        }
    }

    public boolean isBlacklisted(String jti) {
        if (!StringUtils.hasText(jti)) {
            return false;
        }
        if (redisTemplate != null) {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        }
        if (inMemoryBlacklist != null) {
            var expiresAt = inMemoryBlacklist.get(jti);
            if (expiresAt == null) {
                return false;
            }
            if (Instant.now().isAfter(expiresAt)) {
                inMemoryBlacklist.remove(jti, expiresAt);
                return false;
            }
            return true;
        }
        return false;
    }
}
