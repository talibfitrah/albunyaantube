package com.albunyaan.tube.moderation.dto;

import java.util.List;

public record ModerationProposalPageResponse(
    List<ModerationProposalResponse> data,
    PageInfo pageInfo
) {
    public record PageInfo(String cursor, String nextCursor, boolean hasNext, int limit) {}
}
