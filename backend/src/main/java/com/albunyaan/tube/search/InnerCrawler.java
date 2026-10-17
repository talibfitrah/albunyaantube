package com.albunyaan.tube.search;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.service.ChannelOrchestrator;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.store.AlreadyClosedException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The inner-search crawler (spec §6): one daemon thread, the only writer of the corpus and of the per-source state. It
 * reads each admitted source's listing through UploadsFetcher at CrawlPacer's pace, and classifies every job's outcome
 * for the crawler's own breaker (§6.6). Off unless `search.inner.dir` is set and an admin sets a mode (R26).
 * NewPipe is called only here, on the thread named CrawlPacer.CRAWLER_THREAD: never through YouTubeGateway's pool or
 * ChannelOrchestrator, whose threads CrawlPacer neither paces nor classifies and whose 429s reach the shared breaker.
 */
@Component
public class InnerCrawler {
    private static final Logger log = LoggerFactory.getLogger(InnerCrawler.class);
    static final long DAY_MS = 86_400_000L, IDLE_MS = 60_000, SAVE_EVERY_MS = 300_000, RETRY_MS = 7 * DAY_MS;
    // ponytail: the live-only set is re-read hourly, at most 500 approved channels per LIVE category; a channel outside
    // either gets first pages (2 requests each). Refresh on CatalogChanged, or page the query, if that ever matters.
    static final long LIVE_REFRESH_MS = 3_600_000;
    static final int LIVE_CHANNELS_MAX = 500;
    static final int DAILY_CAP = 1_000;
    /**
     * R21's language markers (I-1): stop words that only one of the two languages uses. Dutch is Lucene's Dutch stop set
     * minus its English one (in, is, of, was) minus English words and Arabic transliteration particles (al, die, door,
     * over, me, men, had, want, hem); el, an, as and on are not Dutch stop words, so they need no entry. English is
     * Lucene's English set minus the Dutch one minus the Arabic articles an, as, at (an-Nas, as-Sunnah, at-Tawhid).
     * Final Dutch (88): aan alles als altijd andere ben bij daar dan dat de der deze dit doch doen dus een eens en er ge
     * geen geweest haar heb hebben heeft het hier hij hoe hun iemand iets ik ja je kan kon kunnen maar meer met mij mijn
     * moet na naar niet niets nog nu om omdat onder ons ook op reeds te tegen toch toen tot u uit uw van veel voor waren
     * wat werd wezen wie wil worden wordt zal ze zelf zich zij zijn zo zonder zou.
     * Final English (26): a and are be but by for if into it no not on or such that the their then there these they
     * this to will with.
     */
    private static final Set<String> NL = markers(DutchAnalyzer.getDefaultStopSet(), EnglishAnalyzer.ENGLISH_STOP_WORDS_SET,
            Set.of("al", "die", "door", "over", "me", "men", "had", "want", "hem"));
    private static final Set<String> EN = markers(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET, DutchAnalyzer.getDefaultStopSet(),
            Set.of("an", "as", "at"));

    enum Kind { FIRST_PAGE, FULL, POLL }

    record Job(Kind kind, String key) {}

    private final InnerStore store;
    private final CrawlPacer pacer;
    private final UploadsFetcher fetcher;
    private final SearchIndex search;
    private final InnerIndex index; // a constructor dependency: Spring stops this crawler before it closes the index
    private final CategoryRepository categories; // ruling 3, Firestore reads only: no path to the gateway or the breaker
    private final ChannelRepository channels;
    private final Clock clock;
    private final Object wake = new Object();
    private boolean woken; // guarded by wake
    private volatile boolean stopped;
    private volatile boolean rebuildRequested;
    private boolean refreshFailing; // crawler thread only: a dead writer's stack trace is logged once
    private Thread thread;
    private long lastSaveAt;
    private String lastDailyLog = "";
    private long jobStartedAt, jobRequestsBefore;
    private volatile Set<String> liveOnly; // channel keys whose every category is LIVE; null until first read
    private long liveOnlyAt;
    private boolean steady;

    public InnerCrawler(InnerStore store, CrawlPacer pacer, UploadsFetcher fetcher, SearchIndex search, InnerIndex index,
                        CategoryRepository categories, ChannelRepository channels, Clock clock) {
        this.store = store;
        this.pacer = pacer;
        this.fetcher = fetcher;
        this.search = search;
        this.index = index;
        this.categories = categories;
        this.channels = channels;
        this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!store.enabled()) {
            log.info("inner search is off: search.inner.dir is not set");
            return;
        }
        thread = new Thread(this::run, CrawlPacer.CRAWLER_THREAD);
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    void stop() throws InterruptedException {
        stopped = true;
        wakeUp();
        if (thread != null) {
            thread.interrupt(); // I-7: ends a pacer sleep (CrawlPacer refuses it as "interrupted") or an idle wait at once
            thread.join(5_000);
        }
        store.save();
    }

    /** Ends a wait at once: an admin change, or shutdown. */
    void wakeUp() {
        synchronized (wake) {
            woken = true;
            wake.notifyAll();
        }
    }

    /** Waits up to `ms`, or until wakeUp(); a wake that came first is never lost. */
    void idle(long ms) throws InterruptedException {
        synchronized (wake) {
            if (!woken) wake.wait(ms);
            woken = false;
        }
    }

    private void run() {
        try {
            try {
                boot();
            } catch (RuntimeException | Error e) { // R10, as for a step: the crawl goes on without the inner index
                log.error("inner-index boot rebuild failed", e);
            }
            while (!stopped) {
                long ms;
                try {
                    ms = step();
                } catch (RuntimeException | Error e) { // R10: one thread, and it never dies of a fault
                    log.error("inner crawler step failed", e);
                    ms = IDLE_MS;
                }
                if (ms > 0) idle(Math.min(ms, IDLE_MS)); // a pause or the daily cap never holds back refreshes and retirement
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** R14: after the main index logs "search index built", rebuild the inner index on this thread, before any crawl. */
    void boot() throws InterruptedException {
        while (!stopped && !search.awaitBuilt(IDLE_MS)) {
            // nothing starts before the main index is built
        }
        if (!stopped) rebuildIndex();
    }

    /** R31 / §9: a cap change (admin or the memory trigger) is a rebuild from the corpus, never a re-crawl. */
    void requestRebuild() {
        rebuildRequested = true;
        wakeUp();
    }

    /** PUT /api/admin/inner-search (§9): any subset. */
    public record Update(InnerState.Mode mode, Boolean serveInner, Boolean streamGate, Boolean backfillHeld,
                         Integer capPerSource, Boolean resetPace) {}

    /**
     * Applies an admin change, saves it at once (R15) and wakes the crawler, so it takes effect before its next request.
     * A cap is set and saved before the rebuild is requested, so the crawler thread's rebuild reads the new cap.
     */
    public void update(Update u) {
        if (u.capPerSource() != null && (u.capPerSource() < 1 || u.capPerSource() > InnerState.MAX_PER_SOURCE)) {
            throw new IllegalArgumentException("capPerSource must be 1.." + InnerState.MAX_PER_SOURCE);
        }
        InnerState s = store.state();
        // R25 hand-off: FULL before any first page would run the whole initial crawl inside the 1,000/day cap (~17 days)
        if (u.mode() == InnerState.Mode.FULL && s.mode != InnerState.Mode.FULL
                && s.sources.values().stream().noneMatch(src -> src.firstPageAt > 0)) {
            throw new IllegalArgumentException("mode FULL before any first pass: run FIRST_PASS first");
        }
        boolean rebuild = false;
        synchronized (s) {
            if (u.mode() != null) s.mode = u.mode();
            if (u.serveInner() != null) s.serveInner = u.serveInner();
            if (u.streamGate() != null) s.streamGate = u.streamGate();
            if (u.backfillHeld() != null) {
                s.backfillHeld = u.backfillHeld();
                if (!u.backfillHeld()) s.shortLargeRun = 0;
            }
            if (u.capPerSource() != null && u.capPerSource() != s.capPerSource) {
                s.capPerSource = u.capPerSource();
                rebuild = true; // R31: a rebuild from the corpus, never a re-crawl
            }
        }
        if (Boolean.TRUE.equals(u.resetPace())) pacer.resetPace();
        store.save();
        log.info("inner-search settings changed: {}", u);
        if (rebuild) requestRebuild();
        else wakeUp();
    }

    /** GET /api/admin/inner-search (§9). Never the key "status": live-channels' write-path tripwire counts it. */
    public Map<String, Object> status() {
        InnerState s = store.state();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", store.enabled());
        out.put("mode", s.mode);
        out.put("stopFile", store.stopped());
        out.put("serveInner", s.serveInner);
        out.put("streamGate", s.streamGate);
        out.put("backfillHeld", s.backfillHeld);
        out.put("capPerSource", s.capPerSource);
        synchronized (s) {
            Map<String, Object> breaker = new LinkedHashMap<>();
            breaker.put("intervalMs", s.breaker.intervalMs);
            breaker.put("pausedUntil", s.breaker.pausedUntil);
            breaker.put("cleanRequestsAtInterval", s.breaker.cleanRequestsAtInterval);
            breaker.put("transportPauses", s.breaker.transportPauses);
            breaker.put("unavailableRun", List.copyOf(s.breaker.unavailableRun));
            out.put("breaker", breaker);
            out.put("lastSignal", s.signals.isEmpty() ? null : s.signals.get(s.signals.size() - 1));
            out.put("signals", List.copyOf(s.signals));
            TreeMap<String, TreeMap<String, Long>> requests = new TreeMap<>();
            s.requestsByDay.forEach((day, counts) -> requests.put(day, new TreeMap<>(counts)));
            out.put("requests", requests); // R37: per UTC day and thread prefix
            out.put("memory", s.memory.isEmpty() ? null : s.memory.get(s.memory.size() - 1));
        }
        Map<String, Integer> counts = counts();
        out.put("truncatedInFirstPass", counts.remove("truncatedInFirstPass"));
        out.put("sources", counts);
        Map<String, Object> idx = new LinkedHashMap<>();
        boolean ready = index.ready();
        int docs = 0;
        long bytes = 0;
        try {
            docs = index.docs();
            bytes = index.bytes();
        } catch (IOException | AlreadyClosedException e) { // closed at shutdown after ready(): never a 500
            ready = false;
            docs = 0;
        }
        idx.put("ready", ready);
        idx.put("docs", docs);
        idx.put("bytes", bytes);
        idx.put("rebuildCpuMs", index.rebuildCpuMs());
        idx.put("rebuildWallMs", index.rebuildWallMs());
        idx.put("timeouts", index.timeouts());
        out.put("index", idx);
        return out;
    }

    private void rebuildIndex() {
        try {
            index.rebuild(search.sources(), store.state().capPerSource);
        } catch (IOException | RuntimeException e) {
            if (stopped) return; // stop()'s interrupt (ClosedByInterruptException) ended it: not a failed rebuild
            log.error("inner-index rebuild failed; the previous inner index (if any) keeps serving", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One iteration of the loop (§6.2): the ms to wait before the next. Crawler thread only. */
    long step() {
        long now = clock.millis();
        if (rebuildRequested) {
            rebuildRequested = false;
            rebuildIndex();
        }
        try {
            index.maybeRefresh();
            refreshFailing = false;
        } catch (IOException | AlreadyClosedException e) { // a dead writer (§12): the next index write fails its job
            if (refreshFailing) log.warn("inner-index refresh failed again: {}", e.toString());
            else log.warn("inner-index refresh failed", e);
            refreshFailing = true;
        } catch (RuntimeException e) {
            if (!stopped) throw e;
            return 0; // stop()'s interrupt inside Lucene (ThreadInterruptedException): not a fault
        }
        InnerState s = store.state();
        housekeeping(now);
        if (store.stopped() || s.mode == InnerState.Mode.OFF) return IDLE_MS;
        if (now < s.breaker.pausedUntil) return s.breaker.pausedUntil - now;
        if (!readLiveOnly(now)) return IDLE_MS;
        Map<String, String> admitted = crawlable(search.sources());
        Job job = nextJob(admitted, now);
        if (job == null) return IDLE_MS;
        pacer.dailyCap(steady ? DAILY_CAP : Integer.MAX_VALUE);
        if (steady && pacer.today(CrawlPacer.CRAWLER_THREAD) + estimate(job) > DAILY_CAP) {
            return (now / DAY_MS + 1) * DAY_MS - now; // R23: due work waits for the next UTC day, oldest first
        }
        runJob(job, admitted.get(job.key()), now);
        return 0;
    }

    private void housekeeping(long now) {
        if (now - lastSaveAt >= SAVE_EVERY_MS) {
            store.save(); // R15: every 5 min; signals, modes and caps save themselves at once
            lastSaveAt = now;
        }
        String today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toString();
        if (!today.equals(lastDailyLog)) {
            lastDailyLog = today;
            logDaily(today);
        }
    }

    /** §9: once per UTC day, and at boot: the previous day's NewPipe requests per thread prefix (R37) and sources by state. */
    private void logDaily(String today) {
        InnerState s = store.state();
        String previous;
        synchronized (s) {
            Map.Entry<String, TreeMap<String, Long>> day = s.requestsByDay.lowerEntry(today);
            previous = day == null ? "none" : day.getKey() + " " + day.getValue();
        }
        log.info("inner-crawl daily: mode={} interval={}s requests[{}] sources={}", s.mode, s.breaker.intervalMs / 1000,
                previous, counts());
    }

    /**
     * Controller ruling 3: the channels whose every category is a LIVE one (a channel also in an ordinary category is
     * crawled), re-read hourly. False while they cannot be read: no job until then, so none is crawled by mistake.
     */
    private boolean readLiveOnly(long now) {
        if (liveOnly != null && now - liveOnlyAt < LIVE_REFRESH_MS) return true;
        try {
            Set<String> live = categories.findAll().stream().filter(Category::isLive).map(Category::getId)
                    .collect(Collectors.toSet());
            Set<String> keys = new HashSet<>();
            for (String categoryId : live) {
                for (Channel c : channels.findByCategoryId(categoryId, LIVE_CHANNELS_MAX)) { // APPROVED: what SearchIndex admits
                    List<String> in = c.getCategoryIds();
                    if (c.getYoutubeId() != null && in != null && !in.isEmpty() && live.containsAll(in)) keys.add("channel:" + c.getYoutubeId());
                }
            }
            liveOnly = keys;
            liveOnlyAt = now;
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("inner-crawl: LIVE categories unreadable, no job this step: {}", e.toString());
            return false;
        }
    }

    /** Ruling 3: the admitted sources (the caller's copy) minus `RD…` mixes, auto-generated and endless, and LIVE-only channels. */
    private Map<String, String> crawlable(Map<String, String> admitted) {
        Set<String> skip = liveOnly != null ? liveOnly : Set.of();
        admitted.keySet().removeIf(key -> key.startsWith("playlist:RD") || skip.contains(key));
        return admitted;
    }

    /** Crawlable sources by state (§9). */
    Map<String, Integer> counts() {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String k : List.of("admitted", "firstPaged", "complete", "short", "dirty", "capped", "unavailable",
                "pendingBackfill", "truncatedInFirstPass")) {
            c.put(k, 0);
        }
        for (String key : crawlable(search.sources()).keySet()) {
            c.merge("admitted", 1, Integer::sum);
            InnerState.Source src = store.state().sources.get(key);
            if (src == null) continue;
            if (src.firstPageAt > 0) c.merge("firstPaged", 1, Integer::sum);
            if (src.completeAt > 0 && !src.shortCrawl) c.merge("complete", 1, Integer::sum);
            if (src.shortCrawl) c.merge("short", 1, Integer::sum);
            if (src.dirty) c.merge("dirty", 1, Integer::sum);
            if (src.capped) c.merge("capped", 1, Integer::sum);
            if (src.unavailable) c.merge("unavailable", 1, Integer::sum);
            if (src.firstPageAt > 0 && src.completeAt == 0 && !src.shortCrawl) c.merge("pendingBackfill", 1, Integer::sum);
            if (src.shortCrawl && src.count >= 0 && src.count <= 100) c.merge("truncatedInFirstPass", 1, Integer::sum);
        }
        return c;
    }

    /**
     * R23: FIRST_PASS = first pages only. FULL = (1) newly admitted sources' full crawls, (2) due polls, oldest due
     * first (a short or dirty source is listed in full at its poll slot), (3) backfill full crawls, smallest first,
     * unknown counts last, unless held (R32), (4) `dirty` full crawls not yet due. Sets `steady` (no backfill left, or
     * it is held): from then on every job, a new source's too (owner ruling), runs inside the daily cap. Sources in the
     * current unavailable run wait for it to resolve (R33.7); non-admitted sources are simply never picked (R24), and
     * nothing is dropped for a missing read (SearchIndex's catalog change removes, then re-adds).
     */
    Job nextJob(Map<String, String> admitted, long now) {
        InnerState s = store.state();
        Set<String> inRun;
        synchronized (s) {
            inRun = new HashSet<>(s.breaker.unavailableRun);
        }
        boolean full = s.mode == InnerState.Mode.FULL;
        Job fresh = null, backfill = null, dirty = null, poll = null;
        long pollDue = Long.MAX_VALUE, backfillCount = Long.MAX_VALUE;
        for (String key : new TreeSet<>(admitted.keySet())) {
            InnerState.Source src = source(key);
            if (src.retryAt > now || inRun.contains(key)) continue;
            src.unavailable = false; // its 7 days are over: back to its previous state
            if (src.firstPageAt == 0) {
                if (fresh == null) fresh = new Job(full ? Kind.FULL : Kind.FIRST_PAGE, key);
                continue;
            }
            if (!full) continue;
            if (src.completeAt == 0 && !src.shortCrawl) { // first-paged only: backfill
                long count = src.count < 0 ? Long.MAX_VALUE : src.count;
                if (!s.backfillHeld && (backfill == null || count < backfillCount)) {
                    backfill = new Job(Kind.FULL, key);
                    backfillCount = count;
                }
                continue;
            }
            long due = src.lastPollAt + pollInterval(src, now);
            if (due <= now) {
                if (due < pollDue) { // a dirty source competes here too, so a binding cap never starves it
                    poll = new Job(src.shortCrawl || src.dirty ? Kind.FULL : Kind.POLL, key);
                    pollDue = due;
                }
            } else if (src.dirty && dirty == null) {
                dirty = new Job(Kind.FULL, key);
            }
        }
        steady = full && backfill == null; // a held backfill is steady; a new admission does not lift the cap
        if (fresh != null) return fresh;
        if (poll != null) return poll;
        if (backfill != null) return backfill;
        return dirty;
    }

    private void runJob(Job job, String name, long now) {
        String key = job.key();
        InnerState.Source src = source(key);
        jobStartedAt = now;
        jobRequestsBefore = pacer.today(CrawlPacer.CRAWLER_THREAD);
        pacer.jobStart();
        boolean ended = false;
        try {
            attempt(job, name, src, now);
            ended = true;
        } finally {
            if (!ended) { // I-3: an Error, or a fault inside a handler, still propagates to R10's loop; this source waits
                backoff(src, now);
                store.save();
            }
        }
    }

    /** Runs the job and classifies its outcome (§6.6, R3-18, the controller's rulings). */
    private void attempt(Job job, String name, InnerState.Source src, long now) {
        String key = job.key();
        try {
            boolean ok = switch (job.kind()) {
                case FIRST_PAGE -> firstPage(key, name, src, now);
                case FULL -> fullCrawl(key, name, src, now, null);
                case POLL -> poll(key, name, src, now);
            };
            if (ok) {
                src.failures = 0; // the next failure waits 1 h again
                succeeded(now);
            }
        } catch (CrawlPacer.CrawlPausedException e) {
            // a rate signal CrawlPacer.after already recorded, or STOP / OFF / a pause / the daily cap: nothing to add
        } catch (ReCaptchaException e) { // a rate limit on the IP (NewPipe's convention; the prod downloader's 429), never this source's fault
            if (!pacer.signalled()) pacer.rateSignal("RECAPTCHA", key, 0);
        } catch (ContentNotAvailableException e) {
            // NewPipe 0.26.5 throws a plain CNA for InnerTube JSON errors, ERROR alerts, /oops and any 404 (library guide):
            // only the definitive gone answer joins the R33.7 run; any other CNA is transient, so it counts as transport (§6.6).
            // A channel source is read as its UU… playlist, so its deleted-channel answer is channelGone's (I-2).
            if (ChannelOrchestrator.playlistGone(e) || (key.startsWith("channel:") && ChannelOrchestrator.channelGone(e))) {
                pacer.unavailable(key);
            } else {
                transport(key, src, now, e);
            }
        } catch (UncheckedIOException e) { // our own disk write: never a rate signal (§12)
            if (stopped) return; // stop()'s interrupt closed the channel (ClosedByInterruptException): not a failed disk
            log.error("inner-crawl source={}: {}; crawling paused 1 h", key, e.getMessage(), e);
            long pausedUntil = now + CrawlPacer.PAUSE_MS;
            synchronized (store.state()) {
                store.state().breaker.pausedUntil = pausedUntil; // a full disk must not burn requests on re-fetches
            }
            backoff(src, pausedUntil); // I-5: its own wait starts when the pause ends, so the pass then moves on
            store.save();
        } catch (ExtractionException | RuntimeException e) { // R33.6: a parse failure, or anything else extraction throws
            if (stopped) return; // stop()'s interrupt (e.g. Lucene's ThreadInterruptedException): not this source's failure
            if (abortedByPacer(e)) return; // NewPipe wrapped the pacer's abort: as the first catch
            src.failures++;
            src.retryAt = now + RETRY_MS;
            if (requestsSent() == 0) { // ruling 2: a key UploadsFetcher or NewPipe rejected before any request
                log.warn("inner-crawl source={}: rejected before any request ({}); retry in 7 d", key, e.toString());
            } else {
                log.warn("inner-crawl source={}: {}; retry in 7 d", key, e.toString());
                if (!pacer.signalled()) pacer.rateSignal("PARSE", key, 0);
            }
            store.save(); // I-4: the 7-day wait survives a restart
        } catch (IOException e) { // transport, another 5xx, a timeout
            transport(key, src, now, e);
        }
    }

    /**
     * §6.6 transport (and R3-18's generic CNA): the job window. The source also waits, 1 h doubling per failure in a row
     * up to 7 d, so one source that keeps failing neither blocks the key-ordered pass nor fills the window by itself.
     */
    private void transport(String key, InnerState.Source src, long now, Exception e) {
        if (stopped) return; // stop()'s interrupt (an InterruptedIOException), not this source's failure
        backoff(src, now);
        log.warn("inner-crawl source={}: {}; retry in {} min", key, e.toString(), (src.retryAt - now) / 60_000);
        if (!pacer.signalled()) pacer.transportFailure();
        store.save(); // I-4: the wait survives a restart
    }

    /** The source waits from `from`: 1 h, doubling per failure in a row, up to 7 d. */
    private static void backoff(InnerState.Source src, long from) {
        src.failures++;
        src.retryAt = from + Math.min(RETRY_MS, CrawlPacer.PAUSE_MS << Math.min(src.failures - 1, 8));
    }

    private static boolean abortedByPacer(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof CrawlPacer.CrawlPausedException) return true;
        }
        return false;
    }

    /** This job's NewPipe requests (R37's per-day counter); a job that crossed 00:00 UTC counts as one that sent some. */
    private long requestsSent() {
        if (clock.millis() / DAY_MS != jobStartedAt / DAY_MS) return 1;
        return pacer.today(CrawlPacer.CRAWLER_THREAD) - jobRequestsBefore;
    }

    /** R33.7: a success right after an unavailable run confirms each source in it as UNAVAILABLE for 7 days. */
    private void succeeded(long now) {
        for (String confirmed : pacer.jobSucceeded()) {
            InnerState.Source src = source(confirmed);
            src.unavailable = true;
            src.retryAt = now + RETRY_MS;
            log.info("inner-crawl source={} UNAVAILABLE; retried in 7 d", confirmed);
        }
    }

    /** §6.4 first page: 2 requests, the exact count plus the newest 100; a complete page (R32) is swept, others merged. */
    private boolean firstPage(String key, String name, InnerState.Source src, long now) throws IOException, ExtractionException {
        List<InnerCorpus.Item> corpus = corpus(key, src);
        if (corpus == null) return false;
        UploadsFetcher.Batch page = fetcher.first(key, src.hl != null ? src.hl : hl(name));
        if (page.listed() == 0 && !corpus.isEmpty()) {
            pacer.unavailable(key);
            return false;
        }
        boolean complete = complete(page.listed(), page.count(), page.next() == null);
        // first: a failed write changes no state
        writeCorpus(key, name, complete ? page.items() : mergeKeep(page.items(), corpus), corpus, complete);
        if (!page.items().isEmpty()) src.hl = hl(titles(page.items())); // R21: the source's own language, kept from now on
        src.count = page.count();
        src.firstPageAt = now;
        src.lastPollAt = now;
        if (src.lastChangeAt == 0) src.lastChangeAt = newest(page.items(), now);
        if (complete) {
            src.completeAt = now;
            src.shortCrawl = false;
        } else {
            src.shortCrawl = page.count() >= 0 && page.count() <= 100; // R32: truncated in the first pass; -1 is unknown
        }
        logJob("FIRST_PAGE", key, src, added(page.items(), corpus), 0);
        return true;
    }

    private long estimate(Job job) {
        if (job.kind() != Kind.FULL) return 2;
        long count = source(job.key()).count;
        int cap = Math.min(store.state().capPerSource, InnerState.MAX_PER_SOURCE);
        return count < 0 ? 2 : 1 + (Math.min(count, cap) + 99) / 100;
    }

    /**
     * §6.4 full crawl: 1 + ⌈n/100⌉ requests, until the listing ends or `capPerSource` items. Complete (R32): replaced
     * (mark and sweep). Stopped at the cap: the newest window replaces its stretch and older stored items stay, because
     * the corpus keeps everything (R31). Short: merged, never swept, and retried at the next poll slot (R32), so `dirty`
     * clears either way. A failure mid-crawl leaves the old corpus untouched; a crawl restarts from its first page.
     */
    private boolean fullCrawl(String key, String name, InnerState.Source src, long now, UploadsFetcher.Batch firstPage)
            throws IOException, ExtractionException {
        List<InnerCorpus.Item> corpus = corpus(key, src);
        if (corpus == null) return false;
        InnerState s = store.state();
        int cap = Math.min(s.capPerSource, InnerState.MAX_PER_SOURCE);
        UploadsFetcher.Batch page = firstPage != null ? firstPage : fetcher.first(key, src.hl != null ? src.hl : hl(name));
        if (page.listed() == 0 && !corpus.isEmpty()) {
            pacer.unavailable(key);
            return false;
        }
        if (src.hl == null && !page.items().isEmpty()) src.hl = hl(titles(page.items()));
        List<InnerCorpus.Item> all = new ArrayList<>(page.items());
        int listed = page.listed();
        org.schabi.newpipe.extractor.Page next = page.next();
        while (next != null && listed < cap) {
            UploadsFetcher.Batch more = fetcher.next(key, src.hl != null ? src.hl : hl(name), next);
            all.addAll(more.items());
            listed += more.listed();
            next = more.next();
        }
        all = mergeKeep(all, List.of()); // a deletion during the crawl can list one item twice
        boolean capped = next != null;
        boolean complete = !capped && complete(listed, page.count(), true);
        // a capped playlist only merges: its order is the owner's, so its window says nothing about older positions
        List<InnerCorpus.Item> stored = complete ? all
                : capped && key.startsWith("channel:") ? mergeWindow(all, corpus) : mergeKeep(all, corpus);
        int added = added(all, corpus), removed = corpus.size() + added - stored.size();
        writeCorpus(key, name, stored, corpus, complete); // first: a failed write changes no state
        src.count = page.count();
        src.capped = capped;
        src.lastPollAt = now;
        src.dirty = false;
        if (src.firstPageAt == 0) src.firstPageAt = now;
        // R22: a removal, or a poll's relist that found new ids (§6.4), changed it now; added items alone date from
        // their newest upload, so a backfill of old items never speeds the polls up
        if (removed > 0 || (added > 0 && firstPage != null)) src.lastChangeAt = now;
        else if (added > 0 || src.lastChangeAt == 0) src.lastChangeAt = Math.max(src.lastChangeAt, newest(all, now));
        if (complete || capped) {
            src.completeAt = now;
            src.shortCrawl = false;
            if (page.count() > 1_000) s.shortLargeRun = 0;
        } else {
            src.shortCrawl = true;
            if (page.count() > 1_000 && ++s.shortLargeRun >= 3 && !s.backfillHeld) {
                s.backfillHeld = true;
                log.warn("inner-crawl: 3 short crawls in a row of sources over 1,000 items; backfill held (R32)");
                store.save();
            }
        }
        logJob("FULL", key, src, added, removed);
        return true;
    }

    /**
     * §6.4 poll: the first page (count + newest 100). Channels: gap fill while a page holds no known id, in-window
     * removals, then the count check (R22). Playlists: unchanged = titles refreshed; changed = listed again at once
     * (≤ 100 items: the poll page itself is the complete listing).
     */
    private boolean poll(String key, String name, InnerState.Source src, long now) throws IOException, ExtractionException {
        List<InnerCorpus.Item> corpus = corpus(key, src);
        if (corpus == null) return false;
        UploadsFetcher.Batch page = fetcher.first(key, src.hl != null ? src.hl : hl(name));
        if (page.listed() == 0 && !corpus.isEmpty()) {
            pacer.unavailable(key);
            return false;
        }
        if (key.startsWith("playlist:")) {
            List<String> top = ids(corpus.subList(0, Math.min(corpus.size(), page.items().size())));
            if (page.count() == src.count && ids(page.items()).equals(top)) {
                writeCorpus(key, name, mergeWindow(page.items(), corpus), corpus, false); // titles refreshed
                src.lastPollAt = now;
                logJob("POLL", key, src, 0, 0);
                return true;
            }
            return fullCrawl(key, name, src, now, page);
        }
        Set<String> known = new HashSet<>(ids(corpus));
        int cap = Math.min(store.state().capPerSource, InnerState.MAX_PER_SOURCE);
        List<InnerCorpus.Item> window = new ArrayList<>(page.items());
        int listed = page.listed(); // before R20, as fullCrawl counts: a members-only listing still reaches the cap
        org.schabi.newpipe.extractor.Page next = page.next();
        while (next != null && listed < cap && window.stream().noneMatch(i -> known.contains(i.id()))) {
            UploadsFetcher.Batch more = fetcher.next(key, src.hl != null ? src.hl : hl(name), next); // gap fill
            window.addAll(more.items());
            listed += more.listed();
            next = more.next();
        }
        window = mergeKeep(window, List.of());
        List<InnerCorpus.Item> merged = mergeWindow(window, corpus);
        int added = added(window, corpus), removed = corpus.size() + added - merged.size();
        writeCorpus(key, name, merged, corpus, false); // first: a failed write changes no state
        src.lastPollAt = now;
        if (added > 0 || removed > 0) src.lastChangeAt = now;
        src.count = page.count();
        boolean keepsMoreThanACrawlSweeps = src.capped && merged.size() > cap; // stopped at a lowered cap: never swept
        if (!keepsMoreThanACrawlSweeps && page.count() >= 0 && page.count() < merged.size() - tolerance(page.count())) {
            src.dirty = true; // removed outside the window: listed again in full (R22)
        }
        logJob("POLL", key, src, added, removed);
        return true;
    }

    /** R22: half the time since the source last changed, between 1 and 7 days. */
    static long pollInterval(InnerState.Source src, long now) {
        return Math.max(DAY_MS, Math.min(7 * DAY_MS, (now - src.lastChangeAt) / 2));
    }

    /**
     * §6.4: the window (newest first) replaces the stored items down to `o`, its oldest already-known id: the last
     * known one in the window's own order, never the deepest stored position (a resurfaced id would drop everything
     * above it). A known id stored above `o` that the window no longer lists is dropped; every item below `o` stays.
     */
    static List<InnerCorpus.Item> mergeWindow(List<InnerCorpus.Item> window, List<InnerCorpus.Item> stored) {
        Map<String, Integer> at = new HashMap<>();
        for (int i = 0; i < stored.size(); i++) at.putIfAbsent(stored.get(i).id(), i);
        int oldestKnown = -1;
        Set<String> inWindow = new HashSet<>();
        for (InnerCorpus.Item it : window) {
            inWindow.add(it.id());
            Integer p = at.get(it.id());
            if (p != null) oldestKnown = p;
        }
        if (oldestKnown < 0) return mergeKeep(window, stored); // no overlap (gap fill hit the cap): never drop
        List<InnerCorpus.Item> out = new ArrayList<>(window);
        for (int i = oldestKnown + 1; i < stored.size(); i++) {
            if (!inWindow.contains(stored.get(i).id())) out.add(stored.get(i));
        }
        return out;
    }

    static List<String> ids(List<InnerCorpus.Item> items) {
        return items.stream().map(InnerCorpus.Item::id).toList();
    }

    /**
     * The source's stored items. A missing or unreadable file of a source that was crawled resets that source, so it
     * is crawled again from scratch (§5, Review Focus 1), and this job stops (null).
     */
    private List<InnerCorpus.Item> corpus(String key, InnerState.Source src) {
        List<InnerCorpus.Item> items = InnerCorpus.read(store.corpusFile(key));
        if (items != null) return items;
        if (src.firstPageAt == 0) return new ArrayList<>();
        log.warn("inner-crawl source={}: corpus file missing or unreadable; crawling it again", key);
        src.firstPageAt = 0;
        src.completeAt = 0;
        src.shortCrawl = false;
        src.dirty = false;
        src.items = 0;
        return null;
    }

    /** Writes the source's corpus file (R15), cut to the R2 safety cap, then applies the same change to the inner index. */
    private void writeCorpus(String key, String name, List<InnerCorpus.Item> next, List<InnerCorpus.Item> before, boolean sweep) {
        List<InnerCorpus.Item> kept = next.size() > InnerState.MAX_PER_SOURCE ? next.subList(0, InnerState.MAX_PER_SOURCE) : next;
        try {
            InnerCorpus.write(store.corpusFile(key), kept);
        } catch (IOException e) {
            throw new UncheckedIOException("corpus write failed; the old corpus is kept", e);
        }
        try {
            if (sweep) index.replaceSource(key, name, kept);
            else index.merge(key, name, changed(before, kept), removed(before, kept));
        } catch (IOException | AlreadyClosedException e) { // §12 tragic IndexWriter: the job fails as on a full disk, never a parse signal
            rebuildRequested = true; // the next step rebuilds from the corpus: one attempt per failed job, so per pause
            throw new UncheckedIOException("inner-index write failed; the corpus is written; rebuilding the index from it",
                    new IOException(e));
        }
        source(key).items = kept.size();
    }

    /** Items that are new or differ in any field (title, duration, day, uploader). */
    static List<InnerCorpus.Item> changed(List<InnerCorpus.Item> before, List<InnerCorpus.Item> after) {
        Set<InnerCorpus.Item> old = new HashSet<>(before);
        return after.stream().filter(i -> !old.contains(i)).toList();
    }

    static Set<String> removed(List<InnerCorpus.Item> before, List<InnerCorpus.Item> after) {
        Set<String> kept = after.stream().map(InnerCorpus.Item::id).collect(Collectors.toSet());
        return before.stream().map(InnerCorpus.Item::id).filter(id -> !kept.contains(id)).collect(Collectors.toSet());
    }

    private void logJob(String job, String key, InnerState.Source src, int added, int removed) {
        log.info("inner-crawl job={} source={} req={} new={} removed={} count={} items={} hl={} complete={} ms={}", job, key,
                requestsSent(), added, removed, src.count, src.items, src.hl, src.completeAt > 0 && !src.shortCrawl,
                clock.millis() - jobStartedAt);
    }

    InnerState.Source source(String key) {
        return store.state().sources.computeIfAbsent(key, k -> new InnerState.Source());
    }

    /** R21: Arabic-script letters > Latin letters → ar; else more Dutch-only than English-only markers (NL, EN) → nl; else en. */
    // ponytail: three languages; per-language detection if the first pass shows a material share of other-language channels.
    static String hl(String text) {
        if (text == null) return "en";
        int arabic = 0, latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            Character.UnicodeScript script = Character.UnicodeScript.of(cp);
            if (script == Character.UnicodeScript.ARABIC) arabic++;
            else if (script == Character.UnicodeScript.LATIN) latin++;
        }
        if (arabic > latin) return "ar";
        int nl = 0, en = 0;
        // An apostrophe stays inside a word: Ja'far, Na‘at and Jaʼfar are one word each, never the markers ja and na.
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}'\u2018\u2019\u02BC]+")) {
            if (word.isEmpty()) continue;
            if (NL.contains(word)) nl++;
            if (EN.contains(word)) en++;
        }
        return nl > en ? "nl" : "en";
    }

    private static Set<String> markers(CharArraySet own, CharArraySet other, Set<String> blocked) {
        Set<String> out = new HashSet<>();
        for (Object word : own) {
            String w = new String((char[]) word);
            if (!other.contains(w) && !blocked.contains(w)) out.add(w);
        }
        return Set.copyOf(out);
    }

    static String titles(List<InnerCorpus.Item> items) {
        return items.stream().map(InnerCorpus.Item::t).filter(Objects::nonNull).collect(Collectors.joining(" "));
    }

    /** The newest item's approximate upload day as epoch ms, else `now` (R22's starting lastChangeAt). */
    static long newest(List<InnerCorpus.Item> items, long now) {
        return items.stream().map(InnerCorpus.Item::a).filter(Objects::nonNull).max(Integer::compare)
                .map(day -> day * DAY_MS).orElse(now);
    }

    static int added(List<InnerCorpus.Item> page, List<InnerCorpus.Item> stored) {
        Set<String> known = stored.stream().map(InnerCorpus.Item::id).collect(Collectors.toSet());
        return (int) page.stream().filter(i -> !known.contains(i.id())).count();
    }

    /** R32: listed items may fall short of the count by max(2, ⌈1 %⌉): unavailable items hidden, a deletion mid-crawl. */
    static long tolerance(long count) {
        return Math.max(2, (count + 99) / 100);
    }

    /** R32 "complete": listed ≥ count − tolerance; when the count is unknown (< 0, never 0), only a listing that reached its end. */
    static boolean complete(int listed, long count, boolean ended) {
        return count < 0 ? ended : listed >= count - tolerance(count);
    }

    /** The page's items first, then every stored item it does not list: adds and updates, never drops (R32 short, first pages). */
    static List<InnerCorpus.Item> mergeKeep(List<InnerCorpus.Item> page, List<InnerCorpus.Item> stored) {
        Map<String, InnerCorpus.Item> out = new LinkedHashMap<>();
        for (InnerCorpus.Item it : page) out.putIfAbsent(it.id(), it);
        for (InnerCorpus.Item it : stored) out.putIfAbsent(it.id(), it);
        return new ArrayList<>(out.values());
    }
}
