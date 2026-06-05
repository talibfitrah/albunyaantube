package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * F3 — server-authoritative content-registry status for a (type, youtubeId).
 *
 * <p>The public sync {@code PUT /api/account/{subscriptions,playlists,favorites}/{id}}
 * endpoint must NOT trust a client-supplied {@code approvalStatus}: a user could
 * otherwise flip their own AWAITING import to APPROVED (defeating the moderation
 * gate) or resurrect an admin-rejected row. {@code SyncService} derives the stored
 * {@code approvalStatus} from this gate instead.
 *
 * <p>The lookup is keyed by youtubeId, which (for both organic catalog adds and
 * YouTube imports) equals the sync document id — the public API exposes
 * {@code youtubeId} as the item id (see {@code ContentItemMapper}).
 */
@Component
public class ContentApprovalGate {

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;

    public ContentApprovalGate(ChannelRepository channels,
                               PlaylistRepository playlists,
                               VideoRepository videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    /**
     * Registry status string ("APPROVED" | "PENDING" | "REJECTED") for the given
     * content, or {@code null} when the id is blank or not present in the registry.
     */
    public String statusOf(YouTubeContentType type, String youtubeId)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (youtubeId == null || youtubeId.isBlank()) {
            return null;
        }
        return switch (type) {
            case CHANNEL  -> channels.findByYoutubeId(youtubeId).map(Channel::getStatus).orElse(null);
            case PLAYLIST -> playlists.findByYoutubeId(youtubeId).map(Playlist::getStatus).orElse(null);
            case VIDEO    -> videos.findByYoutubeId(youtubeId).map(Video::getStatus).orElse(null);
            default       -> null;
        };
    }

    /**
     * Finding 3: full per-user-derivation inputs for a (type, youtubeId) — status plus
     * visibility and personalGrants — so {@code SyncService} can keep a PERSONAL-approved
     * item APPROVED only for its grantees. {@code null} when the id is blank or absent.
     */
    public ApprovalInfo infoOf(YouTubeContentType type, String youtubeId)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (youtubeId == null || youtubeId.isBlank()) {
            return null;
        }
        return switch (type) {
            case CHANNEL  -> channels.findByYoutubeId(youtubeId)
                    .map(c -> new ApprovalInfo(c.getStatus(), c.getVisibility(), c.getPersonalGrants())).orElse(null);
            case PLAYLIST -> playlists.findByYoutubeId(youtubeId)
                    .map(p -> new ApprovalInfo(p.getStatus(), p.getVisibility(), p.getPersonalGrants())).orElse(null);
            case VIDEO    -> videos.findByYoutubeId(youtubeId)
                    .map(v -> new ApprovalInfo(v.getStatus(), v.getVisibility(), v.getPersonalGrants())).orElse(null);
            default       -> null;
        };
    }

    /**
     * Registry status + visibility + personal grants for one item. {@code visibility} is
     * null on legacy/public docs (treated as PUBLIC); {@code personalGrants} is null/empty
     * unless the item was personally approved.
     */
    public record ApprovalInfo(String status, String visibility, List<String> personalGrants) {}
}
