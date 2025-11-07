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
 * Utility to inspect data and identify stub records
 */
@Component
@Profile("inspect")
public class DataInspector implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInspector.class);
    private final Firestore db;

    public DataInspector(Firestore db) {
        this.db = db;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("🔍 Inspecting Data to identify stub records...");
        log.info("");

        // Inspect channels
        log.info("=== CHANNELS ===");
        List<QueryDocumentSnapshot> channels = db.collection("channels").get().get().getDocuments();
        for (QueryDocumentSnapshot doc : channels) {
            String docId = doc.getId();
            String youtubeId = doc.getString("youtubeId");
            String name = doc.getString("name");
            String submittedBy = doc.getString("submittedBy");

            log.info("ID: {} | YouTubeID: {} | Name: {} | SubmittedBy: {}",
                    docId, youtubeId, name, submittedBy);
        }
        log.info("");

        // Inspect playlists
        log.info("=== PLAYLISTS ===");
        List<QueryDocumentSnapshot> playlists = db.collection("playlists").get().get().getDocuments();
        for (QueryDocumentSnapshot doc : playlists) {
            String docId = doc.getId();
            String youtubeId = doc.getString("youtubeId");
            String title = doc.getString("title");
            String submittedBy = doc.getString("submittedBy");

            log.info("ID: {} | YouTubeID: {} | Title: {} | SubmittedBy: {}",
                    docId, youtubeId, title, submittedBy);
        }
        log.info("");

        log.info("🎉 Inspection Complete!");

        System.exit(0);
    }
}

