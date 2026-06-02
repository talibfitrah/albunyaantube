package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.importflow.ImportDisposition;
import com.albunyaan.tube.dto.importflow.ImportItem;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * User-import submission path.
 *
 * <p>Idempotent: if the youtubeId already exists in any status, returns that status without
 * saving a duplicate. If absent, creates a PENDING doc with empty categoryIds and
 * source="USER_IMPORT" so admin can assign categories at approval time.
 *
 * <p>Thumbnail sanitization is delegated to
 * {@link RegistrySubmissionWriter#sanitizeThumbnailUrl} to keep the CDN allowlist
 * in one place. The writer is NOT injected — the method is static and called directly,
 * matching how {@code RegistryController} uses it on the single-add path.
 */
@Service
public class UserImportSubmissionService {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public UserImportSubmissionService(
            ChannelRepository channels,
            PlaylistRepository playlists,
            VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    /**
     * Submit a single imported YouTube item on behalf of {@code uid}.
     *
     * @return the disposition reflecting the item's status in the registry
     */
    public ImportDisposition submit(ImportItem item, String uid)
            throws ExecutionException, InterruptedException, TimeoutException {

        return switch (item.type()) {
            case CHANNEL  -> submitChannel(item, uid);
            case PLAYLIST -> submitPlaylist(item, uid);
            case VIDEO    -> submitVideo(item, uid);
            default -> throw new IllegalArgumentException(
                    "Unsupported content type for import: " + item.type());
        };
    }

    // ── per-type helpers ──────────────────────────────────────────────────

    private ImportDisposition submitChannel(ImportItem item, String uid)
            throws ExecutionException, InterruptedException, TimeoutException {

        Optional<Channel> existing = channels.findByYoutubeId(item.youtubeId());
        if (existing.isPresent()) {
            return statusToDisposition(existing.get().getStatus());
        }

        Channel ch = new Channel();
        ch.setYoutubeId(item.youtubeId());
        ch.setName(item.title());
        ch.setThumbnailUrl(RegistrySubmissionWriter.sanitizeThumbnailUrl(item.thumbnailUrl()));
        ch.setStatus("PENDING");
        ch.setSource("USER_IMPORT");
        ch.setSubmittedBy(uid);
        ch.setCategoryIds(List.of());
        Timestamp now = Timestamp.now();
        ch.setCreatedAt(now);
        ch.setUpdatedAt(now);

        try {
            channels.save(ch);
        } catch (Exception e) {
            // Race: another request saved the same youtubeId between our read and write.
            Optional<Channel> raceRead = channels.findByYoutubeId(item.youtubeId());
            if (raceRead.isPresent()) {
                return statusToDisposition(raceRead.get().getStatus());
            }
            throw e;
        }
        return ImportDisposition.PENDING;
    }

    private ImportDisposition submitPlaylist(ImportItem item, String uid)
            throws ExecutionException, InterruptedException, TimeoutException {

        Optional<Playlist> existing = playlists.findByYoutubeId(item.youtubeId());
        if (existing.isPresent()) {
            return statusToDisposition(existing.get().getStatus());
        }

        Playlist pl = new Playlist();
        pl.setYoutubeId(item.youtubeId());
        pl.setTitle(item.title());
        pl.setThumbnailUrl(RegistrySubmissionWriter.sanitizeThumbnailUrl(item.thumbnailUrl()));
        pl.setStatus("PENDING");
        pl.setSource("USER_IMPORT");
        pl.setSubmittedBy(uid);
        pl.setCategoryIds(List.of());
        Timestamp now = Timestamp.now();
        pl.setCreatedAt(now);
        pl.setUpdatedAt(now);

        try {
            playlists.save(pl);
        } catch (Exception e) {
            Optional<Playlist> raceRead = playlists.findByYoutubeId(item.youtubeId());
            if (raceRead.isPresent()) {
                return statusToDisposition(raceRead.get().getStatus());
            }
            throw e;
        }
        return ImportDisposition.PENDING;
    }

    private ImportDisposition submitVideo(ImportItem item, String uid)
            throws ExecutionException, InterruptedException, TimeoutException {

        Optional<Video> existing = videos.findByYoutubeId(item.youtubeId());
        if (existing.isPresent()) {
            return statusToDisposition(existing.get().getStatus());
        }

        Video v = new Video();
        v.setYoutubeId(item.youtubeId());
        v.setTitle(item.title());
        v.setThumbnailUrl(RegistrySubmissionWriter.sanitizeThumbnailUrl(item.thumbnailUrl()));
        v.setStatus("PENDING");
        v.setSource("USER_IMPORT");
        v.setSubmittedBy(uid);
        v.setCategoryIds(List.of());
        if (item.channelId() != null) {
            v.setChannelId(item.channelId());
        }
        Timestamp now = Timestamp.now();
        v.setCreatedAt(now);
        v.setUpdatedAt(now);

        try {
            videos.save(v);
        } catch (Exception e) {
            Optional<Video> raceRead = videos.findByYoutubeId(item.youtubeId());
            if (raceRead.isPresent()) {
                return statusToDisposition(raceRead.get().getStatus());
            }
            throw e;
        }
        return ImportDisposition.PENDING;
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Maps the Firestore status string to an {@link ImportDisposition}.
     * Unknown statuses (e.g. ARCHIVED, UNAVAILABLE) map to PENDING so the
     * caller sees "not yet approved" rather than silently failing.
     */
    static ImportDisposition statusToDisposition(String status) {
        if (status == null) return ImportDisposition.PENDING;
        return switch (status.toUpperCase()) {
            case "APPROVED"   -> ImportDisposition.APPROVED;
            case "REJECTED"   -> ImportDisposition.REJECTED;
            default           -> ImportDisposition.PENDING;
        };
    }
}
