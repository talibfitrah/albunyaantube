import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import { fetchAllCategories, type CategoryOption } from '@/services/categories';

export interface RegistrySearchFilters {
  q?: string;
  categoryId?: string | null;
}

export const useRegistryFiltersStore = defineStore('registry-filters', () => {
  const query = ref('');
  const categoryId = ref<string | null>(null);
  const categories = ref<CategoryOption[]>([]);
  const isCategoryLoading = ref(false);
  const categoryError = ref<string | null>(null);
  const hasFetchedCategories = ref(false);

  const trimmedQuery = computed(() => query.value.trim());

  const searchParams = computed<RegistrySearchFilters>(() => ({
    q: trimmedQuery.value ? trimmedQuery.value : undefined,
    categoryId: categoryId.value ?? undefined
  }));

  const hasActiveFilters = computed(() => Boolean(trimmedQuery.value || categoryId.value));

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

  function reset(): void {
    query.value = '';
    categoryId.value = null;
  }

  return {
    query,
    categoryId,
    categories,
    isCategoryLoading,
    categoryError,
    hasFetchedCategories,
    searchParams,
    hasActiveFilters,
    fetchCategories,
    setQuery,
    setCategoryId,
    reset
  };
});
