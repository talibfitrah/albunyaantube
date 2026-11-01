import { describe, it, expect } from 'vitest';
import { useCursorPagination } from '@/composables/useCursorPagination';
import type { CursorPage } from '@/types/pagination';

const page = (data: string[]): CursorPage<string> => ({
  data,
  pageInfo: { cursor: null, nextCursor: null, hasNext: false }
});

describe('useCursorPagination', () => {
  it('drops a reply that a newer load replaced, so a slow old filter never wins', async () => {
    const replies: ((p: CursorPage<string>) => void)[] = [];
    const pager = useCursorPagination<string>(() => new Promise(r => replies.push(r)));

    const first = pager.load(null, 'reset');   // e.g. role = ADMIN
    const second = pager.load(null, 'reset');  // then role = MODERATOR
    replies[1](page(['moderator']));
    await second;
    replies[0](page(['admin']));               // the older reply lands last
    await first;

    expect(pager.items.value).toEqual(['moderator']);
    expect(pager.isLoading.value).toBe(false);
  });
});
