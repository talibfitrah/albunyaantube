package com.albunyaan.tube.moderation;

import com.albunyaan.tube.moderation.dto.CategoryTagResponse;
import com.albunyaan.tube.moderation.dto.ModerationProposalCreateRequest;
import com.albunyaan.tube.moderation.dto.ModerationProposalPageResponse;
import com.albunyaan.tube.moderation.dto.ModerationProposalRejectRequest;
import com.albunyaan.tube.moderation.dto.ModerationProposalResponse;
import com.albunyaan.tube.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/moderation/proposals", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ModerationProposalController {

    private final ModerationProposalService moderationProposalService;

    public ModerationProposalController(ModerationProposalService moderationProposalService) {
        this.moderationProposalService = moderationProposalService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ModerationProposalResponse> createProposal(
        @AuthenticationPrincipal User currentUser,
        @Valid @RequestBody ModerationProposalCreateRequest request
    ) {
        var proposal = moderationProposalService.createProposal(
            currentUser,
            request.kind(),
            request.ytId(),
            request.suggestedCategories(),
            request.notes()
        );
        var response = ModerationProposalResponse.fromProposal(proposal, toCategoryTags(proposal));
        return ResponseEntity
            .created(URI.create("/api/v1/moderation/proposals/" + response.id()))
            .body(response);
    }

    @GetMapping
    public ResponseEntity<ModerationProposalPageResponse> listProposals(
        @AuthenticationPrincipal User currentUser,
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(name = "status", required = false) ModerationProposalStatus status
    ) {
        var page = moderationProposalService.listProposals(cursor, limit, status);
        var data = page
            .proposals()
            .stream()
            .map(proposal -> ModerationProposalResponse.fromProposal(proposal, toCategoryTags(proposal)))
            .toList();
        var pageInfo = new ModerationProposalPageResponse.PageInfo(
            page.cursor(),
            page.nextCursor(),
            page.hasNext(),
            page.limit()
        );
        return ResponseEntity.ok(new ModerationProposalPageResponse(data, pageInfo));
    }

    @PostMapping(path = "/{id}/approve")
    public ResponseEntity<ModerationProposalResponse> approveProposal(
        @AuthenticationPrincipal User currentUser,
        @PathVariable("id") UUID id
    ) {
        var proposal = moderationProposalService.approveProposal(id, currentUser);
        var response = ModerationProposalResponse.fromProposal(proposal, toCategoryTags(proposal));
        return ResponseEntity.ok(response);
    }

    @PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ModerationProposalResponse> rejectProposal(
        @AuthenticationPrincipal User currentUser,
        @PathVariable("id") UUID id,
        @Valid @RequestBody(required = false) ModerationProposalRejectRequest request
    ) {
        var proposal = moderationProposalService.rejectProposal(
            id,
            currentUser,
            request != null ? request.reason() : null
        );
        var response = ModerationProposalResponse.fromProposal(proposal, toCategoryTags(proposal));
        return ResponseEntity.ok(response);
    }

    private List<CategoryTagResponse> toCategoryTags(ModerationProposal proposal) {
        return moderationProposalService
            .toCategoryTags(proposal.getSuggestedCategories())
            .stream()
            .map(tag -> new CategoryTagResponse(tag.id(), tag.label()))
            .toList();
    }
}
