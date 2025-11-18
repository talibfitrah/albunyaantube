package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.DownloadCompletedEventDto;
import com.albunyaan.tube.dto.DownloadFailedEventDto;
import com.albunyaan.tube.dto.DownloadManifestDto;
import com.albunyaan.tube.dto.DownloadPolicyDto;
import com.albunyaan.tube.dto.DownloadStartedEventDto;
import com.albunyaan.tube.dto.DownloadTokenDto;
import com.albunyaan.tube.dto.ErrorResponse;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.DownloadService;
import jakarta.validation.Valid;
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

        DownloadTokenDto token = downloadService.generateDownloadToken(videoId, user != null ? user.getUid() : "anonymous", eulaAcceptedValue);
        return ResponseEntity.ok(token);
    }

    @GetMapping("/manifest/{videoId}")
    public ResponseEntity<DownloadManifestDto> getManifest(
            @PathVariable String videoId,
            @RequestParam String token,
            @RequestParam(defaultValue = "false") boolean supportsMerging)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        DownloadManifestDto manifest = downloadService.getDownloadManifest(videoId, token, supportsMerging);
        return ResponseEntity.ok(manifest);
    }

    /**
     * P4-T1: Track download started event with typed DTO
     */
    @PostMapping("/analytics/download-started")
    public ResponseEntity<Void> trackDownloadStarted(@Valid @RequestBody DownloadStartedEventDto event,
            @AuthenticationPrincipal FirebaseUserDetails user) {
        downloadService.trackDownloadStarted(
            event.getVideoId(),
            user != null ? user.getUid() : "anonymous",
            event.getQuality(),
            event.getDeviceType()
        );
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * P4-T1: Track download completed event with typed DTO
     */
    @PostMapping("/analytics/download-completed")
    public ResponseEntity<Void> trackDownloadCompleted(@Valid @RequestBody DownloadCompletedEventDto event,
            @AuthenticationPrincipal FirebaseUserDetails user) {
        downloadService.trackDownloadCompleted(
            event.getVideoId(),
            user != null ? user.getUid() : "anonymous",
            event.getQuality(),
            event.getFileSize(),
            event.getDeviceType()
        );
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * P4-T1: Track download failed event with typed DTO
     */
    @PostMapping("/analytics/download-failed")
    public ResponseEntity<Void> trackDownloadFailed(@Valid @RequestBody DownloadFailedEventDto event,
            @AuthenticationPrincipal FirebaseUserDetails user) {
        downloadService.trackDownloadFailed(
            event.getVideoId(),
            user != null ? user.getUid() : "anonymous",
            event.getErrorReason(),
            event.getDeviceType()
        );
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}

