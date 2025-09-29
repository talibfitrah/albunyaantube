package com.albunyaan.tube.moderation;

import com.albunyaan.tube.category.Category;
import com.albunyaan.tube.common.AuditableEntity;
import com.albunyaan.tube.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "moderation_proposal")
public class ModerationProposal extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private ModerationProposalKind kind;

    @Column(name = "yt_id", nullable = false, length = 64)
    private String ytId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ModerationProposalStatus status = ModerationProposalStatus.PENDING;

    @Column(name = "notes")
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proposer_id")
    private User proposer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by_id")
    private User decidedBy;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    @ManyToMany
    @JoinTable(
        name = "moderation_proposal_suggested_category",
        joinColumns = @JoinColumn(name = "proposal_id"),
        inverseJoinColumns = @JoinColumn(name = "category_id")
    )
    @OrderColumn(name = "position")
    private final List<Category> suggestedCategories = new ArrayList<>();

    protected ModerationProposal() {
        // JPA
    }

    public ModerationProposal(
        ModerationProposalKind kind,
        String ytId,
        List<Category> suggestedCategories,
        String notes,
        User proposer
    ) {
        this.kind = kind;
        this.ytId = ytId;
        this.notes = notes;
        this.proposer = proposer;
        updateSuggestedCategories(suggestedCategories);
    }

    public UUID getId() {
        return id;
    }

    public ModerationProposalKind getKind() {
        return kind;
    }

    public String getYtId() {
        return ytId;
    }

    public ModerationProposalStatus getStatus() {
        return status;
    }

    public List<Category> getSuggestedCategories() {
        return Collections.unmodifiableList(suggestedCategories);
    }

    public String getNotes() {
        return notes;
    }

    public User getProposer() {
        return proposer;
    }

    public User getDecidedBy() {
        return decidedBy;
    }

    public OffsetDateTime getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public void updateSuggestedCategories(List<Category> categories) {
        this.suggestedCategories.clear();
        if (categories != null) {
            this.suggestedCategories.addAll(categories);
        }
    }

    public void approve(User actor) {
        this.status = ModerationProposalStatus.APPROVED;
        this.decidedBy = actor;
        this.decidedAt = OffsetDateTime.now();
        this.decisionReason = null;
    }

    public void reject(User actor, String reason) {
        this.status = ModerationProposalStatus.REJECTED;
        this.decidedBy = actor;
        this.decidedAt = OffsetDateTime.now();
        this.decisionReason = reason;
    }

    public void markPending() {
        this.status = ModerationProposalStatus.PENDING;
        this.decidedBy = null;
        this.decidedAt = null;
        this.decisionReason = null;
    }
}
