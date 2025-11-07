package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.SimpleExportResponse;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Service for exporting content in simple JSON format.
 * Format: [{channelId: "Title|Cat1,Cat2"}, {playlistId: "Title|Cat1,Cat2"}, {videoId: "Title|Cat1,Cat2"}]
 *
 * Export logic:
 * 1. Query only APPROVED items from Firestore
 * 2. For each item, get primary category names (comma-separated)
 * 3. Format as "Title|Categories"
 * 4. Return as 3-object array structure
 */
@Service
public class SimpleExportService {

    private static final Logger logger = LoggerFactory.getLogger(SimpleExportService.class);

    private final Firestore firestore;
    private final CategoryMappingService categoryMappingService;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;

    public SimpleExportService(
            Firestore firestore,
            CategoryMappingService categoryMappingService,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository
    ) {
        this.firestore = firestore;
        this.categoryMappingService = categoryMappingService;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
    }

    /**
     * Export content in simple format.
     * Only exports APPROVED items.
     *
     * @param includeChannels Whether to include channels
     * @param includePlaylists Whether to include playlists
     * @param includeVideos Whether to include videos
     * @return SimpleExportResponse with 3-object array structure
     */
    public SimpleExportResponse exportSimpleFormat(
            boolean includeChannels,
            boolean includePlaylists,
            boolean includeVideos
    ) {
        SimpleExportResponse response = new SimpleExportResponse();

        try {
            // Export channels
            if (includeChannels) {
                exportChannels(response);
            }

            // Export playlists
            if (includePlaylists) {
                exportPlaylists(response);
            }

            // Export videos
            if (includeVideos) {
                exportVideos(response);
            }

        } catch (Exception e) {
            logger.error("Failed to export simple format: {}", e.getMessage(), e);
        }

        return response;
    }

    /**
     * Export approved channels
     */
    private void exportChannels(SimpleExportResponse response) throws ExecutionException, InterruptedException {
        // Query only APPROVED channels
        QuerySnapshot querySnapshot = firestore.collection("channels")
                .whereEqualTo("status", "APPROVED")
                .get()
                .get();

        List<Channel> channels = querySnapshot.toObjects(Channel.class);

        logger.info("Exporting {} approved channels", channels.size());

        for (Channel channel : channels) {
            if (channel.getYoutubeId() != null && channel.getName() != null) {
                String categories = categoryMappingService.getCategoryNamesCommaSeparated(channel.getCategoryIds());

                response.addChannel(
                        channel.getYoutubeId(),
                        channel.getName(),
                        categories
                );
            }
        }
    }

    /**
     * Export approved playlists
     */
    private void exportPlaylists(SimpleExportResponse response) throws ExecutionException, InterruptedException {
        // Query only APPROVED playlists
        QuerySnapshot querySnapshot = firestore.collection("playlists")
                .whereEqualTo("status", "APPROVED")
                .get()
                .get();

        List<Playlist> playlists = querySnapshot.toObjects(Playlist.class);

        logger.info("Exporting {} approved playlists", playlists.size());

        for (Playlist playlist : playlists) {
            if (playlist.getYoutubeId() != null && playlist.getTitle() != null) {
                String categories = categoryMappingService.getCategoryNamesCommaSeparated(playlist.getCategoryIds());

                response.addPlaylist(
                        playlist.getYoutubeId(),
                        playlist.getTitle(),
                        categories
                );
            }
        }
    }

    /**
     * Export approved videos
     */
    private void exportVideos(SimpleExportResponse response) throws ExecutionException, InterruptedException {
        // Query only APPROVED videos
        QuerySnapshot querySnapshot = firestore.collection("videos")
                .whereEqualTo("status", "APPROVED")
                .get()
                .get();

        List<Video> videos = querySnapshot.toObjects(Video.class);

        logger.info("Exporting {} approved videos", videos.size());

        for (Video video : videos) {
            if (video.getYoutubeId() != null && video.getTitle() != null) {
                String categories = categoryMappingService.getCategoryNamesCommaSeparated(video.getCategoryIds());

                response.addVideo(
                        video.getYoutubeId(),
                        video.getTitle(),
                        categories
                );
            }
        }
    }
}

