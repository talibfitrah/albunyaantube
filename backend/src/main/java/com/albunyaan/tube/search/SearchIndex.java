package com.albunyaan.tube.search;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.ContentItemMapper;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.VisibilityPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.QueryTimeout;
import org.apache.lucene.index.QueryTimeoutImpl;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherLifetimeManager;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * In-process Lucene index over every searchable item (spec 2026-10-13 §3-§5): admitted channels,
 * playlists and videos plus `searchable_streams`. One writer thread owns every mutation; searches
 * run concurrently on near-real-time searchers.
 */
@Component
public class SearchIndex {

    public static final String MATCH_HEADER = "X-Search-Match";
    static final int MIN_CONFIDENT_HITS = 3;
    static final int STREAM_PAGE = 500;
    private static final String[] TIER_NAMES = {"", "exact", "approximate", "related"};
    /** Relevance, then `key`: tied hits keep one order across page fetches even when a save re-appends one (F9). */
    private static final Sort RELEVANCE = new Sort(SortField.FIELD_SCORE, new SortField("key", SortField.Type.STRING));
    /** How long a superseded in-tab search snapshot stays usable for later pages; then they run on the current index. Tests lower it. */
    double snapshotTtlSec = 600;
    /** At most this many snapshots are kept by each prune, newest first (review I1). */
    private static final int MAX_SNAPSHOTS = 64;
    private static final long FIRST_RETRY_MS = 30_000;
    private static final long MAX_RETRY_MS = 300_000;
    /** R30: the inner pass's time budget per search; partial hits on expiry. */
    static final long INNER_TIMEOUT_MS = 40;
    /** R35: an item this long or shorter is a Short. */
    private static final int SHORT_MAX_SECONDS = 60;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    /**
     * One answer: the items, the tier of the first one (`exact`/`approximate`/`related`/`none`), and the index
     * snapshot {@link #searchPage} ran on (-1 for {@link #search}).
     */
    public record Hits(List<ContentItemDto> items, String match, long snapshot) {
        public static final Hits NONE = new Hits(List.of(), "none");

        public Hits(List<ContentItemDto> items, String match) {
            this(items, match, -1);
        }
    }

    /**
     * {@link #titles}' answer. `complete` is false when a later ask may find more originals: the inner budget ran out, the
     * inner index failed, or it does not serve yet while some id still has no title. `/titles` then caches it briefly.
     */
    public record Titles(Map<String, String> map, boolean complete) {}

    /** The first catalog build has not finished: the API answers 503 + Retry-After (spec §7). */
    public static class NotReadyException extends RuntimeException {
        public NotReadyException() {
            super("search index is still building");
        }
    }

    /**
     * An admitted channel or playlist: its display name (the inner index's channel name, R16), the ids it excludes, and
     * a channel's category ids (Q18, {@link #liveOnly}); none for a playlist.
     */
    record Admitted(String name, Set<String> excluded, List<String> categoryIds) {}

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;
    private final SearchableStreamRepository streams;
    private final CategoryRepository categories;
    private final Directory dir;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    /** The searcher each in-tab search's first page ran on, by reader version: its later pages run there too. Tests swap it. */
    SearcherLifetimeManager snapshots = new SearcherLifetimeManager();
    private final long readyWaitMs;
    /** Null where no inner index exists (tests); never null in the app context. */
    private final InnerIndex inner;
    /** Tests lower it to force a timeout. */
    long innerTimeoutMs = INNER_TIMEOUT_MS;
    /** A budget of `innerTimeoutMs`; tests script one that runs out at a chosen check. */
    LongFunction<QueryTimeout> newTimeout = QueryTimeoutImpl::new;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final CountDownLatch built = new CountDownLatch(1);
    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "search-index-writer");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long buildStartedAt;
    private volatile String catalogCounts = "";
    private final AtomicBoolean tragicLogged = new AtomicBoolean();
    /** Delays of the re-read retries after a failed one (F3); tests shorten them. */
    long[] rereadRetryMs = {2_000, 10_000, 60_000};
    /** Follow-up re-reads after a failed or timed-out write, which may commit after the first re-read (B2); tests shorten them. */
    long[] lateCommitRereadMs = {15_000, 60_000};

    // Query-time stream visibility (spec §4.2). Replaced or mutated only on the writer thread.
    /** Admitted channel/playlist source keys ("channel:UC…") -> name and the video ids that source excludes. */
    private volatile Map<String, Admitted> admittedSources = new ConcurrentHashMap<>();
    /** youtubeId of every Video document, admitted or not: such an id never shows as a stream. */
    private volatile Set<String> videoIds = ConcurrentHashMap.newKeySet();
    /** Ids of the LIVE categories (Q18), read with each catalog and at each public-cache eviction; empty while inner search is off. */
    private volatile Set<String> liveCategories = Set.of();
    private final AtomicBoolean liveReadQueued = new AtomicBoolean();

    /**
     * A fresh temp directory per Spring context, deleted in {@link #close()} (Ruling F15): MMap'd, off-heap, throwaway.
     * It goes under `search.index.base-dir`, default java.io.tmpdir: a host that ages /tmp can move it out (B4).
     */
    @Autowired
    public SearchIndex(ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos,
                       SearchableStreamRepository streams, CategoryRepository categories,
                       @Value("${search.index.base-dir:}") String baseDir, InnerIndex inner) throws IOException {
        this(FSDirectory.open(Files.createTempDirectory(Files.createDirectories(
                        Path.of(baseDir.isEmpty() ? System.getProperty("java.io.tmpdir") : baseDir)), "fitrahtube-search-")),
                channels, playlists, videos, streams, categories, 5_000, inner);
    }

    SearchIndex(Directory dir, ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos,
                SearchableStreamRepository streams, long readyWaitMs) throws IOException {
        this(dir, channels, playlists, videos, streams, null, readyWaitMs, null);
    }

    SearchIndex(Directory dir, ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos,
                SearchableStreamRepository streams, CategoryRepository categories, long readyWaitMs, InnerIndex inner)
            throws IOException {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
        this.streams = streams;
        this.categories = categories;
        this.dir = dir;
        this.writer = new IndexWriter(dir, new IndexWriterConfig(SearchText.INDEX));
        this.searchers = new SearcherManager(writer, null);
        this.readyWaitMs = readyWaitMs;
        this.inner = inner;
    }

    /** Context close: stop the writer thread, drop the uncommitted index and delete its directory. */
    @PreDestroy
    void close() {
        Path path = path(); // unreadable once dir is closed
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
            IOUtils.closeWhileHandlingException(snapshots); // throws if a first page records meanwhile; the rest must still run
            searchers.close();
            writer.rollback(); // nothing to keep: the index is rebuilt at every start
            dir.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("search index close interrupted", e);
        } catch (IOException e) {
            log.warn("search index close failed", e);
        } finally { // whatever failed above, the temp directory must not outlive the context
            if (path != null) delete(path);
        }
    }

    /** The index directory on disk; null for an in-memory index. */
    Path path() {
        return dir instanceof FSDirectory fs ? fs.getDirectory() : null;
    }

    static void delete(Path path) {
        try (Stream<Path> files = Files.walk(path)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        } catch (IOException e) {
            log.warn("search index directory {} not deleted", path, e);
        }
    }

    // ---- build (spec §5.1, §5.4, §7) -----------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        buildStartedAt = System.currentTimeMillis();
        executor.execute(() -> buildCatalog(FIRST_RETRY_MS));
    }

    /** Catalog half: gates readiness; retried with backoff while Firestore is unreachable. */
    private void buildCatalog(long retryMs) {
        try {
            replaceCatalog();
        } catch (Throwable e) { // an Error would otherwise vanish inside the executor's future
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (executor.isShutdown()) return; // close() interrupted us: not a failure, and no retry
            logIfTragic();
            log.error("search index build failed; retrying in {} s", retryMs / 1000, e);
            executor.schedule(() -> buildCatalog(Math.min(retryMs * 2, MAX_RETRY_MS)), retryMs, TimeUnit.MILLISECONDS);
            return;
        }
        ready.countDown();
        executor.execute(() -> loadStreams(null, 0, 0, FIRST_RETRY_MS));
    }

    /**
     * Stream half: one page of 500 per writer task, so catalog events interleave with the ≈46k-document
     * load instead of queuing behind it (spec §5.7). Until it finishes, results hold catalog items only.
     */
    private void loadStreams(String afterId, int loaded, int skipped, long retryMs) {
        List<QueryDocumentSnapshot> page;
        int skippedHere = 0;
        try {
            page = streams.findVisiblePage(afterId, STREAM_PAGE);
            for (QueryDocumentSnapshot d : page) {
                SearchableStream s;
                Document doc;
                try { // one document that cannot be mapped or indexed must not stop the load (F4)
                    s = d.toObject(SearchableStream.class);
                    if (s.getStreamId() == null || s.getTitle() == null) continue;
                    doc = streamDoc(streamDto(s), s.getSourceKeys());
                } catch (Exception e) {
                    skippedHere++;
                    log.warn("search index skipped stream {}", d.getId(), e);
                    continue;
                }
                writer.updateDocument(streamKey(s.getStreamId()), doc);
            }
            searchers.maybeRefreshBlocking();
        } catch (Throwable e) { // an Error would otherwise vanish inside the executor's future (F5)
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (executor.isShutdown()) return;
            logIfTragic();
            log.error("search index stream page after {} failed; retrying in {} s", afterId, retryMs / 1000, e);
            executor.schedule(() -> loadStreams(afterId, loaded, skipped, Math.min(retryMs * 2, MAX_RETRY_MS)),
                    retryMs, TimeUnit.MILLISECONDS);
            return;
        }
        int total = loaded + page.size();
        int totalSkipped = skipped + skippedHere;
        if (page.size() == STREAM_PAGE) {
            String last = page.get(page.size() - 1).getId();
            executor.execute(() -> loadStreams(last, total, totalSkipped, FIRST_RETRY_MS));
        } else {
            // Spec §5.1 step 3: the line that measures the real counts and size after each deploy.
            log.info("search index built: {} streams={} skippedStreams={} bytes={} ms={}",
                    catalogCounts, total, totalSkipped, bytes(), System.currentTimeMillis() - buildStartedAt);
            built.countDown();
        }
    }

    /**
     * Re-reads the catalog and swaps it in with one refresh: readers never see half a catalog. Every
     * document is built before the old ones are deleted, so a failure leaves the last good catalog.
     */
    void replaceCatalog() throws Exception {
        Set<String> live = liveCategoryIds(); // first: unreadable fails the build, never serves an empty LIVE set
        Map<String, Admitted> sources = new ConcurrentHashMap<>();
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Document> docs = new ArrayList<>();
        int[] c = admitEach(channels.findByStatusUnordered("APPROVED"), Channel.class, sources, ids, docs);
        int[] p = admitEach(playlists.findByStatusUnordered("APPROVED"), Playlist.class, sources, ids, docs);
        int[] v = admitEach(videos.findAllUnordered(), Video.class, sources, ids, docs);
        replace(new Term("source", "CATALOG"), docs); // one atomic block: a failed add keeps the old catalog
        liveCategories = live;
        admittedSources = sources;
        videoIds = ids;
        searchers.maybeRefreshBlocking();
        catalogCounts = String.format("channels=%d playlists=%d videos=%d blockedVideos=%d skipped=%d",
                c[0], p[0], v[0], v[1], c[2] + p[2] + v[2]);
        log.info("search index catalog: {}", catalogCounts);
    }

    /**
     * Admits each document; returns {admitted, not admitted, skipped}. Counting documents, not ids, keeps the
     * log exact when two share a youtubeId (F8). One that cannot be mapped or indexed is skipped, never failing the build (F4).
     */
    private static int[] admitEach(List<QueryDocumentSnapshot> found, Class<?> type, Map<String, Admitted> sources,
                                   Set<String> ids, List<Document> docs) {
        int[] counts = new int[3];
        for (QueryDocumentSnapshot d : found) {
            try {
                counts[admit(d.toObject(type), sources, ids, docs) ? 0 : 1]++;
            } catch (Exception e) {
                counts[2]++;
                // Read raw, an unmappable Video still blocks its stream (B3: fail closed, not open).
                if (type == Video.class && d.get("youtubeId") instanceof String youtubeId) ids.add(youtubeId);
                log.warn("search index skipped {} {}", type.getSimpleName(), d.getId(), e);
            }
        }
        return counts;
    }

    /** Safety net (spec §5.4): catalog only; streams are read once at boot and only removed after (StreamIndexService hooks). */
    @Scheduled(fixedDelay = 6, initialDelay = 6, timeUnit = TimeUnit.HOURS)
    public void reconcile() {
        executor.execute(() -> {
            try {
                replaceCatalog();
            } catch (Throwable e) { // an Error would otherwise vanish inside the executor's future (F5)
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (executor.isShutdown()) return;
                logIfTragic();
                log.warn("search index reconcile failed; keeping the last good index", e);
            }
        });
    }

    // ---- freshness (spec §5.2, §5.3) -----------------------------------------------------------

    /**
     * Every event is an invalidation (F3): the writer re-reads the document by id when it applies the
     * event, so the index converges to Firestore's current state whatever order the events arrive in.
     * A failed or timed-out write may commit after that re-read, so it is read again later (B2).
     */
    @EventListener
    public void onCatalogChanged(CatalogChanged event) {
        reread(event, 0);
        if (!event.writeFailed()) return;
        for (long ms : lateCommitRereadMs) {
            try {
                executor.schedule(() -> reread(event, 0), ms, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                return; // writer stopped (context closing): nothing left to keep fresh
            }
        }
    }

    /** A failed re-read is retried on the writer; after the last retry the item is hidden until the reconcile re-admits it. */
    private void reread(CatalogChanged event, int retry) {
        String what = "catalog " + event.type() + " " + event.docId();
        submit(what, () -> {
            try {
                applyCatalogChange(event, reload(event.type(), event.docId()));
            } catch (Throwable e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (executor.isShutdown()) return;
                if (retry < rereadRetryMs.length) {
                    log.warn("search index re-read failed ({}); retrying in {} ms", what, rereadRetryMs[retry], e);
                    executor.schedule(() -> reread(event, retry + 1), rereadRetryMs[retry], TimeUnit.MILLISECONDS);
                    return;
                }
                log.warn("search index re-read failed ({}); hiding it until the next reconcile", what, e);
                applyCatalogChange(event, null); // fail closed: never keep showing what may be rejected or deleted
            }
        });
    }

    /** Indexes `entity` (null = gone) under the event's key; nothing is applied if building its documents fails. */
    private void applyCatalogChange(CatalogChanged event, Object entity) throws Exception {
        Map<String, Admitted> sources = new HashMap<>();
        Set<String> ids = new HashSet<>();
        List<Document> docs = new ArrayList<>();
        admit(entity, sources, ids, docs);
        if (event.youtubeId() != null) ids.add(event.youtubeId()); // blocks its stream even when no Video mapped (R1)
        Term key = new Term("key", event.type() + ":" + event.docId());
        // The old source key goes first: the new one may be the same key, and must not lose it.
        String oldId = storedValues(key.text(), "id").stream().findFirst().orElse(null);
        if (oldId != null && !"VIDEO".equals(event.type())) {
            admittedSources.remove(event.type().toLowerCase(Locale.ROOT) + ":" + oldId);
        }
        admittedSources.putAll(sources);
        videoIds.addAll(ids); // a deleted Video's id stays in videoIds until the next reconcile: its stream stays hidden (fail closed)
        replace(key, docs);
    }

    /** Swaps the documents matching `term` for `docs` in one step. An empty block must not reach updateDocuments: it breaks rollback. */
    private void replace(Term term, List<Document> docs) throws IOException {
        if (docs.isEmpty()) {
            writer.deleteDocuments(term);
        } else {
            writer.updateDocuments(term, docs);
        }
    }

    private Object reload(String type, String docId) throws Exception {
        return switch (type) {
            case "CHANNEL" -> channels.findById(docId).orElse(null);
            case "PLAYLIST" -> playlists.findById(docId).orElse(null);
            default -> videos.findById(docId).orElse(null);
        };
    }

    /** After StreamIndexService.removeSource: drop the key from every stream; delete streams left with none. */
    public void removeSource(String sourceKey) {
        submit("removeSource " + sourceKey, () -> {
            IndexSearcher searcher = searchers.acquire();
            try {
                Query query = new TermQuery(new Term("sourceKey", sourceKey));
                StoredFields fields = searcher.storedFields();
                for (ScoreDoc hit : searcher.search(query, Math.max(1, searcher.count(query))).scoreDocs) {
                    Document doc = fields.document(hit.doc);
                    Set<String> keys = new LinkedHashSet<>(Arrays.asList(doc.getValues("sourceKey")));
                    keys.remove(sourceKey);
                    Term key = streamKey(doc.get("id"));
                    if (keys.isEmpty()) {
                        writer.deleteDocuments(key);
                    } else {
                        writer.updateDocument(key, streamDoc(JSON.readValue(doc.get("dto"), ContentItemDto.class), keys));
                    }
                }
            } finally {
                searchers.release(searcher);
            }
        });
    }

    /** After StreamIndexService.markStreamArchived. */
    public void removeStream(String streamId) {
        submit("removeStream " + streamId, () -> writer.deleteDocuments(streamKey(streamId)));
    }

    /** After StreamIndexService.markStreamRestored (stage 9 B13): the stored stream is indexed again, as the boot load does. */
    public void restoreStream(SearchableStream stream) {
        if (stream.getStreamId() == null || stream.getTitle() == null) return;
        submit("restoreStream " + stream.getStreamId(),
                () -> writer.updateDocument(streamKey(stream.getStreamId()), streamDoc(streamDto(stream), stream.getSourceKeys())));
    }

    /**
     * Stage 9 B25 (Cubic r13 #5): after Restore deleted a take-down record, its id stops blocking the stream. The record's
     * delete event carries no youtubeId and applyCatalogChange only adds, so without this the stream stayed hidden until
     * reconcile(). On the writer, after that event's re-read; a Video doc that holds the id again (a re-add) keeps it.
     */
    public void releaseVideoId(String youtubeId) {
        if (youtubeId == null) return;
        submit("releaseVideoId " + youtubeId, () -> {
            if (videos.findByYoutubeId(youtubeId).isEmpty()) videoIds.remove(youtubeId);
        });
    }

    /** Stored values of `field` on the document with `key` (as of the last refresh), or none. */
    private List<String> storedValues(String key, String field) throws IOException {
        IndexSearcher searcher = searchers.acquire();
        try {
            TopDocs top = searcher.search(new TermQuery(new Term("key", key)), 1);
            return top.scoreDocs.length == 0 ? List.of()
                    : Arrays.asList(searcher.storedFields().document(top.scoreDocs[0].doc).getValues(field));
        } finally {
            searchers.release(searcher);
        }
    }

    /** Every mutation runs here, one at a time, then refreshes. Failures only log: Firestore already committed (spec §7). */
    private void submit(String what, WriterTask task) {
        try {
            executor.execute(() -> {
                try {
                    task.run();
                    searchers.maybeRefreshBlocking();
                } catch (Throwable e) { // an Error would otherwise vanish inside the executor's future
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (executor.isShutdown()) return;
                    logIfTragic();
                    log.warn("search index update failed ({})", what, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("search index update dropped ({}): writer stopped", what, e);
        }
    }

    /** A tragic IndexWriter failure never heals: every later write fails and search serves a frozen index. Said once, at ERROR (F7). */
    private void logIfTragic() {
        Throwable tragic = writer.getTragicException();
        if (tragic != null && tragicLogged.compareAndSet(false, true)) {
            log.error("search index writer hit a tragic failure; search serves the last good index until a restart", tragic);
        }
    }

    @FunctionalInterface
    private interface WriterTask {
        void run() throws Exception;
    }

    // ---- search (spec §4.4) --------------------------------------------------------------------

    /**
     * Per-search counters for the slow-search line (inner-search spec §7.3 step 2a): tiers run, ms and hits per tier,
     * stored documents loaded, hidden hits skipped. Never the query text (search spec §10 promises no query logging).
     */
    static final class Trace {
        int tokens;
        final long[] tierMs = new long[4];
        final int[] tierHits = new int[4];
        final boolean[] tierRan = new boolean[4];
        int loaded, hidden;
        long innerMs = -1;
        int innerHits;
        boolean innerTimedOut;
        long titlesMs = -1;
        int titles;
        boolean titlesTimedOut;
        /** R18 gate lookups on the inner index (InnerIndex.gateDrops) and their total time; today's ladder pays them. */
        int gateChecks;
        long gateNs;

        String line(long totalMs, int want, String type) {
            StringBuilder tiers = new StringBuilder();
            for (int t = 1; t <= 3; t++) {
                if (!tierRan[t]) continue;
                if (!tiers.isEmpty()) tiers.append(',');
                tiers.append(t).append(':').append(tierMs[t]).append("ms:").append(tierHits[t]);
            }
            return "slow search: ms=" + totalMs + " tokens=" + tokens + " want=" + want + " type=" + type
                    + " tiers=" + tiers + " loaded=" + loaded + " hidden=" + hidden
                    + (innerMs < 0 ? "" : " inner=" + innerMs + "ms:" + innerHits + (innerTimedOut ? ":timeout" : ""))
                    + (titlesMs < 0 ? "" : " titles=" + titlesMs + "ms:" + titles + (titlesTimedOut ? ":timeout" : ""))
                    + (gateChecks == 0 ? "" : " gate=" + gateChecks + ":" + gateNs / 1_000_000);
        }
    }

    /** Server-side ms above which `/search` logs its slow-search line. Tests and the local harness set -1 (every search). */
    long slowSearchMs = 150;

    /**
     * Global search (`/search`): the ladder over catalog items and visible streams. `type` is CHANNEL/PLAYLIST/VIDEO
     * or null (all types).
     */
    public Hits search(String query, String type, int want) throws IOException {
        awaitReady();
        long started = System.nanoTime();
        Trace trace = new Trace();
        try {
            Ladder today;
            IndexSearcher searcher = searchers.acquire();
            try {
                today = ladder(searcher, query, type, false, want, null, null, trace);
            } finally {
                searchers.release(searcher);
            }
            return withInner(today, type, want, trace);
        } finally {
            long ms = (System.nanoTime() - started) / 1_000_000;
            if (ms > slowSearchMs) log.info(trace.line(ms, want, type));
        }
    }

    /**
     * Spec §7.1: C (today's list) is unchanged. Inner tier-1–2 hits go in at h = max(3, |C tier ≤ 2|), so C's first h
     * items and the header stay today's, and every curated tier ≤ 2 hit stays ahead of every inner hit. Never for
     * CHANNEL/PLAYLIST searches, while serveInner is off, or before the inner index is built. Then, on every search while
     * serving, broad ones too, Q17 on the answer. Any failure of the inner work (an inner index closing under it, at a
     * rebuild swap or shutdown) answers C: it never fails a search. A failure of the Q17 swap alone keeps the merge; the
     * titles not yet swapped stay stored.
     */
    private Hits withInner(Ladder today, String type, int want, Trace trace) {
        Hits plain = new Hits(today.items(), today.match());
        if (inner == null || !inner.serving() || today.tokens().isEmpty() || "CHANNEL".equals(type) || "PLAYLIST".equals(type)) {
            return plain;
        }
        try {
            IndexSearcher shared = inner.acquire();
            if (shared == null) return plain;
            try {
                Set<String> todays = new HashSet<>();
                for (ContentItemDto item : today.items()) todays.add(item.getId());
                Hits answer = merged(shared, today, todays, want, trace);
                try { // In C only a stored stream names a source; inner hits never share an id with C.
                    originalTitles(shared, answer.items().stream()
                            .filter(item -> item.getSourceType() != null && todays.contains(item.getId())).toList(), trace);
                } catch (IOException | RuntimeException e) {
                    log.warn("inner titles failed ({}); keeping the stored titles", e.getClass().getName()); // the class only: see below
                }
                return answer;
            } finally {
                inner.release(shared);
            }
        } catch (IOException | RuntimeException e) { // AlreadyClosedException included
            // The class only: a Lucene message can quote query terms, and no search log carries the query (search spec §10).
            log.warn("inner search failed ({}); answering today's list", e.getClass().getName());
            return plain;
        }
    }

    /** The §7.1 merge: C, or C[0:h] ++ I ++ C[h:] cut to `want`. `todays` holds C's ids. */
    private Hits merged(IndexSearcher shared, Ladder today, Set<String> todays, int want, Trace trace) throws IOException {
        Hits plain = new Hits(today.items(), today.match());
        int at = Math.min(Math.max(MIN_CONFIDENT_HITS, today.upToTier2()), today.items().size());
        int need = want - at; // = want − min(h, |C|); ≤ 0 also covers "≥ 50 exact or approximate hits"
        if (need <= 0) return plain;
        List<Tiered> found = innerLadder(shared, today.tokens(), need, new HashSet<>(todays), trace); // dedupe: C's copy keeps its place
        if (found.isEmpty()) return plain;
        List<ContentItemDto> out = new ArrayList<>(today.items().subList(0, at));
        for (Tiered t : found) out.add(t.item());
        out.addAll(today.items().subList(at, today.items().size())); // only C's tier-3 hits can sit here
        String match = today.items().isEmpty() ? TIER_NAMES[found.get(0).tier()] : today.match();
        return new Hits(List.copyOf(out.subList(0, Math.min(want, out.size()))), match);
    }

    /**
     * Tiers 1–2 on the inner index (R17), score order, longer videos before Shorts in each tier (R35), within 40 ms (R30).
     * ponytail: the budget is checked per page, per document and per tier, and Lucene checks it between scoring ranges;
     * weight-time work (fuzzy and prefix rewrites, term seeks) is not bounded, so one such step can overrun 40 ms.
     * ExitableDirectoryReader would bound it, if Task 30's max inner ms ever says so.
     */
    private List<Tiered> innerLadder(IndexSearcher shared, List<String> tokens, int need, Set<String> seen, Trace trace)
            throws IOException {
        long started = System.nanoTime();
        List<Tiered> out = new ArrayList<>();
        QueryTimeout timeout = newTimeout.apply(innerTimeoutMs);
        IndexSearcher searcher = new IndexSearcher(shared.getIndexReader()); // per query: its own timeout
        searcher.setTimeout(timeout);
        Map<Query, Query> rewritten = new HashMap<>();
        UnaryOperator<Query> once = fuzzy -> rewritten.computeIfAbsent(fuzzy, q -> {
            try {
                return searcher.rewrite(q);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        for (int tier = 1; tier <= 2 && out.size() < need; tier++) {
            if (trace.innerTimedOut || timeout.shouldExit()) { // before the tier's rewrites
                trace.innerTimedOut = true;
                break;
            }
            List<ContentItemDto> longer = new ArrayList<>(), shorts = new ArrayList<>();
            collectInner(searcher, SearchText.tier(tier, tokens, once), need - out.size(), seen, longer, shorts, timeout, trace);
            for (ContentItemDto item : longer) out.add(new Tiered(item, tier));
            for (ContentItemDto item : shorts) if (out.size() < need) out.add(new Tiered(item, tier));
            if (searcher.timedOut()) trace.innerTimedOut = true; // Lucene stopped scoring mid-tier: no next tier
        }
        if (trace.innerTimedOut) inner.timedOut();
        trace.innerMs = (System.nanoTime() - started) / 1_000_000;
        trace.innerHits = Math.min(out.size(), need);
        return out.size() > need ? out.subList(0, need) : out;
    }

    /** Pages one inner tier in score order until `want` longer videos are found, the hits run out, or the budget ends. */
    private void collectInner(IndexSearcher searcher, Query query, int want, Set<String> seen, List<ContentItemDto> longer,
                              List<ContentItemDto> shorts, QueryTimeout timeout, Trace trace) throws IOException {
        ScoreDoc after = null;
        long n = Math.max(10, 2L * want);
        while (longer.size() < want) {
            if (timeout.shouldExit()) {
                trace.innerTimedOut = true;
                return;
            }
            TopDocs top = after == null ? searcher.search(query, (int) n) : searcher.searchAfter(after, query, (int) n);
            if (top.scoreDocs.length == 0) return;
            StoredFields fields = searcher.storedFields();
            for (ScoreDoc hit : top.scoreDocs) {
                if (timeout.shouldExit()) {
                    trace.innerTimedOut = true;
                    return;
                }
                Document doc = fields.document(hit.doc);
                trace.loaded++;
                String keeper = keeper(doc, trace);
                if (keeper == null) {
                    trace.hidden++;
                    continue;
                }
                if (!seen.add(doc.get("id"))) continue;
                IndexableField d = doc.getField("d");
                int seconds = d == null ? 0 : d.numericValue().intValue();
                (seconds > 0 && seconds <= SHORT_MAX_SECONDS ? shorts : longer).add(innerDto(doc, keeper));
                if (longer.size() == want) return;
            }
            if (top.scoreDocs.length < n) return; // the tier has no more hits (or the search timed out)
            after = top.scoreDocs[top.scoreDocs.length - 1];
            n = Math.min(2 * n, Integer.MAX_VALUE);
        }
    }

    /**
     * Q17: each stored stream shown takes its inner copy's title, which is in its source's own `hl` (the stored titles came
     * from `hl=en` lists, so they are often machine-translated), and keeps its place. Its own budget, checked before each
     * lookup: past it (a cold page cache, say) the rest keep their stored titles. The slow line shows its ms, the titles
     * it swapped and whether it ran out.
     */
    private void originalTitles(IndexSearcher shared, List<ContentItemDto> stored, Trace trace) throws IOException {
        if (stored.isEmpty()) return;
        long started = System.nanoTime();
        QueryTimeout timeout = newTimeout.apply(innerTimeoutMs);
        IndexSearcher searcher = new IndexSearcher(shared.getIndexReader());
        searcher.setTimeout(timeout);
        for (ContentItemDto item : stored) {
            if (timeout.shouldExit()) {
                trace.titlesTimedOut = true;
                break;
            }
            String title = innerTitle(searcher, item.getId(), item.getSourceType().toLowerCase(Locale.ROOT) + ":" + item.getSourceId());
            if (title != null) {
                item.setTitle(title);
                trace.titles++;
            }
        }
        if (searcher.timedOut()) trace.titlesTimedOut = true; // Lucene cut a lookup short
        trace.titlesMs = (System.nanoTime() - started) / 1_000_000;
    }

    /** A video's copies looked at for its inner title; one video is rarely listed by more sources. */
    private static final int TITLE_COPIES = 16;

    /** The inner title of `id` (Q17, and Task 24b's titles), from {@link #innerCopy}; null if no copy names it. */
    String innerTitle(IndexSearcher searcher, String id, String preferredKey) throws IOException {
        Copy copy = innerCopy(searcher, id, preferredKey);
        return copy == null ? null : copy.doc().get("t");
    }

    /** An inner copy and the admitted source that kept it when it was chosen: one read, so a catalog swap cannot split them. */
    private record Copy(Document doc, String keeper) {}

    /**
     * The inner copy that names `id` (Q17, Task 24b's titles, R27's pasted ids): the copy `preferredKey` lists if that
     * source keeps it, else the first copy an admitted source keeps; null if none does. A source that is not admitted
     * never names a video, nor does a blank title (as in the catalog path).
     */
    private Copy innerCopy(IndexSearcher searcher, String id, String preferredKey) throws IOException {
        StoredFields fields = searcher.storedFields();
        Copy first = null;
        for (ScoreDoc hit : searcher.search(new TermQuery(new Term("id", id)), TITLE_COPIES).scoreDocs) {
            Document doc = fields.document(hit.doc);
            String keeper = keeper(doc, null), title = doc.get("t"); // an INNER copy never reaches the stream gate
            if (keeper == null || title == null || title.isBlank()) continue;
            if (keeper.equals(preferredKey)) return new Copy(doc, keeper);
            if (first == null) first = new Copy(doc, keeper);
        }
        return first;
    }

    /** R28: the stream DTO's shape from an INNER document's stored fields, naming the source that keeps it. */
    static ContentItemDto innerDto(Document doc, String keeper) {
        String id = doc.get("id");
        IndexableField seconds = doc.getField("d"), day = doc.getField("a");
        Integer daysAgo = day == null ? null
                : (int) Math.max(0, LocalDate.now(ZoneOffset.UTC).toEpochDay() - day.numericValue().longValue());
        ContentItemDto dto = ContentItemDto.video(id, doc.get("t"), null, seconds == null ? 0 : seconds.numericValue().intValue(),
                daysAgo, null, "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg", null, doc.get("c"), null);
        provenance(dto, keeper);
        return dto;
    }

    /**
     * In-tab search (spec §4.7) for one page: catalog only, on the searcher `snapshot` names, so every page of one
     * search cuts the same ranking. BM25 statistics move whenever any document changes (a catalog save or a stream
     * removal; all documents share `title` and `channel`), and re-ranking between page fetches repeated or skipped items (Cubic P3, 1.0.4). An unknown or
     * pruned snapshot (-1 for a first page) runs on the current searcher and pins it; the result names the one used.
     * A pinned page keeps its ranking, never its catalog: each hit must still have its catalog document now (Codex P1-1).
     */
    public Hits searchPage(String query, String type, int want, Set<String> allowedIds, long snapshot)
            throws IOException {
        awaitReady();
        // On every page, not only a first page's record: a superseded snapshot past its TTL is never read (Codex P1-1).
        // ponytail: the TTL counts from when a newer first page supersedes a snapshot, so the newest never expires on an
        // idle server; with admission checked per hit that is only an older ranking and older titles. Carry the first
        // page's time in the cursor if that ever matters.
        int[] kept = {0}; // the pruner sees the snapshots newest first
        snapshots.prune((ageSec, s) -> ageSec > snapshotTtlSec || ++kept[0] > MAX_SNAPSHOTS);
        IndexSearcher pinned = snapshots.acquire(snapshot);
        IndexSearcher searcher = pinned != null ? pinned : searchers.acquire();
        IndexSearcher now = null;
        try {
            if (pinned == null) snapshot = snapshots.record(searcher);
            else now = searchers.acquire();
            Ladder hits = ladder(searcher, query, type, true, want, allowedIds, now, new Trace());
            return new Hits(hits.items(), hits.match(), snapshot);
        } finally {
            if (now != null) searchers.release(now);
            if (pinned != null) snapshots.release(pinned); else searchers.release(searcher);
        }
    }

    /**
     * The ladder (spec §4.4): exact, then approximate, then related, each appended (deduplicated by id) behind the hits
     * before it. Approximate fills a page the exact tier left short (a spelling variant such as "ikhlas" for "ikhlaas");
     * related, the noisiest, runs only while fewer than {@link #MIN_CONFIDENT_HITS} items are found. `catalogOnly`
     * drops streams (in-tab lists); `allowedIds` (null = all) restricts membership for filtered in-tab search (§4.7);
     * `now` (null = `searcher` is current) is the current searcher a pinned page's hits must still be catalog items on.
     */
    private Ladder ladder(IndexSearcher searcher, String query, String type, boolean catalogOnly, int want,
                          Set<String> allowedIds, IndexSearcher now, Trace trace) throws IOException {
        List<String> tokens = SearchText.tokens(SearchText.BASE, query);
        if (tokens.size() > SearchText.MAX_TOKENS) tokens = tokens.subList(0, SearchText.MAX_TOKENS);
        trace.tokens = tokens.size();
        if (tokens.isEmpty()) return new Ladder(List.of(), "none", List.of(), 0);
        List<ContentItemDto> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String match = "none";
        int upToTier2 = 0;
        // Each fuzzy clause is rewritten (its automaton built) once per search, then shared by tiers 2 and 3 (F1).
        Map<Query, Query> rewritten = new HashMap<>();
        UnaryOperator<Query> once = fuzzy -> rewritten.computeIfAbsent(fuzzy, q -> {
            try {
                return searcher.rewrite(q);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        for (int tier = 1; tier <= 3 && out.size() < Math.min(want, tier < 3 ? want : MIN_CONFIDENT_HITS); tier++) {
            boolean wasEmpty = out.isEmpty();
            int before = out.size();
            long t0 = System.nanoTime();
            collect(searcher, filtered(SearchText.tier(tier, tokens, once), type, catalogOnly), want, allowedIds, now, out, seen, trace);
            trace.tierRan[tier] = true;
            trace.tierMs[tier] = (System.nanoTime() - t0) / 1_000_000;
            trace.tierHits[tier] = out.size() - before;
            if (wasEmpty && !out.isEmpty()) match = TIER_NAMES[tier];
            if (tier <= 2) upToTier2 = out.size();
        }
        return new Ladder(out, match, tokens, upToTier2);
    }

    /** One run of the ladder: the items, the header tier, the query's tokens, and how many items tiers 1–2 found (§7.1). */
    private record Ladder(List<ContentItemDto> items, String match, List<String> tokens, int upToTier2) {}

    private record Tiered(ContentItemDto item, int tier) {}

    /** Exact YouTube-id lookup for pasted URLs and ids (spec §4.4); on a miss, the inner index (inner-search R27). No Firestore read. */
    public Hits byId(String youtubeId, String type) throws IOException {
        awaitReady();
        List<ContentItemDto> out = new ArrayList<>();
        IndexSearcher searcher = searchers.acquire();
        try {
            collect(searcher, filtered(new TermQuery(new Term("id", youtubeId)), type, false), 1, null, null, out, new HashSet<>(), new Trace());
        } finally {
            searchers.release(searcher);
        }
        if (out.isEmpty() && inner != null && inner.serving() && (type == null || "VIDEO".equals(type))) {
            try { // R27: a pasted id of a video inside an admitted source, named by the search's own copy rule
                IndexSearcher s = inner.acquire();
                if (s != null) {
                    try {
                        Copy copy = innerCopy(s, youtubeId, null);
                        if (copy != null) out.add(innerDto(copy.doc(), copy.keeper()));
                    } finally {
                        inner.release(s);
                    }
                }
            } catch (IOException | RuntimeException e) { // AlreadyClosedException included: as /search, never a 500
                log.warn("pasted id: inner lookup failed ({}); answering a miss", e.getClass().getName()); // the class only: no id
            }
        }
        return out.isEmpty() ? Hits.NONE : new Hits(out, "exact");
    }

    /**
     * Inner-search Task 24b (owner issue 1): original titles for the ids the apps list on channel and playlist pages,
     * whose own lists come back machine-translated under hl=en.
     * - The catalog's first: validators refresh Video.title from StreamInfo.getName(), which NewPipe 0.26.5 reads from
     *   playerResponse.videoDetails.title.
     * - Then the inner index's (each source's own hl, R21), while it serves, by the search's own rule ({@link #innerTitle})
     *   and within the title pass's budget, checked per id: past it (a cold page cache) the rest are unknown ids.
     * - Never a stored stream's: the apps pushed those from their hl=en lists.
     * No Firestore read, no YouTube call. The map holds catalog titles, then inner ones, each in request order.
     */
    public Titles titles(Collection<String> ids) throws IOException {
        awaitReady();
        Map<String, String> out = new LinkedHashMap<>();
        IndexSearcher searcher = searchers.acquire();
        try {
            StoredFields fields = searcher.storedFields();
            for (String id : ids) {
                Query catalogVideo = new BooleanQuery.Builder()
                        .add(new TermQuery(new Term("id", id)), Occur.FILTER)
                        .add(new TermQuery(new Term("source", "CATALOG")), Occur.FILTER)
                        .add(new TermQuery(new Term("type", "VIDEO")), Occur.FILTER)
                        .build();
                ScoreDoc[] hit = searcher.search(catalogVideo, 1).scoreDocs;
                if (hit.length == 0) continue;
                String title = dto(fields.document(hit[0].doc)).getTitle();
                if (title != null && !title.isBlank()) out.put(id, title);
            }
        } finally {
            searchers.release(searcher);
        }
        if (out.keySet().containsAll(ids)) return new Titles(out, true); // nothing left for the inner index
        if (inner == null || !inner.serving()) return new Titles(out, false); // its originals come later
        try {
            IndexSearcher shared = inner.acquire();
            if (shared == null) return new Titles(out, false);
            try {
                QueryTimeout timeout = newTimeout.apply(innerTimeoutMs);
                IndexSearcher budgeted = new IndexSearcher(shared.getIndexReader());
                budgeted.setTimeout(timeout);
                for (String id : ids) {
                    if (out.containsKey(id)) continue;
                    if (timeout.shouldExit()) return new Titles(out, false);
                    String title = innerTitle(budgeted, id, null);
                    if (title != null) out.put(id, title);
                }
                return new Titles(out, !budgeted.timedOut()); // Lucene may have cut a lookup short
            } finally {
                inner.release(shared);
            }
        } catch (IOException | RuntimeException e) { // AlreadyClosedException included: as /search, never a 500 for the inner index
            log.warn("titles: inner lookup failed ({}); answering the catalog's", e.getClass().getName()); // the class only
            return new Titles(out, false);
        }
    }

    /** API `type` (CHANNELS/PLAYLISTS/VIDEOS) -> index type; anything else (null, ALL, HOME) -> all types. */
    public static String docType(String apiType) {
        if (apiType == null) return null;
        return switch (apiType.toUpperCase(Locale.ROOT)) {
            case "CHANNELS" -> "CHANNEL";
            case "PLAYLISTS" -> "PLAYLIST";
            case "VIDEOS" -> "VIDEO";
            default -> null;
        };
    }

    /**
     * Tier-1 words over a text outside the index (a LIVE category's current lives, L3): every query token is a word of
     * `text` or shares its stem, the last one also as a prefix, through the index's analyzers (case, Arabic and digit
     * folding, ال stripped). A stopword is optional unless the query is all stopwords, as in tier 1.
     * ponytail: no fuzzy or concept tiers; run the ladder on a temporary index if those lists grow.
     */
    public static boolean matches(String query, String text) {
        List<String> words = SearchText.tokens(SearchText.BASE, text);
        List<String> tokens = SearchText.tokens(SearchText.BASE, query);
        boolean onlyStopwords = tokens.stream().allMatch(SearchText::isStopword);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!onlyStopwords && SearchText.isStopword(token)) continue;
            boolean last = i == tokens.size() - 1;
            String stem = SearchText.stem(token);
            if (words.stream().noneMatch(w -> w.equals(token) || SearchText.stem(w).equals(stem)
                    || last && w.startsWith(token))) return false;
        }
        return !tokens.isEmpty();
    }

    /** Admitted source keys ("channel:UC…", "playlist:PL…") and their names, now (inner-search R23, R24). */
    public Map<String, String> sources() {
        Map<String, String> out = new HashMap<>();
        admittedSources.forEach((key, source) -> out.put(key, source.name() == null ? "" : source.name()));
        return out;
    }

    /**
     * Q18 (controller ruling 3), the one admission rule that serving ({@link #keeper}) and InnerCrawler share: an admitted
     * channel whose every category is a LIVE one is no inner-search source (approved for its lives; its back catalogue was
     * never curated). A channel also in an ordinary category is one, as is one with no category; a playlist never is.
     * Fresh: a channel's categories change with its CatalogChanged re-read, the LIVE set at each eviction.
     */
    boolean liveOnly(String sourceKey) {
        Admitted source = admittedSources.get(sourceKey);
        return source != null && liveOnly(source);
    }

    private boolean liveOnly(Admitted source) {
        List<String> in = source.categoryIds();
        return in != null && !in.isEmpty() && liveCategories.containsAll(in);
    }

    /**
     * Q18: CategoryController evicts the public caches after every category create, update (LIVE on, or the kill switch
     * off) and delete, as LiveChannelsMigration does after it sets LIVE; each eviction re-reads the LIVE set on the writer.
     * A burst queues one read. ponytail: a failed read keeps the last set until the next eviction or the 6-hourly
     * reconcile; retry it as reread() does if that window ever matters.
     */
    @EventListener
    public void onPublicContentEvicted(PublicContentCacheService.Evicted event) {
        if (!liveReadQueued.compareAndSet(false, true)) return;
        submit("LIVE categories", () -> {
            liveReadQueued.set(false); // first: an eviction during the read queues another
            liveCategories = liveCategoryIds();
        });
    }

    /** Q18's LIVE category ids; none while inner search is off, since only inner serving and the crawler ask. */
    private Set<String> liveCategoryIds() throws Exception {
        if (inner == null || !inner.enabled()) return Set.of();
        return categories.findAll().stream().filter(Category::isLive).map(Category::getId).collect(Collectors.toUnmodifiableSet());
    }

    /** Inner-search R34: every admitted source that lists this video, from the inner index and the stored stream's keys. */
    public List<String> sourcesListing(String youtubeId) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        if (inner != null) keys.addAll(inner.sourceKeys(youtubeId));
        keys.addAll(storedValues("STREAM:" + youtubeId, "sourceKey"));
        keys.retainAll(admittedSources.keySet());
        return List.copyOf(keys);
    }

    /** True once the first build, catalog and stored streams, has logged "search index built" (inner-search R14). */
    public boolean awaitBuilt(long ms) throws InterruptedException {
        return built.await(ms, TimeUnit.MILLISECONDS);
    }

    /**
     * R34: every source listing is loaded: the first build and, while inner search is on, the inner index. Before that an
     * empty sourcesListing means "not loaded", never "none" (slice B I1, Codex P2-4).
     */
    public boolean listingsLoaded() {
        return built.getCount() == 0 && (inner == null || !inner.enabled() || inner.ready());
    }

    /** Blocks up to the ready wait for the first catalog build; NotReadyException after it (spec §7). */
    public void awaitReady() {
        try {
            if (ready.await(readyWaitMs, TimeUnit.MILLISECONDS)) return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        throw new NotReadyException();
    }

    private static Query filtered(Query query, String type, boolean catalogOnly) {
        BooleanQuery.Builder b = new BooleanQuery.Builder().add(query, Occur.MUST);
        if (type != null) b.add(new TermQuery(new Term("type", type)), Occur.FILTER);
        if (catalogOnly) b.add(new TermQuery(new Term("source", "CATALOG")), Occur.FILTER);
        return b.build();
    }

    /**
     * Over-fetches 2x and pages with searchAfter until `want` visible, unseen, allowed items are found. The page
     * doubles each round, so many hidden hits cost a few query runs, not one per page (M7).
     */
    private void collect(IndexSearcher searcher, Query query, int want, Set<String> allowedIds, IndexSearcher now,
                         List<ContentItemDto> out, Set<String> seen, Trace trace) throws IOException {
        ScoreDoc after = null;
        long n = Math.max(10, 2L * (want - out.size()));
        for (; out.size() < want; n = Math.min(2 * n, Integer.MAX_VALUE)) {
            TopDocs top = after == null ? searcher.search(query, (int) n, RELEVANCE)
                    : searcher.searchAfter(after, query, (int) n, RELEVANCE);
            if (top.scoreDocs.length == 0) return;
            StoredFields fields = searcher.storedFields();
            for (ScoreDoc hit : top.scoreDocs) {
                Document doc = fields.document(hit.doc);
                trace.loaded++;
                String id = doc.get("id");
                if (allowedIds != null && !allowedIds.contains(id)) continue;
                if (now != null && now.count(new BooleanQuery.Builder() // withdrawn since the pinned first page
                        .add(new TermQuery(new Term("id", id)), Occur.FILTER)
                        .add(new TermQuery(new Term("source", "CATALOG")), Occur.FILTER).build()) == 0) {
                    continue;
                }
                String keeper = keeper(doc, trace);
                if (!"CATALOG".equals(doc.get("source")) && keeper == null) {
                    trace.hidden++;
                    continue;
                }
                if (seen.add(id)) {
                    ContentItemDto dto = dto(doc);
                    if (keeper != null) provenance(dto, keeper);
                    out.add(dto);
                    if (out.size() == want) return;
                }
            }
            after = top.scoreDocs[top.scoreDocs.length - 1];
        }
    }

    /**
     * Spec §4.2, §8.1: the admitted source that keeps a STREAM or INNER document visible, or null (hidden or catalog).
     * An INNER copy is kept only by an inner-search source: never a LIVE-only channel (Q18), whenever its corpus was
     * stored. `trace` (nullable) counts the stream gate's lookups.
     */
    private String keeper(Document doc, Trace trace) {
        String kind = doc.get("source");
        if (!"STREAM".equals(kind) && !"INNER".equals(kind)) return null;
        String id = doc.get("id");
        if (videoIds.contains(id)) return null;
        boolean stream = "STREAM".equals(kind); // the R18 gate applies to stored streams only
        Map<String, Admitted> sources = admittedSources;
        for (String key : doc.getValues("sourceKey")) {
            Admitted source = sources.get(key);
            if (source == null || source.excluded().contains(id)) continue;
            if (stream ? inner != null && inner.gateDrops(key, id, trace) : liveOnly(source)) continue;
            return key;
        }
        return null;
    }

    /** R28: a stream or inner hit names the source that keeps it. Installed apps ignore both fields (F31). */
    private static void provenance(ContentItemDto dto, String sourceKey) {
        dto.setSourceType(sourceKey.startsWith("channel:") ? "CHANNEL" : "PLAYLIST");
        dto.setSourceId(sourceKey.substring(sourceKey.indexOf(':') + 1));
    }

    private static ContentItemDto dto(Document doc) throws IOException {
        ContentItemDto dto = JSON.readValue(doc.get("dto"), ContentItemDto.class);
        IndexableField uploadedAt = doc.getField("uploadedAt");
        if (uploadedAt != null) {
            // Relative to now, so recomputed per response (spec §4.2).
            dto.setUploadedDaysAgo(ContentItemMapper.uploadedDaysAgo(
                    Timestamp.ofTimeMicroseconds(uploadedAt.numericValue().longValue() * 1000)));
        }
        return dto;
    }

    // ---- documents (spec §4.2) -----------------------------------------------------------------

    /**
     * The one per-type indexing rule, shared by the catalog build and the freshness listener (Ruling F3).
     * `entity` is a Channel, Playlist or Video (anything else, including null, is ignored):
     * <ul>
     *   <li>every Video's youtubeId joins `videoIds`, admitted or not (it blocks the stream with that id);</li>
     *   <li>an admitted channel or playlist registers its source key and exclusions in `sources`;</li>
     *   <li>an admitted entity's document is appended to `docs` for the caller to add to the writer.</li>
     * </ul>
     * Returns whether the entity was admitted. Deleting any previous document is the caller's job.
     */
    static boolean admit(Object entity, Map<String, Admitted> sources, Set<String> videoIds, List<Document> docs)
            throws IOException {
        if (entity instanceof Channel c) {
            if (!admitted(c.getStatus(), c.getVisibility(), c.getValidationStatus(), c.getYoutubeId())) return false;
            // Its own name in `channel` too: every upload of it matches there, and the channel must not lose to them (P1).
            docs.add(catalogDoc("CHANNEL", c.getId(), c.getYoutubeId(), c.getName(), c.getKeywords(), c.getName(),
                    c.getDescription(), ContentItemMapper.fromChannel(c), null));
            sources.put("channel:" + c.getYoutubeId(), new Admitted(c.getName(), exclusions(c), c.getCategoryIds()));
        } else if (entity instanceof Playlist p) {
            if (!admitted(p.getStatus(), p.getVisibility(), p.getValidationStatus(), p.getYoutubeId())) return false;
            docs.add(catalogDoc("PLAYLIST", p.getId(), p.getYoutubeId(), p.getTitle(), p.getKeywords(), null,
                    p.getDescription(), ContentItemMapper.fromPlaylist(p), null));
            sources.put("playlist:" + p.getYoutubeId(), new Admitted(p.getTitle(), exclusions(p), List.of()));
        } else if (entity instanceof Video v) {
            if (v.getYoutubeId() != null) videoIds.add(v.getYoutubeId());
            if (!admitted(v.getStatus(), v.getVisibility(), v.getValidationStatus(), v.getYoutubeId())) return false;
            docs.add(catalogDoc("VIDEO", v.getId(), v.getYoutubeId(), v.getTitle(), v.getKeywords(),
                    v.getChannelTitle(), v.getDescription(), ContentItemMapper.fromVideo(v), v.getUploadedAt()));
        } else {
            return false;
        }
        return true;
    }

    static boolean admitted(String status, String visibility, ValidationStatus validation, String youtubeId) {
        return youtubeId != null && "APPROVED".equals(status) && VisibilityPolicy.isPublic(visibility)
                && validation != ValidationStatus.UNAVAILABLE && validation != ValidationStatus.ARCHIVED;
    }

    private static Set<String> exclusions(Channel c) {
        Channel.ExcludedItems excluded = c.getExcludedItems();
        Set<String> out = new HashSet<>(excluded.getVideos());
        out.addAll(excluded.getShorts());
        out.addAll(excluded.getLiveStreams());
        return out;
    }

    private static Set<String> exclusions(Playlist p) {
        return p.getExcludedVideoIds() == null ? Set.of() : new HashSet<>(p.getExcludedVideoIds());
    }

    private static Document catalogDoc(String type, String docId, String id, String title, List<String> keywords,
                                       String channel, String description, ContentItemDto dto,
                                       Timestamp uploadedAt) throws IOException {
        Document doc = doc(type + ":" + docId, id, type, "CATALOG", title, keywords, channel, dto);
        if (description != null && !description.isBlank()) {
            doc.add(new TextField("description", description, Field.Store.NO));
        }
        if (uploadedAt != null) doc.add(new StoredField("uploadedAt", uploadedAt.toDate().getTime()));
        return doc;
    }

    private static Document streamDoc(ContentItemDto dto, Collection<String> sourceKeys) throws IOException {
        Document doc = doc("STREAM:" + dto.getId(), dto.getId(), "VIDEO", "STREAM", dto.getTitle(), null,
                dto.getChannelTitle(), dto);
        for (String key : sourceKeys) {
            if (key != null) doc.add(new StringField("sourceKey", key, Field.Store.YES));
        }
        return doc;
    }

    private static Document doc(String key, String id, String type, String source, String title,
                                List<String> keywords, String channel, ContentItemDto dto) throws IOException {
        Document doc = new Document();
        doc.add(new StringField("key", key, Field.Store.NO));
        doc.add(new SortedDocValuesField("key", new BytesRef(key))); // the tie-break (F9)
        doc.add(new StringField("id", id, Field.Store.YES));
        doc.add(new StringField("type", type, Field.Store.NO));
        doc.add(new StringField("source", source, Field.Store.YES));
        addText(doc, "title", title);
        if (keywords != null) for (String keyword : keywords) addText(doc, "keywords", keyword);
        addText(doc, "channel", channel);
        doc.add(new StoredField("dto", JSON.writeValueAsString(dto)));
        return doc;
    }

    /** A base field and its `_stem` twin; the per-field index analyzer picks the chain (spec §4.3). Shared by InnerIndex (inner-search R16). */
    static void addText(Document doc, String field, String value) {
        if (value == null || value.isBlank()) return;
        doc.add(new TextField(field, value, Field.Store.NO));
        doc.add(new TextField(field + "_stem", value, Field.Store.NO));
    }

    /** A stream shown as a stream has no Video document, so no upload date. */
    static ContentItemDto streamDto(SearchableStream s) {
        return ContentItemDto.video(s.getStreamId(), s.getTitle(), null,
                s.getDurationSeconds() != null ? s.getDurationSeconds().intValue() : 0,
                null, null, s.getThumbnailUrl(), s.getViewCount(), s.getChannelName(), null);
    }

    private static Term streamKey(String streamId) {
        return new Term("key", "STREAM:" + streamId);
    }

    /** Index size for the build log line; -1 if a merge removed a file mid-count. */
    private long bytes() {
        try {
            long total = 0;
            for (String file : dir.listAll()) total += dir.fileLength(file);
            return total;
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Tests only: runs every queued writer task, including tasks those tasks queue in turn (Ruling F1). The
     * check runs ON the writer thread, so no task can be between the queue and the worker while it looks.
     * A retry scheduled for later is not waited for.
     */
    void awaitWrites() throws Exception {
        while (executor.submit(() -> executor.getQueue().stream()
                .anyMatch(t -> ((Delayed) t).getDelay(TimeUnit.NANOSECONDS) <= 0)).get(10, TimeUnit.SECONDS)) {
            // another round: the tasks that ran queued more
        }
    }
}
