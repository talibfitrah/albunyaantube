package com.albunyaan.tube.moderation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationProposalRepository extends JpaRepository<ModerationProposal, UUID> {

    @EntityGraph(attributePaths = { "proposer", "proposer.roles", "decidedBy", "decidedBy.roles", "suggestedCategories" })
    @Query(
        """
        select p from ModerationProposal p
        where p.status = :status
          and (
            p.createdAt > :createdAtCursor
            or (p.createdAt = :createdAtCursor and p.id > :idCursor)
          )
        order by p.createdAt asc, p.id asc
        """
    )
    List<ModerationProposal> findPageByStatus(
        @Param("status") ModerationProposalStatus status,
        @Param("createdAtCursor") OffsetDateTime createdAtCursor,
        @Param("idCursor") UUID idCursor,
        Pageable pageable
    );

    @EntityGraph(attributePaths = { "proposer", "proposer.roles", "decidedBy", "decidedBy.roles", "suggestedCategories" })
    @Query(
        """
        select p from ModerationProposal p
        where p.createdAt > :createdAtCursor
           or (p.createdAt = :createdAtCursor and p.id > :idCursor)
        order by p.createdAt asc, p.id asc
        """
    )
    List<ModerationProposal> findPageAll(
        @Param("createdAtCursor") OffsetDateTime createdAtCursor,
        @Param("idCursor") UUID idCursor,
        Pageable pageable
    );

    @EntityGraph(attributePaths = { "proposer", "proposer.roles", "decidedBy", "decidedBy.roles", "suggestedCategories" })
    List<ModerationProposal> findByStatusOrderByCreatedAtAscIdAsc(
        ModerationProposalStatus status,
        Pageable pageable
    );

    @EntityGraph(attributePaths = { "proposer", "proposer.roles", "decidedBy", "decidedBy.roles", "suggestedCategories" })
    List<ModerationProposal> findAllByOrderByCreatedAtAscIdAsc(Pageable pageable);

    long countByStatus(ModerationProposalStatus status);
}
