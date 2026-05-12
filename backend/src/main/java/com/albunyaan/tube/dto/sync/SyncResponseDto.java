package com.albunyaan.tube.dto.sync;

public class SyncResponseDto {
    private SyncPageDto<SubscriptionSyncDto> subscriptions;
    private SyncPageDto<PlaylistSyncDto> playlists;
    private SyncPageDto<FavoriteSyncDto> favorites;

    public SyncResponseDto() {}

    public SyncResponseDto(SyncPageDto<SubscriptionSyncDto> s,
                           SyncPageDto<PlaylistSyncDto> p,
                           SyncPageDto<FavoriteSyncDto> f) {
        this.subscriptions = s;
        this.playlists = p;
        this.favorites = f;
    }

    public SyncPageDto<SubscriptionSyncDto> getSubscriptions() {
        return subscriptions;
    }

    public void setSubscriptions(SyncPageDto<SubscriptionSyncDto> v) {
        this.subscriptions = v;
    }

    public SyncPageDto<PlaylistSyncDto> getPlaylists() {
        return playlists;
    }

    public void setPlaylists(SyncPageDto<PlaylistSyncDto> v) {
        this.playlists = v;
    }

    public SyncPageDto<FavoriteSyncDto> getFavorites() {
        return favorites;
    }

    public void setFavorites(SyncPageDto<FavoriteSyncDto> v) {
        this.favorites = v;
    }
}
