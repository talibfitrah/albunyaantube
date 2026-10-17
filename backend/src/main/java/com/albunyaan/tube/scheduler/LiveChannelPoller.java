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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.lang.Nullable;
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
 * served changed (D14). ponytail: no lock, single instance; adopt the validators' Firestore lock if the backend
 * scales out (D7).
 */
@Component
public class LiveChannelPoller {

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
    @Nullable private final YouTubeCircuitBreaker breaker;
    private final Clock clock;
    private final boolean enabled;

    /** In memory only (spec §3): a restart starts it over, which only delays an unproven hide. */
    private Long unprovenSince;
    private long requestDay = -1;
    private int requestsToday;

    public LiveChannelPoller(LiveCategoryService liveCategories, VideoRepository videos,
                             LiveChannelStateRepository states, LiveChannelService live, SortOrderService sortOrder,
                             AuditLogService audit, PublicContentCacheService caches,
                             @Nullable YouTubeCircuitBreaker breaker, Clock clock,
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

    /** D1: serve the last known lives from Firestore before the first cycle (Review Focus 1). No YouTube traffic. */
    @EventListener(ApplicationReadyEvent.class)
    public void loadAtBoot() {
        try {
            live.loadStates(states.findAll());
            refreshView();
        } catch (Exception e) {
            log.warn("live poller: boot load failed, serving nothing until the first cycle: {}", e.getMessage());
        }
    }

    public synchronized void cycle(long now) {
        if (breaker != null && breaker.isOpen()) {
            log.info("live poller: circuit breaker open, cycle skipped");
            return;
        }
        try {
            View view = view();
            if (view.liveCategoryIds().isEmpty()) {
                live.publish(Map.of(), Map.of());
                return;
            }
            Map<String, List<LiveTabResult.LiveItem>> servedBefore = live.served();
            List<String> targets = new ArrayList<>(view.targets());
            targets.sort(Comparator.comparingLong(uc -> {
                LiveChannelState s = live.state(uc);
                return s == null || s.getCheckedAt() == null ? 0L : s.getCheckedAt();
            }));
            int budget = CYCLE_BUDGET;
            int spent = 0;
            Map<String, LiveTabResult> fetched = new LinkedHashMap<>();
            for (String uc : targets.subList(0, Math.min(MAX_TARGETS, targets.size()))) {
                LiveChannelState s = live.state(uc);
                if (s != null && s.getNextPollAt() != null && s.getNextPollAt() > now) continue;
                if (budget < MAX_PER_CHANNEL) break;                    // D16: the next cycle resumes here
                LiveTabResult r = live.livesNow(uc);
                budget -= r.requests();
                spent += r.requests();
                if (r.outcome() == LiveTabResult.Outcome.BREAKER) break; // D3: not counted
                fetched.put(uc, r);                                     // a rate limit too: apply advances its checkedAt
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
            if (!proven && fetched.keySet().stream().anyMatch(uc -> isLive(live.state(uc)))) {
                log.error("live poller: no channel read LIVE this cycle; keeping the last lives (suspect since {})",
                        Instant.ofEpochMilli(unprovenSince));
            }
            boolean changed = false;
            for (Map.Entry<String, LiveTabResult> e : fetched.entrySet()) {
                changed |= apply(e.getKey(), e.getValue(), proven, hideAllowed,
                        view.links().getOrDefault(e.getKey(), Set.of()), view.liveCategoryIds(), now);
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
     * One channel: rules, restores, LIVE-category exits, persist + publish, then archives. True when what is served
     * changed. R12 write order: a restore saves its doc before the state drops the record, and an archive saves its
     * doc only after the state holding the record is persisted. A failed state write therefore archives nothing; the
     * id stays tracked in the snapshot and the next cycle ends it again.
     */
    private boolean apply(String uc, LiveTabResult read, boolean proven, boolean hideAllowed, Set<String> links,
                          Set<String> liveCategoryIds, long now) throws Exception {
        LiveChannelState prev = live.state(uc);
        LivePollRules.Outcome o = LivePollRules.apply(prev, uc, read, proven, hideAllowed, links, now);
        LiveChannelState next = o.next();
        boolean changed = !Objects.equals(servedIds(prev), servedIds(next));
        for (Map.Entry<String, String> r : o.restored().entrySet()) {
            changed |= restore(r.getKey(), r.getValue());
        }
        List<Video> toArchive = new ArrayList<>();
        for (String id : o.ended()) {
            changed |= end(id, next, liveCategoryIds, toArchive);
        }
        live.put(next);
        for (Video doc : toArchive) {
            doc.setValidationStatus(ValidationStatus.ARCHIVED);
            videos.save(doc);
            audit.logSystem("live_stream_archived_ended", "video", doc.getId(), "live-poller");
        }
        return changed || !toArchive.isEmpty();
    }

    /**
     * §5 end(): leave every LIVE category; when the doc is APPROVED and holds no other category, record its status to
     * restore and queue it for archiving (R12).
     */
    private boolean end(String youtubeId, LiveChannelState state, Set<String> liveCategoryIds, List<Video> toArchive)
            throws Exception {
        Video doc = videos.findByYoutubeId(youtubeId).orElse(null);
        if (doc == null) {
            return false;
        }
        boolean changed = false;
        List<String> held = doc.getCategoryIds() == null ? List.of() : new ArrayList<>(doc.getCategoryIds());
        for (String cat : held) {
            if (!liveCategoryIds.contains(cat)) continue;
            sortOrder.removeContentFromCategoryAndUpdate(cat, doc.getId(), "video");
            audit.logSystem("live_link_removed_ended", "video", doc.getId(), "live-poller");
            changed = true;
        }
        Video fresh = videos.findById(doc.getId()).orElse(doc);
        ValidationStatus vs = fresh.getValidationStatus();
        boolean uncategorised = fresh.getCategoryIds() == null || fresh.getCategoryIds().isEmpty();
        if ("APPROVED".equals(fresh.getStatus()) && uncategorised
                && vs != ValidationStatus.UNAVAILABLE && vs != ValidationStatus.ARCHIVED) {
            state.getArchived().put(youtubeId, vs == null ? "" : vs.name());
            toArchive.add(fresh);
        }
        return changed;
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
