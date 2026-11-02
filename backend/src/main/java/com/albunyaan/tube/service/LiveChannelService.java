package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * The live-channel snapshot (D1) and the single entry point to the Live tab (§5: poller, guard and seed share it).
 * Readers (expansion, the public endpoint, admin status) never touch Firestore through this class.
 * ponytail: no distributed lock, single instance; adopt the validators' Firestore lock if the backend scales out (D7).
 */
@Service
public class LiveChannelService {

    static final long MEMO_MS = 60_000L;
    /** Two poller cycles (10 min apart): a channel seeded this recently may be missing from the published entries. */
    static final long ADDED_MS = 20 * 60_000L;
    /**
     * Cubic r3 P2: the global budget for the public endpoint's candidate registry lookups (each also asks for an
     * on-demand read). Far above real use: a lookup runs only when the snapshot does not list the id and no verdict is
     * cached for that (channel, id), so a playing live costs about one per view, not one per 60 s heartbeat. Spent, the
     * endpoint answers 503 (the apps keep playing and ask again), so a flood of made-up ids can only degrade, never stop
     * playback or run up Firestore reads.
     */
    static final int CANDIDATE_LOOKUPS_PER_MINUTE = 120;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LiveChannelService.class);

    private record Memo(long at, LiveTabResult result) {}

    /** A verdict judged under one generation is never read under the next, so a judge racing a clear is not served. */
    private record Verdict(long generation, String channelId, String videoId) {}

    private final LiveTabReader reader;
    private final LiveChannelStateRepository states;
    private final Clock clock;
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();
    /** UC id -> when the guard last seeded it: every channel add, before its write lands. */
    private final Map<String, Long> added = new ConcurrentHashMap<>();
    private final Object stateLock = new Object();
    /**
     * Cubic r2 #3: the endpoint's verdicts on candidates the snapshot does not list (the apps' 60 s heartbeat), so each
     * (channel, video) costs one registry lookup and one on-demand ask per view, not per call. Cleared on every state or
     * view change and on moderation events; the TTL is only a safety net.
     */
    private final Cache<Verdict, Boolean> verdicts = Caffeine.newBuilder()
            .maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(10)).build();
    private final AtomicLong verdictGeneration = new AtomicLong();
    /** The lookup budget's token bucket: at most one minute's worth banked. Guarded by {@code this}. */
    private double lookupTokens = CANDIDATE_LOOKUPS_PER_MINUTE;
    private long lookupsAccruedAt;

    private volatile Map<String, LiveChannelState> snapshot = Map.of();
    private volatile Map<String, Channel> entries = Map.of();
    private volatile Map<String, List<LiveTabResult.LiveItem>> served = Map.of();
    /** Link video id -> its channel UC: the servable videos of the LIVE categories, from the poller's view. */
    private volatile Map<String, String> links = Map.of();
    /** Codex r6 C1: lives the last view's registry lookup found blocked (taken down, un-approved): a local denial. */
    private volatile Set<String> blocked = Set.of();
    private volatile Long suspectSince;
    /** When the guard last passed an add into a LIVE category ({@link #liveAddChecked}); null if never. */
    private volatile Long addCheckedAt;
    /** L1: false until loadStates succeeds. A state computed from the empty snapshot would overwrite the stored one. */
    private volatile boolean loaded;
    /** False until a view built from the loaded states is published: before that entries() is empty, not the truth. */
    private volatile boolean published;

    public LiveChannelService(LiveTabReader reader, LiveChannelStateRepository states, Clock clock) {
        this.reader = reader;
        this.states = states;
        this.clock = clock;
        this.lookupsAccruedAt = clock.millis();
    }

    /** The channel's current lives; OK and TERMINATED reads are reused for 60 s, at no request cost (D16). */
    public LiveTabResult livesNow(String ucId) {
        long now = clock.millis();
        Memo m = memo.get(ucId);
        if (m != null && now - m.at() < MEMO_MS) {
            LiveTabResult r = m.result();
            return new LiveTabResult(r.outcome(), r.lives(), 0, r.error());
        }
        LiveTabResult r = reader.read(ucId);
        if (r.outcome() == LiveTabResult.Outcome.OK || r.outcome() == LiveTabResult.Outcome.TERMINATED) {
            memo.put(ucId, new Memo(now, r));
        }
        return r;
    }

    /** D10: empty = no channel; an IOException = no signal (R7), see {@link LiveTabReader#resolveChannelId}. */
    public Optional<String> resolveChannelId(String videoId) throws IOException {
        return reader.resolveChannelId(videoId);
    }

    /**
     * D15: a CHANNEL add seeds its state with one read; a failed read seeds ERROR. The add is never refused for the
     * seed (pre-flight M9): a failed state write is logged and the state stays in the snapshot; the next cycle persists it.
     * A breaker refusal or a rate limit says nothing about the channel, so it seeds nothing (R7). An empty read hides
     * only a channel that is not served now: re-adding a served channel during a suspect stretch keeps its lives (R10).
     * Ended links stay ended unless the read lists them live. Before the states load nothing is read or
     * seeded (L1, F3): the first cycle loads them and reads the channel. Stage 9 B14: a channel (re)entering a LIVE
     * category was not polled while out, so its stored lives may have ended days ago; a seed that says nothing or fails
     * drops them (UNKNOWN, hidden) and keeps its ended links.
     */
    public void seed(String ucId) {
        long now = clock.millis();
        added.values().removeIf(at -> now - at >= ADDED_MS);
        added.put(ucId, now);
        if (!loaded) {
            log.warn("live seed: states not loaded yet; {} is read by the next cycle", ucId);
            return;
        }
        seedWith(ucId, livesNow(ucId));                         // the YouTube read stays outside the lock
    }

    /** {@link #seed} from its read. Sends no request. */
    private void seedWith(String ucId, LiveTabResult r) {
        boolean noSignal = r.outcome() == LiveTabResult.Outcome.BREAKER || r.outcome() == LiveTabResult.Outcome.RATE_LIMITED;
        synchronized (stateLock) {
            LiveChannelState next = state(ucId);
            boolean entering = !entries.containsKey(ucId);
            if (entering && next != null && !next.getLives().isEmpty() && (noSignal || r.outcome() == LiveTabResult.Outcome.ERROR)) {
                next = next.copy();                                     // stage 9 B14: unread since it re-entered
                next.setLives(new ArrayList<>());
                next.setStatus(LiveChannelState.UNKNOWN);
            } else if (noSignal) {
                return;
            }
            if (!noSignal) {
                next = LivePollRules.apply(next, ucId, r, false, entering, Set.of(), clock.millis()).next();
            }
            putOrKeep(next);
        }
    }

    /** Under stateLock: persist and publish; a failed write keeps it in the snapshot, and the channel's next apply persists it. */
    private void putOrKeep(LiveChannelState next) {
        try {
            put(next);
        } catch (ExecutionException | TimeoutException e) {
            log.warn("live state write failed for {} ({}); kept in memory", next.getChannelId(), e.getMessage());
            publishState(next);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            publishState(next);
        }
    }

    /** Cubic r6 K1 (c): the guard just saw this link live on its channel, so it leaves ENDED now, not at the next read. */
    public void liveAgain(String ucId, String videoId) {
        synchronized (stateLock) {
            LiveChannelState prev = state(ucId);
            if (prev == null || !prev.getEnded().contains(videoId)) return;
            LiveChannelState next = prev.copy();
            next.getEnded().remove(videoId);
            putOrKeep(next);
        }
    }

    /** D7: the seed and the poller's per-channel apply compute-and-put one at a time under this monitor. */
    public Object stateLock() {
        return stateLock;
    }

    /** Persist, then publish into the snapshot. Refused before the states load (L1). */
    public void put(LiveChannelState next) throws ExecutionException, InterruptedException, TimeoutException {
        if (!loaded) throw new IllegalStateException("live channel states not loaded yet");
        states.save(next);
        publishState(next);
    }

    private synchronized void publishState(LiveChannelState next) {
        Map<String, LiveChannelState> copy = new HashMap<>(snapshot);
        copy.put(next.getChannelId(), next);
        snapshot = Map.copyOf(copy);
        clearVerdicts();
    }

    public synchronized void loadStates(Collection<LiveChannelState> saved) {
        Map<String, LiveChannelState> copy = new HashMap<>();
        saved.forEach(s -> copy.put(s.getChannelId(), s));
        snapshot = Map.copyOf(copy);
        loaded = true;
        clearVerdicts();
    }

    public boolean loaded() {
        return loaded;
    }

    /**
     * The poller's view of the LIVE categories: the channel entries, their R11-filtered lives, the links by channel, and
     * the lives its registry lookup found blocked.
     */
    public void publish(Map<String, Channel> entries, Map<String, List<LiveTabResult.LiveItem>> served,
                        Map<String, Set<String>> links, Set<String> blocked) {
        Map<String, String> byId = new HashMap<>();
        links.forEach((uc, ids) -> ids.forEach(id -> byId.put(id, uc)));
        this.entries = Map.copyOf(entries);
        this.served = Map.copyOf(served);
        this.links = Map.copyOf(byId);
        this.blocked = Set.copyOf(blocked);
        if (loaded) published = true;   // after the maps: a reader that sees true sees them
        clearVerdicts();
    }

    /**
     * Cubic r2 #3: the cached verdict on a candidate of this channel, else {@code judge}'s (which may ask for an
     * on-demand read). Null is no verdict: the lookup failed, or (Cubic r3 P2) the lookup budget is spent and
     * {@code judge} is not called. Null is never cached. A cached verdict costs no budget. Two first calls may both judge.
     * Codex r6 C2: {@code judge} reads its inputs (the view, the states) itself, after the generation is taken; a view or
     * state published while it runs makes its verdict null too (503), so stale inputs are never answered or cached.
     */
    public Boolean candidateVerdict(String channelId, String videoId, Supplier<Boolean> judge) {
        long generation = verdictGeneration.get();
        Verdict key = new Verdict(generation, channelId, videoId);
        Boolean verdict = verdicts.getIfPresent(key);
        if (verdict != null) return verdict;
        if (!takeLookup()) return null;
        verdict = judge.get();
        if (verdict == null || verdictGeneration.get() != generation) return null;
        verdicts.put(key, verdict);
        return verdict;
    }

    private synchronized boolean takeLookup() {
        long now = clock.millis();
        long elapsed = Math.max(0, now - lookupsAccruedAt);
        lookupTokens = Math.min(CANDIDATE_LOOKUPS_PER_MINUTE, lookupTokens + CANDIDATE_LOOKUPS_PER_MINUTE * elapsed / 60_000.0);
        lookupsAccruedAt = Math.max(lookupsAccruedAt, now);
        if (lookupTokens < 1) return false;
        lookupTokens--;
        return true;
    }

    /** Every candidate is judged again: the states, the view or the catalog changed. */
    public void clearVerdicts() {
        verdictGeneration.incrementAndGet();
        verdicts.invalidateAll();
    }

    /** Stage 9 Cubic P2: the endpoint's 404 is definitive only once this is true, not merely once the states loaded. */
    public boolean published() {
        return published;
    }

    /**
     * Stage 9 Cubic r2 P2: seeded within {@link #ADDED_MS}. Only the poller's cycle republishes the
     * entries, so the public endpoint answers 503, not its definitive 404, for such a channel until a view lists it.
     * ponytail: a time window, not "until the first view built after the write"; two failed cycles in a row end it early.
     */
    public boolean recentlyAdded(String ucId) {
        Long at = added.get(ucId);
        return at != null && clock.millis() - at < ADDED_MS;
    }

    /**
     * Cubic r4 R4-1 (b): the guard passed an add into a LIVE category, before its write lands. With nothing served an
     * eviction marks no refresh (B14), so the first entry or link of an empty setup would wait for the next cycle.
     */
    public void liveAddChecked() {
        addCheckedAt = clock.millis();
    }

    /**
     * The channel of a link of a LIVE category in the last view, else null. In memory only. Cubic r6 K2: an ENDED link
     * is still one (ENDED is never a removal).
     */
    public String linkChannel(String videoId) {
        return links.get(videoId);
    }

    /** Codex r6 C1: the last view's registry lookup found this live blocked. In memory only. */
    public boolean blocked(String videoId) {
        return blocked.contains(videoId);
    }

    /** Cubic r6 K1 (b): a channel the poller reads, an entry or a link's channel, in the last view (never others, I8). */
    public boolean pollTarget(String ucId) {
        return entries.containsKey(ucId) || links.containsValue(ucId);
    }

    /**
     * True when the last view served a channel entry or a link, or the guard passed an add within {@link #ADDED_MS}:
     * only then can a moderation write change the answers (B14; R4-1 for the add, whose write lands after the check).
     */
    public boolean servesAnything() {
        Long at = addCheckedAt;
        return !entries.isEmpty() || !links.isEmpty() || (at != null && clock.millis() - at < ADDED_MS);
    }

    public LiveChannelState state(String ucId) {
        return snapshot.get(ucId);
    }

    public Map<String, LiveChannelState> snapshot() {
        return snapshot;
    }

    public Map<String, Channel> entries() {
        return entries;
    }

    public Map<String, List<LiveTabResult.LiveItem>> served() {
        return served;
    }

    public Long suspectSince() {
        return suspectSince;
    }

    public void setSuspectSince(Long suspectSince) {
        this.suspectSince = suspectSince;
    }
}
