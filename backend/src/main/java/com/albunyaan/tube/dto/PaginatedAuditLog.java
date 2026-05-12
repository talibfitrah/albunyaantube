package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.AuditLog;

import java.util.List;

/**
 * Plan F (ADMIN-USER-01, F8) — cursor-paginated audit log page.
 * nextCursor is null on the last page.
 */
public class PaginatedAuditLog {
    private final List<AuditLog> items;
    private final String nextCursor;

    public PaginatedAuditLog(List<AuditLog> items, String nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
    }

    public List<AuditLog> getItems() { return items; }
    public String getNextCursor() { return nextCursor; }
}
