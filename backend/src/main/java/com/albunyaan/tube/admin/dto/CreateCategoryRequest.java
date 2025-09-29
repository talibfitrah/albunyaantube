package com.albunyaan.tube.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record CreateCategoryRequest(
    @NotBlank
    @Pattern(regexp = "^[a-z0-9-]{2,40}$")
    String slug,
    @NotEmpty
    Map<
        @Pattern(regexp = "^[a-z]{2,8}(?:-[a-z]{2,8})?$") String,
        @NotBlank @Size(max = 80) String
    > name,
    Map<
        @Pattern(regexp = "^[a-z]{2,8}(?:-[a-z]{2,8})?$") String,
        @NotBlank @Size(max = 240) String
    > description
) {}
