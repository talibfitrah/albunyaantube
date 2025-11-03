package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.ExportResponse;
import com.albunyaan.tube.dto.ImportRequest;
import com.albunyaan.tube.dto.ImportResponse;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ImportExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

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

    public ImportExportController(ImportExportService importExportService) {
        this.importExportService = importExportService;
    }

    /**
     * Export all content as JSON
     *
     * @param includeCategories Include categories in export
     * @param includeChannels Include channels in export
     * @param includePlaylists Include playlists in export
     * @param includeVideos Include videos in export
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
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, IOException {

        ExportResponse export = importExportService.exportAll(
            includeCategories,
            includeChannels,
            includePlaylists,
            includeVideos,
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
    ) throws ExecutionException, InterruptedException, IOException {

        ExportResponse export = importExportService.exportAll(
            true, false, false, false, user.getUid()
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
    ) throws ExecutionException, InterruptedException, IOException {

        ExportResponse export = importExportService.exportAll(
            false, true, false, false, user.getUid()
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
    ) throws ExecutionException, InterruptedException, IOException {

        ExportResponse export = importExportService.exportAll(
            false, false, true, false, user.getUid()
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
     * @param user Current authenticated user
     * @return JSON file download
     */
    @GetMapping("/export/videos")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportVideos(
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, IOException {

        ExportResponse export = importExportService.exportAll(
            false, false, false, true, user.getUid()
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
    ) throws IOException, ExecutionException, InterruptedException {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(
                ImportResponse.error("File is empty")
            );
        }

        if (!file.getOriginalFilename().endsWith(".json")) {
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

        if (!file.getOriginalFilename().endsWith(".json")) {
            return ResponseEntity.badRequest().body(
                ImportResponse.error("Only JSON files are supported")
            );
        }

        String jsonContent = new String(file.getBytes());
        ImportRequest importRequest = ImportRequest.fromJson(jsonContent);

        ImportResponse validation = importExportService.validateImport(importRequest);

        return ResponseEntity.ok(validation);
    }
}
