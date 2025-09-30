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
import jakarta.persistence.Table;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "channel_registry")
public class ChannelRegistry extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "yt_channel_id", nullable = false, unique = true, length = 64)
    private String ytChannelId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "channel_category",
        joinColumns = @JoinColumn(name = "channel_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    private Set<Category> categories = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "channel_excluded_video", joinColumns = @JoinColumn(name = "channel_id"))
    @Column(name = "yt_video_id", length = 64, nullable = false)
    private Set<String> excludedVideoIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "channel_excluded_playlist", joinColumns = @JoinColumn(name = "channel_id"))
    @Column(name = "yt_playlist_id", length = 64, nullable = false)
    private Set<String> excludedPlaylistIds = new LinkedHashSet<>();

    protected ChannelRegistry() {
        // JPA
    }

    public ChannelRegistry(String ytChannelId) {
        this.ytChannelId = ytChannelId;
    }

    public UUID getId() {
        return id;
    }

    public String getYtChannelId() {
        return ytChannelId;
    }

    public Set<Category> getCategories() {
        return Collections.unmodifiableSet(categories);
    }

    public Set<String> getExcludedVideoIds() {
        return Collections.unmodifiableSet(excludedVideoIds);
    }

    public Set<String> getExcludedPlaylistIds() {
        return Collections.unmodifiableSet(excludedPlaylistIds);
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

    public void replaceExcludedPlaylistIds(Set<String> ytPlaylistIds) {
        this.excludedPlaylistIds.clear();
        if (ytPlaylistIds != null && !ytPlaylistIds.isEmpty()) {
            this.excludedPlaylistIds.addAll(ytPlaylistIds);
        }
    }
}
