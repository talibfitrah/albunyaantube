package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;

import java.util.List;

/**
 * What the public by-id routes ({@code GET /api/v1/videos|channels|playlists/{id}}) serialize --
 * never the Firestore entity, which carries staff uids and the reviewer's email
 * ({@code submittedBy}, {@code approvedBy}, {@code approvalMetadata}) plus registry bookkeeping.
 *
 * <p>Fields: what a client reads (iOS {@code OfflineGateClient}: video {@code youtubeId} +
 * {@code offlineAllowed}; iOS {@code PublicHeaders}: channel {@code youtubeId}/{@code name}/
 * {@code thumbnailUrl}, playlist {@code title}/{@code thumbnailUrl}/{@code itemCount}; Android only
 * HEADs these routes) plus the content metadata {@link ContentItemDto} already serves publicly.
 * Admin routes keep serializing the entities.
 *
 * <p>Exclusions (owner bug 2026-11-03): the apps list a channel's and a playlist's items on the device from
 * YouTube, so an admin exclusion can hide an item there only if the app reads it here and drops it:
 * {@code excludedVideoIds} (a channel's Videos, Shorts and Live lists) and a channel's {@code excludedPlaylistIds}.
 * Never null.
 */
public final class PublicDetails {

    private PublicDetails() {}

    /** {@code offlineAllowed} is the EFFECTIVE permission ({@link Video#allowsOffline()}), never the raw null. */
    public record VideoDetail(String youtubeId, String title, String description, String thumbnailUrl,
                              Integer durationSeconds, Long viewCount, String channelTitle,
                              boolean offlineAllowed) {}

    public record ChannelDetail(String youtubeId, String name, String description, String thumbnailUrl,
                                Long subscribers, Integer videoCount, List<String> excludedVideoIds,
                                List<String> excludedPlaylistIds) {}

    public record PlaylistDetail(String youtubeId, String title, String description, String thumbnailUrl,
                                 Integer itemCount, List<String> excludedVideoIds) {}

    public static VideoDetail of(Video v) {
        Integer duration = ContentItemDto.knownSeconds(v.getDurationSeconds());
        return new VideoDetail(v.getYoutubeId(), v.getTitle(), v.getDescription(), v.getThumbnailUrl(),
                duration, v.getViewCount(), v.getChannelTitle(), v.allowsOffline());
    }

    public static ChannelDetail of(Channel c) {
        Channel.ExcludedItems excluded = c.getExcludedItems(); // never null: its setter and the constructor see to it
        return new ChannelDetail(c.getYoutubeId(), c.getName(), c.getDescription(), c.getThumbnailUrl(),
                c.getSubscribers(), c.getVideoCount(), excluded.videoIds(), ids(excluded.getPlaylists()));
    }

    public static PlaylistDetail of(Playlist p) {
        return new PlaylistDetail(p.getYoutubeId(), p.getTitle(), p.getDescription(), p.getThumbnailUrl(),
                p.getItemCount(), ids(p.getExcludedVideoIds()));
    }

    /** A stored id list as served: never null, no null or blank entry (an app decodes it as strings). */
    private static List<String> ids(List<String> stored) {
        return stored == null ? List.of() : stored.stream().filter(id -> id != null && !id.isBlank()).toList();
    }
}
