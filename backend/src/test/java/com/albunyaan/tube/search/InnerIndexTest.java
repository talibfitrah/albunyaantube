package com.albunyaan.tube.search;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Spec R14, R16: the boot rebuild, the crawler's two writes, the stored fields and the 60 s refresh. */
class InnerIndexTest {

    @TempDir Path tmp;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    InnerStore store;
    InnerIndex index;
    final List<Long> slept = new ArrayList<>();
    static final String CH = InnerCrawlerTest.CH, PL = InnerCrawlerTest.PL;

    @BeforeEach
    void setUp() {
        store = new InnerStore(tmp.resolve("inner").toString());
        index = new InnerIndex(tmp.resolve("base").toString(), store, clock);
        index.sleeper = slept::add;
        index.refreshEveryMs = 0;
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    void corpus(String key, List<InnerCorpus.Item> items) throws IOException {
        InnerCorpus.write(store.corpusFile(key), items);
    }

    Document find(String key, String id) throws IOException {
        IndexSearcher s = index.acquire();
        try {
            TopDocs top = s.search(new org.apache.lucene.search.BooleanQuery.Builder()
                    .add(new TermQuery(new Term("sourceKey", key)), org.apache.lucene.search.BooleanClause.Occur.FILTER)
                    .add(new TermQuery(new Term("id", id)), org.apache.lucene.search.BooleanClause.Occur.FILTER).build(), 1);
            return top.scoreDocs.length == 0 ? null : s.storedFields().document(top.scoreDocs[0].doc);
        } finally {
            index.release(s);
        }
    }

    List<Path> indexDirs() throws IOException {
        if (!Files.exists(tmp.resolve("base"))) return List.of();
        try (Stream<Path> all = Files.list(tmp.resolve("base"))) {
            return all.filter(p -> p.getFileName().toString().startsWith("fitrahtube-inner-")).toList();
        }
    }

    @Test
    void beforeTheFirstBuildNothingIsServedAndNothingFails() throws IOException {
        assertFalse(index.ready());
        assertNull(index.acquire());
        assertEquals(0, index.bytes());
        assertEquals(0, index.docs());
        assertEquals(Set.of(), index.sourceKeys("lesson00001"));
        store.state().serveInner = true;
        assertFalse(index.serving());
    }

    @Test
    void aRebuildIndexesEveryReadableCorpusFileInBatchesThatSleepAsLongAsTheyTook() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 6_000, "Lezing"));
        corpus(PL, InnerCrawlerTest.items("story", 0, 10, "Verhaal"));
        Files.write(store.corpusDir().resolve("channel_UCbadbadbadbadbadbadbadbad.jsonl.gz"), new byte[]{1, 2, 3});
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(InnerIndex.class)).addAppender(logs);
        index.rebuild(Map.of(CH, "Dawah Nederland"), 10_000);
        ((Logger) LoggerFactory.getLogger(InnerIndex.class)).detachAppender(logs);
        assertTrue(index.ready());
        assertEquals(6_010, index.docs());
        assertEquals(1, slept.size(), "one full batch of 5,000: one duty-cycle sleep");
        assertTrue(index.bytes() > 0);
        assertEquals(1, indexDirs().size());
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("inner-index built: docs=6010 bytes=")
                && e.getFormattedMessage().contains("cpuMs=") && e.getFormattedMessage().contains("wallMs=")));
    }

    @Test
    void theCapCutsEachSourceToItsNewestItemsAtRebuild() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 300, "x"));
        index.rebuild(Map.of(), 100);
        assertEquals(100, index.docs());
        assertTrue(index.sourceKeys(InnerCrawlerTest.id("lesson", 0)).contains(CH));
        assertFalse(index.sourceKeys(InnerCrawlerTest.id("lesson", 100)).contains(CH));
    }

    @Test
    void replaceSourceSweepsAndMergeTouchesOnlyWhatChanged() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.rebuild(Map.of(CH, "Dawah Nederland"), 10_000);
        index.replaceSource(CH, "Dawah Nederland", InnerCrawlerTest.items("story", 0, 2, "x"));
        assertEquals(2, index.docs());
        assertFalse(index.sourceKeys(InnerCrawlerTest.id("lesson", 0)).contains(CH));
        String renamed = InnerCrawlerTest.id("story", 0);
        index.merge(CH, "Dawah Nederland", List.of(new InnerCorpus.Item(renamed, "Renamed lecture", 60, null, null, null)),
                List.of(InnerCrawlerTest.id("story", 1)));
        assertEquals(1, index.docs());
        assertEquals("Renamed lecture", find(CH, renamed).get("t"));
    }

    @Test
    void aDocumentCarriesEverythingTheResultNeeds() throws Exception {
        corpus(CH, List.of(new InnerCorpus.Item("ilgiwqpcOC8", "Lezing: 'De rechtvaardige kalief'", 2712, 20180, null, null)));
        corpus(PL, List.of(new InnerCorpus.Item("cBjnzYHSV4M", "Hoe voed ik mijn kind op?", 1800, null, "Dawah Nederland", "UCfassTU6mGWIj-yb26_ADjQ")));
        index.rebuild(Map.of(CH, "Dawah Nederland", PL, "Nederlandse lezingen"), 10_000);
        Document ch = find(CH, "ilgiwqpcOC8");
        assertEquals("INNER", ch.get("source"));
        assertEquals("Dawah Nederland", ch.get("c"), "a channel source's items carry the catalog name");
        assertEquals(2712, ch.getField("d").numericValue().intValue());
        assertEquals(20180, ch.getField("a").numericValue().intValue());
        assertNull(ch.get("u"));
        Document pl = find(PL, "cBjnzYHSV4M");
        assertEquals("Dawah Nederland", pl.get("c"), "a playlist item carries its own uploader");
        assertEquals("UCfassTU6mGWIj-yb26_ADjQ", pl.get("u"));
        assertNull(pl.getField("a"));
    }

    /** Task 16 hand-off: only a null title is dropped upstream, so a blank one must never become a document. */
    @Test
    void aBlankTitleIsNeverIndexed() throws Exception {
        corpus(CH, List.of(new InnerCorpus.Item("ilgiwqpcOC8", " ", 1, null, null, null),
                new InnerCorpus.Item("cBjnzYHSV4M", "Hoe voed ik mijn kind op?", 1, null, null, null)));
        index.rebuild(Map.of(), 10_000);
        assertEquals(1, index.docs());
        index.replaceSource(PL, "x", List.of(new InnerCorpus.Item("ilgiwqpcOC8", "", 1, null, null, null)));
        assertFalse(index.sourceKeys("ilgiwqpcOC8").contains(PL));
        assertTrue(index.sourceKeys("cBjnzYHSV4M").contains(CH), "replacing one source never touches another");
        index.merge(CH, "x", List.of(new InnerCorpus.Item("cBjnzYHSV4M", "  ", 1, null, null, null)), List.of());
        assertFalse(index.sourceKeys("cBjnzYHSV4M").contains(CH), "a title gone blank drops the old document");
        assertEquals(0, index.docs());
    }

    long hits(String field, String term) throws IOException {
        IndexSearcher s = index.acquire();
        try {
            return s.count(new TermQuery(new Term(field, term)));
        } finally {
            index.release(s);
        }
    }

    @Test
    void theTextFieldsUseTheMainIndexAnalyzers() throws Exception {
        corpus(CH, List.of(new InnerCorpus.Item("ilgiwqpcOC8", "Lectures on Patience", 1, null, null, null)));
        index.rebuild(Map.of(CH, "Dawah Nederland"), 10_000);
        assertEquals(1, hits("title", "lectures"), "base chain: lower-cased, unstemmed");
        assertEquals(0, hits("title", "lecture"));
        assertEquals(1, hits("title_stem", "lecture"), "stem chain");
        assertEquals(1, hits("channel", "nederland"));
    }

    /** R16: `c` is the catalog name only for channel sources; a playlist's own title is never a channel name. */
    @Test
    void aPlaylistItemWithoutAnUploaderGetsNoChannel() throws Exception {
        corpus(PL, List.of(new InnerCorpus.Item("ilgiwqpcOC8", "x", 1, null, null, null)));
        index.rebuild(Map.of(PL, "Nederlandse lezingen"), 10_000);
        assertNull(find(PL, "ilgiwqpcOC8").get("c"));
        index.merge(PL, "Nederlandse lezingen", List.of(new InnerCorpus.Item("cBjnzYHSV4M", "y", 1, null, null, null)), List.of());
        assertNull(find(PL, "cBjnzYHSV4M").get("c"));
        assertEquals(0, hits("channel", "nederlandse"));
    }

    /** A hand-edited state.json: a cap below 1 must not throw once replaceSource has buffered its delete. */
    @Test
    void aCapBelowOneIsClampedNeverThrown() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.rebuild(Map.of(), -1);
        assertEquals(1, index.docs());
        store.state().capPerSource = -5;
        index.replaceSource(CH, "x", InnerCrawlerTest.items("story", 0, 3, "x"));
        assertEquals(1, index.docs());
        assertTrue(index.sourceKeys(InnerCrawlerTest.id("story", 0)).contains(CH));
    }

    /** A kill -9 skips close(): the first rebuild sweeps what it left, without following a link out of base-dir. */
    @Test
    void theFirstRebuildSweepsLeftoversOfAKilledProcess() throws Exception {
        Path base = tmp.resolve("base"), outside = tmp.resolve("outside");
        Files.createDirectories(base.resolve("fitrahtube-inner-1234/sub"));
        Files.write(base.resolve("fitrahtube-inner-1234/sub/_0.cfs"), new byte[]{1});
        Files.createDirectories(outside);
        Files.write(outside.resolve("keep"), new byte[]{1});
        Path link = Files.createSymbolicLink(base.resolve("fitrahtube-inner-link"), outside);
        Files.createDirectories(base.resolve("fitrahtube-search-5678"));
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.rebuild(Map.of(), 10_000);
        assertFalse(Files.exists(base.resolve("fitrahtube-inner-1234")));
        assertTrue(Files.exists(outside.resolve("keep")));
        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.exists(base.resolve("fitrahtube-search-5678")), "the main index's directories are not ours");
        assertEquals(2, indexDirs().size(), "the live index and the untouched link");
    }

    @Test
    void aRebuildThatFinishesAfterCloseLeavesNothingBehind() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.close();
        index.rebuild(Map.of(), 10_000);
        assertFalse(index.ready());
        assertEquals(List.of(), indexDirs());
    }

    @Test
    void anErrorMidRebuildKeepsTheOldIndexAndLeavesNoDirectory() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.rebuild(Map.of(), 10_000);
        Map<String, String> failing = new HashMap<>() {
            @Override
            public String get(Object key) {
                throw new OutOfMemoryError("Java heap space"); // I2: an Error mid-build, after the writer opened
            }
        };
        assertThrows(OutOfMemoryError.class, () -> index.rebuild(failing, 10_000));
        assertEquals(3, index.docs(), "the old index keeps serving");
        assertEquals(1, indexDirs().size(), "the failed build's directory is deleted");
    }

    @Test
    void sourceKeysListsEverySourceThatHoldsTheVideo() throws Exception {
        corpus(CH, List.of(new InnerCorpus.Item("ilgiwqpcOC8", "x", 1, null, null, null)));
        corpus(PL, List.of(new InnerCorpus.Item("ilgiwqpcOC8", "x", 1, null, "y", null)));
        index.rebuild(Map.of(), 10_000);
        assertEquals(Set.of(CH, PL), index.sourceKeys("ilgiwqpcOC8"));
        index.merge(PL, "y", List.of(), List.of("ilgiwqpcOC8"));
        assertEquals(Set.of(CH), index.sourceKeys("ilgiwqpcOC8"), "a removal drops only that source's copy");
    }

    /**
     * Codex P2-7: sourceKeys read only the top 100 copies, before R34's admission filter, so a 101st source (or an admitted
     * one behind 100 copies kept from rejected sources) went unexcluded and kept the reported video searchable.
     */
    @Test
    void sourceKeysListsEverySourceNotJustTheFirstHundred() throws Exception {
        Set<String> all = new java.util.TreeSet<>();
        for (int i = 0; i < 150; i++) {
            String key = String.format("playlist:PLcopy%03d", i);
            corpus(key, List.of(new InnerCorpus.Item("ilgiwqpcOC8", "x", 1, null, null, null)));
            all.add(key);
        }
        index.rebuild(Map.of(), 10_000);
        assertEquals(all, index.sourceKeys("ilgiwqpcOC8"));
    }

    @Test
    void aSecondRebuildSwapsAndTheOldDirectoryGoesAMinuteLater() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.rebuild(Map.of(), 10_000);
        index.rebuild(Map.of(), 2);
        assertEquals(2, index.docs());
        assertEquals(2, indexDirs().size(), "the retired index outlives in-flight searches");
        now.set(now.get().plusSeconds(61));
        index.maybeRefresh();
        assertEquals(1, indexDirs().size());
    }

    @Test
    void closeDeletesItsDirectories() throws Exception {
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.rebuild(Map.of(), 10_000);
        index.close();
        assertEquals(List.of(), indexDirs());
    }

    @Test
    void searchersRefreshAtMostEverySixtySeconds() throws Exception {
        index.refreshEveryMs = 60_000;
        corpus(CH, InnerCrawlerTest.items("lesson", 0, 3, "x"));
        index.rebuild(Map.of(), 10_000);
        index.merge(CH, "x", InnerCrawlerTest.items("new", 0, 1, "x"), List.of());
        assertEquals(3, index.docs(), "not refreshed yet");
        now.set(now.get().plusSeconds(61));
        index.maybeRefresh();
        assertEquals(4, index.docs());
    }
}
