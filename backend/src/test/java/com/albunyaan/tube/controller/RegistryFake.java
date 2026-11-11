package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.mockito.invocation.InvocationOnMock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;

/**
 * An in-memory registry behind the three repository mocks, answering the Library's queries the
 * way Firestore does: filter, order by createdAt then document id, resume strictly after, limit.
 */
final class RegistryFake {

    final List<Channel> channels = new ArrayList<>();
    final List<Playlist> playlists = new ArrayList<>();
    final List<Video> videos = new ArrayList<>();
    /** Channel rows the listing queries returned, summed: what Firestore would bill. */
    int channelRowsRead;

    /** createdAt repeats across and within types and steps below a millisecond, so ties and precision both matter. */
    static Timestamp at(int i) {
        return Timestamp.ofTimeSecondsAndNanos(1_700_000_000L + (i / 3), (i % 3) * 1_000);
    }

    static <T> Comparator<T> order(Function<T, Timestamp> at, Function<T, String> id, boolean oldest) {
        Comparator<T> asc = Comparator.comparing(at).thenComparing(id);
        return oldest ? asc : asc.reversed();
    }

    void stub(ChannelRepository c, PlaylistRepository p, VideoRepository v) throws Exception {
        lenient().when(c.findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt()))
                .thenAnswer(inv -> {
                    List<Channel> page = page(channels, inv, Channel::getStatus, Channel::getCategoryIds, Channel::getCreatedAt, Channel::getId);
                    channelRowsRead += page.size();
                    return page;
                });
        lenient().when(p.findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt()))
                .thenAnswer(inv -> page(playlists, inv, Playlist::getStatus, Playlist::getCategoryIds, Playlist::getCreatedAt, Playlist::getId));
        lenient().when(v.findPageAfter(any(), any(), anyBoolean(), any(), any(), anyInt()))
                .thenAnswer(inv -> page(videos, inv, Video::getStatus, Video::getCategoryIds, Video::getCreatedAt, Video::getId));
        lenient().when(c.findAllByIds(anyList())).thenAnswer(inv -> byId(channels, inv, Channel::getId));
        lenient().when(p.findAllByIds(anyList())).thenAnswer(inv -> byId(playlists, inv, Playlist::getId));
        lenient().when(v.findAllByIds(anyList())).thenAnswer(inv -> byId(videos, inv, Video::getId));
    }

    private static <T> Map<String, T> byId(List<T> rows, InvocationOnMock inv, Function<T, String> id) {
        List<String> ids = inv.getArgument(0);
        Map<String, T> out = new HashMap<>();
        for (T r : rows) if (ids.contains(id.apply(r))) out.put(id.apply(r), r);
        return out;
    }

    private static <T> List<T> page(List<T> rows, InvocationOnMock inv, Function<T, String> status,
                                    Function<T, List<String>> categories, Function<T, Timestamp> at, Function<T, String> id) {
        String st = inv.getArgument(0);
        String cat = inv.getArgument(1);
        boolean oldest = inv.getArgument(2);
        Timestamp afterAt = inv.getArgument(3);
        String afterId = inv.getArgument(4);
        int limit = inv.getArgument(5);
        return rows.stream()
                .filter(r -> st == null || st.equals(status.apply(r)))
                .filter(r -> cat == null || (categories.apply(r) != null && categories.apply(r).contains(cat)))
                .sorted(order(at, id, oldest))
                .filter(r -> {
                    if (afterAt == null) return true;
                    int cmp = at.apply(r).compareTo(afterAt);
                    if (cmp == 0) cmp = id.apply(r).compareTo(afterId);
                    return oldest ? cmp > 0 : cmp < 0;
                })
                .limit(limit)
                .toList();
    }
}
