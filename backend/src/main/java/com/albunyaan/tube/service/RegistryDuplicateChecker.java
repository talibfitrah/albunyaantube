package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Looks up whether a given (type, youtubeId) already exists in the registry.
 * <p>
 * Provides a per-batch {@link Batch} object that memoizes lookups so the same youtubeId
 * isn't queried twice in one preview call. Each call to {@link #newBatch()} produces an
 * independent cache — separate preview calls never share state.
 */
@Service
public class RegistryDuplicateChecker {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public RegistryDuplicateChecker(ChannelRepository channels,
                                    PlaylistRepository playlists,
                                    VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    /** Create a new per-batch memoizing lookup context. Thread-safe; can be shared across worker threads. */
    public Batch newBatch() {
        return new Batch();
    }

    /**
     * Cubic b26-r1: a video's docs hold an admin's re-add into doc `docId` when another of them is a take-down's ARCHIVED
     * (a pre-B11 duplicate): the re-add would serve the id beside it. The doc itself archived is no hold: a take-down
     * record's re-add lifts it (B14), and save() keeps an in-place one. Bulk (here) and single add alike.
     */
    public static boolean heldBeside(java.util.List<Video> docs, String docId) {
        return docs.stream().anyMatch(d -> !docId.equals(d.getId()) && ValidationStatus.holds(d.getValidationStatus()));
    }

    /** Snapshot of an existing registry entry sufficient for duplicate reporting. */
    /** `held`: B26, a REJECTED channel's or playlist's youtubeId has an ARCHIVED doc, a take-down a re-add must not undo. */
    public record ExistingMatch(String registryId, String status, boolean held) {
        public ExistingMatch(String registryId, String status) {
            this(registryId, status, false);
        }
    }

    /**
     * Per-batch memoizing view. Thread-safe via ConcurrentHashMap so multiple worker threads can share one batch.
     */
    public final class Batch {

        private final Map<String, Optional<ExistingMatch>> cache = new ConcurrentHashMap<>();

        private Batch() {}

        private String cacheKey(YouTubeContentType type, String youtubeId) {
            return type.name() + ':' + youtubeId;
        }

        /**
         * Return the existing registry entry for {@code (type, youtubeId)}, or empty if none.
         * Results are memoized for the lifetime of this batch — the repository is called at
         * most once per unique (type, youtubeId) pair.
         */
        public Optional<ExistingMatch> findExisting(YouTubeContentType type, String youtubeId) {
            return cache.computeIfAbsent(cacheKey(type, youtubeId), k -> queryOnce(type, youtubeId));
        }

        /**
         * Record a newly-written registry entry so subsequent {@link #findExisting} calls
         * in the same batch return it as a duplicate. Closes the intra-batch race where two
         * rows with the same {@code (type, youtubeId)} both passed the initial lookup
         * (memoized empty Optional) and both wrote a Firestore doc.
         *
         * <p>Uses {@code compute} (not {@code put}) so a parallel
         * {@link #findExisting}'s in-flight {@code computeIfAbsent} for the same key
         * cannot land after this call and clobber the marker with
         * {@code Optional.empty()}. Today's only caller is the
         * single-threaded BulkSubmissionService.submit row loop where the
         * race window is zero, but the {@code compute} form makes the API
         * contract match the thread-safety claim in the class javadoc so a
         * future parallel-submit refactor won't silently regress dedupe.
         */
        public void markAsExisting(YouTubeContentType type, String youtubeId, String registryId, String status) {
            Optional<ExistingMatch> marker = Optional.of(new ExistingMatch(registryId, status));
            cache.compute(cacheKey(type, youtubeId), (k, prev) -> marker);
        }

        private Optional<ExistingMatch> queryOnce(YouTubeContentType type, String youtubeId) {
            try {
                return switch (type) {
                    // Cubic users4: a REJECTED match is held when a report archived any doc of the id (in place, then
                    // rejected): an admin's re-add writes a second doc, which would serve the take-down again
                    case CHANNEL  -> {
                        Optional<Channel> c = channels.findByYoutubeId(youtubeId);
                        boolean held = c.isPresent() && "REJECTED".equals(c.get().getStatus()) && channels.findAllByYoutubeId(youtubeId)
                                .stream().anyMatch(d -> ValidationStatus.holds(d.getValidationStatus()));
                        yield c.map(x -> new ExistingMatch(x.getId(), x.getStatus(), held));
                    }
                    case PLAYLIST -> {
                        Optional<Playlist> p = playlists.findByYoutubeId(youtubeId);
                        boolean held = p.isPresent() && "REJECTED".equals(p.get().getStatus()) && playlists.findAllByYoutubeId(youtubeId)
                                .stream().anyMatch(d -> ValidationStatus.holds(d.getValidationStatus()));
                        yield p.map(x -> new ExistingMatch(x.getId(), x.getStatus(), held));
                    }
                    // Cubic b26-r1: a re-add goes into the REJECTED match itself, so only another doc's take-down holds it
                    case VIDEO    -> {
                        Optional<Video> v = videos.findByYoutubeId(youtubeId);
                        boolean held = v.isPresent() && "REJECTED".equals(v.get().getStatus())
                                && heldBeside(videos.findAllByYoutubeId(youtubeId), v.get().getId());
                        yield v.map(x -> new ExistingMatch(x.getId(), x.getStatus(), held));
                    }
                    case ALL      -> throw new IllegalStateException("ALL is not a content type for duplicate checks: " + type);
                };
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException(
                        "Registry lookup failed for " + type + ":" + youtubeId, e);
            }
        }
    }
}
