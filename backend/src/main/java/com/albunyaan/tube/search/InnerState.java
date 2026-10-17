package com.albunyaan.tube.search;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The inner-search crawler's persisted state, state.json (spec §5, R15, R26). A plain bag for Jackson. Rule: every
 * change to a collection here, and every save, holds this object's monitor; the volatile switches are read lock-free
 * by search threads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class InnerState {
    public enum Mode { OFF, FIRST_PASS, FULL }

    /** R2: the per-source safety cap. */
    public static final int MAX_PER_SOURCE = 10_000;

    public int v = 2;
    public volatile Mode mode = Mode.OFF;
    public volatile boolean serveInner;
    public volatile boolean streamGate;
    public volatile boolean backfillHeld;
    public volatile int capPerSource = MAX_PER_SOURCE;
    /** R32: short full crawls in a row of sources with count > 1,000; the third sets backfillHeld. */
    public int shortLargeRun;
    public Breaker breaker = new Breaker();
    public List<Signal> signals = new ArrayList<>();
    /** R37: UTC day -> thread-name prefix -> NewPipe requests; the last 14 days. */
    public TreeMap<String, TreeMap<String, Long>> requestsByDay = new TreeMap<>();
    public List<Memory> memory = new ArrayList<>();
    public ConcurrentHashMap<String, Source> sources = new ConcurrentHashMap<>();

    /** The crawler's own breaker (R12, R13): never the shared YouTubeCircuitBreaker. */
    public static class Breaker {
        public long intervalMs = 30_000; // CrawlPacer.FIRST_PASS_INTERVAL_MS
        public long pausedUntil;
        public long cleanRequestsAtInterval;
        public int transportPauses;
        public List<String> unavailableRun = new ArrayList<>();
    }

    public record Signal(long at, String type, String path, int status) {}

    public record Memory(long at, long availableMinusIndexMiB) {}

    /** One admitted channel's uploads or one admitted playlist (spec §6.3). */
    public static class Source {
        public String hl;
        public long count = -1;
        public int items;
        public boolean capped;
        public volatile boolean dirty;
        @JsonProperty("short")
        public volatile boolean shortCrawl;
        public long firstPageAt;
        public volatile long completeAt;
        public long lastPollAt;
        public long lastChangeAt;
        public int failures;
        public long retryAt;
        public boolean unavailable;
    }
}
