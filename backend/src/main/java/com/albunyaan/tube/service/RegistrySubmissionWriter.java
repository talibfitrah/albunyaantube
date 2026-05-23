package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.registry.PreviewMetadata;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.model.VideoType;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Single source of truth for "write a fresh registry doc from
 * NewPipe-extracted preview metadata + admin-resolved categories + actor role".
 *
 * <p>Consumed by the bulk-submit pipeline. For the legacy single-add
 * endpoints in {@link com.albunyaan.tube.controller.RegistryController}, the
 * controller-level handlers continue to manage their own entity assembly because
 * the request body carries fields beyond what {@link PreviewMetadata} models
 * (e.g. {@code description}, {@code keywords}, {@code videoCount}). Status
 * normalisation in this writer mirrors {@code normalizeStatusAndApprovedBy}'s
 * post-validation contract: callers pre-decide the final status string
 * ({@code "PENDING"} or {@code "APPROVED"}) and pass {@code isAdmin} so the
 * writer can defensively re-force non-admins to {@code PENDING}.
 *
 * <p>HTTP-layer concerns (submitterNote sanitization, mass-assignment guarding,
 * dedupe lookups, sortOrderService side effects, audit logging) intentionally
 * stay in the calling layer — this writer only touches Firestore.
 */
@Service
public class RegistrySubmissionWriter {

    /**
     * YouTube CDN allowlist for thumbnail URLs.
     * Client-supplied metadata.thumbnailUrl is not re-fetched by the backend, so a
     * crafted submit body could point at any host (XSS via SVG, pixel-tracking,
     * cookie-stealing endpoints). Restrict to known-good YouTube/Google image CDNs.
     */
    private static final java.util.Set<String> ALLOWED_THUMBNAIL_HOSTS = java.util.Set.of(
            "i.ytimg.com", "img.youtube.com", "i9.ytimg.com",
            "yt3.googleusercontent.com", "yt3.ggpht.com",
            "lh3.googleusercontent.com"
    );

    /**
     * Reject thumbnailUrl pointing to non-YouTube CDN hosts.
     * Returns null (no thumbnail) for unsafe URLs; the UI falls back to a placeholder.
     */
    /**
     * Public so single-add controller paths (addChannel/addPlaylist/addVideo)
     * can sanitize body-supplied thumbnailUrl before save. Without this hook,
     * single-add was the only registry write path that bypassed the
     * thumbnail-host allowlist — moderators/admins could land arbitrary
     * (e.g. attacker-hosted tracking-pixel or SVG-XSS) thumbnail URLs into
     * the public feed via direct POST.
     */
    public static String sanitizeThumbnailUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            java.net.URI uri = new java.net.URI(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || scheme == null) return null;
            if (!"https".equalsIgnoreCase(scheme)) return null;
            // Reject userinfo (`https://attacker@i.ytimg.com/...`). The browser
            // ignores userinfo on `<img src>`, so SSRF is benign, but the
            // userinfo persists into Firestore and is rendered verbatim to
            // admins in the registry UI — not a value we want stored.
            if (uri.getUserInfo() != null) return null;
            return ALLOWED_THUMBNAIL_HOSTS.contains(host.toLowerCase(java.util.Locale.ROOT)) ? raw : null;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public RegistrySubmissionWriter(ChannelRepository channels,
                                    PlaylistRepository playlists,
                                    VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    /**
     * Persist a fresh Channel doc. Returns the new Firestore document ID.
     *
     * @param meta            NewPipe preview snapshot (youtubeId required; title/thumbnail/subscribers used)
     * @param categoryIds     admin-resolved category IDs to associate
     * @param requestedStatus desired status string ("PENDING" or "APPROVED"); ignored when {@code isAdmin=false}
     * @param submittedByUid  Firebase UID of the actor submitting the row
     * @param isAdmin         whether the actor has admin role (non-admins always written as PENDING)
     */
    public String writeChannel(PreviewMetadata meta,
                               List<String> categoryIds,
                               String requestedStatus,
                               String submittedByUid,
                               boolean isAdmin)
            throws ExecutionException, InterruptedException, TimeoutException {
        Channel c = new Channel(meta.youtubeId());
        c.setName(meta.title());
        c.setThumbnailUrl(sanitizeThumbnailUrl(meta.thumbnailUrl()));
        if (meta.subscribers() != null) {
            c.setSubscribers(meta.subscribers());
        }
        c.setCategoryIds(categoryIds);
        applyStatus(c::setStatus, c::setApprovedBy, requestedStatus, submittedByUid, isAdmin);
        c.setSubmittedBy(submittedByUid);
        Timestamp now = Timestamp.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        Channel saved = channels.save(c);
        return saved.getId();
    }

    /**
     * Persist a fresh Playlist doc. Returns the new Firestore document ID.
     *
     * <p>Note: {@code meta.channelId()} and {@code meta.channelName()} are
     * carried in the metadata for downstream display but are not stored on the
     * {@link Playlist} entity (no setters exist) — this matches the existing
     * Firestore schema.
     */
    public String writePlaylist(PreviewMetadata meta,
                                List<String> categoryIds,
                                String requestedStatus,
                                String submittedByUid,
                                boolean isAdmin)
            throws ExecutionException, InterruptedException, TimeoutException {
        Playlist p = new Playlist(meta.youtubeId());
        p.setTitle(meta.title());
        p.setThumbnailUrl(sanitizeThumbnailUrl(meta.thumbnailUrl()));
        if (meta.itemCount() != null) {
            p.setItemCount(meta.itemCount().intValue());
        }
        p.setCategoryIds(categoryIds);
        applyStatus(p::setStatus, p::setApprovedBy, requestedStatus, submittedByUid, isAdmin);
        p.setSubmittedBy(submittedByUid);
        Timestamp now = Timestamp.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        Playlist saved = playlists.save(p);
        return saved.getId();
    }

    /**
     * Persist a fresh Video doc. Returns the new Firestore document ID.
     *
     * @param videoType resolved video type; {@code null} is normalised to
     *                  {@link VideoType#STANDARD} so legacy callers needn't
     *                  branch on liveness explicitly.
     */
    public String writeVideo(PreviewMetadata meta,
                             VideoType videoType,
                             List<String> categoryIds,
                             String requestedStatus,
                             String submittedByUid,
                             boolean isAdmin)
            throws ExecutionException, InterruptedException, TimeoutException {
        Video v = new Video(meta.youtubeId());
        v.setTitle(meta.title());
        v.setThumbnailUrl(sanitizeThumbnailUrl(meta.thumbnailUrl()));
        if (meta.durationSeconds() != null) {
            v.setDurationSeconds(meta.durationSeconds().intValue());
        }
        if (meta.viewCount() != null) {
            v.setViewCount(meta.viewCount());
        }
        if (meta.channelId() != null) {
            v.setChannelId(meta.channelId());
        }
        if (meta.channelName() != null) {
            v.setChannelTitle(meta.channelName());
        }
        v.setVideoType(videoType != null ? videoType : VideoType.STANDARD);
        v.setCategoryIds(categoryIds);
        applyStatus(v::setStatus, v::setApprovedBy, requestedStatus, submittedByUid, isAdmin);
        v.setSubmittedBy(submittedByUid);
        Timestamp now = Timestamp.now();
        v.setCreatedAt(now);
        v.setUpdatedAt(now);
        Video saved = videos.save(v);
        return saved.getId();
    }

    /**
     * Status + approvedBy normalisation. Non-admins are always pinned to PENDING
     * with null approvedBy — defensive even if the caller mis-passes APPROVED.
     * For admins, the requested status is honoured (blank/null defaults to
     * PENDING — the bulk path's contract; the single-add path's "admin default
     * is APPROVED" lives in the controller-level
     * {@code normalizeStatusAndApprovedBy} so it isn't re-implemented here).
     */
    private static void applyStatus(Consumer<String> setStatus,
                                    Consumer<String> setApprovedBy,
                                    String requestedStatus,
                                    String actorUid,
                                    boolean isAdmin) {
        if (!isAdmin) {
            setStatus.accept("PENDING");
            setApprovedBy.accept(null);
            return;
        }
        String s = (requestedStatus == null || requestedStatus.isBlank())
                ? "PENDING"
                : requestedStatus.toUpperCase(java.util.Locale.ROOT);
        setStatus.accept(s);
        setApprovedBy.accept("APPROVED".equals(s) ? actorUid : null);
    }
}
