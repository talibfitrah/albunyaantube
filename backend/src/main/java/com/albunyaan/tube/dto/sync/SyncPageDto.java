package com.albunyaan.tube.dto.sync;

import java.util.List;

public class SyncPageDto<T extends SyncRowDto> {
    private List<T> items;
    private Long nextCursor;   // null when no further pages
    /**
     * Compound-cursor tiebreaker: the docId of the last returned row, paired
     * with {@link #nextCursor}. Lets the next pull use
     * {@code startAfter(ts, id)} so rows tied on the same millisecond
     * boundary don't drop (cubic R3/R4 P1).
     */
    private String nextCursorId;

    public SyncPageDto() {}

    public SyncPageDto(List<T> items, Long nextCursor) {
        this(items, nextCursor, null);
    }

    public SyncPageDto(List<T> items, Long nextCursor, String nextCursorId) {
        this.items = items;
        this.nextCursor = nextCursor;
        this.nextCursorId = nextCursorId;
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> v) {
        this.items = v;
    }

    public Long getNextCursor() {
        return nextCursor;
    }

    public void setNextCursor(Long v) {
        this.nextCursor = v;
    }

    public String getNextCursorId() {
        return nextCursorId;
    }

    public void setNextCursorId(String v) {
        this.nextCursorId = v;
    }
}
