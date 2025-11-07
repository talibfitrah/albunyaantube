package com.albunyaan.tube.util;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Utility to verify data after cleanup
 */
@Component
@Profile("verify")
public class DataVerifier implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataVerifier.class);
    private final Firestore db;

    public DataVerifier(Firestore db) {
        this.db = db;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("📊 Verifying Data After Cleanup...");
        log.info("");

        // Count and list channels
        List<QueryDocumentSnapshot> channels = db.collection("channels").get().get().getDocuments();
        log.info("✅ Channels: {} documents", channels.size());
        if (!channels.isEmpty()) {
            log.info("   Sample Channel IDs:");
            channels.stream().limit(5).forEach(doc -> {
                String youtubeId = doc.getString("youtubeId");
                String name = doc.getString("name");
                log.info("   - {} (YouTube ID: {})", name, youtubeId);
            });
        }
        log.info("");

        // Count and list playlists
        List<QueryDocumentSnapshot> playlists = db.collection("playlists").get().get().getDocuments();
        log.info("✅ Playlists: {} documents", playlists.size());
        if (!playlists.isEmpty()) {
            log.info("   Sample Playlist IDs:");
            playlists.stream().limit(5).forEach(doc -> {
                String youtubeId = doc.getString("youtubeId");
                String title = doc.getString("title");
                log.info("   - {} (YouTube ID: {})", title, youtubeId);
            });
        }
        log.info("");

        // Count and list videos
        List<QueryDocumentSnapshot> videos = db.collection("videos").get().get().getDocuments();
        log.info("✅ Videos: {} documents", videos.size());
        if (!videos.isEmpty()) {
            log.info("   Sample Video IDs:");
            videos.stream().limit(10).forEach(doc -> {
                String youtubeId = doc.getString("youtubeId");
                String title = doc.getString("title");
                log.info("   - {} (YouTube ID: {})", title, youtubeId);
            });
        }
        log.info("");

        // Count categories
        List<QueryDocumentSnapshot> categories = db.collection("categories").get().get().getDocuments();
        log.info("✅ Categories: {} documents", categories.size());
        log.info("");

        log.info("🎉 Verification Complete!");
        log.info("📊 Summary: {} channels, {} playlists, {} videos, {} categories",
                channels.size(), playlists.size(), videos.size(), categories.size());

        System.exit(0);
    }
}

