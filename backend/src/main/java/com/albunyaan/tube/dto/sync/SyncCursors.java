package com.albunyaan.tube.dto.sync;

public class SyncCursors {
    private long subscriptions;
    private long playlists;
    private long favorites;
    // Compound-cursor tiebreakers (optional, paired with the long timestamp
    // above). When set, SyncRepository uses startAfter(ts, id) so rows tied
    // on the same millisecond don't silently drop (cubic R3/R4 P1).
    private String subscriptionsId;
    private String playlistsId;
    private String favoritesId;

    public SyncCursors() {}
    public SyncCursors(long s, long p, long f) {
        this(s, null, p, null, f, null);
    }
    public SyncCursors(long s, String sId, long p, String pId, long f, String fId) {
        this.subscriptions = s;   this.subscriptionsId = sId;
        this.playlists = p;       this.playlistsId = pId;
        this.favorites = f;       this.favoritesId = fId;
    }
    public long getSubscriptions()          { return subscriptions; }
    public void setSubscriptions(long v)    { this.subscriptions = v; }
    public long getPlaylists()              { return playlists; }
    public void setPlaylists(long v)        { this.playlists = v; }
    public long getFavorites()              { return favorites; }
    public void setFavorites(long v)        { this.favorites = v; }
    public String getSubscriptionsId()        { return subscriptionsId; }
    public void setSubscriptionsId(String v)  { this.subscriptionsId = v; }
    public String getPlaylistsId()            { return playlistsId; }
    public void setPlaylistsId(String v)      { this.playlistsId = v; }
    public String getFavoritesId()            { return favoritesId; }
    public void setFavoritesId(String v)      { this.favoritesId = v; }
}
