package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.CatalogChanged;
import com.google.cloud.Timestamp;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Every registry row of every status, in memory, for the admin Content Library search.
 *
 * <p>Firestore has no substring search, and the public {@code SearchIndex} holds approved public
 * content only. The registry is a few thousand rows, so the snapshot is read once (paged, no
 * ceiling) and then kept current by the {@link CatalogChanged} event every catalog write publishes:
 * the next search re-reads just the rows written since. A write that failed or timed out may still
 * commit later, so its row is re-read on every search for {@link #LATE_COMMIT_MS}. Rows written
 * outside those paths (a console edit) are picked up by the six-hourly reload.</p>
 *
 * <p>Like the browse listing, a row without createdAt is not listed (Firestore's createdAt order
 * leaves it out).</p>
 */
@Component
public class LibrarySearchSnapshot {

    private static final int LOAD_PAGE = 500;
    private static final long LATE_COMMIT_MS = 120_000;

    /** A page of rows in createdAt order, strictly after the given position (null = from the start). */
    @FunctionalInterface
    public interface PageRead<T> {
        List<T> fetch(Timestamp afterCreatedAt, String afterId, int limit)
                throws ExecutionException, InterruptedException, TimeoutException;
    }

    @FunctionalInterface
    interface IdRead<T> {
        Map<String, T> fetch(List<String> ids) throws ExecutionException, InterruptedException, TimeoutException;
    }

    /** The rows of each type whose text contains the term, filtered by status and category (null = any). */
    public record Matches(List<Channel> channels, List<Playlist> playlists, List<Video> videos) {
    }

    private final Table<Channel> channels;
    private final Table<Playlist> playlists;
    private final Table<Video> videos;

    public LibrarySearchSnapshot(ChannelRepository c, PlaylistRepository p, VideoRepository v) {
        channels = new Table<>((a, id, n) -> c.findPageAfter(null, null, false, a, id, n), c::findAllByIds,
                Channel::getId, Channel::getCreatedAt, Channel::getStatus, Channel::getCategoryIds,
                ch -> text(ch.getName(), ch.getDescription(), ch.getKeywords()));
        playlists = new Table<>((a, id, n) -> p.findPageAfter(null, null, false, a, id, n), p::findAllByIds,
                Playlist::getId, Playlist::getCreatedAt, Playlist::getStatus, Playlist::getCategoryIds,
                pl -> text(pl.getTitle(), pl.getDescription(), pl.getKeywords()));
        videos = new Table<>((a, id, n) -> v.findPageAfter(null, null, false, a, id, n), v::findAllByIds,
                Video::getId, Video::getCreatedAt, Video::getStatus, Video::getCategoryIds,
                vi -> text(vi.getTitle(), vi.getDescription(), vi.getKeywords()));
    }

    public synchronized Matches find(String term, String status, String category)
            throws ExecutionException, InterruptedException, TimeoutException {
        String q = term.toLowerCase(Locale.ROOT);
        return new Matches(channels.find(q, status, category), playlists.find(q, status, category),
                videos.find(q, status, category));
    }

    @EventListener
    public void onCatalogChanged(CatalogChanged event) {
        Table<?> table = switch (event.type()) {
            case "CHANNEL" -> channels;
            case "PLAYLIST" -> playlists;
            case "VIDEO" -> videos;
            default -> null;
        };
        if (table == null || event.docId() == null) return;
        long until = event.writeFailed() ? System.currentTimeMillis() + LATE_COMMIT_MS : 0;
        // Always a new Mark, so a refresh that read before this event cannot clear it.
        table.dirty.merge(event.docId(), new Mark(until), (old, now) -> new Mark(Math.max(old.until, now.until)));
    }

    @Scheduled(fixedDelay = 6, initialDelay = 6, timeUnit = TimeUnit.HOURS)
    public void reload() {
        channels.loaded = false;
        playlists.loaded = false;
        videos.loaded = false;
    }

    private static String text(String title, String description, List<String> keywords) {
        // NUL between fields, so a term never matches across two of them.
        StringJoiner joined = new StringJoiner("\u0000");
        if (title != null) joined.add(title);
        if (description != null) joined.add(description);
        if (keywords != null) keywords.stream().filter(Objects::nonNull).forEach(joined::add);
        return joined.toString().toLowerCase(Locale.ROOT);
    }

    /** Identity, not value: see {@link #onCatalogChanged}. */
    private static final class Mark {
        final long until;

        Mark(long until) {
            this.until = until;
        }
    }

    private record Entry<T>(T row, String text) {
    }

    /** One type's rows. {@code rows} is guarded by the snapshot's lock; {@code dirty} is written by any thread. */
    private static final class Table<T> {
        final Map<String, Entry<T>> rows = new HashMap<>();
        final Map<String, Mark> dirty = new ConcurrentHashMap<>();
        volatile boolean loaded;

        private final PageRead<T> pages;
        private final IdRead<T> byIds;
        private final Function<T, String> id;
        private final Function<T, Timestamp> createdAt;
        private final Function<T, String> status;
        private final Function<T, List<String>> categories;
        private final Function<T, String> text;

        Table(PageRead<T> pages, IdRead<T> byIds, Function<T, String> id, Function<T, Timestamp> createdAt,
              Function<T, String> status, Function<T, List<String>> categories, Function<T, String> text) {
            this.pages = pages;
            this.byIds = byIds;
            this.id = id;
            this.createdAt = createdAt;
            this.status = status;
            this.categories = categories;
            this.text = text;
        }

        List<T> find(String q, String st, String cat) throws ExecutionException, InterruptedException, TimeoutException {
            if (!loaded) load();
            refresh();
            List<T> out = new ArrayList<>();
            for (Entry<T> e : rows.values()) {
                T r = e.row();
                if (!e.text().contains(q)) continue;
                if (st != null && !st.equals(status.apply(r))) continue;
                if (cat != null && (categories.apply(r) == null || !categories.apply(r).contains(cat))) continue;
                out.add(r);
            }
            return out;
        }

        /** Every row, page by page. Dirty marks are kept: a write during the read is re-read by the refresh after it. */
        private void load() throws ExecutionException, InterruptedException, TimeoutException {
            // Set before the read, so a reload() during it (which clears it) is not overwritten.
            loaded = true;
            Map<String, Entry<T>> fresh = new HashMap<>();
            Timestamp afterAt = null;
            String afterId = null;
            try {
                while (true) {
                    List<T> page = pages.fetch(afterAt, afterId, LOAD_PAGE);
                    for (T r : page) fresh.put(id.apply(r), new Entry<>(r, text.apply(r)));
                    if (page.size() < LOAD_PAGE) break;
                    T last = page.get(page.size() - 1);
                    afterAt = createdAt.apply(last);
                    afterId = id.apply(last);
                }
            } catch (ExecutionException | InterruptedException | TimeoutException | RuntimeException e) {
                loaded = false;
                throw e;
            }
            rows.clear();
            rows.putAll(fresh);
        }

        /** Re-read the rows written since the last search. On a failed read the marks stay for the next one. */
        private void refresh() throws ExecutionException, InterruptedException, TimeoutException {
            Map<String, Mark> seen = new HashMap<>(dirty);
            if (seen.isEmpty()) return;
            Map<String, T> read = byIds.fetch(new ArrayList<>(seen.keySet()));
            long now = System.currentTimeMillis();
            seen.forEach((key, mark) -> {
                T r = read.get(key);
                if (r == null || createdAt.apply(r) == null) rows.remove(key);
                else rows.put(key, new Entry<>(r, text.apply(r)));
                if (mark.until <= now) dirty.remove(key, mark);
            });
        }
    }
}
