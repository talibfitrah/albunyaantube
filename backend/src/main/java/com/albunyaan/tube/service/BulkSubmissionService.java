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

    private PreviewRow buildRow(int rowIndex, String originalUrl, RegistryDuplicateChecker.Batch batch) {
        try {
            YouTubeUrlParseResult parsed = parser.parse(originalUrl);
            if (parsed.errorCode() != null) {
                return new PreviewRow(rowIndex, originalUrl, null, null, null, null, RowStatus.ERROR, null, null,
                        PreviewError.of(parsed.errorCode()));
            }

            Optional<RegistryDuplicateChecker.ExistingMatch> existing =
                    batch.findExisting(parsed.type(), parsed.youtubeId());

            if (existing.isPresent()
                    && ("PENDING".equals(existing.get().status()) || "APPROVED".equals(existing.get().status()))) {
                return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(), null, null,
                        RowStatus.DUPLICATE, existing.get().registryId(), existing.get().status(),
                        PreviewError.of(PreviewErrorCode.DUPLICATE));
            }

            PreviewFetchResult fetch = gateway.fetchByDetectedType(parsed.type(), parsed.youtubeId(), parsed.normalizedUrl());
            if (fetch.errorCode() != null) {
                return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(), null, null,
                        RowStatus.ERROR, null, null, PreviewError.of(fetch.errorCode()));
            }

            if (existing.isPresent() && "REJECTED".equals(existing.get().status())) {
                return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(),
                        fetch.videoType(), fetch.metadata(),
                        RowStatus.DUPLICATE_REJECTED, existing.get().registryId(), "REJECTED",
                        PreviewError.of(PreviewErrorCode.DUPLICATE_REJECTED));
            }

            return new PreviewRow(rowIndex, originalUrl, parsed.normalizedUrl(), parsed.type(),
                    fetch.videoType(), fetch.metadata(),
                    RowStatus.OK, null, null, null);
        } catch (Exception e) {
            log.warn("BULK-01: buildRow rowIndex={} url={} unexpected exception: {}", rowIndex, originalUrl, e.toString());
            return new PreviewRow(rowIndex, originalUrl, null, null, null, null, RowStatus.ERROR, null, null,
                    PreviewError.of(PreviewErrorCode.NEWPIPE_PARSING_ERROR));
        }
    }
}
