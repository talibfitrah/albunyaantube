package com.albunyaan.tube.auth;

import com.albunyaan.tube.auth.dto.LoginRequest;
import com.albunyaan.tube.auth.dto.LogoutRequest;
import com.albunyaan.tube.auth.dto.RefreshRequest;
import com.albunyaan.tube.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        var bundle = authService.login(request.email(), request.password());
        return ResponseEntity.ok(mapToResponse(bundle));
    }

    @PostMapping(path = "/refresh", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        var bundle = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(mapToResponse(bundle));
    }

    @PostMapping(path = "/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> logout(
        @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
        @Valid @RequestBody LogoutRequest request
    ) {
        var accessToken = extractToken(authorizationHeader);
        authService.logout(accessToken, request.refreshToken());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    private TokenResponse mapToResponse(AuthService.TokenBundle bundle) {
        return new TokenResponse(
            "Bearer",
            bundle.accessToken().token(),
            bundle.accessToken().expiresAt(),
            bundle.refreshToken().token(),
            bundle.refreshToken().expiresAt(),
            bundle.accessToken().roles()
        );
    }

    private String extractToken(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        return authorizationHeader.substring(7);
    }
}
