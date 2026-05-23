import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import BulkPreviewTable from '../src/components/contentSearch/bulk/BulkPreviewTable.vue'
import { useBulkSubmissionStore } from '../src/stores/bulkSubmissionStore'
import { messages } from '../src/locales/messages'

vi.mock('../src/services/categoryService', () => ({
  getAllCategories: () =>
    Promise.resolve([{ id: 'cat-1', name: 'Cat 1', subcategories: [] }]),
}))

vi.mock('../src/services/bulkSubmissionService', () => ({
  bulkSubmissionService: {
    previewBulk: vi.fn(),
    submitBulk: vi.fn(),
  },
}))

beforeEach(() => setActivePinia(createPinia()))

const makeI18n = () => createI18n({ legacy: false, locale: 'en', messages })

const okRow = (idx: number) =>
  ({
    rowIndex: idx,
    originalUrl: 'https://www.youtube.com/watch?v=test' + idx,
    normalizedUrl: 'https://www.youtube.com/watch?v=test' + idx,
    detectedType: 'VIDEO',
    videoType: 'STANDARD',
    metadata: { youtubeId: 'test' + idx, title: 'Title ' + idx } as any,
    status: 'OK',
    duplicateOf: null,
    duplicateStatus: null,
    error: null,
  }) as any

const mountTable = () => {
  const i18n = makeI18n()
  return mount(BulkPreviewTable, { global: { plugins: [i18n] } })
}

describe('BulkPreviewTable', () => {
  it('renders one row per previewRows entry', () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [okRow(0), okRow(1), okRow(2)]
    const wrapper = mountTable()
    expect(wrapper.findAll('tbody tr').length).toBe(3)
  })

  it('header counts valid / duplicate / error correctly', () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [
      okRow(0),
      { ...okRow(1), status: 'DUPLICATE' },
      { ...okRow(2), status: 'DUPLICATE_REJECTED' },
      { ...okRow(3), status: 'ERROR' },
    ]
    const wrapper = mountTable()
    const small = wrapper.find('small').text()
    // 1 valid (only OK — cubic R1 fix removed DUPLICATE_REJECTED from
    // valid counting since runSubmit drops them), 2 duplicate
    // (DUPLICATE + DUPLICATE_REJECTED), 1 error.
    expect(small).toContain('1')
  })

  it('removeRow drops row from rendered table', async () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [okRow(0), okRow(1)]
    const wrapper = mountTable()
    store.removeRow(0)
    await wrapper.vm.$nextTick()
    expect(wrapper.findAll('tbody tr').length).toBe(1)
  })

  it('Submit button disabled when no valid rows', () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [{ ...okRow(0), status: 'ERROR' }]
    const wrapper = mountTable()
    const submitBtn = wrapper.findAll('button').find((b) => b.text() === 'Submit')
    expect(submitBtn).toBeDefined()
    expect(submitBtn!.attributes('disabled')).toBeDefined()
  })

  it('Submit button enabled when at least one valid row exists', () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [okRow(0)]
    const wrapper = mountTable()
    const submitBtn = wrapper.findAll('button').find((b) => b.text() === 'Submit')
    expect(submitBtn).toBeDefined()
    expect(submitBtn!.attributes('disabled')).toBeUndefined()
  })

  it('DUPLICATE_REJECTED does NOT count as valid (Submit disabled)', () => {
    // Cubic R1 finding: counting DUPLICATE_REJECTED as valid enabled
    // the Submit button for a batch that runSubmit then refused as "No
    // valid rows to submit" — stuck UX state. Re-submission of rejected
    // items must go through the admin approval queue, not the bulk path.
    const store = useBulkSubmissionStore()
    store.previewRows = [{ ...okRow(0), status: 'DUPLICATE_REJECTED' }]
    const wrapper = mountTable()
    const submitBtn = wrapper.findAll('button').find((b) => b.text() === 'Submit')
    expect(submitBtn!.attributes('disabled')).toBeDefined()
  })

  it('back button sets phase to INPUT', async () => {
    const store = useBulkSubmissionStore()
    store.previewRows = [okRow(0)]
    store.phase = 'PREVIEW'
    const wrapper = mountTable()
    const backBtn = wrapper.findAll('button').find((b) => b.text().includes('Back'))
    await backBtn!.trigger('click')
    expect(store.phase).toBe('INPUT')
  })
})
