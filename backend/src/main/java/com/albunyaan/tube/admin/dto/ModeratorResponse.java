package com.albunyaan.tube.admin.dto;

import com.albunyaan.tube.user.User;
import com.albunyaan.tube.user.UserStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ModeratorResponse(
    UUID id,
    String email,
    String displayName,
    UserStatus status,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static ModeratorResponse fromUser(User user) {
        return new ModeratorResponse(
            user.getId(),
            user.getEmail(),
            user.getDisplayName(),
            user.getStatus(),
            user.getCreatedAt(),
            user.getUpdatedAt()
        );
    }
}
