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

  it('updates query and filter state', () => {
    const store = useRegistryFiltersStore();
    store.setQuery('  halal ');
    store.setCategoryId('quran');
    store.setVideoLength('SHORT');
    store.setVideoDateRange('LAST_7_DAYS');
    store.setVideoSort('POPULAR');

    expect(store.query).toBe('  halal ');
    expect(store.categoryId).toBe('quran');
    expect(store.videoLength).toBe('SHORT');
    expect(store.videoDateRange).toBe('LAST_7_DAYS');
    expect(store.videoSort).toBe('POPULAR');
    expect(store.searchParams).toEqual({
      q: 'halal',
      categoryId: 'quran',
      videoLength: 'SHORT',
      videoDateRange: 'LAST_7_DAYS',
      videoSort: 'POPULAR'
    });
    expect(store.hasActiveFilters).toBe(true);
  });

  it('resets filters to defaults', () => {
    const store = useRegistryFiltersStore();
    store.setQuery('value');
    store.setCategoryId('cat-1');
    store.setVideoLength('MEDIUM');
    store.setVideoDateRange('LAST_24_HOURS');
    store.setVideoSort('RECENT');

    store.reset();

    expect(store.query).toBe('');
    expect(store.categoryId).toBeNull();
    expect(store.videoLength).toBeNull();
    expect(store.videoDateRange).toBeNull();
    expect(store.videoSort).toBeNull();
    expect(store.searchParams).toEqual({
      q: undefined,
      categoryId: undefined,
      videoLength: undefined,
      videoDateRange: undefined,
      videoSort: undefined
    });
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
