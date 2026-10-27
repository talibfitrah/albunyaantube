package com.albunyaan.tube.service;

import com.albunyaan.tube.model.ContentReport;
import com.albunyaan.tube.model.ReportReason;
import com.albunyaan.tube.model.ReportStatus;
import com.albunyaan.tube.model.ReportTargetType;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.ContentReportRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.SearchIndex;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ContentReportService {

    private static final Logger log = LoggerFactory.getLogger(ContentReportService.class);
    private static final int RATE_LIMIT_MAX = 5;
    private static final String ANONYMOUS_DEVICE_KEY = "ANONYMOUS_DEVICE";
    private static final java.util.regex.Pattern VIDEO_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{11}");
    /** Video.source of a take-down record (stage 9 B11): Restore deletes such a record, a re-add replaces it. */
    static final String TAKE_DOWN_SOURCE = "REPORT";

    private final ContentReportRepository reportRepository;
    private final Cache<String, AtomicInteger> rateLimitCache;
    private final VideoRepository videoRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final PublicContentCacheService publicContentCacheService;
    private final StreamIndexService streamIndexService;
    private final SearchIndex searchIndex;
    private final Cache<String, Object> workspaceExclusionsCache;
    private final LiveChannelService liveChannelService;

    public ContentReportService(
            ContentReportRepository reportRepository,
            @Qualifier("reportRateLimitCache") Cache<String, AtomicInteger> rateLimitCache,
            VideoRepository videoRepository,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            PublicContentCacheService publicContentCacheService,
            StreamIndexService streamIndexService,
            SearchIndex searchIndex,
            @Qualifier("workspaceExclusionsCache") Cache<String, Object> workspaceExclusionsCache,
            LiveChannelService liveChannelService) {
        this.reportRepository = reportRepository;
        this.rateLimitCache = rateLimitCache;
        this.videoRepository = videoRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.publicContentCacheService = publicContentCacheService;
        this.streamIndexService = streamIndexService;
        this.searchIndex = searchIndex;
        this.workspaceExclusionsCache = workspaceExclusionsCache;
        this.liveChannelService = liveChannelService;
    }

    public ContentReport submitReport(
            ReportTargetType targetType, String targetId,
            List<ReportReason> reasons, String otherDescription, String deviceKey)
            throws ExecutionException, InterruptedException, TimeoutException {
        return submitReport(targetType, targetId, reasons, otherDescription, deviceKey,
                null, null, null);
    }

    public ContentReport submitReport(
            ReportTargetType targetType, String targetId,
            List<ReportReason> reasons, String otherDescription, String deviceKey,
            ReportTargetType parentType, String parentId, String contentSubType)
            throws ExecutionException, InterruptedException, TimeoutException {

        checkRateLimit(deviceKey);

        ContentReport report = new ContentReport();
        report.setTargetType(targetType);
        report.setTargetId(targetId);
        report.setReasons(reasons);
        report.setOtherDescription(otherDescription);
        report.setDeviceId(deviceKey);
        report.setStatus(ReportStatus.PENDING);
        report.setCreatedAt(Timestamp.now());

        // Parent context is only meaningful when (a) targetType is the
        // child shape (VIDEO or PLAYLIST) AND (b) parentType is a
        // container (CHANNEL or PLAYLIST). Drop bogus combinations
        // silently — the report is still useful, just without the
        // resolve→exclude side-effect.
        if (parentType == ReportTargetType.CHANNEL || parentType == ReportTargetType.PLAYLIST) {
            if (parentId != null && !parentId.isBlank()) {
                report.setParentType(parentType);
                report.setParentId(parentId);
            }
        }
        if (contentSubType != null && !contentSubType.isBlank()) {
            // Normalize to upper-case and only accept the documented values.
            String normalized = contentSubType.toUpperCase();
            if ("SHORT".equals(normalized) || "LIVESTREAM".equals(normalized) || "POST".equals(normalized)) {
                report.setContentSubType(normalized);
            }
        }

        ContentReport saved = reportRepository.save(report);

        try {
            reportRepository.writeAdminNotification(saved.getId(), targetType, targetId);
        } catch (Exception e) {
            log.warn("Failed to write admin notification for report {}: {}", saved.getId(), e.getMessage());
        }

        return saved;
    }

    public List<ContentReport> getReports(ReportStatus status, ReportTargetType targetType, int page, int size)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (status != null && targetType != null) {
            return reportRepository.findByStatusAndTargetType(status, targetType, page, size);
        } else if (status != null) {
            return reportRepository.findByStatus(status, page, size);
        } else if (targetType != null) {
            return reportRepository.findAllByTargetType(targetType, page, size);
        }
        return reportRepository.findAll(page, size);
    }

    public ContentReport resolveReport(String reportId, ReportStatus newStatus, String resolvedBy, String note)
            throws ExecutionException, InterruptedException, TimeoutException {
        ContentReport report = reportRepository.findById(reportId)
                .orElseThrow(() -> new IllegalArgumentException("Report not found: " + reportId));
        boolean parented = report.getParentType() != null && report.getParentId() != null;
        // R34 reads the sources listing the video from the built search index, and the inner index while inner search is
        // on. Refuse a cold resolve before the report is marked, so it stays PENDING and the admin retries on the 503.
        // No wait; the Video read happens only while cold.
        if (newStatus == ReportStatus.RESOLVED && !parented && report.getTargetType() == ReportTargetType.VIDEO
                && !searchIndex.listingsLoaded() && videoRepository.findByYoutubeId(report.getTargetId()).isEmpty()) {
            throw new SearchIndex.NotReadyException();
        }
        report.setStatus(newStatus);
        report.setResolvedAt(Timestamp.now());
        report.setResolvedBy(resolvedBy);
        report.setResolutionNote(note);
        ContentReport saved = reportRepository.update(report);
        if (newStatus == ReportStatus.RESOLVED) {
            // When the report carries parent context, prefer adding the
            // target to the parent's exclusion list — that's the
            // user-visible side-effect the admin cares about and it does
            // not destroy the underlying content (vs. archiveReportedContent
            // which flips ValidationStatus to ARCHIVED app-wide). When
            // there's no parent context, fall back to the legacy
            // archive-everywhere behaviour so existing reports keep
            // working.
            boolean excluded = false;
            String miss = null;
            if (parented) {
                try {
                    excluded = excludeFromParent(report.getParentType(), report.getParentId(), report.getTargetType(),
                            report.getTargetId(), report.getContentSubType(), null);
                } catch (Exception e) { // stage 9 Cubic r8 #9: a failure is not "no parent", so never the take-down
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    log.warn("Failed to add target {} to parent {} exclusion list: {}", report.getTargetId(),
                            report.getParentId(), e.getMessage());
                    miss = "Not excluded on " + report.getParentType().name().toLowerCase() + ":" + report.getParentId()
                            + " (" + e.getClass().getSimpleName() + "), and not taken down. Exclude it there by hand.";
                }
            }
            if (excluded) {
                // Exclusion changes affect public listings and the admin
                // Exclusions workspace — bust both caches so they show it now.
                exclusionsChanged();
            } else if (miss == null) {   // no parent context, or none that applies
                miss = archiveReportedContent(report.getTargetType(), report.getTargetId());
            }
            if (miss != null) { // never a clean "resolved": the admin sees the miss and does it by hand
                report.setResolutionNote(note == null || note.isBlank() ? miss : note + "\n" + miss);
                saved = reportRepository.update(report);
            }
        }
        return saved;
    }

    /**
     * Add the target to its parent's exclusion list. True when it is there now (added, or already there); false when it
     * does not apply (no such parent, or no exclusion bucket for the target), so resolveReport takes the target down
     * instead. A failed read or write throws (stage 9 Cubic r8 #9): never read as "does not apply".
     */
    private boolean excludeFromParent(ReportTargetType parentType, String parentId, ReportTargetType targetType,
                                      String targetId, String contentSubType, List<String> addedTo)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (parentType == null || parentId == null || targetId == null || targetType == null) return false;

        switch (parentType) {
            case CHANNEL -> {
                var opt = channelRepository.findByYoutubeId(parentId);
                if (opt.isEmpty()) {
                    log.warn("Cannot exclude target {}: parent channel {} not found", targetId, parentId);
                    return false;
                }
                var channel = opt.get();
                var excluded = channel.getExcludedItems();
                if (excluded == null) excluded = new com.albunyaan.tube.model.Channel.ExcludedItems();
                String storageType = resolveChannelStorageType(targetType, contentSubType);
                if (storageType == null) {
                    log.warn("Cannot map report target ({}, sub={}) to a channel exclusion bucket", targetType, contentSubType);
                    return false;
                }
                boolean added = addToChannelExclusions(excluded, storageType, targetId);
                if (added) {
                    channel.setExcludedItems(excluded);
                    channelRepository.save(channel);
                    if (addedTo != null) addedTo.add("channel:" + parentId);
                }
                return true; // even no-op (already excluded) counts — don't fall back to archive
            }
            case PLAYLIST -> {
                if (targetType != ReportTargetType.VIDEO) {
                    log.warn("Playlist parent only supports VIDEO target, got {}", targetType);
                    return false;
                }
                var opt = playlistRepository.findByYoutubeId(parentId);
                if (opt.isEmpty()) {
                    log.warn("Cannot exclude target {}: parent playlist {} not found", targetId, parentId);
                    return false;
                }
                var playlist = opt.get();
                var ids = playlist.getExcludedVideoIds();
                if (ids == null) ids = new java.util.ArrayList<>();
                if (!ids.contains(targetId)) {
                    ids.add(targetId);
                    playlist.setExcludedVideoIds(ids);
                    playlistRepository.save(playlist);
                    if (addedTo != null) addedTo.add("playlist:" + parentId);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Map a report target+sub-type pair to the Channel.ExcludedItems
     * bucket name. Mirrors the resolveStorageType helper in
     * ExclusionsWorkspaceController so the two flows produce identical
     * exclusion records.
     */
    private static String resolveChannelStorageType(ReportTargetType targetType, String contentSubType) {
        if (targetType == ReportTargetType.PLAYLIST) return "PLAYLIST";
        if (targetType == ReportTargetType.VIDEO) {
            if (contentSubType == null) return "VIDEO";
            return switch (contentSubType) {
                case "SHORT" -> "SHORT";
                case "LIVESTREAM" -> "LIVESTREAM";
                case "POST" -> "POST";
                default -> "VIDEO";
            };
        }
        return null; // CHANNEL target inside CHANNEL parent makes no sense
    }

    /**
     * Append [excludeId] to the right Channel.ExcludedItems bucket.
     * Returns true when the list grew; false if the id was already there
     * or the bucket name was unrecognised. Mirrors the helper in
     * ExclusionsWorkspaceController; intentionally duplicated to keep
     * each controller/service free of the other's concerns.
     */
    private static boolean addToChannelExclusions(
            com.albunyaan.tube.model.Channel.ExcludedItems excluded,
            String storageType, String excludeId) {
        java.util.List<String> list;
        switch (storageType) {
            case "VIDEO": list = excluded.getVideos(); break;
            case "PLAYLIST": list = excluded.getPlaylists(); break;
            case "LIVESTREAM": list = excluded.getLiveStreams(); break;
            case "SHORT": list = excluded.getShorts(); break;
            case "POST": list = excluded.getPosts(); break;
            default: return false;
        }
        if (list == null) {
            list = new java.util.ArrayList<>();
            switch (storageType) {
                case "VIDEO": excluded.setVideos(list); break;
                case "PLAYLIST": excluded.setPlaylists(list); break;
                case "LIVESTREAM": excluded.setLiveStreams(list); break;
                case "SHORT": excluded.setShorts(list); break;
                case "POST": excluded.setPosts(list); break;
            }
        }
        if (!list.contains(excludeId)) {
            list.add(excludeId);
            return true;
        }
        return false;
    }

    /**
     * Archives the target. A video with no document gets one, its take-down record (stage 9 Cubic r2): any Video document
     * hides its id from search on every source, including one crawled after this resolve (SearchIndex.videoIds). It is
     * also excluded on the sources listing it now, for their channel and playlist screens (R34). Returns a miss, or null.
     * Stage 9 B23 (Cubic r11 #1, replacing B21's REJECTED): a doc keeps its status, so Restore gives it back to the review
     * it was in and its importers follow that review. Its ARCHIVED is the hold: the submitter's withdraw refuses an
     * ARCHIVED doc (the repositories' deleteByIdIfStatusIn), whatever status it is moved to.
     */
    private String archiveReportedContent(ReportTargetType targetType, String targetId) {
        boolean archived = false;
        String miss = null;
        try {
            switch (targetType) {
                case VIDEO -> {
                    java.util.Optional<Video> opt;
                    Video video;
                    // Stage 9 B16 (Cubic r6 P2, R12): the D7 monitor, so no live-poller restore lands between this read,
                    // the archive and the drop of the poller's restore record: a link it ended, taken down, stays down.
                    synchronized (liveChannelService.stateLock()) {
                        opt = videoRepository.findByYoutubeId(targetId);
                        if (opt.isEmpty() && !VIDEO_ID.matcher(targetId).matches()) { // stage 9 B11 P3: no junk records
                            log.warn("Cannot take down video {}: not a video id", targetId);
                            return "Not taken down: the reported id is not a video id.";
                        }
                        video = opt.orElseGet(() -> takeDownRecord(targetId));
                        video.setValidationStatus(ValidationStatus.ARCHIVED);
                        videoRepository.save(video);
                        archived = true;
                        // Stage 9 B19 (r6 P3-3): with no doc too. A record can outlive the doc the poller archived (an
                        // admin deleted it); kept, it would "restore" this take-down record when the id goes live again.
                        try {
                            liveChannelService.dropRestore(targetId);
                        } catch (Exception e) {
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            log.warn("Video {} taken down, but the live poller's restore record of it was not dropped: {}",
                                    targetId, e.getMessage());
                            miss = "Taken down, but the live poller may put it back if it goes live again ("
                                    + e.getClass().getSimpleName() + "). Resolve the report again later.";
                        }
                    }
                    if (opt.isEmpty()) {
                        String excludeMiss;
                        try {
                            excludeMiss = excludeEverywhereListed(video);
                        } catch (Exception e) { // the record is saved and indexed: only the per-source exclusions are missing
                            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                            log.warn("Video {} taken down, but its source exclusions failed: {}", targetId, e.getMessage());
                            excludeMiss = "Taken down, but not excluded (or not recorded for Restore) on the sources listing it ("
                                    + e.getClass().getSimpleName() + "). Check them by hand.";
                        }
                        if (excludeMiss != null) miss = miss == null ? excludeMiss : miss + "\n" + excludeMiss;   // B19: both
                    }
                    // Stage 9 B16 (Cubic r6 P2): after the sources are read, as they are read off the stream's index doc,
                    // which this deletes. The saved record already hides the stream from search meanwhile.
                    streamIndexService.markStreamArchived(targetId);
                }
                case CHANNEL -> {
                    var opt = channelRepository.findByYoutubeId(targetId);
                    if (opt.isPresent()) {
                        opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
                        channelRepository.save(opt.get());
                        streamIndexService.removeSource("CHANNEL", opt.get().getYoutubeId());
                        archived = true;
                    } else {
                        log.warn("Cannot archive channel {}: not found in database", targetId);
                    }
                }
                case PLAYLIST -> {
                    var opt = playlistRepository.findByYoutubeId(targetId);
                    if (opt.isPresent()) {
                        opt.get().setValidationStatus(ValidationStatus.ARCHIVED);
                        playlistRepository.save(opt.get());
                        streamIndexService.removeSource("PLAYLIST", opt.get().getYoutubeId());
                        archived = true;
                    } else {
                        log.warn("Cannot archive playlist {}: not found in database", targetId);
                    }
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Failed to archive {} {}: {}", targetType, targetId, e.getMessage());
            miss = "Not taken down: " + e.getClass().getSimpleName() + ". Take it down by hand.";
        }
        if (archived) {
            publicContentCacheService.evictPublicContentCaches();
        }
        return miss;
    }

    /**
     * A reported video's take-down record: REJECTED (never served, never in the approvals queue) and, by the caller,
     * ARCHIVED (listed under Archived content, where Restore or delete removes it). An admin's later add of the id, single
     * or bulk, is written into this doc, as for any REJECTED video (stage 9 B11); a moderator's is refused (B16).
     */
    private static Video takeDownRecord(String youtubeId) {
        Video video = new Video(youtubeId);
        video.setStatus("REJECTED");
        video.setSource(TAKE_DOWN_SOURCE);
        return video;
    }

    /**
     * Stage 9 B13: Restore lifts a take-down whole: the exclusions its report added and its stream's archive, then the
     * record (CAS: a re-add may have landed). A failure leaves the record, so Restore can run again; each lift repeats safely.
     * Stage 9 B14: what was lifted shows at once, also when a later step fails.
     */
    public void liftTakeDown(Video record) throws ExecutionException, InterruptedException, TimeoutException {
        try {
            lift(record);
            videoRepository.deleteByIdIfStatusIn(record.getId(), java.util.Set.of("REJECTED"));
        } finally {
            exclusionsChanged();
        }
    }

    /**
     * Stage 9 B14 (Cubic r5 #3): a re-add writes into the id's REJECTED doc (B11, a status CAS). When that doc was a
     * take-down record, the exclusions its report added go too, as on Restore. At add time, PENDING too: the doc itself
     * gates the video until approval (410 at the public gate, its stream blocked in search). After the write, so a lost
     * race lifts nothing; a lift failure then is logged for an admin, and the re-add stands.
     * Stage 9 B23 (Cubic r11 #13): the same lift as Restore's, so its stream's archive goes too.
     */
    public Video reAdd(Video video) throws ExecutionException, InterruptedException, TimeoutException {
        Video replaced = videoRepository.findById(video.getId()).orElse(null);
        Video saved = videoRepository.saveIfStatus(video, "REJECTED");
        if (replaced != null && isTakeDownRecord(replaced)) {
            try {
                lift(replaced);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Video {} re-added over its take-down record, but not every exclusion its report added ({}) was "
                        + "lifted, or its stream not shown in search again; lift them by hand: {}", video.getYoutubeId(),
                        replaced.getTakeDownExclusions(), e.toString());
            } finally {
                exclusionsChanged();
            }
        }
        return saved;
    }

    /**
     * What a take-down hid besides its record, for Restore and a re-add alike: the exclusions its report added, as the
     * record names them (B13: the id leaves each source's list), then its stream's archive (visible and searchable again).
     */
    private void lift(Video record) throws ExecutionException, InterruptedException, TimeoutException {
        String videoId = record.getYoutubeId();
        for (String key : record.getTakeDownExclusions() == null ? List.<String>of() : record.getTakeDownExclusions()) {
            String sourceId = key.substring(key.indexOf(':') + 1);
            if (key.startsWith("channel:")) {
                var channel = channelRepository.findByYoutubeId(sourceId).orElse(null);
                if (channel != null && channel.getExcludedItems() != null && channel.getExcludedItems().getVideos().remove(videoId)) {
                    channel.setExcludedItems(channel.getExcludedItems());      // recounts
                    channelRepository.save(channel);
                }
            } else {
                var playlist = playlistRepository.findByYoutubeId(sourceId).orElse(null);
                if (playlist != null && playlist.getExcludedVideoIds() != null && playlist.getExcludedVideoIds().remove(videoId)) {
                    playlist.setExcludedVideoIds(playlist.getExcludedVideoIds()); // recounts
                    playlistRepository.save(playlist);
                }
            }
        }
        streamIndexService.markStreamRestored(videoId);
    }

    /** A take-down record: the doc exists only to hide the id, so lifting the take-down means deleting it. */
    public static boolean isTakeDownRecord(Video video) {
        return isTakeDownRecord(video.getStatus(), video.getSource());
    }

    /** The same, on a raw doc's fields. Stage 9 B21 (Cubic r9 #14): no status change moves it out of REJECTED. */
    public static boolean isTakeDownRecord(String status, String source) {
        return "REJECTED".equals(status) && TAKE_DOWN_SOURCE.equals(source);
    }

    /** After an exclusion write: public listings and the admin Exclusions workspace (a 5-minute cache) show it at once. */
    private void exclusionsChanged() {
        publicContentCacheService.evictPublicContentCaches();
        workspaceExclusionsCache.invalidateAll();
    }

    /**
     * Inner-search R34. A report opened from search carries no parent, and an inner or stream video had no Video
     * document to archive (the caller now writes its take-down record). Also exclude it on every admitted source that
     * lists it now, for their channel and playlist screens: channels -> excludedItems.videos, playlists ->
     * excludedVideoIds. Works for every installed app; a later app may send sourceType/sourceId as the parent.
     * Returns the miss for the resolution note (sources not excluded), or null. The record keeps the sources it excluded
     * the video on (stage 9 B13), and only those: one an admin had excluded it on before stays excluded after Restore.
     */
    private String excludeEverywhereListed(Video record) throws Exception {
        String videoId = record.getYoutubeId();
        // Before the listings load (the first build; the inner index while inner search is on) an empty listing means
        // "not loaded", not "none". resolveReport refuses a cold parentless resolve; only a parented report whose parent
        // is gone gets here cold.
        if (!searchIndex.listingsLoaded()) {
            log.warn("Cannot exclude video {}: the search index is still building", videoId);
            return "Not excluded: the search index was still building, so the sources listing this video are unknown. "
                    + "Exclude it by hand.";
        }
        List<String> keys = searchIndex.sourcesListing(videoId);
        if (keys.isEmpty()) {
            log.info("Report resolved: video {} is listed by no approved source yet; its take-down record hides it", videoId);
            return null;
        }
        List<String> added = new java.util.ArrayList<>();
        List<String> missed = new java.util.ArrayList<>();
        for (String key : keys) {
            String sourceId = key.substring(key.indexOf(':') + 1);
            boolean done = false;
            try {
                done = excludeFromParent(key.startsWith("channel:") ? ReportTargetType.CHANNEL : ReportTargetType.PLAYLIST,
                        sourceId, ReportTargetType.VIDEO, videoId, null, added);
            } catch (Exception e) {   // this source only: the next ones are still tried, and it is named in the miss
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("Failed to add target {} to parent {} exclusion list: {}", videoId, sourceId, e.getMessage());
            }
            if (!done) missed.add(key);
        }
        if (missed.size() < keys.size()) exclusionsChanged();
        if (!added.isEmpty()) {   // stage 9 B14: that one field, only while the doc is still the record (a re-add wins)
            videoRepository.updateIfStatusAndSource(record.getId(), "REJECTED", TAKE_DOWN_SOURCE, "takeDownExclusions", added);
        }
        log.info("Report resolved: video {} listed by {}; not excluded on {}", videoId, keys, missed);
        return missed.isEmpty() ? null : "Not excluded on " + String.join(", ", missed) + ". Exclude it there by hand.";
    }

    public ReportStats getStats() throws ExecutionException, InterruptedException, TimeoutException {
        long pending = reportRepository.countByStatus(ReportStatus.PENDING);
        long resolved = reportRepository.countByStatus(ReportStatus.RESOLVED);
        long rejected = reportRepository.countByStatus(ReportStatus.REJECTED);
        Timestamp since = Timestamp.ofTimeSecondsAndNanos(
                Instant.now().minus(3, ChronoUnit.HOURS).getEpochSecond(), 0);
        long newLast3h = reportRepository.countCreatedAfter(since);
        return new ReportStats(pending, resolved, rejected, newLast3h);
    }

    private void checkRateLimit(String deviceKey) {
        if (deviceKey == null || deviceKey.isBlank()) deviceKey = ANONYMOUS_DEVICE_KEY;
        AtomicInteger count = rateLimitCache.get(deviceKey, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();
        if (current > RATE_LIMIT_MAX) {
            count.decrementAndGet();
            throw new RateLimitExceededException("Report rate limit exceeded for device: " + deviceKey);
        }
    }

    public record ReportStats(long pending, long resolved, long rejected, long newLast3h) {}

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) { super(message); }
    }
}
