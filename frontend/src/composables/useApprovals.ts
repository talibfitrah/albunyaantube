/**
 * Approvals Composable
 * Domain logic for content approval workflow
 */

import { computed, ref } from 'vue';
import { fetchPendingApprovals, approveItem, rejectItem } from '@/services/approvalService';
import { mapPendingApprovalToUi, sortApprovals } from '@/utils/approvalTransformers';
import type { PendingApproval } from '@/utils/approvalTransformers';
import { toast } from '@/utils/toast';

export type ContentTypeFilter = 'all' | 'channels' | 'playlists' | 'videos';
export type SortFilter = 'oldest' | 'newest';

export interface ApprovalsFilters {
  type: ContentTypeFilter;
  category: string;
  sort: SortFilter;
}

export function useApprovals(initialFilters?: Partial<ApprovalsFilters>) {
  // Filter state
  const contentType = ref<ContentTypeFilter>(initialFilters?.type ?? 'all');
  const categoryFilter = ref(initialFilters?.category ?? '');
  const sortFilter = ref<SortFilter>(initialFilters?.sort ?? 'oldest');

  // Data state
  const approvals = ref<PendingApproval[]>([]);
  const isLoading = ref(false);
  const error = ref<string | null>(null);
  const processingId = ref<string | null>(null);

  // Computed
  const filters = computed<ApprovalsFilters>(() => ({
    type: contentType.value,
    category: categoryFilter.value,
    sort: sortFilter.value
  }));

  const totalPending = computed(() => approvals.value.length);

  /**
   * Load approvals from API with current filters
   */
  async function loadApprovals(): Promise<void> {
    isLoading.value = true;
    error.value = null;

    try {
      const response = await fetchPendingApprovals({
        type: contentType.value,
        category: categoryFilter.value || undefined
      });

      // Transform DTOs to UI models
      const mapped = response.data.map(mapPendingApprovalToUi);

      // Apply sorting
      approvals.value = sortApprovals(mapped, sortFilter.value);
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to load approvals';
      throw err;
    } finally {
      isLoading.value = false;
    }
  }

  /**
   * Set a filter and reload
   */
  function setFilter<K extends keyof ApprovalsFilters>(key: K, value: ApprovalsFilters[K]): void {
    if (key === 'type') contentType.value = value as ContentTypeFilter;
    else if (key === 'category') categoryFilter.value = value as string;
    else if (key === 'sort') sortFilter.value = value as SortFilter;
  }

  /**
   * Approve an item
   */
  async function approve(item: PendingApproval): Promise<void> {
    if (processingId.value) return;

    processingId.value = item.id;
    try {
      await approveItem(item.id);
      toast.success(`${capitalize(item.type)} approved successfully`);
      await loadApprovals();
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to approve';
      throw err;
    } finally {
      processingId.value = null;
    }
  }

  /**
   * Reject an item
   */
  async function reject(item: PendingApproval, reason: string): Promise<void> {
    if (processingId.value) return;

    processingId.value = item.id;
    try {
      await rejectItem(item.id, reason);
      toast.success(`${capitalize(item.type)} rejected`);
      await loadApprovals();
    } catch (err) {
      error.value = err instanceof Error ? err.message : 'Failed to reject';
      throw err;
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

function capitalize(str: string): string {
  return str.charAt(0).toUpperCase() + str.slice(1);
}
