package com.albunyaan.tube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * Main application class for Albunyaan Tube Backend
 *
 * Stack:
 * - Spring Boot 3
 * - Firebase Firestore (database)
 * - Firebase Authentication (auth)
 * - YouTube Data API v3 (content search)
 * - Redis (caching)
 */
@SpringBootApplication
@EnableCaching
public class AlbunyaanTubeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlbunyaanTubeApplication.class, args);
    }
}
