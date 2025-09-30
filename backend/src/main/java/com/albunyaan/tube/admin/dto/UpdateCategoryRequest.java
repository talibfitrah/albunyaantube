package com.albunyaan.tube.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

public record UpdateCategoryRequest(
    @Pattern(regexp = "^[a-z0-9-]{2,40}$")
    String slug,
    Map<
        @Pattern(regexp = "^[a-z]{2,8}(?:-[a-z]{2,8})?$") String,
        @NotBlank @Size(max = 80) String
    > name,
    Map<
        @Pattern(regexp = "^[a-z]{2,8}(?:-[a-z]{2,8})?$") String,
        @NotBlank @Size(max = 240) String
    > description,
    @Valid
    List<SubcategoryRequest> subcategories
) {}
