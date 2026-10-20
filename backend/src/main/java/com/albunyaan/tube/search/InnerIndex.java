package com.albunyaan.tube.search;

import jakarta.annotation.PreDestroy;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.ConcurrentMergeScheduler;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * The inner index (inner-search spec R14, R16): one Lucene document per (admitted source, video it lists), rebuilt
 * from the corpus at every boot into a fresh temp directory, as the main index is. Only the crawler thread writes
 * (rebuild, replaceSource, merge, maybeRefresh); search threads acquire and release searchers.
 */
@Component
public class InnerIndex {
    private static final Logger log = LoggerFactory.getLogger(InnerIndex.class);
    static final int BATCH = 5_000;
    private static final long RETIRE_MS = 60_000;
    private static final String PREFIX = "fitrahtube-inner-";

    /** `cap`: the per-source cut this index was built with (R31). */
    private record Live(Path path, Directory dir, IndexWriter writer, SearcherManager searchers, int cap) {}

    private final Path base;
    private final InnerStore store;
    private final Clock clock;
    CrawlPacer.Sleeper sleeper = Thread::sleep;
    long refreshEveryMs = 60_000;
    private volatile Live live;
    private Live retired;
    private boolean closed; // under the monitor
    private long retiredAt, lastRefreshAt;
    private final AtomicLong timeouts = new AtomicLong();
    private volatile long rebuildCpuMs = -1, rebuildWallMs = -1;

    public InnerIndex(@Value("${search.index.base-dir:}") String baseDir, InnerStore store, Clock clock) {
        this.base = Path.of(baseDir.isEmpty() ? System.getProperty("java.io.tmpdir") : baseDir);
        this.store = store;
        this.clock = clock;
    }

    /**
     * R14 / R31: a fresh index from every corpus file (each cut to `cap`), added in batches of 5,000, each followed by a
     * sleep as long as it took (≤ 50 % of the one core), then swapped in. A failure keeps the old index.
     */
    void rebuild(Map<String, String> names, int cap) throws IOException, InterruptedException {
        long cpu0 = cpuNanos(), process0 = processCpuNanos(), wall0 = System.nanoTime();
        cap = clamp(cap);
        Files.createDirectories(base);
        if (live == null) sweepLeftovers();
        Path path = Files.createTempDirectory(base, PREFIX);
        Directory dir = FSDirectory.open(path);
        IndexWriter writer = null;
        try {
            writer = new IndexWriter(dir, config());
            int docs = 0, inBatch = 0;
            long batchStart = System.nanoTime();
            Set<String> indexed = new HashSet<>();
            for (Path file : InnerCorpus.files(store.corpusDir())) {
                String key = InnerCorpus.sourceKey(file);
                List<InnerCorpus.Item> items = InnerCorpus.read(file);
                if (items == null) continue; // unreadable: treated as missing; the crawler crawls that source again (§5)
                indexed.add(key);
                for (InnerCorpus.Item item : items.subList(0, Math.min(cap, items.size()))) {
                    if (!indexable(item)) continue;
                    writer.addDocument(doc(key, names.get(key), item));
                    docs++;
                    if (++inBatch == BATCH) {
                        sleeper.sleep((System.nanoTime() - batchStart) / 1_000_000);
                        inBatch = 0;
                        batchStart = System.nanoTime();
                    }
                }
            }
            untrustUnindexed(indexed);
            if (!swap(new Live(path, dir, writer, new SearcherManager(writer, null), cap))) return;
            rebuildCpuMs = (cpuNanos() - cpu0) / 1_000_000;
            rebuildWallMs = (System.nanoTime() - wall0) / 1_000_000;
            // processCpuMs: every thread, so the merge thread too, but also requests and any other work meanwhile
            log.info("inner-index built: docs={} bytes={} cpuMs={} processCpuMs={} wallMs={}", docs, bytes(), rebuildCpuMs,
                    (processCpuNanos() - process0) / 1_000_000, rebuildWallMs);
        } catch (IOException | InterruptedException | RuntimeException | Error e) { // an OOM too: no writer or directory leaks
            try {
                if (writer != null) writer.rollback();
                dir.close();
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            SearchIndex.delete(path);
            throw e;
        }
    }

    /** A complete crawl (R32 mark and sweep): the source's documents are replaced, cut to capPerSource. */
    void replaceSource(String sourceKey, String name, List<InnerCorpus.Item> items) throws IOException {
        Live l = live;
        if (l == null) return; // not built: the next rebuild reads the corpus
        l.writer().deleteDocuments(new Term("sourceKey", sourceKey));
        int cap = clamp(store.state().capPerSource);
        for (InnerCorpus.Item item : items.subList(0, Math.min(cap, items.size()))) {
            if (indexable(item)) l.writer().addDocument(doc(sourceKey, name, item));
        }
        refresh(); // sweeps are rare; a stale searcher would let the R18 gate hide what this crawl lists
    }

    /** A merge or a poll: only what changed (§6.4). */
    void merge(String sourceKey, String name, List<InnerCorpus.Item> changed, Collection<String> removedIds) throws IOException {
        Live l = live;
        if (l == null) return;
        for (String id : removedIds) l.writer().deleteDocuments(pair(sourceKey, id));
        for (InnerCorpus.Item item : changed) {
            l.writer().deleteDocuments(pair(sourceKey, item.id()));
            if (indexable(item)) l.writer().addDocument(doc(sourceKey, name, item));
        }
        maybeRefresh();
    }

    /** At most every refreshEveryMs (R14, RV M2: fewer tiny segments), and retires a swapped-out index after a minute. */
    void maybeRefresh() throws IOException {
        if (clock.millis() - lastRefreshAt >= refreshEveryMs) refresh();
    }

    private void refresh() throws IOException {
        Live l = live;
        if (l != null) l.searchers().maybeRefreshBlocking();
        lastRefreshAt = clock.millis();
        closeRetired();
    }

    /** A searcher on the current index, or null before the first build. Release it with release(). */
    IndexSearcher acquire() throws IOException {
        Live l = live;
        return l == null ? null : l.searchers().acquire();
    }

    void release(IndexSearcher searcher) throws IOException {
        searcher.getIndexReader().decRef();
    }

    boolean ready() {
        return live != null;
    }

    /** R26 / R14: inner hits are served only when an admin turned them on and the index is built. */
    boolean serving() {
        return store.state().serveInner && live != null;
    }

    static final long TRUST_MS = 30L * 86_400_000;

    /**
     * R18 stream gate: a stored stream is hidden for `sourceKey` only when that source's latest crawl was complete (not
     * short, and not stopped at a cap, which never lists the older items), at most 30 days old, and does not list the id
     * in this index. The index must hold all the crawl listed: every counted item stored (NewPipe drops items it cannot
     * parse), and no more than the cap this index and the current one cut a source to (R31 lowers it unattended).
     * Fails open: an unknown, untrusted or unreadable case, or no searcher (not built, closing), keeps today's behaviour.
     * `trace` (nullable) counts the lookups and their time for the slow-search line.
     */
    boolean gateDrops(String sourceKey, String id, SearchIndex.Trace trace) {
        InnerState s = store.state();
        Live l = live;
        if (!s.streamGate || l == null) return false;
        InnerState.Source src = s.sources.get(sourceKey);
        if (src == null) return false;
        long count = src.count, completeAt = src.completeAt;
        int items = src.items;
        if (completeAt == 0 || src.shortCrawl || src.capped || clock.millis() - completeAt > TRUST_MS
                || count < 0 || items < count - InnerCrawler.tolerance(count) || items > Math.min(clamp(s.capPerSource), l.cap())) {
            return false;
        }
        long started = System.nanoTime();
        try {
            IndexSearcher searcher = acquire();
            if (searcher == null) return false;
            try {
                return searcher.count(pair(sourceKey, id)) == 0;
            } finally {
                release(searcher);
            }
        } catch (IOException | RuntimeException e) { // AlreadyClosedException included (shutdown): never fails a search
            return false;
        } finally {
            if (trace != null) {
                trace.gateChecks++;
                trace.gateNs += System.nanoTime() - started;
            }
        }
    }

    /** Every source key whose listing holds this video (R34). Empty before the first build. */
    Set<String> sourceKeys(String id) throws IOException {
        IndexSearcher s = acquire();
        if (s == null) return Set.of();
        try {
            Set<String> out = new LinkedHashSet<>();
            for (ScoreDoc hit : s.search(new TermQuery(new Term("id", id)), 100).scoreDocs) {
                out.add(s.storedFields().document(hit.doc).get("sourceKey"));
            }
            return out;
        } finally {
            release(s);
        }
    }

    long bytes() {
        Live l = live;
        if (l == null) return 0;
        long total = 0;
        try {
            for (String file : l.dir().listAll()) {
                try {
                    total += l.dir().fileLength(file);
                } catch (IOException mergedAway) {
                    // a merge removed it between the listing and here
                }
            }
        } catch (IOException e) {
            return total;
        }
        return total;
    }

    int docs() throws IOException {
        IndexSearcher s = acquire();
        if (s == null) return 0;
        try {
            return s.getIndexReader().numDocs();
        } finally {
            release(s);
        }
    }

    void timedOut() {
        timeouts.incrementAndGet();
    }

    long timeouts() {
        return timeouts.get();
    }

    long rebuildCpuMs() {
        return rebuildCpuMs;
    }

    long rebuildWallMs() {
        return rebuildWallMs;
    }

    @PreDestroy
    synchronized void close() {
        closed = true;
        Live l = live;
        live = null; // a concurrent search sees "not ready", never a closed index
        close(l);
        close(retired);
        retired = null;
    }

    /**
     * R16: slim, ranked by score only. `c` = the item's own uploader (playlist sources), else the catalog name for a
     * channel source; a playlist's title is never a channel name.
     */
    static Document doc(String sourceKey, String channelName, InnerCorpus.Item item) {
        Document doc = new Document();
        doc.add(new StringField("id", item.id(), Field.Store.YES));
        doc.add(new StringField("sourceKey", sourceKey, Field.Store.YES));
        doc.add(new StoredField("source", "INNER"));
        String channel = item.c() != null ? item.c() : sourceKey.startsWith("channel:") ? channelName : null;
        SearchIndex.addText(doc, "title", item.t());
        SearchIndex.addText(doc, "channel", channel);
        doc.add(new StoredField("t", item.t()));
        if (channel != null && !channel.isEmpty()) doc.add(new StoredField("c", channel));
        if (item.u() != null) doc.add(new StoredField("u", item.u()));
        doc.add(new StoredField("d", item.d()));
        if (item.a() != null) doc.add(new StoredField("a", item.a()));
        return doc;
    }

    /**
     * A source whose corpus is missing or unreadable has no documents in the rebuilt index, so the R18 gate must not trust
     * its last crawl: it waits for the crawler to crawl it again. Crawler thread; no save under the monitor (the 5-min one
     * persists it, and a restart rebuilds and clears it again).
     */
    private void untrustUnindexed(Set<String> indexed) {
        InnerState s = store.state();
        synchronized (s) {
            s.sources.forEach((key, src) -> {
                if (!indexed.contains(key)) src.completeAt = 0;
            });
        }
    }

    /** A hand-edited line without an id or title is skipped, never fatal; a blank title too (upstream drops only null). */
    private static boolean indexable(InnerCorpus.Item item) {
        return item.id() != null && item.t() != null && !item.t().isBlank();
    }

    /** A hand-edited state.json can hold any cap; a bad one must not throw after replaceSource buffered its delete. */
    private static int clamp(int cap) {
        return Math.min(Math.max(cap, 1), InnerState.MAX_PER_SOURCE);
    }

    /**
     * A kill -9 skips close(), and each leftover is a whole index: before the first build, delete our directories that
     * earlier processes left. Real directories directly under base only; a link is never followed.
     * ponytail: assumes one backend per base-dir; give each its own base-dir if two ever share a host.
     */
    private void sweepLeftovers() throws IOException {
        try (Stream<Path> all = Files.list(base)) {
            for (Path p : all.toList()) {
                if (p.getFileName().toString().startsWith(PREFIX) && Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    SearchIndex.delete(p);
                }
            }
        }
    }

    private static Query pair(String sourceKey, String id) {
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term("sourceKey", sourceKey)), Occur.FILTER)
                .add(new TermQuery(new Term("id", id)), Occur.FILTER)
                .build();
    }

    private static IndexWriterConfig config() {
        ConcurrentMergeScheduler merges = new ConcurrentMergeScheduler();
        merges.setMaxMergesAndThreads(1, 1); // one merge thread (R14, RV M2)
        return new IndexWriterConfig(SearchText.INDEX).setMergeScheduler(merges);
    }

    /** False once closed: a rebuild that finishes after shutdown closes its index instead of leaking it. */
    private synchronized boolean swap(Live next) {
        if (closed) {
            close(next);
            return false;
        }
        close(retired); // an older retired index is long out of use
        retired = live;
        retiredAt = clock.millis();
        live = next;
        lastRefreshAt = clock.millis();
        return true;
    }

    /** A retired index closes once in-flight searches (≤ 40 ms) are long done. */
    private synchronized void closeRetired() {
        if (retired != null && clock.millis() - retiredAt >= RETIRE_MS) {
            close(retired);
            retired = null;
        }
    }

    private static void close(Live l) {
        if (l == null) return;
        try {
            l.searchers().close();
            l.writer().rollback(); // nothing to keep: the index is rebuilt at every boot
            l.dir().close();
        } catch (IOException e) {
            log.warn("inner index close failed", e);
        }
        SearchIndex.delete(l.path());
    }

    private static long cpuNanos() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    /** -1 on a JVM without the com.sun extension (the delta then reads 0). */
    private static long processCpuNanos() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getProcessCpuTime() : -1;
    }
}
