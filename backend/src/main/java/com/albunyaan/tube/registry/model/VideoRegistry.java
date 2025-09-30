package com.albunyaan.tube.registry.model;

import com.albunyaan.tube.category.Category;
import com.albunyaan.tube.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "video_registry")
public class VideoRegistry extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id")
    private ChannelRegistry channel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "playlist_id")
    private PlaylistRegistry playlist;

    @Column(name = "yt_video_id", nullable = false, unique = true, length = 64)
    private String ytVideoId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "video_category",
        joinColumns = @JoinColumn(name = "video_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<Category> categories = new LinkedHashSet<>();

    protected VideoRegistry() {
        // JPA
    }

    public VideoRegistry(ChannelRegistry channel, String ytVideoId) {
        this.channel = channel;
        this.ytVideoId = ytVideoId;
    }

    public UUID getId() {
        return id;
    }

    public ChannelRegistry getChannel() {
        return channel;
    }

    public PlaylistRegistry getPlaylist() {
        return playlist;
    }

    public String getYtVideoId() {
        return ytVideoId;
    }

    public Set<Category> getCategories() {
        return Collections.unmodifiableSet(categories);
    }

    public void setPlaylist(PlaylistRegistry playlist) {
        this.playlist = playlist;
    }

    public void updateCategories(Set<Category> categories) {
        this.categories.clear();
        if (categories != null && !categories.isEmpty()) {
            this.categories.addAll(categories);
        }
    }
}
