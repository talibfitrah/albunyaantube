package com.albunyaan.tube.controller;

import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.PlaybackSolveRateLimiter;
import com.albunyaan.tube.service.PlaybackSolverService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * iOS playback Phase 2 (spec §4.6, R28): the signed sts lookup and challenge solve behind the {@code tv_samsung} HD
 * upgrade. The device sends only {@code {playerId, n[], s[]}} (R21); nothing about a request is logged.
 */
@RestController
@RequestMapping("/api/playback")
public class PlaybackSolverController {

    public record SolveRequest(String playerId, List<String> n, List<String> s) {}

    private final PlaybackSolverService service;
    private final PlaybackSolveRateLimiter limiter;

    public PlaybackSolverController(PlaybackSolverService service, PlaybackSolveRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @GetMapping("/player")
    public ResponseEntity<Object> player() {
        return reply(service.player());
    }

    @PostMapping("/solve")
    public ResponseEntity<Object> solve(@AuthenticationPrincipal FirebaseUserDetails principal, @RequestBody SolveRequest body) {
        Long retryAfter = limiter.tryAcquire(principal.getUid());
        if (retryAfter != null) {
            return ResponseEntity.status(429).header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter)).body(Map.of());
        }
        return reply(service.solve(body.playerId(), body.n(), body.s()));
    }

    private static ResponseEntity<Object> reply(PlaybackSolverService.Answer answer) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(answer.status());
        if (answer.retryAfter() != null) builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(answer.retryAfter()));
        return builder.body(answer.body());
    }
}
