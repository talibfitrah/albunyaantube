package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.albunyaan.tube.repository.VideoRepository;
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
    static final long HIDE_AFTER_UNPROVEN_MS = 60 * MINUTE_MS;

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

    public synchronized void cycle(long now) {
        try {
            if (!live.loaded()) live.loadStates(states.findAll());     // L1: a failed boot load retries before any write
            Map<String, List<LiveTabResult.LiveItem>> servedBefore = live.served();
            View view = view();
            if (breaker.isOpen()) {
                // L1: no YouTube request, so an open breaker (1-48 h) never freezes the view or the endpoint's 404s
                refreshView(view);
                if (!servedIds(servedBefore).equals(servedIds(live.served()))) caches.evictPublicContentCaches();
                log.info("live poller: circuit breaker open, cycle skipped");
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
            for (String uc : targets) {
                if (budget < MAX_PER_CHANNEL) break;                    // D16: the next cycle resumes here
                LiveTabResult r = live.livesNow(uc);
                budget -= r.requests();
                spent += r.requests();
                if (r.outcome() == LiveTabResult.Outcome.BREAKER) break; // D3: not counted
                tried.put(uc, now);
                fetched.put(uc, r);                             // a rate limit too: apply advances its checkedAt
                if (r.outcome() == LiveTabResult.Outcome.RATE_LIMITED) {
                    // R7: stop at the first rate limit; oldest-first then reads this channel last next cycle
                    log.warn("live poller: YouTube rate-limited {}; cycle at {} stopped ({})", uc,
                            Instant.ofEpochMilli(now), r.error());
                    break;
                }
            }
            count(spent, now);

            boolean proven = fetched.values().stream().anyMatch(LiveTabResult::hasLive);
            unprovenSince = proven ? null : (unprovenSince != null ? unprovenSince : now);
            boolean hideAllowed = proven || now - unprovenSince >= HIDE_AFTER_UNPROVEN_MS;
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
            live.setSuspectSince(unprovenSince);
            refreshView(view);
            if (changed || !servedIds(servedBefore).equals(servedIds(live.served()))) {
                caches.evictPublicContentCaches();                     // D14: ids or visibility, never a title
            }
        } catch (Exception e) {
            log.warn("live poller: cycle failed: {}", e.toString());
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

    private record Ending(Video doc, boolean archive) {}

    /**
     * One channel: rules and restores, then persist + publish, then the ended ids leave and archive. True when what is
     * served changed. Compute-and-put runs under the seed's monitor (D7); the YouTube read is already done. R12 order:
     * a restore saves its doc before the state drops the record, and nothing else changes for an ended id until the
     * state holding its restore record is written. A failed state write therefore changes no membership or doc; the id
     * stays tracked and the next cycle ends it again. A failure after the write is logged with the ids (see end()).
     */
    private boolean apply(String uc, LiveTabResult read, boolean proven, boolean hideAllowed, Set<String> links,
                          Set<String> liveCategoryIds, long now) throws Exception {
        boolean changed;
        List<Ending> endings = new ArrayList<>();
        synchronized (live.stateLock()) {
            LiveChannelState prev = live.state(uc);
            LivePollRules.Outcome o = LivePollRules.apply(prev, uc, read, proven, hideAllowed, links, now);
            LiveChannelState next = o.next();
            changed = !Objects.equals(servedIds(prev), servedIds(next));
            for (Map.Entry<String, String> r : o.restored().entrySet()) {
                changed |= restore(r.getKey(), r.getValue());
            }
            for (String id : o.ended()) {
                Video doc = videos.findByYoutubeId(id).orElse(null);
                if (doc == null) continue;
                boolean archive = archivable(doc, liveCategoryIds);
                if (archive) {
                    ValidationStatus vs = doc.getValidationStatus();
                    next.getArchived().put(id, vs == null ? "" : vs.name());
                }
                endings.add(new Ending(doc, archive));
            }
            live.put(next);
        }
        for (Ending e : endings) {
            changed |= end(e.doc(), e.archive(), liveCategoryIds);
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
            for (String cat : doc.getCategoryIds() == null ? List.<String>of() : List.copyOf(doc.getCategoryIds())) {
                if (!liveCategoryIds.contains(cat)) continue;
                sortOrder.removeContentFromCategoryAndUpdate(cat, doc.getId(), "video");
                audit.logSystem("live_link_removed_ended", "video", doc.getId(), "live-poller");
                changed = true;
            }
            Video fresh = archive ? videos.findById(doc.getId()).orElse(null) : null;   // removal saved categoryIds
            if (fresh != null && archivable(fresh, liveCategoryIds)) {
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

    /** R12: APPROVED, no category outside the LIVE ones, and not already UNAVAILABLE or ARCHIVED. */
    public static boolean archivable(Video doc, Set<String> liveCategoryIds) {
        ValidationStatus vs = doc.getValidationStatus();
        return "APPROVED".equals(doc.getStatus())
                && (doc.getCategoryIds() == null || liveCategoryIds.containsAll(doc.getCategoryIds()))
                && vs != ValidationStatus.UNAVAILABLE && vs != ValidationStatus.ARCHIVED;
    }

    /** R12: only a doc this poller archived, and only while it is still ARCHIVED (an admin's later change wins). */
    private boolean restore(String youtubeId, String previous) throws Exception {
        Video doc = videos.findByYoutubeId(youtubeId).orElse(null);
        if (doc == null || doc.getValidationStatus() != ValidationStatus.ARCHIVED) {
            return false;
        }
        doc.setValidationStatus(previous.isEmpty() ? null : ValidationStatus.valueOf(previous));
        videos.save(doc);
        audit.logSystem("live_stream_restored", "video", doc.getId(), "live-poller");
        return true;
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
