package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * FIREBASE-MIGRATE-01: Firebase configuration properties
 */
@Component
@ConfigurationProperties(prefix = "app.firebase")
public class FirebaseProperties {

    private String serviceAccountPath;
    private String projectId;
    private Firestore firestore = new Firestore();

    public String getServiceAccountPath() {
        return serviceAccountPath;
    }

    public void setServiceAccountPath(String serviceAccountPath) {
        this.serviceAccountPath = serviceAccountPath;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Firestore getFirestore() {
        return firestore;
    }

    public void setFirestore(Firestore firestore) {
        this.firestore = firestore;
    }

    public static class Firestore {
        private String databaseId = "(default)";

        public String getDatabaseId() {
            return databaseId;
        }

        public void setDatabaseId(String databaseId) {
            this.databaseId = databaseId;
        }
    }
}
