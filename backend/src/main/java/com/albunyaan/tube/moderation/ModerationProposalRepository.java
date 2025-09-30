package com.albunyaan.tube.moderation;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ModerationProposalRepository extends JpaRepository<ModerationProposal, UUID> {

    @Query(
        """
        select p from ModerationProposal p
        where (:status is null or p.status = :status)
          and (
            :createdAtCursor is null or p.createdAt > :createdAtCursor
            or (p.createdAt = :createdAtCursor and p.id > :idCursor)
          )
        order by p.createdAt asc, p.id asc
        """
    )
    List<ModerationProposal> findPage(
        @Param("status") ModerationProposalStatus status,
        @Param("createdAtCursor") OffsetDateTime createdAtCursor,
        @Param("idCursor") UUID idCursor,
        Pageable pageable
    );

    long countByStatus(ModerationProposalStatus status);
}
