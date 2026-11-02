package com.albunyaan.tube.service;

import java.util.Collection;
import java.util.List;

/**
 * One Live-tab read (spec §5, D3, R7). OK = items were found (lives may be empty: "items present, none LIVE");
 * TERMINATED = channel gone (an empty read with a reason); ERROR = an exception or no items after the continuations;
 * RATE_LIMITED = YouTube pushed back (reCAPTCHA, 429, consent or bot-check redirect): not a hide or miss signal, the
 * poller stops its cycle; BREAKER = refused by the shared circuit breaker, never counted against the channel.
 * {@code requests} counts the gateway calls the read made (initial page plus continuations), not one the breaker
 * refused. {@code error} keeps the exception for the admin view.
 */
public record LiveTabResult(Outcome outcome, List<LiveItem> lives, int requests, String error) {

    public enum Outcome { OK, TERMINATED, ERROR, RATE_LIMITED, BREAKER }

    public record LiveItem(String id, String title) {}

    public static LiveTabResult ok(Collection<LiveItem> lives, int requests) {
        return new LiveTabResult(Outcome.OK, List.copyOf(lives), requests, null);
    }

    public static LiveTabResult terminated(int requests, String error) {
        return new LiveTabResult(Outcome.TERMINATED, List.of(), requests, error);
    }

    public static LiveTabResult error(int requests, String error) {
        return new LiveTabResult(Outcome.ERROR, List.of(), requests, error);
    }

    public static LiveTabResult rateLimited(int requests, String error) {
        return new LiveTabResult(Outcome.RATE_LIMITED, List.of(), requests, error);
    }

    public static LiveTabResult breaker(int requests, String error) {
        return new LiveTabResult(Outcome.BREAKER, List.of(), requests, error);
    }

    /** The R10 "proven" signal: this read saw at least one LIVE item. */
    public boolean hasLive() {
        return outcome == Outcome.OK && !lives.isEmpty();
    }
}
