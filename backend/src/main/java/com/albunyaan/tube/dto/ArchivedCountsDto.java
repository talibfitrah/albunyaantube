package com.albunyaan.tube.dto;

/**
 * Content Validation: Archived Counts DTO
 *
 * Returns the count of archived content by type.
 */
public class ArchivedCountsDto {

    private long channels;
    private long playlists;
    private long videos;
    private long total;

    public ArchivedCountsDto() {
    }

    public ArchivedCountsDto(long channels, long playlists, long videos) {
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
        this.total = channels + playlists + videos;
    }

    // Getters and Setters

    public long getChannels() {
        return channels;
    }

    public void setChannels(long channels) {
        this.channels = channels;
        recalculateTotal();
    }

    public long getPlaylists() {
        return playlists;
    }

    public void setPlaylists(long playlists) {
        this.playlists = playlists;
        recalculateTotal();
    }

    public long getVideos() {
        return videos;
    }

    public void setVideos(long videos) {
        this.videos = videos;
        recalculateTotal();
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    private void recalculateTotal() {
        this.total = this.channels + this.playlists + this.videos;
    }
}
