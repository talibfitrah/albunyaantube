package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.ApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
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
public class ApprovalController {

    private static final Logger log = LoggerFactory.getLogger(ApprovalController.class);

    private final ApprovalService approvalService;

    public ApprovalController(ApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    /**
     * GET /api/admin/approvals/pending
     *
     * List pending approvals with filters
     *
     * Query params:
     * - type: CHANNEL|PLAYLIST (optional)
     * - category: category ID (optional)
     * - limit: page size (default 20)
     * - cursor: pagination cursor (optional)
     */
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
        } catch (Exception e) {
            log.error("Failed to get pending approvals", e);
            return ResponseEntity.internalServerError().build();
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

            return ResponseEntity.ok(response);
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

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.warn("Rejection failed - item not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            log.error("Failed to reject item: {}", id, e);
            return ResponseEntity.internalServerError().build();
        }
    }
}

