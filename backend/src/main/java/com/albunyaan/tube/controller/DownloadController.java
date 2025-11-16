package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.DownloadManifestDto;
import com.albunyaan.tube.dto.DownloadPolicyDto;
import com.albunyaan.tube.dto.DownloadTokenDto;
import com.albunyaan.tube.dto.ErrorResponse;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.DownloadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/downloads")
public class DownloadController {
    private final DownloadService downloadService;

    public DownloadController(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    @GetMapping("/policy/{videoId}")
    public ResponseEntity<DownloadPolicyDto> checkPolicy(@PathVariable String videoId)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        DownloadPolicyDto policy = downloadService.checkDownloadPolicy(videoId);
        return ResponseEntity.ok(policy);
    }

    @PostMapping("/token/{videoId}")
    public ResponseEntity<?> generateToken(@PathVariable String videoId, @RequestBody Map<String, Boolean> request,
            @AuthenticationPrincipal FirebaseUserDetails user) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Validate request body
        if (request == null || !request.containsKey("eulaAccepted")) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", "Missing required field: eulaAccepted"));
        }

        Boolean eulaAcceptedValue = request.get("eulaAccepted");
        if (eulaAcceptedValue == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", "Field eulaAccepted cannot be null"));
        }

        if (!eulaAcceptedValue) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("EULA_NOT_ACCEPTED", "EULA must be accepted to download content"));
        }

        try {
            DownloadTokenDto token = downloadService.generateDownloadToken(videoId, user != null ? user.getUid() : "anonymous", eulaAcceptedValue);
            return ResponseEntity.ok(token);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("POLICY_VIOLATION", e.getMessage()));
        }
    }

    @GetMapping("/manifest/{videoId}")
    public ResponseEntity<?> getManifest(@PathVariable String videoId, @RequestParam String token)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        try {
            DownloadManifestDto manifest = downloadService.getDownloadManifest(videoId, token);
            return ResponseEntity.ok(manifest);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("INVALID_TOKEN", e.getMessage()));
        }
    }

    @PostMapping("/analytics/download-started")
    public ResponseEntity<Void> trackDownloadStarted(@RequestBody Map<String, String> request,
            @AuthenticationPrincipal FirebaseUserDetails user) {
        String videoId = request.get("videoId");
        String quality = request.get("quality");
        String deviceType = request.getOrDefault("deviceType", "unknown");
        downloadService.trackDownloadStarted(videoId, user != null ? user.getUid() : "anonymous", quality, deviceType);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/analytics/download-completed")
    public ResponseEntity<Void> trackDownloadCompleted(@RequestBody Map<String, Object> request,
            @AuthenticationPrincipal FirebaseUserDetails user) {
        String videoId = (String) request.get("videoId");
        String quality = (String) request.get("quality");
        Long fileSize = request.get("fileSize") != null ? ((Number) request.get("fileSize")).longValue() : null;
        String deviceType = (String) request.getOrDefault("deviceType", "unknown");
        downloadService.trackDownloadCompleted(videoId, user != null ? user.getUid() : "anonymous", quality, fileSize, deviceType);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/analytics/download-failed")
    public ResponseEntity<Void> trackDownloadFailed(@RequestBody Map<String, String> request,
            @AuthenticationPrincipal FirebaseUserDetails user) {
        String videoId = request.get("videoId");
        String errorReason = request.get("errorReason");
        String deviceType = request.getOrDefault("deviceType", "unknown");
        downloadService.trackDownloadFailed(videoId, user != null ? user.getUid() : "anonymous", errorReason, deviceType);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}

