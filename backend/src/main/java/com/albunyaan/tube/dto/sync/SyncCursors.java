package com.albunyaan.tube.dto.sync;

public class SyncCursors {
    private long subscriptions;
    private long playlists;
    private long favorites;

    public SyncCursors() {}
    public SyncCursors(long s, long p, long f) {
        this.subscriptions = s; this.playlists = p; this.favorites = f;
    }
    public long getSubscriptions()          { return subscriptions; }
    public void setSubscriptions(long v)    { this.subscriptions = v; }
    public long getPlaylists()              { return playlists; }
    public void setPlaylists(long v)        { this.playlists = v; }
    public long getFavorites()              { return favorites; }
    public void setFavorites(long v)        { this.favorites = v; }
}
