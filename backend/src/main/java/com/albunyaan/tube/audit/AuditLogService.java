package com.albunyaan.tube.audit;

import com.albunyaan.tube.audit.dto.AuditActorDto;
import com.albunyaan.tube.audit.dto.AuditEntityDto;
import com.albunyaan.tube.audit.dto.AuditEntryDto;
import com.albunyaan.tube.common.TraceContext;
import com.albunyaan.tube.registry.dto.CursorPage;
import com.albunyaan.tube.registry.dto.CursorPageInfo;
import com.albunyaan.tube.user.Role;
import com.albunyaan.tube.user.User;
import com.albunyaan.tube.user.UserRepository;
import jakarta.transaction.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
public class AuditLogService {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public AuditLogService(AuditLogRepository auditLogRepository, UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    public AuditEntry recordEntry(
        AuditAction action,
        AuditResourceType resourceType,
        String resourceId,
        String resourceSlug,
        User actor,
        Map<String, Object> metadata
    ) {
        var actorRoles = actor != null
            ? actor
                .getRoles()
                .stream()
                .map(Role::getCode)
                .map(Enum::name)
                .collect(Collectors.toCollection(LinkedHashSet::new))
            : Set.<String>of();
        var entry = new AuditEntry(
            action,
            resourceType,
            resourceId,
            resourceSlug,
            actor != null ? actor.getId() : null,
            actor != null ? actor.getEmail() : null,
            actor != null ? actor.getDisplayName() : null,
            actor != null ? actor.getStatus().name() : null,
            actorRoles,
            metadata != null ? Map.copyOf(metadata) : Map.of(),
            TraceContext.get()
        );
        return auditLogRepository.save(entry);
    }

    @Transactional(Transactional.TxType.SUPPORTS)
    public CursorPage<AuditEntryDto> listEntries(String cursor, int requestedLimit) {
        var limit = normalizeLimit(requestedLimit);
        var decodedCursor = decodeCursor(cursor);
        var pageable = PageRequest.of(0, limit + 1);
        var entries = auditLogRepository.findPage(
            decodedCursor != null ? decodedCursor.createdAt() : null,
            decodedCursor != null ? decodedCursor.id() : null,
            pageable
        );

        var hasNext = entries.size() > limit;
        if (hasNext) {
            entries = new ArrayList<>(entries.subList(0, limit));
        }

        var data = entries.stream().map(this::toDto).toList();
        var nextCursor = hasNext ? Cursor.from(entries.get(entries.size() - 1).getCreatedAt(), entries.get(entries.size() - 1).getId()) : null;
        var pageInfo = new CursorPageInfo(cursor, nextCursor != null ? nextCursor.raw() : null, hasNext, limit);
        return new CursorPage<>(data, pageInfo);
    }

    private AuditEntryDto toDto(AuditEntry entry) {
        var actor = resolveActor(entry);
        var entity = new AuditEntityDto(entry.getResourceType().name(), entry.getResourceId(), entry.getResourceSlug());
        return new AuditEntryDto(
            entry.getId(),
            entry.getAction(),
            actor,
            entity,
            entry.getDetails(),
            entry.getCreatedAt(),
            entry.getTraceId()
        );
    }

    private AuditActorDto resolveActor(AuditEntry entry) {
        if (entry.getActorId() == null) {
            return new AuditActorDto(null, entry.getActorEmail(), entry.getActorDisplayName(), entry.getActorRoles(), entry.getActorStatus(), null, null, null);
        }
        return userRepository
            .findById(entry.getActorId())
            .map(user -> mapUser(entry, user))
            .orElseGet(() -> new AuditActorDto(
                entry.getActorId(),
                entry.getActorEmail(),
                entry.getActorDisplayName(),
                entry.getActorRoles(),
                entry.getActorStatus(),
                null,
                null,
                null
            ));
    }

    private AuditActorDto mapUser(AuditEntry entry, User user) {
        var roles = user
            .getRoles()
            .stream()
            .map(Role::getCode)
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new AuditActorDto(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            roles,
            user.getStatus().name(),
            null,
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }

    private Cursor decodeCursor(String cursor) {
        if (!StringUtils.hasText(cursor)) {
            return null;
        }
        var parts = cursor.split("\\|", 2);
        if (parts.length != 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        }
        return new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]), cursor);
    }

    private int normalizeLimit(int requestedLimit) {
        if (requestedLimit < MIN_LIMIT || requestedLimit > MAX_LIMIT) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Limit must be between %d and %d".formatted(MIN_LIMIT, MAX_LIMIT)
            );
        }
        return requestedLimit;
    }

    private record Cursor(OffsetDateTime createdAt, UUID id, String raw) {
        static Cursor from(OffsetDateTime createdAt, UUID id) {
            return new Cursor(createdAt, id, createdAt.toString() + "|" + id);
        }
    }
}
