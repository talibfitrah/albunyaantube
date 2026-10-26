package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LiveChannelService.class);

    private record Memo(long at, LiveTabResult result) {}

    private final LiveTabReader reader;
    private final LiveChannelStateRepository states;
    private final Clock clock;
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();
    /** UC id -> when the guard last seeded it: every channel add, before its write lands. */
    private final Map<String, Long> added = new ConcurrentHashMap<>();
    private final Object stateLock = new Object();

    private volatile Map<String, LiveChannelState> snapshot = Map.of();
    private volatile Map<String, Channel> entries = Map.of();
    private volatile Map<String, List<LiveTabResult.LiveItem>> served = Map.of();
    private volatile Long suspectSince;
    /** L1: false until loadStates succeeds. A state computed from the empty snapshot would overwrite the stored one. */
    private volatile boolean loaded;
    /** False until a view built from the loaded states is published: before that entries() is empty, not the truth. */
    private volatile boolean published;

    public LiveChannelService(LiveTabReader reader, LiveChannelStateRepository states, Clock clock) {
        this.reader = reader;
        this.states = states;
        this.clock = clock;
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

    /** Spec §10: the migration polls fresh; skips the memo and refreshes it. */
    public LiveTabResult readFresh(String ucId) {
        memo.remove(ucId);
        return livesNow(ucId);
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
     * Restore records stay for the next cycle, which restores the docs (R12). Before the states load nothing is read or
     * seeded (L1, F3): the first cycle loads them and reads the channel. Stage 9 B14: a channel (re)entering a LIVE
     * category was not polled while out, so its stored lives may have ended days ago; a seed that says nothing or fails
     * drops them (UNKNOWN, hidden) and keeps its restore records.
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

    /**
     * {@link #seed} from a read the caller already made (spec §10: the migration's fresh poll). Sends no request. Before
     * the states load, put() refuses; the migration is blocked then (F2).
     */
    public void seedWith(String ucId, LiveTabResult r) {
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
                LivePollRules.Outcome o = LivePollRules.apply(next, ucId, r, false, entering, Set.of(), clock.millis());
                next = o.next();
                next.getArchived().putAll(o.restored());
            }
            try {
                put(next);
            } catch (ExecutionException | TimeoutException e) {
                log.warn("live seed: state write failed for {} ({}); kept in memory until the next cycle", ucId, e.getMessage());
                publishState(next);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                publishState(next);
            }
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
    }

    public synchronized void loadStates(Collection<LiveChannelState> saved) {
        Map<String, LiveChannelState> copy = new HashMap<>();
        saved.forEach(s -> copy.put(s.getChannelId(), s));
        snapshot = Map.copyOf(copy);
        loaded = true;
    }

    public boolean loaded() {
        return loaded;
    }

    /** The poller's view of the LIVE categories: the channel entries and their R11-filtered lives. */
    public void publish(Map<String, Channel> entries, Map<String, List<LiveTabResult.LiveItem>> served) {
        this.entries = Map.copyOf(entries);
        this.served = Map.copyOf(served);
        if (loaded) published = true;   // after the maps: a reader that sees true sees them
    }

    /** Stage 9 Cubic P2: the endpoint's 404 is definitive only once this is true, not merely once the states loaded. */
    public boolean published() {
        return published;
    }

    /**
     * Stage 9 Cubic r2 P2: seeded within {@link #ADDED_MS}. Only the poller's cycle (or the migration) republishes the
     * entries, so the public endpoint answers 503, not its definitive 404, for such a channel until a view lists it.
     * ponytail: a time window, not "until the first view built after the write"; two failed cycles in a row end it early.
     */
    public boolean recentlyAdded(String ucId) {
        Long at = added.get(ucId);
        return at != null && clock.millis() - at < ADDED_MS;
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
