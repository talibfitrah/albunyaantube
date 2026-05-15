package com.albunyaan.tube.dto.sync;

import java.util.List;

public class SyncPageDto<T extends SyncRowDto> {
    private List<T> items;
    /**
     * Cursor pointing past the last row in {@link #items}, or null iff the
     * underlying iterator returned zero rows for this query. After SYNC-TAIL-01
     * (Cubic R7 P1) the server mints a cursor for every non-empty page —
     * partial tails included — so clients must keep pulling until they
     * observe a page with {@code items.isEmpty() && nextCursor == null}.
     */
    private Long nextCursor;
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
