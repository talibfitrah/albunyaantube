import { setActivePinia, createPinia } from 'pinia';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useRegistryFiltersStore } from '@/stores/registryFilters';

const fetchAllCategoriesMock = vi.fn();

vi.mock('@/services/categories', () => ({
  fetchAllCategories: (...args: unknown[]) => fetchAllCategoriesMock(...args)
}));

describe('useRegistryFiltersStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    fetchAllCategoriesMock.mockReset();
  });

  it('updates query and category state', () => {
    const store = useRegistryFiltersStore();
    store.setQuery('  halal ');
    store.setCategoryId('quran');

    expect(store.query).toBe('  halal ');
    expect(store.categoryId).toBe('quran');
    expect(store.searchParams).toEqual({ q: 'halal', categoryId: 'quran' });
    expect(store.hasActiveFilters).toBe(true);
  });

  it('resets filters to defaults', () => {
    const store = useRegistryFiltersStore();
    store.setQuery('value');
    store.setCategoryId('cat-1');

    store.reset();

    expect(store.query).toBe('');
    expect(store.categoryId).toBeNull();
    expect(store.searchParams).toEqual({ q: undefined, categoryId: undefined });
    expect(store.hasActiveFilters).toBe(false);
  });

  it('fetches categories once and caches results', async () => {
    const store = useRegistryFiltersStore();
    fetchAllCategoriesMock.mockResolvedValue([
      { id: 'cat-1', slug: 'quran', label: 'Quran' }
    ]);

    expect(store.isCategoryLoading).toBe(false);
    await store.fetchCategories();

    expect(fetchAllCategoriesMock).toHaveBeenCalledTimes(1);
    expect(store.isCategoryLoading).toBe(false);
    expect(store.categories).toHaveLength(1);
    expect(store.hasFetchedCategories).toBe(true);

    await store.fetchCategories();
    expect(fetchAllCategoriesMock).toHaveBeenCalledTimes(1);
  });

  it('captures errors while loading categories', async () => {
    const store = useRegistryFiltersStore();
    const error = new Error('network issue');
    fetchAllCategoriesMock.mockRejectedValueOnce(error);

    await store.fetchCategories(true);

    expect(store.categoryError).toBe('network issue');
    expect(store.categories).toHaveLength(0);
    expect(store.hasFetchedCategories).toBe(false);
  });
});
