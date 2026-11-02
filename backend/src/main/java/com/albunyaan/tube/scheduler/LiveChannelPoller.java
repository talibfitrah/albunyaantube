package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.ValidationStatus;
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
import com.albunyaan.tube.service.SortOrderService;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spec §5: every 10 minutes, read the Live tab of each distinct channel behind a LIVE category, apply
 * LivePollRules, remove/archive ended streams, persist, republish the snapshot, and evict caches only when what is
 * served changed (D14). ponytail: no distributed lock, single instance; adopt the validators' Firestore lock if the backend
 * scales out (D7).
 */
@Component
public class LiveChannelPoller implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(LiveChannelPoller.class);

    static final int MAX_TARGETS = 30;
    static final int CYCLE_BUDGET = 90;
    static final int MAX_PER_CHANNEL = 6;
    /** Owner rule: the shared IP carries the inner-search crawler (≤ 1,000/day) and this poller (864/day today). Log only. */
    static final int DAILY_ALLOTMENT = 864;
    static final long MINUTE_MS = 60_000L;
    static final long DAY_MS = 1440 * MINUTE_MS;

    private final LiveCategoryService liveCategories;
    private final VideoRepository videos;
    private final LiveChannelStateRepository states;
    private final LiveChannelService live;
    private final SortOrderService sortOrder;
    private final AuditLogService audit;
    private final PublicContentCacheService caches;
    private final YouTubeCircuitBreaker breaker;
    private final Clock clock;
    private final boolean enabled;

    /** In memory only (spec §3): a restart starts it over, which only delays an unproven hide. */
    private Long unprovenSince;
    private long requestDay = -1;
    private int requestsToday;
    /**
     * L2: when this process last read each channel. An ERROR leaves checkedAt alone, so attempts, not checks, order the
     * targets. Pruned to the current targets each cycle. ponytail: in memory; a restart orders by checkedAt until each
     * target is read once.
     */
    private final Map<String, Long> tried = new HashMap<>();
    /** The cycle's lock; the moderation refresh only tries it (stage 9 B13): a cycle's YouTube reads take minutes. */
    private final ReentrantLock cycleLock = new ReentrantLock();
    /** Stage 9 B13: a moderation write since the last view; the next tick republishes the view (Firestore only). */
    private final AtomicBoolean viewStale = new AtomicBoolean();

    public LiveChannelPoller(LiveCategoryService liveCategories, VideoRepository videos,
                             LiveChannelStateRepository states, LiveChannelService live, SortOrderService sortOrder,
                             AuditLogService audit, PublicContentCacheService caches,
                             YouTubeCircuitBreaker breaker, Clock clock,
                             @Value("${app.live-channels.poller.enabled:true}") boolean enabled) {
        this.liveCategories = liveCategories;
        this.videos = videos;
        this.states = states;
        this.live = live;
        this.sortOrder = sortOrder;
        this.audit = audit;
        this.caches = caches;
        this.breaker = breaker;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelay = 10, initialDelay = 1, timeUnit = TimeUnit.MINUTES)
    public void scheduledCycle() {
        if (enabled) {
            cycle(clock.millis());
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
            if (!live.loaded()) live.loadStates(states.findAll());     // L1: a failed boot load retries before any write
            Map<String, List<LiveTabResult.LiveItem>> servedBefore = live.served();
            View view = view();
            if (breaker.isOpen()) {
                // L1: no YouTube request, so an open breaker (1-48 h) never freezes the view or the endpoint's 404s.
                // Rev 9: lives no read has proven for an hour hide meanwhile; a read after the breaker closes serves them.
                boolean changed = expireHolds(view.targets(), now);
                try {
                    refreshView(view);
                } finally {
                    if (changed || !servedIds(servedBefore).equals(servedIds(live.served()))) caches.evictPublicContentCaches();
                }
                log.info("live poller: circuit breaker open, YouTube reads skipped");
                return;
            }
            tried.keySet().retainAll(view.targets());                   // F4: a channel left in no LIVE category
            if (view.liveCategoryIds().isEmpty()) {
                live.publish(Map.of(), Map.of());
                return;
            }
            List<String> targets = view.targets().stream()
                    .filter(uc -> {                                   // L2: a backoff skips before the 30 cap
                        LiveChannelState s = live.state(uc);
                        return s == null || s.getNextPollAt() == null || s.getNextPollAt() <= now;
                    })
                    .sorted(Comparator.comparingLong(this::lastTried))
                    .limit(MAX_TARGETS)
                    .toList();
            int budget = CYCLE_BUDGET;
            int spent = 0;
            Map<String, LiveTabResult> fetched = new LinkedHashMap<>();
            boolean blocked = false;                                    // Rev 9: YouTube refused or rate-limited a read
            for (String uc : targets) {
                if (budget < MAX_PER_CHANNEL) break;                    // D16: the next cycle resumes here
                LiveTabResult r = live.livesNow(uc);
                budget -= r.requests();
                spent += r.requests();
                if (r.outcome() == LiveTabResult.Outcome.BREAKER) {      // D3: not counted
                    blocked = true;
                    break;
                }
                tried.put(uc, now);
                fetched.put(uc, r);                             // a rate limit too: apply runs its Rev 9 hold
                if (r.outcome() == LiveTabResult.Outcome.RATE_LIMITED) {
                    blocked = true;
                    // R7: stop at the first rate limit; oldest-first then reads this channel last next cycle
                    log.warn("live poller: YouTube rate-limited {}; cycle at {} stopped ({})", uc,
                            Instant.ofEpochMilli(now), r.error());
                    break;
                }
            }
            count(spent, now);

            boolean proven = fetched.values().stream().anyMatch(LiveTabResult::hasLive);
            unprovenSince = proven ? null : (unprovenSince != null ? unprovenSince : now);
            boolean hideAllowed = proven || now - unprovenSince >= LivePollRules.HIDE_AFTER_MS;
            if (!proven && fetched.entrySet().stream()
                    .anyMatch(e -> isRead(e.getValue()) && isLive(live.state(e.getKey())))) {
                log.error("live poller: no channel read LIVE this cycle; keeping the last lives (suspect since {})",
                        Instant.ofEpochMilli(unprovenSince));
            }
            boolean changed = false;
            for (Map.Entry<String, LiveTabResult> e : fetched.entrySet()) {
                try {
                    changed |= apply(e.getKey(), e.getValue(), proven, hideAllowed,
                            view.links().getOrDefault(e.getKey(), Set.of()), view.liveCategoryIds(), now);
                } catch (Exception ex) {
                    changed = true;                                     // a restore may have landed before the failure
                    log.warn("live poller: {} not applied this cycle: {}", e.getKey(), ex.toString());
                }
            }
            if (blocked) {   // Rev 9 (Cubic rev9-r1 #1): a block only; the ceiling or the 30 cap alone never hides
                Set<String> unread = new LinkedHashSet<>(view.targets());
                unread.removeAll(fetched.keySet());
                changed |= expireHolds(unread, now);
            }
            live.setSuspectSince(unprovenSince);
            try {
                refreshView(view);
            } finally {   // stage 9 B21 (Cubic r9 #21): apply() already changed the snapshot, so a failed refresh evicts too
                if (changed || !servedIds(servedBefore).equals(servedIds(live.served()))) {
                    caches.evictPublicContentCaches();                 // D14: ids or visibility, never a title
                }
            }
        } catch (Exception e) {
            log.warn("live poller: cycle failed: {}", e.toString());
        }
    }

    /**
     * Stage 9 B13: the poller's view was the only thing a moderation write left stale for 10-20 minutes. Every moderation
     * path but the exclusions evicts the public caches (a take-down, an un-approve, a channel leaving a LIVE category,
     * a category losing LIVE or deleted): that marks the view stale. Stage 9 B14: only while something is served (prod
     * has no LIVE category, and no write then changes what the endpoint serves), or while a cycle runs: its view, read
     * before this write, may publish the first entries.
     */
    @EventListener
    public void onPublicContentEvicted(PublicContentCacheService.Evicted event) {
        if (cycleLock.isLocked() || !live.entries().isEmpty()) viewStale.set(true);
    }

    /** Stage 9 B13: an exclusion writes only its channel doc, with no eviction: a write to a served channel's doc counts. */
    @EventListener
    public void onCatalogChanged(CatalogChanged event) {
        if ("CHANNEL".equals(event.type()) && live.entries().values().stream().anyMatch(c -> Objects.equals(event.docId(), c.getId()))) {
            viewStale.set(true);
        }
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
            if (!servedIds(before).equals(servedIds(live.served()))) caches.evictPublicContentCaches();
        } catch (Exception e) {
            viewStale.set(true);                                           // the next tick tries again
            log.warn("live poller: moderation refresh failed: {}", e.toString());
        } finally {
            cycleLock.unlock();
        }
    }

    /** D16 oldest first: the later of the last check and this process's last read (L2). */
    private long lastTried(String uc) {
        LiveChannelState s = live.state(uc);
        long checked = s == null || s.getCheckedAt() == null ? 0L : s.getCheckedAt();
        return Math.max(checked, tried.getOrDefault(uc, 0L));
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
        live.publish(view.entries(), served);
    }

    /**
     * Rev 9 (owner, 2026-11-01): channels no read reached this cycle hide lives unproven for an hour
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

    private record Ending(Video doc, boolean archive) {}

    /**
     * One channel: rules and restores, then persist + publish, then the ended ids leave and archive. True when what is
     * served changed. Compute-and-put runs under the seed's monitor (D7); the YouTube read is already done. R12 order:
     * a restore saves its doc before the state drops the record, and nothing else changes for an ended id until the
     * state holding its restore record is written. A failed state write therefore changes no membership or doc; the id
     * stays tracked and the next cycle ends it again. A failure after the write is logged with the ids (see end()), and
     * so is a restored link's rejoin that a later failure loses (stage 9 B14: the next cycle finds it no longer ARCHIVED).
     */
    private boolean apply(String uc, LiveTabResult read, boolean proven, boolean hideAllowed, Set<String> links,
                          Set<String> liveCategoryIds, long now) throws Exception {
        boolean changed;
        List<Ending> endings = new ArrayList<>();
        List<String[]> rejoins = new ArrayList<>();                         // {LIVE category, doc id} (stage 9 B13)
        try {
            synchronized (live.stateLock()) {
                LiveChannelState prev = live.state(uc);
                LivePollRules.Outcome o = LivePollRules.apply(prev, uc, read, proven, hideAllowed, links, now);
                LiveChannelState next = o.next();
                changed = !Objects.equals(servedIds(prev), servedIds(next));
                for (Map.Entry<String, String> r : o.restored().entrySet()) {
                    List<String> from = next.getArchivedFrom().remove(r.getKey());
                    String docId = restore(r.getKey(), r.getValue());
                    if (docId == null) continue;
                    changed = true;
                    if (from != null) from.stream().filter(liveCategoryIds::contains).forEach(cat -> rejoins.add(new String[]{cat, docId}));
                }
                for (String id : o.ended()) {
                    Video doc = videos.findByYoutubeId(id).orElse(null);
                    if (doc == null) continue;
                    boolean archive = archivable(doc, liveCategoryIds);
                    if (archive) {
                        ValidationStatus vs = doc.getValidationStatus();
                        next.getArchived().put(id, vs == null ? "" : vs.name());
                        next.getArchivedFrom().put(id, List.copyOf(doc.getCategoryIds()));   // archivable: all LIVE ones
                    }
                    endings.add(new Ending(doc, archive));
                }
                live.put(next);
            }
        } catch (Exception e) {
            for (String[] r : rejoins) addByHand(r[1], r[0], e);
            throw e;
        }
        for (Ending e : endings) {
            changed |= end(e.doc(), e.archive(), liveCategoryIds);
        }
        for (String[] r : rejoins) {
            changed |= rejoin(r[0], r[1]);
        }
        return changed;
    }

    /**
     * §5 end(), after the state is written: leave every LIVE category, then archive when decided (R12). A failure is
     * logged with both ids: a link still in a LIVE category is tracked again and ends later, but a failed archive is
     * not retried, so an admin archives it by hand.
     */
    private boolean end(Video doc, boolean archive, Set<String> liveCategoryIds) {
        boolean changed = false;
        try {
            // every LIVE category, not just the doc's own: an order row can outlive the doc's id, and members() lists it
            // (the removal is idempotent). ponytail: a few LIVE categories at most, so no per-category membership read.
            for (String cat : liveCategoryIds) {
                sortOrder.removeContentFromCategoryAndUpdate(cat, doc.getId(), "video");
                if (doc.getCategoryIds() != null && doc.getCategoryIds().contains(cat)) {   // stage 9 B13: only where it was
                    audit.logSystem("live_link_removed_ended", "video", doc.getId(), "live-poller");
                }
                changed = true;
            }
            Video fresh = archive ? videos.findById(doc.getId()).orElse(null) : null;   // removal saved categoryIds
            if (fresh != null && archivableOnceRemoved(fresh, liveCategoryIds)) {
                fresh.setValidationStatus(ValidationStatus.ARCHIVED);
                videos.save(fresh);
                audit.logSystem("live_stream_archived_ended", "video", fresh.getId(), "live-poller");
                changed = true;
            }
        } catch (Exception e) {
            log.warn("live poller: ending video {} (doc {}) failed after its state was written; archive it by hand if "
                    + "it is still listed: {}", doc.getYoutubeId(), doc.getId(), e.toString());
            return true;                                                    // something may have landed
        }
        return changed;
    }

    /**
     * R12: APPROVED, in a LIVE category and in no other, and not already UNAVAILABLE or ARCHIVED. Stage 9 B13: a doc in
     * no LIVE category is never the poller's to archive (a departed live's own VOD: it never returns to be restored).
     */
    public static boolean archivable(Video doc, Set<String> liveCategoryIds) {
        return doc.getCategoryIds() != null && doc.getCategoryIds().stream().anyMatch(liveCategoryIds::contains)
                && archivableOnceRemoved(doc, liveCategoryIds);
    }

    /** R12 once the link has left its LIVE categories (end(), the migration's step 7): no other category came since. */
    public static boolean archivableOnceRemoved(Video doc, Set<String> liveCategoryIds) {
        ValidationStatus vs = doc.getValidationStatus();
        return "APPROVED".equals(doc.getStatus())
                && (doc.getCategoryIds() == null || liveCategoryIds.containsAll(doc.getCategoryIds()))
                && vs != ValidationStatus.UNAVAILABLE && vs != ValidationStatus.ARCHIVED;
    }

    /** R12: only a doc this poller archived, and only while it is still ARCHIVED (an admin's later change wins). Its id. */
    private String restore(String youtubeId, String previous) throws Exception {
        Video doc = videos.findByYoutubeId(youtubeId).orElse(null);
        if (doc == null || doc.getValidationStatus() != ValidationStatus.ARCHIVED) {
            return null;
        }
        doc.setValidationStatus(previous.isEmpty() ? null : ValidationStatus.valueOf(previous));
        videos.saveLiftingArchive(doc);                                     // B25: save() would keep the ARCHIVED
        audit.logSystem("live_stream_restored", "video", doc.getId(), "live-poller");
        return doc.getId();
    }

    /**
     * Stage 9 B13 (R12): a restored link goes back into the LIVE category it left, through the guarded add (the 30-entry
     * cap; live on its channel now, which this cycle's read just showed). A refusal or a failure leaves it out, logged.
     */
    private boolean rejoin(String categoryId, String docId) {
        try {
            sortOrder.addMultipleContentToCategory(categoryId, List.<String[]>of(new String[]{docId, "video"}));
            return true;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            addByHand(docId, categoryId, e);
            return false;
        }
    }

    private static void addByHand(String docId, String categoryId, Exception e) {
        log.warn("live poller: restored video doc {} not put back in live category {}; add it by hand: {}",
                docId, categoryId, e.toString());
    }

    private void count(int requests, long now) {
        long day = now / DAY_MS;
        if (day != requestDay) {
            if (requestDay >= 0) {
                String line = "live poller: " + requestsToday + " YouTube requests on " + LocalDate.ofEpochDay(requestDay)
                        + " (shared-IP allotment " + DAILY_ALLOTMENT + "/day)";
                if (requestsToday > DAILY_ALLOTMENT) log.warn(line); else log.info(line);
            }
            requestDay = day;
            requestsToday = 0;
        }
        requestsToday += requests;
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
