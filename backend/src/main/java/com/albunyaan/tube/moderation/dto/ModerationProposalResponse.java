package com.albunyaan.tube.moderation.dto;

import com.albunyaan.tube.moderation.ModerationProposal;
import com.albunyaan.tube.moderation.ModerationProposalKind;
import com.albunyaan.tube.moderation.ModerationProposalStatus;
import java.time.OffsetDateTime;
import java.util.List;

public record ModerationProposalResponse(
    java.util.UUID id,
    ModerationProposalKind kind,
    String ytId,
    ModerationProposalStatus status,
    List<CategoryTagResponse> suggestedCategories,
    AdminUserSummaryResponse proposer,
    String notes,
    AdminUserSummaryResponse decidedBy,
    OffsetDateTime decidedAt,
    String decisionReason,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static ModerationProposalResponse fromProposal(
        ModerationProposal proposal,
        List<CategoryTagResponse> suggestedCategories
    ) {
        var decidedBy = proposal.getDecidedBy() != null ? AdminUserSummaryResponse.fromUser(proposal.getDecidedBy()) : null;
        return new ModerationProposalResponse(
            proposal.getId(),
            proposal.getKind(),
            proposal.getYtId(),
            proposal.getStatus(),
            suggestedCategories,
            AdminUserSummaryResponse.fromUser(proposal.getProposer()),
            proposal.getNotes(),
            decidedBy,
            proposal.getDecidedAt(),
            proposal.getDecisionReason(),
            proposal.getCreatedAt(),
            proposal.getUpdatedAt()
        );
    }
}
