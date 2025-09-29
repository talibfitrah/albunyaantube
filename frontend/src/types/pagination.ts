export interface CursorPageInfo {
  cursor: string | null;
  nextCursor: string | null;
  hasNext: boolean;
  limit?: number;
}

export interface CursorPage<T> {
  data: T[];
  pageInfo: CursorPageInfo;
}
