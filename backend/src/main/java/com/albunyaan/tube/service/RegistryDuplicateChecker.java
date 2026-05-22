package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * BULK-01 (T4) — looks up whether a given (type, youtubeId) already exists in the registry.
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

    /** Create a new per-batch memoizing lookup context. Not thread-safe; one per preview call. */
    public Batch newBatch() {
        return new Batch();
    }

    /** Snapshot of an existing registry entry sufficient for duplicate reporting. */
    public record ExistingMatch(String registryId, String status) {}

    /**
     * Per-batch memoizing view over the three repositories.
     * <p>
     * Not thread-safe — each preview call must obtain its own {@link Batch} via
     * {@link RegistryDuplicateChecker#newBatch()}.
     */
    public final class Batch {

        private final Map<String, Optional<ExistingMatch>> cache = new HashMap<>();

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

        private Optional<ExistingMatch> queryOnce(YouTubeContentType type, String youtubeId) {
            try {
                return switch (type) {
                    case CHANNEL  -> channels.findByYoutubeId(youtubeId)
                            .map(c -> new ExistingMatch(c.getId(), c.getStatus()));
                    case PLAYLIST -> playlists.findByYoutubeId(youtubeId)
                            .map(p -> new ExistingMatch(p.getId(), p.getStatus()));
                    case VIDEO    -> videos.findByYoutubeId(youtubeId)
                            .map(v -> new ExistingMatch(v.getId(), v.getStatus()));
                    case ALL      -> Optional.empty(); // ALL is a search filter, not a content type
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
