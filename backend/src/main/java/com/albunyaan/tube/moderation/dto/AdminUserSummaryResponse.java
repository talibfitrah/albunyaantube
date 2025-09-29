package com.albunyaan.tube.moderation.dto;

import com.albunyaan.tube.user.RoleCode;
import com.albunyaan.tube.user.User;
import com.albunyaan.tube.user.UserStatus;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public record AdminUserSummaryResponse(
    java.util.UUID id,
    String email,
    String displayName,
    List<RoleCode> roles,
    UserStatus status,
    OffsetDateTime lastLoginAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static AdminUserSummaryResponse fromUser(User user) {
        var roles = user
            .getRoles()
            .stream()
            .map(role -> role.getCode())
            .sorted(Comparator.comparing(Enum::name))
            .collect(Collectors.toList());
        return new AdminUserSummaryResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            roles,
            user.getStatus(),
            null,
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }
}
