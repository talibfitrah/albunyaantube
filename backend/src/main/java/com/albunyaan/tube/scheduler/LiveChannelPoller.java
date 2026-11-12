package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.search.CatalogChanged;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.LiveCategoryService;
import com.albunyaan.tube.service.LiveChannelService;
import com.albunyaan.tube.service.LivePollRules;
import com.albunyaan.tube.service.LiveTabResult;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spec §5: every 10 minutes, read the Live tab of each due channel behind a LIVE category (a daily sweep, a 4 h
 * recheck of channels read with no lives, plus R15 confirmations and suspect keeps), apply LivePollRules, persist, republish the snapshot, and evict caches only
 * when what is served changed (D14). An ended link is hidden and audited, never removed or archived (owner ruling
 * 2026-11-02). The public endpoint asks for an on-demand read after a stream rotation ({@link #refreshOnDemand}).
 * ponytail: no distributed lock, single instance; adopt the validators' Firestore lock if the backend scales out (D7).
 */
@Component
public class LiveChannelPoller implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(LiveChannelPoller.class);

    /**
     * Burst caps: at most 30 reads and 90 tokens (requests) in one cycle. CYCLE_BUDGET also caps each bucket's balance,
     * the on-demand one included, so neither can save up more than one cycle's burst.
     */
    static final int MAX_TARGETS = 30;
    static final int CYCLE_BUDGET = 90;
    static final long MINUTE_MS = 60_000L;
    static final long CYCLE_MS = 10 * MINUTE_MS;
    /** How much sooner an urgent target counts as tried; any other never waits more than this longer. */
    static final long URGENT_HEAD_START_MS = 60 * MINUTE_MS;
    static final long DAY_MS = 1440 * MINUTE_MS;
    /** An endpoint call reads its channel only if it was not read or tried this recently (one read per retry). */
    static final long ON_DEMAND_MIN_MS = 2 * MINUTE_MS;
    /** Cubic r8, r9: a heartbeat asks for a read ({@link #refreshStale}) only when the channel's last is older than this. */
    static final long STALE_ASK_MS = 30 * MINUTE_MS;
    /**
     * Cubic r2 #2, R10: reads are proven while some read, a cycle's or an on-demand one, listed a live this recently.
     * A LIVE channel is read only daily, so a small setup can go hours with no live read; once this lapses, a cycle's
     * first read re-reads the channel that listed a live last (the proof probe, Cubic r5 R5-1), at most once per window.
     * Reads that are empty after that probe is empty too are a real signal (a YouTube-wide empty answer), not a quiet
     * spell. The hide still waits {@link LivePollRules#HIDE_AFTER_MS} after it.
     */
    static final long PROVEN_WINDOW_MS = 120 * MINUTE_MS;

    private final LiveCategoryService liveCategories;
    private final VideoRepository videos;
    private final LiveChannelStateRepository states;
    private final LiveChannelService live;
    private final AuditLogService audit;
    private final PublicContentCacheService caches;
    private final YouTubeCircuitBreaker breaker;
    private final Clock clock;
    private final boolean enabled;
    /** The fixed daily request cap (the shared-IP allotment): 2/3 for the cycles, 1/3 for the on-demand reads. */
    private final int dailyRequests;
    private final Bucket cycleTokens;
    /** Only the on-demand thread touches it (spends under cycleLock, for requestsToday). */
    private final Bucket onDemandTokens;

    /**
     * The first read after {@link #PROVEN_WINDOW_MS} ran out with no live; null while proven. In memory only (spec §3):
     * a restart starts it over, which only delays an unproven hide. The proof it counts from (liveSeenAt) is persisted.
     */
    private Long unprovenSince;
    /** Cubic r5 R5-1: when the last proof probe was read. In memory: a restart may probe once more. */
    private Long lastProbeAt;
    private long requestDay = -1;
    private int requestsToday;
    /**
     * L2: when this process last read each channel. An ERROR leaves checkedAt alone, so attempts, not checks, order the
     * targets. Pruned to the current targets each cycle. ponytail: in memory; a restart orders by checkedAt until each
     * target is read once.
     */
    private final Map<String, Long> tried = new ConcurrentHashMap<>();
    /**
     * Cubic r2 #1: when a never-tried target was first seen. Its place in the queue is fixed from then on, so it ages
     * like a read one; a place relative to now would let channels coming due every 4 h starve it under the cap.
     */
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    /** On-demand reads in flight: one per channel. */
    private final Set<String> onDemandInFlight = ConcurrentHashMap.newKeySet();
    /** Off the request thread; one at a time, so a burst of calls never runs reads in parallel. Tests run it inline. */
    Executor onDemand = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "live-on-demand");
        t.setDaemon(true);
        return t;
    });
    /** The cycle's lock; the moderation refresh only tries it (stage 9 B13): a cycle's YouTube reads take minutes. */
    private final ReentrantLock cycleLock = new ReentrantLock();
    /** Stage 9 B13: a moderation write since the last view; the next tick republishes the view (Firestore only). */
    private final AtomicBoolean viewStale = new AtomicBoolean();

    public LiveChannelPoller(LiveCategoryService liveCategories, VideoRepository videos,
                             LiveChannelStateRepository states, LiveChannelService live,
                             AuditLogService audit, PublicContentCacheService caches,
                             YouTubeCircuitBreaker breaker, Clock clock,
                             @Value("${app.live-channels.poller.enabled:true}") boolean enabled,
                             @Value("${app.live-channels.poller.daily-requests:864}") int dailyRequests) {
        this.liveCategories = liveCategories;
        this.videos = videos;
        this.states = states;
        this.live = live;
        this.audit = audit;
        this.caches = caches;
        this.breaker = breaker;
        this.clock = clock;
        this.enabled = enabled;
        this.dailyRequests = Math.max(1, dailyRequests);
        this.cycleTokens = new Bucket(this.dailyRequests * 2 / 3.0);
        this.onDemandTokens = new Bucket(this.dailyRequests / 3.0);
    }

    @Scheduled(fixedDelay = 10, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void scheduledCycle() {
        if (!enabled) return;
        cycleLock.lock();         // Codex r6 C3: the time is taken once the lock is held, never before a wait on it
        try {
            cycle(clock.millis());
        } finally {
            cycleLock.unlock();
        }
    }

    /**
     * D1: serve the last known lives from Firestore before the first cycle (Review Focus 1). No YouTube traffic.
     * Runs while the singletons are instantiated: before Tomcat opens the port (finishRefresh) and before @Scheduled
     * tasks register (ContextRefreshedEvent), so no request renders, and no cycle publishes, before the load. The
     * caches are in-memory Caffeine (CacheConfig), empty at boot. Each read is bounded by the repositories' Firestore
     * timeouts; a failure logs and never blocks startup.
     */
    @Override
    public void afterSingletonsInstantiated() {
        try {
            live.loadStates(states.findAll());
            refreshView();
        } catch (Exception e) {
            log.warn("live poller: boot load failed, serving nothing until the first cycle: {}", e.getMessage());
        }
    }

    /** One cycle at {@code now}: a time taken under cycleLock ({@link #scheduledCycle}), or a test's fixed one. */
    public void cycle(long now) {
        cycleLock.lock();
        try {
            cycleLocked(now);
        } finally {
            cycleLock.unlock();
        }
    }

    private void cycleLocked(long now) {
        try {
            boolean open = breaker.isOpen();
            cycleTokens.accrue(now, open);
            closeDay(now);                                              // Before this cycle's reads
            if (!live.loaded()) live.loadStates(states.findAll());     // L1: a failed boot load retries before any write
            Map<String, List<LiveTabResult.LiveItem>> servedBefore = live.served();
            View view = view();
            if (open) {
                // L1: no YouTube request, so an open breaker (1-48 h) never freezes the view or the endpoint's 404s.
                // Lives unproven for LivePollRules.HOLD_MS hide meanwhile
                publish(view, Map.of(), false, view.targets(), servedBefore, now);
                log.info("live poller: circuit breaker open, YouTube reads skipped");
                return;
            }
            tried.keySet().retainAll(view.targets());                   // F4: a channel left in no LIVE category
            firstSeen.keySet().retainAll(view.targets());
            view.targets().forEach(uc -> {
                if (lastAttempt(uc) == null) firstSeen.putIfAbsent(uc, now); else firstSeen.remove(uc);
            });
            if (view.liveCategoryIds().isEmpty()) {
                refreshView(view);                                      // publishes nothing served; prunes ENDED (C4)
                return;
            }
            List<String> targets = view.targets().stream()
                    .filter(uc -> {                                   // L2: a backoff skips before the 30 cap
                        LiveChannelState s = live.state(uc);
                        return s == null || s.getNextPollAt() == null || s.getNextPollAt() <= now;
                    })
                    .sorted(Comparator.comparingLong((String uc) ->
                            lastTried(uc, now) - (urgent(uc) ? URGENT_HEAD_START_MS : 0)))
                    .limit(MAX_TARGETS)
                    .toList();
            targets = withProbe(view.targets(), targets, now);
            Map<String, LiveTabResult> fetched = new LinkedHashMap<>();
            for (String uc : targets) {
                if (cycleTokens.tokens < 1) break;                      // D16: the next cycle resumes here
                LiveTabResult r = live.livesNow(uc);
                spend(cycleTokens, uc, r, now);
                if (r.outcome() == LiveTabResult.Outcome.BREAKER) break; // D3: not counted
                fetched.put(uc, r);                             // a rate limit too: apply runs its HOLD_MS hold
                if (r.outcome() == LiveTabResult.Outcome.RATE_LIMITED) {
                    // R7: stop at the first rate limit; oldest-first then reads this channel last next cycle
                    log.warn("live poller: YouTube rate-limited {}; cycle at {} stopped ({})", uc,
                            Instant.ofEpochMilli(now), r.error());
                    break;
                }
            }

            boolean proven = proven(fetched.values(), now);
            if (!proven && fetched.entrySet().stream()
                    .anyMatch(e -> isRead(e.getValue()) && isLive(live.state(e.getKey())))) {
                log.error("live poller: no read listed a live for {} min; keeping the last lives (suspect since {})",
                        PROVEN_WINDOW_MS / MINUTE_MS, Instant.ofEpochMilli(unprovenSince));
            }
            // Every target left unread is held LivePollRules.HOLD_MS at most
            Set<String> unread = new LinkedHashSet<>(view.targets());
            unread.removeAll(fetched.keySet());
            publish(view, fetched, proven, unread, servedBefore, now);
        } catch (Exception e) {
            log.warn("live poller: cycle failed: {}", e.toString());
        }
    }

    /**
     * Cubic r2 #2, under cycleLock: R10's verdict on these reads, before they are applied. Proven when one lists a live,
     * or one did within {@link #PROVEN_WINDOW_MS} (liveSeenAt, persisted). Otherwise a real read (not an error, rate
     * limit or refusal) starts the unproven clock; no read leaves it. A proven verdict clears it.
     */
    private boolean proven(Collection<LiveTabResult> reads, long now) {
        Long seen = lastLiveSeen();
        boolean proven = reads.stream().anyMatch(LiveTabResult::hasLive) || (seen != null && now - seen < PROVEN_WINDOW_MS);
        if (proven) unprovenSince = null;
        else if (unprovenSince == null && reads.stream().anyMatch(LiveChannelPoller::isRead)) unprovenSince = now;
        live.setSuspectSince(unprovenSince);
        return proven;
    }

    /** The latest liveSeenAt of any channel (persisted), or null if no read ever listed a live. */
    private Long lastLiveSeen() {
        return live.snapshot().values().stream().map(LiveChannelState::getLiveSeenAt).filter(Objects::nonNull)
                .max(Long::compare).orElse(null);
    }

    /**
     * Cubic r5 R5-1, under cycleLock: the cycle's reads, led by a proof probe when they are about to be judged with the
     * proof window lapsed: a re-read of the target that listed a live last (not one in an ERROR backoff), on the cycle's
     * budget and within its cap. At most one per {@link #PROVEN_WINDOW_MS}; none if no read ever listed a live.
     */
    private List<String> withProbe(Set<String> all, List<String> due, long now) {
        Long seen = lastLiveSeen();
        if (due.isEmpty() || cycleTokens.tokens < 1 || seen == null || now - seen < PROVEN_WINDOW_MS
                || (lastProbeAt != null && now - lastProbeAt < PROVEN_WINDOW_MS)) return due;
        String probe = all.stream()
                .filter(uc -> {
                    LiveChannelState s = live.state(uc);
                    return s != null && s.getLiveSeenAt() != null
                            && (s.getErrorCount() == 0 || s.getNextPollAt() == null || s.getNextPollAt() <= now);
                })
                .max(Comparator.comparingLong(uc -> live.state(uc).getLiveSeenAt()))
                .orElse(null);
        if (probe == null) return due;
        lastProbeAt = now;
        List<String> reads = new ArrayList<>(List.of(probe));
        due.stream().filter(uc -> !uc.equals(probe)).limit(MAX_TARGETS - 1).forEach(reads::add);
        return reads;
    }

    /** Under cycleLock: apply the reads (D7), hold the unread, republish, evict on a served change (D14). */
    private void publish(View view, Map<String, LiveTabResult> fetched, boolean proven, Set<String> unread,
                         Map<String, List<LiveTabResult.LiveItem>> servedBefore, long now) throws Exception {
        boolean hideAllowed = proven || (unprovenSince != null && now - unprovenSince >= LivePollRules.HIDE_AFTER_MS);
        boolean changed = false;
        for (Map.Entry<String, LiveTabResult> e : fetched.entrySet()) {
            try {
                changed |= apply(e.getKey(), e.getValue(), proven, hideAllowed,
                        view.links().getOrDefault(e.getKey(), Set.of()), now);
            } catch (Exception ex) {
                log.warn("live poller: {} not applied this cycle: {}", e.getKey(), ex.toString());
            }
        }
        changed |= expireHolds(unread, now);
        try {
            refreshView(view);
        } finally {
            if (changed || !servedIds(servedBefore).equals(servedIds(live.served()))) caches.evictForLiveChange();
        }
    }

    /** Pays a read's real request count (a small debt is repaid later) and records the attempt (L2). Under cycleLock. */
    private void spend(Bucket bucket, String uc, LiveTabResult r, long now) {
        bucket.tokens -= r.requests();
        requestsToday += r.requests();
        if (r.outcome() != LiveTabResult.Outcome.BREAKER) tried.merge(uc, now, Math::max);   // D3: a refusal is no attempt
    }

    /**
     * Stage 9 B13: the poller's view was the only thing a moderation write left stale for 10-20 minutes. Every moderation
     * path but the exclusions evicts the public caches (a take-down, an un-approve, a channel leaving a LIVE category,
     * a category losing LIVE or deleted): that marks the view stale. Stage 9 B14: only while something is served (prod
     * has no LIVE category, and no write then changes what the endpoint serves; a refresh still re-reads every category
     * doc), or while a cycle runs: its view, read before this write, may publish the first entries. Cubic r4 R4-1: the
     * guard's check of an add counts as served ({@link LiveChannelService#servesAnything}), so the first link's write does too.
     * Cubic r5: the poller's own eviction (a cycle's or a refresh's served change, delivered synchronously on the thread
     * holding cycleLock) follows the view it just published, so it is no pending write: else a removal's 404 would wait a
     * second tick, and with no tick (or a failing one) the endpoints would answer 503 for good.
     */
    @EventListener
    public void onPublicContentEvicted(PublicContentCacheService.Evicted event) {
        live.clearVerdicts();                                                // Cubic r2 #3: before the next tick
        if (cycleLock.isHeldByCurrentThread()) return;
        if (cycleLock.isLocked() || live.servesAnything()) viewStale.set(true);
    }

    /**
     * Stage 9 B13: an exclusion writes only its channel doc, with no eviction: a write to a served channel's doc counts.
     * Any catalog write re-judges the endpoint's candidates (Cubic r2 #3).
     */
    @EventListener
    public void onCatalogChanged(CatalogChanged event) {
        live.clearVerdicts();                                                // e.g. a candidate's registry doc
        if ("CHANNEL".equals(event.type()) && live.entries().values().stream().anyMatch(c -> Objects.equals(event.docId(), c.getId()))) {
            viewStale.set(true);
        }
    }

    /**
     * Cubic r4 R4-1: a moderation refresh is pending or a cycle runs, so the published view may lack an entry or link the
     * apps already list (LiveCategoryService reads Firestore). The endpoints answer 503, not their definitive 404, meanwhile.
     */
    public boolean viewPending() {
        return viewStale.get() || cycleLock.isLocked();
    }

    @Scheduled(fixedDelay = 15, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void scheduledRefresh() {
        if (enabled) {
            refreshIfStale();
        }
    }

    /**
     * Stage 9 B13: republishes the view after a moderation write, as the cycle's own refresh does (Firestore only), and
     * evicts when what is served changed (D14). Never while a cycle runs: the tick skips and keeps the flag, so a cycle
     * that built its view before the write is followed by one more refresh.
     */
    public void refreshIfStale() {
        if (!viewStale.get() || !cycleLock.tryLock()) {
            return;
        }
        try {
            if (!viewStale.getAndSet(false)) return;
            Map<String, List<LiveTabResult.LiveItem>> before = live.served();
            refreshView(view());
            if (!servedIds(before).equals(servedIds(live.served()))) caches.evictForLiveChange();
        } catch (Exception e) {
            viewStale.set(true);                                           // the next tick tries again
            log.warn("live poller: moderation refresh failed: {}", e.toString());
        } finally {
            cycleLock.unlock();
        }
    }

    /** An in-memory elapsed-time token bucket; a read starts only with one token and may leave a small debt. */
    private static final class Bucket {
        private final double perDay;
        private double tokens;
        private Long accruedAt;

        Bucket(double perDay) {
            this.perDay = perDay;
        }

        /** One cycle's share at first (a restart never bursts), at most CYCLE_BUDGET, none while the breaker is open. */
        void accrue(long now, boolean open) {
            long elapsed = accruedAt == null ? CYCLE_MS : Math.max(0, now - accruedAt);
            if (!open) tokens = Math.min(CYCLE_BUDGET, tokens + perDay * elapsed / DAY_MS);
            accruedAt = accruedAt == null ? now : Math.max(accruedAt, now);
        }
    }

    /** A head start for an end being confirmed (R15) or a suspect keep (LIVE, no next poll); not links. */
    private boolean urgent(String uc) {
        LiveChannelState s = live.state(uc);
        return s != null && (LivePollRules.countingMisses(s) || (isLive(s) && s.getNextPollAt() == null));
    }

    /** D16 oldest first: the last attempt; never tried = a head start before it was first seen (Cubic r2 #1). */
    private long lastTried(String uc, long now) {
        Long last = lastAttempt(uc);
        return last == null ? firstSeen.getOrDefault(uc, now) - URGENT_HEAD_START_MS : last;
    }

    /** The later of the last check and this process's last read (L2), or null if neither. */
    private Long lastAttempt(String uc) {
        LiveChannelState s = live.state(uc);
        Long checked = s == null ? null : s.getCheckedAt();
        Long attempt = tried.get(uc);
        if (checked == null && attempt == null) return null;
        return Math.max(checked == null ? 0L : checked, attempt == null ? 0L : attempt);
    }

    /** Recompute the published entries and R11-filtered lives from Firestore (no YouTube traffic). */
    public void refreshView() {
        try {
            refreshView(view());
        } catch (Exception e) {
            log.warn("live poller: view refresh failed: {}", e.getMessage());
        }
    }

    private record View(Set<String> liveCategoryIds, Map<String, Channel> entries, Map<String, Set<String>> links,
                        Set<String> targets) {}

    private View view() throws Exception {
        Set<String> cats = liveCategories.liveCategoryIds();
        Map<String, Channel> entries = new LinkedHashMap<>();
        Map<String, Set<String>> links = new HashMap<>();
        for (String cat : cats) {
            LiveCategoryService.Members m = liveCategories.members(cat);
            for (Channel c : m.channels()) {
                if (LiveCategoryService.servable(c) && c.getYoutubeId() != null) entries.putIfAbsent(c.getYoutubeId(), c);
            }
            for (Video v : m.videos()) {
                if (LiveCategoryService.servable(v) && v.getChannelId() != null) {   // D13 / RV M12
                    links.computeIfAbsent(v.getChannelId(), k -> new LinkedHashSet<>()).add(v.getYoutubeId());
                }
            }
        }
        Set<String> targets = new LinkedHashSet<>(entries.keySet());
        targets.addAll(links.keySet());
        return new View(cats, entries, links, targets);
    }

    private void refreshView(View view) throws Exception {
        pruneEnded(view.links());
        Set<String> ids = new HashSet<>();
        view.entries().keySet().forEach(uc -> {
            LiveChannelState s = live.state(uc);
            if (isLive(s)) s.getLives().forEach(l -> ids.add(l.getVideoId()));
        });
        Map<String, Video> docs = ids.isEmpty() ? Map.of() : videos.findByYoutubeIds(ids);
        Map<String, List<LiveTabResult.LiveItem>> served = new HashMap<>();
        view.entries().forEach((uc, channel) -> {
            LiveChannelState s = live.state(uc);
            if (!isLive(s)) return;
            served.put(uc, LiveCategoryService.servedLives(channel, s, docs).stream()
                    .map(l -> new LiveTabResult.LiveItem(l.getVideoId(), l.getTitle())).toList());
        });
        Set<String> blocked = new HashSet<>();                          // Codex r6 C1: the endpoint's local denials
        docs.forEach((id, doc) -> {
            if (LiveCategoryService.wouldDraw410(doc)) blocked.add(id);
        });
        live.publish(view.entries(), served, view.links(), blocked);
    }

    /**
     * Codex r6 C4: before each view publish, every state's ENDED ids are cut to its channel's links in that view, so the
     * set never outgrows the category. Written only on a change; a failed write is logged and retried by the next view.
     */
    private void pruneEnded(Map<String, Set<String>> links) {
        if (!live.loaded()) return;
        for (String uc : live.snapshot().keySet()) {
            try {
                synchronized (live.stateLock()) {
                    LiveChannelState prev = live.state(uc);
                    Set<String> keep = links.getOrDefault(uc, Set.of());
                    if (prev == null || keep.containsAll(prev.getEnded())) continue;
                    LiveChannelState next = prev.copy();
                    next.getEnded().retainAll(keep);
                    live.put(next);
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("live poller: ended links of {} not pruned this time: {}", uc, e.toString());
            }
        }
    }

    /**
     * Channels no read reached this cycle hide lives unproven for {@link LivePollRules#HOLD_MS}
     * ({@link LivePollRules#expireHold}). Time only, no YouTube call. A failed write is logged; the next cycle retries.
     */
    private boolean expireHolds(Set<String> ucs, long now) {
        boolean changed = false;
        for (String uc : ucs) {
            try {
                synchronized (live.stateLock()) {
                    LiveChannelState prev = live.state(uc);
                    if (prev == null) continue;
                    LiveChannelState next = prev.copy();
                    if (!LivePollRules.expireHold(next, now)) continue;
                    live.put(next);
                    changed = true;
                }
            } catch (Exception e) {
                log.warn("live poller: {} not hidden this cycle: {}", uc, e.toString());
            }
        }
        return changed;
    }

    /** One channel: rules, persist + publish (D7), and the audit of each link R15 hid (owner ruling 2026-11-02). */
    private boolean apply(String uc, LiveTabResult read, boolean proven, boolean hideAllowed, Set<String> links, long now)
            throws Exception {
        LivePollRules.Outcome o;
        boolean changed;
        synchronized (live.stateLock()) {
            LiveChannelState prev = live.state(uc);
            o = LivePollRules.apply(prev, uc, read, proven, hideAllowed, links, now);
            changed = !Objects.equals(servedIds(prev), servedIds(o.next()))
                    || !Objects.equals(prev == null ? List.of() : prev.getEnded(), o.next().getEnded());
            live.put(o.next());
        }
        for (String id : o.ended()) {
            try {
                String docId = videos.findByYoutubeId(id).map(Video::getId).orElse(id);
                audit.logSystem("live_link_hidden_ended", "video", docId, "live-poller");
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("live poller: link {} on {} hidden as ended; audit not written: {}", id, uc, e.toString());
            }
        }
        return changed;
    }

    /**
     * A heartbeat's ask for a read of a channel whose last read may be behind: an ENDED link's (Cubic r6 K1 b, a stream
     * live again is shown again) and a judged candidate the snapshot lacks (the snapshot catches up). At most one per
     * {@link #STALE_ASK_MS} of the channel's last read (Cubic r8, r9): a read that just missed it would miss it again,
     * and its state write clears the verdict, so a watched stream would otherwise ask every {@link #ON_DEMAND_MIN_MS}
     * out of the shared on-demand budget.
     */
    public void refreshStale(String uc) {
        if (readWithin(uc, STALE_ASK_MS, clock.millis())) return;
        refreshOnDemand(uc, STALE_ASK_MS);
    }

    /** Read or tried within [ms] (Codex r5: a failed read counts too, not only checkedAt). */
    private boolean readWithin(String uc, long ms, long now) {
        Long last = lastAttempt(uc);
        return last != null && now - last < ms;
    }

    /**
     * An endpoint asks for one async read of a channel the last view polls, an entry or (Cubic r6 K1 b) a link's channel;
     * never an unlisted id (I8). One per channel.
     */
    public void refreshOnDemand(String uc) {
        refreshOnDemand(uc, 0);
    }

    /** [minAgeMs]: checked again when the read runs (Codex r6: a cycle can read the channel while it waits in the queue). */
    private void refreshOnDemand(String uc, long minAgeMs) {
        if (!enabled || !live.pollTarget(uc) || breaker.isOpen() || !onDemandInFlight.add(uc)) return;
        try {
            onDemand.execute(() -> {
                try {
                    readOnDemand(uc, minAgeMs);
                } finally {
                    onDemandInFlight.remove(uc);
                }
            });
        } catch (RejectedExecutionException e) {
            onDemandInFlight.remove(uc);
        }
    }

    /**
     * The YouTube read runs outside cycleLock (a cycle never waits on it); the rest under it, on a fresh view and at the
     * time the lock was taken. A cycle may read the channel meanwhile: its newer result stands, and this one is only
     * counted, so lives never flap and no timestamp moves back.
     */
    private void readOnDemand(String uc, long minAgeMs) {
        long start = clock.millis();
        boolean open = breaker.isOpen();
        onDemandTokens.accrue(start, open);
        if (open || cycleLock.isLocked() || !onDemandDue(uc, start) || readWithin(uc, minAgeMs, start)
                || onDemandTokens.tokens < 1) return;
        LiveTabResult r = live.livesNow(uc);
        cycleLock.lock();
        try {
            long now = clock.millis();
            closeDay(now);
            Long last = lastAttempt(uc);                   // before spend records this read
            spend(onDemandTokens, uc, r, now);
            if (r.outcome() == LiveTabResult.Outcome.BREAKER) return;
            if (r.outcome() == LiveTabResult.Outcome.RATE_LIMITED) {
                log.warn("live poller: YouTube rate-limited the on-demand read of {} ({})", uc, r.error());
            }
            if (last != null && last >= start) return;   // a cycle read it after this read began: that result stands
            View view = view();
            if (!view.targets().contains(uc)) return;                         // removed meanwhile
            publish(view, Map.of(uc, r), proven(List.of(r), now), Set.of(), live.served(), now);
        } catch (Exception e) {
            log.warn("live poller: on-demand read of {} failed: {}", uc, e.toString());
        } finally {
            cycleLock.unlock();
        }
    }

    /** Not read or tried in the last 2 min, and not inside an ERROR backoff (D3); the sweep and 4 h recheck do not apply. */
    private boolean onDemandDue(String uc, long now) {
        LiveChannelState s = live.state(uc);
        Long last = lastAttempt(uc);
        return (last == null || now - last >= ON_DEMAND_MIN_MS)
                && (s == null || s.getErrorCount() == 0 || s.getNextPollAt() == null || s.getNextPollAt() <= now);
    }

    /** Before any read: a new UTC day logs the last one's request count against the allotment. Never goes back a day. */
    private void closeDay(long now) {
        long day = now / DAY_MS;
        if (day <= requestDay) return;                    // a `now` taken before a lock wait across midnight
        if (requestDay >= 0) {
            String line = "live poller: " + requestsToday + " YouTube requests on " + LocalDate.ofEpochDay(requestDay)
                    + " (shared-IP allotment " + dailyRequests + "/day)";
            if (requestsToday > dailyRequests) log.warn(line); else log.info(line);
        }
        requestDay = day;
        requestsToday = 0;
    }

    /** A real Live-tab read (an empty one when the cycle is unproven), not an error, rate limit or refusal. */
    private static boolean isRead(LiveTabResult r) {
        return r.outcome() == LiveTabResult.Outcome.OK || r.outcome() == LiveTabResult.Outcome.TERMINATED;
    }

    private static boolean isLive(LiveChannelState s) {
        return s != null && LiveChannelState.LIVE.equals(s.getStatus());
    }

    private static List<String> servedIds(LiveChannelState s) {
        return isLive(s) ? s.getLives().stream().map(LiveChannelState.Live::getVideoId).toList() : List.of();
    }

    private static Map<String, List<String>> servedIds(Map<String, List<LiveTabResult.LiveItem>> served) {
        Map<String, List<String>> ids = new HashMap<>();
        served.forEach((uc, lives) -> ids.put(uc, lives.stream().map(LiveTabResult.LiveItem::id).toList()));
        return ids;
    }
}
