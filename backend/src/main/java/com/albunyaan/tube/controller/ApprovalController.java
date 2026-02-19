package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ApprovalService;
import com.albunyaan.tube.service.PublicContentCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * BACKEND-APPR-01: Approval Controller
 *
 * REST API for approval workflow (pending, approve, reject).
 * Admin/Moderator only endpoints.
 */
@RestController
@RequestMapping("/api/admin/approvals")
@PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalService approvalService;
    private final ApprovalRepository approvalRepository;
    private final PublicContentCacheService publicContentCacheService;

    public ApprovalController(ApprovalService approvalService, ApprovalRepository approvalRepository, PublicContentCacheService publicContentCacheService) {
        this.approvalService = approvalService;
        this.approvalRepository = approvalRepository;
        this.publicContentCacheService = publicContentCacheService;
    }

    /**
     * GET /api/admin/approvals/pending-count
     *
     * Returns the total count of pending items across all content types.
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    @GetMapping("/pending-count")
    public ResponseEntity<java.util.Map<String, Long>> getPendingCount() {
        try {
            long count = approvalRepository.countAllPending();
            return ResponseEntity.ok(java.util.Map.of("count", count));
        } catch (Exception e) {
            log.error("Failed to get pending count", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * GET /api/admin/approvals/pending
     *
     * List pending approvals with filters
     *
     * Query params:
     * - type: CHANNEL|PLAYLIST|VIDEO (optional)
     * - category: category ID (optional)
     * - limit: page size (default 20)
     * - cursor: pagination cursor (optional)
     */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/pending")
    public ResponseEntity<CursorPageDto<PendingApprovalDto>> getPendingApprovals(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal FirebaseUserDetails user)
            throws ExecutionException, InterruptedException, TimeoutException {

        try {
            log.debug("GET /pending - type={}, category={}, limit={}, cursor={}, user={}",
                    type, category, limit, cursor, user.getUid());

            CursorPageDto<PendingApprovalDto> result = approvalService.getPendingApprovals(
                    type, category, limit, cursor);

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter in pending approvals: {}", e.getMessage());
            return ResponseEntity.badRequest().body(null);
        } catch (Exception e) {
            log.error("Failed to get pending approvals - type={}, category={}, cursor={}",
                    type, category, cursor, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * GET /api/admin/approvals/my-submissions
     *
     * List the authenticated user's own submissions filtered by status.
     * Available to both admins and moderators.
     *
     * Query params:
     * - status: PENDING|APPROVED|REJECTED (default PENDING)
     * - type: CHANNEL|PLAYLIST|VIDEO (optional)
     * - limit: page size (default 20)
     * - cursor: pagination cursor (optional)
     */
    @PreAuthorize("hasAnyRole('ADMIN', 'MODERATOR')")
    @GetMapping("/my-submissions")
    public ResponseEntity<CursorPageDto<PendingApprovalDto>> getMySubmissions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor,
            @AuthenticationPrincipal FirebaseUserDetails user)
            throws ExecutionException, InterruptedException, TimeoutException {

        try {
            log.debug("GET /my-submissions - status={}, type={}, limit={}, cursor={}, user={}",
                    status, type, limit, cursor, user.getUid());

            CursorPageDto<PendingApprovalDto> result = approvalService.getMySubmissions(
                    user.getUid(), status, type, limit, cursor);

            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameter in my-submissions: {}", e.getMessage());
            return ResponseEntity.badRequest().body(null);
        } catch (Exception e) {
            log.error("Failed to get my submissions for user {} - status={}, type={}, cursor={}",
                    user.getUid(), status, type, cursor, e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    /**
     * POST /api/admin/approvals/{id}/approve
     *
     * Approve a pending item
     *
     * Body:
     * {
     *   "reviewNotes": "High quality content",
     *   "categoryOverride": "category_id" (optional)
     * }
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/approve")
    public ResponseEntity<ApprovalResponseDto> approve(
            @PathVariable String id,
            @RequestBody ApprovalRequestDto request,
            @AuthenticationPrincipal FirebaseUserDetails user)
            throws ExecutionException, InterruptedException, TimeoutException {

        try {
            log.debug("POST /approve - id={}, user={}", id, user.getUid());

            ApprovalResponseDto response = approvalService.approve(
                    id, request, user.getUid(), user.getEmail());
            try {
                publicContentCacheService.evictPublicContentCaches();
            } catch (Exception ce) {
                log.warn("Cache eviction failed after approving {}: {}", id, ce.getMessage());
            }

            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.warn("Approval failed - invalid state: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException e) {
            log.warn("Approval failed - item not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to approve item: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/admin/approvals/{id}/reject
     *
     * Reject a pending item
     *
     * Body:
     * {
     *   "reason": "NOT_ISLAMIC|LOW_QUALITY|DUPLICATE|OTHER",
     *   "reviewNotes": "Content not aligned with platform guidelines"
     * }
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/reject")
    public ResponseEntity<ApprovalResponseDto> reject(
            @PathVariable String id,
            @RequestBody RejectionRequestDto request,
            @AuthenticationPrincipal FirebaseUserDetails user)
            throws ExecutionException, InterruptedException, TimeoutException {

        try {
            log.debug("POST /reject - id={}, reason={}, user={}",
                    id, request.getReason(), user.getUid());

            ApprovalResponseDto response = approvalService.reject(
                    id, request, user.getUid(), user.getEmail());
            try {
                publicContentCacheService.evictPublicContentCaches();
            } catch (Exception ce) {
                log.warn("Cache eviction failed after rejecting {}: {}", id, ce.getMessage());
            }

            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.warn("Rejection failed - invalid state: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        } catch (IllegalArgumentException e) {
            log.warn("Rejection failed - item not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to reject item: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

