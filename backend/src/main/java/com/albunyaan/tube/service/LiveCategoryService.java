package com.albunyaan.tube.service;

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

import java.util.ArrayList;
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

    private static List<String> idsOf(List<CategoryContentOrder> rows, String type) {
        return rows.stream().filter(r -> type.equals(r.getContentType())).map(CategoryContentOrder::getContentId).toList();
    }
}
