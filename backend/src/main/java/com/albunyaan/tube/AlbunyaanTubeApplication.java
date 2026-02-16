package com.albunyaan.tube;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for FitrahTube (Albunyaan Tube) Backend.
 * <p>
 * Note: The user-facing brand name is "FitrahTube". The package ({@code com.albunyaan.tube})
 * and class name ({@code AlbunyaanTubeApplication}) retain the original "albunyaan" naming
 * for backward compatibility.
 *
 * Stack:
 * - Spring Boot 3
 * - Firebase Firestore (database)
 * - Firebase Authentication (auth)
 * - NewPipeExtractor (YouTube content extraction - no API key required)
 * - Redis/Caffeine (caching)
 */
@SpringBootApplication
@EnableCaching
@EnableScheduling
public class AlbunyaanTubeApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlbunyaanTubeApplication.class, args);
    }
}

