package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.ContentItemMapper;
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
import com.albunyaan.tube.service.ImportRateLimitedException;
import com.albunyaan.tube.service.SubmissionRateLimiter;
import com.albunyaan.tube.service.UserImportSubmissionService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;
    private final UserImportSubmissionService submissions;
    private final SubmissionRateLimiter rateLimiter;

    public ImportController(
            ChannelRepository channels,
            PlaylistRepository playlists,
            VideoRepository videos,
            UserImportSubmissionService submissions,
            SubmissionRateLimiter rateLimiter) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
        this.submissions = submissions;
        this.rateLimiter = rateLimiter;
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

        // Per-user daily item budget check — whole request rejected if over budget.
        // tryAcquireImport atomically consumes count slots; returns null when allowed,
        // or retryAfterSec when the budget would be exceeded (no slots consumed).
        int itemCount = req.items().size();
        Long retryAfterSec = rateLimiter.tryAcquireImport(principal.getUid(), itemCount);
        if (retryAfterSec != null) {
            // remaining=0: the request was rejected because there was insufficient budget;
            // the exact unconsumed amount is not tracked here to keep this read-free.
            throw new ImportRateLimitedException(retryAfterSec);
        }

        List<ImportResult> out = new ArrayList<>();
        for (ImportItem item : req.items()) {
            try {
                Optional<?> existing = lookup(item);
                if (existing.isPresent()) {
                    Object model = existing.get();
                    String status = statusOf(model);
                    ImportDisposition disposition = UserImportSubmissionService.statusToDisposition(status);
                    ContentItemDto dto = disposition == ImportDisposition.APPROVED
                            ? toDto(model, item.type())
                            : null;
                    out.add(new ImportResult(item.youtubeId(), item.type(), disposition, dto));
                } else {
                    ImportDisposition disposition = submissions.submit(item, principal.getUid());
                    out.add(new ImportResult(item.youtubeId(), item.type(), disposition, null));
                }
            } catch (Exception e) {
                log.warn("Import resolve failed for youtubeId={} type={}", item.youtubeId(), item.type(), e);
                out.add(new ImportResult(item.youtubeId(), item.type(), ImportDisposition.ERROR, null));
            }
        }

        return ResponseEntity.ok(new ImportResolveResponse(out));
    }

    // ── private helpers ─────────────────────────────────────────────────

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
     * Maps a registry model to a {@link ContentItemDto} via {@link ContentItemMapper}.
     * Only called when {@code disposition == APPROVED}.
     */
    private static ContentItemDto toDto(Object model, YouTubeContentType type) {
        return switch (type) {
            case CHANNEL  -> ContentItemMapper.fromChannel((Channel) model);
            case PLAYLIST -> ContentItemMapper.fromPlaylist((Playlist) model);
            case VIDEO    -> ContentItemMapper.fromVideo((Video) model);
            default -> throw new IllegalArgumentException(
                    "Unsupported content type for DTO mapping: " + type);
        };
    }
}
