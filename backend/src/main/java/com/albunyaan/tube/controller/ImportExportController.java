package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ExportResponse;
import com.albunyaan.tube.dto.ImportRequest;
import com.albunyaan.tube.dto.ImportResponse;
import com.albunyaan.tube.dto.SimpleExportResponse;
import com.albunyaan.tube.dto.SimpleImportResponse;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ImportExportService;
import com.albunyaan.tube.service.SimpleExportService;
import com.albunyaan.tube.service.SimpleImportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

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

    private final ImportExportService importExportService;
    private final SimpleImportService simpleImportService;
    private final SimpleExportService simpleExportService;

    public ImportExportController(
            ImportExportService importExportService,
            SimpleImportService simpleImportService,
            SimpleExportService simpleExportService
    ) {
        this.importExportService = importExportService;
        this.simpleImportService = simpleImportService;
        this.simpleExportService = simpleExportService;
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
}

