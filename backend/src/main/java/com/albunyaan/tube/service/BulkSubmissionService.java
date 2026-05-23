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
    private final PublicContentCacheService publicContentCacheService;
    private final SortOrderService sortOrderService;

    public BulkSubmissionService(YouTubeUrlParser parser, YouTubeGateway gateway,
                                  RegistryDuplicateChecker dedupe, RegistrySubmissionWriter writer,
                                  @Qualifier("bulkPreviewExecutor") ExecutorService bulkPreviewExecutor,
                                  PublicContentCacheService publicContentCacheService,
                                  SortOrderService sortOrderService) {
        this.parser = parser;
        this.gateway = gateway;
        this.dedupe = dedupe;
        this.writer = writer;
        this.bulkPreviewExecutor = bulkPreviewExecutor;
        this.publicContentCacheService = publicContentCacheService;
        this.sortOrderService = sortOrderService;
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

        // BULK-01 (Group J): cap the whole batch at 180s so a hung NewPipe call
        // can't pin a worker thread indefinitely (the bulkPreviewExecutor only
        // has 5 threads — 5 stuck calls = endpoint dead). Per-future timeouts
        // are stronger but require restructuring the join; this batch cap
        // gives us the safety net without a rewrite.
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(180, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            log.warn("bulk-preview batch timed out after 180s — falling back to per-future best-effort join");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("bulk-preview interrupted; returning best-effort partial results");
        } catch (ExecutionException ee) {
            // individual futures handle their own errors in buildRow; an outer
            // ExecutionException means a buildRow leaked a throwable — log and
            // fall through to the join below which will surface it per-row.
            log.warn("bulk-preview allOf threw {}", ee.getMessage());
        }

        List<PreviewRow> rows = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<PreviewRow> f = futures.get(i);
            try {
                rows.add(f.isDone() ? f.join() : f.getNow(timeoutRow(i, req.urls().get(i))));
            } catch (java.util.concurrent.CancellationException | CompletionException ce) {
                rows.add(timeoutRow(i, req.urls().get(i)));
            }
            if (!f.isDone()) f.cancel(true);
        }

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
        // BULK-01 security (Group G): per-submit dedupe batch closes the
        // preview→submit window where a second moderator could land the same
        // youtubeId after the first moderator previewed but before they
        // submitted. Not a full Firestore transaction, but closes the
        // sequential-submit race.
        RegistryDuplicateChecker.Batch lateBatch = dedupe.newBatch();

        for (SubmitRow row : req.rows()) {
            // BULK-01 security (Group C) — verify client-supplied metadata.youtubeId + detectedType
            // round-trip from row.originalUrl. Prevents tampered submit bodies pointing at a
            // different entity than the moderator previewed (mass assignment via metadata).
            YouTubeUrlParseResult parsed = parser.parse(row.originalUrl());
            if (parsed.errorCode() != null) {
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null,
                        SubmitStatus.FAILED, "INVALID_URL"));
                failed++;
                continue;
            }
            if (parsed.type() != row.detectedType()) {
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null,
                        SubmitStatus.FAILED, "TYPE_MISMATCH"));
                failed++;
                continue;
            }
            if (row.metadata() == null
                    || !java.util.Objects.equals(parsed.youtubeId(), row.metadata().youtubeId())) {
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null,
                        SubmitStatus.FAILED, "YOUTUBE_ID_MISMATCH"));
                failed++;
                continue;
            }

            // BULK-01 security (Group G): late dedupe check immediately before write.
            // Closes the preview→submit window where a parallel submit landed the
            // same youtubeId. Costs one Firestore read per row but is small relative
            // to the write that follows.
            try {
                var lateDupe = lateBatch.findExisting(row.detectedType(), row.metadata().youtubeId());
                if (lateDupe.isPresent() && !"REJECTED".equals(lateDupe.get().status())) {
                    results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null,
                            SubmitStatus.FAILED, "DUPLICATE"));
                    failed++;
                    continue;
                }
            } catch (RuntimeException dedupeErr) {
                log.warn("bulk-submit late-dedupe failed rowIndex={} url={} reason={}",
                        row.rowIndex(), row.originalUrl(), dedupeErr.getMessage());
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null,
                        SubmitStatus.FAILED, "INTERNAL_ERROR"));
                failed++;
                continue;
            }

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

                // BULK-01 (Group H): when a bulk row lands APPROVED, mirror the
                // single-add controller path's side effects so the new row is
                // immediately visible to the public API and sortable in its
                // categories. APPROVED-only — PENDING rows don't appear in
                // public content and don't need sort-order seeding.
                if ("APPROVED".equals(resolvedStatus) && row.categoryIds() != null) {
                    String typeKey = switch (row.detectedType()) {
                        case CHANNEL  -> "channel";
                        case PLAYLIST -> "playlist";
                        case VIDEO    -> "video";
                        default       -> null;
                    };
                    if (typeKey != null) {
                        for (String categoryId : row.categoryIds()) {
                            try {
                                sortOrderService.addContentToCategory(categoryId, registryId, typeKey);
                            } catch (RuntimeException sortErr) {
                                log.warn("bulk-submit sortOrder add failed rowIndex={} category={} registryId={} reason={}",
                                        row.rowIndex(), categoryId, registryId, sortErr.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("bulk-submit row failed: rowIndex={} url={} reason={}", row.rowIndex(), row.originalUrl(), e.getMessage());
                results.add(new SubmitResult(row.rowIndex(), row.originalUrl(), null, SubmitStatus.FAILED, "WRITE_ERROR"));
                failed++;
            }
        }

        // BULK-01 (Group H): one cache eviction after the batch (not per-row)
        // so the public-content cache picks up all new APPROVED rows in one
        // sweep. Skip if nothing landed APPROVED.
        if (added > 0 && "APPROVED".equals(resolvedStatus)) {
            try {
                publicContentCacheService.evictPublicContentCaches();
            } catch (RuntimeException cacheErr) {
                log.warn("bulk-submit cache eviction failed reason={}", cacheErr.getMessage());
            }
        }

        log.info("bulk-submit actorUid={} rowCount={} added={} failed={} durationMs={}",
                actorUid, req.rows().size(), added, failed, System.currentTimeMillis() - start);

        return new BulkSubmitResponse(req.rows().size(), added, failed, results);
    }

    /** BULK-01 (Group J): synth-row for previews that didn't complete inside the 180s batch budget. */
    private static PreviewRow timeoutRow(int rowIndex, String originalUrl) {
        return new PreviewRow(rowIndex, originalUrl, null, null, null, RowStatus.ERROR, null, null,
                PreviewError.of(PreviewErrorCode.NETWORK_ERROR));
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

            // BULK-01 security (Group G): treat ANY non-REJECTED existing row as a duplicate.
            // Previously only PENDING/APPROVED were caught — REQUEST_CHANGES (a real status
            // per RegistryController.VALID_STATUSES) and any future status string would
            // fall through and silently create a duplicate Firestore doc.
            if (existing.isPresent() && !"REJECTED".equals(existing.get().status())) {
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
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // BULK-01 (Group K): RegistryDuplicateChecker wraps Firestore errors
            // as IllegalStateException with "Registry lookup failed for ..." prefix.
            // Surface those distinctly so on-call doesn't chase NewPipe when the
            // real cause is Firestore unavailability.
            boolean isFirestore = e instanceof IllegalStateException
                    && e.getMessage() != null
                    && e.getMessage().startsWith("Registry lookup failed for ");
            PreviewErrorCode code = isFirestore
                    ? PreviewErrorCode.INTERNAL_ERROR
                    : PreviewErrorCode.NEWPIPE_PARSING_ERROR;
            log.warn("BULK-01: buildRow rowIndex={} url={} {} exception: {}",
                    rowIndex, originalUrl, isFirestore ? "Firestore" : "NewPipe", e.toString());
            return new PreviewRow(rowIndex, originalUrl, null, null, null, RowStatus.ERROR, null, null,
                    PreviewError.of(code));
        }
    }
}
