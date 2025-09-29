package com.albunyaan.tube.registry.dto;

public record CursorPageInfo(
    String cursor,
    String nextCursor,
    boolean hasNext,
    Integer limit
) {}

