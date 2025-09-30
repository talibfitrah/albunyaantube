package com.albunyaan.tube.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;

public record SubcategoryRequest(
    UUID id,
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]{2,40}$")
    String slug,
    @NotEmpty
    Map<
        @Pattern(regexp = "^[a-z]{2,8}(?:-[a-z]{2,8})?$") String,
        @NotBlank @Size(max = 80) String
    > name
) {}
