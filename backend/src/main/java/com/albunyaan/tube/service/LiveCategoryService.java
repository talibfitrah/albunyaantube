package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.ContentItemMapper;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** LIVE-category reads: members, the serving filters (R11), the expansion (Task 5) and the admin status (Task 12). */
@Service
public class LiveCategoryService {

    public enum Served { SERVED, EXCLUDED, BLOCKED }

    /** A category's members in display order: order rows first, then members without a row (today's fallback, F16). */
    public record Members(List<Object> ordered) {
        public List<Channel> channels() { return of(Channel.class); }
        public List<Video> videos() { return of(Video.class); }
        public List<Playlist> playlists() { return of(Playlist.class); }
        private <T> List<T> of(Class<T> type) { return ordered.stream().filter(type::isInstance).map(type::cast).toList(); }
    }

    static final int MEMBER_QUERY_LIMIT = 100;

    private final CategoryRepository categories;
    private final CategoryContentOrderRepository orders;
    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;
    private final LiveChannelService live;

    public LiveCategoryService(CategoryRepository categories, CategoryContentOrderRepository orders,
                               ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos,
                               LiveChannelService live) {
        this.categories = categories;
        this.orders = orders;
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
        this.live = live;
    }

    public Set<String> liveCategoryIds() throws ExecutionException, InterruptedException, TimeoutException {
        return categories.findAll().stream().filter(Category::isLive).map(Category::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isLive(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        return categoryId != null && categories.findById(categoryId).map(Category::isLive).orElse(false);
    }

    public Members members(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        List<CategoryContentOrder> rows = orders.findByCategoryIdOrderByPosition(categoryId);
        Map<String, Channel> channelMap = channels.findAllByIds(idsOf(rows, "channel"));
        Map<String, Playlist> playlistMap = playlists.findAllByIds(idsOf(rows, "playlist"));
        Map<String, Video> videoMap = videos.findAllByIds(idsOf(rows, "video"));
        List<Object> ordered = new ArrayList<>();
        for (CategoryContentOrder row : rows) {
            Object member = switch (row.getContentType()) {
                case "channel" -> channelMap.get(row.getContentId());
                case "playlist" -> playlistMap.get(row.getContentId());
                case "video" -> videoMap.get(row.getContentId());
                default -> null;
            };
            if (member != null) {
                ordered.add(member);
            }
        }
        for (Channel c : channels.findByCategoryIdAllStatus(categoryId, MEMBER_QUERY_LIMIT)) {
            if (!channelMap.containsKey(c.getId())) ordered.add(c);
        }
        for (Playlist p : playlists.findByCategoryIdAllStatus(categoryId, MEMBER_QUERY_LIMIT)) {
            if (!playlistMap.containsKey(p.getId())) ordered.add(p);
        }
        for (Video v : videos.findByCategoryIdAllStatus(categoryId, MEMBER_QUERY_LIMIT)) {
            if (!videoMap.containsKey(v.getId())) ordered.add(v);
        }
        return new Members(ordered);
    }

    /**
     * Spec §6: the category's public items. A channel entry expands into one VIDEO per current live (R3; OFFLINE and
     * UNKNOWN hidden, ERROR and suspect keep their last lives); a link is served as stored (D11) when it carries a
     * channel id (D13); duplicates keep their first position (D4). Every served id answers 200 or 404 on the public
     * gate, never 410 (R11): one batched registry lookup per render, chunked by 30.
     */
    public List<ContentItemDto> items(String categoryId) throws ExecutionException, InterruptedException, TimeoutException {
        Members m = members(categoryId);
        Map<String, LiveChannelState> snapshot = live.snapshot();   // one read: the lookup and the filter see the same lives
        Set<String> liveIds = new LinkedHashSet<>();
        for (Channel c : m.channels()) {
            LiveChannelState s = c.getYoutubeId() == null ? null : snapshot.get(c.getYoutubeId());
            if (s != null && LiveChannelState.LIVE.equals(s.getStatus())) {
                s.getLives().forEach(l -> liveIds.add(l.getVideoId()));
            }
        }
        Map<String, Video> docs = liveIds.isEmpty() ? Map.of() : videos.findByYoutubeIds(liveIds);
        List<ContentItemDto> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object member : m.ordered()) {
            if (member instanceof Channel c && servable(c) && c.getYoutubeId() != null) {
                for (LiveChannelState.Live l : servedLives(c, snapshot.get(c.getYoutubeId()), docs)) {
                    if (seen.add(l.getVideoId())) {
                        out.add(ContentItemMapper.fromLive(c, l.getVideoId(), l.getTitle()));
                    }
                }
            } else if (member instanceof Video v && servable(v) && v.getChannelId() != null && seen.add(v.getYoutubeId())) {
                out.add(ContentItemMapper.fromVideo(v));
            }
        }
        return out;
    }

    /** The public filters (PublicContentService.isApproved && isAvailable): APPROVED, public, not UNAVAILABLE/ARCHIVED. */
    public static boolean servable(Channel c) {
        return "APPROVED".equals(c.getStatus()) && VisibilityPolicy.isPublic(c.getVisibility())
                && c.getValidationStatus() != ValidationStatus.UNAVAILABLE
                && c.getValidationStatus() != ValidationStatus.ARCHIVED;
    }

    public static boolean servable(Video v) {
        return "APPROVED".equals(v.getStatus()) && VisibilityPolicy.isPublic(v.getVisibility())
                && v.getValidationStatus() != ValidationStatus.UNAVAILABLE
                && v.getValidationStatus() != ValidationStatus.ARCHIVED;
    }

    /** F15: the public gate answers 410 for a registry doc that is not APPROVED, or is UNAVAILABLE/ARCHIVED. */
    public static boolean wouldDraw410(Video doc) {
        return !"APPROVED".equals(doc.getStatus())
                || doc.getValidationStatus() == ValidationStatus.UNAVAILABLE
                || doc.getValidationStatus() == ValidationStatus.ARCHIVED;
    }

    /** R11: one filter for the expansion, the public live endpoint and the admin status. */
    public static Served served(Channel channel, String videoId, Map<String, Video> docs) {
        Channel.ExcludedItems ex = channel.getExcludedItems();
        if (ex != null && (ex.getLiveStreams().contains(videoId) || ex.getVideos().contains(videoId))) {
            return Served.EXCLUDED;
        }
        Video doc = docs.get(videoId);
        return doc != null && wouldDraw410(doc) ? Served.BLOCKED : Served.SERVED;
    }

    /** R11, pre-flight D10: a channel entry's lives that are served now (state LIVE, then the one filter). */
    public static List<LiveChannelState.Live> servedLives(Channel channel, LiveChannelState state, Map<String, Video> docs) {
        if (state == null || !LiveChannelState.LIVE.equals(state.getStatus())) {
            return List.of();
        }
        return state.getLives().stream().filter(l -> served(channel, l.getVideoId(), docs) == Served.SERVED).toList();
    }

    /** Spec §4.2: per LIVE category, every channel entry's live state and every link's miss count, for the admin. */
    public Map<String, Object> status() throws ExecutionException, InterruptedException, TimeoutException {
        Map<String, LiveChannelState> snapshot = live.snapshot();   // one read: every entry sees the same states
        List<Map<String, Object>> categoriesOut = new ArrayList<>();
        for (String categoryId : liveCategoryIds()) {
            Members m = members(categoryId);
            Set<String> liveIds = new LinkedHashSet<>();
            for (Channel c : m.channels()) {
                LiveChannelState s = c.getYoutubeId() == null ? null : snapshot.get(c.getYoutubeId());
                if (s != null) s.getLives().forEach(l -> liveIds.add(l.getVideoId()));
            }
            Map<String, Video> docs = liveIds.isEmpty() ? Map.of() : videos.findByYoutubeIds(liveIds);
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Object member : m.ordered()) {
                if (member instanceof Channel c) entries.add(channelEntry(c, docs, snapshot));
                else if (member instanceof Video v) entries.add(linkEntry(v, snapshot));
            }
            Map<String, Object> category = new LinkedHashMap<>();
            category.put("id", categoryId);
            category.put("entries", entries);
            categoriesOut.add(category);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("suspectSince", iso(live.suspectSince()));
        out.put("categories", categoriesOut);
        return out;
    }

    private Map<String, Object> channelEntry(Channel c, Map<String, Video> docs, Map<String, LiveChannelState> snapshot) {
        LiveChannelState s = c.getYoutubeId() == null ? null : snapshot.get(c.getYoutubeId());
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("contentType", "channel");
        e.put("contentId", c.getId());
        e.put("channelId", c.getYoutubeId());
        e.put("name", c.getName());
        e.put("servable", servable(c));   // false: items() drops this entry whole (its lives show BLOCKED)
        e.put("status", s == null ? LiveChannelState.UNKNOWN : s.getStatus());
        e.put("reason", s == null ? null : s.getReason());
        List<Map<String, Object>> lives = new ArrayList<>();
        if (s != null) {
            for (LiveChannelState.Live l : s.getLives()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", l.getVideoId());
                item.put("title", l.getTitle());
                item.put("served", (servable(c) ? served(c, l.getVideoId(), docs) : Served.BLOCKED).name());
                lives.add(item);
            }
        }
        e.put("lives", lives);
        e.put("checkedAt", iso(s == null ? null : s.getCheckedAt()));
        e.put("offlineSince", iso(s == null ? null : s.getOfflineSince()));
        e.put("errorSince", iso(s == null ? null : s.getErrorSince()));
        e.put("lastError", s == null ? null : s.getLastError());
        return e;
    }

    private Map<String, Object> linkEntry(Video v, Map<String, LiveChannelState> snapshot) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("contentType", "video");
        e.put("contentId", v.getId());
        e.put("youtubeId", v.getYoutubeId());
        e.put("channelId", v.getChannelId());
        LiveChannelState s = v.getChannelId() == null ? null : snapshot.get(v.getChannelId());
        LiveChannelState.Miss miss = s == null ? null : s.getMisses().get(v.getYoutubeId());
        e.put("status", v.getChannelId() == null ? "NO_CHANNEL" : !servable(v) ? "BLOCKED" : miss == null ? "LIVE" : "MISSING");
        e.put("misses", miss == null ? 0 : miss.getCount());
        e.put("missingSince", iso(miss == null ? null : miss.getSince()));
        return e;
    }

    private static String iso(Long epochMs) {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs).toString();
    }

    private static List<String> idsOf(List<CategoryContentOrder> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.getContentType())).map(CategoryContentOrder::getContentId).toList();
    }
}
