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
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    // ---- Task 19: FULL ---------------------------------------------------------------------------

    /** A source already crawled: complete, its corpus written, last polled and changed `daysAgo` ago. */
    void crawled(String key, List<InnerCorpus.Item> stored, long count, int daysAgo) throws IOException {
        InnerCorpus.write(store.corpusFile(key), stored);
        InnerState.Source src = crawler.source(key);
        long at = now.get().toEpochMilli() - daysAgo * InnerCrawler.DAY_MS;
        src.firstPageAt = at;
        src.completeAt = at;
        src.lastPollAt = at;
        src.lastChangeAt = at;
        src.count = count;
        src.hl = "en";
        src.items = stored.size();
    }

    @Test
    void fullModeOrdersNewSourcesThenDuePollsThenBackfillThenDirty() throws Exception {
        String fresh = "channel:UCaaaaaaaaaaaaaaaaaaaaaa", due = "channel:UCbbbbbbbbbbbbbbbbbbbbbb",
                backfill = "channel:UCcccccccccccccccccccccc", dirty = "channel:UCdddddddddddddddddddddd";
        for (String k : List.of(fresh, due, backfill, dirty)) admitted.put(k, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(due, items("due", 0, 3, "x"), 3, 2);
        crawled(dirty, items("dirt", 0, 3, "x"), 3, 0);
        crawler.source(dirty).dirty = true;
        crawled(backfill, items("back", 0, 100, "x"), 900, 0);
        crawler.source(backfill).completeAt = 0;                          // first-paged only
        long t = now.get().toEpochMilli();
        assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.FULL, fresh), crawler.nextJob(admitted, t));
        crawled(fresh, items("fresh", 0, 1, "x"), 1, 0);
        assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.POLL, due), crawler.nextJob(admitted, t));
        crawler.source(due).lastPollAt = t;
        assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.FULL, backfill), crawler.nextJob(admitted, t));
        store.state().backfillHeld = true;                                // R32 rule of three: no backfill crawls
        assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.FULL, dirty), crawler.nextJob(admitted, t));
        crawler.source(dirty).dirty = false;
        assertNull(crawler.nextJob(admitted, t));
    }

    @Test
    void aShortSourceIsListedInFullAtItsPollSlot() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 3);
        crawler.source(CH).shortCrawl = true;
        crawler.source(CH).completeAt = 0;
        assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.FULL, CH), crawler.nextJob(admitted, now.get().toEpochMilli()));
    }

    @Test
    void aCompleteFullCrawlSweepsAndAShortOneOnlyMerges() throws Exception {
        admitted.put(CH, "x");
        admitted.put(PL, "x");
        store.state().mode = InnerState.Mode.FULL;
        InnerCorpus.write(store.corpusFile(CH), items("gone", 0, 2, "x"));
        InnerCorpus.write(store.corpusFile(PL), items("kept", 0, 2, "x"));
        crawler.source(CH).firstPageAt = 1;
        crawler.source(PL).firstPageAt = 1;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(150, items("lesson", 0, 100, "x"), true));
        when(fetcher.next(eq(CH), anyString(), any())).thenReturn(batch(-1, items("lesson", 100, 50, "x"), false));
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(300, items("story", 0, 100, "x"), true));
        when(fetcher.next(eq(PL), anyString(), any())).thenReturn(batch(-1, items("story", 100, 50, "x"), false));
        step();
        step();
        assertEquals(150, corpus(CH).size(), "complete (150 of 150): swept, the two old ids are gone");
        assertTrue(corpus(CH).stream().noneMatch(i -> i.id().startsWith("gone")));
        assertTrue(source(CH).completeAt > 0);
        assertFalse(source(CH).shortCrawl);
        assertEquals(152, corpus(PL).size(), "short (150 of 300): merged, nothing dropped");
        assertTrue(source(PL).shortCrawl);
        assertEquals(0, source(PL).completeAt);
    }

    @Test
    void theToleranceIsTwoItemsOrOnePercent() {
        assertEquals(2, InnerCrawler.tolerance(100));
        assertEquals(3, InnerCrawler.tolerance(300));
        assertEquals(17, InnerCrawler.tolerance(1634));
        assertTrue(InnerCrawler.complete(98, 100, false));
        assertFalse(InnerCrawler.complete(97, 100, false));
        assertTrue(InnerCrawler.complete(297, 300, false));
        assertFalse(InnerCrawler.complete(296, 300, false));
        assertTrue(InnerCrawler.complete(5, -1, true));
        assertFalse(InnerCrawler.complete(5, -1, false));
    }

    @Test
    void threeShortCrawlsOfLargeSourcesInARowHoldTheBackfill() throws Exception {
        store.state().mode = InnerState.Mode.FULL;
        for (String k : List.of("channel:UCaaaaaaaaaaaaaaaaaaaaaa", "channel:UCbbbbbbbbbbbbbbbbbbbbbb", "channel:UCcccccccccccccccccccccc")) {
            admitted.put(k, "x"); // new sources: each one's FULL job is its whole crawl
            when(fetcher.first(eq(k), anyString())).thenReturn(batch(5_000, items("lesson", 0, 100, "x"), true));
            when(fetcher.next(eq(k), anyString(), any())).thenReturn(batch(-1, items("lesson", 100, 100, "x"), false));
        }
        step();
        step();
        assertFalse(store.state().backfillHeld);
        step();
        assertTrue(store.state().backfillHeld);
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("backfill held")));
    }

    @Test
    void aCrawlStoppedAtALoweredCapKeepsTheOlderItems() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        store.state().capPerSource = 100;
        crawled(CH, items("lesson", 0, 300, "x"), 300, 0);
        crawler.source(CH).dirty = true;
        List<InnerCorpus.Item> newest = new ArrayList<>(items("new", 0, 50, "x"));
        newest.addAll(items("lesson", 0, 50, "x"));
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(350, newest, true));
        step();
        assertEquals(350, corpus(CH).size(), "50 new + the 300 stored: a capped window never sweeps older items");
        assertTrue(source(CH).capped);
        assertFalse(source(CH).dirty);
        verify(fetcher, never()).next(anyString(), anyString(), any());
    }

    @Test
    void aPollAddsNewUploadsAndDropsOnesGoneFromItsWindow() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 150, "x"), 150, 2);
        List<InnerCorpus.Item> page = new ArrayList<>(items("new", 0, 2, "x"));
        page.addAll(items("lesson", 0, 1, "x"));
        page.addAll(items("lesson", 2, 97, "x")); // lesson…01 is gone; the window ends at lesson…98
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(151, page, true));
        step();
        List<String> ids = corpus(CH).stream().map(InnerCorpus.Item::id).toList();
        assertEquals(151, ids.size());
        assertFalse(ids.contains(id("lesson", 1)));
        assertTrue(ids.contains(id("lesson", 149)), "older than the window: kept");
        assertEquals(id("new", 0), ids.get(0));
        assertFalse(source(CH).dirty);
        assertEquals(now.get().toEpochMilli(), source(CH).lastChangeAt);
        verify(fetcher, never()).next(anyString(), anyString(), any());
    }

    @Test
    void aPollThatFindsOnlyNewItemsFollowsUntilItMeetsAKnownOne() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 50, "x"), 50, 2);
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(180, items("new", 0, 100, "x"), true));
        List<InnerCorpus.Item> second = new ArrayList<>(items("new", 100, 30, "x"));
        second.addAll(items("lesson", 0, 50, "x"));
        when(fetcher.next(eq(CH), anyString(), any())).thenReturn(batch(-1, second, true));
        step();
        verify(fetcher, times(1)).next(eq(CH), anyString(), any());
        assertEquals(180, corpus(CH).size());
    }

    @Test
    void aCountBelowWhatThePollKeepsMarksTheSourceDirtyForAFullCrawl() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 300, "x"), 300, 2);
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(290, items("lesson", 0, 100, "x"), true));
        step();
        assertTrue(source(CH).dirty, "300 kept but YouTube counts 290: something older left");
        assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.FULL, CH), crawler.nextJob(admitted, now.get().toEpochMilli()));
    }

    @Test
    void aChangedPlaylistIsListedAgainAtOnceAndAnUnchangedOneOnlyRefreshesItsTitles() throws Exception {
        admitted.put(PL, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(PL, items("story", 0, 40, "Old title"), 40, 2);
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(40, items("story", 0, 40, "New title"), false));
        step();
        assertTrue(corpus(PL).stream().allMatch(i -> i.t().startsWith("New title")));
        crawler.source(PL).lastPollAt = 0;
        List<InnerCorpus.Item> edited = new ArrayList<>(items("story", 0, 39, "New title"));
        edited.addAll(items("story", 40, 2, "New title")); // the owner removed story…39 and added two
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(41, edited, false));
        step();
        assertEquals(41, corpus(PL).size(), "count changed; 41 ≤ 100 items: the poll page itself is the complete listing");
        assertTrue(corpus(PL).stream().noneMatch(i -> i.id().equals(id("story", 39))),
                "listed again and swept: a channel-style poll would keep the item below its window");
        assertEquals(now.get().toEpochMilli(), source(PL).lastChangeAt, "§6.4: a poll that found a change");
        verify(fetcher, times(2)).first(eq(PL), anyString());
        crawler.source(PL).lastPollAt = 0;
        crawler.source(PL).lastChangeAt = now.get().toEpochMilli() - 2 * InnerCrawler.DAY_MS;
        List<InnerCorpus.Item> added = new ArrayList<>(edited);
        added.addAll(items("story", 42, 1, "New title")); // only an addition: an old video, new to the playlist
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(42, added, false));
        step();
        assertEquals(now.get().toEpochMilli(), source(PL).lastChangeAt, "§6.4: a poll that found a new id, however old");
        verify(fetcher, never()).next(anyString(), anyString(), any());
    }

    @Test
    void thePollIntervalIsHalfTheTimeSinceTheLastChangeBetweenOneAndSevenDays() {
        long t = 100 * InnerCrawler.DAY_MS;
        InnerState.Source src = new InnerState.Source();
        src.lastChangeAt = t - InnerCrawler.DAY_MS;
        assertEquals(InnerCrawler.DAY_MS, InnerCrawler.pollInterval(src, t));
        src.lastChangeAt = t - 10 * InnerCrawler.DAY_MS;
        assertEquals(5 * InnerCrawler.DAY_MS, InnerCrawler.pollInterval(src, t));
        src.lastChangeAt = t - 30 * InnerCrawler.DAY_MS;
        assertEquals(7 * InnerCrawler.DAY_MS, InnerCrawler.pollInterval(src, t));
    }

    private void today(long crawlerRequests) {
        store.state().requestsByDay.put("2026-10-20", new TreeMap<>(Map.of(CrawlPacer.CRAWLER_THREAD, crawlerRequests)));
    }

    @Test
    void theSteadyStateStopsAtAThousandRequestsAUtcDay() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 2);
        today(999);
        long wait = step();
        assertEquals(Instant.parse("2026-10-21T00:00:00Z").toEpochMilli() - now.get().toEpochMilli(), wait);
        verifyNoInteractions(fetcher);
    }

    @Test
    void theDailyCapInsideAFullCrawlKeepsTheOldCorpusAndRecordsNoSignal() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 150, "x"), 150, 0);
        crawler.source(CH).dirty = true;                 // estimate 1 + 2 = 3 requests: 997 + 3 fits the cap
        today(997);
        when(fetcher.first(eq(CH), anyString())).thenAnswer(inv -> {
            pacer.before(BROWSE);
            pacer.before(BROWSE);
            return batch(400, items("new", 0, 100, "x"), true);
        });
        when(fetcher.next(eq(CH), anyString(), any())).thenAnswer(inv -> {
            pacer.before(BROWSE);                         // the 1,001st request is refused before it is sent
            return batch(-1, items("new", 100, 100, "x"), true);
        });
        step();
        assertEquals(150, corpus(CH).size(), "the old corpus stays");
        assertTrue(store.state().signals.isEmpty());
        assertTrue(source(CH).dirty, "still due tomorrow");
        assertTrue(step() > 0);
    }

    @Test
    void aCompleteSourceWhoseCorpusFileIsGoneIsCrawledAgain() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 2);
        Files.delete(store.corpusFile(CH));
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(3, items("lesson", 0, 3, "x"), false));
        step();                                           // the poll finds no file: the source is reset, nothing fetched
        verifyNoInteractions(fetcher);
        assertEquals(0, source(CH).firstPageAt);
        step();                                           // now a new source: a full crawl
        verify(fetcher, times(1)).first(eq(CH), anyString());
        assertEquals(3, corpus(CH).size());
        assertTrue(source(CH).completeAt > 0);
    }

    @Test
    void aSourceNoLongerAdmittedIsSkippedAndComesBackWithoutAFetch() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 0);
        admitted.remove(CH);
        assertNull(crawler.nextJob(admitted, now.get().toEpochMilli()));
        admitted.put(CH, "x");
        assertNull(crawler.nextJob(admitted, now.get().toEpochMilli()), "re-admitted: its corpus is still there, not due yet");
        verifyNoInteractions(fetcher);
    }

    // ---- Task 19: beyond the brief ---------------------------------------------------------------

    @Test
    void theDailyCountSurvivesARestartAndResetsAtMidnightUtc() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 2);
        today(999);
        store.save();
        store = new InnerStore(tmp.resolve("inner").toString()); // a restart: the day's count is read back from state.json
        pacer = new CrawlPacer(store, mock(YouTubeThrottler.class), clock);
        crawler = new InnerCrawler(store, pacer, fetcher, search, categories, channels, clock);
        assertTrue(step() > 0);
        verifyNoInteractions(fetcher);
        now.set(Instant.parse("2026-10-21T00:00:00Z"));
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(3, items("lesson", 0, 3, "x"), false));
        assertEquals(0, step(), "a new UTC day: a new budget");
        verify(fetcher).first(eq(CH), anyString());
    }

    @Test
    void aShortDirtyCrawlWaitsForItsPollSlotInsteadOfRepeating() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 150, "x"), 1_500, 0);
        crawler.source(CH).dirty = true;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(1_490, items("lesson", 0, 100, "x"), true));
        when(fetcher.next(eq(CH), anyString(), any())).thenReturn(batch(-1, items("lesson", 100, 100, "x"), false));
        step();
        assertTrue(source(CH).shortCrawl);
        assertFalse(source(CH).dirty, "R32: a short crawl is retried at the next poll slot");
        assertNull(crawler.nextJob(admitted, now.get().toEpochMilli()));
    }

    @Test
    void aSuccessfulPollOrFullCrawlEndsTheBackoffRun() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 2);
        crawler.source(CH).failures = 4;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(3, items("lesson", 0, 3, "x"), false));
        step();
        assertEquals(0, source(CH).failures, "the next failure waits 1 h again");
    }

    // ---- Task 19 fix round 1 ----------------------------------------------------------------------

    @Test
    void aDueDirtySourceIsServedAtItsPollSlotUnderTheCap() throws Exception {
        String clean = "channel:UCbbbbbbbbbbbbbbbbbbbbbb", dirty = "channel:UCdddddddddddddddddddddd";
        admitted.put(clean, "x");
        admitted.put(dirty, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(clean, items("lesson", 0, 3, "x"), 3, 2);   // due 1 d ago
        crawled(dirty, items("story", 0, 3, "x"), 3, 3);    // due 1.5 d ago
        crawler.source(dirty).dirty = true;
        today(990);                                         // steady and the cap binds: priority 4 would never run
        when(fetcher.first(eq(dirty), anyString())).thenReturn(batch(3, items("story", 0, 3, "x"), false));
        step();
        verify(fetcher).first(eq(dirty), anyString());
        verify(fetcher, never()).first(eq(clean), anyString());
        assertFalse(source(dirty).dirty);
    }

    @Test
    void theWindowEndsAtItsLastKnownIdSoAResurfacedIdDropsNothingBelowIt() {
        List<InnerCorpus.Item> stored = items("lesson", 0, 150, "x");
        List<InnerCorpus.Item> window = new ArrayList<>(items("lesson", 120, 1, "x")); // resurfaced at the top
        window.addAll(items("lesson", 0, 2, "x"));
        List<InnerCorpus.Item> merged = InnerCrawler.mergeWindow(window, stored);
        assertEquals(150, merged.size(), "§6.4 o = lesson…01, the window's oldest known id: nothing below it goes");
        assertEquals(window, merged.subList(0, 3));
    }

    @Test
    void aCappedPlaylistCrawlNeverDropsItemsTheOwnerReordered() throws Exception {
        admitted.put(PL, "x");
        store.state().mode = InnerState.Mode.FULL;
        store.state().capPerSource = 100;
        crawled(PL, items("story", 0, 300, "x"), 300, 2);
        List<InnerCorpus.Item> page = new ArrayList<>(items("story", 0, 50, "x"));
        page.addAll(items("story", 200, 50, "x"));          // the owner moved 200…249 up: order is theirs, not upload time
        when(fetcher.first(eq(PL), anyString())).thenReturn(batch(300, page, true));
        step();
        assertTrue(source(PL).capped);
        assertEquals(300, corpus(PL).size(), "capped: merged, nothing dropped");
        verify(fetcher, never()).next(anyString(), anyString(), any());
    }

    @Test
    void aFullCrawlStartsLastChangeAtAtTheNewestUploadAndARemovalAtNow() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(3, items("lesson", 0, 3, "x"), false));
        step();
        assertEquals(20_000 * InnerCrawler.DAY_MS, source(CH).lastChangeAt, "R22: the newest upload day, not the crawl time");
        crawler.source(CH).dirty = true;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(2, items("lesson", 0, 2, "x"), false));
        step();
        assertEquals(now.get().toEpochMilli(), source(CH).lastChangeAt, "an item was removed");
    }

    @Test
    void aPollGapFillCountsListedItemsSoAHiddenListingStopsAtTheCap() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        store.state().capPerSource = 200;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 2);
        UploadsFetcher.Batch membersOnly = new UploadsFetcher.Batch(5_000, List.of(), 100, new Page(BROWSE)); // all R20-filtered
        AtomicInteger pages = new AtomicInteger();
        when(fetcher.first(eq(CH), anyString())).thenReturn(membersOnly);
        when(fetcher.next(eq(CH), anyString(), any())).thenAnswer(inv ->
                pages.incrementAndGet() < 20 ? membersOnly : new UploadsFetcher.Batch(-1, List.of(), 100, null));
        step();
        verify(fetcher, times(1)).next(eq(CH), anyString(), any());
        assertEquals(3, corpus(CH).size());
    }

    @Test
    void theBackfillGoesSmallestFirstAndUnknownCountsLast() throws Exception {
        String big = "channel:UCaaaaaaaaaaaaaaaaaaaaaa", unknown = "channel:UCbbbbbbbbbbbbbbbbbbbbbb",
                small = "channel:UCcccccccccccccccccccccc";
        store.state().mode = InnerState.Mode.FULL;
        Map<String, Long> counts = Map.of(big, 900L, unknown, -1L, small, 300L);
        for (String k : counts.keySet()) {
            admitted.put(k, "x");
            crawled(k, items("lesson", 0, 1, "x"), counts.get(k), 0);
            crawler.source(k).completeAt = 0; // first-paged only
        }
        long t = now.get().toEpochMilli();
        for (String k : List.of(small, big, unknown)) {
            assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.FULL, k), crawler.nextJob(admitted, t));
            crawler.source(k).completeAt = t;
        }
    }

    @Test
    void theDuePollsGoOldestDueFirst() throws Exception {
        String a = "channel:UCaaaaaaaaaaaaaaaaaaaaaa", b = "channel:UCbbbbbbbbbbbbbbbbbbbbbb",
                c = "channel:UCcccccccccccccccccccccc";
        store.state().mode = InnerState.Mode.FULL;
        Map<String, Integer> daysAgo = Map.of(a, 2, b, 5, c, 3); // due 1 d, 2.5 d and 1.5 d ago
        for (String k : daysAgo.keySet()) {
            admitted.put(k, "x");
            crawled(k, items("lesson", 0, 1, "x"), 1, daysAgo.get(k));
        }
        long t = now.get().toEpochMilli();
        for (String k : List.of(b, c, a)) {
            assertEquals(new InnerCrawler.Job(InnerCrawler.Kind.POLL, k), crawler.nextJob(admitted, t));
            crawler.source(k).lastPollAt = t;
        }
    }

    @Test
    void thereIsNoCapWhileTheBackfillRuns() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 100, "x"), 150, 0);
        crawler.source(CH).completeAt = 0;                 // first-paged only: R23 "pace only"
        today(999);
        when(fetcher.first(eq(CH), anyString())).thenAnswer(inv -> {
            pacer.before(BROWSE);
            pacer.before(BROWSE);
            return batch(150, items("lesson", 0, 100, "x"), true);
        });
        when(fetcher.next(eq(CH), anyString(), any())).thenAnswer(inv -> {
            pacer.before(BROWSE);
            return batch(-1, items("lesson", 100, 50, "x"), false);
        });
        step();
        assertEquals(1_002, pacer.today(CrawlPacer.CRAWLER_THREAD));
        assertEquals(150, corpus(CH).size());
        assertTrue(source(CH).completeAt > 0);
    }

    @Test
    void aNewSourceAdmittedInTheSteadyStateWaitsForTheCapLikeAPoll() throws Exception {
        String fresh = "channel:UCaaaaaaaaaaaaaaaaaaaaaa";
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        crawled(CH, items("lesson", 0, 3, "x"), 3, 0);     // steady: nothing new, nothing to backfill
        today(999);
        admitted.put(fresh, "x");                           // owner ruling: new admissions never lift the cap
        when(fetcher.first(eq(fresh), anyString())).thenReturn(batch(1, items("new", 0, 1, "x"), false));
        assertEquals(Instant.parse("2026-10-21T00:00:00Z").toEpochMilli() - now.get().toEpochMilli(), step());
        verifyNoInteractions(fetcher);
    }

    @Test
    void aSourceCappedAtALoweredCapIsNotMarkedDirtyByItsPoll() throws Exception {
        admitted.put(CH, "x");
        store.state().mode = InnerState.Mode.FULL;
        store.state().capPerSource = 100;
        crawled(CH, items("lesson", 0, 300, "x"), 300, 2);
        crawler.source(CH).capped = true;
        when(fetcher.first(eq(CH), anyString())).thenReturn(batch(290, items("lesson", 0, 100, "x"), true));
        step();
        assertFalse(source(CH).dirty, "it keeps more than a capped crawl lists, and a capped crawl never sweeps");
    }
}
