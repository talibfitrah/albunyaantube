package com.albunyaan.tube.service;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.dto.SearchHit;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.YouTubeSearchResponse;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B6: Server-side YouTube search for moderators.
 *
 * <p>Proxies NewPipe-Extractor search (via {@link NewPipeSearchClient} →
 * {@link YouTubeGateway}) and annotates each result with whether it already
 * exists in the registry, along with its current approval status.</p>
 */
@Service
public class YouTubeSearchService {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeSearchService.class);

    private final NewPipeSearchClient newPipeClient;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final YouTubeGateway gateway;

    public YouTubeSearchService(
            NewPipeSearchClient newPipeClient,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            YouTubeGateway gateway) {
        this.newPipeClient = newPipeClient;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.gateway = gateway;
    }

    /**
     * Search YouTube for content of the given type, annotating results with registry state.
     *
     * @param q         search query
     * @param type      content type to search
     * @param pageToken opaque page token from the previous response (null = first page)
     * @return annotated search results
     * @throws YouTubeSearchException on extraction or network failure
     */
    @Cacheable(
            value = CacheConfig.CACHE_NEWPIPE_SEARCH_RESULTS,
            key = "#q + ':' + #type.name() + ':' + (#pageToken ?: '')",
            unless = "#result == null"
    )
    public YouTubeSearchResponse search(String q, YouTubeContentType type, String pageToken) {
        try {
            NewPipeSearchClient.RawPage raw = newPipeClient.search(q, type, pageToken);

            List<SearchHit> hits = new ArrayList<>();
            List<String> ids = new ArrayList<>();

            for (InfoItem item : raw.items()) {
                SearchHit hit = toHit(item, type);
                if (hit == null) continue;
                hits.add(hit);
                ids.add(hit.youtubeId());
            }

            if (!hits.isEmpty()) {
                hits = annotateKnown(hits, ids, type);
            }

            return new YouTubeSearchResponse(hits, raw.nextPageToken());

        } catch (Exception e) {
            logger.warn("YouTubeSearchService.search failed [q={}, type={}, page={}]: {}",
                    q, type, pageToken, e.getMessage());
            throw new YouTubeSearchException("Search failed: " + e.getMessage(), e);
        }
    }

    // ==================== Private helpers ====================

    /**
     * Convert a raw NewPipe {@link InfoItem} to a {@link SearchHit}.
     * Returns null for items that don't match the expected type (skip them).
     */
    private SearchHit toHit(InfoItem item, YouTubeContentType type) {
        try {
            return switch (type) {
                case CHANNEL  -> channelHit(item);
                case PLAYLIST -> playlistHit(item);
                case VIDEO    -> videoHit(item);
            };
        } catch (Exception e) {
            logger.debug("Skipping InfoItem due to extraction error: {}", e.getMessage());
            return null;
        }
    }

    private SearchHit channelHit(InfoItem item) {
        if (!(item instanceof ChannelInfoItem ch)) return null;

        String url = ch.getUrl();
        String id = gateway.extractChannelId(url);
        if (id == null) {
            logger.debug("Could not extract channel ID from URL: {}", url);
            return null;
        }

        long subs = ch.getSubscriberCount();
        String secondary = (subs >= 0) ? formatSubscribers(subs) : null;

        return new SearchHit(id, ch.getName(), url, thumbnailUrl(ch.getThumbnails()),
                secondary, false, null);
    }

    private SearchHit playlistHit(InfoItem item) {
        if (!(item instanceof PlaylistInfoItem pl)) return null;

        String url = pl.getUrl();
        String id = gateway.extractPlaylistId(url);
        if (id == null) {
            logger.debug("Could not extract playlist ID from URL: {}", url);
            return null;
        }

        return new SearchHit(id, pl.getName(), url, thumbnailUrl(pl.getThumbnails()),
                pl.getUploaderName(), false, null);
    }

    private SearchHit videoHit(InfoItem item) {
        if (!(item instanceof StreamInfoItem sv)) return null;

        String url = sv.getUrl();
        String id = gateway.extractVideoId(url);
        if (id == null) {
            logger.debug("Could not extract video ID from URL: {}", url);
            return null;
        }

        return new SearchHit(id, sv.getName(), url, thumbnailUrl(sv.getThumbnails()),
                sv.getUploaderName(), false, null);
    }

    /**
     * Pick the first (lowest-index) thumbnail URL; NewPipe usually orders them
     * smallest-first so the caller can pick their preferred resolution.
     */
    private String thumbnailUrl(List<Image> thumbnails) {
        if (thumbnails == null || thumbnails.isEmpty()) return null;
        return thumbnails.get(0).getUrl();
    }

    private String formatSubscribers(long count) {
        if (count >= 1_000_000) return (count / 1_000_000) + "M";
        if (count >= 1_000) return (count / 1_000) + "K";
        return String.valueOf(count);
    }

    /**
     * Batch-look up the given IDs in the appropriate repository and return a
     * new list of hits with {@code alreadyKnown} and {@code knownStatus} set.
     */
    private List<SearchHit> annotateKnown(List<SearchHit> hits, List<String> ids,
                                           YouTubeContentType type) {
        try {
            Map<String, String> statusByYoutubeId = fetchStatusMap(ids, type);
            List<SearchHit> annotated = new ArrayList<>(hits.size());
            for (SearchHit hit : hits) {
                String knownStatus = statusByYoutubeId.get(hit.youtubeId());
                if (knownStatus != null) {
                    annotated.add(new SearchHit(hit.youtubeId(), hit.name(), hit.url(),
                            hit.thumbnailUrl(), hit.secondary(), true, knownStatus));
                } else {
                    annotated.add(hit);
                }
            }
            return annotated;
        } catch (Exception e) {
            // Non-fatal: return un-annotated hits rather than failing the whole search.
            logger.warn("Registry annotation failed for type={}: {}", type, e.getMessage());
            return hits;
        }
    }

    /**
     * Fetch a youtubeId → status map from the appropriate repository.
     */
    private Map<String, String> fetchStatusMap(List<String> ids, YouTubeContentType type)
            throws Exception {
        return switch (type) {
            case CHANNEL -> {
                Map<String, Channel> found = channelRepository.findByYoutubeIds(ids);
                yield found.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().getStatus() != null ? e.getValue().getStatus() : "UNKNOWN"
                        ));
            }
            case PLAYLIST -> {
                Map<String, Playlist> found = playlistRepository.findByYoutubeIds(ids);
                yield found.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().getStatus() != null ? e.getValue().getStatus() : "UNKNOWN"
                        ));
            }
            case VIDEO -> {
                Map<String, Video> found = videoRepository.findByYoutubeIds(ids);
                yield found.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey,
                                e -> e.getValue().getStatus() != null ? e.getValue().getStatus() : "UNKNOWN"
                        ));
            }
        };
    }
}
