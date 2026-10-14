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
import jakarta.annotation.PreDestroy;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
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
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
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

    // Query-time stream visibility (spec §4.2). Replaced or mutated only on the writer thread.
    /** Admitted channel/playlist source keys ("channel:UC…") -> the video ids that source excludes. */
    private volatile Map<String, Set<String>> admittedSources = new ConcurrentHashMap<>();
    /** youtubeId of every Video document, admitted or not: such an id never shows as a stream. */
    private volatile Set<String> videoIds = ConcurrentHashMap.newKeySet();

    /** A fresh temp directory per Spring context, deleted in {@link #close()} (Ruling F15): MMap'd, off-heap, throwaway. */
    @Autowired
    public SearchIndex(ChannelRepository channels, PlaylistRepository playlists, VideoRepository videos,
                       SearchableStreamRepository streams) throws IOException {
        this(FSDirectory.open(Files.createTempDirectory("fitrahtube-search-")), channels, playlists, videos, streams,
                5_000);
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
        Path path = dir instanceof FSDirectory fs ? fs.getDirectory() : null; // unreadable once dir is closed
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
            log.error("search index build failed; retrying in {} s", retryMs / 1000, e);
            executor.schedule(() -> buildCatalog(Math.min(retryMs * 2, MAX_RETRY_MS)), retryMs, TimeUnit.MILLISECONDS);
            return;
        }
        ready.countDown();
        executor.execute(() -> loadStreams(null, 0, FIRST_RETRY_MS));
    }

    /**
     * Stream half: one page of 500 per writer task, so catalog events interleave with the ≈46k-document
     * load instead of queuing behind it (spec §5.7). Until it finishes, results hold catalog items only.
     */
    private void loadStreams(String afterId, int loaded, long retryMs) {
        List<SearchableStream> page;
        try {
            page = streams.findVisiblePage(afterId, STREAM_PAGE);
            for (SearchableStream s : page) {
                if (s.getStreamId() == null || s.getTitle() == null) continue;
                writer.updateDocument(streamKey(s.getStreamId()), streamDoc(streamDto(s), s.getSourceKeys()));
            }
            searchers.maybeRefreshBlocking();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (executor.isShutdown()) return;
            log.error("search index stream page after {} failed; retrying in {} s", afterId, retryMs / 1000, e);
            executor.schedule(() -> loadStreams(afterId, loaded, Math.min(retryMs * 2, MAX_RETRY_MS)),
                    retryMs, TimeUnit.MILLISECONDS);
            return;
        }
        int total = loaded + page.size();
        if (page.size() == STREAM_PAGE) {
            String last = page.get(page.size() - 1).getStreamId();
            executor.execute(() -> loadStreams(last, total, FIRST_RETRY_MS));
        } else {
            // Spec §5.1 step 3: the line that measures the real counts and size after each deploy.
            log.info("search index built: {} streams={} bytes={} ms={}",
                    catalogCounts, total, bytes(), System.currentTimeMillis() - buildStartedAt);
        }
    }

    /**
     * Re-reads the catalog and swaps it in with one refresh: readers never see half a catalog. Every
     * document is built before the old ones are deleted, so a failure leaves the last good catalog.
     */
    void replaceCatalog() throws Exception {
        List<Channel> cs = channels.findByStatus("APPROVED");
        List<Playlist> ps = playlists.findByStatus("APPROVED");
        List<Video> vs = videos.findAllUnordered();
        Map<String, Set<String>> sources = new ConcurrentHashMap<>();
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Document> docs = new ArrayList<>();
        int channelDocs = 0, playlistDocs = 0;
        for (Channel c : cs) if (admit(c, sources, ids, docs)) channelDocs++;
        for (Playlist p : ps) if (admit(p, sources, ids, docs)) playlistDocs++;
        for (Video v : vs) admit(v, sources, ids, docs);
        int videoDocs = docs.size() - channelDocs - playlistDocs;
        replace(new Term("source", "CATALOG"), docs); // one atomic block: a failed add keeps the old catalog
        admittedSources = sources;
        videoIds = ids;
        searchers.maybeRefreshBlocking();
        catalogCounts = String.format("channels=%d playlists=%d videos=%d blockedVideos=%d",
                channelDocs, playlistDocs, videoDocs, ids.size() - videoDocs);
        log.info("search index catalog: {}", catalogCounts);
    }

    /** Safety net (spec §5.4): catalog only; streams have one fully hooked writer and are re-read at boot. */
    @Scheduled(fixedDelay = 6, initialDelay = 6, timeUnit = TimeUnit.HOURS)
    public void reconcile() {
        executor.execute(() -> {
            try {
                replaceCatalog();
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                if (executor.isShutdown()) return;
                log.warn("search index reconcile failed; keeping the last good index", e);
            }
        });
    }

    // ---- freshness (spec §5.2, §5.3) -----------------------------------------------------------

    /**
     * The entity is the caller's live object: it is turned into Lucene documents here, on the caller's
     * thread, so later changes to it cannot reach the index (and a save costs no Firestore read, spec §5.6).
     * An event without an entity (delete, partial update) is re-read by id on the writer thread.
     */
    @EventListener
    public void onCatalogChanged(CatalogChanged event) {
        String what = "catalog " + event.type() + " " + event.docId();
        Built snapshot = null;
        if (event.entity() != null) {
            try {
                snapshot = build(event.entity());
            } catch (Throwable e) { // Firestore already committed: the publisher must not see a failure (spec §7)
                log.warn("search index update dropped ({}): snapshot failed", what, e);
                return;
            }
        }
        Built built = snapshot;
        submit(what, () -> applyCatalogChange(event, built != null ? built : build(reload(event.type(), event.docId()))));
    }

    /** What {@link #admit} produced for one entity, not yet applied to the index or the visibility maps. */
    private record Built(Map<String, Set<String>> sources, Set<String> videoIds, List<Document> docs) {}

    private static Built build(Object entity) throws IOException {
        Built built = new Built(new HashMap<>(), new HashSet<>(), new ArrayList<>());
        admit(entity, built.sources(), built.videoIds(), built.docs());
        return built;
    }

    private void applyCatalogChange(CatalogChanged event, Built built) throws Exception {
        Term key = new Term("key", event.type() + ":" + event.docId());
        // The old source key goes first: the new one may be the same key, and must not lose it.
        String oldId = storedValues(key.text(), "id").stream().findFirst().orElse(null);
        if (oldId != null && !"VIDEO".equals(event.type())) {
            admittedSources.remove(event.type().toLowerCase(Locale.ROOT) + ":" + oldId);
        }
        admittedSources.putAll(built.sources());
        videoIds.addAll(built.videoIds()); // a deleted Video's id stays in videoIds until the next reconcile: its stream stays hidden (fail closed)
        replace(key, built.docs());
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
                    if (!executor.isShutdown()) log.warn("search index update failed ({})", what, e);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("search index update dropped ({}): writer stopped", what, e);
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
            for (int tier = 1; tier <= 3 && out.size() < Math.min(want, MIN_CONFIDENT_HITS); tier++) {
                boolean wasEmpty = out.isEmpty();
                collect(searcher, filtered(SearchText.tier(tier, tokens), type, catalogOnly), want, allowedIds, out, seen);
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

    private void awaitReady() {
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

    /** Over-fetches 2x and pages with searchAfter until `want` visible, unseen, allowed items are found. */
    private void collect(IndexSearcher searcher, Query query, int want, Set<String> allowedIds,
                         List<ContentItemDto> out, Set<String> seen) throws IOException {
        ScoreDoc after = null;
        while (out.size() < want) {
            int n = Math.max(10, 2 * (want - out.size()));
            TopDocs top = after == null ? searcher.search(query, n) : searcher.searchAfter(after, query, n);
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
            docs.add(catalogDoc("CHANNEL", c.getId(), c.getYoutubeId(), c.getName(), c.getKeywords(), null,
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
     * Tests only: runs every queued writer task, including tasks those tasks queue in turn, and waits
     * out a task still running when the queue drains (Ruling F1). A retry scheduled for later is not waited for.
     */
    void awaitWrites() throws Exception {
        do {
            executor.submit(() -> { }).get(10, TimeUnit.SECONDS);
        } while (executor.getQueue().stream().anyMatch(t -> ((Delayed) t).getDelay(TimeUnit.NANOSECONDS) <= 0)
                || executor.getActiveCount() > 0);
    }
}
