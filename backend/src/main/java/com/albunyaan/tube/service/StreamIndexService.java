package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.StreamItemDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class StreamIndexService {

    private static final Logger log = LoggerFactory.getLogger(StreamIndexService.class);
    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");
    private static final Set<String> VALID_STREAM_TYPES = Set.of("VIDEO", "SHORT", "LIVESTREAM", "LIVE", "PAST_LIVE");

    private final SearchableStreamRepository streamRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final SearchTokenizer tokenizer;

    public StreamIndexService(SearchableStreamRepository streamRepository,
                               ChannelRepository channelRepository,
                               PlaylistRepository playlistRepository,
                               SearchTokenizer tokenizer) {
        this.streamRepository = streamRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.tokenizer = tokenizer;
    }

    /**
     * Index streams from an approved channel (Videos, Shorts, or Live tab).
     * Channel name is fetched server-side from Firestore — never trusted from client.
     */
    public void indexFromChannel(String channelYoutubeId, List<StreamItemDto> items) {
        try {
            Optional<Channel> opt = channelRepository.findByYoutubeId(channelYoutubeId);
            if (opt.isEmpty() || !"APPROVED".equals(opt.get().getStatus())) return;

            Channel channel = opt.get();
            Channel.ExcludedItems excluded = channel.getExcludedItems();
            Set<String> excludedVideos = new HashSet<>(excluded.getVideos());
            Set<String> excludedShorts = new HashSet<>(excluded.getShorts());
            Set<String> excludedLive = new HashSet<>(excluded.getLiveStreams());
            String sourceKey = "channel:" + channelYoutubeId;

            for (StreamItemDto item : items) {
                if (isChannelExcluded(item.getId(), item.getStreamType(), excludedVideos, excludedShorts, excludedLive)) continue;
                upsert(item, channelYoutubeId, channel.getName(), sourceKey);
            }
        } catch (Exception e) {
            log.warn("indexFromChannel failed for {}: {}", channelYoutubeId, e.getMessage());
        }
    }

    /**
     * Index streams from an approved playlist.
     */
    public void indexFromPlaylist(String playlistYoutubeId, List<StreamItemDto> items) {
        try {
            Optional<Playlist> opt = playlistRepository.findByYoutubeId(playlistYoutubeId);
            if (opt.isEmpty() || !"APPROVED".equals(opt.get().getStatus())) return;

            Playlist playlist = opt.get();
            List<String> rawExcluded = playlist.getExcludedVideoIds();
            Set<String> excluded = rawExcluded != null ? new HashSet<>(rawExcluded) : Collections.emptySet();
            String sourceKey = "playlist:" + playlistYoutubeId;

            for (StreamItemDto item : items) {
                if (excluded.contains(item.getId())) continue;
                String rawChannelId = item.getChannelId();
                String channelId;
                if (rawChannelId != null && CHANNEL_ID_PATTERN.matcher(rawChannelId).matches()) {
                    channelId = rawChannelId;
                } else {
                    channelId = extractChannelId(item.getUploaderUrl());
                }
                if (channelId == null) {
                    log.warn("Could not resolve channelId for stream {}, skipping", item.getId());
                    continue;
                }
                String uploaderName = item.getUploaderName();
                if (uploaderName != null && uploaderName.length() > 200) uploaderName = uploaderName.substring(0, 200);
                upsert(item, channelId, uploaderName, sourceKey);
            }
        } catch (Exception e) {
            log.warn("indexFromPlaylist failed for {}: {}", playlistYoutubeId, e.getMessage());
        }
    }

    /**
     * Remove all streams contributed by a source (called when channel/playlist is rejected).
     */
    public void removeSource(String sourceType, String sourceYoutubeId) {
        String sourceKey = sourceType.toLowerCase() + ":" + sourceYoutubeId;
        try {
            List<SearchableStream> batch;
            int removed = 0;
            do {
                batch = streamRepository.findBySourceKey(sourceKey, 500);
                for (SearchableStream stream : batch) {
                    streamRepository.removeSource(stream.getStreamId(), sourceKey);
                    removed++;
                }
            } while (batch.size() == 500);
            log.info("Removed search index source {} ({} streams)", sourceKey, removed);
        } catch (Exception e) {
            log.warn("removeSource failed for {}: {}", sourceKey, e.getMessage());
        }
    }

    private void upsert(StreamItemDto item, String channelId, String channelName, String sourceKey) {
        try {
            if (item.getId() == null || item.getName() == null) {
                log.warn("Skipping stream with null id or name (sourceKey={})", sourceKey);
                return;
            }
            String titleNorm = tokenizer.normalizeArabic(item.getName().toLowerCase(Locale.ROOT));
            List<String> tokens = tokenizer.tokenize(item.getName(), channelName);

            SearchableStream stream = new SearchableStream();
            stream.setStreamId(item.getId());
            stream.setTitle(item.getName());
            stream.setTitleNorm(titleNorm);
            stream.setThumbnailUrl(item.getThumbnailUrl());
            stream.setChannelId(channelId);
            stream.setChannelName(channelName);
            String streamType = item.getStreamType();
            stream.setStreamType(streamType != null && VALID_STREAM_TYPES.contains(streamType.toUpperCase()) ? streamType : null);
            stream.setDurationSeconds(item.getDuration());
            stream.setViewCount(item.getViewCount());
            stream.setSearchTokens(tokens);
            stream.setVisible(true);

            streamRepository.upsert(stream, sourceKey);
        } catch (Exception e) {
            log.warn("Failed to upsert stream {}: {}", item.getId(), e.getMessage());
        }
    }

    private boolean isChannelExcluded(String streamId, String streamType,
                                       Set<String> videos, Set<String> shorts, Set<String> live) {
        if (streamType == null) return videos.contains(streamId);
        return switch (streamType.toUpperCase()) {
            case "SHORT" -> shorts.contains(streamId);
            case "LIVE", "PAST_LIVE", "LIVESTREAM" -> live.contains(streamId);
            default -> videos.contains(streamId);
        };
    }

    private String extractChannelId(String uploaderUrl) {
        if (uploaderUrl == null) return null;
        int idx = uploaderUrl.indexOf("/UC");
        if (idx < 0) return null;
        String rest = uploaderUrl.substring(idx + 1);
        int slash = rest.indexOf('/');
        String candidate = slash > 0 ? rest.substring(0, slash) : rest;
        return CHANNEL_ID_PATTERN.matcher(candidate).matches() ? candidate : null;
    }
}
