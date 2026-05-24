import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BulkSubmissionTab from '../src/components/contentSearch/bulk/BulkSubmissionTab.vue'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'

vi.mock('../src/services/categoryService', () => ({
  getAllCategories: () => Promise.resolve([{ id: 'cat-1', name: 'Cat 1' }]),
}))

vi.mock('../src/services/bulkSubmissionService', () => ({
  bulkSubmissionService: {
    previewBulk: vi.fn(),
    submitBulk: vi.fn(),
  },
}))

beforeEach(() => setActivePinia(createPinia()))

const $t = (k: string) => k

const mountTab = () =>
  mount(BulkSubmissionTab, {
    global: {
      mocks: { $t },
      stubs: {
        BulkInputView: { name: 'BulkInputView', template: '<div class="stub-input" />' },
        BulkPreviewTable: { name: 'BulkPreviewTable', template: '<div class="stub-preview" />' },
        BulkResultSummary: { name: 'BulkResultSummary', template: '<div class="stub-result" />' },
        BulkFormatHelpModal: { name: 'BulkFormatHelpModal', template: '<div class="stub-help" />' },
      },
    },
  })

describe('BulkSubmissionTab', () => {
  it('renders BulkInputView in INPUT phase', () => {
    const wrapper = mountTab()
    expect(wrapper.findComponent({ name: 'BulkInputView' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'BulkPreviewTable' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'BulkResultSummary' }).exists()).toBe(false)
  })

  it('renders BulkPreviewTable in PREVIEW phase', async () => {
    const store = useBulkSubmissionStore()
    store.phase = 'PREVIEW'
    const wrapper = mountTab()
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'BulkPreviewTable' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'BulkInputView' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'BulkResultSummary' }).exists()).toBe(false)
  })

  it('renders BulkResultSummary in RESULT phase', async () => {
    const store = useBulkSubmissionStore()
    store.phase = 'RESULT'
    store.submitResult = { totalSubmitted: 1, added: 1, failed: 0, results: [] } as any
    const wrapper = mountTab()
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'BulkResultSummary' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'BulkInputView' }).exists()).toBe(false)
    expect(wrapper.findComponent({ name: 'BulkPreviewTable' }).exists()).toBe(false)
  })

  it('shows spinner in LOADING phase', async () => {
    const store = useBulkSubmissionStore()
    store.phase = 'LOADING'
    const wrapper = mountTab()
    await wrapper.vm.$nextTick()
    expect(wrapper.find('.spinner').exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'BulkInputView' }).exists()).toBe(false)
  })

  it('shows error alert when store.error is set', async () => {
    const store = useBulkSubmissionStore()
    store.error = 'Something went wrong'
    const wrapper = mountTab()
    await wrapper.vm.$nextTick()
    const alert = wrapper.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('Something went wrong')
  })

  it('shows BulkFormatHelpModal when show-format-help is emitted', async () => {
    const wrapper = mountTab()
    // Initially not shown
    expect(wrapper.findComponent({ name: 'BulkFormatHelpModal' }).exists()).toBe(false)
    // Emit the event from BulkInputView
    wrapper.findComponent({ name: 'BulkInputView' }).vm.$emit('show-format-help')
    await wrapper.vm.$nextTick()
    expect(wrapper.findComponent({ name: 'BulkFormatHelpModal' }).exists()).toBe(true)
  })
})
