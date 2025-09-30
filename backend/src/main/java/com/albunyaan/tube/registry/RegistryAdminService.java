package com.albunyaan.tube.registry;

import com.albunyaan.tube.audit.AuditAction;
import com.albunyaan.tube.audit.AuditLogService;
import com.albunyaan.tube.audit.AuditResourceType;
import com.albunyaan.tube.category.Category;
import com.albunyaan.tube.category.CategoryRepository;
import com.albunyaan.tube.registry.model.ChannelRegistry;
import com.albunyaan.tube.registry.model.PlaylistRegistry;
import com.albunyaan.tube.registry.model.VideoRegistry;
import com.albunyaan.tube.registry.repository.ChannelRegistryRepository;
import com.albunyaan.tube.registry.repository.PlaylistRegistryRepository;
import com.albunyaan.tube.registry.repository.VideoRegistryRepository;
import com.albunyaan.tube.user.User;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class RegistryAdminService {

    private final ChannelRegistryRepository channelRegistryRepository;
    private final PlaylistRegistryRepository playlistRegistryRepository;
    private final VideoRegistryRepository videoRegistryRepository;
    private final CategoryRepository categoryRepository;
    private final AuditLogService auditLogService;

    public RegistryAdminService(
        ChannelRegistryRepository channelRegistryRepository,
        PlaylistRegistryRepository playlistRegistryRepository,
        VideoRegistryRepository videoRegistryRepository,
        CategoryRepository categoryRepository,
        AuditLogService auditLogService
    ) {
        this.channelRegistryRepository = channelRegistryRepository;
        this.playlistRegistryRepository = playlistRegistryRepository;
        this.videoRegistryRepository = videoRegistryRepository;
        this.categoryRepository = categoryRepository;
        this.auditLogService = auditLogService;
    }

    public ChannelRegistry registerOrUpdateChannel(User actor, String ytChannelId, Set<String> categorySlugs) {
        var normalizedId = normalizeYoutubeId(ytChannelId);
        var categories = resolveCategories(categorySlugs);
        var channel = channelRegistryRepository
            .findByYtChannelId(normalizedId)
            .orElseGet(() -> new ChannelRegistry(normalizedId));
        channel.updateCategories(categories);
        var saved = channelRegistryRepository.save(channel);
        recordAudit(actor, AuditAction.REGISTRY_CHANNEL_INCLUDED, AuditResourceType.CHANNEL, saved.getId(), Map.of("ytId", normalizedId));
        return saved;
    }

    public PlaylistRegistry registerOrUpdatePlaylist(
        User actor,
        String ytPlaylistId,
        String channelYtId,
        Set<String> categorySlugs
    ) {
        var normalizedPlaylistId = normalizeYoutubeId(ytPlaylistId);
        var parentChannel = channelRegistryRepository
            .findByYtChannelId(normalizeYoutubeId(channelYtId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        var categories = resolveCategories(categorySlugs);
        var playlist = playlistRegistryRepository
            .findByYtPlaylistId(normalizedPlaylistId)
            .orElseGet(() -> new PlaylistRegistry(parentChannel, normalizedPlaylistId));
        playlist.updateCategories(categories);
        playlist.setChannel(parentChannel);
        var saved = playlistRegistryRepository.save(playlist);
        recordAudit(
            actor,
            AuditAction.REGISTRY_PLAYLIST_INCLUDED,
            AuditResourceType.PLAYLIST,
            saved.getId(),
            Map.of("ytId", normalizedPlaylistId, "channelId", parentChannel.getId().toString())
        );
        return saved;
    }

    public VideoRegistry registerOrUpdateVideo(
        User actor,
        String ytVideoId,
        String channelYtId,
        String playlistYtId,
        Set<String> categorySlugs
    ) {
        var normalizedVideoId = normalizeYoutubeId(ytVideoId);
        var channel = channelRegistryRepository
            .findByYtChannelId(normalizeYoutubeId(channelYtId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));
        PlaylistRegistry playlist = null;
        if (StringUtils.hasText(playlistYtId)) {
            var normalizedPlaylistId = normalizeYoutubeId(playlistYtId);
            playlist = playlistRegistryRepository
                .findByYtPlaylistId(normalizedPlaylistId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found"));
        }
        var categories = resolveCategories(categorySlugs);
        var video = videoRegistryRepository
            .findByYtVideoId(normalizedVideoId)
            .orElseGet(() -> new VideoRegistry(channel, normalizedVideoId));
        video.updateCategories(categories);
        video.setPlaylist(playlist);
        var saved = videoRegistryRepository.save(video);
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("ytId", normalizedVideoId);
        metadata.put("channelId", channel.getId().toString());
        if (playlist != null) {
            metadata.put("playlistId", playlist.getId().toString());
        }
        recordAudit(actor, AuditAction.REGISTRY_VIDEO_INCLUDED, AuditResourceType.VIDEO, saved.getId(), metadata);
        return saved;
    }

    public ChannelRegistry updateChannelExclusions(
        User actor,
        UUID channelId,
        Set<String> excludedPlaylistIds,
        Set<String> excludedVideoIds
    ) {
        var channel = channelRegistryRepository
            .findById(channelId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found: " + channelId));
        channel.replaceExcludedPlaylistIds(normalizeYoutubeIds(excludedPlaylistIds));
        channel.replaceExcludedVideoIds(normalizeYoutubeIds(excludedVideoIds));
        var saved = channelRegistryRepository.save(channel);
        recordAudit(
            actor,
            AuditAction.REGISTRY_CHANNEL_EXCLUDED,
            AuditResourceType.CHANNEL,
            saved.getId(),
            Map.of(
                "excludedPlaylistIds",
                saved.getExcludedPlaylistIds(),
                "excludedVideoIds",
                saved.getExcludedVideoIds()
            )
        );
        return saved;
    }

    public PlaylistRegistry updatePlaylistExclusions(User actor, UUID playlistId, Set<String> excludedVideoIds) {
        var playlist = playlistRegistryRepository
            .findById(playlistId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Playlist not found: " + playlistId));
        playlist.replaceExcludedVideoIds(normalizeYoutubeIds(excludedVideoIds));
        var saved = playlistRegistryRepository.save(playlist);
        recordAudit(
            actor,
            AuditAction.REGISTRY_PLAYLIST_EXCLUDED,
            AuditResourceType.PLAYLIST,
            saved.getId(),
            Map.of("excludedVideoIds", saved.getExcludedVideoIds())
        );
        return saved;
    }

    private void recordAudit(User actor, AuditAction action, AuditResourceType resourceType, UUID resourceId, Map<String, Object> metadata) {
        auditLogService.recordEntry(action, resourceType, resourceId.toString(), null, actor, metadata);
    }

    private LinkedHashSet<Category> resolveCategories(Set<String> slugs) {
        if (slugs == null || slugs.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one category is required");
        }
        var categories = new LinkedHashSet<Category>();
        for (var slug : slugs) {
            var normalized = normalizeCategorySlug(slug);
            var category = categoryRepository
                .findBySlug(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown category: " + slug));
            categories.add(category);
        }
        return categories;
    }

    private Set<String> normalizeYoutubeIds(Set<String> ids) {
        if (ids == null) {
            return Set.of();
        }
        return ids.stream().filter(StringUtils::hasText).map(this::normalizeYoutubeId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeYoutubeId(String ytId) {
        if (!StringUtils.hasText(ytId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "YouTube id must not be blank");
        }
        var trimmed = ytId.trim();
        if (trimmed.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "YouTube id exceeds 64 characters");
        }
        return trimmed;
    }

    private String normalizeCategorySlug(String slug) {
        if (!StringUtils.hasText(slug)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category slug must not be blank");
        }
        return slug.trim().toLowerCase(Locale.ROOT);
    }
}
