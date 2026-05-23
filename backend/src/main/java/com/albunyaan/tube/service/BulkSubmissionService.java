package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * BULK-01 (T7) — orchestrator for the bulk URL submission preview pipeline.
 * Submit method lands in T8.
 */
@Service
public class BulkSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(BulkSubmissionService.class);

    /**
     * BULK-01 security — admin-allowed statuses for bulk submit.
     * Mass-assignment defense: only PENDING/APPROVED accepted; REJECTED must go through
     * the per-row approval queue UI so it carries proper rejection metadata + audit trail.
     */
    private static final java.util.Set<String> ADMIN_VALID_STATUSES = java.util.Set.of("PENDING", "APPROVED");

    private final YouTubeUrlParser parser;
    private final YouTubeGateway gateway;
    private final RegistryDuplicateChecker dedupe;
    private final RegistrySubmissionWriter writer;   // null in T7; required by T8
    private final ExecutorService bulkPreviewExecutor;

    public BulkSubmissionService(YouTubeUrlParser parser, YouTubeGateway gateway,
                                  RegistryDuplicateChecker dedupe, RegistrySubmissionWriter writer,
                                  @Qualifier("bulkPreviewExecutor") ExecutorService bulkPreviewExecutor) {
        this.parser = parser;
        this.gateway = gateway;
        this.dedupe = dedupe;
        this.writer = writer;
        this.bulkPreviewExecutor = bulkPreviewExecutor;
    }

    public BulkPreviewResponse preview(BulkPreviewRequest req) {
        long start = System.currentTimeMillis();
        RegistryDuplicateChecker.Batch batch = dedupe.newBatch();
        List<CompletableFuture<PreviewRow>> futures = new ArrayList<>(req.urls().size());
        for (int i = 0; i < req.urls().size(); i++) {
            final int rowIndex = i;
            final String url = req.urls().get(i);
            futures.add(CompletableFuture.supplyAsync(() -> buildRow(rowIndex, url, batch), bulkPreviewExecutor));
        }
        List<PreviewRow> rows = futures.stream().map(CompletableFuture::join).toList();

        int okCount = (int) rows.stream().filter(r -> r.status() == RowStatus.OK).count();
        int errCount = (int) rows.stream().filter(r -> r.status() == RowStatus.ERROR).count();
        log.info("bulk-preview rowCount={} okCount={} errorCount={} durationMs={}",
                rows.size(), okCount, errCount, System.currentTimeMillis() - start);

        return new BulkPreviewResponse(rows);
    }

    public BulkSubmitResponse submit(BulkSubmitRequest req, String actorUid, boolean isAdmin) {
        long start = System.currentTimeMillis();
        // Role-based status normalization: moderators always PENDING; admin defaults PENDING but can pass APPROVED.
        String resolvedStatus;
        if (isAdmin && req.status() != null && !req.status().isBlank()) {
            String upper = req.status().toUpperCase(java.util.Locale.ROOT);
            if (!ADMIN_VALID_STATUSES.contains(upper)) {
                // Mass-assignment defense: reject crafted status values (REJECTED, REQUEST_CHANGES, etc.).
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "status must be one of: " + ADMIN_VALID_STATUSES);
            }
            resolvedStatus = upper;
        } else {
            resolvedStatus = "PENDING";
        }

        List<SubmitResult> results = new ArrayList<>(req.rows().size());
        int added = 0, failed = 0;

        for (SubmitRow row : req.rows()) {
            try {
                String registryId = switch (row.detectedType()) {
                    case CHANNEL  -> writer.writeChannel(row.metadata(), row.categoryIds(), resolvedStatus, actorUid, isAdmin);
                    case PLAYLIST -> writer.writePlaylist(row.metadata(), row.categoryIds(), resolvedStatus, actorUid, isAdmin);
                    case VIDEO    -> writer.writeVideo(row.metadata(),
                            row.videoType() != null ? row.videoType() : VideoType.STANDARD,
                            row.categoryIds(), resolvedStatus, actorUid, isAdmin);
                    default       -> throw new IllegalStateException("Unsupported detectedType in bulk submit: " + row.detectedType());
                };
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), registryId, SubmitStatus.ADDED, null));
                added++;
            } catch (Exception e) {
                log.warn("bulk-submit row failed: rowIndex={} url={} reason={}", row.rowIndex(), row.originalUrl(), e.getMessage());
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null, SubmitStatus.FAILED, "WRITE_ERROR"));
                failed++;
            }
        }

        log.info("bulk-submit actorUid={} rowCount={} added={} failed={} durationMs={}",
                actorUid, req.rows().size(), added, failed, System.currentTimeMillis() - start);

        return new BulkSubmitResponse(req.rows().size(), added, failed, results);
    }

    private PreviewRow buildRow(int rowIndex, String originalUrl, RegistryDuplicateChecker.Batch batch) {
        try {
            YouTubeUrlParseResult parsed = parser.parse(originalUrl);
            if (parsed.errorCode() != null) {
                return new PreviewRow(rowIndex, originalUrl, null, null, null, RowStatus.ERROR, null, null,
                        PreviewError.of(parsed.errorCode()));
            }

            Optional<RegistryDuplicateChecker.ExistingMatch> existing =
                    batch.findExisting(parsed.type(), parsed.youtubeId());

            if (existing.isPresent()
                    && ("PENDING".equals(existing.get().status()) || "APPROVED".equals(existing.get().status()))) {
                return new PreviewRow(rowIndex, originalUrl, parsed.type(), null, null,
                        RowStatus.DUPLICATE, existing.get().registryId(), existing.get().status(),
                        PreviewError.of(PreviewErrorCode.DUPLICATE));
            }

            PreviewFetchResult fetch = gateway.fetchByDetectedType(parsed.type(), parsed.youtubeId(), parsed.normalizedUrl());
            if (fetch.errorCode() != null) {
                return new PreviewRow(rowIndex, originalUrl, parsed.type(), null, null,
                        RowStatus.ERROR, null, null, PreviewError.of(fetch.errorCode()));
            }

            if (existing.isPresent() && "REJECTED".equals(existing.get().status())) {
                return new PreviewRow(rowIndex, originalUrl, parsed.type(),
                        fetch.videoType(), fetch.metadata(),
                        RowStatus.DUPLICATE_REJECTED, existing.get().registryId(), "REJECTED",
                        PreviewError.of(PreviewErrorCode.DUPLICATE_REJECTED));
            }

            return new PreviewRow(rowIndex, originalUrl, parsed.type(),
                    fetch.videoType(), fetch.metadata(),
                    RowStatus.OK, null, null, null);
        } catch (Exception e) {
            log.warn("BULK-01: buildRow rowIndex={} url={} unexpected exception: {}", rowIndex, originalUrl, e.toString());
            return new PreviewRow(rowIndex, originalUrl, null, null, null, RowStatus.ERROR, null, null,
                    PreviewError.of(PreviewErrorCode.NEWPIPE_PARSING_ERROR));
        }
    }
}
