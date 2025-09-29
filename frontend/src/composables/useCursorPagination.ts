import { computed, ref } from 'vue';
import type { CursorPage, CursorPageInfo } from '@/types/pagination';

type LoadMode = 'reset' | 'append' | 'replace';

type Fetcher<T> = (cursor: string | null, limit: number) => Promise<CursorPage<T>>;

export interface CursorPaginationOptions {
  limit?: number;
}

export function useCursorPagination<T>(fetcher: Fetcher<T>, options: CursorPaginationOptions = {}) {
  const limit = options.limit ?? 20;
  const items = ref<T[]>([]);
  const pageInfo = ref<CursorPageInfo | null>(null);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const history = ref<(string | null)[]>([]);

  async function load(cursor: string | null = null, mode: LoadMode = 'replace'): Promise<void> {
    isLoading.value = true;
    error.value = null;
    try {
      const page = await fetcher(cursor, limit);
      items.value = page.data;
      pageInfo.value = page.pageInfo;

      if (mode === 'reset') {
        history.value = [cursor];
      } else if (mode === 'append') {
        history.value = [...history.value, cursor];
      } else {
        if (history.value.length === 0) {
          history.value = [cursor];
        } else {
          history.value = [...history.value.slice(0, -1), cursor];
        }
      }
    } catch (err) {
      if (err instanceof Error) {
        error.value = err.message;
      } else {
        error.value = 'Unknown error occurred.';
      }
    } finally {
      isLoading.value = false;
    }
  }

  async function next(): Promise<void> {
    if (!pageInfo.value || !pageInfo.value.hasNext) {
      return;
    }
    await load(pageInfo.value.nextCursor ?? null, 'append');
  }

  async function previous(): Promise<void> {
    if (history.value.length <= 1) {
      return;
    }
    const trimmed = history.value.slice(0, -1);
    const previousCursor = trimmed[trimmed.length - 1] ?? null;
    history.value = trimmed;
    await load(previousCursor, 'replace');
  }

  const hasNext = computed(() => pageInfo.value?.hasNext ?? false);
  const hasPrevious = computed(() => history.value.length > 1);

  return {
    items,
    pageInfo,
    isLoading,
    error,
    load,
    next,
    previous,
    hasNext,
    hasPrevious
  };
}
