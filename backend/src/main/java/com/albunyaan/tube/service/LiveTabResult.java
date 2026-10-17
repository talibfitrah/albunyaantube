package com.albunyaan.tube.service;

import java.util.List;

/**
 * One Live-tab read (spec §5, D3). OK = items were found (lives may be empty: "items present, none LIVE");
 * TERMINATED = channel gone (an empty read with a reason); ERROR = an exception or no items after the continuations;
 * BREAKER = refused by the shared circuit breaker, never counted against the channel. {@code requests} counts the
 * browse requests that reached YouTube.
 */
public record LiveTabResult(Outcome outcome, List<LiveItem> lives, int requests, String error) {

    public enum Outcome { OK, TERMINATED, ERROR, BREAKER }

    public record LiveItem(String id, String title) {}

    public static LiveTabResult ok(List<LiveItem> lives, int requests) {
        return new LiveTabResult(Outcome.OK, List.copyOf(lives), requests, null);
    }

    public static LiveTabResult terminated(int requests) {
        return new LiveTabResult(Outcome.TERMINATED, List.of(), requests, null);
    }

    public static LiveTabResult error(int requests, String error) {
        return new LiveTabResult(Outcome.ERROR, List.of(), requests, error);
    }

    public static LiveTabResult breaker(int requests, String error) {
        return new LiveTabResult(Outcome.BREAKER, List.of(), requests, error);
    }

    /** The R10 "proven" signal: this read saw at least one LIVE item. */
    public boolean hasLive() {
        return outcome == Outcome.OK && !lives.isEmpty();
    }
}
