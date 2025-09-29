package com.albunyaan.tube.auth;

import com.albunyaan.tube.user.Role;
import com.albunyaan.tube.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey secretKey;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;

    public JwtService(JwtProperties properties) {
        if (properties.secret() == null || properties.secret().isBlank()) {
            throw new IllegalStateException("JWT secret must be configured");
        }
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        this.accessTokenTtl = Duration.ofSeconds(properties.accessTokenTtlSeconds());
        this.refreshTokenTtl = Duration.ofSeconds(properties.refreshTokenTtlSeconds());
    }

    public TokenDetails generateToken(User user, TokenType tokenType) {
        var now = Instant.now();
        var expiry = now.plus(tokenType == TokenType.ACCESS ? accessTokenTtl : refreshTokenTtl);
        var jti = UUID.randomUUID().toString();

        var roles = user
            .getRoles()
            .stream()
            .map(Role::getCode)
            .map(Enum::name)
            .toList();

        var claims = Map.<String, Object>of(
            "typ",
            tokenType.name(),
            "email",
            user.getEmail(),
            "roles",
            roles
        );

        var token = Jwts
            .builder()
            .id(jti)
            .subject(user.getId().toString())
            .claims(claims)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expiry))
            .signWith(secretKey)
            .compact();

        return new TokenDetails(token, tokenType, jti, now, expiry, roles, user.getId());
    }

    public ParsedToken parse(String token) {
        try {
            Jws<Claims> jws = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token);
            var claims = jws.getPayload();
            var tokenType = TokenType.valueOf(claims.get("typ", String.class));
            var issuedAt = claims.getIssuedAt().toInstant();
            var expiresAt = claims.getExpiration().toInstant();
            var roles = ((List<?>) claims.get("roles", List.class))
                .stream()
                .map(Object::toString)
                .toList();
            var subject = UUID.fromString(claims.getSubject());
            return new ParsedToken(
                claims.getId(),
                tokenType,
                issuedAt,
                expiresAt,
                roles,
                subject,
                claims.get("email", String.class)
            );
        } catch (JwtException | IllegalArgumentException ex) {
            throw new JwtValidationException("Invalid token", ex);
        }
    }

    public record TokenDetails(
        String token,
        TokenType tokenType,
        String jti,
        Instant issuedAt,
        Instant expiresAt,
        List<String> roles,
        UUID userId
    ) {
    }

    public record ParsedToken(
        String jti,
        TokenType tokenType,
        Instant issuedAt,
        Instant expiresAt,
        List<String> roles,
        UUID userId,
        String email
    ) {
    }

    public static class JwtValidationException extends RuntimeException {
        public JwtValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
