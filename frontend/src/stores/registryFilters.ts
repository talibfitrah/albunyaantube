import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { fetchAllCategories, type CategoryOption } from '@/services/categories';

export type VideoLengthFilter = 'SHORT' | 'MEDIUM' | 'LONG';
export type VideoDateRangeFilter = 'LAST_24_HOURS' | 'LAST_7_DAYS' | 'LAST_30_DAYS';
export type VideoSortFilter = 'RECENT' | 'POPULAR';

export interface RegistrySearchFilters {
  q?: string;
  categoryId?: string | null;
  videoLength?: VideoLengthFilter | null;
  videoDateRange?: VideoDateRangeFilter | null;
  videoSort?: VideoSortFilter | null;
}

export const useRegistryFiltersStore = defineStore('registry-filters', () => {
  const query = ref('');
  const categoryId = ref<string | null>(null);
  const categories = ref<CategoryOption[]>([]);
  const isCategoryLoading = ref(false);
  const categoryError = ref<string | null>(null);
  const hasFetchedCategories = ref(false);
  const videoLength = ref<VideoLengthFilter | null>(null);
  const videoDateRange = ref<VideoDateRangeFilter | null>(null);
  const videoSort = ref<VideoSortFilter | null>(null);

  const trimmedQuery = computed(() => query.value.trim());

  const searchParams = computed<RegistrySearchFilters>(() => ({
    q: trimmedQuery.value ? trimmedQuery.value : undefined,
    categoryId: categoryId.value ?? undefined,
    videoLength: videoLength.value ?? undefined,
    videoDateRange: videoDateRange.value ?? undefined,
    videoSort: videoSort.value ?? undefined
  }));

  const hasActiveFilters = computed(
    () =>
      Boolean(trimmedQuery.value || categoryId.value || videoLength.value || videoDateRange.value || videoSort.value)
  );

  async function fetchCategories(force = false): Promise<void> {
    if (isCategoryLoading.value) {
      return;
    }
    if (hasFetchedCategories.value && !force) {
      return;
    }

    isCategoryLoading.value = true;
    categoryError.value = null;
    try {
      categories.value = await fetchAllCategories();
      hasFetchedCategories.value = true;
    } catch (err) {
      categoryError.value = err instanceof Error ? err.message : String(err);
    } finally {
      isCategoryLoading.value = false;
    }
  }

  function setQuery(next: string): void {
    query.value = next;
  }

  function setCategoryId(next: string | null | undefined): void {
    categoryId.value = next ?? null;
  }

  function setVideoLength(next: VideoLengthFilter | null | undefined): void {
    videoLength.value = next ?? null;
  }

  function setVideoDateRange(next: VideoDateRangeFilter | null | undefined): void {
    videoDateRange.value = next ?? null;
  }

  function setVideoSort(next: VideoSortFilter | null | undefined): void {
    videoSort.value = next ?? null;
  }

  function reset(): void {
    query.value = '';
    categoryId.value = null;
    videoLength.value = null;
    videoDateRange.value = null;
    videoSort.value = null;
  }

  return {
    query,
    categoryId,
    categories,
    isCategoryLoading,
    categoryError,
    hasFetchedCategories,
    videoLength,
    videoDateRange,
    videoSort,
    searchParams,
    hasActiveFilters,
    fetchCategories,
    setQuery,
    setCategoryId,
    setVideoLength,
    setVideoDateRange,
    setVideoSort,
    reset
  };
});
