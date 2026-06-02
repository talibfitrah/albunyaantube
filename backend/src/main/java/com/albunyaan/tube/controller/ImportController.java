package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.importflow.ImportDisposition;
import com.albunyaan.tube.dto.importflow.ImportItem;
import com.albunyaan.tube.dto.importflow.ImportResolveRequest;
import com.albunyaan.tube.dto.importflow.ImportResolveResponse;
import com.albunyaan.tube.dto.importflow.ImportResult;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.UserImportSubmissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Endpoint for the Android app to batch-resolve YouTube items during the "import to Me list" flow.
 *
 * <p>For each item the caller provides, returns a disposition so the client knows whether to
 * immediately add it to the Me list as APPROVED (with curated metadata), show it as AWAITING
 * (PENDING), skip it (REJECTED / ERROR), etc. Items absent from the registry are submitted for
 * approval via {@link UserImportSubmissionService}.
 */
@RestController
@RequestMapping("/api/account/import")
public class ImportController {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;
    private final UserImportSubmissionService submissions;

    public ImportController(
            ChannelRepository channels,
            PlaylistRepository playlists,
            VideoRepository videos,
            UserImportSubmissionService submissions) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
        this.submissions = submissions;
    }

    /**
     * Resolve a batch of YouTube items and return their dispositions.
     *
     * <p>APPROVED items include a {@link ContentItemDto} with curated metadata; all other
     * dispositions return {@code null} content. A per-item exception (Firestore timeout, submit
     * failure, etc.) yields {@link ImportDisposition#ERROR} for that item without affecting others.
     */
    @PostMapping("/resolve")
    public ResponseEntity<ImportResolveResponse> resolve(
            @Valid @RequestBody ImportResolveRequest req,
            @AuthenticationPrincipal FirebaseUserDetails principal) {

        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        List<ImportResult> out = new ArrayList<>();
        for (ImportItem item : req.items()) {
            try {
                Optional<?> existing = lookup(item);
                if (existing.isPresent()) {
                    Object model = existing.get();
                    String status = statusOf(model);
                    ImportDisposition disposition = statusToDisposition(status);
                    ContentItemDto dto = disposition == ImportDisposition.APPROVED
                            ? toDto(model, item.type())
                            : null;
                    out.add(new ImportResult(item.youtubeId(), item.type(), disposition, dto));
                } else {
                    ImportDisposition disposition = submissions.submit(item, principal.getUid());
                    out.add(new ImportResult(item.youtubeId(), item.type(), disposition, null));
                }
            } catch (Exception e) {
                out.add(new ImportResult(item.youtubeId(), item.type(), ImportDisposition.ERROR, null));
            }
        }

        return ResponseEntity.ok(new ImportResolveResponse(out));
    }

    // ── private helpers ─────────────────────────────────────────────────

    /**
     * Maps a Firestore status string to an {@link ImportDisposition}.
     * Mirrors {@code UserImportSubmissionService.statusToDisposition} exactly — unknown/null
     * statuses (ARCHIVED, UNAVAILABLE, etc.) map to PENDING so the caller sees "not approved yet"
     * rather than a silent error.
     */
    private static ImportDisposition statusToDisposition(String status) {
        if (status == null) return ImportDisposition.PENDING;
        return switch (status.toUpperCase()) {
            case "APPROVED" -> ImportDisposition.APPROVED;
            case "REJECTED" -> ImportDisposition.REJECTED;
            default         -> ImportDisposition.PENDING;
        };
    }

    private Optional<?> lookup(ImportItem item)
            throws ExecutionException, InterruptedException, TimeoutException {
        return switch (item.type()) {
            case CHANNEL  -> channels.findByYoutubeId(item.youtubeId());
            case PLAYLIST -> playlists.findByYoutubeId(item.youtubeId());
            case VIDEO    -> videos.findByYoutubeId(item.youtubeId());
            default -> throw new IllegalArgumentException(
                    "Unsupported content type for import: " + item.type());
        };
    }

    private static String statusOf(Object model) {
        if (model instanceof Channel  c) return c.getStatus();
        if (model instanceof Playlist p) return p.getStatus();
        if (model instanceof Video    v) return v.getStatus();
        throw new IllegalArgumentException("Unexpected model type: " + model.getClass());
    }

    /**
     * Maps a registry model to a {@link ContentItemDto} using the same static builder pattern as
     * {@code PublicContentService.toDto()} — kept in sync with that implementation.
     *
     * <p>Only called when {@code disposition == APPROVED}, so null category / metadata are
     * acceptable (same trade-off as the public content controller).
     */
    private static ContentItemDto toDto(Object model, YouTubeContentType type) {
        return switch (type) {
            case CHANNEL -> {
                Channel ch = (Channel) model;
                yield ContentItemDto.channel(
                        ch.getYoutubeId(),
                        ch.getName(),
                        ch.getCategory() != null ? ch.getCategory().getName() : null,
                        ch.getSubscribers(),
                        ch.getDescription(),
                        ch.getThumbnailUrl(),
                        ch.getVideoCount(),
                        ch.getKeywords());
            }
            case PLAYLIST -> {
                Playlist pl = (Playlist) model;
                yield ContentItemDto.playlist(
                        pl.getYoutubeId(),
                        pl.getTitle(),
                        pl.getCategory() != null ? pl.getCategory().getName() : null,
                        pl.getItemCount(),
                        pl.getDescription(),
                        pl.getThumbnailUrl(),
                        pl.getKeywords());
            }
            case VIDEO -> {
                Video v = (Video) model;
                int durationSeconds = v.getDurationSeconds() != null ? v.getDurationSeconds() : 0;
                LocalDateTime uploadedAt = v.getUploadedAt() != null
                        ? v.getUploadedAt().toDate().toInstant()
                                .atZone(ZoneId.systemDefault()).toLocalDateTime()
                        : LocalDateTime.now();
                int uploadedDaysAgo = (int) ChronoUnit.DAYS.between(uploadedAt, LocalDateTime.now());
                yield ContentItemDto.video(
                        v.getYoutubeId(),
                        v.getTitle(),
                        null,                // category name — same trade-off as PublicContentService
                        durationSeconds,
                        uploadedDaysAgo,
                        v.getDescription(),
                        v.getThumbnailUrl(),
                        v.getViewCount(),
                        v.getChannelTitle(),
                        v.getKeywords());
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported content type for DTO mapping: " + type);
        };
    }
}
