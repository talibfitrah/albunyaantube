package com.albunyaan.tube.dto.sync;

import java.util.List;

public class SyncPageDto<T extends SyncRowDto> {
    private List<T> items;
    private Long nextCursor;   // null when no further pages

    public SyncPageDto() {}

    public SyncPageDto(List<T> items, Long nextCursor) {
        this.items = items;
        this.nextCursor = nextCursor;
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
}
