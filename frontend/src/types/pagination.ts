/**
 * Pagination metadata from the API.
 *
 * Wire format from backend:
 * {
 *   "nextCursor": "string|null",
 *   "hasNext": boolean,
 *   "totalCount": number|null,
 *   "truncated": boolean|null
 * }
 *
 * The cursor and limit fields are optional - maintained client-side
 * or can be computed from the request parameters.
 */
export interface CursorPageInfo {
  nextCursor: string | null;
  hasNext: boolean;
  /** Current cursor - optional, maintained client-side for history */
  cursor?: string | null;
  /** Page size - optional, maintained client-side */
  limit?: number;
  /** Total count of items matching current filters - optional, returned by some endpoints */
  totalCount?: number | null;
  /**
   * Indicates whether results were truncated due to safety limits.
   * When true, the totalCount and results may be incomplete.
   * Used by workspace exclusions aggregation to signal when hard limits have been hit.
   */
  truncated?: boolean | null;
}

/**
 * Generic cursor-paginated response from the API.
 */
export interface CursorPage<T> {
  data: T[];
  pageInfo: CursorPageInfo;
}
