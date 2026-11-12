package com.albunyaan.tube.repository;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * A limit-L call is the first L of the full sorted union, so Home (small limit) and the tab (no limit) list a
 * parent's categorized items in the same order. Subcategory "a" holds the 7 biggest items, "b" one small one.
 */
class FindByCategoryIdsLimitTest {

    private static final List<String> IDS = List.of("p", "a", "b");
    /** Per category: doc ids, best first (the order the per-category Firestore query returns them in). */
    private static final Map<String, List<String>> BY_CATEGORY =
            Map.of("p", List.of(), "a", List.of("a7", "a6", "a5", "a4", "a3", "a2", "a1"), "b", List.of("b0"));

    private static <T> List<T> top(List<String> ids, int limit, java.util.function.Function<String, T> doc) {
        return new ArrayList<>(ids.stream().limit(limit).map(doc).toList());
    }

    /** a7 ranks highest (7), b0 lowest (0). */
    private static int rank(String id) {
        return id.charAt(1) - '0';
    }

    private static List<String> idsOf(List<?> docs) {
        return docs.stream().map(d -> d instanceof Channel c ? c.getId()
                : d instanceof Playlist p ? p.getId() : ((Video) d).getId()).toList();
    }

    @Test
    void channelsLimitIsTheHeadOfTheFullUnion() throws Exception {
        ChannelRepository repo = spy(new ChannelRepository(mock(Firestore.class), new FirestoreTimeoutProperties(),
                mock(ApplicationEventPublisher.class)));
        for (String cat : IDS) {
            doAnswer(inv -> top(BY_CATEGORY.get(cat), inv.getArgument(1), id -> {
                Channel c = new Channel("yt-" + id);
                c.setId(id);
                c.setSubscribers((long) rank(id));
                return c;
            })).when(repo).findByCategoryOrderBySubscribersDesc(eq(cat), anyInt());
        }
        List<String> all = idsOf(repo.findByCategoryIds(IDS, Integer.MAX_VALUE));
        assertEquals(List.of("a7", "a6", "a5", "a4", "a3", "a2", "a1", "b0"), all);
        assertEquals(all.subList(0, 6), idsOf(repo.findByCategoryIds(IDS, 6)));
    }

    @Test
    void playlistsLimitIsTheHeadOfTheFullUnion() throws Exception {
        PlaylistRepository repo = spy(new PlaylistRepository(mock(Firestore.class), new FirestoreTimeoutProperties(),
                mock(ApplicationEventPublisher.class)));
        // Newest first is the reverse of most items first: a fetch ordered by createdAt keeps the wrong top L.
        java.util.function.Function<String, Playlist> doc = id -> {
            Playlist p = new Playlist("yt-" + id);
            p.setId(id);
            p.setItemCount(rank(id));
            return p;
        };
        for (String cat : IDS) {
            doAnswer(inv -> top(BY_CATEGORY.get(cat), inv.getArgument(1), doc))
                    .when(repo).findByCategoryOrderByItemCountDesc(eq(cat), anyInt());
            List<String> newestFirst = new ArrayList<>(BY_CATEGORY.get(cat));
            java.util.Collections.reverse(newestFirst);
            doAnswer(inv -> top(newestFirst, inv.getArgument(1), doc))
                    .when(repo).findByCategoryId(eq(cat), anyInt());
        }
        List<String> all = idsOf(repo.findByCategoryIds(IDS, Integer.MAX_VALUE));
        assertEquals(all.subList(0, 6), idsOf(repo.findByCategoryIds(IDS, 6)));
    }

    @Test
    void videosLimitIsTheHeadOfTheFullUnion() throws Exception {
        VideoRepository repo = spy(new VideoRepository(mock(Firestore.class), new FirestoreTimeoutProperties(),
                mock(ApplicationEventPublisher.class)));
        for (String cat : IDS) {
            doAnswer(inv -> top(BY_CATEGORY.get(cat), inv.getArgument(1), id -> {
                Video v = new Video("yt-" + id);
                v.setId(id);
                v.setUploadedAt(Timestamp.ofTimeSecondsAndNanos(1_700_000_000L + rank(id), 0));
                return v;
            })).when(repo).findByCategoryOrderByUploadedAtDesc(eq(cat), anyInt());
        }
        List<String> all = idsOf(repo.findByCategoryIds(IDS, Integer.MAX_VALUE));
        assertEquals(all.subList(0, 6), idsOf(repo.findByCategoryIds(IDS, 6)));
    }
}
