package com.albunyaan.tube.service;

import com.albunyaan.tube.exception.LiveCategoryException;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Spec R1, R14, §7: the one rule every write path runs before it writes. A write "adds" a LIVE membership when the
 * doc gains a LIVE category id (checkAdd) or when its status becomes APPROVED while it holds one (checkPublish).
 * Adds are ADMIN-only; a CHANNEL is capped and seeded (D15); a VIDEO must be LIVE on its channel's Live tab now,
 * which proves both "live" and ownership; anything else is refused. Bulk paths use checkAll (D9).
 * Every entry point runs two passes, so a refused request spends no YouTube read and seeds nothing: pass 1 applies the
 * rules that need no YouTube read (admin, type, cap) to every row; pass 2 checks every video against its channel's Live tab;
 * the channel seeds run only once both pass. An admin with both kinds of offender sees the YouTube ones next attempt.
 */
@Service
public class LiveCategoryGuard {

    private static final Logger log = LoggerFactory.getLogger(LiveCategoryGuard.class);

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

        /** D10: {@code after}'s channel id when it is a UC id (the request body), else {@code before}'s (the stored doc). */
        public static Change of(String ref, Video after, Video before, Collection<String> newIds, String newStatus) {
            String channelId = before == null || LiveTabReader.isChannelId(after.getChannelId())
                    ? after.getChannelId() : before.getChannelId();
            return new Change(ref, "video", after.getYoutubeId(), channelId,
                    before == null ? List.of() : before.getCategoryIds(), before == null ? null : before.getStatus(),
                    newIds, newStatus);
        }
    }

    /** One refused row: its ref for the offender list and its own refusal. */
    private record Offender(String ref, LiveCategoryException refusal) {}

    private final LiveCategoryService liveCategories;
    private final LiveChannelService live;
    private final VideoRepository videos;
    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final CategoryMappingService categoryMapping;

    public LiveCategoryGuard(LiveCategoryService liveCategories, LiveChannelService live, VideoRepository videos,
                             ChannelRepository channels, PlaylistRepository playlists, CategoryMappingService categoryMapping) {
        this.liveCategories = liveCategories;
        this.live = live;
        this.videos = videos;
        this.channels = channels;
        this.playlists = playlists;
        this.categoryMapping = categoryMapping;
    }

    /** One write. Returns the verified UC channel id for a VIDEO that adds or publishes LIVE; null otherwise. */
    public String check(boolean admin, Change change) throws ExecutionException, InterruptedException, TimeoutException {
        List<Offender> refused = new ArrayList<>();
        Map<String, String> verified = run(admin, List.of(change), liveCategories.liveCategoryIds(), new HashMap<>(), refused);
        if (!refused.isEmpty()) {
            throw refused.get(0).refusal();
        }
        return verified.get(change.ref());
    }

    /** D9: every row is verified; any refusal refuses the whole request and lists every offender. */
    public Map<String, String> checkAll(boolean admin, List<Change> changes)
            throws ExecutionException, InterruptedException, TimeoutException {
        List<Offender> refused = new ArrayList<>();
        Map<String, String> verified = run(admin, changes, liveCategories.liveCategoryIds(), new HashMap<>(), refused);
        if (!refused.isEmpty()) {
            throw envelope(refused);
        }
        return verified;
    }

    /**
     * Rows 15–16 (D9): the changes a simple-format import ([channels, playlists, videos] maps of youtubeId →
     * "Title|Cat1,Cat2|keywords") would make. Only rows that name a LIVE category and are new (the import skips
     * existing ones) are returned, so an import that never touches LIVE costs no extra read.
     * ponytail: both simple importers drop checkAll's verified channel ids and store the uploader-URL id; NewPipe 0.26.5
     * gives a /channel/UC… URL, and the guard proved the video live on that channel. Write the verified id if it stops.
     */
    public List<Change> simpleImportChanges(List<Map<String, String>> simpleData, String defaultStatus)
            throws ExecutionException, InterruptedException, TimeoutException {
        Set<String> liveIds = liveCategories.liveCategoryIds();
        List<Change> changes = new ArrayList<>();
        String[] types = {"channel", "playlist", "video"};
        for (int i = 0; i < types.length && i < simpleData.size(); i++) {
            Map<String, String> rows = simpleData.get(i);
            if (rows == null) continue;
            for (Map.Entry<String, String> row : rows.entrySet()) {
                String names = SimpleImportService.Row.parse(row.getValue()).categories();
                List<String> ids = categoryMapping.mapCategoryNamesToIds(names);
                if (ids.stream().noneMatch(liveIds::contains)) continue;
                String youtubeId = row.getKey();
                boolean exists = switch (types[i]) {
                    case "channel" -> channels.findByYoutubeId(youtubeId).isPresent();
                    case "playlist" -> playlists.findByYoutubeId(youtubeId).isPresent();
                    default -> videos.findByYoutubeId(youtubeId).isPresent();
                };
                if (!exists) {
                    changes.add(new Change(youtubeId, types[i], youtubeId, null, List.of(), null, ids, defaultStatus));
                }
            }
        }
        return changes;
    }

    /** Row 17 (§4.2): setting LIVE on a non-empty category requires every member to conform. */
    public void checkMembersForLive(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        LiveCategoryService.Members m = liveCategories.members(categoryId);
        int count = m.ordered().size();
        if (count > MAX_ENTRIES) {                                             // the cap (R7), before any read
            throw new LiveCategoryException(HttpStatus.BAD_REQUEST, LiveCategoryException.FULL,
                    "This category holds " + count + " items; the live category takes at most " + MAX_ENTRIES,
                    Map.of("count", count, "max", MAX_ENTRIES));
        }
        List<String> into = List.of(categoryId);
        List<Change> changes = new ArrayList<>();
        m.channels().forEach(c -> changes.add(new Change(c.getId(), "channel", c.getYoutubeId(), null, List.of(), c.getStatus(), into, c.getStatus())));
        m.playlists().forEach(p -> changes.add(new Change(p.getId(), "playlist", p.getYoutubeId(), null, List.of(), p.getStatus(), into, p.getStatus())));
        m.videos().forEach(v -> changes.add(new Change(v.getId(), "video", v.getYoutubeId(), v.getChannelId(), List.of(), v.getStatus(), into, v.getStatus())));
        Map<String, Set<String>> entries = new HashMap<>(Map.of(categoryId, keys(m)));   // the members ARE the entries
        List<Offender> refused = new ArrayList<>();
        Map<String, String> verified = run(true, changes, Set.of(categoryId), entries, refused);
        if (!refused.isEmpty()) {
            LiveCategoryException e = envelope(refused);
            if (e.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) {             // only failed checks: retry, not bad members
                throw e;
            }
            throw new LiveCategoryException(HttpStatus.BAD_REQUEST, LiveCategoryException.MEMBERS_INVALID,
                    "This category holds items that aren't channels or live streams", e.getDetails());
        }
        for (Video v : m.videos()) {                                           // D13: a link needs its channel id
            String channel = verified.get(v.getId());
            if (channel != null && !channel.equals(v.getChannelId())) {
                // Re-read: the members were read before the YouTube checks; only channelId changes.
                // ponytail: read-then-set, not a transaction; a single-field update if this ever races in practice.
                Optional<Video> fresh = videos.findById(v.getId());
                if (fresh.isPresent()) {
                    fresh.get().setChannelId(channel);
                    videos.save(fresh.get());
                }
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

    /**
     * The two passes and the seeds (class doc). Refusals land in {@code refused}; the returned map is ref → channel.
     * {@code entries}: per LIVE category, the type:youtubeId keys of its members, read once per request on first use.
     */
    private Map<String, String> run(boolean admin, List<Change> changes, Set<String> liveIds, Map<String, Set<String>> entries,
                                    List<Offender> refused) throws ExecutionException, InterruptedException, TimeoutException {
        List<Change> adds = new ArrayList<>();
        for (Change c : changes) {                                             // pass 1: no YouTube read
            try {
                if (admits(admin, c, liveIds, entries)) {
                    adds.add(c);
                }
            } catch (LiveCategoryException e) {
                refused.add(new Offender(c.ref(), e));
            }
        }
        Map<String, String> verified = new LinkedHashMap<>();
        if (!refused.isEmpty()) {
            return verified;
        }
        for (Change c : adds) {                                                // pass 2: the Live tab
            if ("video".equals(c.type())) {
                try {
                    verified.put(c.ref(), liveChannel(c));
                } catch (LiveCategoryException e) {
                    refused.add(new Offender(c.ref(), e));
                }
            }
        }
        if (refused.isEmpty()) {
            Set<String> seeds = new LinkedHashSet<>();                         // rule 3 (D15): once per channel, once verified
            for (Change c : adds) {
                if ("channel".equals(c.type()) && c.youtubeId() != null) {
                    seeds.add(c.youtubeId());
                }
            }
            seeds.forEach(live::seed);
        }
        return verified;
    }

    /** Pass 1 for one row: false when it adds no LIVE id (rule 1); throws for rules 2 and 4 and the cap. */
    private boolean admits(boolean admin, Change c, Set<String> liveIds, Map<String, Set<String>> entries)
            throws ExecutionException, InterruptedException, TimeoutException {
        Set<String> added = addedLive(c, liveIds);
        if (added.isEmpty()) {
            return false;                                                      // rule 1
        }
        if (!admin) {                                                          // rule 2
            throw refusal(HttpStatus.FORBIDDEN, LiveCategoryException.ADMIN_ONLY,
                    "Only admins can add to or publish into the live category", c);
        }
        if (!"channel".equals(c.type()) && !"video".equals(c.type())) {        // rule 4
            throw refusal(HttpStatus.BAD_REQUEST, LiveCategoryException.TYPE_NOT_ALLOWED,
                    "The live category accepts only channels and live streams", c);
        }
        for (String cat : added) {                                             // the cap (R7), over the poller's members
            Set<String> members = entries.get(cat);
            if (members == null) {
                // ponytail: members() is ~3 doc reads per member (≤ ~90 at the cap), once per LIVE category per request;
                // a stored member count if these writes ever get hot.
                members = keys(liveCategories.members(cat));
                entries.put(cat, members);
            }
            members.add(c.type() + ":" + c.youtubeId());                       // a doc already in the category is no new entry
            if (members.size() > MAX_ENTRIES) {
                throw refusal(HttpStatus.BAD_REQUEST, LiveCategoryException.FULL,
                        "The live category is full (" + MAX_ENTRIES + " entries)", c);
            }
        }
        return true;
    }

    /** Pass 2 for one video, rule 5 (D10): its channel, then the video must be LIVE on that channel's Live tab now. */
    private String liveChannel(Change c) {
        String channel = LiveTabReader.isChannelId(c.channelId()) ? c.channelId() : null;   // a non-UC id counts as missing
        if (channel == null) {
            try {
                channel = live.resolveChannelId(c.youtubeId()).orElse(null);
            } catch (IOException e) {
                log.warn("live guard: channel lookup for video {} failed ({}); answering 503", c.youtubeId(), e.getMessage());
                throw unavailable(c.youtubeId(), Map.of("youtubeId", String.valueOf(c.youtubeId())));
            }
        }
        if (channel == null) {
            throw refusal(HttpStatus.BAD_REQUEST, LiveCategoryException.CHANNEL_UNKNOWN,
                    "The channel of video " + c.youtubeId() + " could not be found", c);
        }
        Map<String, Object> details = Map.of("youtubeId", String.valueOf(c.youtubeId()), "channelId", channel);
        LiveTabResult read = live.livesNow(channel);
        boolean unknown = switch (read.outcome()) {                            // exhaustive: a new outcome must pick a side
            case OK, TERMINATED -> false;                                      // TERMINATED is an empty read: NOT_LIVE below
            case ERROR, RATE_LIMITED, BREAKER -> true;                         // R7: no signal either way
        };
        if (unknown) {
            throw unavailable(c.youtubeId(), details);
        }
        if (read.lives().stream().noneMatch(l -> l.id().equals(c.youtubeId()))) {
            throw new LiveCategoryException(HttpStatus.BAD_REQUEST, LiveCategoryException.NOT_LIVE,
                    "Video " + c.youtubeId() + " is not live on channel " + channel + " now", details);
        }
        return channel;
    }

    /** One key per member, as a {@link Change} names it: type:youtubeId. */
    private static Set<String> keys(LiveCategoryService.Members m) {
        Set<String> keys = new HashSet<>();
        m.channels().forEach(x -> keys.add("channel:" + x.getYoutubeId()));
        m.playlists().forEach(x -> keys.add("playlist:" + x.getYoutubeId()));
        m.videos().forEach(x -> keys.add("video:" + x.getYoutubeId()));
        return keys;
    }

    private static LiveCategoryException unavailable(String youtubeId, Map<String, Object> details) {
        return new LiveCategoryException(HttpStatus.SERVICE_UNAVAILABLE, LiveCategoryException.CHECK_UNAVAILABLE,
                "Couldn't confirm video " + youtubeId + " is live; try again later", details);
    }

    /** D9's one refusal: the first 4xx offender's status and code (a definitive refusal outranks a failed check), else the first. */
    private static LiveCategoryException envelope(List<Offender> refused) {
        LiveCategoryException headline = refused.stream().map(Offender::refusal)
                .filter(e -> e.getStatus().is4xxClientError()).findFirst().orElse(refused.get(0).refusal());
        List<Map<String, Object>> offenders = refused.stream().map(o -> Map.<String, Object>of(
                "ref", String.valueOf(o.ref()), "code", o.refusal().getCode(), "message", o.refusal().getMessage())).toList();
        return new LiveCategoryException(headline.getStatus(), headline.getCode(),
                refused.size() + " item(s) refused for the live category; nothing was saved", Map.of("offenders", offenders));
    }

    private static LiveCategoryException refusal(HttpStatus status, String code, String message, Change c) {
        return new LiveCategoryException(status, code, message,
                Map.of("ref", String.valueOf(c.ref()), "type", String.valueOf(c.type())));
    }
}
