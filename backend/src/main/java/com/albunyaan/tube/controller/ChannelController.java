package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.repository.ChannelRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutionException;

@RestController
@RequestMapping("/api/admin/channels")
public class ChannelController {

    private final ChannelRepository channelRepository;
    private final com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache;

    public ChannelController(
            ChannelRepository channelRepository,
            com.github.benmanes.caffeine.cache.Cache<String, Object> workspaceExclusionsCache
    ) {
        this.channelRepository = channelRepository;
        this.workspaceExclusionsCache = workspaceExclusionsCache;
    }

    /**
     * Update channel exclusions in bulk.
     */
    @PutMapping("/{id}/exclusions")
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = CacheConfig.CACHE_CHANNELS, allEntries = true)
    public ResponseEntity<Channel> updateExclusions(
            @PathVariable String id,
            @RequestBody Channel.ExcludedItems excludedItems
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        channel.setExcludedItems(excludedItems);
        Channel updated = channelRepository.save(channel);
        workspaceExclusionsCache.invalidateAll();
        return ResponseEntity.ok(updated);
    }

    /**
     * Get channel exclusions.
     */
    @GetMapping("/{id}/exclusions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Channel.ExcludedItems> getExclusions(@PathVariable String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        Channel.ExcludedItems excluded = channel.getExcludedItems();
        if (excluded == null) {
            excluded = new Channel.ExcludedItems();
        }
        return ResponseEntity.ok(excluded);
    }

    /**
     * Add a single exclusion to a channel.
     */
    @PostMapping("/{id}/exclusions/{type}/{youtubeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = CacheConfig.CACHE_CHANNELS, allEntries = true)
    public ResponseEntity<Channel.ExcludedItems> addExclusion(
            @PathVariable String id,
            @PathVariable String type,
            @PathVariable String youtubeId
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (youtubeId == null || youtubeId.trim().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        Channel.ExcludedItems excluded = channel.getExcludedItems();
        if (excluded == null) {
            excluded = new Channel.ExcludedItems();
        }

        boolean added = false;
        switch (type.toLowerCase()) {
            case "video":
                if (!excluded.getVideos().contains(youtubeId)) {
                    excluded.getVideos().add(youtubeId);
                    added = true;
                }
                break;
            case "playlist":
                if (!excluded.getPlaylists().contains(youtubeId)) {
                    excluded.getPlaylists().add(youtubeId);
                    added = true;
                }
                break;
            case "livestream":
                if (!excluded.getLiveStreams().contains(youtubeId)) {
                    excluded.getLiveStreams().add(youtubeId);
                    added = true;
                }
                break;
            case "short":
                if (!excluded.getShorts().contains(youtubeId)) {
                    excluded.getShorts().add(youtubeId);
                    added = true;
                }
                break;
            case "post":
                if (!excluded.getPosts().contains(youtubeId)) {
                    excluded.getPosts().add(youtubeId);
                    added = true;
                }
                break;
            default:
                return ResponseEntity.badRequest().build();
        }

        if (added) {
            channel.setExcludedItems(excluded);
            channel.touch();
            channelRepository.save(channel);
            workspaceExclusionsCache.invalidateAll();
        }

        return ResponseEntity.ok(excluded);
    }

    /**
     * Remove a single exclusion from a channel.
     */
    @DeleteMapping("/{id}/exclusions/{type}/{youtubeId}")
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = CacheConfig.CACHE_CHANNELS, allEntries = true)
    public ResponseEntity<Channel.ExcludedItems> removeExclusion(
            @PathVariable String id,
            @PathVariable String type,
            @PathVariable String youtubeId
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Channel channel = channelRepository.findById(id).orElse(null);
        if (channel == null) {
            return ResponseEntity.notFound().build();
        }

        Channel.ExcludedItems excluded = channel.getExcludedItems();
        if (excluded == null) {
            return ResponseEntity.notFound().build();
        }

        boolean removed;
        switch (type.toLowerCase()) {
            case "video":
                removed = excluded.getVideos().remove(youtubeId);
                break;
            case "playlist":
                removed = excluded.getPlaylists().remove(youtubeId);
                break;
            case "livestream":
                removed = excluded.getLiveStreams().remove(youtubeId);
                break;
            case "short":
                removed = excluded.getShorts().remove(youtubeId);
                break;
            case "post":
                removed = excluded.getPosts().remove(youtubeId);
                break;
            default:
                return ResponseEntity.badRequest().build();
        }

        if (removed) {
            channel.setExcludedItems(excluded);
            channel.touch();
            channelRepository.save(channel);
            workspaceExclusionsCache.invalidateAll();
        }

        return ResponseEntity.ok(excluded);
    }
}
