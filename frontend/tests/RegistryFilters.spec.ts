import '@testing-library/jest-dom';
import { fireEvent, render, screen } from '@testing-library/vue';
import { createI18n } from 'vue-i18n';
import { createPinia, setActivePinia } from 'pinia';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import RegistryFilters from '@/components/registry/RegistryFilters.vue';
import { useRegistryFiltersStore } from '@/stores/registryFilters';
import { messages } from '@/locales/messages';

const fetchAllCategoriesMock = vi.fn();

vi.mock('@/services/categories', () => ({
  fetchAllCategories: (...args: unknown[]) => fetchAllCategoriesMock(...args)
}));

function buildI18n() {
  return createI18n({
    legacy: false,
    locale: 'en',
    messages
  });
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

describe('RegistryFilters', () => {
  let pinia: ReturnType<typeof createPinia>;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    fetchAllCategoriesMock.mockReset();
  });

  it('shows skeleton while categories are loading', async () => {
    let resolveCategories: ((value: unknown) => void) | undefined;
    fetchAllCategoriesMock.mockReturnValue(
      new Promise(resolve => {
        resolveCategories = resolve;
      })
    );

    render(RegistryFilters, {
      global: {
        plugins: [buildI18n(), pinia]
      }
    });

    const skeleton = await screen.findByTestId('category-skeleton');
    expect(skeleton).toBeInTheDocument();

    resolveCategories?.([
      { id: 'cat-1', slug: 'quran', label: 'Quran' }
    ]);
    await flushPromises();
    await screen.findByRole('combobox');

    expect(screen.queryByTestId('category-skeleton')).toBeNull();
  });

  it('renders category options and reflects store selection', async () => {
    fetchAllCategoriesMock.mockResolvedValue([
      { id: 'cat-1', slug: 'quran', label: 'Quran' }
    ]);
    const store = useRegistryFiltersStore();

    render(RegistryFilters, {
      global: {
        plugins: [buildI18n(), pinia]
      }
    });

    await screen.findByRole('option', { name: 'Quran' });
    const select = await screen.findByRole('combobox');

    store.setCategoryId('quran');
    await flushPromises();

    expect((select as HTMLSelectElement).value).toBe('quran');
    expect(fetchAllCategoriesMock).toHaveBeenCalledTimes(1);
  });

  it('clears the query when pressing the clear button', async () => {
    fetchAllCategoriesMock.mockResolvedValue([]);

    render(RegistryFilters, {
      global: {
        plugins: [buildI18n(), pinia]
      }
    });

    const input = screen.getByRole('searchbox', { name: 'Search' });
    await fireEvent.update(input, 'testing');
    await flushPromises();

    const clearButton = screen.getByRole('button', { name: 'Clear search' });
    await fireEvent.click(clearButton);
    await flushPromises();

    const store = useRegistryFiltersStore();
    expect(store.query).toBe('');
  });
});
