package com.albunyaan.tube.service;

import com.albunyaan.tube.model.LiveChannelState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec §5 apply(): one channel's next state from its previous state and this cycle's read. Pure, so U2 drives every
 * R10/R12/R15/D3 case without Firestore or YouTube; LiveChannelPoller performs the side effects it returns.
 */
public final class LivePollRules {

    static final long MINUTE_MS = 60_000L;
    /** D3, R7: 10 → 20 → 40 → 80 min. */
    static final long BASE_BACKOFF_MS = 10 * MINUTE_MS;
    /** R15: two proven misses AND at least 15 min since the first. */
    static final long MISS_MIN_MS = 15 * MINUTE_MS;
    static final int MISSES_TO_END = 2;
    static final int ERROR_MAX = 200;

    /** {@code ended}: ids to remove and archive now. {@code restored}: reappeared ids → the status to restore ("" = none). */
    public record Outcome(LiveChannelState next, List<String> ended, Map<String, String> restored) {}

    private LivePollRules() {
    }

    public static Outcome apply(LiveChannelState prev, String channelId, LiveTabResult read,
                                boolean proven, boolean hideAllowed, Set<String> links, long now) {
        LiveChannelState next = prev == null ? LiveChannelState.unknown(channelId) : prev.copy();
        // Fail-closed allow-list: only a real read may hide, count a miss or end an id. Exhaustive with no default,
        // so a new LiveTabResult outcome does not compile until it picks its row here.
        return switch (read.outcome()) {
            case OK, TERMINATED -> applyRead(next, read, proven, hideAllowed, links, now);
            case ERROR -> {
                next.setErrorCount(next.getErrorCount() + 1);
                if (next.getErrorSince() == null) {
                    next.setErrorSince(now);
                }
                String error = read.error() == null ? "" : read.error();
                next.setLastError(error.length() > ERROR_MAX ? error.substring(0, ERROR_MAX) : error);
                next.setNextPollAt(now + BASE_BACKOFF_MS * (1L << Math.min(next.getErrorCount() - 1, 3)));
                yield new Outcome(next, List.of(), Map.of());                    // D3: lives, status, misses unchanged
            }
            case RATE_LIMITED -> {
                // R7: YouTube pushed back, not this channel: no hide, miss or backoff. checkedAt advances so the
                // oldest-first order rotates past it and one channel cannot sit first and starve the rest.
                next.setCheckedAt(now);
                yield new Outcome(next, List.of(), Map.of());
            }
            case BREAKER -> new Outcome(next, List.of(), Map.of());              // never counted (RV M3)
        };
    }

    /** OK, or TERMINATED (an empty read with a reason, D3). */
    private static Outcome applyRead(LiveChannelState next, LiveTabResult read, boolean proven, boolean hideAllowed,
                                     Set<String> links, long now) {
        List<String> newIds = read.lives().stream().map(LiveTabResult.LiveItem::id).toList();
        Set<String> prevIds = next.getLives().stream().map(LiveChannelState.Live::getVideoId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        next.setErrorCount(0);
        next.setErrorSince(null);
        next.setLastError(null);
        next.setNextPollAt(null);
        next.setCheckedAt(now);
        next.setReason(read.outcome() == LiveTabResult.Outcome.TERMINATED ? LiveChannelState.TERMINATED : null);
        if (!newIds.isEmpty()) {
            next.setLives(read.lives().stream().map(l -> new LiveChannelState.Live(l.id(), l.title()))
                    .collect(Collectors.toCollection(ArrayList::new)));
            next.setStatus(LiveChannelState.LIVE);
            next.setOfflineSince(null);
        } else if (hideAllowed) {
            next.setLives(new ArrayList<>());
            next.setStatus(LiveChannelState.OFFLINE);
            if (next.getOfflineSince() == null) {
                next.setOfflineSince(now);
            }
        }                                                                         // else: suspect, keep (R10)

        Map<String, String> restored = new LinkedHashMap<>();
        for (String id : newIds) {
            next.getMisses().remove(id);
            String previous = next.getArchived().remove(id);
            if (previous != null) {
                restored.put(id, previous);
            }
        }

        List<String> ended = new ArrayList<>();
        if (!proven) {
            // R12c: a live that leaves `lives` unproven (the hour-late hide, or the seed's replacement) stays tracked at
            // count 0, which is not a miss (R10); proven cycles then count it like any departed live.
            Set<String> kept = next.getLives().stream().map(LiveChannelState.Live::getVideoId).collect(Collectors.toSet());
            prevIds.stream().filter(id -> !kept.contains(id))
                    .forEach(id -> next.getMisses().putIfAbsent(id, new LiveChannelState.Miss(0, now)));
        } else {
            // Ids already being counted keep counting after they leave `lives` (a replaced or hidden live), so a
            // departed stream ends on its second proven miss even when the next read no longer lists it.
            Set<String> tracked = new LinkedHashSet<>(prevIds);
            tracked.addAll(links);
            tracked.addAll(next.getMisses().keySet());
            tracked.removeAll(newIds);
            for (String id : tracked) {
                LiveChannelState.Miss miss = next.getMisses().computeIfAbsent(id, k -> new LiveChannelState.Miss(0, now));
                if (miss.getCount() == 0) {
                    miss.setSince(now);              // R15: the window opens at the first PROVEN miss, never unproven
                }
                miss.setCount(miss.getCount() + 1);
                if (miss.getCount() >= MISSES_TO_END && now - miss.getSince() >= MISS_MIN_MS) {
                    next.getMisses().remove(id);
                    ended.add(id);
                }
            }
        }
        return new Outcome(next, ended, restored);
    }
}
