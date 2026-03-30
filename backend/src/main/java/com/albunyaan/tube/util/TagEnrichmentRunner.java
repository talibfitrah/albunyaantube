package com.albunyaan.tube.util;

import com.albunyaan.tube.service.TagEnrichmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One-time tag enrichment runner for production content.
 *
 * Runs with: ./gradlew bootRun --args='--spring.profiles.active=enrich-tags'
 *
 * Processes all content (channels, playlists, videos) sequentially:
 * 1. For items with real YouTube IDs: fetches tags from YouTube via NewPipeExtractor
 * 2. Generates additional tags from title, description, and categories
 * 3. Adds cross-language translations (English, Arabic, Dutch)
 * 4. Saves enriched keywords to Firestore
 *
 * Rate limiting is handled by YouTubeThrottler + YouTubeCircuitBreaker.
 * If circuit breaker trips, remaining YouTube fetches are skipped but
 * metadata-based tags are still generated.
 */
@Component
@Profile("enrich-tags")
public class TagEnrichmentRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(TagEnrichmentRunner.class);

    private final TagEnrichmentService tagEnrichmentService;

    public TagEnrichmentRunner(TagEnrichmentService tagEnrichmentService) {
        this.tagEnrichmentService = tagEnrichmentService;
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("=== TAG ENRICHMENT RUNNER STARTED ===");
        log.info("Processing all content with YouTube tag fetching enabled...");

        // force=true: enrich ALL items (even if some already have keywords)
        // fetchYouTube=true: fetch real tags from YouTube for items with real IDs
        TagEnrichmentService.EnrichmentResult result =
                tagEnrichmentService.enrichAllContent(true, true);

        log.info("=== TAG ENRICHMENT COMPLETE ===");
        log.info("  Total items:  {}", result.total);
        log.info("  Enriched:     {}", result.enriched);
        log.info("  Skipped:      {}", result.skipped);
        log.info("  Errors:       {}", result.errors);

        if (result.errorMessages != null && !result.errorMessages.isEmpty()) {
            log.warn("  Error details:");
            for (String msg : result.errorMessages) {
                log.warn("    - {}", msg);
            }
        }

        log.info("=== ENRICHMENT RUNNER FINISHED ===");
    }
}
