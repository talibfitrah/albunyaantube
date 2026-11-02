package com.albunyaan.tube.model;

import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.IgnoreExtraProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spec §3: {@code live_channel_state/{UC id}}, one per polled channel. Times are epoch milliseconds. Served from
 * LiveChannelService's in-memory snapshot (D1); written by the poller and the add-time seed (D15).
 */
@IgnoreExtraProperties   // docs the old model wrote carry archived/archivedFrom (empty maps in prod)
public class LiveChannelState {

    public static final String UNKNOWN = "UNKNOWN";
    public static final String LIVE = "LIVE";
    public static final String OFFLINE = "OFFLINE";
    public static final String TERMINATED = "TERMINATED";

    @DocumentId
    private String channelId;
    private String status = UNKNOWN;
    private String reason;
    private List<Live> lives = new ArrayList<>();
    private Long checkedAt;
    /**
     * Cubic r2 #2: the last read that listed a live here. The latest across channels is R10's proof that YouTube answers
     * normally; persisted so a restart keeps it. Docs written before it load null (no proof yet).
     */
    private Long liveSeenAt;
    private Long offlineSince;
    private int errorCount;
    private Long errorSince;
    private String lastError;
    private Long nextPollAt;
    private Map<String, Miss> misses = new HashMap<>();
    /**
     * Owner ruling 2026-11-02: links R15 ended, hidden from the category list until a read lists them live (admin status
     * ENDED). Codex r6 C4: pruned to the channel's current links on every view publish.
     */
    private List<String> ended = new ArrayList<>();
    private Long updatedAt;

    public LiveChannelState() {
    }

    public static LiveChannelState unknown(String channelId) {
        LiveChannelState s = new LiveChannelState();
        s.channelId = channelId;
        return s;
    }

    public LiveChannelState copy() {
        LiveChannelState c = new LiveChannelState();
        c.channelId = channelId;
        c.status = status;
        c.reason = reason;
        c.lives = new ArrayList<>();
        lives.forEach(l -> c.lives.add(new Live(l.videoId, l.title)));
        c.checkedAt = checkedAt;
        c.liveSeenAt = liveSeenAt;
        c.offlineSince = offlineSince;
        c.errorCount = errorCount;
        c.errorSince = errorSince;
        c.lastError = lastError;
        c.nextPollAt = nextPollAt;
        c.misses = new HashMap<>();
        misses.forEach((k, v) -> c.misses.put(k, new Miss(v.count, v.since)));
        c.ended = new ArrayList<>(ended);
        c.updatedAt = updatedAt;
        return c;
    }

    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public List<Live> getLives() { return lives; }
    public void setLives(List<Live> lives) { this.lives = lives == null ? new ArrayList<>() : lives; }
    public Long getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Long checkedAt) { this.checkedAt = checkedAt; }
    public Long getLiveSeenAt() { return liveSeenAt; }
    public void setLiveSeenAt(Long liveSeenAt) { this.liveSeenAt = liveSeenAt; }
    public Long getOfflineSince() { return offlineSince; }
    public void setOfflineSince(Long offlineSince) { this.offlineSince = offlineSince; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int errorCount) { this.errorCount = errorCount; }
    public Long getErrorSince() { return errorSince; }
    public void setErrorSince(Long errorSince) { this.errorSince = errorSince; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Long getNextPollAt() { return nextPollAt; }
    public void setNextPollAt(Long nextPollAt) { this.nextPollAt = nextPollAt; }
    public Map<String, Miss> getMisses() { return misses; }
    public void setMisses(Map<String, Miss> misses) { this.misses = misses == null ? new HashMap<>() : misses; }
    public List<String> getEnded() { return ended; }
    public void setEnded(List<String> ended) { this.ended = ended == null ? new ArrayList<>() : ended; }
    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public static class Live {
        private String videoId;
        private String title;

        public Live() {
        }

        public Live(String videoId, String title) {
            this.videoId = videoId;
            this.title = title;
        }

        public String getVideoId() { return videoId; }
        public void setVideoId(String videoId) { this.videoId = videoId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }

    public static class Miss {
        private int count;
        private long since;

        public Miss() {
        }

        public Miss(int count, long since) {
            this.count = count;
            this.since = since;
        }

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }
        public long getSince() { return since; }
        public void setSince(long since) { this.since = since; }
    }
}
