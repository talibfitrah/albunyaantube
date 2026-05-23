package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
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

    /** Snapshot of an existing registry entry sufficient for duplicate reporting. */
    public record ExistingMatch(String registryId, String status) {}

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
         */
        public void markAsExisting(YouTubeContentType type, String youtubeId, String registryId, String status) {
            cache.put(cacheKey(type, youtubeId), Optional.of(new ExistingMatch(registryId, status)));
        }

        private Optional<ExistingMatch> queryOnce(YouTubeContentType type, String youtubeId) {
            try {
                return switch (type) {
                    case CHANNEL  -> channels.findByYoutubeId(youtubeId)
                            .map(c -> new ExistingMatch(c.getId(), c.getStatus()));
                    case PLAYLIST -> playlists.findByYoutubeId(youtubeId)
                            .map(p -> new ExistingMatch(p.getId(), p.getStatus()));
                    case VIDEO    -> videos.findByYoutubeId(youtubeId)
                            .map(v -> new ExistingMatch(v.getId(), v.getStatus()));
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
