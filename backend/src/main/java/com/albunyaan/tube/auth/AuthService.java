package com.albunyaan.tube.auth;

import com.albunyaan.tube.user.User;
import com.albunyaan.tube.user.UserRepository;
import com.albunyaan.tube.user.UserStatus;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    public AuthService(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        JwtService jwtService,
        TokenBlacklistService tokenBlacklistService
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    public TokenBundle login(String email, String password) {
        var user = userRepository
            .findByEmailIgnoreCase(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not active");
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        return issueTokens(user);
    }

    public TokenBundle refresh(String refreshToken) {
        var parsed = parseTokenOrThrow(refreshToken, TokenType.REFRESH);

        if (tokenBlacklistService.isBlacklisted(parsed.jti())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked");
        }

        var user = userRepository
            .findByIdAndStatus(parsed.userId(), UserStatus.ACTIVE)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not active"));

        tokenBlacklistService.blacklist(parsed.jti(), parsed.expiresAt());

        return issueTokens(user);
    }

    public void logout(String accessToken, String refreshToken) {
        if (StringUtils.hasText(accessToken)) {
            blacklistSilently(accessToken);
        }
        if (StringUtils.hasText(refreshToken)) {
            blacklistSilently(refreshToken);
        }
    }

    private void blacklistSilently(String token) {
        try {
            var parsed = jwtService.parse(token);
            tokenBlacklistService.blacklist(parsed.jti(), parsed.expiresAt());
        } catch (JwtService.JwtValidationException ignored) {
            // Ignore malformed tokens on logout to keep endpoint idempotent
        }
    }

    private TokenBundle issueTokens(User user) {
        var access = jwtService.generateToken(user, TokenType.ACCESS);
        var refresh = jwtService.generateToken(user, TokenType.REFRESH);
        return new TokenBundle(access, refresh);
    }

    private JwtService.ParsedToken parseTokenOrThrow(String token, TokenType expectedType) {
        var parsed = jwtService.parse(token);
        if (parsed.tokenType() != expectedType) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid token type");
        }
        return parsed;
    }

    public record TokenBundle(JwtService.TokenDetails accessToken, JwtService.TokenDetails refreshToken) {}
}
