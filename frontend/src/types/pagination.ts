/**
 * Pagination metadata from the API.
 *
 * Wire format from backend:
 * {
 *   "nextCursor": "string|null",
 *   "hasNext": boolean
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
}

/**
 * Generic cursor-paginated response from the API.
 */
export interface CursorPage<T> {
  data: T[];
  pageInfo: CursorPageInfo;
}
