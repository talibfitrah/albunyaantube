package com.albunyaan.tube.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.SearchableStream;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Spec §9.1: the ladder over a fixture corpus (titles from the live catalog, §2.2), built through the
 * real startup path with mocked repositories. No Firestore. If a ranking row fails, tune only the
 * boosts / the 0.5 concept weight in SearchText (keep title > keywords > channel > description);
 * never change an expectation or a fixture title.
 */
class SearchIndexTest {

    private ChannelRepository channelRepo;
    private PlaylistRepository playlistRepo;
    private VideoRepository videoRepo;
    private SearchableStreamRepository streamRepo;
    private SearchIndex index;

    // ---- fixture -------------------------------------------------------------------------------

    private static Channel channel(String youtubeId, String name) {
        Channel c = new Channel(youtubeId);
        c.setId("doc-" + youtubeId);
        c.setName(name);
        c.setStatus("APPROVED");
        return c;
    }

    private static Playlist playlist(String youtubeId, String title) {
        Playlist p = new Playlist(youtubeId);
        p.setId("doc-" + youtubeId);
        p.setTitle(title);
        p.setStatus("APPROVED");
        return p;
    }

    private static Video video(String youtubeId, String title, String channelTitle) {
        Video v = new Video(youtubeId);
        v.setId("doc-" + youtubeId);
        v.setTitle(title);
        v.setChannelTitle(channelTitle);
        v.setStatus("APPROVED");
        return v;
    }

    private static SearchableStream stream(String id, String title, String... sourceKeys) {
        SearchableStream s = new SearchableStream();
        s.setStreamId(id);
        s.setTitle(title);
        s.setSourceKeys(new ArrayList<>(List.of(sourceKeys)));
        s.setVisible(true);
        return s;
    }

    private static <T> T with(T item, Consumer<T> change) {
        change.accept(item);
        return item;
    }

    private static Timestamp daysAgo(int days) {
        return Timestamp.ofTimeSecondsAndNanos(Instant.now().minus(Duration.ofDays(days)).getEpochSecond(), 0);
    }

    private static List<Channel> channels() {
        return List.of(
                channel("UCzakir", "Dr Zakir Naik"),
                channel("UCzakirAr", "Dr. Zakir Naik in arabic"),
                channel("UCalafasy", "Alafasy"),
                channel("UCquranTv", "Quran TV"),
                channel("UCksaQuran", "KSA Qur’an TV"),
                channel("UCadawi", "الشيخ مصطفى العدوي"),
                channel("UChudaa", "Dar al-Hudaa – Opvoeding & Educatie voor kinderen"),
                with(channel("UChalal", "Halal Kitchen"), c -> c.getExcludedItems().setVideos(List.of("halal-excluded"))),
                // Production reads only status == APPROVED; these exercise the index's own admission rule.
                with(channel("UCzakirPending", "Zakir Pending Channel"), c -> c.setStatus("PENDING")),
                with(channel("UCzakirPersonal", "Zakir Personal Channel"), c -> c.setVisibility("PERSONAL")),
                with(channel("UCzakirArchived", "Zakir Archived Channel"), c -> c.setValidationStatus(ValidationStatus.ARCHIVED)),
                with(channel("UCrejected", "Rejected Source"), c -> c.setStatus("REJECTED")));
    }

    private static List<Playlist> playlists() {
        return List.of(
                playlist("PLusool", "شرح الأصول من علم الأصول"),
                playlist("PLaqeedah", "شرح العقيدة السفارينية"),
                playlist("PLarabicKids", "Learn Arabic for Kids – تعليم اللغة العربية للأطفال"),
                playlist("PLtafsir", "Tafsir Ibn Kathir – Surah Al-Fatiha"),
                with(playlist("PLideeen", "Ideeën voor Ramadan"), p -> p.setDescription("Activiteiten voor het hele gezin")));
    }

    private static List<Video> videos() {
        return List.of(
                video("vQuranKareem", "القرآن الكريم كاملاً للقارئ ابراهيم الاخضر", null),
                video("vQuranMajeed", "القرآن المجيد", null),
                with(video("vYusuf", "Prophet Yusuf Story", null), v -> v.setUploadedAt(daysAgo(10))),
                video("vSalahClip", "كليب الصلاة", null),
                video("vPrayerKey", "Prayer is the Key", null),
                with(video("vAdhkar", "أذكار المساء", null), v -> v.setKeywords(List.of(
                        "quran", "adhkar", "dua", "evening", "morning", "dhikr", "remembrance", "protection",
                        "sunnah", "hisn", "muslim", "daily", "routine", "peace", "heart", "reminder", "night"))),
                video("vKahf", "Surah Al-Kahf", "Alafasy"),
                video("vYasin", "Surah Yasin", "Alafasy"),
                video("vQa", "Questions about Islam – Q&A session", "Dr Zakir Naik"),
                // Video documents that are not admitted: they only block the streams with the same id.
                with(video("halal-archived", "Halal Cooking 2", null), v -> v.setValidationStatus(ValidationStatus.ARCHIVED)),
                with(video("halal-rejected", "Halal Cooking Rejected", null), v -> v.setStatus("REJECTED")),
                with(video("halal-unavailable", "Halal Cooking Unavailable", null), v -> v.setValidationStatus(ValidationStatus.UNAVAILABLE)));
    }

    private static List<SearchableStream> streams() {
        return List.of(
                stream("halal-ok", "Halal Cooking", "channel:UChalal"),
                stream("halal-archived", "Halal Cooking 2", "channel:UChalal"),
                stream("halal-rejected", "Halal Cooking Rejected", "channel:UChalal"),
                stream("halal-unavailable", "Halal Cooking Unavailable", "channel:UChalal"),
                stream("halal-excluded", "Halal Cooking Excluded", "channel:UChalal"),
                stream("halal-from-rejected", "Halal Cooking From A Rejected Channel", "channel:UCrejected"),
                stream("vYusuf", "Prophet Yusuf Story (stream copy)", "channel:UChalal"),
                stream("orphan-lecture", "Orphan Lecture", "channel:UChalal"),
                stream("alafasy-live", "Alafasy Live Recitation", "channel:UCalafasy"));
    }

    private SearchIndex newIndex(List<Channel> cs, List<Playlist> ps, List<Video> vs, List<SearchableStream> ss,
                                 long readyWaitMs) throws Exception {
        channelRepo = mock(ChannelRepository.class);
        playlistRepo = mock(PlaylistRepository.class);
        videoRepo = mock(VideoRepository.class);
        streamRepo = mock(SearchableStreamRepository.class);
        when(channelRepo.findByStatus("APPROVED")).thenReturn(cs);
        when(playlistRepo.findByStatus("APPROVED")).thenReturn(ps);
        when(videoRepo.findAllUnordered()).thenReturn(vs);
        when(streamRepo.findVisiblePage(isNull(), eq(SearchIndex.STREAM_PAGE))).thenReturn(ss);
        return new SearchIndex(new ByteBuffersDirectory(), channelRepo, playlistRepo, videoRepo, streamRepo, readyWaitMs);
    }

    private static SearchIndex started(SearchIndex index) throws Exception {
        index.onReady();
        index.awaitWrites();
        return index;
    }

    private static List<String> ids(SearchIndex.Hits hits) {
        return hits.items().stream().map(ContentItemDto::getId).toList();
    }

    private List<String> ids(String query) throws Exception {
        return ids(index.search(query, null, false, 50, null));
    }

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final ch.qos.logback.classic.Logger indexLog =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(SearchIndex.class);

    @BeforeEach
    void setUp() throws Exception {
        logs.start();
        indexLog.addAppender(logs);
        index = started(newIndex(channels(), playlists(), videos(), streams(), 5_000));
    }

    @AfterEach
    void detachLogs() {
        indexLog.detachAppender(logs);
    }

    private List<ILoggingEvent> logged(Level level) {
        return new ArrayList<>(logs.list).stream().filter(e -> e.getLevel() == level).toList();
    }

    // ---- §9.1 ladder ---------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "zakit, UCzakir, approximate",
            "zaikr, UCzakir, approximate",
            "القُرْآنُ الكَرِيمُ, vQuranKareem, exact",
            "الاصول, PLusool, exact",
            "العقيده, PLaqeedah, exact",
            "مصطفي, UCadawi, exact",
            "عقيدة, PLaqeedah, exact",
            "tafseer, PLtafsir, related",
            "kinderen, UChudaa, exact",
            "ideeen, PLideeen, exact",
            "arabic learn, PLarabicKids, exact",
            "story of yusuf, vYusuf, exact"
    })
    void firstHitAndMatchTier(String query, String firstId, String match) throws Exception {
        SearchIndex.Hits hits = index.search(query, null, false, 50, null);
        assertEquals(firstId, ids(hits).get(0));
        assertEquals(match, hits.match());
    }

    @Test
    void anExactWordPutsBothZakirChannelsFirstAndNeverShowsUnadmittedOnes() throws Exception {
        SearchIndex.Hits hits = index.search("zakir", null, false, 50, null);
        List<String> ids = ids(hits);
        assertEquals("exact", hits.match());
        assertEquals(Set.of("UCzakir", "UCzakirAr"), Set.copyOf(ids.subList(0, 2)));
        assertFalse(ids.contains("UCzakirPending") || ids.contains("UCzakirPersonal") || ids.contains("UCzakirArchived"));
    }

    @Test
    void aPrefixWhileTypingFindsTheChannelAndItsVideos() throws Exception {
        SearchIndex.Hits hits = index.search("alafa", null, false, 50, null);
        assertEquals("exact", hits.match());
        assertTrue(ids(hits).containsAll(List.of("UCalafasy", "vKahf")));
    }

    @Test
    void apostrophesFoldAndShortTitlesOutrankKeywordOnlyMatches() throws Exception {
        SearchIndex.Hits hits = index.search("quran", null, false, 50, null);
        List<String> ids = ids(hits);
        assertEquals("exact", hits.match());
        assertTrue(ids.contains("UCksaQuran"), "KSA Qur’an TV must match quran");
        assertTrue(ids.indexOf("UCquranTv") < ids.indexOf("vAdhkar"), "Quran TV must outrank a keyword-only match");
    }

    @Test
    void aTransliterationReachesItsConceptAsRelated() throws Exception {
        SearchIndex.Hits hits = index.search("koran", null, false, 50, null);
        assertEquals("related", hits.match());
        assertTrue(ids(hits).contains("UCquranTv"));
    }

    @Test
    void aDutchWordFindsItsConceptAcrossScripts() throws Exception {
        SearchIndex.Hits hits = index.search("gebed", null, false, 50, null);
        assertEquals("related", hits.match());
        assertTrue(Set.of("vSalahClip", "vPrayerKey").contains(ids(hits).get(0)));
    }

    @Test
    void nonsenseFindsNothing() throws Exception {
        SearchIndex.Hits hits = index.search("xqzvbnm", null, false, 50, null);
        assertTrue(hits.items().isEmpty());
        assertEquals("none", hits.match());
    }

    // ---- in-tab (spec §4.7) --------------------------------------------------------------------

    @Test
    void inTabSearchCoversTitleDescriptionAndNameCaseInsensitivelyAndSkipsStreams() throws Exception {
        assertEquals(List.of("PLtafsir"), ids(index.search("TAFSIR", "PLAYLIST", true, 20, null)));
        assertEquals(List.of("PLideeen"), ids(index.search("activiteiten", "PLAYLIST", true, 20, null)));
        assertEquals(List.of("UChalal"), ids(index.search("kitchen", "CHANNEL", true, 20, null)));
        assertTrue(ids(index.search("halal", "VIDEO", true, 20, null)).isEmpty());
    }

    @Test
    void filteredInTabSearchKeepsOnlyTheAllowedMembers() throws Exception {
        assertEquals(List.of("vYasin"), ids(index.search("surah", "VIDEO", true, 20, Set.of("vYasin"))));
    }

    // ---- stream visibility (spec §4.2) ---------------------------------------------------------

    @Test
    void aStreamShowsOnlyWhileAnAdmittedSourceKeepsItAndNoVideoDocumentClaimsIt() throws Exception {
        // archived / rejected / unavailable Video documents, a channel exclusion and a rejected-only source all hide
        assertEquals(List.of("halal-ok"), ids(index.search("halal", "VIDEO", false, 50, null)));
    }

    @Test
    void aStreamWithAVideoDocumentIsAnsweredByTheCatalogDocument() throws Exception {
        List<ContentItemDto> items = index.search("yusuf", null, false, 50, null).items();
        assertEquals(1, items.size());
        assertEquals("vYusuf", items.get(0).getId());
        assertEquals(10, items.get(0).getUploadedDaysAgo());
    }

    @Test
    void aStreamWithoutAVideoDocumentHasNoUploadAge() throws Exception {
        List<ContentItemDto> items = index.search("orphan", "VIDEO", false, 50, null).items();
        assertEquals(List.of("orphan-lecture"), items.stream().map(ContentItemDto::getId).toList());
        assertNull(items.get(0).getUploadedDaysAgo(), "unknown upload date must stay null, never 'Today'");
    }

    // ---- startup (spec §5.1, §7) ---------------------------------------------------------------

    @Test
    void theCatalogIsSearchableWhileStreamsAreStillLoading() throws Exception {
        SearchIndex slow = newIndex(channels(), playlists(), videos(), List.of(), 5_000);
        CountDownLatch release = new CountDownLatch(1);
        when(streamRepo.findVisiblePage(isNull(), eq(SearchIndex.STREAM_PAGE))).thenAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return List.of(stream("late", "Late Stream", "channel:UChalal"));
        });
        slow.onReady();

        assertEquals("UCzakir", ids(slow.search("zakit", null, false, 10, null)).get(0));
        assertTrue(ids(slow.search("late", "VIDEO", false, 10, null)).isEmpty());

        release.countDown();
        slow.awaitWrites();
        assertEquals(List.of("late"), ids(slow.search("late", "VIDEO", false, 10, null)));
    }

    @Test
    void streamsLoadPageByPageInDocumentIdOrder() throws Exception {
        SearchIndex paged = newIndex(channels(), playlists(), videos(), List.of(), 5_000);
        List<SearchableStream> first = new ArrayList<>();
        for (int i = 0; i < SearchIndex.STREAM_PAGE; i++) {
            first.add(stream(String.format("s%04d", i), "Synthetic stream " + i, "channel:UChalal"));
        }
        when(streamRepo.findVisiblePage(isNull(), eq(SearchIndex.STREAM_PAGE))).thenReturn(first);
        when(streamRepo.findVisiblePage(eq("s0499"), eq(SearchIndex.STREAM_PAGE)))
                .thenReturn(List.of(stream("s0500", "Synthetic stream 500", "channel:UChalal")));
        started(paged);

        assertEquals(501, paged.search("synthetic", "VIDEO", false, 1000, null).items().size());
    }

    @Test
    void aSearchBeforeTheFirstCatalogBuildIsNotReady() throws Exception {
        SearchIndex cold = newIndex(channels(), playlists(), videos(), streams(), 50);
        assertThrows(SearchIndex.NotReadyException.class, () -> cold.search("zakir", null, false, 10, null));
    }

    @Test
    void everyVideoDocumentIsReadUncapped() throws Exception {
        // Review Focus 3: 1,020 video documents > findAll()'s 1,000 default cap (spec §2.3).
        verify(videoRepo).findAllUnordered();
        verify(videoRepo, never()).findAll();
    }

    @Test
    void eachContextGetsItsOwnIndexDirectoryAndClosingDeletesOnlyThatOne() throws Exception {
        // Ruling F15: two Spring contexts in one JVM must not delete each other's index.
        Set<Path> before = indexDirectories();
        SearchIndex first = started(new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo));
        SearchIndex second = started(new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo));
        Set<Path> created = indexDirectories();
        created.removeAll(before);
        assertEquals(2, created.size(), "one directory per context");

        first.close();
        Set<Path> left = indexDirectories();
        left.retainAll(created);
        assertEquals(1, left.size(), "closing one context deletes its own directory only");
        assertEquals("UCzakir", ids(second.search("zakit", null, false, 10, null)).get(0));

        second.close();
        left = indexDirectories();
        left.retainAll(created);
        assertEquals(Set.of(), left);
    }

    @Test
    void closingAnIndexWhoseCatalogAndEventsAdmitNothingStillDeletesTheDirectory() throws Exception {
        // Empty block + delete term (Lucene asserts under -ea when such an index is rolled back).
        newIndex(List.of(), List.of(), List.of(), List.of(), 5_000).close(); // only for its empty-catalog mocks
        Set<Path> before = indexDirectories();
        SearchIndex fresh = started(new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo));
        for (int i = 0; i < 5; i++) {
            Channel pending = with(channel("UCpending" + i, "Pending " + i), c -> c.setStatus("PENDING"));
            fresh.onCatalogChanged(new CatalogChanged("CHANNEL", pending.getId(), pending));
        }
        fresh.awaitWrites();

        assertDoesNotThrow(fresh::close);

        Set<Path> left = indexDirectories();
        left.removeAll(before);
        assertEquals(Set.of(), left, "no index directory is left behind");
    }

    private static Set<Path> indexDirectories() throws Exception {
        try (Stream<Path> paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("fitrahtube-search-"))
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }

    /** Ten DISTINCT 8-letter tokens (identical ones collapse on rewrite and hide the limit). */
    private static final List<String> LONG_TOKENS = List.of("tafsirzz", "hadithzz", "aqeedazz", "sunnahzz",
            "seerahzz", "tawhidzz", "zakaatzz", "wudhoozz", "ramadnzz", "hijrahzz");

    @Test
    void aTenWordFuzzyQueryStaysUnderTheClauseLimit() throws Exception {
        // Review Focus 2: 10 distinct tokens x 3 fields x 50 fuzzy expansions is over Lucene's default 1,024 clauses.
        List<Video> many = new ArrayList<>();
        List<String> oneEditFromEach = new ArrayList<>();
        for (String token : LONG_TOKENS) {
            String root = token.substring(0, token.length() - 2);
            oneEditFromEach.add(root + "az");
            for (char a = 'a'; a <= 'h'; a++) {
                for (char b = 'a'; b <= 'z'; b++) {
                    String word = root + a + b; // 208 neighbours within 2 edits, on title, keywords and channel
                    many.add(with(video("yt-" + word, word, word), v -> v.setKeywords(List.of(word))));
                }
            }
        }
        // The one document near all ten tokens: tier 2 finds only it, so tier 3 runs as well.
        many.add(with(video("yt-all", "Every root", null), v -> v.setKeywords(oneEditFromEach)));
        SearchIndex big = started(newIndex(List.of(), List.of(), many, List.of(), 5_000));
        String query = String.join(" ", LONG_TOKENS);
        assertEquals(SearchText.MAX_TOKENS, LONG_TOKENS.size());

        int raised = IndexSearcher.getMaxClauseCount();
        IndexSearcher.setMaxClauseCount(1024); // precondition: this fixture really exceeds Lucene's default
        try {
            assertThrows(IndexSearcher.TooManyClauses.class, () -> big.search(query, null, false, 20, null));
        } finally {
            IndexSearcher.setMaxClauseCount(raised);
        }

        SearchIndex.Hits hits = big.search(query, null, false, 20, null);
        assertEquals("approximate", hits.match());
        assertEquals("yt-all", ids(hits).get(0));
        assertEquals(20, hits.items().size(), "tier 3 (related) filled the rest of the list");
    }

    // ---- freshness (spec §5.2, §5.3) -----------------------------------------------------------

    @Test
    void anApprovedChannelEventMakesItFindable() throws Exception {
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCsaadi", channel("UCsaadi", "Tafsir As-Saadi")));
        index.awaitWrites();
        assertEquals("UCsaadi", ids("saadi").get(0));
    }

    @Test
    void aKeywordsUpdateChangesWhatMatches() throws Exception {
        assertTrue(ids("khushoo").isEmpty());
        Video updated = with(video("vPrayerKey", "Prayer is the Key", null), v -> v.setKeywords(List.of("khushoo")));
        index.onCatalogChanged(new CatalogChanged("VIDEO", "doc-vPrayerKey", updated));
        index.awaitWrites();
        assertEquals("vPrayerKey", ids("khushoo").get(0));
    }

    @Test
    void aDeleteRemovesTheDocumentAndHidesItsStreams() throws Exception {
        assertEquals(List.of("alafasy-live"), ids(index.search("recitation", "VIDEO", false, 10, null)));
        when(channelRepo.findById("doc-UCalafasy")).thenReturn(Optional.empty());
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCalafasy", null));
        index.awaitWrites();
        assertFalse(ids("alafa").contains("UCalafasy"));
        assertTrue(ids(index.search("recitation", "VIDEO", false, 10, null)).isEmpty());
    }

    @Test
    void rejectingASourceHidesItsStreamsBeforeRemoveSourceRuns() throws Exception {
        Channel rejected = with(channel("UChalal", "Halal Kitchen"), c -> c.setStatus("REJECTED"));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UChalal", rejected));
        index.awaitWrites();
        assertTrue(ids(index.search("halal", "VIDEO", false, 10, null)).isEmpty());
        assertFalse(ids("kitchen").contains("UChalal"), "the rejected channel's own document is gone too");
    }

    @Test
    void excludingAVideoFromItsChannelHidesItsStream() throws Exception {
        // Closes the pre-existing gap (spec §4.2): exclusions never touched searchable_streams.
        Channel kitchen = with(channel("UChalal", "Halal Kitchen"),
                c -> c.getExcludedItems().setVideos(List.of("halal-excluded", "halal-ok")));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UChalal", kitchen));
        index.awaitWrites();
        assertTrue(ids(index.search("halal", "VIDEO", false, 10, null)).isEmpty());
    }

    @Test
    void savingAnAdmittedChannelAgainKeepsItsStreamsVisible() throws Exception {
        // The source key is removed before the shared admit() re-adds it, never after (B3 hand-off).
        Channel renamed = channel("UChalal", "Halal Kitchen Renamed");
        renamed.getExcludedItems().setVideos(List.of("halal-excluded"));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UChalal", renamed));
        index.awaitWrites();
        assertEquals(List.of("halal-ok"), ids(index.search("halal", "VIDEO", false, 10, null)));
        assertEquals("UChalal", ids("renamed").get(0));
    }

    @Test
    void aPlaylistEventIsIndexedByItsOwnType() throws Exception {
        index.onCatalogChanged(new CatalogChanged("PLAYLIST", "doc-PLseerah", playlist("PLseerah", "Seerah of the Prophet")));
        index.awaitWrites();
        assertEquals(List.of("PLseerah"), ids(index.search("seerah", "PLAYLIST", true, 10, null)));
    }

    @Test
    void deletingAVideoDocumentRemovesIt() throws Exception {
        assertTrue(ids("prayer").contains("vPrayerKey"));
        when(videoRepo.findById("doc-vPrayerKey")).thenReturn(Optional.empty());
        index.onCatalogChanged(new CatalogChanged("VIDEO", "doc-vPrayerKey", null));
        index.awaitWrites();
        assertFalse(ids("prayer").contains("vPrayerKey"));
    }

    @Test
    void aFailedRereadIsLoggedWithItsCauseAndLeavesTheIndexAlone() throws Exception {
        when(channelRepo.findById("doc-UCzakir")).thenThrow(new ExecutionException("firestore down", new RuntimeException("cause")));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCzakir", null));
        index.awaitWrites();
        assertTrue(logged(Level.WARN).stream().anyMatch(e -> e.getThrowableProxy() != null
                && ExecutionException.class.getName().equals(e.getThrowableProxy().getClassName())));
        assertEquals("UCzakir", ids("zakit").stream().findFirst().orElse("<empty>"));
    }

    @Test
    void streamHooksAddMergeAndRemove() throws Exception {
        SearchableStream fresh = stream("fresh-1", "Fresh Seerah Lesson");
        index.upsertStreams(List.of(fresh), "channel:UChalal");
        index.awaitWrites();
        assertEquals(List.of("fresh-1"), ids(index.search("fresh", "VIDEO", false, 10, null)));

        index.upsertStreams(List.of(fresh), "channel:UCalafasy");  // a second source merges in
        index.removeSource("channel:UChalal");                    // one source left: still shown
        index.awaitWrites();
        assertEquals(List.of("fresh-1"), ids(index.search("fresh", "VIDEO", false, 10, null)));

        index.removeSource("channel:UCalafasy");                  // none left: deleted
        index.awaitWrites();
        assertTrue(ids(index.search("fresh", "VIDEO", false, 10, null)).isEmpty());

        index.upsertStreams(List.of(fresh), "channel:UCquranTv");
        index.removeStream("fresh-1");                            // archived video
        index.awaitWrites();
        assertTrue(ids(index.search("fresh", "VIDEO", false, 10, null)).isEmpty());
    }

    // ---- build and swap hardening (B3 review) --------------------------------------------------

    @Test
    void aCatalogSwapThatFailsKeepsTheLastGoodCatalog() throws Exception {
        // A youtubeId past Lucene's 32766-byte term limit makes the add throw AFTER the old catalog was deleted.
        when(videoRepo.findAllUnordered()).thenReturn(List.of(video("x".repeat(40_000), "Poisoned", null)));
        assertThrows(IllegalArgumentException.class, () -> index.replaceCatalog());

        // Any later refresh would expose whatever the failed swap left pending.
        index.upsertStreams(List.of(stream("after", "After The Swap", "channel:UChalal")), "channel:UChalal");
        index.awaitWrites();
        assertEquals("UCzakir", ids("zakit").stream().findFirst().orElse("<empty>"), "the last good catalog is still served");
    }

    @Test
    void aCatalogThatBecomesEmptyIsSwappedOut() throws Exception {
        when(channelRepo.findByStatus("APPROVED")).thenReturn(List.of());
        when(playlistRepo.findByStatus("APPROVED")).thenReturn(List.of());
        when(videoRepo.findAllUnordered()).thenReturn(List.of());
        index.replaceCatalog();
        assertTrue(ids("zakir").isEmpty());
        assertTrue(ids("quran").isEmpty());
    }

    @Test
    void shuttingDownDuringTheFirstBuildLogsNoErrorAndSchedulesNoRetry() throws Exception {
        SearchIndex building = newIndex(channels(), playlists(), videos(), streams(), 5_000);
        CountDownLatch inFirestore = new CountDownLatch(1);
        when(channelRepo.findByStatus("APPROVED")).thenAnswer(inv -> {
            inFirestore.countDown();
            new CountDownLatch(1).await(); // until close() interrupts the writer thread
            return List.of();
        });
        building.onReady();
        assertTrue(inFirestore.await(10, TimeUnit.SECONDS));

        building.close();

        assertEquals(List.of(), logged(Level.ERROR).stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void anErrorInTheFirstBuildIsLoggedWithItsStackTrace() throws Exception {
        SearchIndex failing = newIndex(channels(), playlists(), videos(), streams(), 5_000);
        when(channelRepo.findByStatus("APPROVED")).thenThrow(new NoClassDefFoundError("boom"));
        try {
            failing.onReady();
            failing.awaitWrites();
            assertTrue(logged(Level.ERROR).stream().anyMatch(e -> e.getThrowableProxy() != null
                    && NoClassDefFoundError.class.getName().equals(e.getThrowableProxy().getClassName())));
        } finally {
            failing.close();
        }
    }

    // ---- writer hardening (B5) -----------------------------------------------------------------

    @Test
    void anErrorInAWriterTaskIsLoggedWithItsStackTrace() throws Exception {
        // An Error (not an Exception) used to vanish inside the executor's future: no log, no trace.
        when(channelRepo.findById("doc-UCzakir")).thenThrow(new NoClassDefFoundError("boom"));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCzakir", null));
        index.awaitWrites();
        assertTrue(logged(Level.WARN).stream().anyMatch(e -> e.getThrowableProxy() != null
                && NoClassDefFoundError.class.getName().equals(e.getThrowableProxy().getClassName())));
    }

    @Test
    void shuttingDownDuringAReconcileLogsNoWarning() throws Exception {
        SearchIndex reconciling = started(newIndex(channels(), playlists(), videos(), streams(), 5_000));
        CountDownLatch inFirestore = new CountDownLatch(1);
        when(channelRepo.findByStatus("APPROVED")).thenAnswer(inv -> {
            inFirestore.countDown();
            new CountDownLatch(1).await(); // until close() interrupts the writer thread
            return List.of();
        });
        reconciling.reconcile();
        assertTrue(inFirestore.await(10, TimeUnit.SECONDS));

        reconciling.close();

        assertEquals(List.of(), logged(Level.WARN).stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    @Test
    void aCloseThatFailsStillDeletesTheDirectory() throws Exception {
        // rollback() lists the directory; a non-IOException there used to skip the delete and leak the temp dir.
        Path path = Files.createTempDirectory("fitrahtube-search-");
        AtomicBoolean failing = new AtomicBoolean();
        SearchIndex fragile = new SearchIndex(new NIOFSDirectory(path) {
            @Override
            public String[] listAll() throws IOException {
                if (failing.get()) throw new IllegalStateException("directory gone");
                return super.listAll();
            }
        }, channelRepo, playlistRepo, videoRepo, streamRepo, 5_000);
        started(fragile);
        failing.set(true);

        assertThrows(IllegalStateException.class, fragile::close);

        assertFalse(Files.exists(path), "the index directory is deleted even though close failed");
    }

    @Test
    void mutatingAnEntityAfterPublishingItDoesNotChangeWhatIsIndexed() throws Exception {
        // Block the writer thread so the mutation lands before the writer task runs.
        CountDownLatch writerBusy = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(channelRepo.findById("doc-blocker")).thenAnswer(inv -> {
            writerBusy.countDown();
            release.await();
            return Optional.empty();
        });
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-blocker", null));
        assertTrue(writerBusy.await(10, TimeUnit.SECONDS));

        Channel live = channel("UCsnapshot", "Original Snapshot Name");
        index.onCatalogChanged(new CatalogChanged("CHANNEL", live.getId(), live));
        live.setName("Mutated Afterwards");
        live.setStatus("REJECTED");
        release.countDown();
        index.awaitWrites();

        assertEquals("UCsnapshot", ids("original").stream().findFirst().orElse("<empty>"));
        assertTrue(ids("mutated").isEmpty());
    }

    @Test
    void awaitWritesDoesNotWaitForARetryScheduledForLater() throws Exception {
        SearchIndex unreachable = newIndex(channels(), playlists(), videos(), streams(), 5_000);
        when(channelRepo.findByStatus("APPROVED")).thenThrow(new ExecutionException("firestore down", new RuntimeException()));
        try {
            unreachable.onReady(); // fails, then schedules its retry 30 s out
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> unreachable.awaitWrites());
        } finally {
            unreachable.close();
        }
    }
}
