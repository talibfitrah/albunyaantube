package com.albunyaan.tube.auth.dto;

import java.time.Instant;
import java.util.List;

public record TokenResponse(
    String tokenType,
    String accessToken,
    Instant accessTokenExpiresAt,
    String refreshToken,
    Instant refreshTokenExpiresAt,
    List<String> roles
) {}
