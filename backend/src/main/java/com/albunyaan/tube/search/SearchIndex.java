package com.albunyaan.tube.search;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.ContentItemMapper;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import com.albunyaan.tube.repository.VideoRepository;
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
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.function.UnaryOperator;
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
    private static final long FIRST_RETRY_MS = 30_000;
    private static final long MAX_RETRY_MS = 300_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SearchIndex.class);

    /** One answer: the items and the tier of the first one (`exact`/`approximate`/`related`/`none`). */
    public record Hits(List<ContentItemDto> items, String match) {
        public static final Hits NONE = new Hits(List.of(), "none");
    }

    /** The first catalog build has not finished: the API answers 503 + Retry-After (spec §7). */
    public static class NotReadyException extends RuntimeException {
        public NotReadyException() {
            super("search index is still building");
        }
    }

    private final ChannelRepository channels;
    private final PlaylistRepository playlists;
    private final VideoRepository videos;
    private final SearchableStreamRepository streams;
    private final Directory dir;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private final long readyWaitMs;
    private final CountDownLatch ready = new CountDownLatch(1);
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
    /** Admitted channel/playlist source keys ("channel:UC…") -> the video ids that source excludes. */
    private volatile Map<String, Set<String>> admittedSources = new ConcurrentHashMap<>();
    /** youtubeId of every Video document, admitted or not: such an id never shows as a stream. */
    private volatile Set<String> videoIds = ConcurrentHashMap.newKeySet();

    /**
     * A fresh temp directory per Spring context, deleted in {@link #close()} (Ruling F15): MMap'd, off-heap, throwaway.
     * It goes under `search.index.base-dir`, default java.io.tmpdir: a host that ages /tmp can move it out (B4).
     */
    @Autowired
    public SearchIndex(ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos,
                       SearchableStreamRepository streams, @Value("${search.index.base-dir:}") String baseDir)
            throws IOException {
        this(FSDirectory.open(Files.createTempDirectory(Files.createDirectories(
                        Path.of(baseDir.isEmpty() ? System.getProperty("java.io.tmpdir") : baseDir)), "fitrahtube-search-")),
                channels, playlists, videos, streams, 5_000);
    }

    SearchIndex(Directory dir, ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos,
                SearchableStreamRepository streams, long readyWaitMs) throws IOException {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
        this.streams = streams;
        this.dir = dir;
        this.writer = new IndexWriter(dir, new IndexWriterConfig(SearchText.INDEX));
        this.searchers = new SearcherManager(writer, null);
        this.readyWaitMs = readyWaitMs;
    }

    /** Context close: stop the writer thread, drop the uncommitted index and delete its directory. */
    @PreDestroy
    void close() {
        Path path = path(); // unreadable once dir is closed
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
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

    private static void delete(Path path) {
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
        }
    }

    /**
     * Re-reads the catalog and swaps it in with one refresh: readers never see half a catalog. Every
     * document is built before the old ones are deleted, so a failure leaves the last good catalog.
     */
    void replaceCatalog() throws Exception {
        Map<String, Set<String>> sources = new ConcurrentHashMap<>();
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Document> docs = new ArrayList<>();
        int[] c = admitEach(channels.findByStatusUnordered("APPROVED"), Channel.class, sources, ids, docs);
        int[] p = admitEach(playlists.findByStatusUnordered("APPROVED"), Playlist.class, sources, ids, docs);
        int[] v = admitEach(videos.findAllUnordered(), Video.class, sources, ids, docs);
        replace(new Term("source", "CATALOG"), docs); // one atomic block: a failed add keeps the old catalog
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
    private static int[] admitEach(List<QueryDocumentSnapshot> found, Class<?> type, Map<String, Set<String>> sources,
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

    /** Safety net (spec §5.4): catalog only; streams have one fully hooked writer and are re-read at boot. */
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
        Map<String, Set<String>> sources = new HashMap<>();
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

    /** After StreamIndexService upserted these streams for `sourceKey`; merges with stored source keys. */
    public void upsertStreams(List<SearchableStream> written, String sourceKey) {
        if (written.isEmpty()) return;
        submit("upsertStreams " + sourceKey, () -> {
            for (SearchableStream s : written) {
                Set<String> keys = new LinkedHashSet<>(storedValues("STREAM:" + s.getStreamId(), "sourceKey"));
                keys.add(sourceKey);
                writer.updateDocument(streamKey(s.getStreamId()), streamDoc(streamDto(s), keys));
            }
        });
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
     * The ladder: exact, then approximate, then related, each appended (deduplicated by id) while
     * fewer than {@link #MIN_CONFIDENT_HITS} items are found. `type` is CHANNEL/PLAYLIST/VIDEO or
     * null; `catalogOnly` drops streams (in-tab lists); `allowedIds` (null = all) restricts membership
     * for filtered in-tab search (spec §4.7).
     */
    public Hits search(String query, String type, boolean catalogOnly, int want, Set<String> allowedIds)
            throws IOException {
        awaitReady();
        List<String> tokens = SearchText.tokens(SearchText.BASE, query);
        if (tokens.size() > SearchText.MAX_TOKENS) tokens = tokens.subList(0, SearchText.MAX_TOKENS);
        if (tokens.isEmpty()) return Hits.NONE;
        List<ContentItemDto> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String match = "none";
        IndexSearcher searcher = searchers.acquire();
        try {
            // Each fuzzy clause is rewritten (its automaton built) once per search, then shared by tiers 2 and 3 (F1).
            Map<Query, Query> rewritten = new HashMap<>();
            UnaryOperator<Query> once = fuzzy -> rewritten.computeIfAbsent(fuzzy, q -> {
                try {
                    return searcher.rewrite(q);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            for (int tier = 1; tier <= 3 && out.size() < Math.min(want, MIN_CONFIDENT_HITS); tier++) {
                boolean wasEmpty = out.isEmpty();
                collect(searcher, filtered(SearchText.tier(tier, tokens, once), type, catalogOnly), want, allowedIds, out, seen);
                if (wasEmpty && !out.isEmpty()) match = TIER_NAMES[tier];
            }
        } finally {
            searchers.release(searcher);
        }
        return new Hits(out, match);
    }

    /** Exact YouTube-id lookup for pasted URLs and ids (spec §4.4); no Firestore read. */
    public Hits byId(String youtubeId, String type) throws IOException {
        awaitReady();
        List<ContentItemDto> out = new ArrayList<>();
        IndexSearcher searcher = searchers.acquire();
        try {
            collect(searcher, filtered(new TermQuery(new Term("id", youtubeId)), type, false), 1, null, out, new HashSet<>());
        } finally {
            searchers.release(searcher);
        }
        return out.isEmpty() ? Hits.NONE : new Hits(out, "exact");
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
    private void collect(IndexSearcher searcher, Query query, int want, Set<String> allowedIds,
                         List<ContentItemDto> out, Set<String> seen) throws IOException {
        ScoreDoc after = null;
        long n = Math.max(10, 2L * (want - out.size()));
        for (; out.size() < want; n = Math.min(2 * n, Integer.MAX_VALUE)) {
            TopDocs top = after == null ? searcher.search(query, (int) n, RELEVANCE)
                    : searcher.searchAfter(after, query, (int) n, RELEVANCE);
            if (top.scoreDocs.length == 0) return;
            StoredFields fields = searcher.storedFields();
            for (ScoreDoc hit : top.scoreDocs) {
                Document doc = fields.document(hit.doc);
                String id = doc.get("id");
                if ((allowedIds == null || allowedIds.contains(id)) && visible(doc) && seen.add(id)) {
                    out.add(dto(doc));
                    if (out.size() == want) return;
                }
            }
            after = top.scoreDocs[top.scoreDocs.length - 1];
        }
    }

    /** Spec §4.2: a stream shows only if no Video document claims its id and an admitted source keeps it. */
    private boolean visible(Document doc) {
        if (!"STREAM".equals(doc.get("source"))) return true;
        String id = doc.get("id");
        if (videoIds.contains(id)) return false;
        Map<String, Set<String>> sources = admittedSources;
        for (String key : doc.getValues("sourceKey")) {
            Set<String> excluded = sources.get(key);
            if (excluded != null && !excluded.contains(id)) return true;
        }
        return false;
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
    static boolean admit(Object entity, Map<String, Set<String>> sources, Set<String> videoIds, List<Document> docs)
            throws IOException {
        if (entity instanceof Channel c) {
            if (!admitted(c.getStatus(), c.getVisibility(), c.getValidationStatus(), c.getYoutubeId())) return false;
            // Its own name in `channel` too: every upload of it matches there, and the channel must not lose to them (P1).
            docs.add(catalogDoc("CHANNEL", c.getId(), c.getYoutubeId(), c.getName(), c.getKeywords(), c.getName(),
                    c.getDescription(), ContentItemMapper.fromChannel(c), null));
            sources.put("channel:" + c.getYoutubeId(), exclusions(c));
        } else if (entity instanceof Playlist p) {
            if (!admitted(p.getStatus(), p.getVisibility(), p.getValidationStatus(), p.getYoutubeId())) return false;
            docs.add(catalogDoc("PLAYLIST", p.getId(), p.getYoutubeId(), p.getTitle(), p.getKeywords(), null,
                    p.getDescription(), ContentItemMapper.fromPlaylist(p), null));
            sources.put("playlist:" + p.getYoutubeId(), exclusions(p));
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

    /** A base field and its `_stem` twin; the per-field index analyzer picks the chain (spec §4.3). */
    private static void addText(Document doc, String field, String value) {
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
