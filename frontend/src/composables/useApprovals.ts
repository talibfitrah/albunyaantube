import { computed, ref } from 'vue';
import { getPendingApprovals, approveItem, rejectItem as rejectItemApi } from '@/services/approvalService';
import type { PendingApproval } from '@/services/approvalService';

export type ContentTypeFilter = 'all' | 'channels' | 'playlists' | 'videos';
export type SortFilter = 'oldest' | 'newest';

export interface ApprovalsFilters {
  type: ContentTypeFilter;
  category: string;
  sort: SortFilter;
}

export function useApprovals(initialFilters?: Partial<ApprovalsFilters>) {
  // State
  const contentType = ref<ContentTypeFilter>(initialFilters?.type ?? 'all');
  const categoryFilter = ref(initialFilters?.category ?? '');
  const sortFilter = ref<SortFilter>(initialFilters?.sort ?? 'oldest');

  const approvals = ref<PendingApproval[]>([]);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const processingId = ref<string | null>(null);

  // Computed
  const totalPending = computed(() => approvals.value.length);

  const filters = computed<ApprovalsFilters>(() => ({
    type: contentType.value,
    category: categoryFilter.value,
    sort: sortFilter.value
  }));

  // Actions
  async function loadApprovals(): Promise<void> {
    isLoading.value = true;
    error.value = null;

    try {
      const items = await getPendingApprovals({
        type: contentType.value,
        category: categoryFilter.value || undefined,
        sort: sortFilter.value
      });
      approvals.value = items;
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load approvals';
    } finally {
      isLoading.value = false;
    }
  }

  function setFilter<K extends keyof ApprovalsFilters>(key: K, value: ApprovalsFilters[K]): void {
    if (key === 'type') {
      contentType.value = value as ContentTypeFilter;
    } else if (key === 'category') {
      categoryFilter.value = value as string;
    } else if (key === 'sort') {
      sortFilter.value = value as SortFilter;
    }
  }

  async function approve(item: PendingApproval): Promise<void> {
    if (processingId.value) return;

    processingId.value = item.id;
    error.value = null;

    try {
      await approveItem(item.id, item.type);
      await loadApprovals();
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to approve item';
    } finally {
      processingId.value = null;
    }
  }

  async function reject(item: PendingApproval, reason: string): Promise<void> {
    if (processingId.value) return;

    processingId.value = item.id;
    error.value = null;

    try {
      await rejectItemApi(item.id, item.type, reason);
      await loadApprovals();
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to reject item';
    } finally {
      processingId.value = null;
    }
  }

  return {
    // State
    approvals,
    isLoading,
    error,
    processingId,

    // Filters
    contentType,
    categoryFilter,
    sortFilter,
    filters,

    // Computed
    totalPending,

    // Actions
    loadApprovals,
    setFilter,
    approve,
    reject
  };
}
