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
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.SearchableStreamRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.PublicContentService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherLifetimeManager;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.NIOFSDirectory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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
        List<QueryDocumentSnapshot> channelDocs = docs(cs), playlistDocs = docs(ps), videoDocs = docs(vs), streamDocs = docs(ss);
        when(channelRepo.findByStatusUnordered("APPROVED")).thenReturn(channelDocs);
        when(playlistRepo.findByStatusUnordered("APPROVED")).thenReturn(playlistDocs);
        when(videoRepo.findAllUnordered()).thenReturn(videoDocs);
        when(streamRepo.findVisiblePage(isNull(), eq(SearchIndex.STREAM_PAGE))).thenReturn(streamDocs);
        SearchIndex created = new SearchIndex(new ByteBuffersDirectory(), channelRepo, playlistRepo, videoRepo, streamRepo, readyWaitMs);
        opened.add(created);
        return created;
    }

    /** Firestore documents that map to `entities`, each with its entity's document id. */
    private static List<QueryDocumentSnapshot> docs(List<?> entities) {
        return entities.stream().map(entity -> {
            String id = entity instanceof Channel c ? c.getId() : entity instanceof Playlist p ? p.getId()
                    : entity instanceof Video v ? v.getId() : ((SearchableStream) entity).getStreamId();
            return mock(QueryDocumentSnapshot.class, inv -> switch (inv.getMethod().getName()) {
                case "getId" -> id;
                case "toObject" -> entity;
                default -> RETURNS_DEFAULTS.answer(inv);
            });
        }).toList();
    }

    /** Every in-memory index this test built; closed after it (closing twice is harmless for these). */
    private final List<SearchIndex> opened = new ArrayList<>();

    /** Firestore now holds `c`: its event makes the writer re-read it by id (F3). */
    private void saved(Channel c) throws Exception {
        when(channelRepo.findById(c.getId())).thenReturn(Optional.of(c));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", c.getId(), false));
    }

    private void saved(Playlist p) throws Exception {
        when(playlistRepo.findById(p.getId())).thenReturn(Optional.of(p));
        index.onCatalogChanged(new CatalogChanged("PLAYLIST", p.getId(), false));
    }

    private void saved(Video v) throws Exception {
        when(videoRepo.findById(v.getId())).thenReturn(Optional.of(v));
        index.onCatalogChanged(new CatalogChanged("VIDEO", v.getId(), false, v.getYoutubeId())); // as VideoRepository.save publishes
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
        return ids(index.search(query, null, 50));
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
    void tearDown() {
        indexLog.detachAppender(logs);
        opened.forEach(SearchIndex::close);
    }

    private static Set<Thread> writersBefore;

    @BeforeAll
    static void noteWriterThreads() {
        writersBefore = writerThreads();
    }

    @AfterAll
    static void noWriterThreadOutlivesTheClass() throws InterruptedException {
        Set<Thread> leaked = writerThreads();
        leaked.removeAll(writersBefore);
        for (Thread t : leaked) t.join(50); // a closed index's thread is already exiting
        leaked.removeIf(t -> !t.isAlive());
        assertEquals(0, leaked.size(), "every SearchIndex built in this class is closed");
    }

    private static Set<Thread> writerThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> "search-index-writer".equals(t.getName()))
                .collect(Collectors.toCollection(HashSet::new));
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
        SearchIndex.Hits hits = index.search(query, null, 50);
        assertEquals(firstId, ids(hits).get(0));
        assertEquals(match, hits.match());
    }

    @Test
    void anExactWordPutsBothZakirChannelsFirstAndNeverShowsUnadmittedOnes() throws Exception {
        SearchIndex.Hits hits = index.search("zakir", null, 50);
        List<String> ids = ids(hits);
        assertEquals("exact", hits.match());
        assertEquals(Set.of("UCzakir", "UCzakirAr"), Set.copyOf(ids.subList(0, 2)));
        assertFalse(ids.contains("UCzakirPending") || ids.contains("UCzakirPersonal") || ids.contains("UCzakirArchived"));
    }

    @Test
    void aPrefixWhileTypingFindsTheChannelAndItsVideos() throws Exception {
        SearchIndex.Hits hits = index.search("alafa", null, 50);
        assertEquals("exact", hits.match());
        assertTrue(ids(hits).containsAll(List.of("UCalafasy", "vKahf")));
    }

    @Test
    void apostrophesFoldAndShortTitlesOutrankKeywordOnlyMatches() throws Exception {
        SearchIndex.Hits hits = index.search("quran", null, 50);
        List<String> ids = ids(hits);
        assertEquals("exact", hits.match());
        assertTrue(ids.contains("UCksaQuran"), "KSA Qur’an TV must match quran");
        assertTrue(ids.indexOf("UCquranTv") < ids.indexOf("vAdhkar"), "Quran TV must outrank a keyword-only match");
    }

    @Test
    void aTransliterationReachesItsConceptAsRelated() throws Exception {
        SearchIndex.Hits hits = index.search("koran", null, 50);
        assertEquals("related", hits.match());
        assertTrue(ids(hits).contains("UCquranTv"));
    }

    @Test
    void aDutchWordFindsItsConceptAcrossScripts() throws Exception {
        SearchIndex.Hits hits = index.search("gebed", null, 50);
        assertEquals("related", hits.match());
        assertTrue(Set.of("vSalahClip", "vPrayerKey").contains(ids(hits).get(0)));
    }

    @Test
    void aTypoOfATranslatedTagFindsTheItemWhoseOnlyMatchIsThatTag() throws Exception {
        // P1 ruling: TagEnrichmentService adds translations ("prayer" for الصلاة) that are in no title; tags count.
        SearchIndex corpus = started(newIndex(List.of(), List.of(),
                List.of(with(video("vSalahTagged", "كيفية الصلاة", null), v -> v.setKeywords(List.of("صلاة", "prayer", "gebed"))),
                        video("vStoryTime", "Story Time", null)),
                List.of(), 5_000));
        SearchIndex.Hits hits = corpus.search("prayr", null, 50);
        assertEquals(List.of("vSalahTagged"), ids(hits));
        assertEquals("approximate", hits.match());
    }

    @Test
    void theRelatedTierNeedsAWordNotJustAStopword() throws Exception {
        // B5 ruling: "of" alone filled the related tier with anything containing it; stopwords now only add score.
        SearchIndex corpus = started(newIndex(List.of(), List.of(),
                List.of(video("vStoryTime", "Story Time", null), video("vBookOf", "Book of Knowledge", null)),
                List.of(), 5_000));
        SearchIndex.Hits hits = corpus.search("story of xqzv", null, 50);
        assertEquals(List.of("vStoryTime"), ids(hits));
        assertEquals("related", hits.match());
        // A query made only of stopwords still matches on them.
        assertEquals(List.of("vBookOf"), ids(corpus.search("of", null, 50)));
    }

    @Test
    void anUthmaniSpellingAndThePlainSpellingFindEachOtherInTheExactTier() throws Exception {
        // Cubic P3 (1.0.4): Quranic marks U+06D6-06ED stayed in the words; fuzzy allows only one edit at this length.
        SearchIndex corpus = started(newIndex(List.of(), List.of(),
                List.of(video("vUthmani", "ٱلۡحَمۡدُ لِلَّهِ", null), video("vPlain", "سورة الكهف", null)),
                List.of(), 5_000));
        SearchIndex.Hits plainQuery = corpus.search("الحمد", null, 50);
        assertEquals(List.of("vUthmani"), ids(plainQuery));
        assertEquals("exact", plainQuery.match());
        SearchIndex.Hits uthmaniQuery = corpus.search("ٱلۡكَهۡفِ", null, 50);
        assertEquals(List.of("vPlain"), ids(uthmaniQuery));
        assertEquals("exact", uthmaniQuery.match());
    }

    @Test
    void openTanweenAndHonorificSignsDoNotHideATitleFromItsPlainSpelling() throws Exception {
        // Round 2 ruling (1.0.4): marks in U+0610-061A and U+08D3-08FF stayed in the words like U+06D6-06ED did.
        // The marked words are never the last query token: its prefix clause would match them anyway.
        SearchIndex corpus = started(newIndex(List.of(), List.of(), List.of(
                        video("vOpenTanween", "إِنَّ ٱللَّهَ كَانَ غَفُور\u08F0ا رَّحِيم\u08F0ا", null),
                        video("vHonorific", "سيرة محمد\u0610", null)),
                List.of(), 5_000));
        SearchIndex.Hits tanween = corpus.search("غفورا رحيما", null, 50);
        assertEquals(List.of("vOpenTanween"), ids(tanween));
        assertEquals("exact", tanween.match());
        SearchIndex.Hits honorific = corpus.search("محمد سيرة", null, 50);
        assertEquals(List.of("vHonorific"), ids(honorific));
        assertEquals("exact", honorific.match());
    }

    @Test
    void theApproximateTierFillsAShortExactListBehindItButTheRelatedTierStaysGated() throws Exception {
        // Audit round 2 (2026-10-17): three literal "Ikhlaas" titles stopped the ladder, so "Surah Al-Ikhlas" never showed.
        String naik = "channel:UCzakir";
        SearchIndex corpus = started(newIndex(List.of(channel("UCzakir", "Dr Zakir Naik")), List.of(), List.of(
                        video("BcCLLPZNPHk", "Surah Al-Ikhlas Repeats with ZAKY - Let's Memorise Quran!", "One4kids"),
                        video("vSincerity", "Sincerity in Worship", null)), // the ikhlaas concept: related tier only
                List.of(stream("uq7ccPxFN_8", "Test the God You Worship against Surah Ikhlaas - Dr Zakir Naik", naik),
                        stream("PK3HwwqGMko", "Bhagwan Rajneesh Fails the First Test of Surah Ikhlaas - Dr Zakir Naik", naik),
                        stream("Iu0eXp1v7J8", "Concept of God in Sikhism Similar to that Given in Surah Ikhlaas - Dr Zakir Naik", naik)),
                5_000));
        SearchIndex.Hits hits = corpus.search("surah al ikhlaas", null, 50);
        List<String> ids = ids(hits);
        assertEquals("exact", hits.match());
        assertEquals(4, ids.size(), ids.toString());
        assertEquals(Set.of("uq7ccPxFN_8", "PK3HwwqGMko", "Iu0eXp1v7J8"), Set.copyOf(ids.subList(0, 3)));
        assertEquals("BcCLLPZNPHk", ids.get(3));
    }

    @Test
    void nonsenseFindsNothing() throws Exception {
        SearchIndex.Hits hits = index.search("xqzvbnm", null, 50);
        assertTrue(hits.items().isEmpty());
        assertEquals("none", hits.match());
    }

    // ---- slow-search line (inner-search spec §7.3 step 2a) ----------------------------------------

    @Test
    void aSlowSearchLogsOneLineWithItsTiersAndNeverTheQueryText() throws Exception {
        index.slowSearchMs = -1; // every search counts as slow
        index.search("halal kitchen", null, 50);
        List<String> lines = logged(Level.INFO).stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("slow search:")).toList();
        assertEquals(1, lines.size(), lines.toString());
        String line = lines.get(0);
        assertTrue(line.matches("slow search: ms=\\d+ tokens=2 want=50 type=null tiers=1:\\d+ms:\\d+(,[23]:\\d+ms:\\d+)*"
                + " loaded=\\d+ hidden=\\d+"), line);
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("halal") || lower.contains("kitchen"), "no query text: " + line);
    }

    @Test
    void noLogOutputCarriesTheQueryText() throws Exception {
        index.slowSearchMs = -1;
        index.search("zxmarkerqq vvmarkerzz", null, 50);
        String all = new ArrayList<>(logs.list).stream().map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n")).toLowerCase(java.util.Locale.ROOT);
        assertTrue(all.contains("slow search:"), "the line was logged: " + all);
        assertFalse(all.contains("zxmarkerqq") || all.contains("vvmarkerzz"), "query text leaked: " + all);
    }

    @Test
    void aFastSearchLogsNoSlowLine() throws Exception {
        index.search("halal kitchen", null, 50); // the fixture answers in a few ms, under the 150 ms default
        assertTrue(logged(Level.INFO).stream().noneMatch(e -> e.getFormattedMessage().startsWith("slow search:")));
    }

    // ---- P1: live-shaped ranking (probe 2026-10-14 06:26Z) ------------------------------------

    /**
     * A second corpus with the live field shapes, read 2026-10-14 from the public detail endpoints: the two
     * Zakir channels (the main one has no keywords), 26 streams of the main channel titled as the ones that
     * outranked it live (#2-#27 for `zakir`), and the live top 6 for `arabic learn`.
     */
    private SearchIndex liveShaped() throws Exception {
        List<Channel> cs = List.of(
                with(channel("UCzakir", "Dr Zakir Naik"), c -> c.setDescription(
                        "Alhamdulillah Dr Zakir Naik was presented:\n1. ‘King Faisal International Prize’ for ‘Service to Islam’ 2015 by King Salman Al Saud, King of Saudi Arabia.\n2. Dubai International Holy Quran Award’s ‘Islamic Personality of the year - 2013’ by Shaikh Mohammed bin Rashid Al Maktoum, Ruler of Dubai.\n3. ‘Tokoh Ma’al Hijrah Distinguished International Personality Award’ for the Year 2013 by Agong, Tuanku Abdul Halim Mu’adzam Shah, the King of Malaysia.\n4. Ranked No. 82 in the ‘100 Most Influential People in India’ in the 2009. \n5. Ranked amongst the top 70 in the Book ‘The 500 Most Influential Muslims in the World’ from 2011 to 2023 & ranked 79 in  ‘The Top 100 Cumulative Influence over 10 years’.\n6. A plaque by Shaikh Ahmed Deedat in May 2000, “Son what you have done in 4 years had taken me 40 years to accomplish, Alhamdulillah”.\n7. The Honorary ‘Doctor of Humane Letters’ (Honoris Causa), by the University of The Gambia (UTG).\n8. Has more than 25 million followers on his Facebook.\n\n\n")),
                with(channel("UCzakirAr", "Dr. Zakir Naik in arabic"), c -> {
                    c.setDescription("Alhamdulillah Dr Zakir Naik was presented:\n1. ‘King Faisal International Prize’ for ‘Service to Islam’ 2015 by King Salman Al Saud of Saudi Arabia.\n2. Dubai International Holy Quran Award’s ‘Islamic Personality of the year-2013’ by Shaikh Mohammed bin Rashid Al Maktoum, Ruler of Dubai.\n3. Ranked No. 82 in ‘100 Most Influential People in India’ in 2009. \n4. Ranked amongst top 70 in ‘The 500 Most Influential Muslims in the World’ from 2011 to 2020 & ranked 79 in ‘The Top 100 Cumulative Influence over 10 years’.\n5. A plaque by Shaikh Ahmed Deedat in 2000, “Son what you have done in 4 years had taken me 40 years to accomplish, Alhamdulillah”.\n6. The Honorary ‘Doctor of Humane Letters’ (Honoris Causa), by the University of The Gambia.\n7. Has more than 22 million followers on his Facebook, being highest amongst any Religious English Speaker in the world.\n8. He is founder of Peace TV Network which has four 24 hour satellite channels in English, Urdu, Bangla & Chinese with 200 million viewers.");
                    c.setKeywords(List.of(
                        "ذاكر نايك مترجم", "دكتور ذاكر نايك مترجم", "محاضرات دكتور ذاكر بالعربية", "Zakir naik", "Zakir",
                        "naik", "زاكر نايك", "فارس الدعوة", "احمد ديدات", "Ahmed deedat", "deedat", "Zakir naik in arabic",
                        "ترجمة محاضرات دكتور ذاكر نايك", "جديد ترجمة", "جديد", "الدعوة الى الاسلام",
                        "الاسلام والالحاد والمسيحية", "الاسلام والهندوسية", "الهندوسية", "المسيحية", "ذاكر مترجم", "Dr",
                        "arabic", "Alhamdulillah", "presented", "‘King", "Faisal", "International", "Prize’", "‘Service",
                        "Islam’", "King", "Salman", "Al", "Saud", "Saudi", "Arabia", "Dubai", "Holy", "Quran", "Award’s",
                        "‘Islamic", "Personality", "year-2013’", "Shaikh", "Mohammed", "bin", "Rashid", "Maktoum", "Ruler"));
                }),
                with(channel("UClearnArabic", "Learn Arabic-اتعلم عربي"), c -> {
                    c.setDescription("أهلا بكم في قناة اتعلم عربي\nنشارك معكم في القناة أسهل الطرق لتعلم قراءة القرآن الكريم وقراءة اللغة العربية بكل جوانبها من قواعد وكلمات وقراءة وإملاء\nفيديوهات مبسطة لأي مبتدئ في تعلم اللغة العربية والنحو والأساليب والقرآن الكريم\nنرحب دائماً باقتراحاتكم لتطوير قناتنا للأفضل\n#اتعلم_عربي \n#قواعد_الاملاء\n#القراءة_الكتابة\n#learn_arabic \n#arabic_alphabet \n#quran\n");
                    c.setKeywords(List.of(
                        "القراءة والكتابة", "تعليم القراءة والكتابة", "المد_بالألف", "نور_البيان",
                        "معالجة ضعف القراءة والكتابة", "Arabic Language", "تعليم الحروف العربية", "تعليم القراءة",
                        "تعلم القراءة", "#تعلم_الاملاء", "#قواعد_الاملاء", "دروس الاملاء", "Reading arabic words",
                        "Reading arabic alphabet", "تعلم القراءة والكتابة", "حرف الباء", "حرف العين", "Baa taa", "حرف الدال",
                        "Learn", "Arabic-اتعلم", "عربي", "أهلا", "بكم", "قناة", "اتعلم", "نشارك", "معكم", "القناة", "أسهل",
                        "الطرق", "لتعلم", "قراءة", "القرآن", "الكريم", "وقراءة", "اللغة", "العربية", "بكل", "جوانبها",
                        "قواعد", "وكلمات", "وإملاء", "فيديوهات", "مبسطة", "لأي", "مبتدئ", "تعلم", "والنحو", "والأساليب"));
                }));
        List<Playlist> ps = List.of(
                with(playlist("PLarabicKids", "Learn Arabic for Kids -  تعليم اللغة العربية للأطفال"), p -> {
                    p.setDescription("Let's Learn Arabic language with Zakaria, the little muslim. Learn Alphabet, numbers, shapes, colors, fruits, vegetables, vehicles...\nتعلم اللغة العربية مع زكريا المسلم الصغير, تعلم الحروف, الأرقام, الأشكال, الألوان...");
                    p.setKeywords(List.of(
                        "Learn", "Arabic", "Kids", "تعليم", "اللغة", "العربية", "للأطفال", "Learn Arabic for Kids",
                        "تعليم اللغة العربية للأطفال", "YouTube", "playlist", "YouTube playlist: Learn Arabic for Kids",
                        "al-Arabiyyah", "Arabisch", "children", "أطفال", "kinderen"));
                }),
                with(playlist("PLarabicExamples", "Learn Arabic By Examples"), p -> p.setKeywords(List.of(
                        "Learn", "Arabic", "Examples", "al-Arabiyyah", "العربية", "Arabisch"))),
                with(playlist("PLarabicRachid", "Learn Arabic with Rachid"), p -> p.setKeywords(List.of(
                        "Learn", "Arabic", "Rachid", "YouTube", "playlist", "al-Arabiyyah", "العربية", "Arabisch"))));
        List<Video> vs = List.of(
                with(video("vArabicAlphabetAnimals", "Arabic Alphabet for Kids with Animals – Learn Arabic ABC with Zakaria",
                        "Learn with Zakaria - تعلم مع زكريا"), v -> {
                    v.setDescription("Let&apos;s Learn the alphabet in arabic with this cartoon Video from “Learn with Zakaria&quot; in a fun and unique way with our cute Animals. <br><br>This video will make your kids laugh and smile while teaching theme all the arabic letters: Alif, Baa, Taa, Tha, Jeem, Haa, Kha, Dal, dhal, raa, zay, seen, sheen, Sad, Dhad, Dhaa, Ayn, Ghayn, Faa, Qaf, Kaf, Lam, Meem, Noon, Haa, Waw, Yaa. (أ ب ت ث ج ح خ د ذ ر ز س ش ص ض ط ظ ع غ ف ق ك ل م ن ه و ي.)<br><br>Hope you enjoyed this video on &quot;Learn With Zakaria&quot; Channel.<br><br>Welcome to our children’s channel: Learn with Zakaria, a good place where kids and parents can find great learning content every week. <br><br>Our channel is packed with great educational videos that will make your children laugh and smile while teaching them alphabet, colors, shapes, names of animals, fruits, vegetables and much more… in Arabic, English and even French. <br><br>Your kids will also enjoy learning and memorizing “Quran” in a fun and unique way, with our cute characters. <br><br>We hope you will enjoy our content on Zakaria&apos;s Channel.<br><br>Don’t forget to click the subscribe button :)<br><br>For more learning videos click here:<br><br><a href=\"https://www.youtube.com/learnWithZakaria\">learnwithzakaria</a><br><br>Visit our website:<br><br><a href=\"http://learnwithzakaria.com/\">http://learnwithzakaria.com/</a><br><br>Follow us on Facebook:<br><br><a href=\"https://www.facebook.com/LearnWithZakaria\">Facebook: LearnWithZakaria</a>");
                    v.setKeywords(List.of(
                        "الحروف الابجدية العربية للاطفال", "الحروف الهجائية", "الحروف الابجدية", "تعلم اللغة العربية",
                        "أغنية الحروف", "أنشودة الحروف", "الحروف العربية للاطفال طيور الجنة", "الحروف و الحيوانات",
                        "Arabic Abc", "arabic letters", "arabic alphabet for children", "arabic alphabet pronunciation",
                        "arabic alphabet for beginner", "arabic learning for kids", "arabic letters for children",
                        "Arabic for kids", "Arabic", "Alphabet", "Kids", "Animals", "Learn", "ABC", "Zakaria", "Let&apos",
                        "cartoon", "Video", "“Learn", "Zakaria&quot", "fun", "unique", "way", "cute", "<br><br>This", "make",
                        "laugh", "smile", "while", "teaching", "theme", "letters", "Alif", "Baa", "Taa", "Tha", "Jeem",
                        "Haa", "Kha", "Dal", "dhal", "raa"));
                }),
                with(video("vLearnArabicAlphabet", "Learn arabic alphabet تعليم اللغة العربية للمبتدئين", "Quizzes Edu"), v -> {
                    v.setDescription("Learn Arabic the easy way! <br><br>How to learn Arabic? Arabic is a beautiful language to learn. But you shouldn&apos;t just pick up a random textbook, and randomly start reading Arabic sentences and speaking Arabic. You have to learn it the right way. In this video, I go over the best methods to learn Arabic in the most efficient and fastest way.<br><br>In this video, I have talked about the best methods to learn Arabic easily and with fun. Also, there are methods to learn Arabic online, to learn Arabic offline, to learn Arabic with Arabic songs, to learn Arabic with Arabic Alphabets. So, I would suggest you to learn Arabic with any of these methods.<br><br>I will teach you the best methods to learn Arabic. If you follow this method, you will be able to speak Arabic in 2-3 months. You will also learn Arabic grammar, vocabulary, reading &amp; writing Arabic.<br><br>I will be teaching you how to learn Arabic. I will be showing you different methods and will show you how to learn Arabic.<br><br>I have covered the best methods and resources to learn Arabic. You can learn Arabic in 2-3 months by using these methods and resources, but it depends on you how much effort you put into it.<br><br>I have discussed that how you can learn Arabic. You will know about Arabic pronunciation, Arabic grammar, Arabic alphabet and the Arabic language. You will also learn different method to learn Arabic.<br><br>Arabic is one of the most ancient languages in the world. It is spoken in 22 countries. It is the official language of 22 countries. Arabic is also spoken as a first language in more than 140 countries. It is a language of the Qur&apos;an.");
                    v.setKeywords(List.of(
                        "Learn arabic alphabet", "تعليم اللغة العربية للمبتدئين", "تعليم اللغة العربية", "Arabisk skolen",
                        "كتاب الصف الأول", "تعليم العربية", "lær arabisk", "تعليم العربية لغير الناطقين بها",
                        "تعليم العربية للاطفال", "تعليم العربية للأجانب", "تعليم العربية لغير الناطقين بها pdf",
                        "تعليم العربية للاطفال مجانا", "تعليم العربية الحروف", "تعليم العربية القراءة", "learn arabic",
                        "arabic alphabet", "arabic language", "الموسوعة التعليمية", "Learn", "arabic", "alphabet", "تعليم",
                        "اللغة", "العربية", "للمبتدئين", "easy", "way", "<br><br>How", "beautiful", "language",
                        "shouldn&apos", "pick", "random", "textbook", "randomly", "start", "reading", "sentences",
                        "speaking", "right", "video", "go", "best", "methods", "most", "efficient", "fastest", "<br><br>In",
                        "talked", "easily"));
                }));
        List<SearchableStream> ss = new ArrayList<>();
        for (int i = 0; i < ZAKIR_UPLOADS.size(); i++) {
            ss.add(with(stream("zakir-" + i, ZAKIR_UPLOADS.get(i), "channel:UCzakir"), s -> s.setChannelName("Dr Zakir Naik")));
        }
        return started(newIndex(cs, ps, vs, ss, 5_000));
    }

    private static final List<String> ZAKIR_UPLOADS = List.of(
                "Dr Zakir Naik’s Comment on Free Masonry - Dr Zakir Naik",
                "The Reason Dr Zakir Naik Left India - Dr Zakir Naik",
                "Dr Zakir Naik Buys the Best and Latest Cameras - Dr Zakir Naik",
                "51% Wealth Of Dr Zakir Naik Goes To Charity?! - Dr Zakir Naik",
                "The Untold Story Behind Dr Zakir Naik's Career Shift - Dr Zakir Naik",
                "Dr Zakir Naik’s Dream was to be a Heart Specialist - Dr Zakir Naik",
                "Islamic Dress - Dr Zakir Naik", "Allah-Centred Women - Dr Zakir Naik",
                "Proving the Hereafter - Dr Zakir Naik", "Nisaab for Animals - Dr Zakir Naik",
                "Criteria for Hijaab - Dr Zakir Naik", "Man’s Involuntary Actions - Dr Zakir Naik",
                "Hijaab in Islam - Dr Zakir Naik", "Sharia Eliminates Robbery - Dr Zakir Naik",
                "Inheritance of Spouses - Dr Zakir Naik", "Punishment for Rape - Dr Zakir Naik",
                "Secret About Dr Zakir Naik Success?",
                "At the Beginning of his Dawah, Dr Zakir Naik Used to Go to Churches - Dr Zakir Naik",
                "Being Addicted to Luxuries - Dr Zakir Naik", "Signs of Accepted Repentance - Dr Zakir Naik",
                "Diseases Caused by Alcohol - Dr Zakir Naik", "Holidays in IIS School - Dr Zakir Naik",
                "Signs of Unaccepted Repentance - Dr Zakir Naik", "Interaction Q&A with Dr Zakir Naik",
                "A Wrong Common Supplication - Dr Zakir Naik", "All Messengers are Muslims- Dr Zakir Naik");

    @Test
    void bothZakirChannelsOutrankTheMainChannelsOwnUploads() throws Exception {
        SearchIndex.Hits hits = liveShaped().search("zakir", null, 50);
        assertEquals("exact", hits.match());
        assertEquals(Set.of("UCzakir", "UCzakirAr"), Set.copyOf(ids(hits).subList(0, 2)), ids(hits).toString());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"zakit", "zaikr"})
    void aTypoOfTheNamePutsBothZakirChannelsFirst(String query) throws Exception {
        // Ruling: which Zakir channel leads is not a defect; both must beat the main channel's own uploads.
        SearchIndex.Hits hits = liveShaped().search(query, null, 50);
        assertEquals("approximate", hits.match());
        assertEquals(Set.of("UCzakir", "UCzakirAr"), Set.copyOf(ids(hits).subList(0, 2)), ids(hits).toString());
    }

    @Test
    void wordsOutOfOrderPutTheKidsPlaylistInTheTopTen() throws Exception {
        SearchIndex.Hits hits = liveShaped().search("arabic learn", null, 50);
        assertEquals("exact", hits.match());
        int rank = ids(hits).indexOf("PLarabicKids");
        assertTrue(rank >= 0 && rank < 10, ids(hits).toString());
    }

    // ---- tri-lingual golden queries (search-languages audit 2026-10-17) ------------------------

    /** Real catalog titles and ids (prod listing and search, 2026-10-17): only what the golden rows below need. */
    private SearchIndex languages() throws Exception {
        String source = "channel:UCfassTU6mGWIj-yb26_ADjQ";
        return started(newIndex(List.of(
                        channel("UC3YmP7nqf514I1zh1eVbzrA", "Dr Zakir Naik"),
                        channel("UCWZEG9O221KaXiWThBtMHQQ", "Dr. Zakir Naik in arabic"),
                        channel("UCNB_OaI4524fASt8h0IL8dw", "Mufti Menk"),
                        channel("UCFAR9NrKzW8OWzgRscdGXxA", "Islamophobia TV"),
                        channel("UCHDFNoOk8WOXtHo8DIc8efQ", "Mohammed Hijab"),
                        channel("UCvWyP-vFhlS1-XENpD6w68A", "Stories of the Prophets - Quran Stories"),
                        channel("UCzPBBUB3SQlDx7EO00YawkQ", "Tafsir Center for Qur’anic Studies"),
                        channel("UCQHRLH8RQIrdGWMhf5heWiA", "OnePath Network"),
                        channel("UCfassTU6mGWIj-yb26_ADjQ", "Dawah Nederland"),
                        channel("UCYW44APHfIo0GyAO9iosHjQ", "الشيخ مصطفى العدوي"),
                        channel("UCkL2vNPCvXU1niLe7KhKFXg", "الشيخ مصطفى العدوي"),
                        channel("UCrZNAOv_KBLfb-3TFgxIZqg", "إذاعة القرآن الكريم"),
                        channel("UC2FUUw_1T6fLMHOpcc7uuuw", "DoctorJamil AlQudsi (‫الدكتور جميل القدسي دويك‬‎)"),
                        channel("UC8IZ6wMBddjkisG0oVy0_pQ", "Ahmed Ali (‫احمد علي‬‎)"),
                        channel("UC7AbKZa30XPMInZIsViiBOQ", "أحمد السيد")),
                List.of(
                        playlist("PLEaGEZnOHpUMC6v_ER7j43IdfMlzM4jC-", "Islam for Kids - الإسلام للأطفال"),
                        playlist("PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe", "Verhalen van de Profeten"),
                        playlist("PL327D4FD70AAB6848", "Nederlandse lezingen"),
                        playlist("PLiTKQO5a7ZXn3ePWq7YzVAXeXUmdFu6PA", "شرح العقيدة السفارينية"),
                        playlist("PLiTKQO5a7ZXk7462OvRA1qTlJdX8y5ipu", "كتاب الأربعين النووية"),
                        playlist("PLCiQWS1wXKNTkKBPqs_Ca1jIkqGTt2o2G", "مقاطع سلسلة تعليم النشء الصغير العقيدة الصحيحة"),
                        playlist("PLXS0usquloW_VC2gPb6nu1FFQTxWc1Ww_", "شــرح الأربــعــون الــنــوويــة - ابن عثيمين"),
                        playlist("PLwpWNTLjV20_XZx7tY_Gxs4oHUa9Rz5s0", "بالقرآن اهتديت ٢ Guided Through the Quran 2"),
                        playlist("PLwpWNTLjV2091zs4FkItmzL1cGZst3WdD", "بالقرآن اهتديت ١ الجزء الأول Guided Through the Quran 1"),
                        playlist("PLwpWNTLjV20_KHh9d7PTks2SdphsBY0YZ", "برنامج فسيروا 2 | رمضان 2018"),
                        playlist("PLwpWNTLjV208i3Mg2Vdm5sjwVQ6aZ07jz", "برنامج فسيروا 3 | رمضان 2019"),
                        playlist("PLwpWNTLjV208wwpCNyrj2GUEJMYroD0-2", "برنامج فسيروا 1 - 2017 | Faseero 1 - 1438"),
                        playlist("PL4rbTm5NDNGVXf3whVMtCFvQdO9J8m5H8", "تيجان النور - رمضان 1439 - 2018"),
                        playlist("PLiTKQO5a7ZXmaBJcNxgCr-BhedLw_UriP", "شرح نخبة الفكر ( الشرح الأول )"),
                        playlist("PLiTKQO5a7ZXluOys9QONER4krLwPkgGf-", "شرح الأصول من علم الأصول")),
                List.of(
                        video("Ye6CrIcubZg", "شاشات رمضان ١٤٣٩", "جمعية الدعوة بالروضة"),
                        video("cBjnzYHSV4M", "Hoe voed ik mijn kind op in Nederland? - Shaykh Suhayb Salam", null),
                        video("BcCLLPZNPHk", "Surah Al-Ikhlas Repeats with ZAKY - Let's Memorise Quran!", "One4kids")),
                List.of(
                        stream("WZuaPqbZdTE", "🌙🔴 LAATSTE RAMADAANNACHT LIVE BIJ AFAAQ — QUR’AAN, LEZING, INZAMELING & QIYĀAM 🔴🌙", source),
                        stream("ilgiwqpcOC8", "Lezing: 'De rechtvaardige kalief ʿOmar ibn ʿAbdoel-ʿAziez'", source),
                        stream("1CWKkfPncEY", "Learn This Beautiful Du’a with Subi 🤲 #muslimkids #Zaky #MuslimKids #IslamicReminder #Dua", source),
                        stream("uq7ccPxFN_8", "Test the God You Worship against Surah Ikhlaas - Dr Zakir Naik", source),
                        stream("PK3HwwqGMko", "Bhagwan Rajneesh Fails the First Test of Surah Ikhlaas - Dr Zakir Naik", source),
                        stream("Iu0eXp1v7J8", "Concept of God in Sikhism Similar to that Given in Surah Ikhlaas - Dr Zakir Naik", source),
                        stream("ADQYni3iUGA", "Mohammad Hijab vs EVERY Atheist Argument", source),
                        stream("ZfHKKlMUelo", "Mohammad Hijab Destroys the Substitution Theory", source),
                        stream("sqyOExnp5nc", "The conversation nobody expected Mohammad Hijab to have", source)),
                5_000));
    }

    /**
     * Each app language keeps its exact, typo, prefix, stopword, concept and script-variant answers (search-languages audit,
     * 2026-10-17). A row: language | query | expected ids (any one) | worst acceptable rank | tier of the first result.
     */
    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
            "en | dr zakir naik | UC3YmP7nqf514I1zh1eVbzrA UCWZEG9O221KaXiWThBtMHQQ | 1 | exact",
            "en | DR. ZAKIR NAIK!! | UC3YmP7nqf514I1zh1eVbzrA UCWZEG9O221KaXiWThBtMHQQ | 1 | exact",
            "en | mufti mank | UCNB_OaI4524fASt8h0IL8dw | 1 | approximate",
            "en | islamofobia | UCFAR9NrKzW8OWzgRscdGXxA | 1 | approximate",
            "en | mohammed hij | UCHDFNoOk8WOXtHo8DIc8efQ | 1 | exact",
            "en | the stories of the prophets | UCvWyP-vFhlS1-XENpD6w68A | 1 | exact",
            "en | kid islam | PLEaGEZnOHpUMC6v_ER7j43IdfMlzM4jC- | 1 | exact",
            "en | quranic studies tafsir | UCzPBBUB3SQlDx7EO00YawkQ | 1 | exact",
            "en | one path network | UCQHRLH8RQIrdGWMhf5heWiA | 1 | related",
            "en | surah al ikhlaas | BcCLLPZNPHk | 4 | exact",
            "en | mohammad hijab | UCHDFNoOk8WOXtHo8DIc8efQ | 4 | exact",
            "nl | verhalen van de profeten | PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe | 1 | exact",
            "nl | verhallen van de profetten | PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe | 1 | approximate",
            "nl | dawah neder | UCfassTU6mGWIj-yb26_ADjQ | 1 | exact",
            "nl | nederlandse lezing | PL327D4FD70AAB6848 | 1 | exact",
            "nl | qiyāam lezing | WZuaPqbZdTE | 1 | exact",
            "nl | MĲN KIND NEDERLAND | cBjnzYHSV4M | 1 | exact",
            "nl | kalief omar abdoel | ilgiwqpcOC8 | 1 | exact",
            "nl | smeekbede | 1CWKkfPncEY | 1 | related",
            "ar | شَرْحُ العَقِيدَةِ السَّفَّارِينِيَّةِ | PLiTKQO5a7ZXn3ePWq7YzVAXeXUmdFu6PA | 1 | exact",
            "ar | كِتَابُ ٱلۡأَرۡبَعِينَ ٱلنَّوَوِيَّةِ | PLiTKQO5a7ZXk7462OvRA1qTlJdX8y5ipu | 1 | exact",
            "ar | اذاعة القران الكريم | UCrZNAOv_KBLfb-3TFgxIZqg | 1 | exact",
            "ar | العقيده الصحيحه | PLCiQWS1wXKNTkKBPqs_Ca1jIkqGTt2o2G | 1 | exact",
            "ar | مصطفي العدوي | UCYW44APHfIo0GyAO9iosHjQ UCkL2vNPCvXU1niLe7KhKFXg | 1 | exact",
            "ar | شـرح الاربعـون النوويـة ابن عثيمين | PLXS0usquloW_VC2gPb6nu1FFQTxWc1Ww_ | 1 | exact",
            "ar | مصطفی العدوی | UCYW44APHfIo0GyAO9iosHjQ UCkL2vNPCvXU1niLe7KhKFXg | 1 | exact",
            "ar | احمد علی | UC8IZ6wMBddjkisG0oVy0_pQ | 1 | exact",
            "ar | القرآن اهتديت | PLwpWNTLjV20_XZx7tY_Gxs4oHUa9Rz5s0 PLwpWNTLjV2091zs4FkItmzL1cGZst3WdD | 1 | exact",
            "ar | برنامج فسيروا ٣ | PLwpWNTLjV208i3Mg2Vdm5sjwVQ6aZ07jz | 1 | exact",
            "ar | شاشات رمضان 1439 | Ye6CrIcubZg | 1 | exact",
            "ar | الشيخ‏ مصطفى‏ العدوي | UCYW44APHfIo0GyAO9iosHjQ UCkL2vNPCvXU1niLe7KhKFXg | 1 | exact",
            "ar | دويك جميل القدسي | UC2FUUw_1T6fLMHOpcc7uuuw | 1 | exact",
            "ar | شرح نخبة الف | PLiTKQO5a7ZXmaBJcNxgCr-BhedLw_UriP | 1 | exact",
            "ar | الاصول من علم الاصول | PLiTKQO5a7ZXluOys9QONER4krLwPkgGf- | 1 | exact",
            "ar | مصطفى العودي | UCYW44APHfIo0GyAO9iosHjQ UCkL2vNPCvXU1niLe7KhKFXg | 1 | approximate"
    })
    void goldenQueriesKeepTheirAnswerInEveryAppLanguage(String language, String query, String expected, int worstRank,
                                                         String tier) throws Exception {
        SearchIndex.Hits hits = languages().search(query, null, 50);
        List<String> ids = ids(hits);
        int rank = -1;
        for (String id : expected.split(" ")) {
            int at = ids.indexOf(id);
            if (at >= 0 && (rank < 0 || at < rank)) rank = at;
        }
        assertTrue(rank >= 0 && rank < worstRank, language + " '" + query + "' -> " + ids);
        assertEquals(tier, hits.match(), language + " '" + query + "'");
    }

    // ---- in-tab (spec §4.7) --------------------------------------------------------------------

    @Test
    void inTabSearchCoversTitleDescriptionAndNameCaseInsensitivelyAndSkipsStreams() throws Exception {
        assertEquals(List.of("PLtafsir"), ids(index.searchPage("TAFSIR", "PLAYLIST", 20, null, -1)));
        assertEquals(List.of("PLideeen"), ids(index.searchPage("activiteiten", "PLAYLIST", 20, null, -1)));
        assertEquals(List.of("UChalal"), ids(index.searchPage("kitchen", "CHANNEL", 20, null, -1)));
        assertTrue(ids(index.searchPage("halal", "VIDEO", 20, null, -1)).isEmpty());
    }

    @Test
    void inTabSearchNeverListsAStreamEvenOneAnAdmittedSourcePosts() throws Exception {
        // Review M2 (1.0.4): tab lists hold catalog documents only (spec §4.7), while global search shows the stream.
        index.upsertStreams(List.of(stream("posted-zanzibar", "Zanzibar Street Food", "channel:UChalal")), "channel:UChalal");
        index.awaitWrites();
        assertEquals(List.of("posted-zanzibar"), ids(index.search("zanzibar", "VIDEO", 20)));
        assertTrue(ids(index.searchPage("zanzibar", "VIDEO", 20, null, -1)).isEmpty());
    }

    @Test
    void filteredInTabSearchKeepsOnlyTheAllowedMembers() throws Exception {
        assertEquals(List.of("vYasin"), ids(index.searchPage("surah", "VIDEO", 20, Set.of("vYasin"), -1)));
    }

    @Test
    void tiedHitsKeepTheirOrderAcrossPagesWhenOneIsSavedBetweenFetches() throws Exception {
        // Codex P3: equal scores fell back to Lucene doc order, and a save re-appends the document.
        List<Channel> tied = List.of(channel("UCtieA", "Tied Name"), channel("UCtieB", "Tied Name"),
                channel("UCtieC", "Tied Name"), channel("UCtieD", "Tied Name"));
        SearchIndex paged = started(newIndex(tied, List.of(), List.of(), List.of(), 5_000));
        List<String> firstPage = ids(paged.searchPage("tied", "CHANNEL", 3, null, -1)).subList(0, 2);

        when(channelRepo.findById("doc-UCtieA")).thenReturn(Optional.of(tied.get(0))); // saved, unchanged
        paged.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCtieA", false));
        paged.awaitWrites();
        List<String> secondPage = ids(paged.searchPage("tied", "CHANNEL", 5, null, -1)).subList(2, 4);

        assertEquals(List.of("UCtieA", "UCtieB"), firstPage);
        assertEquals(List.of("UCtieC", "UCtieD"), secondPage);
    }

    @Test
    void inTabSearchPagesCutOneRankingWhileStreamPostsMoveTheScores() throws Exception {
        // Cubic P3 (1.0.4): each page re-ran the ranking on the newest index and cut it at an offset. Stream posts
        // share `title`, so they move BM25 statistics, and two close items swapped places between page fetches.
        SearchIndex paged = started(newIndex(List.of(), List.of(), List.of(
                        video("vInTitle", "Ramadan Night", "Masjid Alpha"), video("vInChannel", "Night Talk", "Ramadan Studio")),
                List.of(), 5_000));
        PublicContentService service = new PublicContentService(channelRepo, playlistRepo, videoRepo,
                mock(CategoryRepository.class), mock(CategoryContentOrderRepository.class), Runnable::run, paged,
                mock(LiveCategoryService.class));
        PublicContentService.SearchPage first = service.searchContent("VIDEOS", null, 1, null, null, null, "ramadan night");

        List<SearchableStream> posted = new ArrayList<>();
        for (int i = 0; i < 200; i++) posted.add(stream("posted-" + i, "Ramadan Reminder " + i, "channel:UCposted"));
        paged.upsertStreams(posted, "channel:UCposted");
        paged.awaitWrites();
        // The posts flipped the ranking: a new search now puts the other item first.
        assertEquals(List.of("vInChannel"), pageIds(service.searchContent("VIDEOS", null, 1, null, null, null, "ramadan night")));

        PublicContentService.SearchPage second = service.searchContent("VIDEOS",
                first.page().getPageInfo().getNextCursor(), 1, null, null, null, "ramadan night");
        assertEquals(List.of("vInTitle", "vInChannel"),
                java.util.stream.Stream.concat(pageIds(first).stream(), pageIds(second).stream()).toList());
    }

    private static List<String> pageIds(PublicContentService.SearchPage page) {
        return page.page().getData().stream().map(ContentItemDto::getId).toList();
    }

    @Test
    void atMostTheNewest64InTabSnapshotsAreKept() throws Exception {
        // Review I1 (1.0.4): a snapshot aged out only 600 s after a newer one was recorded, so stream posts (public,
        // unthrottled) each followed by a first page kept thousands of readers alive. Ruling: keep the newest 64.
        List<Long> recorded = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            index.upsertStreams(List.of(stream("burst-" + i, "Burst " + i, "channel:UChalal")), "channel:UChalal");
            index.awaitWrites();
            recorded.add(index.searchPage("tafsir", "PLAYLIST", 20, null, -1).snapshot());
        }
        assertEquals(70, new HashSet<>(recorded).size(), "every first page ran on a new index version");
        index.upsertStreams(List.of(stream("after-burst", "After Burst", "channel:UChalal")), "channel:UChalal");
        index.awaitWrites(); // a page whose snapshot is gone now records a version none of them had

        for (long kept : List.of(recorded.get(69), recorded.get(6))) { // the newest and the oldest of the 64
            SearchIndex.Hits page = index.searchPage("tafsir", "PLAYLIST", 20, null, kept);
            assertEquals(kept, page.snapshot());
            assertEquals(List.of("PLtafsir"), ids(page));
        }
        for (long gone : recorded.subList(0, 6)) {
            assertNotEquals(gone, index.searchPage("tafsir", "PLAYLIST", 20, null, gone).snapshot());
        }
    }

    @Test
    void closeStillReleasesTheWriterAndDirectoryWhenClosingTheSnapshotsThrows() throws Exception {
        // Review M1 (1.0.4): a first page that records while close() runs makes SearcherLifetimeManager.close() throw
        // IllegalStateException, and close() then skipped searchers.close(), writer.rollback() and dir.close().
        Directory dir = new ByteBuffersDirectory();
        SearchIndex closing = started(new SearchIndex(dir, channelRepo, playlistRepo, videoRepo, streamRepo, 5_000));
        closing.snapshots = new SearcherLifetimeManager() {
            @Override
            public synchronized void close() {
                throw new IllegalStateException("another thread called record while this SearcherLifetimeManager"
                        + " instance was being closed; not all searchers were closed");
            }
        };
        assertDoesNotThrow(closing::close);
        assertThrows(AlreadyClosedException.class, dir::listAll); // its last step ran, so the writer rollback before it did
    }

    // ---- stream visibility (spec §4.2) ---------------------------------------------------------

    @Test
    void aStreamShowsOnlyWhileAnAdmittedSourceKeepsItAndNoVideoDocumentClaimsIt() throws Exception {
        // archived / rejected / unavailable Video documents, a channel exclusion and a rejected-only source all hide
        assertEquals(List.of("halal-ok"), ids(index.search("halal", "VIDEO", 50)));
    }

    @Test
    void aStreamWithAVideoDocumentIsAnsweredByTheCatalogDocument() throws Exception {
        List<ContentItemDto> items = index.search("yusuf", null, 50).items();
        assertEquals(1, items.size());
        assertEquals("vYusuf", items.get(0).getId());
        assertEquals(10, items.get(0).getUploadedDaysAgo());
    }

    @Test
    void aStreamWithoutAVideoDocumentHasNoUploadAge() throws Exception {
        List<ContentItemDto> items = index.search("orphan", "VIDEO", 50).items();
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
            return docs(List.of(stream("late", "Late Stream", "channel:UChalal")));
        });
        slow.onReady();

        assertEquals("UCzakir", ids(slow.search("zakit", null, 10)).get(0));
        assertTrue(ids(slow.search("late", "VIDEO", 10)).isEmpty());

        release.countDown();
        slow.awaitWrites();
        assertEquals(List.of("late"), ids(slow.search("late", "VIDEO", 10)));
    }

    @Test
    void streamsLoadPageByPageInDocumentIdOrder() throws Exception {
        SearchIndex paged = newIndex(channels(), playlists(), videos(), List.of(), 5_000);
        List<SearchableStream> first = new ArrayList<>();
        for (int i = 0; i < SearchIndex.STREAM_PAGE; i++) {
            first.add(stream(String.format("s%04d", i), "Synthetic stream " + i, "channel:UChalal"));
        }
        when(streamRepo.findVisiblePage(isNull(), eq(SearchIndex.STREAM_PAGE))).thenReturn(docs(first));
        when(streamRepo.findVisiblePage(eq("s0499"), eq(SearchIndex.STREAM_PAGE)))
                .thenReturn(docs(List.of(stream("s0500", "Synthetic stream 500", "channel:UChalal"))));
        started(paged);

        assertEquals(501, paged.search("synthetic", "VIDEO", 1000).items().size());
    }

    @Test
    void aSearchBeforeTheFirstCatalogBuildIsNotReady() throws Exception {
        SearchIndex cold = newIndex(channels(), playlists(), videos(), streams(), 50);
        assertThrows(SearchIndex.NotReadyException.class, () -> cold.search("zakir", null, 10));
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
        SearchIndex first = started(new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo, ""));
        SearchIndex second = started(new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo, ""));
        Path firstDir = first.path(), secondDir = second.path();
        assertNotEquals(firstDir, secondDir, "one directory per context");
        assertTrue(Files.isDirectory(firstDir) && Files.isDirectory(secondDir));

        first.close();
        assertFalse(Files.exists(firstDir), "closing a context deletes its own directory");
        assertTrue(Files.isDirectory(secondDir), "and only its own");
        assertEquals("UCzakir", ids(second.search("zakit", null, 10)).get(0));

        second.close();
        assertFalse(Files.exists(secondDir));
    }

    @Test
    void theIndexDirectoryLivesUnderTheConfiguredBaseOrElseTheJavaTempDirectory() throws Exception {
        // B4: prod ages /tmp (tmpfiles "D /tmp ... 30d"); search.index.base-dir moves the index out of it.
        Path parent = Files.createTempDirectory("fitrahtube-base-");
        Path base = parent.resolve("search-index"); // not there yet: created on start
        SearchIndex configured = new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo, base.toString());
        try {
            assertEquals(base.toRealPath(), configured.path().getParent()); // FSDirectory keeps the real path (macOS /var)
        } finally { // an FSDirectory index closes once: not via opened
            configured.close();
            Files.delete(base);
            Files.delete(parent);
        }
        SearchIndex byDefault = new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo, "");
        opened.add(byDefault);
        assertEquals(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), byDefault.path().getParent());
    }

    @Test
    void closingAnIndexWhoseCatalogAndEventsAdmitNothingStillDeletesTheDirectory() throws Exception {
        // Empty block + delete term (Lucene asserts under -ea when such an index is rolled back).
        newIndex(List.of(), List.of(), List.of(), List.of(), 5_000); // only for its empty-catalog mocks
        SearchIndex fresh = started(new SearchIndex(channelRepo, playlistRepo, videoRepo, streamRepo, ""));
        for (int i = 0; i < 5; i++) {
            Channel pending = with(channel("UCpending" + i, "Pending " + i), c -> c.setStatus("PENDING"));
            when(channelRepo.findById(pending.getId())).thenReturn(Optional.of(pending));
            fresh.onCatalogChanged(new CatalogChanged("CHANNEL", pending.getId(), false));
        }
        fresh.awaitWrites();
        Path dir = fresh.path();

        assertDoesNotThrow(fresh::close);

        assertFalse(Files.exists(dir), "no index directory is left behind");
    }

    /** Ten DISTINCT 8-letter tokens (identical ones collapse on rewrite and hide the limit). */
    private static final List<String> LONG_TOKENS = List.of("tafsirzz", "hadithzz", "aqeedazz", "sunnahzz",
            "seerahzz", "tawhidzz", "zakaatzz", "wudhoozz", "ramadnzz", "hijrahzz");

    @Test
    void aTenWordFuzzyQueryRunsUnderLucenesDefaultClauseLimit() throws Exception {
        // Review Focus 2 + F1: 10 distinct tokens x 3 fields x 50 fuzzy expansions used to pass Lucene's default
        // 1,024 clauses. Only the 4 longest tokens get fuzzy now, so the fuzzy part of the query stays bounded.
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
        // One document near all ten tokens (it no longer ranks first: only 4 of the misspellings are fuzzed, F1).
        many.add(with(video("yt-all", "Every root", null), v -> v.setKeywords(oneEditFromEach)));
        SearchIndex big = started(newIndex(List.of(), List.of(), many, List.of(), 5_000));
        String query = String.join(" ", LONG_TOKENS);
        assertEquals(SearchText.MAX_TOKENS, LONG_TOKENS.size());

        int raised = IndexSearcher.getMaxClauseCount();
        IndexSearcher.setMaxClauseCount(1024);
        SearchIndex.Hits hits;
        try {
            hits = big.search(query, null, 20);
        } finally {
            IndexSearcher.setMaxClauseCount(raised);
        }

        assertEquals("related", hits.match(), "6 of the 10 misspellings get no fuzzy, so only the related tier matches");
        assertEquals(20, hits.items().size());
    }

    // ---- freshness (spec §5.2, §5.3) -----------------------------------------------------------

    @Test
    void anApprovedChannelEventMakesItFindable() throws Exception {
        saved(channel("UCsaadi", "Tafsir As-Saadi"));
        index.awaitWrites();
        assertEquals("UCsaadi", ids("saadi").get(0));
    }

    @Test
    void aKeywordsUpdateChangesWhatMatches() throws Exception {
        assertTrue(ids("khushoo").isEmpty());
        Video updated = with(video("vPrayerKey", "Prayer is the Key", null), v -> v.setKeywords(List.of("khushoo")));
        saved(updated);
        index.awaitWrites();
        assertEquals("vPrayerKey", ids("khushoo").get(0));
    }

    @Test
    void aDeleteRemovesTheDocumentAndHidesItsStreams() throws Exception {
        assertEquals(List.of("alafasy-live"), ids(index.search("recitation", "VIDEO", 10)));
        when(channelRepo.findById("doc-UCalafasy")).thenReturn(Optional.empty());
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCalafasy", false));
        index.awaitWrites();
        assertFalse(ids("alafa").contains("UCalafasy"));
        assertTrue(ids(index.search("recitation", "VIDEO", 10)).isEmpty());
    }

    @Test
    void rejectingASourceHidesItsStreamsBeforeRemoveSourceRuns() throws Exception {
        Channel rejected = with(channel("UChalal", "Halal Kitchen"), c -> c.setStatus("REJECTED"));
        saved(rejected);
        index.awaitWrites();
        assertTrue(ids(index.search("halal", "VIDEO", 10)).isEmpty());
        assertFalse(ids("kitchen").contains("UChalal"), "the rejected channel's own document is gone too");
    }

    @Test
    void excludingAVideoFromItsChannelHidesItsStream() throws Exception {
        // Closes the pre-existing gap (spec §4.2): exclusions never touched searchable_streams.
        Channel kitchen = with(channel("UChalal", "Halal Kitchen"),
                c -> c.getExcludedItems().setVideos(List.of("halal-excluded", "halal-ok")));
        saved(kitchen);
        index.awaitWrites();
        assertTrue(ids(index.search("halal", "VIDEO", 10)).isEmpty());
    }

    @Test
    void savingAnAdmittedChannelAgainKeepsItsStreamsVisible() throws Exception {
        // The source key is removed before the shared admit() re-adds it, never after (B3 hand-off).
        Channel renamed = channel("UChalal", "Halal Kitchen Renamed");
        renamed.getExcludedItems().setVideos(List.of("halal-excluded"));
        saved(renamed);
        index.awaitWrites();
        assertEquals(List.of("halal-ok"), ids(index.search("halal", "VIDEO", 10)));
        assertEquals("UChalal", ids("renamed").get(0));
    }

    @Test
    void aPlaylistEventIsIndexedByItsOwnType() throws Exception {
        saved(playlist("PLseerah", "Seerah of the Prophet"));
        index.awaitWrites();
        assertEquals(List.of("PLseerah"), ids(index.searchPage("seerah", "PLAYLIST", 10, null, -1)));
    }

    @Test
    void deletingAVideoDocumentRemovesIt() throws Exception {
        assertTrue(ids("prayer").contains("vPrayerKey"));
        when(videoRepo.findById("doc-vPrayerKey")).thenReturn(Optional.empty());
        index.onCatalogChanged(new CatalogChanged("VIDEO", "doc-vPrayerKey", false));
        index.awaitWrites();
        assertFalse(ids("prayer").contains("vPrayerKey"));
    }

    @Test
    void aSaveEventQueuedAfterADeleteCannotBringTheItemBack() throws Exception {
        // Codex P1: the save committed first, but its event reached the writer after the delete's.
        // Both are invalidations: each re-read sees Firestore's current state, deleted.
        when(channelRepo.findById("doc-UCalafasy")).thenReturn(Optional.empty());
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCalafasy", false)); // the delete's event
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCalafasy", false)); // the save's event, late
        index.awaitWrites();
        assertFalse(ids("alafa").contains("UCalafasy"));
        assertTrue(ids(index.search("recitation", "VIDEO", 10)).isEmpty(), "its streams stay hidden");
    }

    @Test
    void aRereadThatKeepsFailingIsRetriedThenHidesTheItemAndItsStreams() throws Exception {
        index.rereadRetryMs = new long[]{0, 0, 0};
        when(channelRepo.findById("doc-UCalafasy")).thenThrow(new ExecutionException("firestore down", new RuntimeException("cause")));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCalafasy", false));
        index.awaitWrites();
        verify(channelRepo, times(4)).findById("doc-UCalafasy"); // the read, then 3 retries
        assertFalse(ids("alafa").contains("UCalafasy"), "fails closed");
        assertTrue(ids(index.search("recitation", "VIDEO", 10)).isEmpty(), "its streams hide too");
        assertTrue(logged(Level.WARN).stream().anyMatch(e -> e.getThrowableProxy() != null
                && ExecutionException.class.getName().equals(e.getThrowableProxy().getClassName())));
    }

    @Test
    void aRereadThatFailsOnceIsRetriedAndAppliesTheCurrentState() throws Exception {
        index.rereadRetryMs = new long[]{0, 0, 0};
        when(channelRepo.findById("doc-UCzakir"))
                .thenThrow(new ExecutionException("blip", new RuntimeException()))
                .thenReturn(Optional.of(channel("UCzakir", "Dr Zakir Naik Revised")));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCzakir", false));
        index.awaitWrites();
        assertEquals("UCzakir", ids("revised").stream().findFirst().orElse("<empty>"));
    }

    @Test
    void aFailedWritesInvalidationIsReadAgainSoACommitThatLandsLateIsSeen() throws Exception {
        // B2: the ack timed out at 10 s, the re-read still saw the old state, then the reject committed.
        index.lateCommitRereadMs = new long[]{0, 0}; // the clock: both follow-ups are due now
        when(channelRepo.findById("doc-UCzakir"))
                .thenReturn(Optional.of(channel("UCzakir", "Dr Zakir Naik"))) // the commit has not landed yet
                .thenReturn(Optional.of(with(channel("UCzakir", "Dr Zakir Naik"), c -> c.setStatus("REJECTED"))));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCzakir", true));
        index.awaitWrites();
        verify(channelRepo, times(3)).findById("doc-UCzakir"); // now, then the two follow-ups
        assertFalse(ids("zakir").contains("UCzakir"), "the late reject is applied");
    }

    @Test
    void aSuccessfulWriteIsReadOnceAndTheFollowUpsWaitFifteenAndSixtySeconds() throws Exception {
        assertArrayEquals(new long[]{15_000, 60_000}, index.lateCommitRereadMs, "ruling (stage 9)");
        index.lateCommitRereadMs = new long[]{0, 0};
        when(channelRepo.findById("doc-UCzakir")).thenReturn(Optional.of(channel("UCzakir", "Dr Zakir Naik")));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCzakir", false));
        index.awaitWrites();
        verify(channelRepo, times(1)).findById("doc-UCzakir");
    }

    @Test
    void aVideoDocumentThatCannotBeMappedStillHidesItsStream() throws Exception {
        // B3: toObject threw before admit(), so the id never reached videoIds and the stream showed (fail open).
        assertTrue(ids(index.search("orphan", "VIDEO", 10)).contains("orphan-lecture"), "precondition");
        QueryDocumentSnapshot unmappable = mock(QueryDocumentSnapshot.class, inv -> switch (inv.getMethod().getName()) {
            case "getId" -> "doc-orphan-lecture";
            case "toObject" -> throw new RuntimeException("Could not deserialize object");
            case "get", "getString" -> "youtubeId".equals(inv.getArgument(0)) ? "orphan-lecture" : null;
            default -> RETURNS_DEFAULTS.answer(inv);
        });
        SearchIndex fresh = newIndex(channels(), playlists(), videos(), streams(), 5_000);
        List<QueryDocumentSnapshot> videoDocs = new ArrayList<>(videoRepo.findAllUnordered());
        videoDocs.add(unmappable);
        when(videoRepo.findAllUnordered()).thenReturn(videoDocs);
        started(fresh);
        assertFalse(ids(fresh.search("orphan", "VIDEO", 10)).contains("orphan-lecture"));
    }

    @Test
    void aNewVideoThatCannotBeMappedOnTheEventPathStillHidesItsStream() throws Exception {
        // R1: findById threw in toObject on every re-read, so the event path never learned the id (fail open for 6 h).
        index.rereadRetryMs = new long[]{0, 0, 0};
        assertTrue(ids(index.search("orphan", "VIDEO", 10)).contains("orphan-lecture"), "precondition");
        when(videoRepo.findById("doc-orphan-lecture")).thenThrow(new RuntimeException("Could not deserialize object"));
        index.onCatalogChanged(new CatalogChanged("VIDEO", "doc-orphan-lecture", false, "orphan-lecture"));
        index.awaitWrites();
        assertFalse(ids(index.search("orphan", "VIDEO", 10)).contains("orphan-lecture"));
    }

    @Test
    void aNewVideoWhoseRereadsAllFailStillHidesItsStream() throws Exception {
        // R1: all 4 re-reads failed, so applyCatalogChange(event, null) ran with no id to block.
        index.rereadRetryMs = new long[]{0, 0, 0};
        assertTrue(ids(index.search("orphan", "VIDEO", 10)).contains("orphan-lecture"), "precondition");
        when(videoRepo.findById("doc-orphan-lecture")).thenThrow(new ExecutionException("firestore down", new RuntimeException()));
        index.onCatalogChanged(new CatalogChanged("VIDEO", "doc-orphan-lecture", false, "orphan-lecture"));
        index.awaitWrites();
        verify(videoRepo, times(4)).findById("doc-orphan-lecture"); // the read, then 3 retries
        assertFalse(ids(index.search("orphan", "VIDEO", 10)).contains("orphan-lecture"));
    }

    @Test
    void aNewApprovedVideoIsFoundAsItsCatalogDocumentAndItsStreamCopyHides() throws Exception {
        // R1 positive control: the event's id changes nothing for a Video that maps.
        saved(with(video("orphan-lecture", "Orphan Lecture", null), v -> v.setUploadedAt(daysAgo(3))));
        index.awaitWrites();
        List<ContentItemDto> items = index.search("orphan", "VIDEO", 10).items();
        assertEquals(List.of("orphan-lecture"), items.stream().map(ContentItemDto::getId).toList());
        assertEquals(3, items.get(0).getUploadedDaysAgo(), "answered by the catalog document, not the stream");
    }

    @Test
    void streamHooksAddMergeAndRemove() throws Exception {
        SearchableStream fresh = stream("fresh-1", "Fresh Seerah Lesson");
        index.upsertStreams(List.of(fresh), "channel:UChalal");
        index.awaitWrites();
        assertEquals(List.of("fresh-1"), ids(index.search("fresh", "VIDEO", 10)));

        index.upsertStreams(List.of(fresh), "channel:UCalafasy");  // a second source merges in
        index.removeSource("channel:UChalal");                    // one source left: still shown
        index.awaitWrites();
        assertEquals(List.of("fresh-1"), ids(index.search("fresh", "VIDEO", 10)));

        index.removeSource("channel:UCalafasy");                  // none left: deleted
        index.awaitWrites();
        assertTrue(ids(index.search("fresh", "VIDEO", 10)).isEmpty());

        index.upsertStreams(List.of(fresh), "channel:UCquranTv");
        index.removeStream("fresh-1");                            // archived video
        index.awaitWrites();
        assertTrue(ids(index.search("fresh", "VIDEO", 10)).isEmpty());
    }

    // ---- build and swap hardening (B3 review) --------------------------------------------------

    @Test
    void aCatalogSwapThatFailsKeepsTheLastGoodCatalog() throws Exception {
        // A youtubeId past Lucene's 32766-byte term limit makes the add throw AFTER the old catalog was deleted.
        when(videoRepo.findAllUnordered()).thenReturn(docs(List.of(video("x".repeat(40_000), "Poisoned", null))));
        assertThrows(IllegalArgumentException.class, () -> index.replaceCatalog());

        // Any later refresh would expose whatever the failed swap left pending.
        index.upsertStreams(List.of(stream("after", "After The Swap", "channel:UChalal")), "channel:UChalal");
        index.awaitWrites();
        assertEquals("UCzakir", ids("zakit").stream().findFirst().orElse("<empty>"), "the last good catalog is still served");
    }

    @Test
    void aCatalogThatBecomesEmptyIsSwappedOut() throws Exception {
        when(channelRepo.findByStatusUnordered("APPROVED")).thenReturn(List.of());
        when(playlistRepo.findByStatusUnordered("APPROVED")).thenReturn(List.of());
        when(videoRepo.findAllUnordered()).thenReturn(List.of());
        index.replaceCatalog();
        assertTrue(ids("zakir").isEmpty());
        assertTrue(ids("quran").isEmpty());
    }

    @Test
    void shuttingDownDuringTheFirstBuildLogsNoErrorAndSchedulesNoRetry() throws Exception {
        SearchIndex building = newIndex(channels(), playlists(), videos(), streams(), 5_000);
        CountDownLatch inFirestore = new CountDownLatch(1);
        when(channelRepo.findByStatusUnordered("APPROVED")).thenAnswer(inv -> {
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
        when(channelRepo.findByStatusUnordered("APPROVED")).thenThrow(new NoClassDefFoundError("boom"));
        try {
            failing.onReady();
            failing.awaitWrites();
            assertTrue(logged(Level.ERROR).stream().anyMatch(e -> e.getThrowableProxy() != null
                    && NoClassDefFoundError.class.getName().equals(e.getThrowableProxy().getClassName())));
        } finally {
            failing.close();
        }
    }

    // ---- one bad document, errors, counts (F4, F5, F8) ------------------------------------------

    @Test
    void aCatalogDocumentThatCannotBeIndexedIsSkippedAndCounted() throws Exception {
        Channel corrupt = mock(Channel.class);
        when(corrupt.getId()).thenReturn("doc-UCcorrupt");
        when(corrupt.getYoutubeId()).thenReturn("UCcorrupt");
        when(corrupt.getStatus()).thenReturn("APPROVED");
        when(corrupt.getName()).thenThrow(new IllegalStateException("corrupt document"));
        List<Channel> cs = new ArrayList<>(channels());
        cs.add(0, corrupt);

        SearchIndex tolerant = started(newIndex(cs, playlists(), videos(), streams(), 5_000));

        assertEquals("UCzakir", ids(tolerant.search("zakit", null, 10)).get(0));
        assertTrue(logged(Level.WARN).stream().anyMatch(e -> e.getFormattedMessage().contains("doc-UCcorrupt")
                && e.getThrowableProxy() != null));
        assertTrue(logged(Level.INFO).stream().anyMatch(e -> e.getFormattedMessage().startsWith("search index catalog:")
                && e.getFormattedMessage().contains("skipped=1")));
    }

    @Test
    void aStreamThatCannotBeIndexedIsSkippedAndTheRestOfItsPageLoads() throws Exception {
        SearchableStream corrupt = mock(SearchableStream.class);
        when(corrupt.getStreamId()).thenReturn("corrupt-stream");
        when(corrupt.getTitle()).thenThrow(new IllegalStateException("corrupt document"));
        List<SearchableStream> page = new ArrayList<>(streams());
        page.add(0, corrupt);

        SearchIndex tolerant = started(newIndex(channels(), playlists(), videos(), page, 5_000));

        assertEquals(List.of("halal-ok"), ids(tolerant.search("halal", "VIDEO", 50)));
        assertTrue(logged(Level.WARN).stream().anyMatch(e -> e.getFormattedMessage().contains("corrupt-stream")
                && e.getThrowableProxy() != null));
        assertTrue(logged(Level.INFO).stream().anyMatch(e -> e.getFormattedMessage().startsWith("search index built:")
                && e.getFormattedMessage().contains("skippedStreams=1")));
    }

    @Test
    void anErrorWhileLoadingStreamsIsLoggedWithItsStackTrace() throws Exception {
        // F5: an Error used to vanish inside the executor's future, and the P1 "built" line never came.
        SearchIndex failing = newIndex(channels(), playlists(), videos(), List.of(), 5_000);
        when(streamRepo.findVisiblePage(isNull(), eq(SearchIndex.STREAM_PAGE))).thenThrow(new NoClassDefFoundError("boom"));
        started(failing);
        assertTrue(logged(Level.ERROR).stream().anyMatch(e -> e.getThrowableProxy() != null
                && NoClassDefFoundError.class.getName().equals(e.getThrowableProxy().getClassName())));
    }

    @Test
    void anErrorInAReconcileIsLoggedWithItsStackTrace() throws Exception {
        when(channelRepo.findByStatusUnordered("APPROVED")).thenThrow(new NoClassDefFoundError("boom"));
        index.reconcile();
        index.awaitWrites();
        assertTrue(logged(Level.WARN).stream().anyMatch(e -> e.getThrowableProxy() != null
                && NoClassDefFoundError.class.getName().equals(e.getThrowableProxy().getClassName())));
    }

    @Test
    void theCatalogLineCountsVideoDocumentsNotDistinctIds() throws Exception {
        // F8 / P1: videos + blockedVideos = every Video document, even when two documents share a youtubeId.
        Video first = video("vDup", "Duplicate One", null);
        Video second = with(video("vDup", "Duplicate Two", null), v -> v.setId("doc-vDup-2"));
        Video blocked = with(video("vBlocked", "Blocked One", null), v -> v.setStatus("REJECTED"));

        started(newIndex(List.of(), List.of(), List.of(first, second, blocked), List.of(), 5_000));

        assertTrue(logged(Level.INFO).stream().anyMatch(e -> e.getFormattedMessage()
                        .startsWith("search index catalog: channels=0 playlists=0 videos=2 blockedVideos=1")),
                () -> logged(Level.INFO).stream().map(ILoggingEvent::getFormattedMessage).toList().toString());
    }

    @Test
    void aTragicWriterFailureIsLoggedOnceAtError() throws Exception {
        // F7: after a tragic IndexWriter failure every later write fails too; only a restart recovers.
        AtomicBoolean diskFails = new AtomicBoolean();
        Directory disk = new FilterDirectory(new ByteBuffersDirectory()) {
            @Override
            public IndexOutput createOutput(String name, IOContext context) throws IOException {
                if (diskFails.get()) throw new IOException("disk failed");
                return super.createOutput(name, context);
            }
        };
        SearchIndex broken = started(new SearchIndex(disk, channelRepo, playlistRepo, videoRepo, streamRepo, 5_000));
        opened.add(broken);
        diskFails.set(true);

        for (int i = 0; i < 3; i++) {
            broken.upsertStreams(List.of(stream("tragic-" + i, "Tragic " + i, "channel:UChalal")), "channel:UChalal");
        }
        broken.awaitWrites();

        List<ILoggingEvent> tragic = logged(Level.ERROR).stream()
                .filter(e -> e.getFormattedMessage().contains("tragic") && e.getThrowableProxy() != null).toList();
        assertEquals(1, tragic.size(), () -> logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList().toString());
    }

    // ---- writer hardening (B5) -----------------------------------------------------------------

    @Test
    void anErrorInAWriterTaskIsLoggedWithItsStackTrace() throws Exception {
        // An Error (not an Exception) used to vanish inside the executor's future: no log, no trace.
        when(channelRepo.findById("doc-UCzakir")).thenThrow(new NoClassDefFoundError("boom"));
        index.onCatalogChanged(new CatalogChanged("CHANNEL", "doc-UCzakir", false));
        index.awaitWrites();
        assertTrue(logged(Level.WARN).stream().anyMatch(e -> e.getThrowableProxy() != null
                && NoClassDefFoundError.class.getName().equals(e.getThrowableProxy().getClassName())));
    }

    @Test
    void shuttingDownDuringAReconcileLogsNoWarning() throws Exception {
        SearchIndex reconciling = started(newIndex(channels(), playlists(), videos(), streams(), 5_000));
        CountDownLatch inFirestore = new CountDownLatch(1);
        when(channelRepo.findByStatusUnordered("APPROVED")).thenAnswer(inv -> {
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
    void awaitWritesDoesNotWaitForARetryScheduledForLater() throws Exception {
        SearchIndex unreachable = newIndex(channels(), playlists(), videos(), streams(), 5_000);
        when(channelRepo.findByStatusUnordered("APPROVED")).thenThrow(new ExecutionException("firestore down", new RuntimeException()));
        try {
            unreachable.onReady(); // fails, then schedules its retry 30 s out
            assertTimeoutPreemptively(Duration.ofSeconds(5), () -> unreachable.awaitWrites());
        } finally {
            unreachable.close();
        }
    }
}
