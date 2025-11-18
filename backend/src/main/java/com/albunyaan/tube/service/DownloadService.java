package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.DownloadManifestDto;
import com.albunyaan.tube.dto.DownloadPolicyDto;
import com.albunyaan.tube.dto.DownloadTokenDto;
import com.albunyaan.tube.exception.InvalidTokenException;
import com.albunyaan.tube.exception.PolicyViolationException;
import com.albunyaan.tube.exception.ResourceNotFoundException;
import com.albunyaan.tube.exception.StreamExtractionException;
import com.albunyaan.tube.model.DownloadEvent;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.firestore.Firestore;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
public class DownloadService {
    private static final Logger logger = LoggerFactory.getLogger(DownloadService.class);

    // Quality threshold for requiring FFmpeg merging (above this = video-only + audio)
    private static final int QUALITY_MERGE_THRESHOLD = 480;

    private final VideoRepository videoRepository;
    private final DownloadTokenService tokenService;
    private final YouTubeGateway youtubeGateway;
    private final Firestore firestore;

    public DownloadService(VideoRepository videoRepository, DownloadTokenService tokenService,
                           YouTubeGateway youtubeGateway, Firestore firestore) {
        this.videoRepository = videoRepository;
        this.tokenService = tokenService;
        this.youtubeGateway = youtubeGateway;
        this.firestore = firestore;
    }

    public DownloadPolicyDto checkDownloadPolicy(String videoId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
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
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        DownloadPolicyDto policy = checkDownloadPolicy(videoId);
        if (!policy.isAllowed()) {
            throw new PolicyViolationException("Download not allowed: " + policy.getReason());
        }
        if (policy.isRequiresEula() && !eulaAccepted) {
            throw new PolicyViolationException("EULA acceptance required");
        }
        String token = tokenService.generateToken(videoId, userId);
        long expiresAtMillis = tokenService.getExpirationTime(videoId, userId);
        return new DownloadTokenDto(token, expiresAtMillis, videoId);
    }

    public DownloadManifestDto getDownloadManifest(String videoId, String token, boolean supportsMerging)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (!tokenService.validateToken(token, videoId)) {
            throw new InvalidTokenException("Invalid or expired download token");
        }
        Video video = videoRepository.findByYoutubeId(videoId).orElse(null);
        if (video == null) {
            throw new ResourceNotFoundException("Video", videoId);
        }

        // Fetch stream info from NewPipe
        StreamInfo streamInfo;
        try {
            streamInfo = youtubeGateway.fetchStreamInfo(videoId);
        } catch (IOException | ExtractionException e) {
            logger.error("Failed to fetch stream info for video {}: {}", videoId, e.getMessage());
            throw new StreamExtractionException("Failed to fetch video streams: " + e.getMessage(), e);
        }

        DownloadManifestDto manifest = new DownloadManifestDto(
                videoId, video.getTitle(), tokenService.getExpirationTimeFromToken(token));

        // Get best audio stream for pairing with video-only streams
        AudioStream bestAudio = selectBestAudioStream(streamInfo.getAudioStreams());
        String bestAudioUrl = bestAudio != null ? bestAudio.getContent() : null;

        // Process video streams
        List<VideoStream> videoStreams = streamInfo.getVideoStreams();
        List<VideoStream> videoOnlyStreams = streamInfo.getVideoOnlyStreams();

        // Add combined video+audio streams (usually ≤480p) as progressive
        for (VideoStream vs : videoStreams) {
            if (vs.getContent() == null || vs.getContent().isEmpty()) continue;

            int height = extractHeight(vs.getResolution());
            String qualityLabel = vs.getResolution();
            String mimeType = vs.getFormat() != null ? vs.getFormat().getMimeType() : "video/mp4";

            manifest.getVideoStreams().add(DownloadManifestDto.StreamOption.progressive(
                    "v" + qualityLabel,
                    qualityLabel,
                    mimeType,
                    vs.getContent(),
                    estimateFileSize(vs.getBitrate(), streamInfo.getDuration()),
                    vs.getBitrate() / 1000 // Convert to kbps
            ));
        }

        // Add video-only streams (>480p) that require merging with audio
        // Only include if client supports merging and we have MP4-compatible audio
        if (supportsMerging && bestAudioUrl != null && isMp4Compatible(bestAudio.getFormat())) {
            for (VideoStream vs : videoOnlyStreams) {
                if (vs.getContent() == null || vs.getContent().isEmpty()) continue;

                // Only include MP4-compatible video for FFmpeg merging
                if (!isMp4Compatible(vs.getFormat())) continue;

                int height = extractHeight(vs.getResolution());
                // Only include high-quality video-only streams
                if (height <= QUALITY_MERGE_THRESHOLD) continue;

                String qualityLabel = vs.getResolution();
                String mimeType = vs.getFormat() != null ? vs.getFormat().getMimeType() : "video/mp4";

                long videoSize = estimateFileSize(vs.getBitrate(), streamInfo.getDuration());
                long audioSize = estimateFileSize(bestAudio.getBitrate(), streamInfo.getDuration());

                manifest.getVideoStreams().add(DownloadManifestDto.StreamOption.split(
                        "v" + qualityLabel,
                        qualityLabel,
                        mimeType,
                        vs.getContent(),
                        bestAudioUrl,
                        videoSize + audioSize,
                        vs.getBitrate() / 1000
                ));
            }
        }

        // Add audio-only streams
        for (AudioStream as : streamInfo.getAudioStreams()) {
            if (as.getContent() == null || as.getContent().isEmpty()) continue;

            int bitrate = as.getBitrate() / 1000; // Convert to kbps
            String qualityLabel = bitrate + "kbps";
            String mimeType = as.getFormat() != null ? as.getFormat().getMimeType() : "audio/mp4";

            manifest.getAudioStreams().add(DownloadManifestDto.StreamOption.progressive(
                    "a" + bitrate,
                    qualityLabel,
                    mimeType,
                    as.getContent(),
                    estimateFileSize(as.getBitrate(), streamInfo.getDuration()),
                    bitrate
            ));
        }

        // Sort and cap video streams to curated quality tiers
        manifest.setVideoStreams(curateVideoStreams(manifest.getVideoStreams()));

        // Sort and cap audio streams to best 3 by bitrate
        manifest.setAudioStreams(curateAudioStreams(manifest.getAudioStreams()));

        logger.info("Generated download manifest for video {}: {} video streams, {} audio streams (supportsMerging={})",
                videoId, manifest.getVideoStreams().size(), manifest.getAudioStreams().size(), supportsMerging);

        return manifest;
    }

    /**
     * Curate video streams to a manageable set of quality tiers.
     * Keeps best stream per tier: 144p, 360p, 480p, 720p, 1080p.
     */
    private List<DownloadManifestDto.StreamOption> curateVideoStreams(List<DownloadManifestDto.StreamOption> streams) {
        if (streams.isEmpty()) return streams;

        // Define quality tiers
        int[] tiers = {144, 360, 480, 720, 1080};
        java.util.Map<Integer, DownloadManifestDto.StreamOption> bestPerTier = new java.util.LinkedHashMap<>();

        for (DownloadManifestDto.StreamOption stream : streams) {
            int height = extractHeight(stream.getQualityLabel());
            int tier = findClosestTier(height, tiers);

            DownloadManifestDto.StreamOption existing = bestPerTier.get(tier);
            if (existing == null || stream.getBitrate() > existing.getBitrate()) {
                bestPerTier.put(tier, stream);
            }
        }

        // Return sorted by quality (highest first)
        List<DownloadManifestDto.StreamOption> result = new java.util.ArrayList<>(bestPerTier.values());
        result.sort(Comparator.comparingInt(s -> -s.getBitrate()));
        return result;
    }

    /**
     * Curate audio streams to top 3 by bitrate.
     */
    private List<DownloadManifestDto.StreamOption> curateAudioStreams(List<DownloadManifestDto.StreamOption> streams) {
        if (streams.isEmpty()) return streams;

        return streams.stream()
                .sorted(Comparator.comparingInt(s -> -s.getBitrate()))
                .limit(3)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Find the closest quality tier for a given height.
     */
    private int findClosestTier(int height, int[] tiers) {
        int closest = tiers[0];
        int minDiff = Math.abs(height - closest);

        for (int tier : tiers) {
            int diff = Math.abs(height - tier);
            if (diff < minDiff) {
                minDiff = diff;
                closest = tier;
            }
        }
        return closest;
    }

    /**
     * Select the best audio stream for pairing with video-only streams.
     * Prefers highest bitrate with mp4/m4a format for FFmpeg compatibility.
     */
    private AudioStream selectBestAudioStream(List<AudioStream> audioStreams) {
        // First, try to find MP4-compatible audio (needed for FFmpeg merging)
        AudioStream mp4Audio = audioStreams.stream()
                .filter(as -> as.getContent() != null && !as.getContent().isEmpty())
                .filter(as -> isMp4Compatible(as.getFormat()))
                .max(Comparator.comparingInt(AudioStream::getBitrate))
                .orElse(null);

        if (mp4Audio != null) {
            return mp4Audio;
        }

        // Fallback to any audio stream
        return audioStreams.stream()
                .filter(as -> as.getContent() != null && !as.getContent().isEmpty())
                .max(Comparator.comparingInt(AudioStream::getBitrate))
                .orElse(null);
    }

    /**
     * Check if a format is MP4/M4A compatible for FFmpeg merging.
     */
    private boolean isMp4Compatible(org.schabi.newpipe.extractor.MediaFormat format) {
        if (format == null) return false;
        String mimeType = format.getMimeType();
        return mimeType != null && (
                mimeType.contains("mp4") ||
                mimeType.contains("m4a") ||
                mimeType.equals("audio/aac") ||
                mimeType.equals("video/mp4")
        );
    }

    /**
     * Extract height from resolution string (e.g., "720p" -> 720)
     */
    private int extractHeight(String resolution) {
        if (resolution == null) return 0;
        try {
            return Integer.parseInt(resolution.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Estimate file size in bytes from bitrate and duration.
     * Returns a fallback estimate if bitrate is unknown.
     */
    private long estimateFileSize(int bitrateBps, long durationSeconds) {
        // Guard against null/zero bitrate
        if (bitrateBps <= 0 || durationSeconds <= 0) {
            // Return a fallback estimate: assume 1 Mbps average for unknown streams
            return durationSeconds > 0 ? durationSeconds * 125_000 : 0; // 1 Mbps = 125,000 bytes/sec
        }
        // bitrate is in bps, duration in seconds
        // fileSize = (bitrate * duration) / 8
        return (long) bitrateBps * durationSeconds / 8;
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

