package com.albunyaan.tube.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditEntry, UUID> {

    @Query(
        """
        select entry from AuditEntry entry
        where (:createdAtCursor is null or entry.createdAt < :createdAtCursor
            or (entry.createdAt = :createdAtCursor and entry.id < :idCursor))
        order by entry.createdAt desc, entry.id desc
        """
    )
    List<AuditEntry> findPage(
        @Param("createdAtCursor") OffsetDateTime createdAtCursor,
        @Param("idCursor") UUID idCursor,
        Pageable pageable
    );
}
