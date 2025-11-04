package com.albunyaan.tube.util;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Cleanup seeder to remove old stub/fake data and keep only real YouTube data.
 *
 * Run with: ./gradlew bootRun --args='--spring.profiles.active=cleanup'
 */
@Component
@Profile("cleanup")
public class DataCleanupSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupSeeder.class);

    private final Firestore db;

    public DataCleanupSeeder(Firestore db) {
        this.db = db;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("🧹 Starting Data Cleanup...");
        log.info("This will remove all stub/fake data and keep only real YouTube data");

        int channelsDeleted = cleanupChannels();
        int playlistsDeleted = cleanupPlaylists();
        int videosDeleted = cleanupVideos();

        log.info("🎉 Cleanup Complete!");
        log.info("📊 Deleted: {} channels, {} playlists, {} videos",
                channelsDeleted, playlistsDeleted, videosDeleted);
        log.info("✅ Only real YouTube data remains");
    }

    private int cleanupChannels() throws ExecutionException, InterruptedException {
        log.info("Cleaning up channels...");

        // Get all channels
        List<QueryDocumentSnapshot> allChannels = db.collection("channels")
                .get()
                .get()
                .getDocuments();

        int count = 0;
        for (QueryDocumentSnapshot doc : allChannels) {
            String channelId = doc.getId();
            String youtubeId = doc.getString("youtubeId");
            String name = doc.getString("name");

            // Delete stub channels: those with fake IDs or "Sample" in the name/ID
            if (isStubChannel(channelId, youtubeId, name)) {
                doc.getReference().delete().get();
                count++;
                log.debug("Deleted stub channel: {} (YouTube ID: {}, Name: {})", channelId, youtubeId, name);
            }
        }

        log.info("✅ Deleted {} stub channels", count);
        return count;
    }

    /**
     * Check if the channel is a stub/fake channel.
     * Check both the document ID and the youtubeId field, as well as the name.
     */
    private boolean isStubChannel(String channelId, String youtubeId, String name) {
        if (youtubeId == null) {
            return false;
        }

        // Check for stub patterns in YouTube ID
        if (youtubeId.contains("00000") ||
            youtubeId.contains("Sample") ||
            youtubeId.contains("hadith") ||
            youtubeId.contains("Test") ||
            youtubeId.length() < 20) {
            return true;
        }

        // Check for stub patterns in name
        if (name != null && name.contains("Sample")) {
            return true;
        }

        return false;
    }

    private int cleanupPlaylists() throws ExecutionException, InterruptedException {
        log.info("Cleaning up playlists...");

        // Get all playlists
        List<QueryDocumentSnapshot> allPlaylists = db.collection("playlists")
                .get()
                .get()
                .getDocuments();

        int count = 0;
        for (QueryDocumentSnapshot doc : allPlaylists) {
            String playlistId = doc.getId();
            String youtubeId = doc.getString("youtubeId");
            String title = doc.getString("title");

            // Delete stub playlists: those with fake IDs or "Sample" in the title/ID
            if (isStubPlaylist(playlistId, youtubeId, title)) {
                doc.getReference().delete().get();
                count++;
                log.debug("Deleted stub playlist: {} (YouTube ID: {}, Title: {})", playlistId, youtubeId, title);
            }
        }

        log.info("✅ Deleted {} stub playlists", count);
        return count;
    }

    /**
     * Check if the playlist is a stub/fake playlist.
     * Check both the youtubeId field and the title.
     */
    private boolean isStubPlaylist(String playlistId, String youtubeId, String title) {
        if (youtubeId == null) {
            return false;
        }

        // Check for stub patterns in YouTube ID
        if (youtubeId.contains("Sample") ||
            youtubeId.contains("Hadith") ||
            youtubeId.contains("Test") ||
            youtubeId.length() < 20) {
            return true;
        }

        // Check for stub patterns in title
        if (title != null && title.contains("Sample")) {
            return true;
        }

        return false;
    }

    private int cleanupVideos() throws ExecutionException, InterruptedException {
        log.info("Cleaning up videos...");

        // Get all videos
        List<QueryDocumentSnapshot> allVideos = db.collection("videos")
                .get()
                .get()
                .getDocuments();

        int count = 0;
        for (QueryDocumentSnapshot doc : allVideos) {
            String videoId = doc.getId();
            // Delete stub videos: those that don't look like real YouTube video IDs
            if (isStubVideoId(videoId)) {
                doc.getReference().delete().get();
                count++;
                log.debug("Deleted stub video: {}", videoId);
            }
        }

        log.info("✅ Deleted {} stub videos", count);
        return count;
    }

    /**
     * Check if the video ID is a stub/fake ID.
     * Real YouTube video IDs are exactly 11 characters and alphanumeric (with _ and -)
     * Our internal format is video_{youtubeId}, so we need to extract the youtube part.
     */
    private boolean isStubVideoId(String videoId) {
        if (videoId == null) {
            return false;
        }

        // Extract the YouTube ID part if it has our internal prefix
        String youtubeId = videoId;
        if (videoId.startsWith("video_")) {
            youtubeId = videoId.substring("video_".length());
        }

        // Check if it's NOT a real YouTube video ID
        // Real YouTube video IDs are exactly 11 characters and alphanumeric (with _ and -)
        if (youtubeId.length() != 11) {
            return true; // Not 11 chars = stub
        }

        // Check if it matches the pattern for fake IDs (contains "000" or other obvious patterns)
        if (youtubeId.contains("000") || youtubeId.startsWith("vid") || youtubeId.startsWith("test")) {
            return true;
        }

        // Check if it matches YouTube video ID pattern (alphanumeric + _ and -)
        if (!youtubeId.matches("^[A-Za-z0-9_-]{11}$")) {
            return true; // Doesn't match pattern = stub
        }

        return false; // Looks like a real YouTube ID
    }
}
