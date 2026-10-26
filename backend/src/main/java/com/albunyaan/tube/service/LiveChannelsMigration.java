package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.scheduler.LiveChannelPoller;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spec §10: the one-shot move of "Channels Live" from 6 stored video links to 4 channel entries. DRY_RUN writes
 * nothing and is the prod reachability gate (G1: one poll per channel; G2: one oEmbed resolution). APPLY is
 * idempotent and resumable: every step checks whether it is done; the backup is written once per category (R18).
 * Deleted with its controller in the cleanup deploy (plan Task 37).
 */
@Service
public class LiveChannelsMigration {

    public static final String BACKUP_ACTION = "live_channels_migration_backup";

    public record PlanChannel(String channelId, int position) {}
    public record PlanVideo(String youtubeId, String channelId) {}
    public record Plan(String mode, String categoryId, List<PlanChannel> channels, List<PlanVideo> removeVideos) {}

    private final CategoryRepository categories;
    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;
    private final CategoryContentOrderRepository orders;
    private final SortOrderService sortOrder;
    private final LiveChannelService live;
    private final LiveCategoryService liveCategories;
    private final LiveChannelPoller poller;
    private final AuditLogRepository auditLogs;
    private final AuditLogService audit;
    private final PublicContentCacheService caches;
    private final Clock clock;

    public LiveChannelsMigration(CategoryRepository categories, ChannelRepository channels, PlaylistRepository playlists,
                                 VideoRepository videos, CategoryContentOrderRepository orders, SortOrderService sortOrder,
                                 LiveChannelService live, LiveCategoryService liveCategories, LiveChannelPoller poller,
                                 AuditLogRepository auditLogs, AuditLogService audit, PublicContentCacheService caches,
                                 Clock clock) {
        this.categories = categories;
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
        this.orders = orders;
        this.sortOrder = sortOrder;
        this.live = live;
        this.liveCategories = liveCategories;
        this.poller = poller;
        this.auditLogs = auditLogs;
        this.audit = audit;
        this.caches = caches;
        this.clock = clock;
    }

    /** Synchronized: a retry while an APPLY still runs (a proxy 524) waits, then resumes; it never writes a second backup. */
    public synchronized Map<String, Object> run(Plan plan, String actorUid, String actorEmail) throws Exception {
        boolean apply = "APPLY".equals(plan.mode());
        if (!apply && !"DRY_RUN".equals(plan.mode())) {
            throw new IllegalArgumentException("mode must be DRY_RUN or APPLY");
        }
        String categoryId = plan.categoryId();
        Category category = categories.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("category not found: " + categoryId));
        long now = clock.millis();
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> done = new ArrayList<>();
        if (category.isLive()) done.add(Map.of("op", "SET_CATEGORY_TYPE"));

        // Step 1: one poll per plan channel (G1).
        List<PlanChannel> planChannels = plan.channels().stream().sorted(Comparator.comparingInt(PlanChannel::position)).toList();
        Map<String, LiveTabResult> polls = new LinkedHashMap<>();
        Map<String, Channel> channelDocs = new LinkedHashMap<>();
        List<Map<String, Object>> channelRows = new ArrayList<>();
        int requests = 0;
        boolean proven = false;
        for (PlanChannel pc : planChannels) {
            if (polls.containsKey(pc.channelId())) {
                blockers.add("channel " + pc.channelId() + " is listed twice");
                continue;
            }
            Channel doc = channels.findByYoutubeId(pc.channelId()).orElse(null);
            LiveTabResult r = live.readFresh(pc.channelId());              // §10: a fresh poll on every run
            polls.put(pc.channelId(), r);
            requests += r.requests();
            proven |= r.hasLive();
            if (doc == null) {
                blockers.add("channel " + pc.channelId() + " has no registry doc");
            } else {
                channelDocs.put(pc.channelId(), doc);
                if (!"APPROVED".equals(doc.getStatus())) blockers.add("channel " + pc.channelId() + " is " + doc.getStatus());
                if (!VisibilityPolicy.isPublic(doc.getVisibility())) blockers.add("channel " + pc.channelId() + " is not public");
                if (doc.getCategoryIds() != null && doc.getCategoryIds().contains(categoryId)) {
                    done.add(Map.of("op", "ADD_CHANNEL_ENTRY", "docId", doc.getId()));
                }
            }
            if (r.outcome() != LiveTabResult.Outcome.OK) blockers.add("poll of " + pc.channelId() + " is " + r.outcome());
            else if (r.lives().isEmpty()) warnings.add("channel " + pc.channelId() + " is offline now");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channelId", pc.channelId());
            row.put("docId", doc == null ? null : doc.getId());
            row.put("docStatus", doc == null ? null : doc.getStatus());
            row.put("public", doc != null && VisibilityPolicy.isPublic(doc.getVisibility()));
            row.put("poll", r.outcome().name());
            row.put("requests", r.requests());
            row.put("lives", r.lives().stream().map(l -> Map.of("id", l.id(), "title", l.title() == null ? "" : l.title())).toList());
            row.put("position", pc.position());
            channelRows.add(row);
        }
        if (!proven) blockers.add("unproven poll: no plan channel reads LIVE (R10)");
        // F2: steps 3 and 7 compute from the snapshot; loaded never goes back to false, so this holds for the whole run
        if (!live.loaded()) blockers.add("live channel states not loaded yet; retry after the next poller cycle");
        if (planChannels.size() > LiveCategoryGuard.MAX_ENTRIES) blockers.add("more than " + LiveCategoryGuard.MAX_ENTRIES + " entries");
        // Stage 9 B16 (Cubic r6 P3): step 6 sets LIVE directly, so D8 is checked here as the category update checks it.
        if (!LiveCategoryGuard.liveShape(categories, categoryId, category.getParentCategoryId())) {
            blockers.add("D8: a live category can't have a parent category or subcategories");
        }

        // The removed videos: their fate is decided by the poll (R12b).
        Map<String, Video> videoDocs = new LinkedHashMap<>();
        Map<String, String> actions = new LinkedHashMap<>();
        List<Map<String, Object>> videoRows = new ArrayList<>();
        for (PlanVideo pv : plan.removeVideos()) {
            Video doc = videos.findByYoutubeId(pv.youtubeId()).orElse(null);
            LiveTabResult r = polls.get(pv.channelId());
            boolean liveNow = r != null && r.lives().stream().anyMatch(l -> l.id().equals(pv.youtubeId()));
            boolean inCategory = doc != null && doc.getCategoryIds() != null && doc.getCategoryIds().contains(categoryId);
            String action = liveNow ? "WRITE_CHANNEL_ID" : "ARCHIVE";
            if (doc == null) blockers.add("video " + pv.youtubeId() + " has no registry doc");
            else videoDocs.put(pv.youtubeId(), doc);
            if (r == null) blockers.add("video " + pv.youtubeId() + " names a channel that is not in the plan");
            if (doc != null && !inCategory) done.add(Map.of("op", "REMOVE_VIDEO_FROM_CATEGORY", "youtubeId", pv.youtubeId()));
            actions.put(pv.youtubeId(), action);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("youtubeId", pv.youtubeId());
            row.put("docId", doc == null ? null : doc.getId());
            row.put("inCategory", inCategory);
            row.put("categoryIds", doc == null ? null : doc.getCategoryIds());   // R12: another category keeps the doc
            row.put("liveNow", liveNow);
            row.put("action", action);
            videoRows.add(row);
        }

        // Members the plan does not cover would be left in a LIVE category unchecked.
        Set<String> covered = new LinkedHashSet<>();
        channelDocs.values().forEach(c -> covered.add(c.getId()));
        videoDocs.values().forEach(v -> covered.add(v.getId()));
        List<String> unlisted = new ArrayList<>();
        for (Object member : liveCategories.members(categoryId).ordered()) {
            String id = member instanceof Channel c ? c.getId() : member instanceof Video v ? v.getId()
                    : member instanceof Playlist p ? p.getId() : null;
            if (id != null && !covered.contains(id)) unlisted.add(id);
        }
        if (!unlisted.isEmpty()) blockers.add("category members not covered by the plan: " + unlisted);
        List<String> pending = new ArrayList<>();
        channels.findByCategoryIdAndStatus(categoryId, "PENDING", 50).forEach(c -> pending.add("channel:" + c.getId()));
        playlists.findByCategoryIdAndStatus(categoryId, "PENDING", 50).forEach(p -> pending.add("playlist:" + p.getId()));
        videos.findByCategoryIdAndStatus(categoryId, "PENDING", 50).forEach(v -> pending.add("video:" + v.getId()));

        // G2: D10's resolution path, once, on the first removed video. A failure is a warning, never a blocker (§10).
        Map<String, Object> resolverCheck = null;
        if (!plan.removeVideos().isEmpty()) {
            PlanVideo first = plan.removeVideos().get(0);
            Optional<String> resolved = Optional.empty();
            boolean match = false;
            try {
                resolved = live.resolveChannelId(first.youtubeId());
                match = resolved.map(first.channelId()::equals).orElse(false);
                if (!match) warnings.add("G2: resolution of " + first.youtubeId() + " gave " + resolved.orElse("nothing"));
            } catch (IOException e) {
                warnings.add("G2: resolution of " + first.youtubeId() + " unavailable: " + e.getMessage());
            }
            resolverCheck = new LinkedHashMap<>();
            resolverCheck.put("youtubeId", first.youtubeId());
            resolverCheck.put("resolvedChannelId", resolved.orElse(null));
            resolverCheck.put("match", match);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", plan.mode());
        out.put("categoryId", categoryId);
        out.put("typeBefore", category.getType());
        out.put("typeAfter", Category.TYPE_LIVE);
        out.put("polledAt", Instant.ofEpochMilli(now).toString());
        out.put("youtubeRequests", requests);
        out.put("proven", proven);
        out.put("channels", channelRows);
        out.put("videos", videoRows);
        out.put("resolverCheck", resolverCheck);
        out.put("done", done);
        out.put("unlistedMembers", unlisted);
        out.put("pendingWithLiveCategory", pending);
        out.put("blockers", blockers);
        out.put("warnings", warnings);
        if (!apply || !blockers.isEmpty()) {
            List<Map<String, Object>> servedAfter = new ArrayList<>();      // what the expansion would serve
            Set<String> seen = new LinkedHashSet<>();
            for (PlanChannel pc : planChannels) {
                for (LiveTabResult.LiveItem l : polls.get(pc.channelId()).lives()) {
                    if (seen.add(l.id())) servedAfter.add(Map.of("position", pc.position(), "id", l.id(), "liveChannelId", pc.channelId()));
                }
            }
            out.put("servedAfter", servedAfter);
            return out;
        }

        try {
            // Step 2: the backup, once per category, ever (R18).
            Optional<AuditLog> backup = auditLogs.findByAction(BACKUP_ACTION, 20).stream()
                    .filter(a -> categoryId.equals(a.getEntityId())).findFirst();
            if (backup.isPresent()) {
                out.put("resumed", true);
                out.put("backupAuditId", backup.get().getId());
            } else {
                AuditLog entry = new AuditLog(BACKUP_ACTION, "category", categoryId, actorUid);
                entry.setActorDisplayName(actorEmail);
                entry.setDetails(backupDetails(category, orders.findByCategoryIdOrderByPosition(categoryId), channelDocs.values(), videoDocs.values()));
                AuditLog saved = auditLogs.save(entry);
                out.put("resumed", false);
                out.put("backupAuditId", saved.getId());
                out.put("backup", saved.getDetails());
            }
            // Step 3: the state docs, into the snapshot, by the seed's reviewed compute-and-put (D7 lock, restore
            // records kept). Not a proven poller cycle, so nothing ends or is removed here (R10, R15).
            polls.forEach(live::seedWith);
            // Step 4: the verified channel id on the still-live docs (D13).
            for (PlanVideo pv : plan.removeVideos()) {
                Video doc = videoDocs.get(pv.youtubeId());
                if ("WRITE_CHANNEL_ID".equals(actions.get(pv.youtubeId())) && !pv.channelId().equals(doc.getChannelId())) {
                    doc.setChannelId(pv.channelId());
                    videos.save(doc);
                }
            }
            // Step 5: the channel entries, while the category is still ordinary (RV M12 order): the guard sees no LIVE
            // id added, so it reads nothing from YouTube.
            List<String[]> toAdd = new ArrayList<>();
            for (Channel c : channelDocs.values()) {
                if (c.getCategoryIds() == null || !c.getCategoryIds().contains(categoryId)) toAdd.add(new String[]{c.getId(), "channel"});
            }
            if (!toAdd.isEmpty()) sortOrder.addMultipleContentToCategory(categoryId, toAdd);
            // Step 6: the type, set directly: the members were validated above, so row 17's members rule is not rerun.
            if (!category.isLive()) {
                category.setType(Category.TYPE_LIVE);
                categories.save(category);
            }
            // Step 7: the 6 links leave; the 2 ended docs are archived (R12b); the 4 live docs stay public. Always called:
            // both halves (the doc's categoryIds, then the order row) skip what is done, so a run cut short between
            // them still loses its orphan row on the re-run.
            for (PlanVideo pv : plan.removeVideos()) {
                sortOrder.removeContentFromCategoryAndUpdate(categoryId, videoDocs.get(pv.youtubeId()).getId(), "video");
            }
            for (PlanVideo pv : plan.removeVideos()) {
                if (!"ARCHIVE".equals(actions.get(pv.youtubeId()))) continue;
                Video doc = videos.findById(videoDocs.get(pv.youtubeId()).getId()).orElseThrow();
                if (doc.getValidationStatus() == ValidationStatus.ARCHIVED) continue;          // done on an earlier run
                if (!LiveChannelPoller.archivableOnceRemoved(doc, Set.of(categoryId))) {    // R12, the poller's rule
                    warnings.add("kept: " + pv.youtubeId() + " " + (doc.getCategoryIds() != null && !doc.getCategoryIds().isEmpty()
                            ? "holds categories " + doc.getCategoryIds()
                            : "is " + (doc.getValidationStatus() == ValidationStatus.UNAVAILABLE ? "UNAVAILABLE" : doc.getStatus())));
                    continue;
                }
                // R12's restore record first, as the poller writes it: if the id reappears live, the poller restores it.
                synchronized (live.stateLock()) {
                    LiveChannelState s = live.state(pv.channelId());
                    LiveChannelState next = s == null ? LiveChannelState.unknown(pv.channelId()) : s.copy();
                    ValidationStatus vs = doc.getValidationStatus();
                    next.getArchived().put(pv.youtubeId(), vs == null ? "" : vs.name());
                    next.getArchivedFrom().put(pv.youtubeId(), List.of(categoryId));
                    live.put(next);
                }
                doc.setValidationStatus(ValidationStatus.ARCHIVED);
                videos.save(doc);
                audit.logSystem("live_stream_archived_ended", "video", doc.getId(), "live-channels-migration");
            }
            // Step 8: positions 0..n in plan order.
            Map<String, Integer> position = new HashMap<>();
            planChannels.forEach(pc -> position.put(channelDocs.get(pc.channelId()).getId(), pc.position()));
            List<CategoryContentOrder> rows = orders.findByCategoryIdOrderByPosition(categoryId);
            rows.forEach(r -> r.setPosition(position.getOrDefault(r.getContentId(), r.getPosition())));
            orders.batchSave(rows);
            // Step 9: the only eviction that matters (any earlier one only flipped to a valid intermediate list).
            poller.refreshView();
            caches.evictPublicContentCaches();
            audit.logSystem("live_channels_migrated", "category", categoryId, actorEmail);
            List<Map<String, Object>> servedAfter = new ArrayList<>();
            for (ContentItemDto item : liveCategories.items(categoryId)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("liveChannelId", item.getLiveChannelId());
                servedAfter.add(row);
            }
            out.put("servedAfter", servedAfter);
            return out;
        } catch (com.albunyaan.tube.exception.LiveCategoryException e) {
            throw e;                                              // a coded refusal keeps its status (§4.2)
        } catch (Exception e) {
            // Pre-flight M1: a crash mid-run is a 5xx (GlobalExceptionHandler's generic handler), never the 409 an
            // IllegalStateException maps to: nothing was refused, the run stopped. Re-run APPLY to resume (R18).
            throw new MigrationFailed("live-channels APPLY stopped; re-run APPLY to resume: " + e.getMessage(), e);
        }
    }

    /** Pre-flight M1: answered 500 by the generic handler. Deleted with this class (Task 37). */
    static final class MigrationFailed extends RuntimeException {
        MigrationFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static Map<String, Object> backupDetails(Category category, List<CategoryContentOrder> rows,
                                                     Iterable<Channel> channelDocs, Iterable<Video> videoDocs) {
        Map<String, Object> cat = new LinkedHashMap<>();
        cat.put("id", category.getId());
        cat.put("name", category.getName());
        cat.put("type", category.getType());
        cat.put("parentCategoryId", category.getParentCategoryId());
        cat.put("displayOrder", category.getDisplayOrder());
        cat.put("icon", category.getIcon());
        cat.put("localizedNames", category.getLocalizedNames());
        List<Map<String, Object>> orderRows = new ArrayList<>();
        for (CategoryContentOrder r : rows) {                       // null-tolerant: a stored row may lack a field
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("contentId", r.getContentId());
            row.put("contentType", r.getContentType());
            row.put("position", r.getPosition());
            orderRows.add(row);
        }
        List<Map<String, Object>> docs = new ArrayList<>();
        channelDocs.forEach(c -> docs.add(doc("channel", c.getId(), c.getCategoryIds(), c.getValidationStatus(), null)));
        videoDocs.forEach(v -> docs.add(doc("video", v.getId(), v.getCategoryIds(), v.getValidationStatus(), v.getChannelId())));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("category", cat);
        details.put("orderRows", orderRows);
        details.put("docs", docs);
        return details;
    }

    private static Map<String, Object> doc(String type, String id, List<String> categoryIds, ValidationStatus vs, String channelId) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", type);
        d.put("id", id);
        d.put("categoryIds", categoryIds);
        d.put("validationStatus", vs == null ? null : vs.name());
        d.put("channelId", channelId);
        return d;
    }
}
