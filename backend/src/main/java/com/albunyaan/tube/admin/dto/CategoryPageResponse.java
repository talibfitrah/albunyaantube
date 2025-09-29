package com.albunyaan.tube.admin.dto;

import java.util.List;

public record CategoryPageResponse(List<CategoryResponse> data, PageInfo pageInfo) {
    public record PageInfo(String cursor, String nextCursor, boolean hasNext, Integer limit) {}
}
