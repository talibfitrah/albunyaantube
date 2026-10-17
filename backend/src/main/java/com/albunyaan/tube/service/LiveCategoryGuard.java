package com.albunyaan.tube.service;

import com.albunyaan.tube.exception.LiveCategoryException;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Spec R1, R14, §7: the one rule every write path runs before it writes. A write "adds" a LIVE membership when the
 * doc gains a LIVE category id (checkAdd) or when its status becomes APPROVED while it holds one (checkPublish).
 * Adds are ADMIN-only; a CHANNEL is capped and seeded (D15); a VIDEO must be LIVE on its channel's Live tab now,
 * which proves both "live" and ownership; anything else is refused. Bulk paths use checkAll (D9).
 */
@Service
public class LiveCategoryGuard {

    /** R7: at most 30 entries per LIVE category. */
    public static final int MAX_ENTRIES = 30;

    /** One write's effect on one doc. {@code type}: "channel" | "playlist" | "video"; {@code ref} names it in offender lists. */
    public record Change(String ref, String type, String youtubeId, String channelId,
                         Collection<String> oldCategoryIds, String oldStatus,
                         Collection<String> newCategoryIds, String newStatus) {

        /** Pre-flight D11: the one doc → Change mapping for the library, sort and import paths. {@code before} null = new doc. */
        public static Change of(String ref, com.albunyaan.tube.model.Channel after, com.albunyaan.tube.model.Channel before,
                                Collection<String> newIds, String newStatus) {
            return new Change(ref, "channel", after.getYoutubeId(), null, before == null ? List.of() : before.getCategoryIds(),
                    before == null ? null : before.getStatus(), newIds, newStatus);
        }

        public static Change of(String ref, com.albunyaan.tube.model.Playlist after, com.albunyaan.tube.model.Playlist before,
                                Collection<String> newIds, String newStatus) {
            return new Change(ref, "playlist", after.getYoutubeId(), null, before == null ? List.of() : before.getCategoryIds(),
                    before == null ? null : before.getStatus(), newIds, newStatus);
        }

        public static Change of(String ref, Video after, Video before, Collection<String> newIds, String newStatus) {
            return new Change(ref, "video", after.getYoutubeId(), after.getChannelId(),
                    before == null ? List.of() : before.getCategoryIds(), before == null ? null : before.getStatus(),
                    newIds, newStatus);
        }
    }

    private final LiveCategoryService liveCategories;
    private final CategoryContentOrderRepository orders;
    private final LiveChannelService live;
    private final VideoRepository videos;

    public LiveCategoryGuard(LiveCategoryService liveCategories, CategoryContentOrderRepository orders,
                             LiveChannelService live, VideoRepository videos) {
        this.liveCategories = liveCategories;
        this.orders = orders;
        this.live = live;
        this.videos = videos;
    }

    /** One write. Returns the verified UC channel id for a VIDEO that adds or publishes LIVE; null otherwise. */
    public String check(boolean admin, Change change) throws ExecutionException, InterruptedException, TimeoutException {
        return verify(admin, change, liveCategories.liveCategoryIds(), new HashMap<>());
    }

    /** D9: every row is verified; any refusal refuses the whole request and lists every offender. */
    public Map<String, String> checkAll(boolean admin, List<Change> changes)
            throws ExecutionException, InterruptedException, TimeoutException {
        return checkAll(admin, changes, liveCategories.liveCategoryIds(), new HashMap<>());
    }

    /** Row 17 (§4.2): setting LIVE on a non-empty category requires every member to conform. */
    public void checkMembersForLive(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        LiveCategoryService.Members m = liveCategories.members(categoryId);
        List<String> into = List.of(categoryId);
        List<Change> changes = new ArrayList<>();
        m.channels().forEach(c -> changes.add(new Change(c.getId(), "channel", c.getYoutubeId(), null, List.of(), c.getStatus(), into, c.getStatus())));
        m.playlists().forEach(p -> changes.add(new Change(p.getId(), "playlist", p.getYoutubeId(), null, List.of(), p.getStatus(), into, p.getStatus())));
        m.videos().forEach(v -> changes.add(new Change(v.getId(), "video", v.getYoutubeId(), v.getChannelId(), List.of(), v.getStatus(), into, v.getStatus())));
        Map<String, Long> counts = new HashMap<>(Map.of(categoryId, 0L));      // the members ARE the entries
        Map<String, String> verified;
        try {
            verified = checkAll(true, changes, Set.of(categoryId), counts);
        } catch (LiveCategoryException e) {
            if (e.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {
                throw e;
            }
            throw new LiveCategoryException(HttpStatus.BAD_REQUEST, LiveCategoryException.MEMBERS_INVALID,
                    "This category holds items that aren't channels or live streams", e.getDetails());
        }
        for (Video v : m.videos()) {                                           // D13: a link needs its channel id
            String channel = verified.get(v.getId());
            if (channel != null && !channel.equals(v.getChannelId())) {
                v.setChannelId(channel);
                videos.save(v);
            }
        }
    }

    /** The LIVE category ids this change adds: all of the doc's on a publish, else only the new ones. */
    static Set<String> addedLive(Change c, Set<String> liveIds) {
        Set<String> added = new LinkedHashSet<>(c.newCategoryIds() == null ? List.of() : c.newCategoryIds());
        boolean publishes = "APPROVED".equalsIgnoreCase(c.newStatus()) && !"APPROVED".equalsIgnoreCase(c.oldStatus());
        if (!publishes && c.oldCategoryIds() != null) {
            added.removeAll(c.oldCategoryIds());
        }
        added.retainAll(liveIds);
        return added;
    }

    private Map<String, String> checkAll(boolean admin, List<Change> changes, Set<String> liveIds, Map<String, Long> counts)
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, String> verified = new LinkedHashMap<>();
        List<Map<String, Object>> offenders = new ArrayList<>();
        LiveCategoryException first = null;
        for (Change c : changes) {
            try {
                String channel = verify(admin, c, liveIds, counts);
                if (channel != null) {
                    verified.put(c.ref(), channel);
                }
            } catch (LiveCategoryException e) {
                if (first == null) {
                    first = e;
                }
                offenders.add(Map.of("ref", String.valueOf(c.ref()), "code", e.getCode(), "message", e.getMessage()));
            }
        }
        if (first != null) {
            throw new LiveCategoryException(first.getStatus(), first.getCode(),
                    offenders.size() + " item(s) refused for the live category; nothing was saved",
                    Map.of("offenders", offenders));
        }
        return verified;
    }

    private String verify(boolean admin, Change c, Set<String> liveIds, Map<String, Long> counts)
            throws ExecutionException, InterruptedException, TimeoutException {
        Set<String> added = addedLive(c, liveIds);
        if (added.isEmpty()) {
            return null;                                                       // rule 1
        }
        if (!admin) {                                                          // rule 2
            throw refusal(HttpStatus.FORBIDDEN, LiveCategoryException.ADMIN_ONLY,
                    "Only admins can add to or publish into the live category", c);
        }
        if (!"channel".equals(c.type()) && !"video".equals(c.type())) {        // rule 4
            throw refusal(HttpStatus.BAD_REQUEST, LiveCategoryException.TYPE_NOT_ALLOWED,
                    "The live category accepts only channels and live streams", c);
        }
        for (String cat : added) {                                             // the cap (R7)
            Long current = counts.get(cat);
            long next = (current != null ? current : orders.countByCategoryId(cat)) + 1;
            if (next > MAX_ENTRIES) {
                throw refusal(HttpStatus.BAD_REQUEST, LiveCategoryException.FULL,
                        "The live category is full (" + MAX_ENTRIES + " entries)", c);
            }
            counts.put(cat, next);
        }
        if ("channel".equals(c.type())) {                                      // rule 3 (D15)
            if (c.youtubeId() != null) {
                live.seed(c.youtubeId());
            }
            return null;
        }
        String channel = c.channelId() != null && !c.channelId().isBlank()     // rule 5 (D10)
                ? c.channelId() : live.resolveChannelId(c.youtubeId()).orElse(null);
        if (channel == null) {
            throw refusal(HttpStatus.BAD_REQUEST, LiveCategoryException.CHANNEL_UNKNOWN,
                    "The channel of video " + c.youtubeId() + " could not be found", c);
        }
        LiveTabResult read = live.livesNow(channel);
        boolean unknown = switch (read.outcome()) {                            // exhaustive: a new outcome must pick a side
            case OK, TERMINATED -> false;                                      // TERMINATED is an empty read: NOT_LIVE below
            case ERROR, RATE_LIMITED, BREAKER -> true;                         // R7: no signal either way
        };
        if (unknown) {
            throw new LiveCategoryException(HttpStatus.SERVICE_UNAVAILABLE, LiveCategoryException.CHECK_UNAVAILABLE,
                    "Couldn't confirm video " + c.youtubeId() + " is live; try again later",
                    Map.of("youtubeId", String.valueOf(c.youtubeId()), "channelId", channel));
        }
        if (read.lives().stream().noneMatch(l -> l.id().equals(c.youtubeId()))) {
            throw new LiveCategoryException(HttpStatus.BAD_REQUEST, LiveCategoryException.NOT_LIVE,
                    "Video " + c.youtubeId() + " is not live on channel " + channel + " now",
                    Map.of("youtubeId", String.valueOf(c.youtubeId()), "channelId", channel));
        }
        return channel;
    }

    private static LiveCategoryException refusal(HttpStatus status, String code, String message, Change c) {
        return new LiveCategoryException(status, code, message,
                Map.of("ref", String.valueOf(c.ref()), "type", String.valueOf(c.type())));
    }
}
