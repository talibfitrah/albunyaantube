package com.albunyaan.tube.data.paging

/**
 * Placeholder for a PagingSource that bridges backend cursor pagination with Paging 3.
 * Implementation notes:
 * - Use backend endpoint parameters (`cursor`, `limit`) and map Paging 3 `LoadParams` to backend cursors.
 * - When `LoadType.REFRESH`, pass null cursor to fetch first page; for `APPEND`, forward the `nextCursor` from previous response.
 * - Errors should surface descriptive messages for offline / HTTP failure scenarios.
 */
class CursorPagingSource {
    // TODO: extend PagingSource<Int, ContentItem> once Android module is initialized.
}
