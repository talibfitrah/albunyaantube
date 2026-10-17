package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.LiveChannelState;
import com.albunyaan.tube.repository.LiveChannelStateRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Clock;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LiveChannelService.class);

    private record Memo(long at, LiveTabResult result) {}

    private final LiveTabReader reader;
    private final LiveChannelStateRepository states;
    private final Clock clock;
    private final Map<String, Memo> memo = new ConcurrentHashMap<>();
    private final Object stateLock = new Object();

    private volatile Map<String, LiveChannelState> snapshot = Map.of();
    private volatile Map<String, Channel> entries = Map.of();
    private volatile Map<String, List<LiveTabResult.LiveItem>> served = Map.of();
    private volatile Long suspectSince;

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
     * Restore records stay for the next cycle, which restores the docs (R12).
     */
    public void seed(String ucId) {
        LiveTabResult r = livesNow(ucId);                       // the YouTube read stays outside the lock
        if (r.outcome() == LiveTabResult.Outcome.BREAKER || r.outcome() == LiveTabResult.Outcome.RATE_LIMITED) {
            return;
        }
        synchronized (stateLock) {
            LivePollRules.Outcome o = LivePollRules.apply(state(ucId), ucId, r, false, !entries.containsKey(ucId),
                    Set.of(), clock.millis());
            LiveChannelState next = o.next();
            next.getArchived().putAll(o.restored());
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

    /** Persist, then publish into the snapshot. */
    public void put(LiveChannelState next) throws ExecutionException, InterruptedException, TimeoutException {
        states.save(next);
        publishState(next);
    }

    private synchronized void publishState(LiveChannelState next) {
        Map<String, LiveChannelState> copy = new HashMap<>(snapshot);
        copy.put(next.getChannelId(), next);
        snapshot = Map.copyOf(copy);
    }

    public synchronized void loadStates(Collection<LiveChannelState> loaded) {
        Map<String, LiveChannelState> copy = new HashMap<>();
        loaded.forEach(s -> copy.put(s.getChannelId(), s));
        snapshot = Map.copyOf(copy);
    }

    /** The poller's view of the LIVE categories: the channel entries and their R11-filtered lives. */
    public void publish(Map<String, Channel> entries, Map<String, List<LiveTabResult.LiveItem>> served) {
        this.entries = Map.copyOf(entries);
        this.served = Map.copyOf(served);
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
