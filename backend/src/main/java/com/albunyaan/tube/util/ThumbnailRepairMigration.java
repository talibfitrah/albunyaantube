package com.albunyaan.tube.util;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.service.ChannelOrchestrator;
import com.albunyaan.tube.service.YouTubeOEmbedClient;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * One-shot, re-runnable migration that repairs broken channel-avatar and
 * playlist-thumbnail URLs by re-extracting fresh metadata via NewPipe.
 *
 * <p>Why it exists: thumbnail URLs are captured once at import time and served
 * forever. Two classes of stored URL are unrenderable —
 * <ul>
 *   <li>fabricated channel-avatar stubs ({@code yt3.ggpht.com/ytc/{channelId}})
 *       left by a legacy seeder (HTTP 400), and</li>
 *   <li>expired playlist {@code /pl_c/…/studio_square_thumbnail.jpg} URLs
 *       (HTTP 404).</li>
 * </ul>
 * The {@code ApprovalService} read path no longer backfills metadata, so this is
 * the explicit "refresh metadata" action that was deferred there.
 *
 * <p>Idempotent: only records whose stored thumbnail is detectably broken
 * (see {@link ThumbnailUrls}) are re-extracted, so re-running it is a no-op once
 * the catalog is clean. A Firestore CAS lock in
 * {@code system_settings/migration_thumbnail_repair} prevents concurrent runs
 * (which would needlessly hammer YouTube), and a stale lock older than
 * {@link #STALE_LOCK_MS} is reclaimed.
 */
@Component
public class ThumbnailRepairMigration {

    private static final Logger logger = LoggerFactory.getLogger(ThumbnailRepairMigration.class);
    private static final String LOCK_DOC = "migration_thumbnail_repair";

    /**
     * A lock held longer than this is treated as a crashed run and reclaimed.
     * A real run is far shorter — only detectably-broken items are re-extracted,
     * each bounded by the oEmbed/NewPipe timeouts — so this window is not reached
     * in practice. If a pathologically large broken-catalog ever exceeded it, a
     * second run could reclaim and run concurrently; that is harmless here because
     * the repair is idempotent (same inputs → same writes) and the CAS release
     * stops either run from clearing the other's lock.
     */
    private static final long STALE_LOCK_MS = 30 * 60 * 1000L;

    public record RunSummary(int channelsScanned, int channelsRepaired,
                             int playlistsScanned, int playlistsRepaired,
                             int failures, List<String> failedChannelIds,
                             List<String> failedPlaylistIds,
                             String startedAt, String completedAt) {}

    private final Firestore firestore;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final ChannelOrchestrator orchestrator;
    private final YouTubeOEmbedClient oEmbedClient;
    private final FirestoreTimeoutProperties timeoutProperties;

    public ThumbnailRepairMigration(Firestore firestore,
                                    ChannelRepository channelRepository,
                                    PlaylistRepository playlistRepository,
                                    ChannelOrchestrator orchestrator,
                                    YouTubeOEmbedClient oEmbedClient,
                                    FirestoreTimeoutProperties timeoutProperties) {
        this.firestore = firestore;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.orchestrator = orchestrator;
        this.oEmbedClient = oEmbedClient;
        this.timeoutProperties = timeoutProperties;
    }

    public RunSummary run(String actorUid) throws Exception {
        DocumentReference lockRef = firestore.collection("system_settings").document(LOCK_DOC);
        String runToken = java.util.UUID.randomUUID().toString();
        if (!claimLock(lockRef, actorUid, runToken)) {
            throw new IllegalStateException(
                "Thumbnail repair is already running. Wait for completion or clear "
                + "system_settings/" + LOCK_DOC + " if the previous run crashed.");
        }

        String startedAt = Timestamp.now().toString();
        int channelsScanned = 0;
        int channelsRepaired = 0;
        int playlistsScanned = 0;
        int playlistsRepaired = 0;
        List<String> failedChannelIds = new ArrayList<>();
        List<String> failedPlaylistIds = new ArrayList<>();

        try {
            // Approved-only: matches the playlist scan scope and avoids spending
            // YouTube round-trips on rejected/pending content users never see.
            List<Channel> channels = channelRepository.findByStatus("APPROVED");
            channelsScanned = channels.size();
            for (Channel channel : channels) {
                String youtubeId = channel.getYoutubeId();
                if (youtubeId == null || youtubeId.isBlank()) {
                    continue;
                }
                if (!ThumbnailUrls.isBrokenChannelAvatar(channel.getThumbnailUrl(), youtubeId)) {
                    continue;
                }
                if (repairChannel(channel, youtubeId)) {
                    channelsRepaired++;
                } else {
                    failedChannelIds.add(youtubeId);
                }
            }

            List<Playlist> playlists = playlistRepository.findAllByOrderByItemCountDesc();
            playlistsScanned = playlists.size();
            for (Playlist playlist : playlists) {
                String youtubeId = playlist.getYoutubeId();
                if (youtubeId == null || youtubeId.isBlank()) {
                    continue;
                }
                if (!ThumbnailUrls.isBrokenPlaylistThumbnail(playlist.getThumbnailUrl())) {
                    continue;
                }
                if (repairPlaylist(playlist, youtubeId)) {
                    playlistsRepaired++;
                } else {
                    failedPlaylistIds.add(youtubeId);
                }
            }
        } finally {
            releaseLock(lockRef, runToken);
        }

        int failures = failedChannelIds.size() + failedPlaylistIds.size();
        String completedAt = Timestamp.now().toString();
        logger.info("Thumbnail repair complete: channels {}/{}, playlists {}/{}, failures={} (failedChannels={}, failedPlaylists={})",
                channelsRepaired, channelsScanned, playlistsRepaired, playlistsScanned, failures,
                failedChannelIds, failedPlaylistIds);
        return new RunSummary(channelsScanned, channelsRepaired,
                playlistsScanned, playlistsRepaired, failures,
                failedChannelIds, failedPlaylistIds, startedAt, completedAt);
    }

    private boolean repairChannel(Channel channel, String youtubeId) {
        try {
            ChannelInfo info = orchestrator.validateAndFetchChannel(youtubeId);
            if (info == null) {
                logger.warn("Thumbnail repair: channel {} could not be re-extracted", youtubeId);
                return false;
            }
            String avatar = ThumbnailUrls.bestAvatarUrl(info.getAvatars());
            if (avatar == null || ThumbnailUrls.isBrokenChannelAvatar(avatar, youtubeId)) {
                logger.warn("Thumbnail repair: channel {} re-extraction yielded no usable avatar", youtubeId);
                return false;
            }
            // Only refresh description / subscribers when the stored description
            // is itself a synthetic seeder stub ("YouTube channel: <name>") — never
            // clobber a real or admin-curated description. A targeted field update
            // (not a full-document set) avoids clobbering fields an admin may edit
            // concurrently during the run.
            String description = null;
            Long subscribers = null;
            if (isSyntheticStub(channel.getDescription())) {
                if (info.getDescription() != null && !info.getDescription().isBlank()) {
                    description = info.getDescription();
                }
                if (info.getSubscriberCount() >= 0) {
                    subscribers = info.getSubscriberCount();
                }
            }
            channelRepository.updateMetadata(channel.getId(), avatar, description, subscribers);
            logger.info("Thumbnail repair: channel {} avatar -> {}", youtubeId, avatar);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Thumbnail repair: channel {} failed: {}", youtubeId, e.getMessage());
            return false;
        }
    }

    private boolean repairPlaylist(Playlist playlist, String youtubeId) {
        try {
            // oEmbed gives the first video's stable /vi/ thumbnail directly (already
            // validated as usable by the client), without relying on NewPipe
            // playlist-item parsing (broken on v0.25.2).
            String thumbnail = oEmbedClient.playlistThumbnailUrl(youtubeId).orElse(null);
            if (thumbnail == null) {
                logger.warn("Thumbnail repair: playlist {} oEmbed yielded no usable thumbnail", youtubeId);
                return false;
            }
            playlistRepository.updateThumbnailUrl(playlist.getId(), thumbnail);
            logger.info("Thumbnail repair: playlist {} thumbnail -> {}", youtubeId, thumbnail);
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Thumbnail repair: playlist {} failed: {}", youtubeId, e.getMessage());
            return false;
        }
    }

    /**
     * A description is a synthetic seeder stub when it is blank or matches the
     * "YouTube channel: &lt;name&gt;" placeholder the seeder wrote. Only such
     * descriptions are safe to overwrite during a thumbnail repair.
     */
    private static boolean isSyntheticStub(String description) {
        return description == null || description.isBlank()
                || description.startsWith("YouTube channel: ");
    }

    private boolean claimLock(DocumentReference lockRef, String actorUid, String runToken) throws Exception {
        return firestore.runTransaction(tx -> {
            DocumentSnapshot snap = tx.get(lockRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (snap.exists() && Boolean.TRUE.equals(snap.getBoolean("running"))) {
                Timestamp claimedAt = snap.getTimestamp("startedAt");
                long ageMs = claimedAt == null
                        ? Long.MAX_VALUE
                        : Timestamp.now().toDate().getTime() - claimedAt.toDate().getTime();
                if (ageMs < STALE_LOCK_MS) {
                    return false;
                }
                logger.warn("Reclaiming stale thumbnail-repair lock (held {} ms by claimedByUid={}).",
                        ageMs, snap.getString("claimedByUid"));
            }
            tx.set(lockRef, Map.of(
                    "running", true,
                    "startedAt", Timestamp.now(),
                    "claimedBy", InetAddress.getLocalHost().getHostName(),
                    "claimedByUid", actorUid == null ? "unknown" : actorUid,
                    "runToken", runToken
            ), SetOptions.merge());
            return true;
        }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    private void releaseLock(DocumentReference lockRef, String runToken) {
        // CAS release: only clear the lock if THIS run still owns it. Prevents a
        // run whose lock was reclaimed as stale (after >30 min) from later clearing
        // a lock a newer run now holds, which would let a third run start
        // concurrently and hammer YouTube.
        try {
            firestore.runTransaction(tx -> {
                DocumentSnapshot snap = tx.get(lockRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (snap.exists() && runToken.equals(snap.getString("runToken"))) {
                    tx.update(lockRef, "running", false, "completedAt", Timestamp.now());
                } else {
                    logger.warn("Thumbnail-repair lock not released: no longer owned by this run.");
                }
                return null;
            }).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            logger.warn("Failed to release thumbnail-repair lock (will be reclaimed as stale): {}",
                    e.getMessage());
        }
    }
}
