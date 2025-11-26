package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ExportResponse;
import com.albunyaan.tube.dto.ImportRequest;
import com.albunyaan.tube.dto.ImportResponse;
import com.albunyaan.tube.dto.SimpleExportResponse;
import com.albunyaan.tube.dto.SimpleImportResponse;
import com.albunyaan.tube.dto.ValidationRunDto;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.repository.ValidationRunRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ContentImportService;
import com.albunyaan.tube.service.ImportExportService;
import com.albunyaan.tube.service.SimpleExportService;
import com.albunyaan.tube.service.SimpleImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Controller for bulk import/export of content (channels, playlists, videos, categories).
 *
 * Export formats: JSON
 * Import formats: JSON
 *
 * Admin-only endpoints for data management.
 */
@RestController
@RequestMapping("/api/admin/import-export")
public class ImportExportController {

    private static final Logger logger = LoggerFactory.getLogger(ImportExportController.class);

    private final ImportExportService importExportService;
    private final SimpleImportService simpleImportService;
    private final SimpleExportService simpleExportService;
    private final ContentImportService contentImportService;
    private final ValidationRunRepository validationRunRepository;
    private final Executor validationExecutor;

    /**
     * Flag to prevent concurrent import runs.
     * Only one async import can run at a time to prevent overwhelming the system.
     * Uses AtomicBoolean for thread-safe atomic check-and-set operations.
     */
    private final AtomicBoolean isImportRunning = new AtomicBoolean(false);

    public ImportExportController(
            ImportExportService importExportService,
            SimpleImportService simpleImportService,
            SimpleExportService simpleExportService,
            ContentImportService contentImportService,
            ValidationRunRepository validationRunRepository,
            @Qualifier("validationExecutor") Executor validationExecutor
    ) {
        this.importExportService = importExportService;
        this.simpleImportService = simpleImportService;
        this.simpleExportService = simpleExportService;
        this.contentImportService = contentImportService;
        this.validationRunRepository = validationRunRepository;
        this.validationExecutor = validationExecutor;
    }

    /**
     * Export all content as JSON
     *
     * @param includeCategories Include categories in export
     * @param includeChannels Include channels in export
     * @param includePlaylists Include playlists in export
     * @param includeVideos Include videos in export
     * @param excludeUnavailableVideos Exclude videos marked as UNAVAILABLE (default: true)
     * @param user Current authenticated user
     * @return JSON file download
     */
    @GetMapping("/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportContent(
            @RequestParam(defaultValue = "true") boolean includeCategories,
            @RequestParam(defaultValue = "true") boolean includeChannels,
            @RequestParam(defaultValue = "true") boolean includePlaylists,
            @RequestParam(defaultValue = "true") boolean includeVideos,
            @RequestParam(defaultValue = "true") boolean excludeUnavailableVideos,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException, IOException {

        ExportResponse export = importExportService.exportAll(
            includeCategories,
            includeChannels,
            includePlaylists,
            includeVideos,
            excludeUnavailableVideos,
            user.getUid()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDispositionFormData("attachment", "albunyaan-tube-export.json");

        return ResponseEntity.ok()
                .headers(headers)
                .body(export.toJson().getBytes());
    }

    /**
     * Export only categories as JSON
     *
     * @param user Current authenticated user
     * @return JSON file download
     */
    @GetMapping("/export/categories")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportCategories(
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException, IOException {

        ExportResponse export = importExportService.exportAll(
            true, false, false, false, true, user.getUid()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDispositionFormData("attachment", "categories-export.json");

        return ResponseEntity.ok()
                .headers(headers)
                .body(export.toJson().getBytes());
    }

    /**
     * Export only channels as JSON
     *
     * @param user Current authenticated user
     * @return JSON file download
     */
    @GetMapping("/export/channels")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportChannels(
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException, IOException {

        ExportResponse export = importExportService.exportAll(
            false, true, false, false, true, user.getUid()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDispositionFormData("attachment", "channels-export.json");

        return ResponseEntity.ok()
                .headers(headers)
                .body(export.toJson().getBytes());
    }

    /**
     * Export only playlists as JSON
     *
     * @param user Current authenticated user
     * @return JSON file download
     */
    @GetMapping("/export/playlists")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportPlaylists(
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException, IOException {

        ExportResponse export = importExportService.exportAll(
            false, false, true, false, true, user.getUid()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDispositionFormData("attachment", "playlists-export.json");

        return ResponseEntity.ok()
                .headers(headers)
                .body(export.toJson().getBytes());
    }

    /**
     * Export only videos as JSON
     *
     * @param excludeUnavailableVideos Exclude videos marked as UNAVAILABLE (default: true)
     * @param user Current authenticated user
     * @return JSON file download
     */
    @GetMapping("/export/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportVideos(
            @RequestParam(defaultValue = "true") boolean excludeUnavailableVideos,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException, IOException {

        ExportResponse export = importExportService.exportAll(
            false, false, false, true, excludeUnavailableVideos, user.getUid()
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDispositionFormData("attachment", "videos-export.json");

        return ResponseEntity.ok()
                .headers(headers)
                .body(export.toJson().getBytes());
    }

    /**
     * Import content from JSON file
     *
     * @param file JSON file to import
     * @param mergeStrategy How to handle conflicts (SKIP, OVERWRITE, MERGE)
     * @param user Current authenticated user
     * @return Import summary with counts and errors
     */
    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ImportResponse> importContent(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "SKIP") String mergeStrategy,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws IOException, ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                ImportResponse.error("File is empty")
            );
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return ResponseEntity.badRequest().body(
                ImportResponse.error("Only JSON files are supported")
            );
        }

        String jsonContent = new String(file.getBytes());
        ImportRequest importRequest = ImportRequest.fromJson(jsonContent);
        importRequest.setMergeStrategy(mergeStrategy);
        importRequest.setImportedBy(user.getUid());

        ImportResponse response = importExportService.importAll(importRequest);

        return ResponseEntity.ok(response);
    }

    /**
     * Validate import file without actually importing
     *
     * @param file JSON file to validate
     * @param user Current authenticated user
     * @return Validation results
     */
    @PostMapping("/import/validate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ImportResponse> validateImport(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                ImportResponse.error("File is empty")
            );
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return ResponseEntity.badRequest().body(
                ImportResponse.error("Only JSON files are supported")
            );
        }

        String jsonContent = new String(file.getBytes());
        ImportRequest importRequest = ImportRequest.fromJson(jsonContent);

        ImportResponse validation = importExportService.validateImport(importRequest);

        return ResponseEntity.ok(validation);
    }

    // ============================================================
    // Simple Format Endpoints
    // ============================================================

    /**
     * Export content in simple format: [{channelId: "Title|Cat1,Cat2"}, ...]
     * Only exports APPROVED items.
     *
     * @param includeChannels Include channels in export
     * @param includePlaylists Include playlists in export
     * @param includeVideos Include videos in export
     * @param user Current authenticated user
     * @return JSON file download in simple format
     */
    @GetMapping("/export/simple")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportSimpleFormat(
            @RequestParam(defaultValue = "true") boolean includeChannels,
            @RequestParam(defaultValue = "true") boolean includePlaylists,
            @RequestParam(defaultValue = "true") boolean includeVideos,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws IOException, ExecutionException, InterruptedException, TimeoutException {

        SimpleExportResponse export = simpleExportService.exportSimpleFormat(
            includeChannels,
            includePlaylists,
            includeVideos
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentDispositionFormData("attachment", "albunyaan-tube-export-simple.json");

        return ResponseEntity.ok()
                .headers(headers)
                .body(export.toJson().getBytes());
    }

    /**
     * Import content from simple format: [{channelId: "Title|Cat1,Cat2"}, ...]
     * Validates YouTube IDs still exist and skips duplicates.
     *
     * @param file JSON file in simple format
     * @param defaultStatus Default approval status (APPROVED or PENDING)
     * @param user Current authenticated user
     * @return Import results with counts and errors
     */
    @PostMapping("/import/simple")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimpleImportResponse> importSimpleFormat(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "APPROVED") String defaultStatus,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                SimpleImportResponse.error("File is empty")
            );
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return ResponseEntity.badRequest().body(
                SimpleImportResponse.error("Only JSON files are supported")
            );
        }

        String jsonContent = new String(file.getBytes());

        // Parse simple format: [{channels}, {playlists}, {videos}]
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, String>> simpleData = mapper.readValue(
            jsonContent,
            new TypeReference<List<Map<String, String>>>() {}
        );

        if (simpleData.size() != 3) {
            return ResponseEntity.badRequest().body(
                SimpleImportResponse.error("Invalid format: expected array of 3 objects [channels, playlists, videos]")
            );
        }

        SimpleImportResponse response = simpleImportService.importSimpleFormat(
            simpleData,
            defaultStatus,
            user.getUid(),
            false // not validateOnly
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Validate simple format import file without actually importing (dry-run)
     *
     * @param file JSON file in simple format
     * @param user Current authenticated user
     * @return Validation results with potential errors
     */
    @PostMapping("/import/simple/validate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimpleImportResponse> validateSimpleImport(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws IOException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                SimpleImportResponse.error("File is empty")
            );
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return ResponseEntity.badRequest().body(
                SimpleImportResponse.error("Only JSON files are supported")
            );
        }

        String jsonContent = new String(file.getBytes());

        // Parse simple format
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, String>> simpleData = mapper.readValue(
            jsonContent,
            new TypeReference<List<Map<String, String>>>() {}
        );

        if (simpleData.size() != 3) {
            return ResponseEntity.badRequest().body(
                SimpleImportResponse.error("Invalid format: expected array of 3 objects [channels, playlists, videos]")
            );
        }

        SimpleImportResponse validation = simpleImportService.importSimpleFormat(
            simpleData,
            "APPROVED", // default status doesn't matter for validation
            user.getUid(),
            true // validateOnly = true (dry-run)
        );

        return ResponseEntity.ok(validation);
    }

    // ============================================================
    // Async Import Endpoints
    // ============================================================

    /**
     * Import content from simple format asynchronously with YouTube validation.
     * Returns immediately with a runId that can be used to poll for status.
     * Prevents timeout errors for large imports by processing in background.
     *
     * @param file JSON file in simple format
     * @param defaultStatus Default approval status (APPROVED or PENDING)
     * @param user Current authenticated user
     * @return 202 Accepted with runId for status polling
     */
    @PostMapping("/import/simple/async")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> importSimpleFormatAsync(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "APPROVED") String defaultStatus,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws IOException {

        // Check if another import is already running (atomic check-and-set)
        if (!isImportRunning.compareAndSet(false, true)) {
            logger.warn("Import already running, rejecting new request from user {}", user.getUid());
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of(
                            "success", false,
                            "error", "An import is already running. Please wait for it to complete.",
                            "status", "CONFLICT"
                    ));
        }

        // Validate file
        if (file.isEmpty()) {
            isImportRunning.set(false);  // Release lock before returning
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "error", "File is empty")
            );
        }

        // Validate file size (50MB limit to prevent DoS)
        long maxFileSize = 50 * 1024 * 1024;  // 50MB
        if (file.getSize() > maxFileSize) {
            isImportRunning.set(false);  // Release lock before returning
            logger.warn("Import file too large: {} bytes from user {}", file.getSize(), user.getUid());
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "error", "File too large. Maximum size: 50MB")
            );
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase(Locale.ROOT).endsWith(".json")) {
            isImportRunning.set(false);  // Release lock before returning
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "error", "Only JSON files are supported")
            );
        }

        // Parse JSON file
        String jsonContent = new String(file.getBytes());
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, String>> simpleData;
        try {
            simpleData = mapper.readValue(
                    jsonContent,
                    new TypeReference<List<Map<String, String>>>() {}
            );
        } catch (IOException e) {
            isImportRunning.set(false);  // Release lock before returning
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "error", "Invalid JSON format: " + e.getMessage())
            );
        }

        if (simpleData.size() != 3) {
            isImportRunning.set(false);  // Release lock before returning
            return ResponseEntity.badRequest().body(
                    Map.of("success", false, "error", "Invalid format: expected array of 3 objects [channels, playlists, videos]")
            );
        }

        // Create validation run to track progress
        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, user.getUid(), user.getEmail());
        run.setStatus(ValidationRun.STATUS_RUNNING);
        run.setCurrentPhase("INITIALIZING");

        try {
            validationRunRepository.save(run);
            logger.info("Created import run {} for user {}", run.getId(), user.getUid());
        } catch (Exception e) {
            isImportRunning.set(false);  // Release lock before returning
            logger.error("Failed to save validation run", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    Map.of("success", false, "error", "Failed to create import run: " + e.getMessage())
            );
        }

        final String runId = run.getId();

        // Start async import
        try {
            CompletableFuture.runAsync(() -> {
                try {
                    contentImportService.importSimpleFormatAsync(
                            run,
                            simpleData,
                            defaultStatus,
                            user.getUid()
                    );
                } catch (Exception e) {
                    logger.error("Import run {} failed with exception", runId, e);
                } finally {
                    isImportRunning.set(false);  // Always release lock
                }
            }, validationExecutor);

            logger.info("Started async import run {}", runId);

            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of(
                            "success", true,
                            "runId", runId,
                            "status", "RUNNING",
                            "message", "Import started. Use /import/status/{runId} to check progress."
                    ));

        } catch (RejectedExecutionException e) {
            isImportRunning.set(false);  // Release lock on rejection
            logger.error("Import executor queue full, rejecting import request", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .header("Retry-After", "60")
                    .body(Map.of(
                            "success", false,
                            "error", "Import service is currently overloaded. Please try again in 60 seconds.",
                            "status", "SERVICE_UNAVAILABLE"
                    ));
        }
    }

    /**
     * Get status of an async import run.
     * Poll this endpoint to track import progress.
     *
     * @param runId ValidationRun ID returned from /import/simple/async
     * @param user Current authenticated user
     * @return Import status with progress counters
     */
    @GetMapping("/import/status/{runId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getImportStatus(
            @PathVariable String runId,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) {
        try {
            ValidationRun run = validationRunRepository.findById(runId).orElse(null);

            if (run == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "Import run not found"));
            }

            // Convert to DTO for response
            ValidationRunDto dto = ValidationRunDto.fromModel(run);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "run", dto
            ));

        } catch (Exception e) {
            logger.error("Failed to get import status for runId {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Failed to retrieve import status: " + e.getMessage()));
        }
    }

    /**
     * Download failed items from an import run as JSON.
     * Only available after import completes.
     *
     * @param runId ValidationRun ID
     * @param user Current authenticated user
     * @return JSON file with failed item IDs and reasons
     */
    @GetMapping("/import/{runId}/failed-items")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> downloadFailedItems(
            @PathVariable String runId,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) {
        try {
            ValidationRun run = validationRunRepository.findById(runId).orElse(null);

            if (run == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("success", false, "error", "Import run not found"));
            }

            if (!ValidationRun.STATUS_COMPLETED.equals(run.getStatus()) &&
                    !ValidationRun.STATUS_FAILED.equals(run.getStatus())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("success", false, "error", "Import run is still in progress"));
            }

            // Extract failed items from details
            Map<String, Object> details = run.getDetails();
            @SuppressWarnings("unchecked")
            List<String> failedItemIds = (List<String>) details.getOrDefault("failedItemIds", List.of());

            // Convert to simple-format JSON: [{channels}, {playlists}, {videos}]
            // Use placeholder "Retry Import|Global" for title|categories since we don't store original values
            Map<String, String> channelsMap = new LinkedHashMap<>();
            Map<String, String> playlistsMap = new LinkedHashMap<>();
            Map<String, String> videosMap = new LinkedHashMap<>();

            for (String prefixedId : failedItemIds) {
                if (prefixedId.startsWith("channel:")) {
                    String youtubeId = prefixedId.substring(8); // Remove "channel:" prefix
                    channelsMap.put(youtubeId, "Retry Import|Global");
                } else if (prefixedId.startsWith("playlist:")) {
                    String youtubeId = prefixedId.substring(9); // Remove "playlist:" prefix
                    playlistsMap.put(youtubeId, "Retry Import|Global");
                } else if (prefixedId.startsWith("video:")) {
                    String youtubeId = prefixedId.substring(6); // Remove "video:" prefix
                    videosMap.put(youtubeId, "Retry Import|Global");
                }
            }

            // Build simple-format array: [channels, playlists, videos]
            List<Map<String, String>> simpleFormatData = List.of(channelsMap, playlistsMap, videosMap);

            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(simpleFormatData);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setContentDispositionFormData("attachment", "import-" + runId + "-failed-items.json");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(json.getBytes());

        } catch (Exception e) {
            logger.error("Failed to download failed items for runId {}", runId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", "Failed to download failed items: " + e.getMessage()));
        }
    }
}

