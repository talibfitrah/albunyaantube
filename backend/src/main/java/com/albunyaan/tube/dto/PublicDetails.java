package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;

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
 */
public final class PublicDetails {

    private PublicDetails() {}

    /** {@code offlineAllowed} is the EFFECTIVE permission ({@link Video#allowsOffline()}), never the raw null. */
    public record VideoDetail(String youtubeId, String title, String description, String thumbnailUrl,
                              Integer durationSeconds, Long viewCount, String channelTitle,
                              boolean offlineAllowed) {}

    public record ChannelDetail(String youtubeId, String name, String description, String thumbnailUrl,
                                Long subscribers, Integer videoCount) {}

    public record PlaylistDetail(String youtubeId, String title, String description, String thumbnailUrl,
                                 Integer itemCount) {}

    public static VideoDetail of(Video v) {
        return new VideoDetail(v.getYoutubeId(), v.getTitle(), v.getDescription(), v.getThumbnailUrl(),
                v.getDurationSeconds(), v.getViewCount(), v.getChannelTitle(), v.allowsOffline());
    }

    public static ChannelDetail of(Channel c) {
        return new ChannelDetail(c.getYoutubeId(), c.getName(), c.getDescription(), c.getThumbnailUrl(),
                c.getSubscribers(), c.getVideoCount());
    }

    public static PlaylistDetail of(Playlist p) {
        return new PlaylistDetail(p.getYoutubeId(), p.getTitle(), p.getDescription(), p.getThumbnailUrl(),
                p.getItemCount());
    }
}
