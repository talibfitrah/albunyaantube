import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BulkInputView from '../src/components/contentSearch/bulk/BulkInputView.vue'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'
import { messages } from '../src/locales/messages'

// Mock category service so the picker doesn't attempt real HTTP
vi.mock('../src/services/categoryService', () => ({
  getAllCategories: () =>
    Promise.resolve([
      { id: 'cat-1', name: 'Category 1', subcategories: [] },
      { id: 'cat-2', name: 'Category 2', subcategories: [] },
    ]),
}))

// Mock bulkSubmissionService so the store's runPreview never makes real HTTP
vi.mock('../src/services/bulkSubmissionService', () => ({
  bulkSubmissionService: {
    previewBulk: vi.fn(),
    submitBulk: vi.fn(),
  },
}))

const mountView = () => {
  const i18n = createI18n({ legacy: false, locale: 'en', messages })
  return mount(BulkInputView, {
    global: {
      plugins: [i18n],
      stubs: {
        // Stub child components to isolate BulkInputView logic
        BulkUrlPasteField: { template: '<div />' },
        BulkFileDropzone: { template: '<div />' },
        BulkDefaultCategoriesPicker: { template: '<div />' },
      },
    },
  })
}

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('BulkInputView', () => {
  it('Parse button is disabled when store has no URLs and no categories', () => {
    const wrapper = mountView()
    const btn = wrapper.find('button.btn-primary')
    expect(btn.attributes('disabled')).toBeDefined()
  })

  it('Parse button is disabled when URLs present but no categories', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = ['https://www.youtube.com/watch?v=AAAAAAAAAAA']
    store.defaultCategoryIds = []
    const wrapper = mountView()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeDefined()
  })

  it('Parse button is disabled when categories present but no URLs', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = []
    store.defaultCategoryIds = ['cat-1']
    const wrapper = mountView()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeDefined()
  })

  it('Parse button is enabled when 1–25 URLs and ≥1 category', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = ['https://www.youtube.com/watch?v=AAAAAAAAAAA']
    store.defaultCategoryIds = ['cat-1']
    const wrapper = mountView()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeUndefined()
  })

  it('Parse button is enabled with exactly 25 URLs and ≥1 category', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = Array.from(
      { length: 25 },
      (_, i) => `https://www.youtube.com/watch?v=${String(i).padStart(11, 'A')}`,
    )
    store.defaultCategoryIds = ['cat-1']
    const wrapper = mountView()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeUndefined()
  })

  it('Parse button is disabled when > 25 URLs even with categories', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = Array.from(
      { length: 26 },
      () => 'https://www.youtube.com/watch?v=AAAAAAAAAAA',
    )
    store.defaultCategoryIds = ['cat-1']
    const wrapper = mountView()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('button.btn-primary').attributes('disabled')).toBeDefined()
  })

  it('clicking the format help button emits showFormatHelp', async () => {
    const wrapper = mountView()
    await wrapper.find('button.btn-link').trigger('click')
    expect(wrapper.emitted('showFormatHelp')).toBeTruthy()
  })

  it('shows store error banner when store.error is set', async () => {
    const store = useBulkSubmissionStore()
    store.error = 'Something went wrong'
    const wrapper = mountView()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.alert-danger').text()).toContain('Something went wrong')
  })

  it('clicking Parse button calls store.runPreview', async () => {
    const store = useBulkSubmissionStore()
    store.parsedUrls = ['https://www.youtube.com/watch?v=AAAAAAAAAAA']
    store.defaultCategoryIds = ['cat-1']
    const spy = vi.spyOn(store, 'runPreview').mockResolvedValue(undefined)
    const wrapper = mountView()
    await wrapper.vm.$nextTick()
    await wrapper.find('button.btn-primary').trigger('click')
    expect(spy).toHaveBeenCalledOnce()
  })
})
