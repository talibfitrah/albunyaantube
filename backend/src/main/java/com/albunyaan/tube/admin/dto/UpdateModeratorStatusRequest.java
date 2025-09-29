package com.albunyaan.tube.admin.dto;

import com.albunyaan.tube.user.UserStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateModeratorStatusRequest(@NotNull UserStatus status) {}
