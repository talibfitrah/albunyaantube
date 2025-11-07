package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.DownloadManifestDto;
import com.albunyaan.tube.dto.DownloadPolicyDto;
import com.albunyaan.tube.dto.DownloadTokenDto;
import com.albunyaan.tube.model.DownloadEvent;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.firestore.Firestore;
import org.springframework.stereotype.Service;
import java.util.concurrent.ExecutionException;

@Service
public class DownloadService {
    private final VideoRepository videoRepository;
    private final DownloadTokenService tokenService;
    private final Firestore firestore;

    public DownloadService(VideoRepository videoRepository, DownloadTokenService tokenService, Firestore firestore) {
        this.videoRepository = videoRepository;
        this.tokenService = tokenService;
        this.firestore = firestore;
    }

    public DownloadPolicyDto checkDownloadPolicy(String videoId) throws ExecutionException, InterruptedException {
        Video video = videoRepository.findByYoutubeId(videoId).orElse(null);
        if (video == null) {
            return DownloadPolicyDto.denied("Video not found in registry");
        }
        if (!"APPROVED".equals(video.getStatus())) {
            return DownloadPolicyDto.denied("Video not approved for viewing");
        }
        return DownloadPolicyDto.allowedWithEula();
    }

    public DownloadTokenDto generateDownloadToken(String videoId, String userId, boolean eulaAccepted)
            throws ExecutionException, InterruptedException {
        DownloadPolicyDto policy = checkDownloadPolicy(videoId);
        if (!policy.isAllowed()) {
            throw new IllegalStateException("Download not allowed: " + policy.getReason());
        }
        if (policy.isRequiresEula() && !eulaAccepted) {
            throw new IllegalStateException("EULA acceptance required");
        }
        String token = tokenService.generateToken(videoId, userId);
        long expiresAt = tokenService.getExpirationTime(videoId, userId);
        return new DownloadTokenDto(token, expiresAt, videoId);
    }

    public DownloadManifestDto getDownloadManifest(String videoId, String token)
            throws ExecutionException, InterruptedException {
        if (!tokenService.validateToken(token, videoId)) {
            throw new IllegalStateException("Invalid or expired download token");
        }
        Video video = videoRepository.findByYoutubeId(videoId).orElse(null);
        if (video == null) {
            throw new IllegalStateException("Video not found");
        }
        DownloadManifestDto manifest = new DownloadManifestDto(videoId, video.getTitle(), tokenService.getExpirationTime(videoId, ""));
        manifest.getVideoStreams().add(new DownloadManifestDto.StreamOption("720p", "https://example.com/video/720p/" + videoId, "mp4", 50_000_000L, 2500));
        manifest.getVideoStreams().add(new DownloadManifestDto.StreamOption("480p", "https://example.com/video/480p/" + videoId, "mp4", 30_000_000L, 1500));
        manifest.getVideoStreams().add(new DownloadManifestDto.StreamOption("360p", "https://example.com/video/360p/" + videoId, "mp4", 15_000_000L, 800));
        manifest.getAudioStreams().add(new DownloadManifestDto.StreamOption("High", "https://example.com/audio/high/" + videoId, "m4a", 5_000_000L, 192));
        manifest.getAudioStreams().add(new DownloadManifestDto.StreamOption("Medium", "https://example.com/audio/medium/" + videoId, "m4a", 3_000_000L, 128));
        return manifest;
    }

    public void trackDownloadStarted(String videoId, String userId, String quality, String deviceType) {
        DownloadEvent event = new DownloadEvent(videoId, userId, "started");
        event.setQuality(quality);
        event.setDeviceType(deviceType);
        firestore.collection("download_events").add(event);
    }

    public void trackDownloadCompleted(String videoId, String userId, String quality, Long fileSize, String deviceType) {
        DownloadEvent event = new DownloadEvent(videoId, userId, "completed");
        event.setQuality(quality);
        event.setFileSize(fileSize);
        event.setDeviceType(deviceType);
        firestore.collection("download_events").add(event);
    }

    public void trackDownloadFailed(String videoId, String userId, String errorReason, String deviceType) {
        DownloadEvent event = new DownloadEvent(videoId, userId, "failed");
        event.setErrorReason(errorReason);
        event.setDeviceType(deviceType);
        firestore.collection("download_events").add(event);
    }
}

