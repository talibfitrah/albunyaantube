package com.albunyaan.tube.registry.dto;

import java.util.List;

public record CursorPage<T>(
    List<T> data,
    CursorPageInfo pageInfo
) {}

