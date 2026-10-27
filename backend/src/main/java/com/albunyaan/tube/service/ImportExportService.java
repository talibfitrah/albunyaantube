package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ExportResponse;
import com.albunyaan.tube.dto.ImportRequest;
import com.albunyaan.tube.dto.ImportResponse;
import com.albunyaan.tube.exception.LiveCategoryException;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Service for bulk import/export of content.
 *
 * Supports exporting/importing:
 * - Categories
 * - Channels
 * - Playlists
 * - Videos
 *
 * Export format: JSON
 * Import strategies: SKIP (default), OVERWRITE, MERGE
 */
@Service
public class ImportExportService {

    private static final Logger logger = LoggerFactory.getLogger(ImportExportService.class);

    private final CategoryRepository categoryRepository;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final AuditLogService auditLogService;
    private final VideoValidationService videoValidationService;
    private final LiveCategoryGuard liveCategoryGuard;

    public ImportExportService(
            CategoryRepository categoryRepository,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            AuditLogService auditLogService,
            VideoValidationService videoValidationService,
            LiveCategoryGuard liveCategoryGuard
    ) {
        this.categoryRepository = categoryRepository;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.auditLogService = auditLogService;
        this.videoValidationService = videoValidationService;
        this.liveCategoryGuard = liveCategoryGuard;
    }

    /**
     * Export all content to JSON
     *
     * @param includeCategories Include categories in export
     * @param includeChannels Include channels in export
     * @param includePlaylists Include playlists in export
     * @param includeVideos Include videos in export
     * @param excludeUnavailableVideos Exclude videos marked as UNAVAILABLE from export
     * @param exportedBy User ID performing the export
     * @return Export response with all requested content
     */
    public ExportResponse exportAll(
            boolean includeCategories,
            boolean includeChannels,
            boolean includePlaylists,
            boolean includeVideos,
            boolean excludeUnavailableVideos,
            String exportedBy
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        List<Category> categories = includeCategories ? categoryRepository.findAll() : null;
        List<Channel> channels = includeChannels ? channelRepository.findAll() : null;
        List<Playlist> playlists = includePlaylists ? playlistRepository.findAll() : null;
        List<Video> videos = null;

        if (includeVideos) {
            videos = videoRepository.findAll();

            // Filter out unavailable videos if requested
            if (excludeUnavailableVideos && videos != null) {
                videos = videos.stream()
                        .filter(v -> v.getValidationStatus() != null
                            && v.getValidationStatus() != com.albunyaan.tube.model.ValidationStatus.UNAVAILABLE)
                        .collect(java.util.stream.Collectors.toList());
                logger.info("Filtered out unavailable videos, remaining: {}", videos.size());
            }
        }

        ExportResponse.ExportMetadata metadata = new ExportResponse.ExportMetadata(
                exportedBy,
                categories != null ? categories.size() : 0,
                channels != null ? channels.size() : 0,
                playlists != null ? playlists.size() : 0,
                videos != null ? videos.size() : 0
        );

        logger.info("Export completed by {}: {} categories, {} channels, {} playlists, {} videos",
                exportedBy,
                metadata.getCategoriesCount(),
                metadata.getChannelsCount(),
                metadata.getPlaylistsCount(),
                metadata.getVideosCount());

        return new ExportResponse(metadata, categories, channels, playlists, videos);
    }

    /**
     * Import content from JSON with specified merge strategy
     */
    public ImportResponse importAll(ImportRequest request) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        ImportResponse.ImportCounts counts = new ImportResponse.ImportCounts();
        ImportResponse response = ImportResponse.success(counts);

        String mergeStrategy = request.getMergeStrategy();
        String importedBy = request.getImportedBy();

        // §7 row 15 (D9): a full JSON import is a raw model write. Every stored doc is read once, here: D8 and the live
        // guard below and the writes after them see the same docs, and nothing is saved until both rules pass.
        // A doc whose youtubeId the file swaps is a new item to the guard (its stored LIVE membership proves nothing).
        Map<String, Category> oldCategories = new HashMap<>();
        Map<String, Channel> oldChannels = new HashMap<>();
        Map<String, Playlist> oldPlaylists = new HashMap<>();
        Map<String, Video> oldVideos = new HashMap<>();
        List<LiveCategoryGuard.Change> liveChanges = new ArrayList<>();
        if (request.getCategories() != null) {
            for (Category c : request.getCategories()) {
                Category old = c.getId() == null ? null : categoryRepository.findById(c.getId()).orElse(null);
                oldCategories.put(c.getId(), old);
                if ((old != null && "SKIP".equals(mergeStrategy)) || c.getParentCategoryId() == null) continue;
                // D8 (a file never sets the type, below): a LIVE category stays top-level, and nothing nests under one.
                if ((old != null && old.isLive())
                        || categoryRepository.findById(c.getParentCategoryId()).map(Category::isLive).orElse(false)) {
                    throw LiveCategoryException.shape(c.getName());
                }
            }
        }
        if (request.getChannels() != null) {
            for (Channel c : request.getChannels()) {
                Channel old = c.getId() == null ? null : channelRepository.findById(c.getId()).orElse(null);
                oldChannels.put(c.getId(), old);
                if (old != null && "SKIP".equals(mergeStrategy)) continue;
                Channel before = old != null && Objects.equals(old.getYoutubeId(), c.getYoutubeId()) ? old : null;
                liveChanges.add(LiveCategoryGuard.Change.of("channel:" + c.getId(), c, before, c.getCategoryIds(), c.getStatus()));
            }
        }
        if (request.getPlaylists() != null) {
            for (Playlist p : request.getPlaylists()) {
                Playlist old = p.getId() == null ? null : playlistRepository.findById(p.getId()).orElse(null);
                oldPlaylists.put(p.getId(), old);
                if (old != null && "SKIP".equals(mergeStrategy)) continue;
                Playlist before = old != null && Objects.equals(old.getYoutubeId(), p.getYoutubeId()) ? old : null;
                liveChanges.add(LiveCategoryGuard.Change.of("playlist:" + p.getId(), p, before, p.getCategoryIds(), p.getStatus()));
            }
        }
        if (request.getVideos() != null) {
            for (Video v : request.getVideos()) {
                Video old = v.getId() == null ? null : videoRepository.findById(v.getId()).orElse(null);
                oldVideos.put(v.getId(), old);
                if (old != null && "SKIP".equals(mergeStrategy)) continue;
                Video before = old != null && Objects.equals(old.getYoutubeId(), v.getYoutubeId()) ? old : null;
                liveChanges.add(LiveCategoryGuard.Change.of("video:" + v.getId(), v, before, v.getCategoryIds(), v.getStatus()));
            }
        }
        Map<String, String> verifiedChannels = liveCategoryGuard.checkAll(true, liveChanges);
        if (request.getVideos() != null) {
            request.getVideos().forEach(v -> {
                String verified = verifiedChannels.get("video:" + v.getId());
                if (verified != null) v.setChannelId(verified);
            });
        }

        // Import categories first (they're referenced by other entities)
        if (request.getCategories() != null) {
            for (Category category : request.getCategories()) {
                try {
                    Objects.requireNonNull(category.getId(), "Missing category ID");   // a per-item error, as findById(null) was
                    Optional<Category> existingOpt = Optional.ofNullable(oldCategories.get(category.getId()));

                    if (existingOpt.isPresent() && "SKIP".equals(mergeStrategy)) {
                        counts.incrementCategoriesSkipped();
                        continue;
                    }

                    if (existingOpt.isPresent() && "MERGE".equals(mergeStrategy)) {
                        // MERGE: Preserve timestamps and audit fields, update content fields
                        Category existing = existingOpt.get();
                        category.setCreatedAt(existing.getCreatedAt());
                        category.setCreatedBy(existing.getCreatedBy());
                        category.setUpdatedBy(importedBy);
                        category.setUpdatedAt(Timestamp.now());
                    } else {
                        // OVERWRITE or new entity
                        category.setUpdatedBy(importedBy);
                        category.setUpdatedAt(Timestamp.now());
                        if (!existingOpt.isPresent()) {
                            category.setCreatedBy(importedBy);
                        }
                    }

                    // R1/R18: a file never sets or clears LIVE; the shape and members rules run only in CategoryController.
                    category.setType(existingOpt.map(Category::getType).orElse(null));
                    categoryRepository.save(category);
                    counts.incrementCategoriesImported();
                } catch (Exception e) {
                    logger.error("Failed to import category {}: {}", category.getId(), e.getMessage());
                    response.addError("CATEGORY", category.getId(), e.getMessage());
                    counts.incrementTotalErrors();
                }
            }
        }

        // Import channels (no setCreatedBy/setUpdatedBy - these fields don't exist)
        if (request.getChannels() != null) {
            for (Channel channel : request.getChannels()) {
                try {
                    Objects.requireNonNull(channel.getId(), "Missing channel ID");
                    Optional<Channel> existingOpt = Optional.ofNullable(oldChannels.get(channel.getId()));

                    if (existingOpt.isPresent() && "SKIP".equals(mergeStrategy)) {
                        counts.incrementChannelsSkipped();
                        continue;
                    }

                    if (existingOpt.isPresent() && "MERGE".equals(mergeStrategy)) {
                        // MERGE: Preserve timestamps, audit fields, update content
                        Channel existing = existingOpt.get();
                        channel.setCreatedAt(existing.getCreatedAt());
                        channel.setSubmittedBy(existing.getSubmittedBy());
                        channel.setApprovedBy(existing.getApprovedBy());
                    }

                    // Update timestamp
                    channel.setUpdatedAt(com.google.cloud.Timestamp.now());

                    channelRepository.save(channel);
                    counts.incrementChannelsImported();
                } catch (Exception e) {
                    logger.error("Failed to import channel {}: {}", channel.getId(), e.getMessage());
                    response.addError("CHANNEL", channel.getId(), e.getMessage());
                    counts.incrementTotalErrors();
                }
            }
        }

        // Import playlists (no setCreatedBy/setUpdatedBy - these fields don't exist)
        if (request.getPlaylists() != null) {
            for (Playlist playlist : request.getPlaylists()) {
                try {
                    Objects.requireNonNull(playlist.getId(), "Missing playlist ID");
                    Optional<Playlist> existingOpt = Optional.ofNullable(oldPlaylists.get(playlist.getId()));

                    if (existingOpt.isPresent() && "SKIP".equals(mergeStrategy)) {
                        counts.incrementPlaylistsSkipped();
                        continue;
                    }

                    if (existingOpt.isPresent() && "MERGE".equals(mergeStrategy)) {
                        // MERGE: Preserve timestamps, audit fields, update content
                        Playlist existing = existingOpt.get();
                        playlist.setCreatedAt(existing.getCreatedAt());
                        playlist.setSubmittedBy(existing.getSubmittedBy());
                        playlist.setApprovedBy(existing.getApprovedBy());
                    }

                    // Update timestamp
                    playlist.setUpdatedAt(com.google.cloud.Timestamp.now());

                    playlistRepository.save(playlist);
                    counts.incrementPlaylistsImported();
                } catch (Exception e) {
                    logger.error("Failed to import playlist {}: {}", playlist.getId(), e.getMessage());
                    response.addError("PLAYLIST", playlist.getId(), e.getMessage());
                    counts.incrementTotalErrors();
                }
            }
        }

        // Import videos (no setCreatedBy/setUpdatedBy - these fields don't exist)
        List<Video> importedVideos = new ArrayList<>();
        if (request.getVideos() != null) {
            for (Video video : request.getVideos()) {
                try {
                    Objects.requireNonNull(video.getId(), "Missing video ID");
                    Optional<Video> existingOpt = Optional.ofNullable(oldVideos.get(video.getId()));

                    if (existingOpt.isPresent() && "SKIP".equals(mergeStrategy)) {
                        counts.incrementVideosSkipped();
                        continue;
                    }
                    // Stage 9 B21 (Cubic r9 #3): an id a report took down is skipped, as the simple and async imports skip
                    // an id with a doc. Written beside its take-down record, an older APPROVED doc is the one served.
                    if (!ContentReportService.isTakeDownRecord(video) && videoRepository.findByYoutubeId(video.getYoutubeId())
                            .filter(ContentReportService::isTakeDownRecord).isPresent()) {
                        counts.incrementVideosSkipped();
                        response.addError("VIDEO", video.getId(), "Skipped: video " + video.getYoutubeId()
                                + " was taken down by a report. Restore it under Archived content first.");
                        continue;
                    }

                    if (existingOpt.isPresent()) {
                        // Stage 9 B14: server-only (an export leaves it out), so a later Restore of a take-down lifts it
                        video.setTakeDownExclusions(existingOpt.get().getTakeDownExclusions());
                    }
                    if (existingOpt.isPresent() && "MERGE".equals(mergeStrategy)) {
                        // MERGE: Preserve timestamps, audit fields, validation status, update content
                        Video existing = existingOpt.get();
                        video.setCreatedAt(existing.getCreatedAt());
                        video.setSubmittedBy(existing.getSubmittedBy());
                        video.setApprovedBy(existing.getApprovedBy());
                        // Preserve validation status - don't overwrite with import data
                        video.setValidationStatus(existing.getValidationStatus());
                        video.setLastValidatedAt(existing.getLastValidatedAt());
                    }

                    // Update timestamp
                    video.setUpdatedAt(com.google.cloud.Timestamp.now());

                    videoRepository.save(video);
                    counts.incrementVideosImported();
                    importedVideos.add(video);
                } catch (Exception e) {
                    logger.error("Failed to import video {}: {}", video.getId(), e.getMessage());
                    response.addError("VIDEO", video.getId(), e.getMessage());
                    counts.incrementTotalErrors();
                }
            }
        }

        // Validate imported videos against YouTube
        if (!importedVideos.isEmpty()) {
            try {
                logger.info("Validating {} imported videos against YouTube API", importedVideos.size());
                videoValidationService.validateSpecificVideos(importedVideos, "IMPORT");
            } catch (Exception e) {
                logger.error("Failed to validate imported videos: {}", e.getMessage(), e);
                // Don't fail the import if validation fails
                response.addError("VALIDATION", "import", "Video validation failed: " + e.getMessage());
                counts.incrementTotalErrors();
            }
        }

        logger.info("Import completed by {}: {} categories, {} channels, {} playlists, {} videos (Strategy: {}, Errors: {})",
                importedBy,
                counts.getCategoriesImported(),
                counts.getChannelsImported(),
                counts.getPlaylistsImported(),
                counts.getVideosImported(),
                mergeStrategy,
                counts.getTotalErrors());

        return response;
    }

    /**
     * Validate import without actually importing
     */
    public ImportResponse validateImport(ImportRequest request) {
        ImportResponse.ImportCounts counts = new ImportResponse.ImportCounts();
        ImportResponse response = ImportResponse.success(counts);
        response.setMessage("Validation completed");

        // Validate categories
        if (request.getCategories() != null) {
            for (Category category : request.getCategories()) {
                if (category.getId() == null || category.getId().isEmpty()) {
                    response.addError("CATEGORY", "unknown", "Missing category ID");
                    counts.incrementTotalErrors();
                    continue;
                }
                if (category.getName() == null || category.getName().isEmpty()) {
                    response.addError("CATEGORY", category.getId(), "Missing category name");
                    counts.incrementTotalErrors();
                    continue;
                }
                counts.incrementCategoriesImported();
            }
        }

        // Validate channels
        if (request.getChannels() != null) {
            for (Channel channel : request.getChannels()) {
                if (channel.getId() == null || channel.getId().isEmpty()) {
                    response.addError("CHANNEL", "unknown", "Missing channel ID");
                    counts.incrementTotalErrors();
                    continue;
                }
                if (channel.getName() == null || channel.getName().isEmpty()) {
                    response.addError("CHANNEL", channel.getId(), "Missing channel name");
                    counts.incrementTotalErrors();
                    continue;
                }
                counts.incrementChannelsImported();
            }
        }

        // Validate playlists
        if (request.getPlaylists() != null) {
            for (Playlist playlist : request.getPlaylists()) {
                if (playlist.getId() == null || playlist.getId().isEmpty()) {
                    response.addError("PLAYLIST", "unknown", "Missing playlist ID");
                    counts.incrementTotalErrors();
                    continue;
                }
                if (playlist.getTitle() == null || playlist.getTitle().isEmpty()) {
                    response.addError("PLAYLIST", playlist.getId(), "Missing playlist title");
                    counts.incrementTotalErrors();
                    continue;
                }
                counts.incrementPlaylistsImported();
            }
        }

        // Validate videos
        if (request.getVideos() != null) {
            for (Video video : request.getVideos()) {
                if (video.getId() == null || video.getId().isEmpty()) {
                    response.addError("VIDEO", "unknown", "Missing video ID");
                    counts.incrementTotalErrors();
                    continue;
                }
                if (video.getTitle() == null || video.getTitle().isEmpty()) {
                    response.addError("VIDEO", video.getId(), "Missing video title");
                    counts.incrementTotalErrors();
                    continue;
                }
                counts.incrementVideosImported();
            }
        }

        return response;
    }
}

