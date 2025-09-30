package com.albunyaan.tube.registry.model;

import com.albunyaan.tube.category.Category;
import com.albunyaan.tube.common.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
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
@Table(name = "playlist_registry")
public class PlaylistRegistry extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "channel_id")
    private ChannelRegistry channel;

    @Column(name = "yt_playlist_id", nullable = false, unique = true, length = 64)
    private String ytPlaylistId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "playlist_category",
        joinColumns = @JoinColumn(name = "playlist_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<Category> categories = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "playlist_excluded_video", joinColumns = @JoinColumn(name = "playlist_id"))
    @Column(name = "yt_video_id", length = 64, nullable = false)
    private Set<String> excludedVideoIds = new LinkedHashSet<>();

    protected PlaylistRegistry() {
        // JPA
    }

    public PlaylistRegistry(ChannelRegistry channel, String ytPlaylistId) {
        this.channel = channel;
        this.ytPlaylistId = ytPlaylistId;
    }

    public UUID getId() {
        return id;
    }

    public ChannelRegistry getChannel() {
        return channel;
    }

    public void setChannel(ChannelRegistry channel) {
        this.channel = channel;
    }

    public String getYtPlaylistId() {
        return ytPlaylistId;
    }

    public Set<Category> getCategories() {
        return Collections.unmodifiableSet(categories);
    }

    public Set<String> getExcludedVideoIds() {
        return Collections.unmodifiableSet(excludedVideoIds);
    }

    public void updateCategories(Set<Category> categories) {
        this.categories.clear();
        if (categories != null && !categories.isEmpty()) {
            this.categories.addAll(categories);
        }
    }

    public void replaceExcludedVideoIds(Set<String> ytVideoIds) {
        this.excludedVideoIds.clear();
        if (ytVideoIds != null && !ytVideoIds.isEmpty()) {
            this.excludedVideoIds.addAll(ytVideoIds);
        }
    }
}
