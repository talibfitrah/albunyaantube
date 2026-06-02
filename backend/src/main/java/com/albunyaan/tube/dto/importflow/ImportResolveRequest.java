package com.albunyaan.tube.dto.importflow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ImportResolveRequest(
        @NotEmpty @Size(max = 200) List<@Valid ImportItem> items) {}
