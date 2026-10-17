package com.albunyaan.tube.search;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.YouTubeThrottler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.Answer;
import org.opentest4j.AssertionFailedError;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InterruptedIOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Spec §6: the loop, the jobs and the outcome rules, with a mocked fetcher, a fake clock and a temp dir. */
class InnerCrawlerTest {

    @TempDir Path tmp;
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-20T10:00:00Z"));
    final Clock clock = new Clock() {
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    InnerStore store;
    CrawlPacer pacer;
    UploadsFetcher fetcher;
    SearchIndex search;
    CategoryRepository categories;
    ChannelRepository channels;
    InnerCrawler crawler;
    final Map<String, String> admitted = new HashMap<>();
    final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    final AtomicBoolean savedUnderTheStateMonitor = new AtomicBoolean();

    static final String CH = "channel:UCfassTU6mGWIj-yb26_ADjQ";
    static final String PL = "playlist:PLlRyFIGAU1YSG18OdUeUQFW730gXRXKBe";
    static final String AR = "channel:UCYW44APHfIo0GyAO9iosHjQ";
    static final String BROWSE = CrawlPacerTest.BROWSE;
    static final String GONE = "Got error: \"The playlist does not exist.\"";
    static final List<String> ABC = List.of("channel:UCaaaaaaaaaaaaaaaaaaaaaa", "channel:UCbbbbbbbbbbbbbbbbbbbbbb",
            "channel:UCcccccccccccccccccccccc");

    @BeforeEach
    void setUp() throws Exception {
        store = new InnerStore(tmp.resolve("inner").toString());
        store.beforeWrite = () -> {
            if (Thread.holdsLock(store.state())) savedUnderTheStateMonitor.set(true);
        };
        pacer = new CrawlPacer(store, mock(YouTubeThrottler.class), clock);
        pacer.sleeper = ms -> now.set(now.get().plusMillis(ms));
        pacer.jitter = () -> 1.0;
        fetcher = mock(UploadsFetcher.class);
        search = mock(SearchIndex.class);
        when(search.sources()).thenAnswer(inv -> new HashMap<>(admitted));
        categories = mock(CategoryRepository.class);
        channels = mock(ChannelRepository.class);
        crawler = new InnerCrawler(store, pacer, fetcher, search, categories, channels, clock);
        logs.start();
        ((Logger) LoggerFactory.getLogger(InnerCrawler.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(InnerCrawler.class)).detachAppender(logs);
        assertFalse(savedUnderTheStateMonitor.get(), "ruling 5: no save() while holding the InnerState monitor");
    }

    long step() throws Exception {
        long[] r = {0};
        CrawlPacerTest.onCrawler(() -> r[0] = crawler.step());
        return r[0];
    }

    static String id(String prefix, int i) {
        return prefix + String.format("%0" + (11 - prefix.length()) + "d", i);
    }

    /** `n` items newest first: ids prefix00…, upload days falling from 20,000. */
    static List<InnerCorpus.Item> items(String prefix, int from, int n, String title) {
        List<InnerCorpus.Item> out = new ArrayList<>();
        for (int i = from; i < from + n; i++) out.add(new InnerCorpus.Item(id(prefix, i), title + " " + i, 1800, 20_000 - i, null, null));
        return out;
    }

    static UploadsFetcher.Batch batch(long count, List<InnerCorpus.Item> items, boolean more) {
        return new UploadsFetcher.Batch(count, items, items.size(), more ? new Page("https://www.youtube.com/youtubei/v1/browse") : null);
    }

    /** As NewPipe does: one request through the shared downloader's pacer hook, then the failure its answer caused. */
    Answer<UploadsFetcher.Batch> requestThen(Exception failure) {
        return inv -> {
            pacer.before(BROWSE);
            throw failure;
        };
    }

    List<InnerCorpus.Item> corpus(String key) {
        return InnerCorpus.read(store.corpusFile(key));
    }

    InnerState.Source source(String key) {
        return store.state().sources.get(key);
    }

    static Channel channel(String youtubeId, String... categoryIds) {
        Channel c = new Channel(youtubeId);
        c.setCategoryIds(List.of(categoryIds));
        return c;
    }

    // ---- §6.2 the loop --------------------------------------------------------------------------

    @Test
    void offTheStopFileAndAPauseFetchNothing() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        assertEquals(InnerCrawler.IDLE_MS, step()); // mode OFF is the default
        store.state().mode = InnerState.Mode.FIRST_PASS;
        Files.createFile(tmp.resolve("inner/STOP"));
        assertEquals(InnerCrawler.IDLE_MS, step());
        Files.delete(tmp.resolve("inner/STOP"));
        store.state().breaker.pausedUntil = now.get().toEpochMilli() + 5_000;
        assertEquals(5_000, step());
        verifyNoInteractions(fetcher);
    }

    @Test
    void theFirstPassReadsOneFirstPagePerSourceThenIdles() throws Exception {
        admitted.put(CH, "Dawah Nederland lezingen over de islam");
        admitted.put(PL, "Verhalen van de Profeten");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(150, items("lesson", 0, 100, "Lezing over het geloof"), true));
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(40, items("story", 0, 40, "Het verhaal van de profeet"), false));
        assertEquals(0, step());
        assertEquals(0, step());
        assertEquals(InnerCrawler.IDLE_MS, step()); // every source first-paged: idle until FULL
        verify(fetcher, times(1)).first(eq(CH), anyString());
        verify(fetcher, times(1)).first(eq(PL), anyString());
        verify(fetcher, never()).next(anyString(), anyString(), any());
        assertEquals(100, corpus(CH).size());
        assertEquals(150, source(CH).count);
        assertEquals(0, source(CH).completeAt, "150 items: the backfill completes it");
        assertFalse(source(CH).shortCrawl);
        assertEquals(40, corpus(PL).size());
        assertTrue(source(PL).completeAt > 0, "a complete first page completes a playlist");
        assertEquals(1, crawler.counts().get("pendingBackfill"));
    }

    @Test
    void aSmallSourceWhoseFirstPageFallsShortIsCountedTruncated() throws Exception {
        admitted.put(PL, "Verhalen van de Profeten");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(60, items("story", 0, 50, "x"), false));
        step();
        assertTrue(source(PL).shortCrawl);
        assertEquals(0, source(PL).completeAt);
        assertEquals(1, crawler.counts().get("truncatedInFirstPass"));
    }

    @Test
    void anUnknownCountIsNeitherZeroNorComplete() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(-1, items("lesson", 0, 100, "x"), true));
        step();
        assertEquals(-1, source(CH).count);
        assertEquals(0, source(CH).completeAt);
        assertFalse(source(CH).shortCrawl, "an unknown count is not a count of ≤ 100");
        assertEquals(1, crawler.counts().get("pendingBackfill"));
        assertEquals(0, crawler.counts().get("truncatedInFirstPass"));
        assertFalse(InnerCrawler.complete(100, -1, false));
        assertFalse(InnerCrawler.complete(0, -1, false));
        assertTrue(InnerCrawler.complete(3, -1, true), "unknown count: complete only when the listing ended");
        assertTrue(InnerCrawler.complete(98, 100, false));
        assertFalse(InnerCrawler.complete(97, 100, true));
        assertEquals(2, InnerCrawler.tolerance(100));
        assertEquals(11, InnerCrawler.tolerance(1_001));
    }

    @Test
    void theLanguageIsGuessedFromTheNameThenTakenFromTheTitles() throws Exception {
        admitted.put(AR, "الشيخ مصطفى العدوي");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(AR), anyString())).thenReturn(batch(3, items("advice", 0, 3, "Advice for the students of knowledge"), false));
        step();
        verify(fetcher).first(AR, "ar");
        assertEquals("en", source(AR).hl, "R21: its titles came back English; kept for every later request");
    }

    @Test
    void hlFollowsTheScriptThenDutchAgainstEnglishStopWords() {
        assertEquals("ar", InnerCrawler.hl("الشيخ مصطفى العدوي"));
        assertEquals("ar", InnerCrawler.hl("DoctorJamil AlQudsi (‫الدكتور جميل القدسي دويك‬‎)")); // more Arabic letters than Latin
        assertEquals("nl", InnerCrawler.hl("Dawah Nederland: lezingen over de islam en het geloof"));
        assertEquals("en", InnerCrawler.hl("Stories of the Prophets"));
        assertEquals("en", InnerCrawler.hl("Dr Zakir Naik"));
        assertEquals("en", InnerCrawler.hl(null));
    }

    @Test
    void englishLectureTitlesStayEnglishAndDutchTitlesStayDutch() {
        // I-1: `al` (Al-Kahf) and words both languages use (of, in, is, over, die, door, me, men) never count as Dutch
        assertEquals("en", InnerCrawler.hl("Tafsir Surah Al-Kahf | Shaykh Saleh Al-Fawzan"));
        assertEquals("en", InnerCrawler.hl("Sahih Al-Bukhari: Book of Revelation"));
        assertEquals("en", InnerCrawler.hl("Ruqyah Shariah Full - Surah Al-Baqarah Recitation"));
        assertEquals("en", InnerCrawler.hl(InnerCrawler.titles(items("aqidah", 1, 100, "Sharh Al-Aqidah Al-Wasitiyyah - Lesson"))));
        assertEquals("en", InnerCrawler.hl("Men who die in the path of Allah: over the door of Paradise"));
        assertEquals("nl", InnerCrawler.hl("Lezing over het geloof en de profeten"));
        assertEquals("nl", InnerCrawler.hl("Wat is de betekenis van tawhied"));
        assertEquals("nl", InnerCrawler.hl("Koranles voor kinderen deel 3"));
        assertEquals("nl", InnerCrawler.hl("Tafsir van Surah An-Nas"), "an/as/at are Arabic articles, not English");
    }

    @Test
    void anApostropheInsideATransliteratedWordKeepsItOneWord() {
        // round 3: split at the apostrophe, Ja'far gave `ja` and Na'at gave `na`, both Dutch markers
        assertEquals("en", InnerCrawler.hl(InnerCrawler.titles(items("tahawi", 1, 100,
                "Sharh al-Aqidah at-Tahawiyyah (Imam Abu Ja'far at-Tahawi) - Lesson"))));
        assertEquals("en", InnerCrawler.hl(InnerCrawler.titles(items("naat", 1, 100, "Beautiful Na'at Sharif"))));
        assertEquals("en", InnerCrawler.hl("Imam Ja’far as-Sadiq | Na‘at | Jaʼfar"), "’ ‘ ʼ too");
        assertEquals("nl", InnerCrawler.hl("Wat is de betekenis van tawhied"));
    }

    @Test
    void aSourceMissingFromOneSourcesReadKeepsItsStateAndCorpus() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(150, items("lesson", 0, 100, "x"), true));
        step();
        long firstPageAt = source(CH).firstPageAt;
        admitted.remove(CH); // SearchIndex.applyCatalogChange: remove, then putAll
        assertEquals(InnerCrawler.IDLE_MS, step());
        admitted.put(CH, "Dawah Nederland");
        assertEquals(InnerCrawler.IDLE_MS, step());
        assertEquals(100, corpus(CH).size());
        assertEquals(firstPageAt, source(CH).firstPageAt);
        assertEquals(100, source(CH).items);
        verify(fetcher, times(1)).first(eq(CH), anyString());
    }

    // ---- controller rulings 1 and 3: the thread, and what is crawled ----------------------------

    @Test
    void itsNewPipeCallsRunOnTheCrawlerThread() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(search.awaitBuilt(anyLong())).thenReturn(true);
        AtomicReference<String> thread = new AtomicReference<>();
        when(fetcher.first(eq(CH), anyString())).thenAnswer(inv -> {
            thread.set(Thread.currentThread().getName());
            return batch(1, items("lesson", 0, 1, "x"), false);
        });
        crawler.start();
        verify(fetcher, timeout(10_000)).first(eq(CH), anyString());
        crawler.stop();
        assertEquals(CrawlPacer.CRAWLER_THREAD, thread.get(), "paced and classified by CrawlPacer, never a gateway pool thread");
    }

    @Test
    void theCrawlerNeverReachesTheSharedBreakerGatewayOrOrchestrator() {
        CrawlPacerTest.neverHoldsTheSharedBreaker(InnerCrawler.class);
        // positive control: the check is transitive; LiveCategoryService reaches the gateway through LiveTabReader
        assertThrows(AssertionFailedError.class, () -> CrawlPacerTest.neverHoldsTheSharedBreaker(LiveCategoryService.class));
    }

    @Test
    void liveOnlyChannelsAndMixesAreNeverCrawled() throws Exception {
        String liveOnly = "UCliveonlyliveonlyliveon", both = "UCbothbothbothbothbothbo";
        admitted.put("channel:" + liveOnly, "Live only");
        admitted.put("channel:" + both, "Live and lectures");
        admitted.put("playlist:RDCMUCfassTU6mGWIj-yb26_ADjQ", "A mix");
        admitted.put(PL, "Verhalen van de Profeten");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        Category liveCategory = new Category();
        liveCategory.setId("live");
        liveCategory.setType(Category.TYPE_LIVE);
        Category lectures = new Category();
        lectures.setId("lectures");
        when(categories.findAll()).thenReturn(List.of(liveCategory, lectures));
        when(channels.findByCategoryId(eq("live"), anyInt()))
                .thenReturn(List.of(channel(liveOnly, "live"), channel(both, "live", "lectures")));
        when(fetcher.first(anyString(), anyString())).thenReturn(batch(1, items("lesson", 0, 1, "x"), false));
        assertEquals(0, step());
        assertEquals(0, step());
        assertEquals(InnerCrawler.IDLE_MS, step());
        verify(fetcher).first(eq("channel:" + both), anyString());
        verify(fetcher).first(eq(PL), anyString());
        verifyNoMoreInteractions(fetcher);
        assertEquals(2, crawler.counts().get("admitted"));
    }

    @Test
    void unreadableLiveCategoriesMeanNoJob() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(categories.findAll()).thenThrow(new TimeoutException("firestore"));
        assertEquals(InnerCrawler.IDLE_MS, step());
        verifyNoInteractions(fetcher);
    }

    // ---- §6.6 outcomes --------------------------------------------------------------------------

    @Test
    void threeUnavailableSourcesInARowAreOneSignalAndKeepTheirState() throws Exception {
        ABC.forEach(k -> admitted.put(k, "x"));
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(anyString(), anyString())).thenThrow(new ContentNotAvailableException(GONE));
        step();
        step();
        step();
        for (String k : ABC) verify(fetcher, times(1)).first(eq(k), anyString()); // a source in the run is never asked twice
        assertEquals("UNAVAILABLE_RUN", store.state().signals.get(0).type());
        for (String k : ABC) {
            assertFalse(source(k).unavailable, k);
            assertEquals(0, source(k).retryAt, k);
        }
    }

    @Test
    void twoUnavailableThenASuccessConfirmsBothForSevenDays() throws Exception {
        String a = ABC.get(0), b = ABC.get(1), c = ABC.get(2);
        ABC.forEach(k -> admitted.put(k, "x"));
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(a), anyString())).thenThrow(new ContentNotAvailableException(GONE));
        when(fetcher.first(eq(b), anyString())).thenThrow(new ContentNotAvailableException(GONE));
        when(fetcher.first(eq(c), anyString())).thenReturn(batch(1, items("lesson", 0, 1, "x"), false));
        step();
        step();
        step();
        long nowMs = now.get().toEpochMilli();
        for (String k : List.of(a, b)) {
            assertTrue(source(k).unavailable, k);
            assertEquals(nowMs + InnerCrawler.RETRY_MS, source(k).retryAt, k);
        }
        assertTrue(store.state().signals.isEmpty());
        assertEquals(InnerCrawler.IDLE_MS, step());
        now.set(now.get().plusMillis(InnerCrawler.RETRY_MS + 1));
        step();
        verify(fetcher, times(2)).first(eq(a), anyString());
    }

    @Test
    void aDeletedChannelIsGoneButTheSameAlertOnAPlaylistIsNot() throws Exception {
        String channelGone = "Got error: \"This channel does not exist.\""; // ChannelOrchestrator.channelGone's exact alert
        admitted.put(CH, "Dawah Nederland");
        admitted.put(PL, "Verhalen van de Profeten");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(anyString(), anyString())).thenThrow(new ContentNotAvailableException(channelGone));
        step();
        assertEquals(List.of(CH), store.state().breaker.unavailableRun, "I-2: a channel's uploads are gone with it");
        assertEquals(0, source(CH).retryAt, "in the run, not in transport backoff");
        step();
        assertEquals(List.of(CH), store.state().breaker.unavailableRun, "playlistGone alone rules a playlist");
        assertTrue(source(PL).retryAt > now.get().toEpochMilli(), "transport backoff");
    }

    @Test
    void anErrorInsideAJobBacksOffThatSourceAndStillPropagates() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        admitted.put(PL, "Verhalen van de Profeten");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenThrow(new AssertionError("a fault in the parser"));
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(1, items("story", 0, 1, "x"), false));
        assertThrows(AssertionError.class, this::step, "I-3: R10's run loop still logs it");
        assertEquals(now.get().toEpochMilli() + CrawlPacer.PAUSE_MS, source(CH).retryAt);
        assertEquals(0, step(), "the pass moves on");
        verify(fetcher).first(eq(PL), anyString());
        assertEquals(InnerCrawler.IDLE_MS, step());
        verify(fetcher, times(1)).first(eq(CH), anyString());
    }

    @Test
    void aSourceBackoffIsSavedAtOnceAndSurvivesARestart() throws Exception {
        String rejected = "playlist:PLshort";
        admitted.put(CH, "Dawah Nederland");
        admitted.put(rejected, "x");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenAnswer(requestThen(new IOException("timeout")));
        when(fetcher.first(eq(rejected), anyString())).thenThrow(new IllegalArgumentException("not a crawlable source key"));
        step(); // transport(): its own save is the only one after the job (the first window failure saves nothing)
        InnerState back = new InnerStore(tmp.resolve("inner").toString()).state(); // I-4: no 5-min housekeeping in between
        assertNotNull(back.sources.get(CH), "transport() saved");
        assertEquals(source(CH).retryAt, back.sources.get(CH).retryAt);
        assertTrue(back.sources.get(CH).retryAt > 0);
        step(); // the rejected key's own save
        back = new InnerStore(tmp.resolve("inner").toString()).state();
        assertEquals(source(rejected).retryAt, back.sources.get(rejected).retryAt);
        assertTrue(back.sources.get(rejected).retryAt > 0);
    }

    /** The real thread reaches the fetcher, stop() interrupts it there, and `then` ends the job; returns the saved state. */
    InnerState stopInsideTheJob(Answer<UploadsFetcher.Batch> then) throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(search.awaitBuilt(anyLong())).thenReturn(true);
        CountDownLatch inJob = new CountDownLatch(1);
        when(fetcher.first(eq(CH), anyString())).thenAnswer(inv -> {
            inJob.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // as NewPipe's I/O leaves it: the job goes on with the flag set
            }
            return then.answer(inv);
        });
        crawler.start();
        assertTrue(inJob.await(10, TimeUnit.SECONDS));
        crawler.stop();
        return new InnerStore(tmp.resolve("inner").toString()).state();
    }

    @Test
    void stopDuringTheCorpusWriteIsNeitherAPauseNorTheSourcesFailure() throws Exception {
        // the write then throws ClosedByInterruptException: an interrupted channel, not a failed disk
        InnerState back = stopInsideTheJob(inv -> batch(1, items("lesson", 0, 1, "x"), false));
        assertEquals(0, back.breaker.pausedUntil, "a clean restart is not paused");
        assertEquals(0, back.sources.get(CH).retryAt);
        assertEquals(0, back.sources.get(CH).failures);
        assertTrue(logs.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR), "no false ERROR");
    }

    @Test
    void stopDuringARequestIsNotTheSourcesFailure() throws Exception {
        InnerState back = stopInsideTheJob(inv -> {
            throw new InterruptedIOException("interrupted");
        });
        assertEquals(0, back.sources.get(CH).retryAt);
        assertEquals(0, back.sources.get(CH).failures);
    }

    @Test
    void aCorpusWriteFailureBacksOffThatSourceSoThePassMovesOnAfterThePause() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        admitted.put(PL, "Verhalen van de Profeten");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        Files.createDirectories(store.corpusFile(CH));
        Files.writeString(store.corpusFile(CH).resolve("blocker"), "x");
        when(fetcher.first(anyString(), anyString())).thenReturn(batch(1, items("lesson", 0, 1, "x"), false));
        step();
        long pausedUntil = store.state().breaker.pausedUntil;
        assertTrue(source(CH).retryAt > pausedUntil, "I-5: it waits past the pause");
        now.set(Instant.ofEpochMilli(pausedUntil));
        assertEquals(0, step());
        verify(fetcher).first(eq(PL), anyString());
        verify(fetcher, times(1)).first(eq(CH), anyString());
        assertEquals(source(CH).retryAt, new InnerStore(tmp.resolve("inner").toString()).state().sources.get(CH).retryAt);
    }

    @Test
    void stopInterruptsAPacerSleepAndReturnsAtOnce() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(search.awaitBuilt(anyLong())).thenReturn(true);
        CountDownLatch sleeping = new CountDownLatch(1);
        pacer.sleeper = ms -> {
            sleeping.countDown();
            Thread.sleep(ms); // the second request waits the 30 s interval
        };
        AtomicReference<String> why = new AtomicReference<>();
        when(fetcher.first(eq(CH), anyString())).thenAnswer(inv -> {
            pacer.before(BROWSE);
            try {
                pacer.before(BROWSE);
            } catch (CrawlPacer.CrawlPausedException e) {
                why.set(e.getMessage());
                throw e;
            }
            return batch(1, items("lesson", 0, 1, "x"), false);
        });
        crawler.start();
        assertTrue(sleeping.await(10, TimeUnit.SECONDS));
        long t0 = System.nanoTime();
        crawler.stop();
        assertTrue(System.nanoTime() - t0 < 3_000_000_000L, "I-7: stop() ends the sleep instead of waiting out its 5 s join");
        assertEquals("inner crawl paused: interrupted", why.get());
        assertEquals(0, source(CH).retryAt, "a shutdown is not this source's failure");
    }

    @Test
    void aGenericContentNotAvailableIsATransportFailureNeverUnavailable() throws Exception {
        ABC.forEach(k -> admitted.put(k, "x"));
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(anyString(), anyString())).thenThrow(new ContentNotAvailableException("Not found (\"404 Not Found\")"));
        step();
        step();
        step();
        assertTrue(store.state().signals.isEmpty());
        assertEquals(List.of(), store.state().breaker.unavailableRun);
        assertTrue(store.state().breaker.pausedUntil > now.get().toEpochMilli(), "3 of the last 10 jobs: paused 1 h");
        for (String k : ABC) assertFalse(source(k).unavailable, k);
    }

    @Test
    void aReCaptchaIsARateSignalAndTheSourceDoesNotWait() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenThrow(new ReCaptchaException("HTTP 429 Too Many Requests", BROWSE));
        step();
        assertEquals("RECAPTCHA", store.state().signals.get(0).type());
        assertEquals(0, source(CH).retryAt);
        assertEquals(0, source(CH).failures);
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("429")), "ruling 6");
    }

    @Test
    void aParseErrorIsASignalAndThatSourceWaitsSevenDays() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenAnswer(requestThen(new ParsingException("Could not get videos")));
        step();
        assertEquals("PARSE", store.state().signals.get(0).type());
        assertEquals(now.get().toEpochMilli() + InnerCrawler.RETRY_MS, source(CH).retryAt);
        assertEquals(1, source(CH).failures);
    }

    @Test
    void aKeyRejectedBeforeAnyRequestIsThatSourcesFailureNeverASignal() throws Exception {
        crawler = new InnerCrawler(store, pacer, new UploadsFetcher(), search, categories, channels, clock); // the real rejections
        String shortId = "playlist:PLshort", noChannel = "channel:notAChannelId";
        admitted.put(shortId, "NewPipe: list id under 10 chars");
        admitted.put(noChannel, "UploadsFetcher.url: no UC id");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        assertEquals(0, step());
        assertEquals(0, step());
        assertEquals(InnerCrawler.IDLE_MS, step(), "both wait; nothing is retried at once");
        assertTrue(store.state().signals.isEmpty());
        assertEquals(0, store.state().breaker.pausedUntil);
        assertEquals(30_000, store.state().breaker.intervalMs);
        assertEquals(InnerState.Mode.FIRST_PASS, store.state().mode);
        for (String k : List.of(shortId, noChannel)) {
            assertEquals(1, source(k).failures, k);
            assertEquals(now.get().toEpochMilli() + InnerCrawler.RETRY_MS, source(k).retryAt, k);
        }
        assertEquals(0, pacer.today(CrawlPacer.CRAWLER_THREAD), "no request was sent");
    }

    @Test
    void threeTransportFailuresPauseWithoutDoubling() throws Exception {
        ABC.forEach(k -> admitted.put(k, "x"));
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(anyString(), anyString())).thenAnswer(requestThen(new IOException("timeout")));
        step();
        step();
        step();
        assertTrue(store.state().breaker.pausedUntil > now.get().toEpochMilli());
        assertEquals(30_000, store.state().breaker.intervalMs);
        assertTrue(store.state().signals.isEmpty());
    }

    @Test
    void oneFailingSourceWaitsAndNeverBlocksTheOthers() throws Exception {
        String bad = ABC.get(0);
        ABC.forEach(k -> admitted.put(k, "x"));
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(bad), anyString())).thenThrow(new ContentNotAvailableException("Not found (\"404 Not Found\")"));
        when(fetcher.first(eq(ABC.get(1)), anyString())).thenReturn(batch(1, items("lesson", 0, 1, "x"), false));
        when(fetcher.first(eq(ABC.get(2)), anyString())).thenReturn(batch(1, items("story", 0, 1, "x"), false));
        step();
        assertEquals(now.get().toEpochMilli() + CrawlPacer.PAUSE_MS, source(bad).retryAt, "after the others, 1 h");
        assertEquals(0, step());
        assertEquals(0, step());
        assertEquals(InnerCrawler.IDLE_MS, step());
        now.set(now.get().plusMillis(CrawlPacer.PAUSE_MS));
        step();
        assertEquals(now.get().toEpochMilli() + 2 * CrawlPacer.PAUSE_MS, source(bad).retryAt, "doubling, up to 7 d");
        assertEquals(2, source(bad).failures);
        assertEquals(0, store.state().breaker.pausedUntil);
        assertTrue(store.state().signals.isEmpty());
        assertTrue(source(ABC.get(1)).firstPageAt > 0 && source(ABC.get(2)).firstPageAt > 0);
    }

    @Test
    void aSignalInsideAResponseIsCountedOnceEvenWhenNewPipeWrapsIt() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString())).thenAnswer(inv -> {
            try {
                pacer.after(BROWSE, 429, BROWSE, "application/json", null);
            } catch (CrawlPacer.CrawlPausedException e) {
                throw new ParsingException("NewPipe wrapped it", e);
            }
            return null;
        });
        step();
        assertEquals(1, store.state().signals.size());
        assertEquals("HTTP_429", store.state().signals.get(0).type());
        assertEquals(60_000, store.state().breaker.intervalMs);
        assertEquals(0, source(CH).retryAt, "the IP was limited, not this source");
    }

    @Test
    void aRefusalNewPipeWrapsIsNeitherASignalNorTheSourcesFailure() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        when(fetcher.first(eq(CH), anyString()))
                .thenThrow(new ParsingException("NewPipe wrapped it", new CrawlPacer.CrawlPausedException("STOP file")));
        step();
        assertTrue(store.state().signals.isEmpty());
        assertEquals(0, source(CH).retryAt);
        assertEquals(0, source(CH).failures);
    }

    @Test
    void anEmptyPageForASourceThatHadItemsIsUnavailable() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        InnerCorpus.write(store.corpusFile(CH), items("lesson", 0, 5, "x"));
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(0, List.of(), false));
        step();
        assertEquals(List.of(CH), store.state().breaker.unavailableRun);
        assertEquals(5, corpus(CH).size(), "the corpus is untouched");
    }

    @Test
    void aCorpusWriteFailureIsAnErrorNeverASignal() throws Exception {
        admitted.put(CH, "Dawah Nederland");
        store.state().mode = InnerState.Mode.FIRST_PASS;
        Files.createDirectories(store.corpusFile(CH));
        Files.writeString(store.corpusFile(CH).resolve("blocker"), "x"); // a non-empty directory where the file goes
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(1, items("lesson", 0, 1, "x"), false));
        step();
        assertTrue(store.state().signals.isEmpty());
        assertEquals(0, store.state().breaker.transportPauses);
        assertEquals(30_000, store.state().breaker.intervalMs);
        assertTrue(store.state().breaker.pausedUntil > now.get().toEpochMilli(), "a full disk pauses, so no request is wasted");
        assertEquals(0, source(CH).firstPageAt, "a failed write changes no source state");
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR && e.getFormattedMessage().contains("corpus write failed")));
    }

    @Test
    void aRestartKeepsTheModeTheBackoffAndTheCap() throws Exception {
        store.state().mode = InnerState.Mode.FIRST_PASS;
        store.state().capPerSource = 2_000;
        pacer.rateSignal("HTTP_429", "x", 429);
        step(); // the first step saves (R15: every 5 min, and the signal saved itself)
        InnerState back = new InnerStore(tmp.resolve("inner").toString()).state();
        assertEquals(InnerState.Mode.FIRST_PASS, back.mode);
        assertEquals(60_000, back.breaker.intervalMs);
        assertTrue(back.breaker.pausedUntil > now.get().toEpochMilli());
        assertEquals(2_000, back.capPerSource);
    }
}
